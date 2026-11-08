"""Trips: what the candidate proposal keeps out, and what the report adds up.

The proposal is the part worth pinning down. A date window alone sweeps
up payroll, card payments and rent, and misses the flight bought in
January, so most of these tests are about rows that must NOT be
proposed — and, just as important, the daily merchants that must not be
mistaken for subscriptions, because on a trip those are the spending.
"""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

import pytest
from fastapi import HTTPException

from app import schemas, trips
from app.models import (
    Account,
    AccountCategory,
    AccountType,
    Category,
    CategoryKind,
    SignConvention,
    Transaction,
    TransactionKind,
    TransactionSplit,
    Trip,
    TripCategoryPolicy,
    TripCategoryRule,
    TripExpense,
    TripItem,
    TripLeg,
)
from app.routers import trips as trips_router

TRIP_START = date(2026, 5, 13)
TRIP_END = date(2026, 5, 28)


@pytest.fixture()
def account(db):
    account = Account(
        name="Card",
        account_category=AccountCategory.credit,
        type=AccountType.credit_card,
        sign_convention=SignConvention.outflow_negative,
    )
    db.add(account)
    db.flush()
    return account


@pytest.fixture()
def categories(db):
    out = {}
    for name, kind in (
        ("Flights + Hotels", CategoryKind.expense),
        ("Food", CategoryKind.expense),
        ("Ground Transportation", CategoryKind.expense),
        ("Rent", CategoryKind.expense),
        ("Subscriptions", CategoryKind.expense),
        ("Salary", CategoryKind.income),
    ):
        category = Category(name=name, kind=kind)
        db.add(category)
        out[name] = category
    db.flush()
    return out


@pytest.fixture()
def trip(db):
    trip = Trip(name="West Coast", start_date=TRIP_START, end_date=TRIP_END)
    db.add(trip)
    db.flush()
    return trip


def _tx(db, account, categories, when, amount, description, category=None, kind=None):
    category_row = categories[category] if category else None
    tx = Transaction(
        account_id=account.id,
        date=when,
        description_raw=description,
        description_normalized=description,
        merchant=description,
        amount=Decimal(amount),
        category_id=category_row.id if category_row else None,
        kind=kind
        or (
            TransactionKind(category_row.kind.value)
            if category_row
            else TransactionKind.uncategorized
        ),
    )
    db.add(tx)
    db.flush()
    return tx


def _policy(db, categories, name, rule):
    db.add(TripCategoryPolicy(category_id=categories[name].id, policy=rule))
    db.flush()


def _buckets(result):
    """bucket -> {merchant: total}, for terse assertions."""
    out: dict[str, dict[str, Decimal]] = {}
    for group in result["groups"]:
        out.setdefault(group["bucket"], {})[group["merchant"]] = group["total"]
    return out


# ---------- the money ----------


def test_spend_amount_flips_sign_and_applies_both_shares():
    # Stored outflow-negative; a cost reads positive.
    assert trips.spend_amount(Decimal("-100.00")) == Decimal("100.00")
    # Half shared with a roommate.
    assert trips.spend_amount(Decimal("-100.00"), Decimal("0.5")) == Decimal("50.00")
    # ...of which half was trip-related: the two axes multiply.
    assert trips.spend_amount(Decimal("-100.00"), Decimal("0.5"), Decimal("0.5")) == Decimal(
        "25.00"
    )
    # Quantized per row, so displayed rows sum to the displayed total.
    assert trips.spend_amount(Decimal("-24.75"), None, Decimal("0.3333")) == Decimal("8.25")


# ---------- what must never be proposed ----------


