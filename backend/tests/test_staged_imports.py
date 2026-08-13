from __future__ import annotations

import hashlib
import json
from datetime import date
from decimal import Decimal

from app import staging
from app.models import (
    Account,
    AccountCategory,
    AccountType,
    ImportBatch,
    NetWorthSnapshot,
    SignConvention,
    Transaction,
)
from app.routers.imports import _apply_staged, _staged_listing

ACTIVE = "personal"


def _account(db, name: str = "Checking") -> Account:
    account = Account(
        name=name,
        institution=None,
        account_category=AccountCategory.bank,
        type=AccountType.checking,
        sign_convention=SignConvention.outflow_negative,
    )
    db.add(account)
    db.flush()
    return account


def _transactions_payload(account_id: int, rows: int = 2, profile: str | None = None) -> dict:
    payload = {
        "format": "deskbooks.staged-transactions/v1",
        "account_id": account_id,
        "transactions": [
            {
                "id": f"txn_{i}",
                "date": f"2026-07-{10 + i:02d}",
                "description": f"COFFEE {i}",
                "amount": "-4.50",
                "pending": False,
            }
            for i in range(rows)
        ],
    }
    if profile is not None:
        payload["profile"] = profile
    return payload


def _stage(staging_dir, name: str, payload: dict, **entry_overrides) -> dict:
    path = staging_dir / name
    data = json.dumps(payload).encode()
    path.write_bytes(data)
    entry = {
        "source": "connector_test",
        "kind": "statement",
        "path": str(path),
        "sha256": hashlib.sha256(data).hexdigest(),
        "downloaded_at": "2026-07-30T09:00:00.000Z",
        **entry_overrides,
    }
    return entry


def _write_manifest(staging_dir, entries) -> None:
    lines = [json.dumps(e) for e in entries]
    (staging_dir / "manifest.jsonl").write_text("\n".join(lines) + "\n")


def _setup(tmp_path):
    staging_dir = tmp_path / "import-staging"
    staging_dir.mkdir()
    return staging_dir, tmp_path / "import-state.json"


def test_staged_listing_reports_status_per_entry(db, tmp_path):
    staging_dir, state_path = _setup(tmp_path)
    account = _account(db)
    db.add(
        Transaction(
            account_id=account.id,
            date=date(2026, 7, 10),
            description_raw="COFFEE 0",
            description_normalized="COFFEE 0",
            amount=Decimal("-4.50"),
        )
    )
    db.commit()

    fresh = _stage(
        staging_dir,
        "fresh.json",
        _transactions_payload(account.id),
        account_id=account.id,
        importer_name="staged_json",
    )
    other = _stage(
        staging_dir,
        "other.json",
        _transactions_payload(account.id, profile="scratch"),
        account_id=account.id,
        importer_name="staged_json",
        profile="scratch",
    )
    orphan = _stage(
        staging_dir,
        "orphan.json",
        _transactions_payload(99),
        account_id=99,
        importer_name="staged_json",
    )
    balances = _stage(
        staging_dir,
        "balances.json",
        {
            "format": "deskbooks.staged-balances/v1",
            "as_of": "2026-07-30",
            "balances": [
                {"account_id": account.id, "balance": "1234.56"},
                {"account_id": 424242, "balance": "1.00"},
            ],
        },
        kind="balances",
        account_id=None,
        importer_name=None,
    )
    gone = dict(fresh, path=str(staging_dir / "gone.json"), sha256="0" * 64)
    hollow = _stage(
        staging_dir,
        "hollow.json",
        _transactions_payload(account.id, rows=0),
        account_id=account.id,
        importer_name="staged_json",
    )
    # every row of this one already exists in the profile (the seeded txn)
    dup_payload = _transactions_payload(account.id, rows=1)
    dup_payload["transactions"][0]["id"] = "txn_dup"
    duplicated = _stage(
        staging_dir,
        "duplicated.json",
        dup_payload,
        account_id=account.id,
        importer_name="staged_json",
    )

    # The shared state file says fresh.json was imported (by some other
    # profile). This profile's database has no such batch, so the listing
    # must NOT believe it.
    staging.save_state(
        state_path,
        {"applied_sha256": {fresh["sha256"]: {"batch_id": 999, "path": fresh["path"]}}},
    )

    _write_manifest(staging_dir, [fresh, other, orphan, balances, gone, hollow, duplicated])

    rows = {r.file_name: r for r in _staged_listing(db, staging_dir, state_path, ACTIVE)}
    assert rows["fresh.json"].status == "new"
    # one of the two rows already exists in the DB, so only one is new
    assert (rows["fresh.json"].row_count, rows["fresh.json"].new_count) == (2, 1)
    assert rows["other.json"].status == "other_profile"
    assert rows["other.json"].profile == "scratch"
    assert rows["orphan.json"].status == "unknown_account"
    assert rows["balances.json"].status == "new"
    assert rows["balances.json"].as_of == date(2026, 7, 30)
    # the unknown account id in the balances file is simply not applyable
    assert (rows["balances.json"].row_count, rows["balances.json"].new_count) == (2, 1)
    assert rows["gone.json"].status == "missing_file"
    assert rows["hollow.json"].status == "empty"
    assert rows["duplicated.json"].status == "imported"
    assert "already in this profile" in rows["duplicated.json"].detail
    # newest-first ordering: manifest order reversed
    assert [r.file_name for r in _staged_listing(db, staging_dir, state_path, ACTIVE)][
        0
    ] == "duplicated.json"


