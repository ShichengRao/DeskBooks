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
    # content tweaked so its sha differs from fresh.json
    already_payload = _transactions_payload(account.id)
    already_payload["transactions"][0]["id"] = "txn_seen"
    already = _stage(staging_dir, "already.json", already_payload, account_id=account.id, importer_name="staged_json")
    staging.save_state(state_path, {"applied_sha256": {already["sha256"]: {"empty": True}}})

    _write_manifest(staging_dir, [fresh, other, orphan, balances, gone, already])

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
    assert rows["already.json"].status == "empty"
    # newest-first ordering: manifest order reversed
    assert [r.file_name for r in _staged_listing(db, staging_dir, state_path, ACTIVE)][0] == "already.json"


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
    assert outcomes["statement.json"].status == "imported"
    assert outcomes["statement.json"].rows_applied == 2
    assert outcomes["balances.json"].status == "imported"
    assert outcomes["balances.json"].rows_applied == 1
    assert outcomes["empty.json"].status == "empty"
    assert result.backup_name is None  # no profile_info given, no backup

    batch = db.query(ImportBatch).one()  # the empty file created no batch
    assert batch.notes == f"automation_sha256={statement['sha256']}"
    assert db.query(Transaction).count() == 2
    snapshot = db.query(NetWorthSnapshot).one()
    assert snapshot.snapshot_date == date(2026, 7, 30)

    state = staging.load_state(state_path)["applied_sha256"]
    assert state[statement["sha256"]]["batch_id"] == batch.id
    assert state[balances["sha256"]]["snapshot_id"] == snapshot.id
    assert state[empty["sha256"]] == {"path": empty["path"], "empty": True}

    # everything now reports imported/empty and a re-apply skips it all
    statuses = {r.file_name: r.status for r in _staged_listing(db, staging_dir, state_path, ACTIVE)}
    assert statuses == {"statement.json": "imported", "balances.json": "imported", "empty.json": "empty"}
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
