"""Trips: what joining one does, and what the totals add up to.

Membership is ticked by hand, so these tests are about the rules that
must hold whatever gets ticked — one trip per row, money moving never
counts, re-ticking never resets anything — and about the arithmetic.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from app import schemas, trips
from app.models import (
    Account,
    AccountCategory,
    AccountType,
    Base,
    Category,
    CategoryKind,
    SignConvention,
    Transaction,
    TransactionKind,
    TransactionSplit,
    Trip,
    TripExpense,
    TripLeg,
)
from app.routers import transactions as transactions_router
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


def _tick(db, trip, *txs):
    return trips_router.change_membership(
        trip.id, schemas.TripMembershipChange(add_transaction_ids=[t.id for t in txs]), db
    )


def _groups(report):
    return {row["name"]: row["total"] for row in report["groups"]}


# ---------- the money ----------


def test_spend_amount_flips_sign_and_applies_both_shares():
    assert trips.spend_amount(Decimal("-100.00")) == Decimal("100.00")
    assert trips.spend_amount(Decimal("-100.00"), Decimal("0.5")) == Decimal("50.00")
    # Roommate share and trip share are separate axes, so they multiply.
    assert trips.spend_amount(Decimal("-100.00"), Decimal("0.5"), Decimal("0.5")) == Decimal(
        "25.00"
    )
    # Quantized per row, so displayed rows sum to the displayed total.
    assert trips.spend_amount(Decimal("-24.75"), None, Decimal("0.3333")) == Decimal("8.25")


def test_half_cents_round_away_from_zero_like_the_trip_page_does():
    """Decimal's default banker's rounding gives half of $28.01 as $14.00;
    the page, and anyone with a calculator, gets $14.01. They must agree."""
    assert trips.spend_amount(Decimal("-28.01"), None, Decimal("0.5")) == Decimal("14.01")
    # A refund's half-cent goes the same way, just negative.
    assert trips.spend_amount(Decimal("28.01"), None, Decimal("0.5")) == Decimal("-14.01")


def test_trip_groups_override_categories_and_fall_back_to_them(db, account, categories, trip):
    """One "Flights + Hotels" category is two lines on a trip."""
    flight = _tx(
        db, account, categories, date(2026, 1, 9), "-427.81", "Airline", "Flights + Hotels"
    )
    hotel = _tx(db, account, categories, date(2026, 5, 24), "-437.62", "Hotel", "Flights + Hotels")
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.commit()
    _tick(db, trip, flight, hotel, lunch)
    for tx, group in ((flight, "Flight"), (hotel, "Hotel")):
        trips_router.update_expense(trip.id, tx.id, schemas.TripExpenseUpdate(group_name=group), db)

    report = trips.trip_report(db, db.get(Trip, trip.id))

    assert _groups(report) == {
        "Hotel": Decimal("437.62"),
        "Flight": Decimal("427.81"),
        "Food": Decimal("22.00"),
    }


def test_a_blank_group_means_the_category_again(db, account, categories, trip):
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.commit()
    _tick(db, trip, lunch)
    trips_router.update_expense(trip.id, lunch.id, schemas.TripExpenseUpdate(group_name="Eats"), db)
    trips_router.update_expense(trip.id, lunch.id, schemas.TripExpenseUpdate(group_name="  "), db)

    assert db.get(TripExpense, lunch.id).group_name is None
    assert _groups(trips.trip_report(db, db.get(Trip, trip.id))) == {"Food": Decimal("22.00")}


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
    # 19 May falls between the two stays; a travel day belongs to neither.
    travelling = _tx(db, account, categories, date(2026, 5, 19), "-20.00", "Airport", "Food")
    db.commit()

    _tick(db, trip, in_seattle, in_ashland, travelling)
    report = trips.trip_report(db, db.get(Trip, trip.id))

    assert report["total"] == Decimal("170.00")
    assert {leg["place"]: leg["total"] for leg in report["legs"]} == {
        "Seattle": Decimal("100.00"),
        "Ashland": Decimal("50.00"),
    }
    assert report["unassigned_total"] == Decimal("20.00")
    assert report["nights"] == 15
    food = report["groups"][0]
    assert food["by_leg"][str(seattle.id)] == Decimal("100.00")
    assert food["unassigned"] == Decimal("20.00")


def test_hand_entered_costs_and_credits_both_count(db, account, categories, trip):
    """Cash and a ride paid from a PayPal balance never reach the ledger,
    and a refund that is not in it yet still has to come off."""
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.commit()
    _tick(db, trip, lunch)
    trips_router.add_item(
        trip.id, schemas.TripItemIn(label="Ride home", amount=Decimal("85.42")), db
    )
    trips_router.add_item(
        trip.id,
        schemas.TripItemIn(label="Ticket refund", amount=Decimal("-142.72"), group_name="Parks"),
        db,
    )

    report = trips.trip_report(db, db.get(Trip, trip.id))

    assert report["total"] == Decimal("-35.30")
    assert report["expense_count"] == 1
    assert report["item_count"] == 2
    assert _groups(report)["Parks"] == Decimal("-142.72")
    assert _groups(report)[trips.UNGROUPED] == Decimal("85.42")


def test_a_zero_amount_is_refused():
    with pytest.raises(ValidationError):
        schemas.TripItemIn(label="Nothing", amount=Decimal("0"))


def test_report_applies_the_trip_share(db, account, categories, trip):
    """The toothpaste inside a larger drugstore order."""
    order = _tx(db, account, categories, date(2026, 5, 20), "-24.75", "Drugstore", "Food")
    db.commit()
    _tick(db, trip, order)
    trips_router.update_expense(
        trip.id, order.id, schemas.TripExpenseUpdate(share=Decimal("0.2")), db
    )

    assert trips.trip_report(db, db.get(Trip, trip.id))["total"] == Decimal("4.95")


def test_trip_share_and_roommate_share_stack(db, account, categories, trip):
    shared = _tx(db, account, categories, date(2026, 5, 20), "-100.00", "Shared", "Food")
    db.add(
        TransactionSplit(
            transaction_id=shared.id, group_name="roommate", personal_share=Decimal("0.5")
        )
    )
    db.add(TripExpense(transaction_id=shared.id, trip_id=trip.id, share=Decimal("0.5")))
    db.flush()

    assert trips.trip_report(db, trip)["total"] == Decimal("25.00")


def test_a_trip_without_dates_still_totals_and_reports_its_span(db, account, categories):
    """A trip can start as just a name. It has no per-night figure until
    it has dates, but it can say where its rows fall."""
    wedding = trips_router.create_trip(schemas.TripIn(name="Wedding"), db)["trip"]
    deposit = _tx(db, account, categories, date(2025, 6, 1), "-400.00", "Venue hotel", "Food")
    dinner = _tx(db, account, categories, date(2026, 6, 6), "-60.00", "Rehearsal", "Food")
    db.commit()
    _tick(db, wedding, deposit, dinner)

    report = trips.trip_report(db, db.get(Trip, wedding.id))

    assert report["total"] == Decimal("460.00")
    assert report["nights"] is None
    assert report["per_night"] is None
    assert (report["span_start"], report["span_end"]) == (date(2025, 6, 1), date(2026, 6, 6))


def test_list_totals_agree_with_the_report(db, account, categories, trip):
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.commit()
    _tick(db, trip, lunch)
    trips_router.add_item(trip.id, schemas.TripItemIn(label="Cash", amount=Decimal("8.50")), db)

    listed = trips_router.list_trips(db=db)

    assert listed[0]["total"] == trips.trip_report(db, db.get(Trip, trip.id))["total"]
    assert (listed[0]["expense_count"], listed[0]["item_count"]) == (1, 1)


# ---------- ticking ----------


def test_retick_leaves_group_share_and_stop_alone(db, account, categories, trip):
    """The page resends ticks freely; that must never undo an edit."""
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-40.00", "Diner", "Food")
    db.commit()
    _tick(db, trip, lunch)
    trips_router.update_expense(
        trip.id,
        lunch.id,
        schemas.TripExpenseUpdate(group_name="Eats", share=Decimal("0.5")),
        db,
    )

    _tick(db, trip, lunch)

    membership = db.get(TripExpense, lunch.id)
    assert (membership.group_name, membership.share) == ("Eats", Decimal("0.5"))


def test_unticking_removes_only_from_this_trip(db, account, categories, trip):
    other = Trip(name="Other")
    db.add(other)
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.flush()
    db.add(TripExpense(transaction_id=lunch.id, trip_id=other.id))
    db.commit()

    trips_router.change_membership(
        trip.id, schemas.TripMembershipChange(remove_transaction_ids=[lunch.id]), db
    )

    assert db.get(TripExpense, lunch.id).trip_id == other.id


def test_a_transaction_belongs_to_at_most_one_trip(db, account, categories, trip):
    """Two overlapping trips must not each count the same charge."""
    other = Trip(name="Other")
    db.add(other)
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.flush()
    db.add(TripExpense(transaction_id=lunch.id, trip_id=other.id))
    db.commit()

    with pytest.raises(HTTPException) as caught:
        _tick(db, trip, lunch)
    assert caught.value.status_code == 409


def test_money_moving_cannot_join_a_trip(db, account, categories, trip):
    """Costs are negated to read positive, so a paycheck would subtract."""
    paycheck = _tx(db, account, categories, date(2026, 5, 20), "3867.24", "Payroll", "Salary")
    db.commit()

    with pytest.raises(HTTPException) as caught:
        _tick(db, trip, paycheck)
    assert caught.value.status_code == 422
    assert "money moving" in caught.value.detail


def test_a_refund_may_join_a_trip_and_nets_off(db, account, categories, trip):
    booking = _tx(
        db, account, categories, date(2026, 5, 14), "-200.00", "Hotel", "Flights + Hotels"
    )
    refund = _tx(
        db,
        account,
        categories,
        date(2026, 6, 16),
        "50.00",
        "Hotel refund",
        "Flights + Hotels",
        kind=TransactionKind.refund,
    )
    db.commit()

    _tick(db, trip, booking, refund)

    assert trips.trip_report(db, db.get(Trip, trip.id))["total"] == Decimal("150.00")


def test_ticking_files_the_row_on_the_stop_covering_it(db, account, categories, trip):
    stop = TripLeg(
        trip_id=trip.id, place="Seattle", start_date=date(2026, 5, 13), end_date=date(2026, 5, 18)
    )
    db.add(stop)
    lunch = _tx(db, account, categories, date(2026, 5, 15), "-22.00", "Diner", "Food")
    db.commit()

    _tick(db, trip, lunch)

    assert db.get(TripExpense, lunch.id).leg_id == stop.id


def test_adding_a_stop_files_the_rows_it_covers(db, account, categories, trip):
    """Collecting the spending and then splitting it by city is the
    normal order, so a new stop picks up what it covers."""
    inside = _tx(db, account, categories, date(2026, 5, 15), "-100.00", "SEA food", "Food")
    outside = _tx(db, account, categories, date(2026, 5, 25), "-40.00", "Later", "Food")
    db.commit()
    _tick(db, trip, inside, outside)
    trips_router.add_item(
        trip.id,
        schemas.TripItemIn(label="Cash fare", amount=Decimal("5.00"), date=date(2026, 5, 16)),
        db,
    )

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
    first = TripLeg(
        trip_id=trip.id, place="Seattle", start_date=date(2026, 5, 13), end_date=date(2026, 5, 18)
    )
    db.add(first)
    tx = _tx(db, account, categories, date(2026, 5, 22), "-60.00", "Odd one", "Food")
    db.flush()
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


def test_deleting_a_stop_keeps_its_spending_on_the_trip(db, account, categories, trip):
    stop = TripLeg(
        trip_id=trip.id, place="Seattle", start_date=date(2026, 5, 13), end_date=date(2026, 5, 18)
    )
    db.add(stop)
    lunch = _tx(db, account, categories, date(2026, 5, 15), "-100.00", "SEA food", "Food")
    db.commit()
    _tick(db, trip, lunch)

    detail = trips_router.delete_leg(trip.id, stop.id, db)

    assert detail["report"]["total"] == Decimal("100.00")
    assert detail["report"]["unassigned_total"] == Decimal("100.00")


def test_a_stop_from_another_trip_is_rejected(db, account, categories, trip):
    other = Trip(name="Other")
    db.add(other)
    db.flush()
    foreign = TripLeg(trip_id=other.id, place="Elsewhere", start_date=TRIP_START, end_date=TRIP_END)
    db.add(foreign)
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.commit()
    _tick(db, trip, lunch)

    with pytest.raises(HTTPException) as caught:
        trips_router.update_expense(
            trip.id, lunch.id, schemas.TripExpenseUpdate(leg_id=foreign.id), db
        )
    assert caught.value.status_code == 422


# ---------- trip dates ----------


def test_a_trip_has_both_dates_or_neither(db):
    with pytest.raises(HTTPException) as caught:
        trips_router.create_trip(schemas.TripIn(name="Half", start_date=TRIP_START), db)
    assert caught.value.status_code == 422


def test_a_trip_cannot_end_before_it_starts(db):
    with pytest.raises(HTTPException) as caught:
        trips_router.create_trip(
            schemas.TripIn(name="Backwards", start_date=TRIP_END, end_date=TRIP_START), db
        )
    assert caught.value.status_code == 422


def test_dates_can_be_cleared_again(db, trip):
    db.commit()
    detail = trips_router.update_trip(
        trip.id, schemas.TripUpdate(start_date=None, end_date=None), db
    )
    assert (detail["trip"].start_date, detail["report"]["nights"]) == (None, None)


# ---------- the ledger side ----------


def test_transactions_can_be_filtered_by_trip(db, account, categories, trip):
    """The trip page browses the ledger with these two filters."""
    other = Trip(name="Other")
    db.add(other)
    mine = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Mine", "Food")
    theirs = _tx(db, account, categories, date(2026, 5, 21), "-30.00", "Theirs", "Food")
    loose = _tx(db, account, categories, date(2026, 5, 22), "-15.00", "Loose", "Food")
    db.flush()
    db.add(TripExpense(transaction_id=theirs.id, trip_id=other.id))
    db.commit()
    _tick(db, trip, mine)

    in_trip = transactions_router.list_transactions(trip_id=trip.id, db=db)
    unclaimed = transactions_router.list_transactions(not_in_trip=True, db=db)
    count = transactions_router.count_transactions(trip_id=trip.id, db=db)

    assert [t.id for t in in_trip] == [mine.id]
    assert [t.id for t in unclaimed] == [loose.id]
    assert count == {"count": 1}


def test_a_transaction_says_which_trip_it_is_in(db, account, categories, trip):
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    loose = _tx(db, account, categories, date(2026, 5, 21), "-15.00", "Loose", "Food")
    db.commit()
    _tick(db, trip, lunch)
    trips_router.update_expense(trip.id, lunch.id, schemas.TripExpenseUpdate(group_name="Eats"), db)

    rows = {
        t.id: schemas.TransactionOut.model_validate(t)
        for t in transactions_router.list_transactions(db=db)
    }

    assert rows[loose.id].trip is None
    assert rows[lunch.id].trip.model_dump() == {
        "trip_id": trip.id,
        "trip_name": "West Coast",
        "group_name": "Eats",
        "leg_id": None,
        "share": Decimal("1.0000"),
    }
    # And the API spells it "trip", whatever the relationship is called.
    assert "trip" in rows[lunch.id].model_dump(by_alias=True)


def test_deleting_a_transaction_removes_it_from_its_trip():
    """Membership goes with the row through the foreign key, as splits and
    tags do — so the app's foreign-key pragma is switched on here."""
    engine = create_engine("sqlite:///:memory:", future=True)

    @event.listens_for(engine, "connect")
    def _fk_on(dbapi_connection, _):
        dbapi_connection.execute("PRAGMA foreign_keys = ON")

    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine, future=True)()
    try:
        account = Account(
            name="Card",
            account_category=AccountCategory.credit,
            type=AccountType.credit_card,
            sign_convention=SignConvention.outflow_negative,
        )
        trip = Trip(name="Trip")
        db.add_all([account, trip])
        db.flush()
        tx = Transaction(
            account_id=account.id,
            date=TRIP_START,
            description_raw="Diner",
            amount=Decimal("-22.00"),
            kind=TransactionKind.expense,
        )
        db.add(tx)
        db.flush()
        db.add(TripExpense(transaction_id=tx.id, trip_id=trip.id))
        db.commit()

        transactions_router.delete_transaction(tx.id, db)

        assert db.get(TripExpense, tx.id) is None
        assert trips.trip_report(db, db.get(Trip, trip.id))["total"] == Decimal("0")
    finally:
        db.close()
        engine.dispose()


def test_deleting_a_trip_leaves_the_transactions_alone(db, account, categories, trip):
    """A trip is a view of the ledger, not a container for it."""
    lunch = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "Diner", "Food")
    db.commit()
    _tick(db, trip, lunch)

    trips_router.delete_trip(trip.id, db)

    assert db.get(Transaction, lunch.id) is not None
    assert db.get(TripExpense, lunch.id) is None


def test_group_suggestions_span_every_trip(db, account, categories, trip):
    other = Trip(name="Other")
    db.add(other)
    a = _tx(db, account, categories, date(2026, 5, 20), "-22.00", "A", "Food")
    b = _tx(db, account, categories, date(2026, 5, 21), "-30.00", "B", "Food")
    db.commit()
    _tick(db, trip, a)
    _tick(db, other, b)
    trips_router.update_expense(trip.id, a.id, schemas.TripExpenseUpdate(group_name="Flight"), db)
    trips_router.update_expense(other.id, b.id, schemas.TripExpenseUpdate(group_name="hotel"), db)
    trips_router.add_item(
        trip.id, schemas.TripItemIn(label="x", amount=Decimal("1"), group_name="Flight"), db
    )

    assert trips.group_suggestions(db) == ["Flight", "hotel"]
