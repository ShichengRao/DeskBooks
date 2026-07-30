from __future__ import annotations

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import analytics as a
from .. import models, schemas
from ..db import get_db

router = APIRouter(prefix="/api/analytics", tags=["analytics"])
DbSession = Annotated[Session, Depends(get_db)]


@router.get("/monthly", response_model=list[schemas.MonthlyPoint])
def monthly(
    start: date,
    end: date,
    db: DbSession,
):
    return a.monthly_breakdown(db, start, end)


@router.get("/sankey", response_model=schemas.SankeyResponse)
def sankey(
    db: DbSession,
    year: int | None = None,
    start: date | None = None,
    end: date | None = None,
):
    if start is not None and end is not None:
        if end < start:
            raise HTTPException(400, "end must be on or after start")
        return a.sankey_for_period(db, start, end, f"{start.isoformat()} to {end.isoformat()}")
    if year is None:
        raise HTTPException(400, "provide either year or start/end")
    return a.yearly_sankey(db, year)


@router.get("/cashflow", response_model=schemas.SankeyResponse)
def cashflow(
    db: DbSession,
    start: date,
    end: date,
):
    if end < start:
        raise HTTPException(400, "end must be on or after start")
    return a.cashflow_sankey(db, start, end, f"{start.isoformat()} to {end.isoformat()}")


@router.get("/recurring", response_model=list[schemas.RecurringMerchant])
def recurring(
    db: DbSession,
    min_occurrences: int = 3,
    start: date | None = None,
    end: date | None = None,
):
    return a.recurring_merchants(db, min_occurrences=min_occurrences, start=start, end=end)


@router.get("/fire/settings", response_model=schemas.FireSettingsOut)
def get_fire_settings(db: DbSession):
    obj = db.scalar(select(models.FireSettings))
    if obj is None:
        obj = models.FireSettings()
        db.add(obj)
        db.commit()
        db.refresh(obj)
    return obj


@router.put("/fire/settings", response_model=schemas.FireSettingsOut)
def put_fire_settings(body: schemas.FireSettingsIn, db: DbSession):
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
def fire_projection(db: DbSession, max_years: int = 60):
    return a.fire_projection(db, max_years=max_years)


@router.get("/reconcile", response_model=schemas.ReconcileResponse)
def reconcile(
    db: DbSession,
    account_id: int,
    year: int | None = None,
    month: int | None = None,
    start: date | None = None,
    end: date | None = None,
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
def split_groups(start: date, end: date, db: DbSession):
    return a.split_group_summary(db, start, end)


@router.put("/reconcile", response_model=schemas.ReconcileResponse)
def upsert_reconcile(body: schemas.ReconcileIn, db: DbSession):
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
