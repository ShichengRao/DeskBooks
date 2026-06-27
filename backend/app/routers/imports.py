from __future__ import annotations

from collections import Counter
from datetime import datetime

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import importers, models, rules as rules_engine, schemas
from ..db import get_db
from ..models import SignConvention

router = APIRouter(prefix="/api/imports", tags=["imports"])


def _normalize_sign(amount, sign_convention: SignConvention):
    """Always return outflow-negative."""
    if sign_convention == SignConvention.outflow_positive:
        return -amount
    return amount


def _dup_key(d, amount, description_normalized) -> tuple:
    return (d, amount, description_normalized or "")


def _existing_key_counts(db: Session, account_id: int) -> Counter:
    """Counter-based: how many rows for each (date, amount, desc) key are
    already in the DB. Lets us correctly handle multiple same-day same-
    merchant rows (e.g., several $2.90 subway swipes) without collapsing
    them into one."""
    rows = db.execute(
        select(
            models.Transaction.date,
            models.Transaction.amount,
            models.Transaction.description_normalized,
        ).where(models.Transaction.account_id == account_id)
    ).all()
    counts: Counter = Counter()
    for d, a, s in rows:
        counts[_dup_key(d, a, s)] += 1
    return counts


@router.get("/importers")
def list_importers():
    return [{"name": i.name, "label": i.label} for i in importers.all_importers()]


@router.post("/preview", response_model=schemas.ImportPreview)
async def preview(
    file: UploadFile = File(...),
    account_id: int = Form(...),
    importer_name: str | None = Form(default=None),
    db: Session = Depends(get_db),
):
    raw = (await file.read()).decode("utf-8", errors="replace")
    account = db.get(models.Account, account_id)
    if not account:
        raise HTTPException(404, "account not found")
    matched = importers.sniff(raw)
    if importer_name:
        chosen = importers.get_by_name(importer_name)
        if not chosen:
            raise HTTPException(400, f"unknown importer: {importer_name}")
    else:
        if not matched:
            raise HTTPException(400, "no importer can handle this file")
        chosen = matched[0]

    rows = chosen.parse(raw)
    # Normalize sign to account convention (csv → outflow-negative DB convention)
    # All importers already produce outflow-negative output, so we don't flip
    # unless the source convention says otherwise (Amex flips internally).
    sniff_notes = [f"matched importers: {', '.join(m.name for m in matched)}"]

    # Duplicate detection (counter-based; see _existing_key_counts).
    existing_counts = _existing_key_counts(db, account_id)
    file_idx: Counter = Counter()

    active_rules = rules_engine.load_active_rules(db)
    for r in rows:
        # Auto-suggest via rules
        ev = rules_engine.evaluate(
            active_rules,
            account_id=account_id,
            description=r.description_normalized or r.description_raw,
            amount=r.amount,
        )
        if ev.category_id:
            r.suggested_category_id = ev.category_id
        if ev.kind:
            r.suggested_kind = ev.kind
        if ev.merchant:
            r.merchant = ev.merchant
        if ev.tags:
            r.suggested_tags = ev.tags
        if ev.matched_rule_id:
            r.suggested_matched_rule_id = ev.matched_rule_id
        key = _dup_key(r.date, r.amount, r.description_normalized)
        position = file_idx[key]
        file_idx[key] += 1
        # This row is the (position+1)-th in the file with this key. It's
        # a dup only if the DB already has at least (position+1) of them.
        if position < existing_counts.get(key, 0):
            r.is_duplicate = True

    return schemas.ImportPreview(
        importer_name=chosen.name,
        account_id=account_id,
        source_filename=file.filename or "uploaded.csv",
        rows=rows,
        sniff_notes=sniff_notes,
    )


@router.post("/apply", response_model=schemas.ImportBatchOut)
def apply(body: schemas.ImportApplyRequest, db: Session = Depends(get_db)):
    account = db.get(models.Account, body.account_id)
    if not account:
        raise HTTPException(404, "account not found")
    batch = models.ImportBatch(
        source_filename=body.source_filename,
        importer_name=body.importer_name,
        account_id=body.account_id,
        row_count_total=len(body.rows),
        status=models.ImportStatus.applied,
    )
    db.add(batch)
    db.flush()
    # Re-check duplicates against current DB state — preview may be stale if another
    # batch landed between preview and apply. Counter-based so we don't
    # collapse legit same-day same-merchant rows.
    existing_counts = _existing_key_counts(db, body.account_id)
    file_idx: Counter = Counter()
    applied = 0
    dups = 0
    rule_fires: list[int] = []
    for r in body.rows:
        key = _dup_key(r.date, r.amount, r.description_normalized)
        position = file_idx[key]
        file_idx[key] += 1
        # Re-derive freshly against current DB state — the preview's
        # is_duplicate flag may be stale if another batch landed between.
        is_dup = position < existing_counts.get(key, 0)
        if is_dup and body.skip_duplicates:
            dups += 1
            continue
        tx = models.Transaction(
            account_id=body.account_id,
            date=r.date,
            post_date=r.post_date,
            description_raw=r.description_raw,
            description_normalized=r.description_normalized,
            merchant=r.merchant,
            amount=r.amount,
            category_id=r.suggested_category_id,
            kind=r.suggested_kind,
            is_user_categorized=False,
            matched_rule_id=r.suggested_matched_rule_id,
            import_batch_id=batch.id,
            raw=r.raw,
        )
        db.add(tx)
        applied += 1
        if r.suggested_matched_rule_id:
            rule_fires.append(r.suggested_matched_rule_id)
    rules_engine.stamp_rule_fires(db, rule_fires)
    batch.row_count_applied = applied
    batch.row_count_duplicate = dups
    db.commit()
    db.refresh(batch)
    return batch


@router.get("", response_model=list[schemas.ImportBatchOut])
def list_batches(db: Session = Depends(get_db)):
    return list(
        db.scalars(
            select(models.ImportBatch).order_by(models.ImportBatch.imported_at.desc())
        )
    )


@router.post("/{batch_id}/rollback")
def rollback(batch_id: int, db: Session = Depends(get_db)):
    batch = db.get(models.ImportBatch, batch_id)
    if not batch:
        raise HTTPException(404)
    if batch.status != models.ImportStatus.applied:
        raise HTTPException(400, "batch is not in 'applied' state")
    db.execute(
        models.Transaction.__table__.delete().where(
            models.Transaction.import_batch_id == batch_id
        )
    )
    batch.status = models.ImportStatus.rolled_back
    db.commit()
    return {"status": "rolled_back"}
