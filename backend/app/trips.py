"""Trip rollups and candidate proposal.

A trip's membership is an explicit set of rows, so everything here is
either help with building that set (`trip_candidates`) or arithmetic over
it once built (`trip_report`).

The proposal is the interesting half. Nothing in the data says which
charges belong to a journey: a date window sweeps up the rent and the
payroll that landed mid-trip, and misses the flights bought in January.
Merchant frequency cannot rescue it either — rent descriptions carry a
per-payment reference, so every rent payment looks like a first, while
the taxi and delivery merchants that frequency *would* flag are exactly
the ones a trip is made of. So this module proposes and explains, the
user confirms, and the two judgements only they can make — which
categories are home life, which get booked ahead — are remembered per
category and reused by every later trip.
"""

from __future__ import annotations

import statistics
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal

from sqlalchemy import and_, func, or_, select
from sqlalchemy.orm import Session

from . import models
from .models import TransactionKind, TripCategoryRule

CENTS = Decimal("0.01")

# How far before a trip to look for things bought ahead. Six months: a
# flight for a May trip is routinely bought in January, which is already
# past four months, and the categories this applies to are opt-in, so a
# wider window costs nothing in noise. Overridable per request.
DEFAULT_LOOKBACK_DAYS = 180

# Standing-charge detection: a bill that landed mid-trip and would have
# landed had you stayed home.
#
# Average spacing alone is not enough. Measured across real and
# synthetic ledgers, a shop you visit every couple of weeks has the same
# average gap as a monthly bill — the difference is that the bill is
# *regular*. Interval spread separates them with room to spare: every
# genuine bill measured came in at or under 0.35, every frequent shop at
# 0.73 or above.
#
# The amount clause catches the one kind that regular spacing misses: a
# travel pass bought whenever the last one runs out, always for exactly
# the same price.
#
# The cadence floor stays load-bearing. Daily merchants — coffee,
# transit fares, delivery — sit near 1, and on a trip those rows are the
# trip, so they must never reach the variance tests at all.
_STANDING_MIN_OCCURRENCES = 4
_STANDING_MIN_CADENCE_DAYS = 5
_STANDING_MAX_CADENCE_DAYS = 40
_STANDING_MAX_INTERVAL_SPREAD = 0.35
_STANDING_MAX_AMOUNT_SPREAD = 0.05

# Buckets a candidate can land in, in the order the review screen shows
# them. Only `proposed` and `pre_booking` arrive pre-selected.
BUCKET_PRE_BOOKING = "pre_booking"
BUCKET_PROPOSED = "proposed"
BUCKET_STANDING_CHARGE = "standing_charge"
BUCKET_HOME_LIFE = "home_life"


# Kinds a trip may count. The candidate search only ever proposes
# expense-kind rows, but rows can also be filed from the Transactions
# page, and that route has to refuse the same things — a paycheck added
# to a trip would be negated into a discount and quietly reduce what the
# trip cost. Refunds and reimbursements are allowed deliberately: a
# refunded trip booking genuinely does net out against it.
COUNTABLE_KINDS = frozenset(
    {
        TransactionKind.expense,
        TransactionKind.uncategorized,
        TransactionKind.refund,
        TransactionKind.reimbursement,
    }
)


def _merchant_key():
    return func.coalesce(models.Transaction.merchant, models.Transaction.description_normalized)


def _spendish():
    """Rows that could be trip spend at all.

    Expense-kind, plus uncategorized outflows so a trip can be built
    before the rows have been filed. Everything else — transfers, card
    payments, contributions, income, refunds — is money moving rather
    than money spent, and sweeping it in is what makes a naive
    date-window trip report nonsense.
    """
    return or_(
        models.Transaction.kind == TransactionKind.expense,
        and_(
            models.Transaction.kind == TransactionKind.uncategorized,
            models.Transaction.amount < 0,
        ),
    )


