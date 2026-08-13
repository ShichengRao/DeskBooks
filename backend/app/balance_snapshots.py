"""Apply connector-staged balances (deskbooks.staged-balances/v1) as
net worth snapshots.

Merging rules:
- One snapshot per calendar date (the model enforces snapshot_date UNIQUE);
  applying into an existing date updates/extends that snapshot instead of
  conflicting.
- A null balance row is skipped entirely: AccountBalance.balance NULL means
  "account did not exist at this snapshot", which a connector must never
  assert implicitly.
- Re-applying the same file is a no-op (same values compare equal).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models

STAGED_BALANCES_FORMAT = "deskbooks.staged-balances/v1"
AUTOMATION_SNAPSHOT_NOTE = "Created by automation balances import"


@dataclass(frozen=True)
class StagedBalances:
    as_of: date
    rows: list[tuple[int, Decimal | None]]
    # Optional profile slug stamped by the connector. Account ids only mean
    # something inside one profile's database, so consumers must skip files
    # stamped for a different profile. None = legacy unstamped file.
    profile: str | None = None


@dataclass
class BalanceApplyResult:
    snapshot_id: int | None
    created_snapshot: bool
    updated: int
    unchanged: int
    skipped_null: int
    unknown_account_ids: list[int] = field(default_factory=list)


def parse_staged_balances_bytes(data: bytes) -> StagedBalances:
    try:
        payload = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid staged balances JSON: {exc}") from exc
    if not isinstance(payload, dict) or payload.get("format") != STAGED_BALANCES_FORMAT:
        raise ValueError(f"not a {STAGED_BALANCES_FORMAT} file")
    try:
        as_of = date.fromisoformat(str(payload["as_of"]))
    except KeyError as exc:
        raise ValueError("staged balances file missing as_of") from exc
    except ValueError as exc:
        raise ValueError(f"staged balances file has invalid as_of: {exc}") from exc
    profile = payload.get("profile")
    if profile is not None and (not isinstance(profile, str) or not profile.strip()):
        raise ValueError("staged balances file has invalid profile (must be a non-empty string)")

    raw_rows = payload.get("balances")
    if not isinstance(raw_rows, list):
        raise ValueError("staged balances file has no balances list")
    rows: list[tuple[int, Decimal | None]] = []
    for index, row in enumerate(raw_rows):
        if not isinstance(row, dict):
            raise ValueError(f"balances[{index}] is not an object")
        try:
            account_id = int(row["account_id"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError(f"balances[{index}] needs an integer account_id") from exc
        balance_raw = row.get("balance")
        if balance_raw is None:
            rows.append((account_id, None))
            continue
        try:
            rows.append((account_id, Decimal(str(balance_raw))))
        except InvalidOperation as exc:
            raise ValueError(f"balances[{index}] has invalid balance: {balance_raw}") from exc
    return StagedBalances(as_of=as_of, rows=rows, profile=profile)


def _run(db: Session, staged: StagedBalances, *, apply: bool) -> BalanceApplyResult:
    snapshot = db.scalars(
        select(models.NetWorthSnapshot).where(models.NetWorthSnapshot.snapshot_date == staged.as_of)
    ).first()
    result = BalanceApplyResult(
        snapshot_id=snapshot.id if snapshot else None,
        created_snapshot=snapshot is None,
        updated=0,
        unchanged=0,
        skipped_null=0,
    )
    if snapshot is None and apply:
        snapshot = models.NetWorthSnapshot(
            snapshot_date=staged.as_of, notes=AUTOMATION_SNAPSHOT_NOTE
        )
        db.add(snapshot)
        db.flush()
        result.snapshot_id = snapshot.id

    existing_balances = {}
    if snapshot is not None:
        existing_balances = {bal.account_id: bal for bal in snapshot.balances}

    for account_id, balance in staged.rows:
        if balance is None:
            result.skipped_null += 1
            continue
        if db.get(models.Account, account_id) is None:
            result.unknown_account_ids.append(account_id)
            continue
        current = existing_balances.get(account_id)
        if current is not None and current.balance is not None and current.balance == balance:
            result.unchanged += 1
            continue
        result.updated += 1
        if not apply:
            continue
        if current is not None:
            current.balance = balance
        else:
            db.add(
                models.AccountBalance(
                    snapshot_id=snapshot.id, account_id=account_id, balance=balance
                )
            )
    if apply:
        db.commit()
    return result


def collect_staged_prefill(
    staging_dir: Path,
    *,
    active_profile: str | None = None,
    valid_account_ids: set[int] | None = None,
) -> list[dict]:
    """Newest staged balance per account across the manifest history.

    Powers the snapshot editor's "fill from connections" button: connectors
    stage balances files even in preview mode, so this reflects the most
    recent fetch without any database write. Missing or malformed files are
    skipped — staged data is a cache, not a source of truth.

    The staging tree is shared by every profile, but account ids only mean
    something inside one profile's database. Files stamped with a profile
    slug are skipped unless it matches `active_profile`; unstamped (legacy)
    rows survive only the `valid_account_ids` check, so at worst a stale
    file can suggest a value for a real account — never invent an account
    the snapshot editor (and the POST that follows) doesn't have.
    """
    manifest = staging_dir / "manifest.jsonl"
    if not manifest.exists():
        return []
    best: dict[int, dict] = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entry = json.loads(line)
        except json.JSONDecodeError:
            continue
        if entry.get("kind") != "balances":
            continue
        path = Path(str(entry.get("path") or ""))
        if not path.is_file():
            continue
        try:
            staged = parse_staged_balances_bytes(path.read_bytes())
        except ValueError:
            continue
        if (
            staged.profile is not None
            and active_profile is not None
            and staged.profile != active_profile
        ):
            continue
        source = str(entry.get("source") or "connector")
        for account_id, balance in staged.rows:
            if balance is None:
                continue
            if valid_account_ids is not None and account_id not in valid_account_ids:
                continue
            current = best.get(account_id)
            # >= so later manifest lines (appended chronologically) win ties.
            if current is None or staged.as_of >= current["as_of"]:
                best[account_id] = {
                    "account_id": account_id,
                    "balance": balance,
                    "as_of": staged.as_of,
                    "source": source,
                }
    return sorted(best.values(), key=lambda row: row["account_id"])


def plan_staged_balances(db: Session, staged: StagedBalances) -> BalanceApplyResult:
    return _run(db, staged, apply=False)


def apply_staged_balances(db: Session, staged: StagedBalances) -> BalanceApplyResult:
    return _run(db, staged, apply=True)