def test_money_movement_is_never_a_candidate(db, account, categories, trip):
    _tx(db, account, categories, TRIP_START, "3867.24", "Payroll", "Salary")
    _tx(
        db,
        account,
        categories,
        TRIP_START,
        "-3026.05",
        "Card payment",
        kind=TransactionKind.cc_payment,
    )
    _tx(db, account, categories, TRIP_START, "10000.00", "Wire in", kind=TransactionKind.transfer)
    _tx(
        db,
        account,
        categories,
        TRIP_START,
        "-2000.00",
        "Brokerage",
        kind=TransactionKind.investment,
    )
    _tx(db, account, categories, TRIP_START, "-22.00", "Diner", "Food")

    result = trips.trip_candidates(db, trip)

    assert _buckets(result) == {trips.BUCKET_PROPOSED: {"Diner": Decimal("22.00")}}


def test_home_life_categories_are_set_aside_not_dropped(db, account, categories, trip):
    """Rent inside the dates is the single biggest source of a wrong total.

    It still has to be *shown* — silently dropping rows is how a total
    stops being trustworthy — so it lands in its own bucket.
    """
    _policy(db, categories, "Rent", TripCategoryRule.skip)
    # A per-payment reference in the description, as real rent rows carry:
    # merchant-frequency detection cannot see these as recurring.
    _tx(db, account, categories, date(2026, 5, 27), "-2426.50", "LANDLORD O2K2D5", "Rent")
    _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")

    buckets = _buckets(trips.trip_candidates(db, trip))

    assert buckets[trips.BUCKET_HOME_LIFE] == {"LANDLORD O2K2D5": Decimal("2426.50")}
    assert buckets[trips.BUCKET_PROPOSED] == {"Diner": Decimal("22.00")}


def test_standing_charges_are_flagged_but_daily_merchants_are_not(db, account, categories, trip):
    """The load-bearing guard.

    A monthly subscription billed mid-trip would have billed anyway. A
    merchant you hit most days is the opposite: on a trip those rows are
    the trip. Frequency alone cannot tell them apart, cadence can.
    """
    for month in range(1, 7):
        _tx(db, account, categories, date(2026, month, 20), "-20.00", "Streaming", "Subscriptions")
    for day in range(1, 26):
        _tx(db, account, categories, date(2026, 5, day), "-3.00", "Taxi", "Ground Transportation")

    buckets = _buckets(trips.trip_candidates(db, trip))

    assert "Streaming" in buckets[trips.BUCKET_STANDING_CHARGE]
    assert "Taxi" in buckets[trips.BUCKET_PROPOSED]
    assert "Taxi" not in buckets.get(trips.BUCKET_STANDING_CHARGE, {})


def test_a_shop_visited_often_is_not_a_standing_charge(db, account, categories, trip):
    """Average spacing cannot tell these apart; regularity can.

    A grocery run every couple of weeks has the same mean gap as a
    monthly bill. The bill arrives on a schedule for the same money; the
    shop does not, and on a trip the shop is the trip.
    """
    # Irregular gaps, varying amounts — a mean cadence of ~17 days that
    # sits squarely inside the subscription band.
    for day, amount in (
        (3, "-41.20"),
        (19, "-88.75"),
        (27, "-12.40"),
        (54, "-63.10"),
        (61, "-104.90"),
        (95, "-37.55"),
    ):
        _tx(
            db,
            account,
            categories,
            date(2026, 1, 1) + timedelta(days=day),
            amount,
            "Corner shop",
            "Food",
        )
    # ...and the same cadence billed like a bill: even gaps, one price.
    for month in range(1, 7):
        _tx(db, account, categories, date(2026, month, 20), "-15.49", "Streaming", "Subscriptions")
    _tx(db, account, categories, date(2026, 5, 20), "-30.00", "Corner shop", "Food")

    buckets = _buckets(trips.trip_candidates(db, trip))

    assert "Corner shop" in buckets[trips.BUCKET_PROPOSED]
    assert "Corner shop" not in buckets.get(trips.BUCKET_STANDING_CHARGE, {})
    assert "Streaming" in buckets[trips.BUCKET_STANDING_CHARGE]