def spend_amount(
    amount: Decimal,
    personal_share: Decimal | None = None,
    trip_share: Decimal | None = None,
) -> Decimal:
    """Money out, positive, after both share axes.

    Follows `budgets.py`: amounts are stored outflow-negative, scaled by
    the roommate share, then negated so a cost reads positive. The trip's
    own share is a second, independent fraction — half a drugstore order
    on a trip that was itself split with a roommate counts a quarter.

    Quantized per row so the rows a trip shows add up to the total it
    reports; a trip whose parts do not sum to its total is worse than no
    trip at all.
    """
    effective = amount if personal_share is None else amount * Decimal(personal_share)
    if trip_share is not None:
        effective = effective * Decimal(trip_share)
    return (-effective).quantize(CENTS)


def _spread(values: list[float]) -> float:
    """Coefficient of variation — spread as a fraction of the mean.

    Scale-free, so one threshold works for a $9 subscription and a $2,400
    rent payment alike.
    """
    if len(values) < 2:
        return 0.0
    mean = statistics.fmean(values)
    if mean == 0:
        return 0.0
    return statistics.pstdev(values) / abs(mean)


def _standing_charges(db: Session, merchants: set[str]) -> dict[str, int]:
    """Of these merchants, the ones that bill on a schedule.

    Scoped to the merchants actually in the window, so this reads a
    handful of histories rather than every merchant the ledger has ever
    seen.
    """
    if not merchants:
        return {}
    key = _merchant_key().label("k")
    stmt = (
        select(key, models.Transaction.date, models.Transaction.amount)
        .where(key.in_(merchants), models.Transaction.is_excluded_from_totals.is_(False))
        .order_by(key, models.Transaction.date)
    )
    history: dict[str, list[tuple[date, Decimal]]] = defaultdict(list)
    for merchant, when, amount in db.execute(stmt):
        history[merchant].append((when, amount))

    out: dict[str, int] = {}
    for merchant, rows in history.items():
        if len(rows) < _STANDING_MIN_OCCURRENCES:
            continue
        days = [when.toordinal() for when, _ in rows]
        cadence = (days[-1] - days[0]) / (len(days) - 1)
        if not _STANDING_MIN_CADENCE_DAYS <= cadence <= _STANDING_MAX_CADENCE_DAYS:
            continue
        gaps = [float(b - a) for a, b in zip(days, days[1:], strict=False)]
        amounts = [abs(float(amount)) for _, amount in rows]
        regular_timing = _spread(gaps) <= _STANDING_MAX_INTERVAL_SPREAD
        identical_amount = _spread(amounts) <= _STANDING_MAX_AMOUNT_SPREAD
        if regular_timing or identical_amount:
            out[merchant] = round(cadence)
    return out


def _category_policies(db: Session) -> dict[int, TripCategoryRule]:
    return {
        category_id: policy
        for category_id, policy in db.execute(
            select(models.TripCategoryPolicy.category_id, models.TripCategoryPolicy.policy)
        )
    }


def _month_presence(db: Session, category_ids: set[int]) -> dict[int, tuple[int, int]]:
    """Per category: months it was charged in, and months of data overall.

    The one honest hint for the home-life question. Rent shows up in
    every month there is data; a restaurant does not. The user still
    decides — this only sorts the decision so the obvious ones are
    obvious.
    """
    if not category_ids:
        return {}
    month = func.strftime("%Y-%m", models.Transaction.date)
    total_months = db.scalar(select(func.count(func.distinct(month)))) or 0
    stmt = (
        select(models.Transaction.category_id, func.count(func.distinct(month)))
        .where(
            models.Transaction.category_id.in_(category_ids),
            models.Transaction.kind == TransactionKind.expense,
        )
        .group_by(models.Transaction.category_id)
    )
    return {
        category_id: (int(charged), int(total_months)) for category_id, charged in db.execute(stmt)
    }


