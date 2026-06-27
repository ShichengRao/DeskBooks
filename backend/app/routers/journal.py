from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import models, schemas
from ..db import get_db

router = APIRouter(prefix="/api/journal", tags=["journal"])


@router.get("", response_model=list[schemas.JournalEntryOut])
def list_entries(goal_id: int | None = None, db: Session = Depends(get_db)):
    stmt = select(models.JournalEntry).order_by(models.JournalEntry.entry_date.desc())
    if goal_id is not None:
        stmt = stmt.where(models.JournalEntry.goal_id == goal_id)
    return list(db.scalars(stmt))


def _snapshot(obj: models.JournalEntry, change_summary: str | None) -> models.JournalEntryRevision:
    return models.JournalEntryRevision(
        entry_id=obj.id,
        title=obj.title,
        body_markdown=obj.body_markdown,
        entry_date=obj.entry_date,
        goal_id=obj.goal_id,
        change_summary=change_summary,
    )


@router.post("", response_model=schemas.JournalEntryOut)
def create_entry(body: schemas.JournalEntryIn, db: Session = Depends(get_db)):
    obj = models.JournalEntry(**body.model_dump())
    db.add(obj)
    db.flush()
    db.add(_snapshot(obj, "created"))
    db.commit()
    db.refresh(obj)
    return obj


@router.get("/{entry_id}", response_model=schemas.JournalEntryOut)
def get_entry(entry_id: int, db: Session = Depends(get_db)):
    obj = db.get(models.JournalEntry, entry_id)
    if not obj:
        raise HTTPException(404)
    return obj


@router.patch("/{entry_id}", response_model=schemas.JournalEntryOut)
def update_entry(entry_id: int, body: schemas.JournalEntryUpdate, db: Session = Depends(get_db)):
    obj = db.get(models.JournalEntry, entry_id)
    if not obj:
        raise HTTPException(404)
    data = body.model_dump(exclude_unset=True)
    change_summary = data.pop("change_summary", None)
    before = (obj.title, obj.body_markdown, obj.entry_date, obj.goal_id)
    for k, v in data.items():
        setattr(obj, k, v)
    after = (obj.title, obj.body_markdown, obj.entry_date, obj.goal_id)
    if after != before:
        db.add(_snapshot(obj, change_summary or "edited"))
    db.commit()
    db.refresh(obj)
    return obj


@router.delete("/{entry_id}")
def delete_entry(entry_id: int, db: Session = Depends(get_db)):
    obj = db.get(models.JournalEntry, entry_id)
    if not obj:
        raise HTTPException(404)
    db.delete(obj)
    db.commit()
    return {"status": "deleted"}


@router.get("/{entry_id}/revisions", response_model=list[schemas.JournalEntryRevisionOut])
def list_revisions(entry_id: int, db: Session = Depends(get_db)):
    return list(
        db.scalars(
            select(models.JournalEntryRevision)
            .where(models.JournalEntryRevision.entry_id == entry_id)
            .order_by(models.JournalEntryRevision.changed_at.desc())
        )
    )
