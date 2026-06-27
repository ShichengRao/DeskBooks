from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import analytics as a, models, schemas
from ..db import get_db

router = APIRouter(prefix="/api/analytics", tags=["analytics"])


@router.get("/monthly")
def monthly(
    start: date = Query(...),
    end: date = Query(...),
    db: Session = Depends(get_db),
):
    return a.monthly_breakdown(db, start, end)


@router.get("/sankey")
def sankey(
    year: int | None = None,
    start: date | None = None,
    end: date | None = None,
    db: Session = Depends(get_db),
):
    if start is not None and end is not None:
        if end < start:
            raise HTTPException(400, "end must be on or after start")
        return a.sankey_for_period(db, start, end, f"{start.isoformat()} to {end.isoformat()}")
    if year is None:
        raise HTTPException(400, "provide either year or start/end")
    return a.yearly_sankey(db, year)


@router.get("/recurring")
def recurring(
    min_occurrences: int = 3,
    start: date | None = None,
    end: date | None = None,
    db: Session = Depends(get_db),
):
    return a.recurring_merchants(db, min_occurrences=min_occurrences, start=start, end=end)


@router.get("/fire/settings", response_model=schemas.FireSettingsOut)
def get_fire_settings(db: Session = Depends(get_db)):
    obj = db.scalar(select(models.FireSettings))
    if obj is None:
        obj = models.FireSettings()
        db.add(obj)
        db.commit()
        db.refresh(obj)
    return obj


@router.put("/fire/settings", response_model=schemas.FireSettingsOut)
def put_fire_settings(body: schemas.FireSettingsIn, db: Session = Depends(get_db)):
    obj = db.scalar(select(models.FireSettings))
    if obj is None:
        obj = models.FireSettings(**body.model_dump())
        db.add(obj)
    else:
        for k, v in body.model_dump().items():
            setattr(obj, k, v)
    db.commit()
    db.refresh(obj)
    return obj


@router.get("/fire/projection", response_model=schemas.FireProjection)
def fire_projection(max_years: int = 60, db: Session = Depends(get_db)):
    return a.fire_projection(db, max_years=max_years)


@router.get("/reconcile", response_model=schemas.ReconcileResponse)
def reconcile(
    account_id: int,
    year: int | None = None,
    month: int | None = None,
    start: date | None = None,
    end: date | None = None,
    db: Session = Depends(get_db),
):
    if start is not None or end is not None:
        if start is None or end is None:
            raise HTTPException(400, "provide both start and end")
        if end < start:
            raise HTTPException(400, "end must be on or after start")
        return a.reconcile_account_period(db, account_id, start, end)
    if year is None or month is None:
        raise HTTPException(400, "provide either year/month or start/end")
    return a.reconcile_account_month(db, account_id, year, month)


@router.get("/splits", response_model=list[schemas.SplitGroupSummary])
def split_groups(start: date, end: date, db: Session = Depends(get_db)):
    return a.split_group_summary(db, start, end)


@router.put("/reconcile", response_model=schemas.ReconcileResponse)
def upsert_reconcile(body: schemas.ReconcileIn, db: Session = Depends(get_db)):
    existing = db.scalar(
        select(models.MonthlyReconciliation).where(
            models.MonthlyReconciliation.account_id == body.account_id,
            models.MonthlyReconciliation.year == body.year,
            models.MonthlyReconciliation.month == body.month,
        )
    )
    if existing:
        existing.statement_total = body.statement_total
        existing.notes = body.notes
    else:
        db.add(
            models.MonthlyReconciliation(
                account_id=body.account_id,
                year=body.year,
                month=body.month,
                statement_total=body.statement_total,
                notes=body.notes,
            )
        )
    db.commit()
    return a.reconcile_account_month(db, body.account_id, body.year, body.month)