def _bucket_for(
    tx: models.Transaction,
    in_lookback: bool,
    merchant: str | None,
    skip_categories: set[int],
    standing: dict[str, int],
) -> tuple[str, str]:
    """Which group a candidate lands in, and why — the reason is shown."""
    if in_lookback:
        return BUCKET_PRE_BOOKING, "bought before the trip, in a category you book ahead"
    if tx.category_id is not None and tx.category_id in skip_categories:
        return (
            BUCKET_HOME_LIFE,
            "a category you keep out of trips; it bills whether you travel or not",
        )
    if merchant is not None and merchant in standing:
        return (
            BUCKET_STANDING_CHARGE,
            f"bills about every {standing[merchant]} days, so it would have billed at home too",
        )
    return BUCKET_PROPOSED, "spending inside the trip's dates"


def trip_candidates(
    db: Session,
    trip: models.Trip,
    lookback_days: int = DEFAULT_LOOKBACK_DAYS,
) -> dict:
    """Rows that might belong to this trip, grouped and explained.

    Repeats collapse into one group per merchant and category, because
    seven taxi rides is one decision, not seven.
    """
    policies = _category_policies(db)
    skip_categories = {cid for cid, rule in policies.items() if rule == TripCategoryRule.skip}
    advance_categories = {cid for cid, rule in policies.items() if rule == TripCategoryRule.advance}

    lookback_start = trip.start_date - timedelta(days=max(0, lookback_days))
    key = _merchant_key().label("merchant_key")
    in_window = and_(
        models.Transaction.date >= trip.start_date,
        models.Transaction.date <= trip.end_date,
    )
    in_lookback = and_(
        models.Transaction.date >= lookback_start,
        models.Transaction.date < trip.start_date,
        models.Transaction.category_id.in_(advance_categories),
    )
    stmt = (
        select(
            models.Transaction,
            models.TransactionSplit.personal_share,
            models.Category.name,
            key,
        )
        .join(
            models.TransactionSplit,
            models.TransactionSplit.transaction_id == models.Transaction.id,
            isouter=True,
        )
        .join(
            models.Category,
            models.Category.id == models.Transaction.category_id,
            isouter=True,
        )
        .where(
            models.Transaction.is_excluded_from_totals.is_(False),
            # A linked pair already cancels itself out of spending; one
            # half of a refund is not a trip cost.
            models.Transaction.transfer_pair_id.is_(None),
            models.Transaction.id.not_in(select(models.TripExpense.transaction_id)),
            _spendish(),
            or_(in_window, in_lookback),
        )
        .order_by(models.Transaction.date, models.Transaction.id)
    )

    rows = db.execute(stmt).all()
    standing = _standing_charges(db, {row[3] for row in rows if row[3] is not None})

    groups: dict[tuple, dict] = {}
    seen_categories: set[int] = set()
    for tx, personal_share, category_name, merchant_key in rows:
        lookback_row = tx.date < trip.start_date
        bucket, reason = _bucket_for(tx, lookback_row, merchant_key, skip_categories, standing)
        if tx.category_id is not None:
            seen_categories.add(tx.category_id)
        label = merchant_key or tx.description_raw
        group_key = (bucket, label, tx.category_id)
        group = groups.get(group_key)
        if group is None:
            group = groups[group_key] = {
                "key": f"{bucket}:{tx.category_id or 0}:{label}",
                "bucket": bucket,
                "reason": reason,
                "merchant": label,
                "category_id": tx.category_id,
                "category_name": category_name,
                "occurrences": 0,
                "total": Decimal("0"),
                "first_date": tx.date,
                "last_date": tx.date,
                "transactions": [],
            }
        spend = spend_amount(tx.amount, personal_share)
        group["occurrences"] += 1
        group["total"] += spend
        group["first_date"] = min(group["first_date"], tx.date)
        group["last_date"] = max(group["last_date"], tx.date)
        group["transactions"].append(
            {
                "id": tx.id,
                "date": tx.date,
                "description": tx.merchant or tx.description_normalized or tx.description_raw,
                "amount": tx.amount,
                "spend": spend,
                "personal_share": Decimal(personal_share) if personal_share is not None else None,
            }
        )

    order = {
        BUCKET_PRE_BOOKING: 0,
        BUCKET_PROPOSED: 1,
        BUCKET_STANDING_CHARGE: 2,
        BUCKET_HOME_LIFE: 3,
    }
    rows = sorted(groups.values(), key=lambda g: (order[g["bucket"]], -g["total"]))

    totals: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    counts: dict[str, int] = defaultdict(int)
    for group in rows:
        totals[group["bucket"]] += group["total"]
        counts[group["bucket"]] += group["occurrences"]

    undecided = seen_categories - set(policies)
    presence = _month_presence(db, undecided)
    category_names = dict(
        db.execute(
            select(models.Category.id, models.Category.name).where(
                models.Category.id.in_(undecided)
            )
        ).all()
    )
    undecided_rows = sorted(
        (
            {
                "category_id": category_id,
                "category_name": category_names.get(category_id, "?"),
                "months_charged": presence.get(category_id, (0, 0))[0],
                "months_observed": presence.get(category_id, (0, 0))[1],
            }
            for category_id in undecided
        ),
        key=lambda row: (-row["months_charged"], row["category_name"]),
    )

    return {
        "trip_id": trip.id,
        "lookback_days": lookback_days,
        "lookback_start": lookback_start,
        "groups": rows,
        "undecided_categories": undecided_rows,
        "summary": {
            bucket: {"transactions": counts[bucket], "total": totals[bucket]}
            for bucket in (
                BUCKET_PRE_BOOKING,
                BUCKET_PROPOSED,
                BUCKET_STANDING_CHARGE,
                BUCKET_HOME_LIFE,
            )
        },
    }


