"""Shared plumbing for the connector staging tree.

The fetch half (automation/) writes staged files plus a JSONL manifest; the
import half reads them. This module holds the pieces both consumers need —
manifest reading, the applied-sha import state, path containment, per-file
profile stamps — so the `automation_import` CLI and the staged-import API
endpoints in `routers/imports.py` don't have to import each other.

Everything here raises ValueError on bad input; the CLI converts to
SystemExit at its boundary and the router converts to HTTP errors.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from sqlalchemy import select

from . import models
from .app_paths import DATA_DIR

ENTRY_KINDS = {"statement", "balances"}


def default_staging_dir() -> Path:
    return DATA_DIR / "import-staging"


def default_state_path() -> Path:
    return default_staging_dir() / "import-state.json"


def read_manifest(path: Path, *, lenient: bool = False) -> list[dict[str, Any]]:
    """Parse manifest.jsonl. Strict mode (the CLI) refuses a corrupt line;
    lenient mode (the UI listing) skips it — a bad line must not hide the
    rest of the staged files from the page."""
    if not path.exists():
        return []
    entries = []
    with path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError as exc:
                if lenient:
                    continue
                raise ValueError(f"invalid manifest JSON on line {line_no}: {exc}") from exc
            if isinstance(entry, dict):
                entries.append(entry)
            elif not lenient:
                raise ValueError(f"manifest line {line_no} is not an object")
    return entries


def load_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"applied_sha256": {}}
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(state, f, indent=2, sort_keys=True)
        f.write("\n")


def require_staged_file(file_path: Path, staging_dir: Path) -> Path:
    resolved = file_path.expanduser().resolve()
    root = staging_dir.expanduser().resolve()
    if root not in [resolved, *resolved.parents]:
        raise ValueError(f"refusing file outside staging dir: {resolved}")
    if not resolved.is_file():
        raise ValueError(f"staged file not found: {resolved}")
    return resolved


def validate_entry(
    entry: dict[str, Any], staging_dir: Path
) -> tuple[str, Path, int | None, str | None, str]:
    kind = str(entry.get("kind") or "statement")
    if kind not in ENTRY_KINDS:
        raise ValueError(f"manifest entry has unknown kind: {kind}")
    try:
        file_path = require_staged_file(Path(str(entry["path"])), staging_dir)
        sha256 = str(entry["sha256"])
    except KeyError as exc:
        raise ValueError(f"manifest entry missing field: {exc.args[0]}") from exc
    if not sha256:
        raise ValueError("manifest entry has empty sha256")

    if kind == "balances":
        # Balances files carry per-row account ids; no importer involved.
        return kind, file_path, None, None, sha256

    try:
        account_id = int(entry["account_id"])
        importer_name = str(entry["importer_name"])
    except KeyError as exc:
        raise ValueError(f"manifest entry missing field: {exc.args[0]}") from exc
    if not importer_name:
        raise ValueError("manifest entry has empty importer_name")
    return kind, file_path, account_id, importer_name, sha256


def staged_file_profile(entry: dict[str, Any], file_path: Path) -> str | None:
    """Profile slug a staged file is meant for, or None for legacy files.

    The manifest entry carries the stamp the runner wrote; fall back to the
    file payload for files staged by hand or by an older runner. Unreadable
    files return None here — the format parsers report those properly."""
    stamp = entry.get("profile")
    if isinstance(stamp, str) and stamp.strip():
        return stamp
    try:
        payload = json.loads(file_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    file_stamp = payload.get("profile") if isinstance(payload, dict) else None
    if isinstance(file_stamp, str) and file_stamp.strip():
        return file_stamp
    return None


def existing_batch_for_sha(db, sha256: str) -> models.ImportBatch | None:
    return db.scalars(
        select(models.ImportBatch).where(models.ImportBatch.notes == f"automation_sha256={sha256}")
    ).first()
