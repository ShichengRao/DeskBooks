from __future__ import annotations

import hashlib
import json
from datetime import date
from decimal import Decimal

import pytest

from app import balance_snapshots
from app.importers.staged_json import parse_staged_transactions_bytes
from app.models import (
    Account,
    AccountBalance,
    AccountCategory,
    AccountType,
    NetWorthSnapshot,
    SignConvention,
)
from app.routers.imports import _preview_from_bytes
from app.staging import staged_file_profile, validate_entry


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


def _staged_transactions_payload() -> dict:
    return {
        "format": "deskbooks.staged-transactions/v1",
        "account_id": 1,
        "transactions": [
            {
                "id": "txn_1",
                "date": "2026-07-01",
                "description": "ACME  GROCERY",
                "amount": "-42.50",
                "pending": False,
                "post_date": "2026-07-02",
                "merchant": "Acme Grocery",
            },
            {
                "id": "txn_2",
                "date": "2026-07-03",
                "description": "PENDING HOLD",
                "amount": "-10.00",
                "pending": True,
            },
        ],
    }


def test_staged_json_parses_rows_and_skips_pending():
    rows = parse_staged_transactions_bytes(json.dumps(_staged_transactions_payload()).encode())
    assert len(rows) == 1
    row = rows[0]
    assert row.date == date(2026, 7, 1)
    assert row.post_date == date(2026, 7, 2)
    assert row.amount == Decimal("-42.50")
    assert row.description_normalized == "ACME GROCERY"
    assert row.merchant == "Acme Grocery"
    assert row.raw["id"] == "txn_1"


def test_staged_json_rejects_wrong_format_and_bad_rows():
    with pytest.raises(ValueError, match="not a deskbooks.staged-transactions/v1"):
        parse_staged_transactions_bytes(b'{"format": "something-else", "transactions": []}')
    bad_amount = _staged_transactions_payload()
    bad_amount["transactions"][0]["amount"] = "not-money"
    with pytest.raises(ValueError, match="invalid date/amount"):
        parse_staged_transactions_bytes(json.dumps(bad_amount).encode())


def test_preview_from_bytes_routes_json_files_to_staged_importer(db):
    account = _account(db)
    payload = _staged_transactions_payload()
    preview = _preview_from_bytes(
        db,
        data=json.dumps(payload).encode(),
        filename="2026-07-03-plaid-acct1-transactions.json",
        account_id=account.id,
        importer_name="staged_json",
    )
    assert preview.importer_name == "staged_json"
    assert len(preview.rows) == 1
    assert preview.rows[0].is_duplicate is False


def _balances_payload(account_id: int, balance: str | None = "1234.56") -> bytes:
    return json.dumps(
        {
            "format": "deskbooks.staged-balances/v1",
            "as_of": "2026-07-30",
            "balances": [{"account_id": account_id, "balance": balance}],
        }
    ).encode()


def test_balances_apply_creates_snapshot_and_is_idempotent(db):
    account = _account(db)
    staged = balance_snapshots.parse_staged_balances_bytes(_balances_payload(account.id))

    plan = balance_snapshots.plan_staged_balances(db, staged)
    assert plan.created_snapshot is True
    assert plan.updated == 1
    assert db.query(NetWorthSnapshot).count() == 0  # preview writes nothing

    result = balance_snapshots.apply_staged_balances(db, staged)
    assert result.created_snapshot is True
    assert result.updated == 1
    snapshot = db.query(NetWorthSnapshot).one()
    assert snapshot.snapshot_date == date(2026, 7, 30)
    assert snapshot.balances[0].balance == Decimal("1234.56")

    again = balance_snapshots.apply_staged_balances(db, staged)
    assert again.created_snapshot is False
    assert again.updated == 0
    assert again.unchanged == 1
    assert db.query(NetWorthSnapshot).count() == 1
    assert db.query(AccountBalance).count() == 1


def test_balances_apply_merges_into_existing_snapshot(db):
    first = _account(db, "Checking")
    second = _account(db, "Savings")
    snapshot = NetWorthSnapshot(snapshot_date=date(2026, 7, 30))
    db.add(snapshot)
    db.flush()
    db.add(AccountBalance(snapshot_id=snapshot.id, account_id=first.id, balance=Decimal("1.00")))
    db.commit()

    staged = balance_snapshots.parse_staged_balances_bytes(
        json.dumps(
            {
                "format": "deskbooks.staged-balances/v1",
                "as_of": "2026-07-30",
                "balances": [
                    {"account_id": first.id, "balance": "2.00"},
                    {"account_id": second.id, "balance": "3.00"},
                ],
            }
        ).encode()
    )
    result = balance_snapshots.apply_staged_balances(db, staged)
    assert result.created_snapshot is False
    assert result.snapshot_id == snapshot.id
    assert result.updated == 2
    balances = {bal.account_id: bal.balance for bal in db.query(AccountBalance).all()}
    assert balances == {first.id: Decimal("2.00"), second.id: Decimal("3.00")}


def test_balances_apply_skips_null_and_unknown_accounts(db):
    account = _account(db)
    staged = balance_snapshots.parse_staged_balances_bytes(
        json.dumps(
            {
                "format": "deskbooks.staged-balances/v1",
                "as_of": "2026-07-30",
                "balances": [
                    {"account_id": account.id, "balance": None},
                    {"account_id": 999, "balance": "5.00"},
                ],
            }
        ).encode()
    )
    result = balance_snapshots.apply_staged_balances(db, staged)
    assert result.skipped_null == 1
    assert result.unknown_account_ids == [999]
    assert result.updated == 0
    assert db.query(AccountBalance).count() == 0


