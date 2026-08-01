from __future__ import annotations

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query
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
def fire_projection(db: DbSession, max_years: int = Query(60, ge=1, le=120)):
    return a.fire_projection(db, max_years=max_years)


@router.get("/splits", response_model=list[schemas.SplitGroupSummary])
def split_groups(start: date, end: date, db: DbSession):
    return a.split_group_summary(db, start, end)


@router.get("/cancel-candidates", response_model=list[schemas.CancelCandidateOut])
def cancel_candidates(start: date, end: date, db: DbSession, window_days: int = 45):
    """Unlinked transactions that come in equal-and-opposite amount pairs
    within window_days of each other — likely refunds, reversals, or
    reimbursements that should net out of cashflow once linked."""
    if end < start:
        raise HTTPException(400, "end must be on or after start")
    return a.cancel_out_candidates(db, start, end, window_days=window_days)


@router.get("/cancel-pairs", response_model=list[schemas.CancelPairOut])
def cancel_pairs(start: date, end: date, db: DbSession):
    """Pairs already linked via transfer_pair_id with at least one side in
    the range; newest first."""
    if end < start:
        raise HTTPException(400, "end must be on or after start")
    return a.linked_cancel_pairs(db, start, end)
