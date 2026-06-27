from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session, selectinload

from .. import models, schemas
from ..db import get_db

router = APIRouter(prefix="/api/transactions", tags=["transactions"])
DB_DEP = Depends(get_db)


class TransactionFilters(BaseModel):
    start: date | None = None
    end: date | None = None
    account_id: int | None = None
    account_category: list[models.AccountCategory] | None = None
    category_id: int | None = None
    kind: list[models.TransactionKind] | None = None
    amount_min: Decimal | None = None
    amount_max: Decimal | None = None
    q: str | None = None
    exclude_excluded: bool = False


class TransactionPage(BaseModel):
    limit: int = Field(default=100, ge=1)
    offset: int = Field(default=0, ge=0)


def _normalize_description(description: str) -> str:
    return " ".join(description.split())


def _apply_filters(stmt, filters: TransactionFilters):
    if filters.start:
        stmt = stmt.where(models.Transaction.date >= filters.start)
    if filters.end:
        stmt = stmt.where(models.Transaction.date <= filters.end)
    if filters.account_id:
        stmt = stmt.where(models.Transaction.account_id == filters.account_id)
    if filters.account_category:
        # Join to Account so we can filter on its category (e.g. "credit"
        # vs "bank"). Keeps the original Transaction projection intact.
        stmt = stmt.join(models.Account, models.Account.id == models.Transaction.account_id).where(
            models.Account.account_category.in_(filters.account_category)
        )
    if filters.category_id:
        stmt = stmt.where(models.Transaction.category_id == filters.category_id)
    if filters.kind:
        stmt = stmt.where(models.Transaction.kind.in_(filters.kind))
    if filters.amount_min is not None:
        stmt = stmt.where(models.Transaction.amount >= filters.amount_min)
    if filters.amount_max is not None:
        stmt = stmt.where(models.Transaction.amount <= filters.amount_max)
    if filters.q:
        like = f"%{filters.q}%"
        stmt = stmt.where(
            or_(
                models.Transaction.description_raw.ilike(like),
                models.Transaction.description_normalized.ilike(like),
                models.Transaction.merchant.ilike(like),
                models.Transaction.notes.ilike(like),
            )
        )
    if filters.exclude_excluded:
        stmt = stmt.where(models.Transaction.is_excluded_from_totals.is_(False))
    return stmt


@router.get("", response_model=list[schemas.TransactionOut])
def list_transactions(
    filters: Annotated[TransactionFilters, Query()],
    page: Annotated[TransactionPage, Query()],
    db: Session = DB_DEP,
):
    stmt = (
        select(models.Transaction)
        .options(selectinload(models.Transaction.tags), selectinload(models.Transaction.split))
        .order_by(models.Transaction.date.desc(), models.Transaction.id.desc())
    )
    stmt = _apply_filters(stmt, filters)
    stmt = stmt.limit(page.limit).offset(page.offset)
    return list(db.scalars(stmt))


@router.get("/count")
def count_transactions(
    filters: Annotated[TransactionFilters, Query()],
    db: Session = DB_DEP,
):
    stmt = select(func.count(models.Transaction.id))
    stmt = _apply_filters(stmt, filters)
    return {"count": db.scalar(stmt) or 0}


@router.post("", response_model=schemas.TransactionOut)
def create_transaction(body: schemas.TransactionIn, db: Session = DB_DEP):
    account = db.get(models.Account, body.account_id)
    if not account:
        raise HTTPException(404, "account not found")

    category = db.get(models.Category, body.category_id) if body.category_id else None
    if body.category_id and category is None:
        raise HTTPException(404, "category not found")

    kind = body.kind
    if category and "kind" not in body.model_fields_set:
        kind = models.TransactionKind(category.kind.value)

    tx = models.Transaction(
        account_id=body.account_id,
        date=body.date,
        post_date=body.post_date,
        description_raw=body.description_raw,
        description_normalized=body.description_normalized
        or _normalize_description(body.description_raw),
        merchant=body.merchant or None,
        amount=body.amount,
        category_id=body.category_id,
        kind=kind,
        is_user_categorized=True,
        is_excluded_from_totals=body.is_excluded_from_totals,
        notes=body.notes or None,
        matched_rule_id=None,
        raw={"source": "manual"},
    )
    db.add(tx)
    db.commit()
    db.refresh(tx)
    return tx


@router.get("/{tx_id}", response_model=schemas.TransactionOut)
def get_transaction(tx_id: int, db: Session = DB_DEP):
    tx = db.scalar(
        select(models.Transaction)
        .options(selectinload(models.Transaction.tags), selectinload(models.Transaction.split))
        .where(models.Transaction.id == tx_id)
    )
    if not tx:
        raise HTTPException(404)
    return tx


def _set_split(tx: models.Transaction, body: schemas.TransactionSplitIn) -> None:
    if not body.group_name:
        tx.split = None
        return
    share = max(Decimal("0"), min(Decimal("1"), body.personal_share))
    if tx.split is None:
        tx.split = models.TransactionSplit(
            group_name=body.group_name,
            personal_share=share,
            notes=body.notes,
        )
    else:
        tx.split.group_name = body.group_name
        tx.split.personal_share = share
        tx.split.notes = body.notes


@router.put("/{tx_id}/split", response_model=schemas.TransactionOut)
def set_transaction_split(
    tx_id: int, body: schemas.TransactionSplitIn, db: Session = DB_DEP
):
    tx = db.scalar(
        select(models.Transaction)
        .options(selectinload(models.Transaction.tags), selectinload(models.Transaction.split))
        .where(models.Transaction.id == tx_id)
    )
    if not tx:
        raise HTTPException(404)
    _set_split(tx, body)
    db.commit()
    db.refresh(tx)
    return tx


