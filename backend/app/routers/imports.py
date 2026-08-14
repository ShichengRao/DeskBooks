from __future__ import annotations

import hashlib
from collections import Counter
from pathlib import Path
from typing import Annotated, NamedTuple

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import backups, balance_snapshots, importers, models, schemas, staging
from .. import rules as rules_engine
from ..db import get_request_profile
from ..importers.amex_xlsx import parse_amex_xlsx_bytes
from ..importers.staged_json import parse_staged_transactions_bytes
from ..models import SignConvention
from ..profiles import ProfileInfo
from .common import DbSession, get_or_404

router = APIRouter(prefix="/api/imports", tags=["imports"])

RequestProfile = Annotated[ProfileInfo, Depends(get_request_profile)]

# Newest entries beyond this stay in manifest.jsonl but off the page; only
# importable ("new") entries get parsed for row counts, so a long history
# doesn't slow the listing down.
STAGED_LIST_CAP = 200


def _normalize_sign(amount, sign_convention: SignConvention):
    """Always return outflow-negative."""
    if sign_convention == SignConvention.outflow_positive:
        return -amount
    return amount


def _dup_key(d, amount, description_normalized) -> tuple:
    return (d, amount, description_normalized or "")


def _provider_id(raw) -> str | None:
    """The source's own transaction id, when it supplies one.

    Connector-staged rows (deskbooks.staged-transactions/v1) carry the
    provider's id; CSV/XLSX rows carry only positional/column data."""
    if not isinstance(raw, dict):
        return None
    value = raw.get("id")
    return value if isinstance(value, str) and value else None


class _ExistingRows(NamedTuple):
    key_counts: Counter
    unidentified_key_counts: Counter
    provider_ids: set[str]


def _existing_rows(db: Session, account_id: int) -> _ExistingRows:
    """What this account already holds, in both dedup currencies.

    `key_counts` is counter-based: how many rows for each (date, amount,
    desc) key are already in the DB. Lets us correctly handle multiple
    same-day same-merchant rows (e.g., several $2.90 subway swipes)
    without collapsing them into one. `unidentified_key_counts` is the
    same tally over only the rows that carry no provider id.

    `provider_ids` is every source transaction id already stored, so a
    refetch of the same transaction is recognized even when the key
    changed underneath it."""
    rows = db.execute(
        select(
            models.Transaction.date,
            models.Transaction.amount,
            models.Transaction.description_normalized,
            models.Transaction.raw,
        ).where(models.Transaction.account_id == account_id)
    ).all()
    counts: Counter = Counter()
    unidentified: Counter = Counter()
    provider_ids: set[str] = set()
    for d, a, s, raw in rows:
        counts[_dup_key(d, a, s)] += 1
        pid = _provider_id(raw)
        if pid is not None:
            provider_ids.add(pid)
        else:
            unidentified[_dup_key(d, a, s)] += 1
    return _ExistingRows(counts, unidentified, provider_ids)


def _mark_duplicates(rows, existing: _ExistingRows) -> None:
    """Flag rows already present in the account.

    A provider id is stable across refetches; the (date, amount, desc)
    key is not. Plaid, for one, backfills `authorized_date` a few days
    after a card transaction posts, which moves the reported date 1-3
    days earlier — under key-only matching every such row re-imports as
    new. So prefer the id whenever the source gives us one."""
    file_idx: Counter = Counter()
    fallback_idx: Counter = Counter()
    seen_provider_ids: set[str] = set()
    for r in rows:
        key = _dup_key(r.date, r.amount, r.description_normalized)
        pid = _provider_id(r.raw)
        if pid is None:
            # This row is the (position+1)-th in the file with this key.
            # It's a dup only if the DB already has at least that many.
            position = file_idx[key]
            file_idx[key] += 1
            r.is_duplicate = position < existing.key_counts.get(key, 0)
            continue
        if pid in existing.provider_ids or pid in seen_provider_ids:
            # Repeats within one file count too — a provider id names one
            # transaction, not a class of them.
            seen_provider_ids.add(pid)
            r.is_duplicate = True
            continue
        seen_provider_ids.add(pid)
        # An id we've never seen. The transaction may still be here from
        # before this account was connected (CSV/XLSX history carries no
        # ids), so fall back to the positional key — but only against rows
        # we can't identify. An existing row bearing a *different* id is a
        # different transaction, however alike it looks.
        position = fallback_idx[key]
        fallback_idx[key] += 1
        r.is_duplicate = position < existing.unidentified_key_counts.get(key, 0)


