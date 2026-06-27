from __future__ import annotations

from fastapi import APIRouter
from sqlalchemy import select, update

from .. import models, schemas
from .. import rules as rules_engine
from .common import DbSession, add_and_refresh, apply_patch, commit_and_refresh, get_or_404

router = APIRouter(prefix="/api/rules", tags=["rules"])


@router.get("", response_model=list[schemas.RuleOut])
def list_rules(db: DbSession):
    return list(db.scalars(select(models.Rule).order_by(models.Rule.priority.asc())))


@router.get("/proposals", response_model=list[schemas.RuleProposalOut])
def list_rule_proposals(
    db: DbSession,
    min_support: int = 3,
    limit: int = 50,
):
    return rules_engine.generate_rule_proposals(db, min_support=min_support, limit=limit)


@router.get("/coverage", response_model=schemas.RuleCoverageOut)
def get_rule_coverage(db: DbSession):
    return rules_engine.coverage_summary(db)


@router.post("/proposals/backtest", response_model=schemas.RuleProposalOut)
def backtest_rule_proposal(
    body: schemas.RuleProposalBacktestIn,
    db: DbSession,
):
    return rules_engine.backtest_rule_proposal(db, **body.model_dump())


@router.post("/proposals/reject")
def reject_rule_proposal(
    body: schemas.RuleProposalRejectIn,
    db: DbSession,
):
    created = rules_engine.reject_rule_proposal(db, **body.model_dump())
    return {"status": "rejected", "created": created}


@router.post("", response_model=schemas.RuleOut)
def create_rule(body: schemas.RuleIn, db: DbSession):
    obj = models.Rule(**body.model_dump())
    return add_and_refresh(db, obj)


@router.patch("/{rule_id}", response_model=schemas.RuleOut)
def update_rule(rule_id: int, body: schemas.RuleUpdate, db: DbSession):
    obj = get_or_404(db, models.Rule, rule_id)
    apply_patch(obj, body)
    return commit_and_refresh(db, obj)


@router.post("/bulk-delete")
def bulk_delete_rules(body: schemas.RuleBulkDelete, db: DbSession):
    ids = sorted(set(body.ids))
    if not ids:
        return {"deleted": 0}
    db.execute(
        update(models.Transaction)
        .where(models.Transaction.matched_rule_id.in_(ids))
        .values(matched_rule_id=None)
    )
    rules = list(db.scalars(select(models.Rule).where(models.Rule.id.in_(ids))))
    for rule in rules:
        db.delete(rule)
    db.commit()
    return {"deleted": len(rules)}


@router.delete("/{rule_id}")
def delete_rule(rule_id: int, db: DbSession):
    obj = get_or_404(db, models.Rule, rule_id)
    db.execute(
        update(models.Transaction)
        .where(models.Transaction.matched_rule_id == rule_id)
        .values(matched_rule_id=None)
    )
    db.delete(obj)
    db.commit()
    return {"status": "deleted"}


@router.post("/reapply")
def reapply_rules(db: DbSession):
    rows_changed, rules_fired = rules_engine.reapply_to_unreviewed(db)
    return {"rows_changed": rows_changed, "rules_fired": rules_fired}
