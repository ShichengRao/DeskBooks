from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.budgets import budget_report
from app.models import (
    Account,
    AccountCategory,
    AccountType,
    Base,
    BudgetDefault,
    BudgetOverride,
    Category,
    CategoryKind,
    SignConvention,
    Transaction,
    TransactionKind,
    TransactionSplit,
)


FOCUS_MONTH_TOTALS = {
    date(2026, 6, 1): {
        "planned_total": Decimal("260.00"),
        "actual_total": Decimal("250.000000"),
        "delta_total": Decimal("10.000000"),
    },
    date(2026, 7, 1): {
        "planned_total": Decimal("330.00"),
        "actual_total": Decimal("200.00"),
        "delta_total": Decimal("130.00"),
    },
}
FOCUS_REPORT_TOTALS = {
    "planned_total": Decimal("590.00"),
    "actual_total": Decimal("450.000000"),
    "delta_total": Decimal("140.000000"),
}
FOCUS_ROW_TOTALS = {
    "Food": {
        "default_amount": Decimal("80.00"),
        "target_amount": Decimal("80.00"),
        "actual_amount": Decimal("0"),
        "delta": Decimal("80.00"),
    },
    "Housing": {
        "default_amount": Decimal("999.00"),
        "target_amount": Decimal("250.00"),
        "actual_amount": Decimal("200.00"),
        "delta": Decimal("50.00"),
    },
    "Rent": {
        "default_amount": Decimal("180.00"),
        "override_amount": Decimal("250.00"),
        "target_amount": Decimal("250.00"),
        "actual_amount": Decimal("200.00"),
        "delta": Decimal("50.00"),
    },
}
RANGE_ROW_TOTALS = {
    "Food": {
        "target_amount": Decimal("160.00"),
        "actual_amount": Decimal("50.000000"),
    },
    "Housing": {
        "target_amount": Decimal("430.00"),
        "actual_amount": Decimal("400.00"),
        "delta": Decimal("30.00"),
    },
}


def _session():
    engine = create_engine("sqlite:///:memory:", future=True)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, future=True)
    return Session()


def _checking_account(db) -> Account:
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


def _category(db, name: str, parent: Category | None = None) -> Category:
    category = Category(name=name, kind=CategoryKind.expense, parent=parent)
    db.add(category)
    db.flush()
    return category


def _expense(
    db,
    account: Account,
    category: Category,
    txn_date: date,
    amount: str,
    *,
    excluded: bool = False,
) -> Transaction:
    tx = Transaction(
        account_id=account.id,
        date=txn_date,
        description_raw=category.name.upper(),
        amount=Decimal(amount),
        category_id=category.id,
        kind=TransactionKind.expense,
        is_excluded_from_totals=excluded,
    )
    db.add(tx)
    db.flush()
    return tx


def _seed_budget_report_case(db) -> None:
    account = _checking_account(db)
    food = _category(db, "Food")
    groceries = _category(db, "Groceries", parent=food)
    housing = _category(db, "Housing")
    rent = _category(db, "Rent", parent=housing)

    shared = _expense(db, account, groceries, date(2026, 6, 3), "-100.00")
    db.add(
        TransactionSplit(
            transaction_id=shared.id,
            group_name="Household",
            personal_share=Decimal("0.5000"),
        )
    )
    _expense(db, account, rent, date(2026, 6, 5), "-200.00")
    _expense(db, account, rent, date(2026, 6, 6), "-999.00", excluded=True)
    _expense(db, account, rent, date(2026, 7, 5), "-200.00")
    db.add_all(
        [
            BudgetDefault(category_id=food.id, amount=Decimal("80.00")),
            BudgetDefault(category_id=housing.id, amount=Decimal("999.00")),
            BudgetDefault(category_id=rent.id, amount=Decimal("180.00")),
            BudgetOverride(
                month=date(2026, 7, 1),
                category_id=rent.id,
                amount=Decimal("250.00"),
            ),
        ]
    )
    db.commit()


def _assert_values(row: dict, expected: dict[str, Decimal | None]) -> None:
    for key, value in expected.items():
        assert row[key] == value


def _assert_named_values(rows: dict, expectations: dict) -> None:
    for name, expected in expectations.items():
        _assert_values(rows[name], expected)


def test_budget_report_applies_defaults_and_monthly_overrides_to_actual_spending():
    db = _session()
    try:
        _seed_budget_report_case(db)

        result = budget_report(db, date(2026, 6, 24), date(2026, 7, 20), date(2026, 7, 1))
        rows = {row["category_name"]: row for row in result["rows"]}
        months = {row["month"]: row for row in result["months"]}

        assert result["start"] == date(2026, 6, 1)
        assert result["end"] == date(2026, 7, 1)
        assert result["focus_month"] == date(2026, 7, 1)
        _assert_named_values(months, FOCUS_MONTH_TOTALS)
        _assert_values(result, FOCUS_REPORT_TOTALS)
        assert rows["Groceries"]["target_amount"] is None
        _assert_named_values(rows, FOCUS_ROW_TOTALS)

        range_result = budget_report(db, date(2026, 6, 24), date(2026, 7, 20))
        range_rows = {row["category_name"]: row for row in range_result["rows"]}

        assert range_result["focus_month"] is None
        _assert_named_values(range_rows, RANGE_ROW_TOTALS)
        _assert_values(range_result, FOCUS_REPORT_TOTALS)
    finally:
        db.close()