@router.get("/importers")
def list_importers():
    return [{"name": i.name, "label": i.label} for i in importers.all_importers()]


def _preview_from_bytes(
    db: DbSession,
    *,
    data: bytes,
    filename: str,
    account_id: int,
    importer_name: str | None,
) -> schemas.ImportPreview:
    account = db.get(models.Account, account_id)
    if not account:
        raise HTTPException(404, "account not found")
    is_xlsx = filename.lower().endswith(".xlsx")
    is_staged_json = filename.lower().endswith(".json") or importer_name == "staged_json"
    raw = "" if (is_xlsx or is_staged_json) else data.decode("utf-8", errors="replace")
    matched = [] if (is_xlsx or is_staged_json) else importers.sniff(raw)
    if is_staged_json:
        try:
            rows = parse_staged_transactions_bytes(data)
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        chosen_name = "staged_json"
        sniff_notes = ["matched importers: staged_json"]
    elif importer_name in {"amex", "amex_xlsx"} and is_xlsx:
        rows = parse_amex_xlsx_bytes(data)
        chosen_name = "amex"
        sniff_notes = ["matched importers: amex"]
    elif is_xlsx:
        rows = parse_amex_xlsx_bytes(data)
        if not rows:
            raise HTTPException(400, "no importer can handle this file")
        chosen_name = "amex"
        sniff_notes = ["matched importers: amex"]
    elif importer_name:
        chosen = importers.get_by_name(importer_name)
        if not chosen:
            raise HTTPException(400, f"unknown importer: {importer_name}")
        rows = chosen.parse(raw)
        chosen_name = chosen.name
        sniff_notes = [f"matched importers: {', '.join(m.name for m in matched)}"]
    else:
        if not matched:
            raise HTTPException(400, "no importer can handle this file")
        chosen = matched[0]
        rows = chosen.parse(raw)
        chosen_name = chosen.name
        sniff_notes = [f"matched importers: {', '.join(m.name for m in matched)}"]
    # Normalize sign to account convention (csv → outflow-negative DB convention)
    # All importers already produce outflow-negative output, so we don't flip
    # unless the source convention says otherwise (Amex flips internally).

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
        if ev.is_excluded_from_totals is not None:
            r.suggested_is_excluded_from_totals = ev.is_excluded_from_totals
        if ev.matched_rule_id:
            r.suggested_matched_rule_id = ev.matched_rule_id

    _mark_duplicates(rows, _existing_rows(db, account_id))

    return schemas.ImportPreview(
        importer_name=chosen_name,
        account_id=account_id,
        source_filename=filename,
        rows=rows,
        sniff_notes=sniff_notes,
    )


@router.post("/preview", response_model=schemas.ImportPreview)
async def preview(
    db: DbSession,
    file: Annotated[UploadFile, File()],
    account_id: Annotated[int, Form()],
    importer_name: Annotated[str | None, Form()] = None,
):
    return _preview_from_bytes(
        db,
        data=await file.read(),
        filename=file.filename or "uploaded.csv",
        account_id=account_id,
        importer_name=importer_name,
    )


