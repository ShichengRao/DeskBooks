from __future__ import annotations

from typing import Annotated, TypeVar

from fastapi import Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import get_db

DbSession = Annotated[Session, Depends(get_db)]
T = TypeVar("T")


def get_or_404(db: Session, model: type[T], obj_id: int, detail: str | None = None) -> T:
    obj = db.get(model, obj_id)
    if not obj:
        raise HTTPException(404, detail)
    return obj


def add_and_refresh(db: Session, obj: T) -> T:
    db.add(obj)
    db.commit()
    db.refresh(obj)
    return obj


def commit_and_refresh(db: Session, obj: T) -> T:
    db.commit()
    db.refresh(obj)
    return obj


def apply_patch(obj: object, body) -> None:
    for key, value in body.model_dump(exclude_unset=True).items():
        setattr(obj, key, value)


def ok() -> dict[str, bool]:
    return {"ok": True}


def deleted() -> dict[str, str]:
    return {"status": "deleted"}
