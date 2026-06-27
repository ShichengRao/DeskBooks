from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from .. import analytics, models, schemas
from ..db import get_db

router = APIRouter(prefix="/api/snapshots", tags=["snapshots"])


@router.get("", response_model=list[schemas.NetWorthSnapshotOut])
def list_snapshots(db: Session = Depends(get_db)):
    stmt = (
        select(models.NetWorthSnapshot)
        .options(selectinload(models.NetWorthSnapshot.balances))
        .order_by(models.NetWorthSnapshot.snapshot_date.desc())
    )
    return list(db.scalars(stmt))


@router.post("", response_model=schemas.NetWorthSnapshotOut)
def create_snapshot(body: schemas.NetWorthSnapshotIn, db: Session = Depends(get_db)):
    existing = db.scalar(
        select(models.NetWorthSnapshot).where(models.NetWorthSnapshot.snapshot_date == body.snapshot_date)
    )
    if existing:
        raise HTTPException(409, "snapshot for this date already exists")
    snap = models.NetWorthSnapshot(snapshot_date=body.snapshot_date, notes=body.notes)
    db.add(snap)
    db.flush()
    for b in body.balances:
        db.add(
            models.AccountBalance(
                snapshot_id=snap.id, account_id=b.account_id, balance=b.balance, notes=b.notes
            )
        )
    db.commit()
    db.refresh(snap)
    return snap


@router.patch("/{snap_id}", response_model=schemas.NetWorthSnapshotOut)
def update_snapshot(snap_id: int, body: schemas.NetWorthSnapshotUpdate, db: Session = Depends(get_db)):
    snap = db.get(models.NetWorthSnapshot, snap_id)
    if not snap:
        raise HTTPException(404)
    if body.snapshot_date is not None:
        snap.snapshot_date = body.snapshot_date
    if body.notes is not None:
        snap.notes = body.notes
    if body.balances is not None:
        # upsert balances
        existing = {b.account_id: b for b in snap.balances}
        seen = set()
        for b in body.balances:
            seen.add(b.account_id)
            if b.account_id in existing:
                existing[b.account_id].balance = b.balance
                existing[b.account_id].notes = b.notes
            else:
                db.add(
                    models.AccountBalance(
                        snapshot_id=snap.id,
                        account_id=b.account_id,
                        balance=b.balance,
                        notes=b.notes,
                    )
                )
        # delete balances not in payload
        for acc_id, bal in existing.items():
            if acc_id not in seen:
                db.delete(bal)
    db.commit()
    db.refresh(snap)
    return snap


@router.delete("/{snap_id}")
def delete_snapshot(snap_id: int, db: Session = Depends(get_db)):
    snap = db.get(models.NetWorthSnapshot, snap_id)
    if not snap:
        raise HTTPException(404)
    db.delete(snap)
    db.commit()
    return {"status": "deleted"}


@router.get("/series")
def snapshot_series(
    start: date | None = None,
    end: date | None = None,
    db: Session = Depends(get_db),
):
    if start is not None and end is not None and end < start:
        raise HTTPException(400, "end must be on or after start")
    return analytics.networth_series(db, start=start, end=end)