def test_a_pass_bought_irregularly_for_one_price_is_a_standing_charge(
    db, account, categories, trip
):
    """A travel pass renewed whenever the last runs out: ragged timing,
    always exactly the same money. The amount is what gives it away."""
    for day in (2, 36, 51, 88, 104, 133):
        _tx(
            db,
            account,
            categories,
            date(2026, 1, 1) + timedelta(days=day),
            "-50.00",
            "Transit pass",
            "Ground Transportation",
        )
    db.flush()

    buckets = _buckets(trips.trip_candidates(db, trip))

    assert "Transit pass" in buckets[trips.BUCKET_STANDING_CHARGE]


def test_rows_claimed_by_another_trip_are_invisible(db, account, categories, trip):
    other = Trip(name="Earlier trip", start_date=TRIP_START, end_date=TRIP_END)
    db.add(other)
    db.flush()
    taken = _tx(db, account, categories, date(2026, 5, 20), "-50.00", "Hotel", "Flights + Hotels")
    db.add(TripExpense(transaction_id=taken.id, trip_id=other.id))
    _tx(db, account, categories, date(2026, 5, 21), "-22.00", "Diner", "Food")
    db.flush()

    buckets = _buckets(trips.trip_candidates(db, trip))

    assert buckets == {trips.BUCKET_PROPOSED: {"Diner": Decimal("22.00")}}


def test_linked_pairs_and_hidden_rows_are_skipped(db, account, categories, trip):
    refund = _tx(db, account, categories, date(2026, 5, 20), "-40.00", "Returned", "Food")
    other_half = _tx(db, account, categories, date(2026, 5, 21), "40.00", "Refund", "Food")
    refund.transfer_pair_id = other_half.id
    other_half.transfer_pair_id = refund.id
    hidden = _tx(db, account, categories, date(2026, 5, 22), "-15.00", "Hidden", "Food")
    hidden.is_excluded_from_totals = True
    _tx(db, account, categories, date(2026, 5, 23), "-22.00", "Diner", "Food")
    db.flush()

    buckets = _buckets(trips.trip_candidates(db, trip))

    assert buckets == {trips.BUCKET_PROPOSED: {"Diner": Decimal("22.00")}}


# ---------- what the window misses ----------


def test_advance_categories_reach_back_before_the_trip(db, account, categories, trip):
    """The flight bought in January belongs to the trip in May.

    Only the user knows which categories work that way, so it is a
    per-category policy rather than a guess.
    """
    _policy(db, categories, "Flights + Hotels", TripCategoryRule.advance)
    _tx(db, account, categories, date(2026, 1, 9), "-427.81", "Airline", "Flights + Hotels")
    # Same run-up, ordinary category: a January dinner is not trip spend.
    _tx(db, account, categories, date(2026, 1, 9), "-30.00", "January diner", "Food")

    buckets = _buckets(trips.trip_candidates(db, trip))

    assert buckets[trips.BUCKET_PRE_BOOKING] == {"Airline": Decimal("427.81")}
    assert "January diner" not in buckets.get(trips.BUCKET_PROPOSED, {})


def test_lookback_window_is_bounded(db, account, categories, trip):
    _policy(db, categories, "Flights + Hotels", TripCategoryRule.advance)
    long_ago = TRIP_START - timedelta(days=200)
    _tx(db, account, categories, long_ago, "-500.00", "Last year's flight", "Flights + Hotels")

    assert trips.trip_candidates(db, trip, lookback_days=199)["groups"] == []
    assert len(trips.trip_candidates(db, trip, lookback_days=201)["groups"]) == 1
    # The default has to be wide enough for a flight bought four months
    # out, which is ordinary for a holiday.
    assert trips.DEFAULT_LOOKBACK_DAYS >= 124


# ---------- grouping ----------


def test_repeats_collapse_into_one_decision(db, account, categories, trip):
    for day, amount in ((14, "-66.97"), (16, "-80.47"), (18, "-38.90")):
        _tx(db, account, categories, date(2026, 5, day), amount, "Taxi", "Ground Transportation")

    groups = trips.trip_candidates(db, trip)["groups"]

    assert len(groups) == 1
    assert groups[0]["occurrences"] == 3
    assert groups[0]["total"] == Decimal("186.34")
    assert groups[0]["first_date"] == date(2026, 5, 14)
    assert groups[0]["last_date"] == date(2026, 5, 18)
    assert len(groups[0]["transactions"]) == 3


