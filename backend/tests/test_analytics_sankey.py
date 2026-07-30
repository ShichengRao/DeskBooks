from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

import pytest

from app.analytics import cashflow_sankey, sankey_for_period
from app.models import (
    Account,
    AccountBalance,
    AccountCategory,
    AccountType,
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


def test_sankey_for_period_balances_cashflow_growth_and_account_deltas(db):
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


def test_cashflow_sankey_excludes_transfers_and_balances_residual(db):
    checking = _account(db, "Checking", AccountCategory.bank, AccountType.checking)
    salary = _category(db, "Salary", CategoryKind.income)
    food = _category(db, "Food", CategoryKind.expense)
    groceries = _category(db, "Groceries", CategoryKind.expense, parent=food)

    for seed in [
        _TransactionSeed(salary, date(2026, 5, 15), "5000.00", TransactionKind.income, "Employer"),
        _TransactionSeed(groceries, date(2026, 5, 16), "-100.00", TransactionKind.expense, "Market"),
        _TransactionSeed(None, date(2026, 5, 17), "-50.00", TransactionKind.donation, "Charity"),
        _TransactionSeed(None, date(2026, 5, 18), "-500.00", TransactionKind.tax, "IRS"),
        _TransactionSeed(None, date(2026, 5, 19), "-2000.00", TransactionKind.transfer, "To Savings"),
        _TransactionSeed(None, date(2026, 5, 20), "300.00", TransactionKind.cc_payment, "Card Payment"),
        _TransactionSeed(None, date(2026, 5, 21), "-1000.00", TransactionKind.investment, "Brokerage Buy"),
        _TransactionSeed(None, date(2026, 5, 22), "25.00", TransactionKind.refund, "Store Refund"),
        _TransactionSeed(None, date(2026, 5, 23), "-40.00", TransactionKind.uncategorized, "Mystery"),
    ]:
        _transaction(db, checking, seed)
    db.commit()

    result = cashflow_sankey(db, date(2026, 5, 1), date(2026, 5, 31), "May")
    links = {link["label"]: link["value"] for link in result["links"]}

    assert links["Salary"] == 5000.0
    assert links["Refunds"] == 25.0
    assert links["Invested"] == 1000.0
    assert links["Spending"] == 140.0  # groceries 100 + uncategorized 40
    assert links["Food"] == 100.0  # rolled up to the parent category
    assert links["Not yet categorized"] == 40.0
    assert links["Donations"] == 50.0
    assert links["Taxes"] == 500.0
    # residual: 5025 in - 140 spend - 50 - 500 - 1000 invested = 3335
    assert links["Cash build-up"] == 3335.0
    assert "To Savings" not in links and "Card Payment" not in links
