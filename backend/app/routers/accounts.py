from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import models, schemas
from ..db import get_db

router = APIRouter(prefix="/api/accounts", tags=["accounts"])
DbSession = Annotated[Session, Depends(get_db)]


def _account_or_404(db: Session, account_id: int) -> models.Account:
    obj = db.get(models.Account, account_id)
    if not obj:
        raise HTTPException(404)
    return obj


def _save_account(db: Session, obj: models.Account) -> models.Account:
    db.commit()
    db.refresh(obj)
    return obj


def _apply_account_update(obj: models.Account, body: schemas.AccountUpdate) -> None:
    for key, value in body.model_dump(exclude_unset=True).items():
        setattr(obj, key, value)


def _account_list_stmt(include_closed: bool):
    stmt = select(models.Account).order_by(models.Account.sort_order, models.Account.name)
    if include_closed:
        return stmt
    return stmt.where(models.Account.is_closed.is_(False))


def _new_account(body: schemas.AccountIn) -> models.Account:
    return models.Account(**body.model_dump())


@router.get("", response_model=list[schemas.AccountOut])
def list_accounts(db: DbSession, include_closed: bool = True):
    return list(db.scalars(_account_list_stmt(include_closed)))


@router.post("", response_model=schemas.AccountOut)
def create_account(body: schemas.AccountIn, db: DbSession):
    obj = _new_account(body)
    db.add(obj)
    return _save_account(db, obj)


@router.get("/{account_id}", response_model=schemas.AccountOut)
def get_account(account_id: int, db: DbSession):
    return _account_or_404(db, account_id)


@router.patch("/{account_id}", response_model=schemas.AccountOut)
def update_account(account_id: int, body: schemas.AccountUpdate, db: DbSession):
    obj = _account_or_404(db, account_id)
    _apply_account_update(obj, body)
    return _save_account(db, obj)


@router.delete("/{account_id}")
def delete_account(account_id: int, db: DbSession):
    obj = _account_or_404(db, account_id)
    # do not actually delete if any transactions exist; mark closed
    has_tx = db.execute(
        select(models.Transaction.id).where(models.Transaction.account_id == account_id).limit(1)
    ).first()
    if has_tx:
        obj.is_closed = True
        db.commit()
        return {"status": "closed_instead_of_deleted"}
    db.delete(obj)
    db.commit()
    return {"status": "deleted"}