def test_undecided_categories_are_reported_with_a_hint(db, account, categories, trip):
    """A category with no policy yet is still proposed, and reported, so
    the review screen can ask once and remember the answer."""
    _policy(db, categories, "Food", TripCategoryRule.include)
    _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    _tx(db, account, categories, date(2026, 5, 21), "-45.00", "Museum", "Ground Transportation")

    result = trips.trip_candidates(db, trip)

    undecided = {row["category_name"] for row in result["undecided_categories"]}
    assert undecided == {"Ground Transportation"}
    assert result["undecided_categories"][0]["months_charged"] == 1


# ---------- the report ----------


def test_report_splits_spend_across_legs_and_leaves_travel_days_unassigned(
    db, account, categories, trip
):
    seattle = TripLeg(
        trip_id=trip.id, place="Seattle", start_date=date(2026, 5, 13), end_date=date(2026, 5, 18)
    )
    ashland = TripLeg(
        trip_id=trip.id, place="Ashland", start_date=date(2026, 5, 20), end_date=date(2026, 5, 26)
    )
    db.add_all([seattle, ashland])
    db.flush()

    in_seattle = _tx(db, account, categories, date(2026, 5, 15), "-100.00", "SEA food", "Food")
    in_ashland = _tx(db, account, categories, date(2026, 5, 22), "-50.00", "MFR food", "Food")
    # 19 May falls between the two stays — a travel day belongs to neither.
    travelling = _tx(db, account, categories, date(2026, 5, 19), "-20.00", "Airport", "Food")
    for tx in (in_seattle, in_ashland, travelling):
        db.add(
            TripExpense(
                transaction_id=tx.id, trip_id=trip.id, leg_id=trips.leg_for_date(trip, tx.date)
            )
        )
    db.flush()

    report = trips.trip_report(db, trip)

    assert report["total"] == Decimal("170.00")
    assert {leg["place"]: leg["total"] for leg in report["legs"]} == {
        "Seattle": Decimal("100.00"),
        "Ashland": Decimal("50.00"),
    }
    assert report["unassigned_total"] == Decimal("20.00")
    assert report["nights"] == 15
    food = next(row for row in report["categories"] if row["category_name"] == "Food")
    assert food["by_leg"][str(seattle.id)] == Decimal("100.00")
    assert food["unassigned"] == Decimal("20.00")


def test_report_counts_manual_items_so_cash_is_not_missing(db, account, categories, trip):
    """Without these the total cannot match the one you work out by hand."""
    tx = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.add(TripExpense(transaction_id=tx.id, trip_id=trip.id))
    db.add(
        TripItem(
            trip_id=trip.id, label="Magic show", amount=Decimal("20.00"), date=date(2026, 5, 25)
        )
    )
    db.add(TripItem(trip_id=trip.id, label="Bus fares", amount=Decimal("13.50")))
    db.flush()

    report = trips.trip_report(db, trip)

    assert report["total"] == Decimal("55.50")
    assert report["expense_count"] == 1
    assert report["item_count"] == 2
    assert sum(row["spend"] for row in report["rows"]) == report["total"]


def test_report_applies_the_trip_share(db, account, categories, trip):
    """The toothpaste inside a larger drugstore order."""
    tx = _tx(db, account, categories, date(2026, 5, 20), "-24.75", "Drugstore", "Food")
    db.add(TripExpense(transaction_id=tx.id, trip_id=trip.id, share=Decimal("0.2")))
    db.flush()

    assert trips.trip_report(db, trip)["total"] == Decimal("4.95")