def test_apply_staged_imports_statements_and_balances_once(db, tmp_path):
    staging_dir, state_path = _setup(tmp_path)
    account = _account(db)
    db.commit()

    statement = _stage(
        staging_dir,
        "statement.json",
        _transactions_payload(account.id),
        account_id=account.id,
        importer_name="staged_json",
    )
    balances = _stage(
        staging_dir,
        "balances.json",
        {
            "format": "deskbooks.staged-balances/v1",
            "as_of": "2026-07-30",
            "balances": [{"account_id": account.id, "balance": "1234.56"}],
        },
        kind="balances",
        account_id=None,
        importer_name=None,
    )
    empty = _stage(
        staging_dir,
        "empty.json",
        _transactions_payload(account.id, rows=0),
        account_id=account.id,
        importer_name="staged_json",
    )
    _write_manifest(staging_dir, [statement, balances, empty])

    result = _apply_staged(db, staging_dir, state_path, ACTIVE, [])
    outcomes = {o.file_name: o for o in result.outcomes}
    # the 0-row file is never a target: the listing already calls it empty
    assert set(outcomes) == {"statement.json", "balances.json"}
    assert outcomes["statement.json"].status == "imported"
    assert outcomes["statement.json"].rows_applied == 2
    # A sweep leaves balances alone. A snapshot describes every account at
    # once, so one must not appear as a side effect of importing whichever
    # connections happened to run — a snapshot holding only those accounts
    # reads as a collapse in net worth.
    assert outcomes["balances.json"].status == "skipped_balances"
    assert db.query(NetWorthSnapshot).count() == 0
    assert result.backup_name is None  # no profile_info given, no backup

    # Naming the file is a choice about that file, so it still applies.
    named = _apply_staged(db, staging_dir, state_path, ACTIVE, [balances["sha256"]])
    assert named.outcomes[0].status == "imported"
    assert named.outcomes[0].rows_applied == 1

    batch = db.query(ImportBatch).one()  # the empty file created no batch
    assert batch.notes == f"automation_sha256={statement['sha256']}"
    assert db.query(Transaction).count() == 2
    snapshot = db.query(NetWorthSnapshot).one()
    assert snapshot.snapshot_date == date(2026, 7, 30)

    state = staging.load_state(state_path)["applied_sha256"]
    assert state[statement["sha256"]]["batch_id"] == batch.id
    assert state[balances["sha256"]]["snapshot_id"] == snapshot.id
    assert empty["sha256"] not in state

    # everything now reports imported/empty and a re-apply skips it all —
    # imported via this profile's batch (statement) and via the balances
    # matching the snapshot, not via the shared state file
    statuses = {r.file_name: r.status for r in _staged_listing(db, staging_dir, state_path, ACTIVE)}
    assert statuses == {
        "statement.json": "imported",
        "balances.json": "imported",
        "empty.json": "empty",
    }
    again = _apply_staged(db, staging_dir, state_path, ACTIVE, [statement["sha256"]])
    assert again.outcomes[0].status == "skipped_imported"
    assert db.query(Transaction).count() == 2


def test_apply_staged_refuses_changed_or_unknown_files(db, tmp_path):
    staging_dir, state_path = _setup(tmp_path)
    account = _account(db)
    db.commit()

    statement = _stage(
        staging_dir,
        "statement.json",
        _transactions_payload(account.id),
        account_id=account.id,
        importer_name="staged_json",
    )
    _write_manifest(staging_dir, [statement])
    # file rewritten after staging: sha no longer matches the manifest
    (staging_dir / "statement.json").write_text(
        json.dumps(_transactions_payload(account.id, rows=1))
    )

    result = _apply_staged(db, staging_dir, state_path, ACTIVE, [statement["sha256"], "f" * 64])
    assert result.outcomes[0].status == "error"
    assert "changed since it was staged" in result.outcomes[0].detail
    assert result.outcomes[1].status == "error"
    assert result.outcomes[1].detail == "not in the staging manifest"
    assert db.query(Transaction).count() == 0
    assert not state_path.exists()