@router.post("/preview-path", response_model=schemas.ImportPreview)
def preview_path(body: schemas.ImportPathPreviewRequest, db: DbSession):
    path = Path(body.path).expanduser()
    if not path.exists() or not path.is_file():
        raise HTTPException(404, "file not found")
    return _preview_from_bytes(
        db,
        data=path.read_bytes(),
        filename=path.name,
        account_id=body.account_id,
        importer_name=body.importer_name,
    )


@router.post("/apply", response_model=schemas.ImportBatchOut)
def apply(body: schemas.ImportApplyRequest, db: DbSession):
    get_or_404(db, models.Account, body.account_id, "account not found")
    batch = models.ImportBatch(
        source_filename=body.source_filename,
        importer_name=body.importer_name,
        account_id=body.account_id,
        row_count_total=len(body.rows),
        status=models.ImportStatus.applied,
    )
    db.add(batch)
    db.flush()
    # Re-derive duplicates freshly against current DB state — the preview's
    # is_duplicate flags may be stale if another batch landed between
    # preview and apply.
    _mark_duplicates(body.rows, _existing_rows(db, body.account_id))
    applied = 0
    dups = 0
    rule_fires: list[int] = []
    for r in body.rows:
        if r.is_duplicate and body.skip_duplicates:
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
            is_excluded_from_totals=r.suggested_is_excluded_from_totals,
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
def list_batches(db: DbSession):
    return list(
        db.scalars(select(models.ImportBatch).order_by(models.ImportBatch.imported_at.desc()))
    )


def _staged_listing(
    db: Session,
    staging_dir: Path,
    state_path: Path,
    active_profile: str,
) -> list[schemas.StagedEntryOut]:
    """Manifest entries as the Import page shows them, newest fetch first.

    Deduped by sha256 and by path (a refetch overwrites the file in place,
    so only the newest manifest line per file reflects what's on disk).

    Status is derived from this profile's database and the file contents
    only — never from the shared import-state file, which any profile's
    import (CLI or UI) writes to. A file counts as imported here when its
    batch exists in this database, or when it has nothing left to change
    (every statement row already present / every balance already equal)."""
    entries = staging.read_manifest(staging_dir / "manifest.jsonl", lenient=True)
    picked: list[dict] = []
    seen_sha: set[str] = set()
    seen_path: set[str] = set()
    for entry in reversed(entries):
        sha256 = str(entry.get("sha256") or "")
        path_str = str(entry.get("path") or "")
        if not sha256 or sha256 in seen_sha or path_str in seen_path:
            continue
        seen_sha.add(sha256)
        seen_path.add(path_str)
        picked.append(entry)
        if len(picked) >= STAGED_LIST_CAP:
            break

    account_names = dict(db.execute(select(models.Account.id, models.Account.name)).all())
    # Dismissals are the one thing the listing does read from the state
    # file, because "I am never importing this" is a decision about the
    # file rather than something derivable from the database.
    dismissed = set(staging.load_state(state_path).get("dismissed_sha256", {}))

    rows: list[schemas.StagedEntryOut] = []
    for entry in picked:
        kind = str(entry.get("kind") or "statement")
        path = Path(str(entry.get("path") or ""))
        raw_account_id = entry.get("account_id")
        account_id = int(raw_account_id) if raw_account_id is not None else None
        out = schemas.StagedEntryOut(
            sha256=str(entry.get("sha256")),
            source=str(entry.get("source") or "connector"),
            kind=kind,
            file_name=path.name,
            path=str(path),
            account_id=account_id,
            account_name=account_names.get(account_id),
            importer_name=entry.get("importer_name"),
            profile=None,
            downloaded_at=entry.get("downloaded_at"),
            status="new",
        )
        rows.append(out)

        if out.sha256 in dismissed:
            out.status = "dismissed"
            continue

        if kind not in staging.ENTRY_KINDS:
            out.status, out.detail = "invalid", f"unknown kind: {kind}"
            continue
        if not path.is_file():
            out.status = "missing_file"
            continue
        try:
            staging.require_staged_file(path, staging_dir)
        except ValueError as exc:
            out.status, out.detail = "invalid", str(exc)
            continue
        out.profile = staging.staged_file_profile(entry, path)
        if out.profile is not None and out.profile != active_profile:
            out.status = "other_profile"
            continue

        if kind == "statement":
            if staging.existing_batch_for_sha(db, out.sha256) is not None:
                out.status = "imported"
                continue
            if account_id is None or account_id not in account_names:
                out.status = "unknown_account"
                out.detail = f"account id {raw_account_id!r} does not exist in this profile"
                continue
            try:
                preview = _preview_from_bytes(
                    db,
                    data=path.read_bytes(),
                    filename=path.name,
                    account_id=account_id,
                    importer_name=entry.get("importer_name"),
                )
            except HTTPException as exc:
                out.status, out.detail = "invalid", str(exc.detail)
                continue
            out.row_count = len(preview.rows)
            out.new_count = sum(1 for r in preview.rows if not r.is_duplicate)
            if out.row_count == 0:
                out.status = "empty"
            elif out.new_count == 0:
                out.status = "imported"
                out.detail = "every row is already in this profile"
        else:
            try:
                staged = balance_snapshots.parse_staged_balances_bytes(path.read_bytes())
            except ValueError as exc:
                out.status, out.detail = "invalid", str(exc)
                continue
            plan = balance_snapshots.plan_staged_balances(db, staged)
            out.as_of = staged.as_of
            out.row_count = sum(1 for _, balance in staged.rows if balance is not None)
            out.new_count = plan.updated
            if out.row_count == 0:
                out.status = "empty"
            elif out.new_count == 0:
                out.status = "imported"
                out.detail = "balances already match this profile"
    return rows