def test_trip_share_and_roommate_share_stack(db, account, categories, trip):
    tx = _tx(db, account, categories, date(2026, 5, 20), "-100.00", "Shared", "Food")
    db.add(
        TransactionSplit(transaction_id=tx.id, group_name="roommate", personal_share=Decimal("0.5"))
    )
    db.add(TripExpense(transaction_id=tx.id, trip_id=trip.id, share=Decimal("0.5")))
    db.flush()

    assert trips.trip_report(db, trip)["total"] == Decimal("25.00")


def test_deleting_a_leg_keeps_its_spending_on_the_trip(db, account, categories, trip):
    leg = TripLeg(
        trip_id=trip.id, place="Seattle", start_date=date(2026, 5, 13), end_date=date(2026, 5, 18)
    )
    db.add(leg)
    db.flush()
    tx = _tx(db, account, categories, date(2026, 5, 15), "-100.00", "SEA food", "Food")
    db.add(TripExpense(transaction_id=tx.id, trip_id=trip.id, leg_id=leg.id))
    db.flush()

    db.delete(leg)
    db.flush()
    db.expire_all()

    report = trips.trip_report(db, db.get(Trip, trip.id))
    assert report["total"] == Decimal("100.00")
    assert report["unassigned_total"] == Decimal("100.00")


def test_per_night_is_none_for_a_same_day_trip(db, account, categories):
    day_trip = Trip(name="Day out", start_date=date(2026, 5, 13), end_date=date(2026, 5, 13))
    db.add(day_trip)
    db.flush()

    report = trips.trip_report(db, day_trip)
    assert report["nights"] == 0
    assert report["per_night"] is None


# ---------- router guards ----------


def test_adding_a_stop_files_the_rows_it_covers(db, account, categories, trip):
    """Reviewing the spending and then splitting it by city is the normal
    order, so a new stop has to pick up what it covers — otherwise every
    row stays unfiled and has to be moved by hand."""
    inside = _tx(db, account, categories, date(2026, 5, 15), "-100.00", "SEA food", "Food")
    outside = _tx(db, account, categories, date(2026, 5, 25), "-40.00", "Later", "Food")
    db.add(TripExpense(transaction_id=inside.id, trip_id=trip.id))
    db.add(TripExpense(transaction_id=outside.id, trip_id=trip.id))
    db.add(
        TripItem(trip_id=trip.id, label="Cash fare", amount=Decimal("5.00"), date=date(2026, 5, 16))
    )
    db.commit()

    detail = trips_router.add_leg(
        trip.id,
        schemas.TripLegIn(
            place="Seattle", start_date=date(2026, 5, 13), end_date=date(2026, 5, 18)
        ),
        db,
    )

    leg_id = detail["trip"].legs[0].id
    assert db.get(TripExpense, inside.id).leg_id == leg_id
    assert db.get(TripExpense, outside.id).leg_id is None
    seattle = next(leg for leg in detail["report"]["legs"] if leg["id"] == leg_id)
    assert seattle["total"] == Decimal("105.00")


def test_filing_a_row_by_hand_outranks_a_later_stop(db, account, categories, trip):
    """An explicit choice is not second-guessed by the dates."""
    first = TripLeg(
        trip_id=trip.id, place="Seattle", start_date=date(2026, 5, 13), end_date=date(2026, 5, 18)
    )
    db.add(first)
    db.flush()
    tx = _tx(db, account, categories, date(2026, 5, 22), "-60.00", "Odd one", "Food")
    db.add(TripExpense(transaction_id=tx.id, trip_id=trip.id, leg_id=first.id))
    db.commit()

    trips_router.add_leg(
        trip.id,
        schemas.TripLegIn(
            place="Ashland", start_date=date(2026, 5, 20), end_date=date(2026, 5, 26)
        ),
        db,
    )

    assert db.get(TripExpense, tx.id).leg_id == first.id


def test_a_transaction_belongs_to_at_most_one_trip(db, account, categories, trip):
    """Two overlapping trips must not each count the same charge."""
    other = Trip(name="Other", start_date=TRIP_START, end_date=TRIP_END)
    db.add(other)
    db.flush()
    tx = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.add(TripExpense(transaction_id=tx.id, trip_id=other.id))
    db.commit()

    with pytest.raises(HTTPException) as caught:
        trips_router.set_expenses(
            trip.id,
            schemas.TripExpensesIn(expenses=[schemas.TripExpenseIn(transaction_id=tx.id)]),
            db,
        )
    assert caught.value.status_code == 409


