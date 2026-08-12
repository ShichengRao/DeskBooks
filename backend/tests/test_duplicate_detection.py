from __future__ import annotations

import itertools
from datetime import date
from decimal import Decimal

from app.models import (
    Account,
    AccountCategory,
    AccountType,
    SignConvention,
    Transaction,
    TransactionKind,
)
from app.routers.imports import _existing_rows, _mark_duplicates
from app.schemas import ImportDraftRow


def _account(db) -> Account:
    account = Account(
        name="Credit Card",
        institution=None,
        account_category=AccountCategory.credit,
        type=AccountType.credit_card,
        sign_convention=SignConvention.outflow_negative,
    )
    db.add(account)
    db.flush()
    return account


_row_index = itertools.count()


def _draft(**kwargs) -> ImportDraftRow:
    fields = {
        "row_index": next(_row_index),
        "date": date(2026, 6, 1),
        "description_raw": "CITY TRANSIT",
        "description_normalized": "CITY TRANSIT",
        "merchant": "City Transit",
        "amount": Decimal("-2.90"),
    }
    fields.update(kwargs)
    return ImportDraftRow(**fields)


def test_duplicate_detection_counts_repeated_same_day_transactions(db):
    account = _account(db)
    for _ in range(2):
        db.add(
            Transaction(
                account_id=account.id,
                date=date(2026, 6, 1),
                description_raw="CITY TRANSIT",
                description_normalized="CITY TRANSIT",
                merchant="City Transit",
                amount=Decimal("-2.90"),
                kind=TransactionKind.uncategorized,
            )
        )
    db.commit()

    counts = _existing_rows(db, account.id).key_counts

    assert counts[(date(2026, 6, 1), Decimal("-2.90"), "CITY TRANSIT")] == 2


def test_third_same_day_swipe_is_new_when_db_holds_two(db):
    account = _account(db)
    for _ in range(2):
        db.add(
            Transaction(
                account_id=account.id,
                date=date(2026, 6, 1),
                description_raw="CITY TRANSIT",
                description_normalized="CITY TRANSIT",
                merchant="City Transit",
                amount=Decimal("-2.90"),
                kind=TransactionKind.uncategorized,
            )
        )
    db.commit()

    rows = [_draft(), _draft(), _draft()]
    _mark_duplicates(rows, _existing_rows(db, account.id))

    assert [r.is_duplicate for r in rows] == [True, True, False]


def test_refetch_dedupes_on_provider_id_after_the_date_shifts(db):
    """Plaid backfills authorized_date days after a card transaction posts,
    which moves the reported date earlier. The row is the same transaction."""
    account = _account(db)
    db.add(
        Transaction(
            account_id=account.id,
            date=date(2026, 6, 13),
            description_raw="Coffee Bar",
            description_normalized="Coffee Bar",
            merchant="Coffee Bar",
            amount=Decimal("-7.94"),
            kind=TransactionKind.uncategorized,
            raw={"id": "txn_abc", "date": "2026-06-13", "post_date": None},
        )
    )
    db.commit()

    rows = [
        _draft(
            date=date(2026, 6, 11),
            post_date=date(2026, 6, 13),
            description_raw="Coffee Bar",
            description_normalized="Coffee Bar",
            merchant="Coffee Bar",
            amount=Decimal("-7.94"),
            raw={"id": "txn_abc", "date": "2026-06-11", "post_date": "2026-06-13"},
        )
    ]
    _mark_duplicates(rows, _existing_rows(db, account.id))

    assert rows[0].is_duplicate is True


def test_provider_id_repeated_within_one_file_is_a_duplicate(db):
    account = _account(db)
    db.commit()

    rows = [_draft(raw={"id": "txn_abc"}), _draft(raw={"id": "txn_abc"})]
    _mark_duplicates(rows, _existing_rows(db, account.id))

    assert [r.is_duplicate for r in rows] == [False, True]


def test_distinct_provider_ids_survive_an_identical_dup_key(db):
    """Two genuine same-day, same-amount swipes carry different provider
    ids, so neither may be collapsed into the other."""
    account = _account(db)
    db.add(
        Transaction(
            account_id=account.id,
            date=date(2026, 6, 1),
            description_raw="CITY TRANSIT",
            description_normalized="CITY TRANSIT",
            merchant="City Transit",
            amount=Decimal("-2.90"),
            kind=TransactionKind.uncategorized,
            raw={"id": "txn_one"},
        )
    )
    db.commit()

    rows = [_draft(raw={"id": "txn_one"}), _draft(raw={"id": "txn_two"})]
    _mark_duplicates(rows, _existing_rows(db, account.id))

    assert [r.is_duplicate for r in rows] == [True, False]


def test_connector_row_dedupes_against_untagged_csv_history(db):
    """History imported from CSV carries no provider id. A later connector
    fetch of the same transaction must still recognize it."""
    account = _account(db)
    db.add(
        Transaction(
            account_id=account.id,
            date=date(2026, 6, 1),
            description_raw="CITY TRANSIT",
            description_normalized="CITY TRANSIT",
            merchant="City Transit",
            amount=Decimal("-2.90"),
            kind=TransactionKind.uncategorized,
            raw={"row": 4},
        )
    )
    db.commit()

    rows = [_draft(raw={"id": "txn_new"}), _draft(raw={"id": "txn_other"})]
    _mark_duplicates(rows, _existing_rows(db, account.id))

    assert [r.is_duplicate for r in rows] == [True, False]


def test_rows_without_a_provider_id_still_use_the_positional_key(db):
    """CSV/XLSX rows carry no provider id and must keep the old behavior."""
    account = _account(db)
    db.add(
        Transaction(
            account_id=account.id,
            date=date(2026, 6, 1),
            description_raw="CITY TRANSIT",
            description_normalized="CITY TRANSIT",
            merchant="City Transit",
            amount=Decimal("-2.90"),
            kind=TransactionKind.uncategorized,
            raw={"row": 4},
        )
    )
    db.commit()

    rows = [_draft(raw={"row": 4}), _draft(raw={"row": 5})]
    _mark_duplicates(rows, _existing_rows(db, account.id))

    assert [r.is_duplicate for r in rows] == [True, False]
