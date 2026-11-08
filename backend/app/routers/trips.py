from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from .. import models, schemas, trips
from ..db import get_db

router = APIRouter(prefix="/api/trips", tags=["trips"])
DB_DEP = Depends(get_db)


def _check_range(start: date, end: date, what: str) -> None:
    if end < start:
        raise HTTPException(422, f"{what} ends before it starts")


def _trip_or_404(db: Session, trip_id: int) -> models.Trip:
    trip = db.scalar(
        select(models.Trip).options(selectinload(models.Trip.legs)).where(models.Trip.id == trip_id)
    )
    if not trip:
        raise HTTPException(404, "trip not found")
    return trip


def _leg_of_trip(trip: models.Trip, leg_id: int | None) -> int | None:
    """A leg id, checked to belong to this trip. Stops a row being filed
    under someone else's stop."""
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


def _items(db: Session, trip_id: int) -> list[models.TripItem]:
    return list(
        db.scalars(
            select(models.TripItem)
            .where(models.TripItem.trip_id == trip_id)
            .order_by(models.TripItem.date, models.TripItem.id)
        )
    )


def _detail(db: Session, trip: models.Trip) -> dict:
    db.refresh(trip)
    return {
        "trip": trip,
        "items": _items(db, trip.id),
        "report": trips.trip_report(db, trip),
    }


