from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.analytics import sankey_for_period
from app.models import (
    Account,
    AccountBalance,
    AccountCategory,
    AccountType,
    Base,
    Category,
    CategoryKind,
    NetWorthSnapshot,
    SignConvention,
    Transaction,
    TransactionKind,
)


@dataclass(frozen=True)
class _TransactionSeed:
    category: Category | None
    transaction_date: date
    amount: str
    kind: TransactionKind
    merchant: str


def _session():
    engine = create_engine("sqlite:///:memory:", future=True)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, future=True)
    return Session()


def _account(
    db,
    name: str,
    category: AccountCategory,
    account_type: AccountType,
) -> Account:
    account = Account(
        name=name,
        institution=None,
        account_category=category,
        type=account_type,
        is_liquid=category == AccountCategory.bank,
        is_taxable=True,
        sign_convention=SignConvention.outflow_negative,
    )
    db.add(account)
    db.flush()
    return account


def _category(
    db,
    name: str,
    kind: CategoryKind,
    parent: Category | None = None,
) -> Category:
    category = Category(name=name, kind=kind, parent_id=parent.id if parent else None)
    db.add(category)
    db.flush()
    return category


def _transaction(
    db,
    account: Account,
    seed: _TransactionSeed,
) -> None:
    db.add(
        Transaction(
            account_id=account.id,
            date=seed.transaction_date,
            description_raw=seed.merchant.upper(),
            description_normalized=seed.merchant.upper(),
            merchant=seed.merchant,
            amount=Decimal(seed.amount),
            category_id=seed.category.id if seed.category else None,
            kind=seed.kind,
        )
    )


def _snapshot(db, snapshot_date: date, balances: dict[Account, str]) -> None:
    snapshot = NetWorthSnapshot(snapshot_date=snapshot_date)
    db.add(snapshot)
    db.flush()
    for account, balance in balances.items():
        db.add(
            AccountBalance(
                snapshot_id=snapshot.id,
                account_id=account.id,
                balance=Decimal(balance),
            )
        )


def test_sankey_for_period_balances_cashflow_growth_and_account_deltas():
    db = _session()
    try:
        checking = _account(db, "Checking", AccountCategory.bank, AccountType.checking)
        brokerage = _account(
            db,
            "Brokerage",
            AccountCategory.investment,
            AccountType.brokerage,
        )

        salary = _category(db, "Salary", CategoryKind.income)
        food = _category(db, "Food", CategoryKind.expense)
        groceries = _category(db, "Groceries", CategoryKind.expense, parent=food)

        for seed in [
            _TransactionSeed(salary, date(2026, 1, 15), "5000.00", TransactionKind.income, "Employer"),
            _TransactionSeed(groceries, date(2026, 1, 16), "-100.00", TransactionKind.expense, "Market"),
            _TransactionSeed(None, date(2026, 1, 17), "-50.00", TransactionKind.donation, "Local Charity"),
            _TransactionSeed(None, date(2026, 1, 18), "-500.00", TransactionKind.tax, "IRS"),
        ]:
            _transaction(db, checking, seed)
        _snapshot(
            db,
            date(2026, 1, 1),
            {checking: "1000.00", brokerage: "2000.00"},
        )
        _snapshot(
            db,
            date(2026, 2, 1),
            {checking: "1500.00", brokerage: "6500.00"},
        )
        db.commit()

        result = sankey_for_period(db, date(2026, 1, 1), date(2026, 1, 31), "January")
        node_names = {node["name"] for node in result["nodes"]}
        links_by_label = {link["label"]: link for link in result["links"]}

        assert {
            "Inflows",
            "Income",
            "Salary",
            "Growth",
            "Stock Growth",
            "Bank Interest",
            "Expenses",
            "Groceries",
            "Donations",
            "Taxes",
            "Account deltas (pos)",
            "Stock Account",
            "CDs + Bank Accounts",
        }.issubset(node_names)

        expected_link_values = {
            "Income": 5000.0,
            "Expenses": 100.0,
            "Donations": 50.0,
            "Taxes": 500.0,
            "Growth": 650.0,
            "Stock Growth": 585.0,
            "Bank Interest": 65.0,
            "Account deltas": 5000.0,
            "Stock Account": 4500.0,
            "CDs + Bank Accounts": 500.0,
        }
        for label, value in expected_link_values.items():
            assert links_by_label[label]["value"] == pytest.approx(value)
        assert result["year"] == 2026
        assert result["label"] == "January"
        assert any("2026-01-01" in note and "2026-02-01" in note for note in result["notes"])
    finally:
        db.close()
