from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from .. import models, schemas, trips
from ..db import get_db

router = APIRouter(prefix="/api/trips", tags=["trips"])
DB_DEP = Depends(get_db)


def _check_range(start: date, end: date, what: str) -> None:
    if end < start:
        raise HTTPException(422, f"{what} ends before it starts")


def _check_trip_dates(start: date | None, end: date | None) -> None:
    """A trip has both dates or neither. Half a range would make the date
    filter and the per-night figure each guess at the missing end."""
    if (start is None) != (end is None):
        raise HTTPException(422, "give a trip both dates, or neither")
    if start is not None and end is not None:
        _check_range(start, end, "trip")


def _trip_or_404(db: Session, trip_id: int) -> models.Trip:
    trip = db.scalar(
        select(models.Trip).options(selectinload(models.Trip.legs)).where(models.Trip.id == trip_id)
    )
    if not trip:
        raise HTTPException(404, "trip not found")
    return trip


def _leg_of_trip(trip: models.Trip, leg_id: int | None) -> int | None:
    """A leg id, checked to belong to this trip, so a row can't be filed
    under another trip's stop."""
    if leg_id is None:
        return None
    if leg_id not in {leg.id for leg in trip.legs}:
        raise HTTPException(422, "leg does not belong to this trip")
    return leg_id


def _category_or_404(db: Session, category_id: int | None) -> int | None:
    if category_id is None:
        return None
    if db.get(models.Category, category_id) is None:
        raise HTTPException(404, "category not found")
    return category_id


def _clean_group(name: str | None) -> str | None:
    """Blank means "use the category name", the same as never set."""
    if name is None:
        return None
    return name.strip() or None


def _detail(db: Session, trip: models.Trip) -> dict:
    db.refresh(trip)
    return {
        "trip": trip,
        "items": list(
            db.scalars(
                select(models.TripItem)
                .where(models.TripItem.trip_id == trip.id)
                .order_by(models.TripItem.date, models.TripItem.id)
            )
        ),
        "report": trips.trip_report(db, trip),
        "group_suggestions": trips.group_suggestions(db),
    }


def _autofile_unassigned(db: Session, trip: models.Trip) -> None:
    """File rows that have no stop onto whichever stop now covers them.

    Adding stops after the spending is in is the natural order — you
    collect what the trip cost, then decide you want it split by city —
    and without this every row would stay unfiled. Rows already filed
    somewhere are left alone: an explicit choice outranks the dates.
    """
    db.flush()
    memberships = db.scalars(
        select(models.TripExpense).where(
            models.TripExpense.trip_id == trip.id, models.TripExpense.leg_id.is_(None)
        )
    ).all()
    if memberships:
        dates = dict(
            db.execute(
                select(models.Transaction.id, models.Transaction.date).where(
                    models.Transaction.id.in_([m.transaction_id for m in memberships])
                )
            ).all()
        )
        for membership in memberships:
            membership.leg_id = trips.leg_for_date(trip, dates.get(membership.transaction_id))
    for item in db.scalars(
        select(models.TripItem).where(
            models.TripItem.trip_id == trip.id, models.TripItem.leg_id.is_(None)
        )
    ).all():
        item.leg_id = trips.leg_for_date(trip, item.date)


@router.get("", response_model=list[schemas.TripSummary])
def list_trips(include_archived: bool = False, db: Session = DB_DEP):
    stmt = select(models.Trip).options(selectinload(models.Trip.legs))
    if not include_archived:
        stmt = stmt.where(models.Trip.archived.is_(False))
    totals = trips.trip_totals(db)
    # Undated trips first — they are the ones still being put together —
    # then the most recent first.
    rows = sorted(
        db.scalars(stmt),
        key=lambda t: (
            t.start_date is not None,
            -(t.start_date.toordinal() if t.start_date else 0),
            -t.id,
        ),
    )
    empty = {"total": 0, "expense_count": 0, "item_count": 0}
    return [{"trip": trip, **totals.get(trip.id, empty)} for trip in rows]


@router.get("/groups", response_model=list[str])
def list_groups(db: Session = DB_DEP):
    return trips.group_suggestions(db)


