from __future__ import annotations

from collections import Counter

from fastapi import APIRouter, HTTPException
from sqlalchemy import select

from .. import models, schemas
from .common import DbSession, add_and_refresh, apply_patch, get_or_404

router = APIRouter(prefix="/api/accounts", tags=["accounts"])


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
    return add_and_refresh(db, obj)


@router.post("/bulk", response_model=list[schemas.AccountOut])
def create_accounts_bulk(body: schemas.AccountBulkIn, db: DbSession):
    """All-or-nothing create for initial setup (~10 accounts at once).
    Name collisions come back as one 422 instead of a partial insert."""
    names = [account.name.strip() for account in body.accounts]
    if any(not name for name in names):
        raise HTTPException(422, "every account needs a name")
    repeated = sorted(name for name, count in Counter(names).items() if count > 1)
    if repeated:
        raise HTTPException(422, f"duplicate name(s) in request: {', '.join(repeated)}")
    taken = sorted(db.scalars(select(models.Account.name).where(models.Account.name.in_(names))))
    if taken:
        raise HTTPException(422, f"account name(s) already exist: {', '.join(taken)}")

    created = []
    for account, name in zip(body.accounts, names, strict=True):
        obj = _new_account(account)
        obj.name = name
        db.add(obj)
        created.append(obj)
    db.commit()
    for obj in created:
        db.refresh(obj)
    return created


@router.get("/{account_id}", response_model=schemas.AccountOut)
def get_account(account_id: int, db: DbSession):
    return get_or_404(db, models.Account, account_id)


@router.patch("/{account_id}", response_model=schemas.AccountOut)
def update_account(account_id: int, body: schemas.AccountUpdate, db: DbSession):
    obj = get_or_404(db, models.Account, account_id)
    apply_patch(obj, body)
    return add_and_refresh(db, obj)


@router.delete("/{account_id}")
def delete_account(account_id: int, db: DbSession):
    obj = get_or_404(db, models.Account, account_id)
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