@router.get("/staged", response_model=list[schemas.StagedEntryOut])
def staged_list(db: DbSession, profile: RequestProfile):
    """Connector-staged files from the shared manifest, with per-file
    import status for the requesting tab's profile. Reads local files only."""
    return _staged_listing(
        db,
        staging.default_staging_dir(),
        staging.default_state_path(),
        profile.slug,
    )


def _apply_staged(
    db: Session,
    staging_dir: Path,
    state_path: Path,
    active_profile: str,
    sha256s: list[str],
    profile_info: ProfileInfo | None = None,
) -> schemas.StagedApplyResult:
    listing = {
        row.sha256: row for row in _staged_listing(db, staging_dir, state_path, active_profile)
    }
    # Naming files is a choice about those files; sweeping up everything
    # new is not. A snapshot is a statement about every account at once,
    # so a sweep that happened to cover one connection must not write one
    # holding only that connection's accounts — it reads as a collapse in
    # net worth. Balances still import when asked for by name.
    swept = not sha256s
    targets = sha256s or [sha for sha, row in listing.items() if row.status == "new"]

    state = staging.load_state(state_path)
    applied_map = state.setdefault("applied_sha256", {})
    outcomes: list[schemas.StagedApplyOutcome] = []
    backup_name: str | None = None
    state_dirty = False

    def ensure_backup() -> None:
        nonlocal backup_name
        if backup_name is None and profile_info is not None:
            backup_name = backups.create_backup(profile_info, label="pre-staged-import")["name"]

    for sha256 in targets:
        row = listing.get(sha256)
        if row is None:
            outcomes.append(
                schemas.StagedApplyOutcome(
                    sha256=sha256,
                    file_name="?",
                    status="error",
                    detail="not in the staging manifest",
                )
            )
            continue
        outcome = schemas.StagedApplyOutcome(sha256=sha256, file_name=row.file_name, status="error")
        outcomes.append(outcome)
        if row.status != "new":
            outcome.status = f"skipped_{row.status}"
            continue
        path = Path(row.path)
        data = path.read_bytes() if path.is_file() else None
        if data is None or hashlib.sha256(data).hexdigest() != sha256:
            outcome.detail = "file missing or changed since it was staged — re-run the fetch"
            continue

        if row.kind == "statement":
            # The listing only marks a file "new" when it has fresh rows,
            # so there is always something to apply here.
            preview = _preview_from_bytes(
                db,
                data=data,
                filename=row.file_name,
                account_id=row.account_id,
                importer_name=row.importer_name,
            )
            ensure_backup()
            batch = apply(
                schemas.ImportApplyRequest(
                    importer_name=preview.importer_name,
                    account_id=preview.account_id,
                    source_filename=preview.source_filename,
                    rows=preview.rows,
                    skip_duplicates=True,
                ),
                db,
            )
            batch.notes = f"automation_sha256={sha256}"
            db.commit()
            applied_map[sha256] = {
                "batch_id": batch.id,
                "path": row.path,
                "imported_at": batch.imported_at.isoformat(),
            }
            state_dirty = True
            outcome.status = "imported"
            outcome.batch_id = batch.id
            outcome.rows_applied = batch.row_count_applied
            outcome.duplicates = batch.row_count_duplicate
        else:
            if swept:
                outcome.status = "skipped_balances"
                outcome.detail = (
                    "balances are left out of a bulk import — build the snapshot from the "
                    "Net Worth page, where every account is in view, or import this file by itself"
                )
                continue
            try:
                staged = balance_snapshots.parse_staged_balances_bytes(data)
            except ValueError as exc:
                outcome.detail = str(exc)
                continue
            ensure_backup()
            result = balance_snapshots.apply_staged_balances(db, staged)
            applied_map[sha256] = {
                "snapshot_id": result.snapshot_id,
                "path": row.path,
                "as_of": staged.as_of.isoformat(),
            }
            state_dirty = True
            outcome.status = "imported"
            outcome.snapshot_id = result.snapshot_id
            outcome.rows_applied = result.updated

    if state_dirty:
        staging.save_state(state_path, state)
    return schemas.StagedApplyResult(outcomes=outcomes, backup_name=backup_name)