@router.post("", response_model=schemas.TripDetail)
def create_trip(body: schemas.TripIn, db: Session = DB_DEP):
    _check_trip_dates(body.start_date, body.end_date)
    trip = models.Trip(
        name=body.name.strip(),
        start_date=body.start_date,
        end_date=body.end_date,
        notes=body.notes or None,
    )
    db.add(trip)
    db.commit()
    return _detail(db, trip)


@router.get("/{trip_id}", response_model=schemas.TripDetail)
def get_trip(trip_id: int, db: Session = DB_DEP):
    return _detail(db, _trip_or_404(db, trip_id))


@router.patch("/{trip_id}", response_model=schemas.TripDetail)
def update_trip(trip_id: int, body: schemas.TripUpdate, db: Session = DB_DEP):
    trip = _trip_or_404(db, trip_id)
    data = body.model_dump(exclude_unset=True)
    _check_trip_dates(data.get("start_date", trip.start_date), data.get("end_date", trip.end_date))
    if "name" in data and data["name"] is not None:
        data["name"] = data["name"].strip()
    for field, value in data.items():
        setattr(trip, field, value)
    db.commit()
    return _detail(db, trip)


@router.delete("/{trip_id}")
def delete_trip(trip_id: int, db: Session = DB_DEP):
    """Deletes the trip and its membership rows. The transactions
    themselves are untouched — a trip is a view of the ledger, not a
    container for it."""
    trip = _trip_or_404(db, trip_id)
    db.delete(trip)
    db.commit()
    return {"status": "deleted"}


@router.put("/{trip_id}/expenses", response_model=schemas.TripDetail)
def change_membership(trip_id: int, body: schemas.TripMembershipChange, db: Session = DB_DEP):
    """Tick and untick rows. Adding a row already in this trip is a no-op,
    so a page of ticks can be resent without resetting anything."""
    trip = _trip_or_404(db, trip_id)

    for transaction_id in body.remove_transaction_ids:
        existing = db.get(models.TripExpense, transaction_id)
        if existing is not None and existing.trip_id == trip.id:
            db.delete(existing)
    db.flush()

    for transaction_id in dict.fromkeys(body.add_transaction_ids):
        tx = db.get(models.Transaction, transaction_id)
        if tx is None:
            raise HTTPException(404, f"transaction {transaction_id} not found")
        existing = db.get(models.TripExpense, transaction_id)
        if existing is not None:
            if existing.trip_id == trip.id:
                continue
            # One row, one trip: two overlapping trips must not each
            # count the same charge.
            raise HTTPException(
                409, f"transaction {transaction_id} already counts toward another trip"
            )
        if tx.kind not in trips.COUNTABLE_KINDS:
            raise HTTPException(
                422,
                f"a {tx.kind.value} row is money moving, not trip spending; "
                "only expenses, refunds and reimbursements can count toward a trip",
            )
        db.add(
            models.TripExpense(
                transaction_id=transaction_id,
                trip_id=trip.id,
                leg_id=trips.leg_for_date(trip, tx.date),
            )
        )

    db.commit()
    return _detail(db, trip)


@router.patch("/{trip_id}/expenses/{transaction_id}", response_model=schemas.TripDetail)
def update_expense(
    trip_id: int,
    transaction_id: int,
    body: schemas.TripExpenseUpdate,
    db: Session = DB_DEP,
):
    trip = _trip_or_404(db, trip_id)
    membership = db.get(models.TripExpense, transaction_id)
    if membership is None or membership.trip_id != trip.id:
        raise HTTPException(404, "that transaction is not in this trip")
    data = body.model_dump(exclude_unset=True)
    if "leg_id" in data:
        data["leg_id"] = _leg_of_trip(trip, data["leg_id"])
    if "group_name" in data:
        data["group_name"] = _clean_group(data["group_name"])
    if "share" in data and data["share"] is None:
        data["share"] = 1
    for field, value in data.items():
        setattr(membership, field, value)
    db.commit()
    return _detail(db, trip)


