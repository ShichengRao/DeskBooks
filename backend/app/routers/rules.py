from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from .. import models, rules as rules_engine, schemas
from ..db import get_db

router = APIRouter(prefix="/api/rules", tags=["rules"])


@router.get("", response_model=list[schemas.RuleOut])
def list_rules(db: Session = Depends(get_db)):
    return list(db.scalars(select(models.Rule).order_by(models.Rule.priority.asc())))


@router.get("/proposals", response_model=list[schemas.RuleProposalOut])
def list_rule_proposals(
    min_support: int = 3,
    limit: int = 50,
    db: Session = Depends(get_db),
):
    return rules_engine.generate_rule_proposals(db, min_support=min_support, limit=limit)


@router.get("/coverage", response_model=schemas.RuleCoverageOut)
def get_rule_coverage(db: Session = Depends(get_db)):
    return rules_engine.coverage_summary(db)


@router.post("/proposals/backtest", response_model=schemas.RuleProposalOut)
def backtest_rule_proposal(
    body: schemas.RuleProposalBacktestIn,
    db: Session = Depends(get_db),
):
    return rules_engine.backtest_rule_proposal(db, **body.model_dump())


@router.post("/proposals/reject")
def reject_rule_proposal(
    body: schemas.RuleProposalRejectIn,
    db: Session = Depends(get_db),
):
    created = rules_engine.reject_rule_proposal(db, **body.model_dump())
    return {"status": "rejected", "created": created}


@router.post("", response_model=schemas.RuleOut)
def create_rule(body: schemas.RuleIn, db: Session = Depends(get_db)):
    obj = models.Rule(**body.model_dump())
    db.add(obj)
    db.commit()
    db.refresh(obj)
    return obj


@router.patch("/{rule_id}", response_model=schemas.RuleOut)
def update_rule(rule_id: int, body: schemas.RuleUpdate, db: Session = Depends(get_db)):
    obj = db.get(models.Rule, rule_id)
    if not obj:
        raise HTTPException(404)
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(obj, k, v)
    db.commit()
    db.refresh(obj)
    return obj


@router.post("/bulk-delete")
def bulk_delete_rules(body: schemas.RuleBulkDelete, db: Session = Depends(get_db)):
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
def delete_rule(rule_id: int, db: Session = Depends(get_db)):
    obj = db.get(models.Rule, rule_id)
    if not obj:
        raise HTTPException(404)
    db.execute(
        update(models.Transaction)
        .where(models.Transaction.matched_rule_id == rule_id)
        .values(matched_rule_id=None)
    )
    db.delete(obj)
    db.commit()
    return {"status": "deleted"}


@router.post("/reapply")
def reapply_rules(db: Session = Depends(get_db)):
    rows_changed, rules_fired = rules_engine.reapply_to_unreviewed(db)
    return {"rows_changed": rows_changed, "rules_fired": rules_fired}