def _autofile_unassigned(db: Session, trip: models.Trip) -> None:
    """File rows that have no stop onto whichever stop now covers them.

    Adding stops after reviewing the spending is the natural order — you
    confirm what the trip cost, then decide you want it split by city —
    and without this every row would stay under "no stop" and have to be
    moved by hand. Rows already filed somewhere are left alone: an
    explicit choice outranks the dates.
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
    stmt = (
        select(models.Trip)
        .options(selectinload(models.Trip.legs))
        .order_by(models.Trip.start_date.desc(), models.Trip.id.desc())
    )
    if not include_archived:
        stmt = stmt.where(models.Trip.archived.is_(False))
    out = []
    for trip in db.scalars(stmt):
        report = trips.trip_report(db, trip)
        out.append(
            {
                "trip": trip,
                "nights": report["nights"],
                "total": report["total"],
                "per_night": report["per_night"],
                "expense_count": report["expense_count"],
                "item_count": report["item_count"],
            }
        )
    return out


# Declared before /{trip_id} so "policies" is never parsed as a trip id.
@router.get("/policies", response_model=list[schemas.TripCategoryPolicyOut])
def list_policies(db: Session = DB_DEP):
    return list(
        db.scalars(
            select(models.TripCategoryPolicy).order_by(models.TripCategoryPolicy.category_id)
        )
    )


@router.put("/policies", response_model=list[schemas.TripCategoryPolicyOut])
def set_policies(body: schemas.TripCategoryPoliciesIn, db: Session = DB_DEP):
    """Record which categories are home life, which get booked ahead.

    Declared once and reused by every later trip — the work that makes
    the second trip quick.
    """
    for entry in body.policies:
        _category_or_404(db, entry.category_id)
        existing = db.get(models.TripCategoryPolicy, entry.category_id)
        if existing is None:
            db.add(models.TripCategoryPolicy(category_id=entry.category_id, policy=entry.policy))
        else:
            existing.policy = entry.policy
    for category_id in body.clear_category_ids:
        existing = db.get(models.TripCategoryPolicy, category_id)
        if existing is not None:
            db.delete(existing)
    db.commit()
    return list_policies(db)


@router.post("", response_model=schemas.TripDetail)
def create_trip(body: schemas.TripIn, db: Session = DB_DEP):
    _check_range(body.start_date, body.end_date, "trip")
    trip = models.Trip(
        name=body.name,
        start_date=body.start_date,
        end_date=body.end_date,
        notes=body.notes or None,
    )
    for index, leg in enumerate(body.legs):
        _check_range(leg.start_date, leg.end_date, f"leg {leg.place}")
        trip.legs.append(
            models.TripLeg(
                place=leg.place,
                start_date=leg.start_date,
                end_date=leg.end_date,
                sort_order=leg.sort_order or index,
                notes=leg.notes or None,
            )
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
    start = data.get("start_date", trip.start_date)
    end = data.get("end_date", trip.end_date)
    _check_range(start, end, "trip")
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


@router.get("/{trip_id}/candidates", response_model=schemas.TripCandidates)
def get_candidates(
    trip_id: int,
    lookback_days: int = Query(default=trips.DEFAULT_LOOKBACK_DAYS, ge=0, le=730),
    db: Session = DB_DEP,
):
    return trips.trip_candidates(db, _trip_or_404(db, trip_id), lookback_days=lookback_days)


@router.put("/{trip_id}/expenses", response_model=schemas.TripDetail)
def set_expenses(trip_id: int, body: schemas.TripExpensesIn, db: Session = DB_DEP):
    trip = _trip_or_404(db, trip_id)

    for transaction_id in body.remove_transaction_ids:
        existing = db.get(models.TripExpense, transaction_id)
        if existing is not None and existing.trip_id == trip.id:
            db.delete(existing)
    db.flush()

    for entry in body.expenses:
        tx = db.get(models.Transaction, entry.transaction_id)
        if tx is None:
            raise HTTPException(404, f"transaction {entry.transaction_id} not found")
        if tx.kind not in trips.COUNTABLE_KINDS:
            # Spend is stored outflow-negative and negated to read as a
            # cost, so an inflow filed onto a trip would subtract from
            # it. Refusing is better than a total that drifts downward
            # every time a paycheck lands inside the dates.
            raise HTTPException(
                422,
                f"a {tx.kind.value} row is money moving, not trip spending; "
                "only expenses, refunds and reimbursements can count toward a trip",
            )
        leg_id = _leg_of_trip(trip, entry.leg_id)
        if leg_id is None and body.auto_assign_legs:
            leg_id = trips.leg_for_date(trip, tx.date)
        existing = db.get(models.TripExpense, entry.transaction_id)
        if existing is None:
            db.add(
                models.TripExpense(
                    transaction_id=entry.transaction_id,
                    trip_id=trip.id,
                    leg_id=leg_id,
                    share=entry.share,
                    notes=entry.notes or None,
                )
            )
            continue
        if existing.trip_id != trip.id:
            # One row, one trip: two overlapping trips must not each
            # count the same charge.
            raise HTTPException(
                409,
                f"transaction {entry.transaction_id} already belongs to another trip",
            )
        existing.leg_id = leg_id
        existing.share = entry.share
        existing.notes = entry.notes or None

    db.commit()
    return _detail(db, trip)


@router.post("/{trip_id}/legs", response_model=schemas.TripDetail)
def add_leg(trip_id: int, body: schemas.TripLegIn, db: Session = DB_DEP):
    trip = _trip_or_404(db, trip_id)
    _check_range(body.start_date, body.end_date, f"leg {body.place}")
    trip.legs.append(
        models.TripLeg(
            place=body.place,
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
        raise HTTPException(404, "leg not found")
    data = body.model_dump(exclude_unset=True)
    _check_range(
        data.get("start_date", leg.start_date),
        data.get("end_date", leg.end_date),
        f"leg {leg.place}",
    )
    for field, value in data.items():
        setattr(leg, field, value)
    _autofile_unassigned(db, trip)
    db.commit()
    return _detail(db, trip)


@router.delete("/{trip_id}/legs/{leg_id}", response_model=schemas.TripDetail)
def delete_leg(trip_id: int, leg_id: int, db: Session = DB_DEP):
    """Rows filed under the leg stay on the trip; their `leg_id` clears,
    so deleting a stop never silently drops its spending."""
    trip = _trip_or_404(db, trip_id)
    leg = db.get(models.TripLeg, leg_id)
    if leg is None or leg.trip_id != trip.id:
        raise HTTPException(404, "leg not found")
    db.delete(leg)
    db.commit()
    return _detail(db, trip)


@router.post("/{trip_id}/items", response_model=schemas.TripDetail)
def add_item(trip_id: int, body: schemas.TripItemIn, db: Session = DB_DEP):
    """A cost with no transaction behind it: cash, a fare, someone else's
    card."""
    trip = _trip_or_404(db, trip_id)
    leg_id = _leg_of_trip(trip, body.leg_id)
    if leg_id is None:
        leg_id = trips.leg_for_date(trip, body.date)
    db.add(
        models.TripItem(
            trip_id=trip.id,
            leg_id=leg_id,
            category_id=_category_or_404(db, body.category_id),
            label=body.label,
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