@router.post("/{trip_id}/legs", response_model=schemas.TripDetail)
def add_leg(trip_id: int, body: schemas.TripLegIn, db: Session = DB_DEP):
    trip = _trip_or_404(db, trip_id)
    _check_range(body.start_date, body.end_date, f"stop {body.place}")
    trip.legs.append(
        models.TripLeg(
            place=body.place.strip(),
            start_date=body.start_date,
            end_date=body.end_date,
            sort_order=body.sort_order or len(trip.legs),
            notes=body.notes or None,
        )
    )
    _autofile_unassigned(db, trip)
    db.commit()
    return _detail(db, trip)


@router.patch("/{trip_id}/legs/{leg_id}", response_model=schemas.TripDetail)
def update_leg(trip_id: int, leg_id: int, body: schemas.TripLegUpdate, db: Session = DB_DEP):
    trip = _trip_or_404(db, trip_id)
    leg = db.get(models.TripLeg, leg_id)
    if leg is None or leg.trip_id != trip.id:
        raise HTTPException(404, "stop not found")
    data = body.model_dump(exclude_unset=True)
    _check_range(
        data.get("start_date", leg.start_date),
        data.get("end_date", leg.end_date),
        f"stop {leg.place}",
    )
    for field, value in data.items():
        setattr(leg, field, value)
    _autofile_unassigned(db, trip)
    db.commit()
    return _detail(db, trip)


@router.delete("/{trip_id}/legs/{leg_id}", response_model=schemas.TripDetail)
def delete_leg(trip_id: int, leg_id: int, db: Session = DB_DEP):
    """Rows filed under the stop stay on the trip; their stop clears, so
    deleting a stop never silently drops its spending."""
    trip = _trip_or_404(db, trip_id)
    leg = db.get(models.TripLeg, leg_id)
    if leg is None or leg.trip_id != trip.id:
        raise HTTPException(404, "stop not found")
    db.delete(leg)
    db.commit()
    return _detail(db, trip)


@router.post("/{trip_id}/items", response_model=schemas.TripDetail)
def add_item(trip_id: int, body: schemas.TripItemIn, db: Session = DB_DEP):
    """A cost or credit with no transaction behind it: cash, a ride paid
    from a PayPal balance, someone else's card."""
    trip = _trip_or_404(db, trip_id)
    leg_id = _leg_of_trip(trip, body.leg_id)
    if leg_id is None:
        leg_id = trips.leg_for_date(trip, body.date)
    db.add(
        models.TripItem(
            trip_id=trip.id,
            leg_id=leg_id,
            category_id=_category_or_404(db, body.category_id),
            group_name=_clean_group(body.group_name),
            label=body.label.strip(),
            amount=body.amount,
            date=body.date,
            notes=body.notes or None,
        )
    )
    db.commit()
    return _detail(db, trip)


@router.patch("/{trip_id}/items/{item_id}", response_model=schemas.TripDetail)
def update_item(trip_id: int, item_id: int, body: schemas.TripItemUpdate, db: Session = DB_DEP):
    trip = _trip_or_404(db, trip_id)
    item = db.get(models.TripItem, item_id)
    if item is None or item.trip_id != trip.id:
        raise HTTPException(404, "item not found")
    data = body.model_dump(exclude_unset=True)
    if "leg_id" in data:
        data["leg_id"] = _leg_of_trip(trip, data["leg_id"])
    if "category_id" in data:
        data["category_id"] = _category_or_404(db, data["category_id"])
    if "group_name" in data:
        data["group_name"] = _clean_group(data["group_name"])
    for field, value in data.items():
        setattr(item, field, value)
    db.commit()
    return _detail(db, trip)


@router.delete("/{trip_id}/items/{item_id}", response_model=schemas.TripDetail)
def delete_item(trip_id: int, item_id: int, db: Session = DB_DEP):
    trip = _trip_or_404(db, trip_id)
    item = db.get(models.TripItem, item_id)
    if item is None or item.trip_id != trip.id:
        raise HTTPException(404, "item not found")
    db.delete(item)
    db.commit()
    return _detail(db, trip)


__all__ = ["router"]