@router.post("/staged/dismiss", response_model=schemas.StagedDismissResult)
def staged_dismiss(body: schemas.StagedDismissRequest, profile: RequestProfile):
    """Mark staged files as never-to-import, or put them back.

    A fetch leaves files behind that will never be wanted — a connection
    you have since set to balance-only, a window you widened once. They
    stay "ready" forever, and a list where most rows are noise is a list
    nobody reads, so the ones that matter get missed too."""
    state_path = staging.default_state_path()
    state = staging.load_state(state_path)
    dismissed = state.setdefault("dismissed_sha256", {})
    for sha256 in body.sha256s:
        if body.dismissed:
            dismissed[sha256] = {"profile": profile.slug}
        else:
            dismissed.pop(sha256, None)
    staging.save_state(state_path, state)
    return schemas.StagedDismissResult(dismissed=len(dismissed), changed=len(body.sha256s))


@router.post("/staged/apply", response_model=schemas.StagedApplyResult)
def staged_apply(body: schemas.StagedApplyRequest, db: DbSession, profile: RequestProfile):
    """Import staged files by sha256 — or everything importable when the
    list is empty. Statements become import batches (duplicates skipped);
    balances merge into net-worth snapshots. One database backup is taken
    before the first write, like the CLI."""
    return _apply_staged(
        db,
        staging.default_staging_dir(),
        staging.default_state_path(),
        profile.slug,
        body.sha256s,
        profile_info=profile,
    )


@router.post("/{batch_id}/rollback")
def rollback(batch_id: int, db: DbSession):
    batch = get_or_404(db, models.ImportBatch, batch_id)
    if batch.status != models.ImportStatus.applied:
        raise HTTPException(400, "batch is not in 'applied' state")
    db.execute(
        models.Transaction.__table__.delete().where(models.Transaction.import_batch_id == batch_id)
    )
    batch.status = models.ImportStatus.rolled_back
    db.commit()
    return {"status": "rolled_back"}
