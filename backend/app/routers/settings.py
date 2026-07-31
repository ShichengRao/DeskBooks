from __future__ import annotations

from fastapi import APIRouter, HTTPException
from sqlalchemy import func, select

from .. import models, schemas
from .common import DbSession

router = APIRouter(prefix="/api/settings", tags=["settings"])


def _settings_row(db) -> models.AppSettings:
    obj = db.get(models.AppSettings, 1)
    if obj is None:
        obj = models.AppSettings(id=1, hidden_kinds=[])
        db.add(obj)
        db.commit()
        db.refresh(obj)
    return obj


def _kind_settings(db) -> dict:
    obj = _settings_row(db)
    counts = {kind.value: 0 for kind in models.TransactionKind}
    for kind, n in db.execute(
        select(models.Transaction.kind, func.count()).group_by(models.Transaction.kind)
    ):
        counts[kind.value] = int(n)
    return {"hidden": list(obj.hidden_kinds or []), "counts": counts}


@router.get("/kinds", response_model=schemas.KindSettingsOut)
def get_kind_settings(db: DbSession):
    """Which transaction kinds are hidden from pickers, plus how many
    rows currently carry each kind (so the UI can warn)."""
    return _kind_settings(db)


@router.put("/kinds", response_model=schemas.KindSettingsOut)
def put_kind_settings(body: schemas.KindSettingsIn, db: DbSession):
    hidden = sorted({kind.value for kind in body.hidden})
    if models.TransactionKind.uncategorized.value in hidden:
        raise HTTPException(400, "the uncategorized kind cannot be hidden")
    obj = _settings_row(db)
    obj.hidden_kinds = hidden
    db.commit()
    return _kind_settings(db)
