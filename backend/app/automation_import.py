from __future__ import annotations

import argparse
import json
from html import escape
from pathlib import Path

from . import backups, balance_snapshots, schemas
from .db import SessionLocal, init_db
from .profiles import get_active_profile
from .routers import imports as import_router
from .staging import (
    default_staging_dir,
    existing_batch_for_sha,
    load_state,
    read_manifest,
    save_state,
    staged_file_profile,
    validate_entry,
)


def write_preview_report(
    staging_dir: Path,
    *,
    preview: schemas.ImportPreview,
    file_path: Path,
    non_duplicates: int,
) -> Path:
    staging_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "source_filename": preview.source_filename,
        "path": str(file_path),
        "account_id": preview.account_id,
        "importer_name": preview.importer_name,
        "row_count": len(preview.rows),
        "new_count": non_duplicates,
        "duplicate_count": len(preview.rows) - non_duplicates,
        "rows": [row.model_dump(mode="json") for row in preview.rows],
    }
    json_path = staging_dir / "latest-preview.json"
    html_path = staging_dir / "latest-preview.html"
    with json_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
        f.write("\n")

    shown_rows = preview.rows[:200]
    row_html = "\n".join(
        "<tr>"
        f"<td>{escape(str(row.date))}</td>"
        f"<td>{escape(row.description_raw)}</td>"
        f"<td>{escape(row.suggested_kind.value)}</td>"
        f'<td class="amount">{escape(str(row.amount))}</td>'
        f"<td>{'yes' if row.is_duplicate else ''}</td>"
        "</tr>"
        for row in shown_rows
    )
    more_note = ""
    if len(preview.rows) > len(shown_rows):
        more_note = f"<p>Showing first {len(shown_rows)} of {len(preview.rows)} rows.</p>"
    html = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>DeskBooks Import Preview</title>
  <style>
    body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 24px; color: #172033; }}
    code {{ background: #f4f6f8; padding: 2px 4px; border-radius: 4px; }}
    table {{ border-collapse: collapse; width: 100%; font-size: 12px; }}
    th, td {{ border-bottom: 1px solid #e4e8ee; padding: 6px 8px; text-align: left; }}
    th {{ background: #f8fafc; position: sticky; top: 0; }}
    .amount {{ text-align: right; font-variant-numeric: tabular-nums; }}
  </style>
</head>
<body>
  <h1>DeskBooks Import Preview</h1>
  <p><strong>{escape(preview.source_filename)}</strong></p>
  <p>Rows: {len(preview.rows)} · New: {non_duplicates} · Duplicates: {len(preview.rows) - non_duplicates} · Importer: <code>{escape(preview.importer_name)}</code> · Account ID: {preview.account_id}</p>
  <p>Source path: <code>{escape(str(file_path))}</code></p>
  {more_note}
  <table>
    <thead><tr><th>Date</th><th>Description</th><th>Kind</th><th>Amount</th><th>Duplicate?</th></tr></thead>
    <tbody>{row_html}</tbody>
  </table>
</body>
</html>
"""
    html_path.write_text(html, encoding="utf-8")
    return html_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Preview or apply staged DeskBooks imports.")
    parser.add_argument("--manifest", type=Path, default=default_staging_dir() / "manifest.jsonl")
    parser.add_argument("--staging-dir", type=Path, default=default_staging_dir())
    parser.add_argument("--state", type=Path, default=default_staging_dir() / "import-state.json")
    parser.add_argument("--apply", action="store_true", help="apply rows after previewing")
    parser.add_argument("--source", help="only process manifest entries from this source")
    parser.add_argument(
        "--apply-balances",
        action="store_true",
        help=(
            "also write staged balances into net-worth snapshots. Off by default: a run "
            "that covers only some connections would otherwise create a snapshot holding "
            "only those accounts, which reads as a collapse in net worth. Leave it off and "
            "fill a snapshot from the Net Worth page instead, where every account is in view."
        ),
    )
    parser.add_argument("--no-backup", action="store_true", help="skip pre-apply SQLite backup")
    args = parser.parse_args()

    init_db()
    try:
        entries = read_manifest(args.manifest)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc
    if args.source:
        entries = [entry for entry in entries if entry.get("source") == args.source]
    if not entries:
        print("[import] no manifest entries to process")
        return

    state = load_state(args.state)
    applied_sha256 = state.setdefault("applied_sha256", {})
    did_backup = False
    imported = 0
    seen_sha256: set[str] = set()
    active_slug = get_active_profile().slug

    with SessionLocal() as db:
        for entry in entries:
            try:
                kind, file_path, account_id, importer_name, sha256 = validate_entry(
                    entry, args.staging_dir
                )
            except ValueError as exc:
                raise SystemExit(str(exc)) from exc
            stamp = staged_file_profile(entry, file_path)
            if stamp is not None and stamp != active_slug:
                # Not recorded in state: the file stays pending until its
                # profile is the active one.
                print(
                    f"[import] skip {file_path.name}: staged for profile "
                    f"'{stamp}' but '{active_slug}' is active"
                )
                continue
            if sha256 in seen_sha256:
                print(f"[import] skip duplicate manifest entry: {file_path.name}")
                continue
            seen_sha256.add(sha256)
            if sha256 in applied_sha256 or existing_batch_for_sha(db, sha256):
                print(f"[import] skip already applied: {file_path.name}")
                continue

            if kind == "balances":
                try:
                    staged = balance_snapshots.parse_staged_balances_bytes(file_path.read_bytes())
                except ValueError as exc:
                    raise SystemExit(f"{file_path.name}: {exc}") from exc
                plan = balance_snapshots.plan_staged_balances(db, staged)
                print(
                    "[import] balances preview "
                    f"{file_path.name}: as_of={staged.as_of} update={plan.updated} "
                    f"unchanged={plan.unchanged} skipped_null={plan.skipped_null} "
                    f"unknown_accounts={plan.unknown_account_ids or 'none'}"
                )
                if not args.apply or not args.apply_balances:
                    # Previewed, never written. A snapshot is a statement
                    # about every account at once, so it should not be a
                    # side effect of importing whichever connections
                    # happened to run.
                    continue
                if not did_backup and not args.no_backup:
                    backup = backups.create_backup(get_active_profile(), label="pre-auto-import")
                    print(f"[import] backup created: {backup['name']}")
                    did_backup = True
                result = balance_snapshots.apply_staged_balances(db, staged)
                applied_sha256[sha256] = {
                    "snapshot_id": result.snapshot_id,
                    "path": str(file_path),
                    "as_of": staged.as_of.isoformat(),
                }
                imported += 1
                print(
                    "[import] applied balances "
                    f"snapshot={result.snapshot_id} updated={result.updated} "
                    f"unchanged={result.unchanged}"
                )
                continue

            preview = import_router._preview_from_bytes(
                db,
                data=file_path.read_bytes(),
                filename=file_path.name,
                account_id=account_id,
                importer_name=importer_name,
            )
            non_duplicates = sum(1 for row in preview.rows if not row.is_duplicate)
            print(
                "[import] preview "
                f"{file_path.name}: rows={len(preview.rows)} new={non_duplicates} "
                f"duplicates={len(preview.rows) - non_duplicates}"
            )
            report_path = write_preview_report(
                args.staging_dir,
                preview=preview,
                file_path=file_path,
                non_duplicates=non_duplicates,
            )
            print(f"[import] preview report: {report_path}")

            if not preview.rows:
                # Nothing to import (e.g. a low-activity account with no
                # transactions in the lookback window) — do not create an
                # empty batch, but remember the file so re-runs skip it.
                if args.apply:
                    applied_sha256[sha256] = {"path": str(file_path), "empty": True}
                    imported += 1
                continue

            if not args.apply:
                continue

            if not did_backup and not args.no_backup:
                backup = backups.create_backup(get_active_profile(), label="pre-auto-import")
                print(f"[import] backup created: {backup['name']}")
                did_backup = True

            batch = import_router.apply(
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
            applied_sha256[sha256] = {
                "batch_id": batch.id,
                "path": str(file_path),
                "imported_at": batch.imported_at.isoformat(),
            }
            imported += 1
            print(
                "[import] applied "
                f"batch={batch.id} rows={batch.row_count_applied} duplicates={batch.row_count_duplicate}"
            )

    if args.apply and imported:
        save_state(args.state, state)


if __name__ == "__main__":
    main()
