"""Trip totals.

A trip's membership is a set the user builds by ticking rows, so there is
nothing to infer here: this is the arithmetic over that set, and the one
rule about what may join it.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import date
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy import func, select, union
from sqlalchemy.orm import Session

from . import models
from .models import TransactionKind

CENTS = Decimal("0.01")


def _cents(value: Decimal) -> Decimal:
    """Round to the cent, halves away from zero.

    Decimal's default is banker's rounding, which turns half of $28.01
    into $14.00 where anyone working it out by hand — and the trip page,
    which shows each row's figure — gets $14.01. The row and the total
    have to agree to the cent, so both sides round the same, explicit
    way.
    """
    return value.quantize(CENTS, rounding=ROUND_HALF_UP)


# Kinds a trip may count. Costs are stored outflow-negative and negated to
# read positive, so an inflow filed onto a trip would subtract from it — a
# paycheck landing mid-trip would quietly discount what the trip cost.
# Refunds and reimbursements are the deliberate exceptions: a refunded
# booking, or a friend paying back their half of the room, genuinely nets
# out against the trip.
COUNTABLE_KINDS = frozenset(
    {
        TransactionKind.expense,
        TransactionKind.uncategorized,
        TransactionKind.refund,
        TransactionKind.reimbursement,
    }
)

# What a counted row is called when it has neither a trip group nor a
# category to borrow one from.
UNGROUPED = "Other"


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
    reports.
    """
    effective = amount if personal_share is None else amount * Decimal(personal_share)
    if trip_share is not None:
        effective = effective * Decimal(trip_share)
    return _cents(-effective)


def group_label(group_name: str | None, category_name: str | None) -> str:
    """The trip group a row totals under: its own label, else its category's."""
    return group_name or category_name or UNGROUPED


def _nights(start: date | None, end: date | None) -> int | None:
    if start is None or end is None:
        return None
    return max(0, (end - start).days)


def _per_night(total: Decimal, nights: int | None) -> Decimal | None:
    if not nights:
        return None
    return _cents(total / nights)


def _leg_for(legs: list[models.TripLeg], when: date | None) -> int | None:
    """The leg whose dates contain this row, if any.

    Legs need not tile the trip: the travel days between two stays belong
    to neither, and a booking made months ahead predates all of them.
    Those rows stay on the trip without a leg rather than being forced
    into the nearest one.
    """
    if when is None:
        return None
    for leg in legs:
        if leg.start_date <= when <= leg.end_date:
            return leg.id
    return None


def leg_for_date(trip: models.Trip, when: date | None) -> int | None:
    """The leg of this trip whose dates contain `when`, if any."""
    return _leg_for(list(trip.legs), when)


def group_suggestions(db: Session) -> list[str]:
    """Every trip group already in use, for the group field's suggestions.

    Groups are free text, so reusing yesterday's spelling of "Flight" is
    what keeps two trips comparable.
    """
    names = union(
        select(models.TripExpense.group_name).where(models.TripExpense.group_name.is_not(None)),
        select(models.TripItem.group_name).where(models.TripItem.group_name.is_not(None)),
    ).subquery()
    return sorted(db.scalars(select(names.c.group_name)).all(), key=str.casefold)


