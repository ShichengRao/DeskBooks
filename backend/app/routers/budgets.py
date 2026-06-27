from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import budgets, models, schemas
from ..db import get_db

router = APIRouter(prefix="/api/budgets", tags=["budgets"])
DbSession = Annotated[Session, Depends(get_db)]


def _validate_budget_category(db: Session, category_id: int, amount: Decimal) -> None:
    if amount < Decimal("0"):
        raise HTTPException(400, "budget amount must be zero or greater")
    category = db.get(models.Category, category_id)
    if category is None:
        raise HTTPException(404, "category not found")
    if category.kind != models.CategoryKind.expense:
        raise HTTPException(400, "budgets can only target expense categories")


@router.get("", response_model=schemas.BudgetReport)
def get_budget(
    db: DbSession,
    start: date | None = None,
    end: date | None = None,
    focus_month: date | None = None,
    month: date | None = None,
):
    if month is not None and start is None and end is None:
        start = month
        end = month
        focus_month = month
    if start is None or end is None:
        raise HTTPException(400, "provide start/end or month")
    if end < start:
        raise HTTPException(400, "end must be on or after start")
    return budgets.budget_report(db, start, end, focus_month)


@router.put("/defaults", response_model=schemas.BudgetDefaultOut)
def upsert_budget_default(body: schemas.BudgetDefaultIn, db: DbSession):
    _validate_budget_category(db, body.category_id, body.amount)
    target = db.scalar(
        select(models.BudgetDefault).where(models.BudgetDefault.category_id == body.category_id)
    )
    if target is None:
        target = models.BudgetDefault(
            category_id=body.category_id,
            amount=body.amount,
            notes=body.notes,
        )
        db.add(target)
    else:
        target.amount = body.amount
        target.notes = body.notes
    db.commit()
    db.refresh(target)
    return target


@router.put("/overrides", response_model=schemas.BudgetOverrideOut)
def upsert_budget_override(body: schemas.BudgetOverrideIn, db: DbSession):
    _validate_budget_category(db, body.category_id, body.amount)
    month = budgets.normalize_month(body.month)
    target = db.scalar(
        select(models.BudgetOverride).where(
            models.BudgetOverride.month == month,
            models.BudgetOverride.category_id == body.category_id,
        )
    )
    if target is None:
        target = models.BudgetOverride(
            month=month,
            category_id=body.category_id,
            amount=body.amount,
            notes=body.notes,
        )
        db.add(target)
    else:
        target.amount = body.amount
        target.notes = body.notes
    db.commit()
    db.refresh(target)
    return target


def _delete_budget_row(db: Session, model, budget_id: int, missing_message: str) -> None:
    target = db.get(model, budget_id)
    if target is None:
        raise HTTPException(404, missing_message)
    db.delete(target)
    db.commit()


@router.delete("/defaults/{budget_id}")
def delete_budget_default(budget_id: int, db: DbSession):
    _delete_budget_row(db, models.BudgetDefault, budget_id, "budget default not found")
    return {"ok": True}


@router.delete("/overrides/{budget_id}")
def delete_budget_override(budget_id: int, db: DbSession):
    _delete_budget_row(db, models.BudgetOverride, budget_id, "budget override not found")
    return {"ok": True}