@router.patch("/{tx_id}", response_model=schemas.TransactionOut)
def update_transaction(
    tx_id: int, body: schemas.TransactionUpdate, db: Session = DB_DEP
):
    tx = db.get(models.Transaction, tx_id)
    if not tx:
        raise HTTPException(404)
    data = body.model_dump(exclude_unset=True)
    # If category is being changed by user, mark as user-categorized AND
    # derive kind from the new category unless the user provided one.
    if "category_id" in data:
        tx.is_user_categorized = True
        tx.matched_rule_id = None  # user override breaks the rule attribution
        cat = db.get(models.Category, data["category_id"]) if data["category_id"] else None
        if cat and "kind" not in data:
            tx.kind = models.TransactionKind(cat.kind.value)
    if "kind" in data:
        tx.is_user_categorized = True
        tx.matched_rule_id = None
    if "description_raw" in data and "description_normalized" not in data:
        raw = data["description_raw"]
        data["description_normalized"] = _normalize_description(raw) if raw else None
    for k, v in data.items():
        setattr(tx, k, v)
    db.commit()
    db.refresh(tx)
    return tx


def _apply_bulk_category(
    tx: models.Transaction,
    body: schemas.TransactionBulkUpdate,
    new_cat: models.Category | None,
) -> None:
    if body.category_id is None:
        return
    tx.category_id = body.category_id
    tx.is_user_categorized = True
    tx.matched_rule_id = None
    if new_cat and body.kind is None:
        tx.kind = models.TransactionKind(new_cat.kind.value)


def _apply_bulk_kind(tx: models.Transaction, body: schemas.TransactionBulkUpdate) -> None:
    if body.kind is None:
        return
    tx.kind = body.kind
    tx.is_user_categorized = True
    tx.matched_rule_id = None


def _apply_bulk_split(tx: models.Transaction, body: schemas.TransactionBulkUpdate) -> None:
    if body.clear_split:
        tx.split = None
        return
    if not body.split_group_name:
        return
    _set_split(
        tx,
        schemas.TransactionSplitIn(
            group_name=body.split_group_name,
            personal_share=body.split_personal_share
            if body.split_personal_share is not None
            else Decimal("0.5"),
            notes=body.split_notes,
        ),
    )


def _tags_by_id(db: Session, tag_ids: list[int] | None) -> dict[int, models.Tag]:
    if not tag_ids:
        return {}
    tags = db.scalars(select(models.Tag).where(models.Tag.id.in_(tag_ids))).all()
    return {tag.id: tag for tag in tags}


def _apply_bulk_tag_changes(
    tx: models.Transaction,
    body: schemas.TransactionBulkUpdate,
    tags_by_id: dict[int, models.Tag],
) -> None:
    if body.add_tag_ids:
        existing = {t.id for t in tx.tags}
        for tag_id in body.add_tag_ids:
            if tag_id not in existing and tag_id in tags_by_id:
                tx.tags.append(tags_by_id[tag_id])
    if body.remove_tag_ids:
        tx.tags = [t for t in tx.tags if t.id not in body.remove_tag_ids]


@router.patch("/bulk/update")
def bulk_update(body: schemas.TransactionBulkUpdate, db: Session = DB_DEP):
    if not body.ids:
        return {"updated": 0}
    txs = list(db.scalars(select(models.Transaction).where(models.Transaction.id.in_(body.ids))))
    new_cat = db.get(models.Category, body.category_id) if body.category_id else None
    tags_by_id = _tags_by_id(db, body.add_tag_ids)
    for tx in txs:
        _apply_bulk_category(tx, body, new_cat)
        _apply_bulk_kind(tx, body)
        if body.is_excluded_from_totals is not None:
            tx.is_excluded_from_totals = body.is_excluded_from_totals
        _apply_bulk_split(tx, body)
        _apply_bulk_tag_changes(tx, body, tags_by_id)
    db.commit()
    return {"updated": len(txs)}


@router.post("/pair")
def pair_transactions(body: schemas.TransactionPair, db: Session = DB_DEP):
    a = db.get(models.Transaction, body.transaction_a_id)
    b = db.get(models.Transaction, body.transaction_b_id)
    if not a or not b:
        raise HTTPException(404, "transactions not found")
    a.transfer_pair_id = b.id
    b.transfer_pair_id = a.id
    a.kind = models.TransactionKind.transfer
    b.kind = models.TransactionKind.transfer
    a.is_user_categorized = True
    b.is_user_categorized = True
    db.commit()
    return {"status": "paired"}


@router.post("/{tx_id}/unpair")
def unpair_transaction(tx_id: int, db: Session = DB_DEP):
    a = db.get(models.Transaction, tx_id)
    if not a or a.transfer_pair_id is None:
        raise HTTPException(404)
    other = db.get(models.Transaction, a.transfer_pair_id)
    if other:
        other.transfer_pair_id = None
    a.transfer_pair_id = None
    db.commit()
    return {"status": "unpaired"}


@router.delete("/{tx_id}")
def delete_transaction(tx_id: int, db: Session = DB_DEP):
    tx = db.get(models.Transaction, tx_id)
    if not tx:
        raise HTTPException(404)
    if tx.transfer_pair_id is not None:
        other = db.get(models.Transaction, tx.transfer_pair_id)
        if other:
            other.transfer_pair_id = None
    db.delete(tx)
    db.commit()
    return {"status": "deleted"}
