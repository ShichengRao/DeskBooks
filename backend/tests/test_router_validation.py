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
    return schemas.AccountIn(
        name=name, account_category=AccountCategory.bank, type=AccountType.checking
    )


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
        schemas.AccountBulkIn(
            accounts=[_account_in("CD Ladder"), _account_in(" Rental Checking ")]
        ),
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


def test_category_nesting_is_limited_to_one_level(db):
    root = _category(db, "Root")
    child = _category(db, "Child", root)
    other = _category(db, "Other")
    db.commit()

    # nesting under a subcategory is refused
    with pytest.raises(HTTPException) as deep:
        categories.update_category(other.id, schemas.CategoryUpdate(parent_id=child.id), db)
    assert deep.value.status_code == 400
    assert "one level" in deep.value.detail

    # a category with children can't itself be nested
    with pytest.raises(HTTPException) as parentful:
        categories.update_category(root.id, schemas.CategoryUpdate(parent_id=other.id), db)
    assert parentful.value.status_code == 400
    assert "subcategories" in parentful.value.detail

    # plain one-level nesting still works
    moved = categories.update_category(other.id, schemas.CategoryUpdate(parent_id=root.id), db)
    assert moved.parent_id == root.id


def test_category_usage_counts_references(db):
    account = _account(db)
    used = _category(db, "Used")
    empty = _category(db, "Empty")
    db.add(
        Transaction(
            account_id=account.id,
            date=date(2026, 6, 1),
            description_raw="X",
            amount=Decimal("-1.00"),
            category_id=used.id,
            kind=TransactionKind.expense,
        )
    )
    db.commit()

    usage = {u.category_id: u for u in categories.category_usage(db)}
    assert usage[used.id].transactions == 1
    assert usage[empty.id].transactions == 0


def test_kind_settings_roundtrip_and_guard(db):
    from app.routers import settings as settings_router

    initial = settings_router.get_kind_settings(db)
    assert initial["hidden"] == []
    assert initial["counts"]["expense"] == 0

    out = settings_router.put_kind_settings(
        schemas.KindSettingsIn(hidden=[TransactionKind.refund, TransactionKind.other_non_expense]),
        db,
    )
    assert out["hidden"] == ["other_non_expense", "refund"]

    with pytest.raises(HTTPException) as guarded:
        settings_router.put_kind_settings(
            schemas.KindSettingsIn(hidden=[TransactionKind.uncategorized]), db
        )
    assert guarded.value.status_code == 400


def test_category_merge_moves_references_and_archives_source(db):
    from app.models import Rule

    account = _account(db)
    source = _category(db, "Health")
    target = _category(db, "Health & Wellness")
    other_kind = Category(name="Salary", kind=CategoryKind.income)
    db.add(other_kind)
    db.add(
        Transaction(
            account_id=account.id,
            date=date(2026, 6, 1),
            description_raw="CLINIC",
            amount=Decimal("-50.00"),
            category_id=source.id,
            kind=TransactionKind.expense,
        )
    )
    db.add(Rule(name="Clinic", match_description_pattern="Clinic", set_category_id=source.id))
    db.flush()
    db.commit()

    with pytest.raises(HTTPException) as selfmerge:
        categories.merge_category(source.id, schemas.CategoryMergeIn(target_id=source.id), db)
    assert selfmerge.value.status_code == 400

    with pytest.raises(HTTPException) as kinds:
        categories.merge_category(source.id, schemas.CategoryMergeIn(target_id=other_kind.id), db)
    assert kinds.value.status_code == 400
    assert "kinds differ" in kinds.value.detail

    result = categories.merge_category(source.id, schemas.CategoryMergeIn(target_id=target.id), db)
    assert result.transactions_moved == 1
    assert result.rules_moved == 1
    assert db.query(Transaction).one().category_id == target.id
    assert db.query(Rule).one().set_category_id == target.id
    assert db.get(Category, source.id).archived is True
