from __future__ import annotations

from fastapi import APIRouter, HTTPException
from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from .. import models, schemas
from .common import DbSession, add_and_refresh, apply_patch, commit_and_refresh, get_or_404

router = APIRouter(prefix="/api/categories", tags=["categories"])


@router.get("/usage", response_model=list[schemas.CategoryUsage])
def category_usage(db: DbSession):
    """Reference counts per category (archived included), so the UI can
    warn before archiving something that's still in use."""

    def counts(column) -> dict[int, int]:
        return {
            cid: int(n)
            for cid, n in db.execute(
                select(column, func.count()).where(column.is_not(None)).group_by(column)
            )
        }

    tx = counts(models.Transaction.category_id)
    rules = counts(models.Rule.set_category_id)
    budgets = counts(models.BudgetDefault.category_id)
    for cid, n in counts(models.BudgetOverride.category_id).items():
        budgets[cid] = budgets.get(cid, 0) + n
    return [
        schemas.CategoryUsage(
            category_id=cid,
            transactions=tx.get(cid, 0),
            rules=rules.get(cid, 0),
            budgets=budgets.get(cid, 0),
        )
        for cid in db.scalars(select(models.Category.id))
    ]


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

    # One level of nesting max: the parent must be top-level, and a
    # category that has children of its own can't become someone's child.
    if parent.parent_id is not None:
        raise HTTPException(400, "only one level of nesting: that parent is itself a subcategory")
    if category_id is not None:
        has_children = db.scalar(
            select(func.count())
            .select_from(models.Category)
            .where(models.Category.parent_id == category_id)
        )
        if has_children:
            raise HTTPException(400, "category has subcategories of its own; move them first")


@router.post("/{category_id}/merge", response_model=schemas.CategoryMergeResult)
def merge_category(category_id: int, body: schemas.CategoryMergeIn, db: DbSession):
    """Move every reference (transactions, rules, budgets) from this
    category onto the target, then archive this one. The one-click way to
    collapse a redundant category without touching rows by hand."""
    source = get_or_404(db, models.Category, category_id)
    target = get_or_404(db, models.Category, body.target_id)
    if source.id == target.id:
        raise HTTPException(400, "cannot merge a category into itself")
    if target.archived:
        raise HTTPException(400, "target category is archived")
    if source.kind != target.kind:
        raise HTTPException(
            400,
            f"kinds differ ({source.kind.value} vs {target.kind.value}); "
            "change one of them first so analytics stay consistent",
        )
    if db.scalar(
        select(func.count())
        .select_from(models.Category)
        .where(models.Category.parent_id == source.id)
    ):
        raise HTTPException(400, "category has subcategories; move them first")

    transactions_moved = db.execute(
        update(models.Transaction)
        .where(models.Transaction.category_id == source.id)
        .values(category_id=target.id)
    ).rowcount
    rules_moved = db.execute(
        update(models.Rule)
        .where(models.Rule.set_category_id == source.id)
        .values(set_category_id=target.id)
    ).rowcount

    budgets_moved = 0
    # Budget rows are unique per category (and per month for overrides):
    # repoint the source's rows unless the target already has one for the
    # same slot, in which case the target's plan wins and the source's row
    # is dropped.
    target_default = db.scalar(
        select(models.BudgetDefault).where(models.BudgetDefault.category_id == target.id)
    )
    for row in db.scalars(
        select(models.BudgetDefault).where(models.BudgetDefault.category_id == source.id)
    ):
        if target_default is not None:
            db.delete(row)
        else:
            row.category_id = target.id
            budgets_moved += 1
    target_override_months = {
        month
        for month in db.scalars(
            select(models.BudgetOverride.month).where(
                models.BudgetOverride.category_id == target.id
            )
        )
    }
    for row in db.scalars(
        select(models.BudgetOverride).where(models.BudgetOverride.category_id == source.id)
    ):
        if row.month in target_override_months:
            db.delete(row)
        else:
            row.category_id = target.id
            budgets_moved += 1

    source.archived = True
    db.commit()
    return schemas.CategoryMergeResult(
        source_id=source.id,
        target_id=target.id,
        transactions_moved=transactions_moved,
        rules_moved=rules_moved,
        budgets_moved=budgets_moved,
    )


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
