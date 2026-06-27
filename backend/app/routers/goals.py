from __future__ import annotations

from decimal import Decimal

from fastapi import APIRouter
from sqlalchemy import select

from .. import models, schemas
from .common import DbSession, get_or_404

router = APIRouter(prefix="/api/goals", tags=["goals"])


def _goal_to_dict(g: models.Goal) -> dict:
    return {
        "title": g.title,
        "target_amount": str(g.target_amount) if g.target_amount is not None else None,
        "target_date": g.target_date.isoformat() if g.target_date else None,
        "kind": g.kind.value,
        "status": g.status.value,
        "linked_account_ids": g.linked_account_ids or [],
        "notes_markdown": g.notes_markdown,
        "sort_order": g.sort_order,
        "archived": g.archived,
    }


@router.get("", response_model=list[schemas.GoalOut])
def list_goals(db: DbSession, include_archived: bool = False):
    stmt = select(models.Goal).order_by(models.Goal.sort_order, models.Goal.created_at.desc())
    if not include_archived:
        stmt = stmt.where(models.Goal.archived.is_(False))
    return list(db.scalars(stmt))


@router.post("", response_model=schemas.GoalOut)
def create_goal(body: schemas.GoalIn, db: DbSession):
    obj = models.Goal(**body.model_dump())
    db.add(obj)
    db.flush()
    db.add(
        models.GoalRevision(
            goal_id=obj.id, snapshot=_goal_to_dict(obj), change_summary="created"
        )
    )
    db.commit()
    db.refresh(obj)
    return obj


@router.get("/{goal_id}", response_model=schemas.GoalOut)
def get_goal(goal_id: int, db: DbSession):
    return get_or_404(db, models.Goal, goal_id)


@router.patch("/{goal_id}", response_model=schemas.GoalOut)
def update_goal(goal_id: int, body: schemas.GoalUpdate, db: DbSession):
    obj = get_or_404(db, models.Goal, goal_id)
    data = body.model_dump(exclude_unset=True)
    change_summary = data.pop("change_summary", None)
    changed_fields: list[str] = []
    for k, v in data.items():
        if getattr(obj, k) != v:
            changed_fields.append(k)
            setattr(obj, k, v)
    if changed_fields:
        db.add(
            models.GoalRevision(
                goal_id=obj.id,
                snapshot=_goal_to_dict(obj),
                change_summary=change_summary or f"updated: {', '.join(changed_fields)}",
            )
        )
    db.commit()
    db.refresh(obj)
    return obj


@router.delete("/{goal_id}")
def archive_goal(goal_id: int, db: DbSession):
    obj = get_or_404(db, models.Goal, goal_id)
    obj.archived = True
    db.add(
        models.GoalRevision(
            goal_id=obj.id, snapshot=_goal_to_dict(obj), change_summary="archived"
        )
    )
    db.commit()
    return {"status": "archived"}


@router.get("/{goal_id}/revisions", response_model=list[schemas.GoalRevisionOut])
def list_revisions(goal_id: int, db: DbSession):
    return list(
        db.scalars(
            select(models.GoalRevision)
            .where(models.GoalRevision.goal_id == goal_id)
            .order_by(models.GoalRevision.changed_at.desc())
        )
    )


@router.get("/{goal_id}/progress")
def goal_progress(goal_id: int, db: DbSession):
    goal = get_or_404(db, models.Goal, goal_id)
    linked = goal.linked_account_ids or []
    if not linked:
        return {"current": None, "target": goal.target_amount, "percent": None}
    # latest snapshot's balances for the linked accounts
    latest = db.scalar(
        select(models.NetWorthSnapshot).order_by(models.NetWorthSnapshot.snapshot_date.desc())
    )
    if not latest:
        return {"current": None, "target": goal.target_amount, "percent": None}
    total = Decimal("0")
    for bal in latest.balances:
        if bal.account_id in linked and bal.balance is not None:
            total += bal.balance
    pct = None
    if goal.target_amount and goal.target_amount != 0:
        pct = float(total / goal.target_amount) * 100
    return {
        "current": total,
        "target": goal.target_amount,
        "percent": pct,
        "as_of": latest.snapshot_date,
    }
