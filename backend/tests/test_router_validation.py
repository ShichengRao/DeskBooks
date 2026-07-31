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
from app.routers import accounts as accounts_router
from app.routers import categories, snapshots, transactions


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


def _account_in(name: str) -> schemas.AccountIn:
    return schemas.AccountIn(name=name, account_category=AccountCategory.bank, type=AccountType.checking)


def test_bulk_account_create_is_all_or_nothing(db):
    existing = _account(db)  # named "Checking"
    db.commit()

    with pytest.raises(HTTPException) as taken:
        accounts_router.create_accounts_bulk(
            schemas.AccountBulkIn(accounts=[_account_in("Checking"), _account_in("CD Ladder")]),
            db,
        )
    assert taken.value.status_code == 422
    assert "Checking" in taken.value.detail
    assert db.query(Account).count() == 1  # nothing partially inserted

    with pytest.raises(HTTPException) as repeated:
        accounts_router.create_accounts_bulk(
            schemas.AccountBulkIn(accounts=[_account_in("Twin"), _account_in(" Twin ")]),
            db,
        )
    assert repeated.value.status_code == 422
    assert "duplicate" in repeated.value.detail

    created = accounts_router.create_accounts_bulk(
        schemas.AccountBulkIn(accounts=[_account_in("CD Ladder"), _account_in(" Rental Checking ")]),
        db,
    )
    assert [a.name for a in created] == ["CD Ladder", "Rental Checking"]
    assert all(a.id and a.id != existing.id for a in created)
    assert db.query(Account).count() == 3


def test_transaction_category_filter_includes_descendants(db):
    account = _account(db)
    housing = _category(db, "Housing")
    rent = _category(db, "Rent", housing)
    food = _category(db, "Food")
    for cat in (rent, food):
        db.add(
            Transaction(
                account_id=account.id,
                date=date(2026, 6, 1),
                description_raw=cat.name.upper(),
                amount=Decimal("-10.00"),
                category_id=cat.id,
                kind=TransactionKind.expense,
            )
        )
    db.commit()

    # selecting the parent surfaces the child's rows...
    rows = transactions.list_transactions(category_id=housing.id, db=db)
    assert [tx.category_id for tx in rows] == [rent.id]
    # ...and the count endpoint agrees
    assert transactions.count_transactions(category_id=housing.id, db=db) == {"count": 1}
    # leaf selection stays exact
    assert transactions.count_transactions(category_id=food.id, db=db) == {"count": 1}


def test_snapshot_routes_reject_unknown_account_ids(db):
    account = _account(db)
    db.commit()

    # e.g. ids prefilled from another profile's staged balances
    with pytest.raises(HTTPException) as create:
        snapshots.create_snapshot(
            schemas.NetWorthSnapshotIn(
                snapshot_date=date(2026, 7, 30),
                balances=[
                    schemas.AccountBalanceIn(account_id=account.id, balance=Decimal("10.00")),
                    schemas.AccountBalanceIn(account_id=25, balance=Decimal("1.00")),
                    schemas.AccountBalanceIn(account_id=26, balance=Decimal("2.00")),
                ],
            ),
            db,
        )
    assert create.value.status_code == 422
    assert "25, 26" in create.value.detail

    created = snapshots.create_snapshot(
        schemas.NetWorthSnapshotIn(
            snapshot_date=date(2026, 7, 30),
            balances=[schemas.AccountBalanceIn(account_id=account.id, balance=Decimal("10.00"))],
        ),
        db,
    )

    with pytest.raises(HTTPException) as update:
        snapshots.update_snapshot(
            created.id,
            schemas.NetWorthSnapshotUpdate(
                balances=[schemas.AccountBalanceIn(account_id=999, balance=Decimal("5.00"))]
            ),
            db,
        )
    assert update.value.status_code == 422