def test_inflows_cannot_be_filed_onto_a_trip_by_hand(db, account, categories, trip):
    """The candidate search never proposes a paycheck, but rows can also
    be filed from the Transactions page. Costs are negated to read
    positive, so an inflow would subtract — a trip total that falls when
    payday lands inside the dates."""
    paycheck = _tx(db, account, categories, date(2026, 5, 20), "3867.24", "Payroll", "Salary")
    db.commit()

    with pytest.raises(HTTPException) as caught:
        trips_router.set_expenses(
            trip.id,
            schemas.TripExpensesIn(expenses=[schemas.TripExpenseIn(transaction_id=paycheck.id)]),
            db,
        )
    assert caught.value.status_code == 422
    assert "money moving" in caught.value.detail


def test_a_refund_may_be_filed_onto_a_trip(db, account, categories, trip):
    """A refunded booking genuinely nets off what the trip cost, so this
    one is allowed on purpose."""
    booking = _tx(
        db, account, categories, date(2026, 5, 14), "-200.00", "Hotel", "Flights + Hotels"
    )
    refund = _tx(
        db,
        account,
        categories,
        date(2026, 5, 16),
        "50.00",
        "Hotel refund",
        "Flights + Hotels",
        kind=TransactionKind.refund,
    )
    db.commit()

    trips_router.set_expenses(
        trip.id,
        schemas.TripExpensesIn(
            expenses=[
                schemas.TripExpenseIn(transaction_id=booking.id),
                schemas.TripExpenseIn(transaction_id=refund.id),
            ]
        ),
        db,
    )

    assert trips.trip_report(db, db.get(Trip, trip.id))["total"] == Decimal("150.00")


def test_a_leg_from_another_trip_is_rejected(db, account, categories, trip):
    other = Trip(name="Other", start_date=TRIP_START, end_date=TRIP_END)
    db.add(other)
    db.flush()
    foreign_leg = TripLeg(
        trip_id=other.id, place="Elsewhere", start_date=TRIP_START, end_date=TRIP_END
    )
    db.add(foreign_leg)
    tx = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.commit()

    with pytest.raises(HTTPException) as caught:
        trips_router.set_expenses(
            trip.id,
            schemas.TripExpensesIn(
                expenses=[schemas.TripExpenseIn(transaction_id=tx.id, leg_id=foreign_leg.id)]
            ),
            db,
        )
    assert caught.value.status_code == 422


def test_a_trip_cannot_end_before_it_starts(db):
    with pytest.raises(HTTPException) as caught:
        trips_router.create_trip(
            schemas.TripIn(name="Backwards", start_date=TRIP_END, end_date=TRIP_START), db
        )
    assert caught.value.status_code == 422


def test_deleting_a_trip_leaves_the_transactions_alone(db, account, categories, trip):
    """A trip is a view of the ledger, not a container for it."""
    tx = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.add(TripExpense(transaction_id=tx.id, trip_id=trip.id))
    db.commit()

    trips_router.delete_trip(trip.id, db)

    assert db.get(Transaction, tx.id) is not None
    assert db.get(TripExpense, tx.id) is None


def test_setting_a_policy_then_clearing_it_round_trips(db, categories):
    food = categories["Food"].id
    db.commit()

    trips_router.set_policies(
        schemas.TripCategoryPoliciesIn(
            policies=[
                schemas.TripCategoryPolicyIn(category_id=food, policy=TripCategoryRule.advance)
            ]
        ),
        db,
    )
    assert db.get(TripCategoryPolicy, food).policy == TripCategoryRule.advance

    trips_router.set_policies(schemas.TripCategoryPoliciesIn(clear_category_ids=[food]), db)
    assert db.get(TripCategoryPolicy, food) is None
