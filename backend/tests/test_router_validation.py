from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest
from fastapi import HTTPException

from app import schemas
from app.models import (
    Account,
    AccountCategory,
    AccountType,
    Category,
    CategoryKind,
    SignConvention,
    Transaction,
    TransactionKind,
)
from app.routers import categories, transactions


def _category(db, name: str, parent: Category | None = None) -> Category:
    category = Category(
        name=name,
        kind=CategoryKind.expense,
        parent_id=parent.id if parent else None,
    )
    db.add(category)
    db.flush()
    return category


def _account(db) -> Account:
    account = Account(
        name="Checking",
        institution=None,
        account_category=AccountCategory.bank,
        type=AccountType.checking,
        sign_convention=SignConvention.outflow_negative,
    )
    db.add(account)
    db.flush()
    return account


def _transaction(db, account: Account) -> Transaction:
    tx = Transaction(
        account_id=account.id,
        date=date(2026, 6, 1),
        description_raw="TEST",
        amount=Decimal("-10.00"),
        kind=TransactionKind.uncategorized,
    )
    db.add(tx)
    db.flush()
    return tx


def test_category_routes_reject_missing_self_and_descendant_parents(db):
    root = _category(db, "Root")
    child = _category(db, "Child", root)
    db.commit()

    with pytest.raises(HTTPException) as missing:
        categories.create_category(
            schemas.CategoryIn(
                name="Orphan",
                parent_id=999,
                kind=CategoryKind.expense,
            ),
            db,
        )
    assert missing.value.status_code == 404

    with pytest.raises(HTTPException) as self_parent:
        categories.update_category(
            root.id,
            schemas.CategoryUpdate(parent_id=root.id),
            db,
        )
    assert self_parent.value.status_code == 400

    with pytest.raises(HTTPException) as descendant_parent:
        categories.update_category(
            root.id,
            schemas.CategoryUpdate(parent_id=child.id),
            db,
        )
    assert descendant_parent.value.status_code == 400


def test_transaction_routes_reject_missing_category_ids(db):
    account = _account(db)
    tx = _transaction(db, account)
    db.commit()

    with pytest.raises(HTTPException) as single_update:
        transactions.update_transaction(
            tx.id,
            schemas.TransactionUpdate(category_id=999),
            db,
        )
    assert single_update.value.status_code == 404

    with pytest.raises(HTTPException) as bulk_update:
        transactions.bulk_update(
            schemas.TransactionBulkUpdate(ids=[tx.id], category_id=999),
            db,
        )
    assert bulk_update.value.status_code == 404