def test_collect_staged_prefill_returns_newest_balance_per_account(tmp_path):
    staging = tmp_path / "staging"
    staging.mkdir()

    def stage(name: str, as_of: str, rows: list[dict], source: str) -> str:
        path = staging / name
        path.write_text(
            json.dumps(
                {"format": "deskbooks.staged-balances/v1", "as_of": as_of, "balances": rows}
            )
        )
        return json.dumps({"kind": "balances", "path": str(path), "source": source})

    older = stage("old.json", "2026-07-01", [{"account_id": 6, "balance": "25088.00"}], "plaid_a")
    newer = stage(
        "new.json",
        "2026-07-30",
        [{"account_id": 6, "balance": "10088.95"}, {"account_id": 7, "balance": None}],
        "plaid_b",
    )
    missing = json.dumps({"kind": "balances", "path": str(staging / "gone.json"), "source": "x"})
    statement = json.dumps({"kind": "statement", "path": str(staging / "old.json"), "source": "y"})
    (staging / "manifest.jsonl").write_text("\n".join([older, newer, missing, statement, "not-json"]) + "\n")

    rows = balance_snapshots.collect_staged_prefill(staging)
    assert rows == [
        {
            "account_id": 6,
            "balance": Decimal("10088.95"),
            "as_of": date(2026, 7, 30),
            "source": "plaid_b",
        }
    ]
    assert balance_snapshots.collect_staged_prefill(tmp_path / "nope") == []


def test_parse_staged_balances_reads_profile_stamp():
    payload = {
        "format": "deskbooks.staged-balances/v1",
        "profile": "personal",
        "as_of": "2026-07-30",
        "balances": [],
    }
    staged = balance_snapshots.parse_staged_balances_bytes(json.dumps(payload).encode())
    assert staged.profile == "personal"

    del payload["profile"]
    assert balance_snapshots.parse_staged_balances_bytes(json.dumps(payload).encode()).profile is None

    payload["profile"] = 7
    with pytest.raises(ValueError, match="invalid profile"):
        balance_snapshots.parse_staged_balances_bytes(json.dumps(payload).encode())


def test_collect_staged_prefill_scopes_to_profile_and_known_accounts(tmp_path):
    staging = tmp_path / "staging"
    staging.mkdir()

    def stage(name: str, rows: list[dict], profile: str | None) -> str:
        payload = {"format": "deskbooks.staged-balances/v1", "as_of": "2026-07-30", "balances": rows}
        if profile is not None:
            payload["profile"] = profile
        path = staging / name
        path.write_text(json.dumps(payload))
        return json.dumps({"kind": "balances", "path": str(path), "source": name})

    lines = [
        stage("mine.json", [{"account_id": 1, "balance": "10.00"}], "personal"),
        stage("other.json", [{"account_id": 1, "balance": "99999.99"}], "test-profile"),
        stage(
            "legacy.json",
            [{"account_id": 2, "balance": "20.00"}, {"account_id": 42, "balance": "30.00"}],
            None,
        ),
    ]
    (staging / "manifest.jsonl").write_text("\n".join(lines) + "\n")

    rows = balance_snapshots.collect_staged_prefill(
        staging, active_profile="personal", valid_account_ids={1, 2}
    )
    # The other profile's file is skipped even though it shares account id 1,
    # and the legacy file's unknown id 42 is dropped while id 2 survives.
    assert rows == [
        {"account_id": 1, "balance": Decimal("10.00"), "as_of": date(2026, 7, 30), "source": "mine.json"},
        {"account_id": 2, "balance": Decimal("20.00"), "as_of": date(2026, 7, 30), "source": "legacy.json"},
    ]


def test_staged_file_profile_prefers_manifest_then_file(tmp_path):
    stamped = tmp_path / "stamped.json"
    stamped.write_text(json.dumps({"format": "x", "profile": "from-file"}))
    unstamped = tmp_path / "unstamped.json"
    unstamped.write_text(json.dumps({"format": "x"}))
    broken = tmp_path / "broken.json"
    broken.write_text("{nope")

    assert staged_file_profile({"profile": "from-manifest"}, stamped) == "from-manifest"
    assert staged_file_profile({}, stamped) == "from-file"
    assert staged_file_profile({"profile": "  "}, unstamped) is None
    assert staged_file_profile({}, broken) is None


def _entry_for(tmp_path, *, kind: str | None, **overrides):
    staged = tmp_path / "staging"
    staged.mkdir(exist_ok=True)
    file_path = staged / "file.json"
    file_path.write_text("{}", encoding="utf-8")
    entry = {
        "path": str(file_path),
        "sha256": hashlib.sha256(b"{}").hexdigest(),
        **overrides,
    }
    if kind is not None:
        entry["kind"] = kind
    return entry, staged


def test_validate_entry_defaults_to_statement_and_requires_importer(tmp_path):
    entry, staging = _entry_for(tmp_path, kind=None, account_id=3, importer_name="staged_json")
    kind, _, account_id, importer_name, _ = validate_entry(entry, staging)
    assert (kind, account_id, importer_name) == ("statement", 3, "staged_json")

    missing, staging = _entry_for(tmp_path, kind=None, account_id=3)
    with pytest.raises(ValueError, match="missing field: importer_name"):
        validate_entry(missing, staging)


def test_validate_entry_balances_needs_no_importer_or_account(tmp_path):
    entry, staging = _entry_for(tmp_path, kind="balances")
    kind, _, account_id, importer_name, _ = validate_entry(entry, staging)
    assert (kind, account_id, importer_name) == ("balances", None, None)

    unknown, staging = _entry_for(tmp_path, kind="prices")
    with pytest.raises(ValueError, match="unknown kind"):
        validate_entry(unknown, staging)
