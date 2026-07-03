from __future__ import annotations

from fastapi import APIRouter, HTTPException
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from .. import models, schemas
from .common import DbSession, add_and_refresh, apply_patch, commit_and_refresh, get_or_404

router = APIRouter(prefix="/api/categories", tags=["categories"])


@router.get("", response_model=list[schemas.CategoryOut])
def list_categories(db: DbSession, include_archived: bool = False):
    stmt = select(models.Category).order_by(models.Category.sort_order, models.Category.name)
    if not include_archived:
        stmt = stmt.where(models.Category.archived.is_(False))
    return list(db.scalars(stmt))


def _validate_parent(db: Session, *, category_id: int | None, parent_id: int | None) -> None:
    if parent_id is None:
        return
    if category_id is not None and parent_id == category_id:
        raise HTTPException(400, "category cannot be its own parent")
    parent = db.get(models.Category, parent_id)
    if parent is None:
        raise HTTPException(404, "parent category not found")

    seen: set[int] = set()
    current = parent
    while current is not None:
        if category_id is not None and current.id == category_id:
            raise HTTPException(400, "category parent cannot be a descendant")
        if current.id in seen:
            raise HTTPException(400, "category hierarchy contains a cycle")
        seen.add(current.id)
        current = db.get(models.Category, current.parent_id) if current.parent_id else None


@router.post("", response_model=schemas.CategoryOut)
def create_category(body: schemas.CategoryIn, db: DbSession):
    _validate_parent(db, category_id=None, parent_id=body.parent_id)
    obj = models.Category(**body.model_dump())
    return add_and_refresh(db, obj)


@router.patch("/{category_id}", response_model=schemas.CategoryOut)
def update_category(category_id: int, body: schemas.CategoryUpdate, db: DbSession):
    obj = get_or_404(db, models.Category, category_id)
    data = body.model_dump(exclude_unset=True)
    if "parent_id" in data:
        _validate_parent(db, category_id=category_id, parent_id=data["parent_id"])
    apply_patch(obj, body)
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
    return commit_and_refresh(db, obj)


@router.delete("/{category_id}")
def delete_category(category_id: int, db: DbSession):
    obj = get_or_404(db, models.Category, category_id)
    obj.archived = True
    db.commit()
    return {"status": "archived"}