def _nights(start: date, end: date) -> int:
    return max(0, (end - start).days)


def _per_night(total: Decimal, nights: int) -> Decimal | None:
    if nights <= 0:
        return None
    return (total / nights).quantize(CENTS)


def _leg_for(legs: list[models.TripLeg], when: date | None) -> int | None:
    """The leg whose dates contain this row, if any.

    Legs need not tile the trip: the travel days between two stays belong
    to neither, and a pre-booking predates all of them. Those rows stay
    on the trip without a leg rather than being forced into the nearest
    one.
    """
    if when is None:
        return None
    for leg in legs:
        if leg.start_date <= when <= leg.end_date:
            return leg.id
    return None


def trip_report(db: Session, trip: models.Trip) -> dict:
    """What the trip cost, by category and by stop."""
    legs = list(trip.legs)
    leg_ids = [leg.id for leg in legs]

    stmt = (
        select(
            models.TripExpense,
            models.Transaction,
            models.TransactionSplit.personal_share,
            models.Category.name,
        )
        .join(models.Transaction, models.Transaction.id == models.TripExpense.transaction_id)
        .join(
            models.TransactionSplit,
            models.TransactionSplit.transaction_id == models.Transaction.id,
            isouter=True,
        )
        .join(
            models.Category,
            models.Category.id == models.Transaction.category_id,
            isouter=True,
        )
        .where(models.TripExpense.trip_id == trip.id)
        .order_by(models.Transaction.date, models.Transaction.id)
    )

    # (category_id, leg_id) -> amount, plus the flat rows the detail
    # table lists.
    cells: dict[tuple[int | None, int | None], Decimal] = defaultdict(lambda: Decimal("0"))
    category_names: dict[int | None, str | None] = {}
    rows: list[dict] = []
    expense_count = 0

    for membership, tx, personal_share, category_name in db.execute(stmt):
        spend = spend_amount(tx.amount, personal_share, membership.share)
        leg_id = membership.leg_id if membership.leg_id in leg_ids else None
        cells[(tx.category_id, leg_id)] += spend
        category_names[tx.category_id] = category_name
        expense_count += 1
        rows.append(
            {
                "kind": "transaction",
                "transaction_id": tx.id,
                "item_id": None,
                "date": tx.date,
                "label": tx.merchant or tx.description_normalized or tx.description_raw,
                "category_id": tx.category_id,
                "category_name": category_name,
                "leg_id": leg_id,
                "amount": tx.amount,
                "share": Decimal(membership.share),
                "personal_share": Decimal(personal_share) if personal_share is not None else None,
                "spend": spend,
                "notes": membership.notes,
            }
        )

    item_stmt = (
        select(models.TripItem, models.Category.name)
        .join(models.Category, models.Category.id == models.TripItem.category_id, isouter=True)
        .where(models.TripItem.trip_id == trip.id)
        .order_by(models.TripItem.date, models.TripItem.id)
    )
    item_count = 0
    for item, category_name in db.execute(item_stmt):
        # Items are already positive money-out, and carry no account
        # convention or roommate split to unwind.
        spend = Decimal(item.amount).quantize(CENTS)
        leg_id = item.leg_id if item.leg_id in leg_ids else _leg_for(legs, item.date)
        cells[(item.category_id, leg_id)] += spend
        category_names.setdefault(item.category_id, category_name)
        item_count += 1
        rows.append(
            {
                "kind": "item",
                "transaction_id": None,
                "item_id": item.id,
                "date": item.date,
                "label": item.label,
                "category_id": item.category_id,
                "category_name": category_name,
                "leg_id": leg_id,
                "amount": spend,
                "share": None,
                "personal_share": None,
                "spend": spend,
                "notes": item.notes,
            }
        )

    rows.sort(key=lambda row: (row["date"] or trip.start_date, row["label"]))

    total = sum(cells.values(), Decimal("0"))
    by_leg: dict[int | None, Decimal] = defaultdict(lambda: Decimal("0"))
    by_category: dict[int | None, Decimal] = defaultdict(lambda: Decimal("0"))
    for (category_id, leg_id), amount in cells.items():
        by_leg[leg_id] += amount
        by_category[category_id] += amount

    categories = sorted(
        (
            {
                "category_id": category_id,
                "category_name": category_names.get(category_id) or "Uncategorized",
                "total": by_category[category_id],
                "by_leg": {
                    str(leg.id): cells.get((category_id, leg.id), Decimal("0")) for leg in legs
                },
                "unassigned": cells.get((category_id, None), Decimal("0")),
            }
            for category_id in by_category
        ),
        key=lambda row: -row["total"],
    )

    nights = _nights(trip.start_date, trip.end_date)
    return {
        "trip_id": trip.id,
        "nights": nights,
        "total": total,
        "per_night": _per_night(total, nights),
        "expense_count": expense_count,
        "item_count": item_count,
        "legs": [
            {
                "id": leg.id,
                "place": leg.place,
                "start_date": leg.start_date,
                "end_date": leg.end_date,
                "nights": _nights(leg.start_date, leg.end_date),
                "total": by_leg[leg.id],
                "per_night": _per_night(by_leg[leg.id], _nights(leg.start_date, leg.end_date)),
            }
            for leg in legs
        ],
        "unassigned_total": by_leg[None],
        "categories": categories,
        "rows": rows,
    }


def leg_for_date(trip: models.Trip, when: date | None) -> int | None:
    """The leg of this trip whose dates contain `when`, if any."""
    return _leg_for(list(trip.legs), when)


__all__ = [
    "COUNTABLE_KINDS",
    "DEFAULT_LOOKBACK_DAYS",
    "BUCKET_HOME_LIFE",
    "BUCKET_PRE_BOOKING",
    "BUCKET_PROPOSED",
    "BUCKET_STANDING_CHARGE",
    "leg_for_date",
    "spend_amount",
    "trip_candidates",
    "trip_report",
]
