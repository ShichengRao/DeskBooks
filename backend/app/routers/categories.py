from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from .. import models, schemas
from ..db import get_db

router = APIRouter(prefix="/api/categories", tags=["categories"])


@router.get("", response_model=list[schemas.CategoryOut])
def list_categories(include_archived: bool = False, db: Session = Depends(get_db)):
    stmt = select(models.Category).order_by(models.Category.sort_order, models.Category.name)
    if not include_archived:
        stmt = stmt.where(models.Category.archived.is_(False))
    return list(db.scalars(stmt))


@router.post("", response_model=schemas.CategoryOut)
def create_category(body: schemas.CategoryIn, db: Session = Depends(get_db)):
    obj = models.Category(**body.model_dump())
    db.add(obj)
    db.commit()
    db.refresh(obj)
    return obj


@router.patch("/{category_id}", response_model=schemas.CategoryOut)
def update_category(category_id: int, body: schemas.CategoryUpdate, db: Session = Depends(get_db)):
    obj = db.get(models.Category, category_id)
    if not obj:
        raise HTTPException(404)
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(obj, k, v)
    # Cascade kind change to transactions that aren't user-categorized — done in
    # the same transaction as the category update so a failure can't leave the
    # category updated with transactions inconsistent.
    if body.kind is not None:
        db.execute(
            update(models.Transaction)
            .where(
                models.Transaction.category_id == category_id,
                models.Transaction.is_user_categorized.is_(False),
            )
            .values(kind=models.TransactionKind(body.kind.value))
        )
    db.commit()
    db.refresh(obj)
    return obj


@router.delete("/{category_id}")
def delete_category(category_id: int, db: Session = Depends(get_db)):
    obj = db.get(models.Category, category_id)
    if not obj:
        raise HTTPException(404)
    obj.archived = True
    db.commit()
    return {"status": "archived"}