def trip_report(db: Session, trip: models.Trip) -> dict:
    """What the trip cost, by trip group and by stop."""
    legs = list(trip.legs)
    leg_ids = {leg.id for leg in legs}

    # (group, leg_id) -> amount
    cells: dict[tuple[str, int | None], Decimal] = defaultdict(lambda: Decimal("0"))
    dates: list[date] = []
    expense_count = 0

    stmt = (
        select(
            models.TripExpense,
            models.Transaction.date,
            models.Transaction.amount,
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
    )
    for membership, when, amount, personal_share, category_name in db.execute(stmt):
        leg_id = membership.leg_id if membership.leg_id in leg_ids else None
        group = group_label(membership.group_name, category_name)
        cells[(group, leg_id)] += spend_amount(amount, personal_share, membership.share)
        dates.append(when)
        expense_count += 1

    item_stmt = (
        select(models.TripItem, models.Category.name)
        .join(models.Category, models.Category.id == models.TripItem.category_id, isouter=True)
        .where(models.TripItem.trip_id == trip.id)
    )
    item_count = 0
    for item, category_name in db.execute(item_stmt):
        # Items are already signed the way a trip means it (cost positive,
        # credit negative) and carry no account convention to unwind.
        leg_id = item.leg_id if item.leg_id in leg_ids else _leg_for(legs, item.date)
        group = group_label(item.group_name, category_name)
        cells[(group, leg_id)] += _cents(Decimal(item.amount))
        if item.date is not None:
            dates.append(item.date)
        item_count += 1

    total = sum(cells.values(), Decimal("0"))
    by_leg: dict[int | None, Decimal] = defaultdict(lambda: Decimal("0"))
    by_group: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    for (group, leg_id), amount in cells.items():
        by_leg[leg_id] += amount
        by_group[group] += amount

    nights = _nights(trip.start_date, trip.end_date)
    return {
        "trip_id": trip.id,
        "nights": nights,
        "total": total,
        "per_night": _per_night(total, nights),
        "expense_count": expense_count,
        "item_count": item_count,
        # Where the counted rows actually fall — a trip with no dates of
        # its own still shows something, and a trip with dates shows how
        # far ahead things were booked.
        "span_start": min(dates) if dates else None,
        "span_end": max(dates) if dates else None,
        "legs": [
            {
                "id": leg.id,
                "place": leg.place,
                "start_date": leg.start_date,
                "end_date": leg.end_date,
                "nights": _nights(leg.start_date, leg.end_date) or 0,
                "total": by_leg[leg.id],
                "per_night": _per_night(by_leg[leg.id], _nights(leg.start_date, leg.end_date)),
            }
            for leg in legs
        ],
        "unassigned_total": by_leg[None],
        "groups": sorted(
            (
                {
                    "name": group,
                    "total": by_group[group],
                    "by_leg": {
                        str(leg.id): cells.get((group, leg.id), Decimal("0")) for leg in legs
                    },
                    "unassigned": cells.get((group, None), Decimal("0")),
                }
                for group in by_group
            ),
            key=lambda row: -row["total"],
        ),
    }


def trip_totals(db: Session) -> dict[int, dict]:
    """Totals for every trip at once, for the list page.

    One pass over memberships and one over items, rather than a full
    report per trip.
    """
    totals: dict[int, dict] = defaultdict(
        lambda: {"total": Decimal("0"), "expense_count": 0, "item_count": 0}
    )
    stmt = (
        select(
            models.TripExpense.trip_id,
            models.TripExpense.share,
            models.Transaction.amount,
            models.TransactionSplit.personal_share,
        )
        .join(models.Transaction, models.Transaction.id == models.TripExpense.transaction_id)
        .join(
            models.TransactionSplit,
            models.TransactionSplit.transaction_id == models.Transaction.id,
            isouter=True,
        )
    )
    for trip_id, share, amount, personal_share in db.execute(stmt):
        totals[trip_id]["total"] += spend_amount(amount, personal_share, share)
        totals[trip_id]["expense_count"] += 1
    for trip_id, amount, count in db.execute(
        select(
            models.TripItem.trip_id,
            func.sum(models.TripItem.amount),
            func.count(models.TripItem.id),
        ).group_by(models.TripItem.trip_id)
    ):
        totals[trip_id]["total"] += _cents(Decimal(amount))
        totals[trip_id]["item_count"] += count
    return totals


__all__ = [
    "COUNTABLE_KINDS",
    "UNGROUPED",
    "group_label",
    "group_suggestions",
    "leg_for_date",
    "spend_amount",
    "trip_report",
    "trip_totals",
]
