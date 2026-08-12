from __future__ import annotations

from datetime import date
from decimal import Decimal

from app.analytics import monthly_breakdown, recurring_merchants
from app.budgets import budget_report
from app.models import (
    Account,
    AccountCategory,
    AccountType,
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
    budget_date: date | None = None,
) -> Transaction:
    tx = Transaction(
        account_id=account.id,
        date=txn_date,
        budget_date=budget_date,
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


def test_budget_report_applies_defaults_and_monthly_overrides_to_actual_spending(db):
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


def test_budget_report_hides_archived_categories_without_activity(db):
    account = _checking_account(db)
    live = _category(db, "Food")
    empty_archived = _category(db, "Old Hobby")
    empty_archived.archived = True
    spent_archived = _category(db, "Legacy Dining")
    spent_archived.archived = True
    planned_archived = _category(db, "Legacy Plan")
    planned_archived.archived = True
    _expense(db, account, live, date(2026, 6, 3), "-10.00")
    _expense(db, account, spent_archived, date(2026, 6, 4), "-25.00")
    db.add(BudgetDefault(category_id=planned_archived.id, amount=Decimal("40.00")))
    db.commit()

    result = budget_report(db, date(2026, 6, 1), date(2026, 6, 30))
    rows = {row["category_name"]: row for row in result["rows"]}

    assert "Old Hobby" not in rows
    assert "Old Hobby (archived)" not in rows
    assert rows["Legacy Dining (archived)"]["actual_amount"] == Decimal("-25.00") * -1
    assert rows["Legacy Plan (archived)"]["default_amount"] == Decimal("40.00")
    assert "Food" in rows


def _rent_setup(db):
    account = _checking_account(db)
    housing = _category(db, "Housing")
    rent = _category(db, "Rent", parent=housing)
    return account, rent


def _actual_by_month(report: dict) -> dict[date, Decimal]:
    return {m["month"]: m["actual_total"] for m in report["months"]}


def test_budget_date_moves_spending_to_the_month_it_is_assigned(db):
    """Rent paid Aug 1 for July counts in July, not August."""
    account, rent = _rent_setup(db)
    _expense(db, account, rent, date(2026, 7, 1), "-1500.00", budget_date=date(2026, 6, 30))
    _expense(db, account, rent, date(2026, 8, 1), "-1500.00", budget_date=date(2026, 7, 31))
    db.commit()

    report = budget_report(db, date(2026, 6, 1), date(2026, 8, 31))
    actual = _actual_by_month(report)

    assert actual[date(2026, 6, 1)] == Decimal("1500.00")
    assert actual[date(2026, 7, 1)] == Decimal("1500.00")
    assert actual[date(2026, 8, 1)] == Decimal("0")


def test_untouched_transactions_still_count_in_their_own_month(db):
    account, rent = _rent_setup(db)
    _expense(db, account, rent, date(2026, 7, 1), "-1500.00")
    db.commit()

    actual = _actual_by_month(budget_report(db, date(2026, 6, 1), date(2026, 8, 31)))

    assert actual[date(2026, 7, 1)] == Decimal("1500.00")
    assert actual[date(2026, 6, 1)] == Decimal("0")


def test_transaction_reassigned_into_the_window_from_outside_is_counted(db):
    """The row's own date sits past the window end; only budget_date puts
    it in range, so the query must filter on the same expression."""
    account, rent = _rent_setup(db)
    _expense(db, account, rent, date(2026, 9, 1), "-1500.00", budget_date=date(2026, 8, 31))
    db.commit()

    actual = _actual_by_month(budget_report(db, date(2026, 8, 1), date(2026, 8, 31)))

    assert actual[date(2026, 8, 1)] == Decimal("1500.00")


def test_transaction_reassigned_out_of_the_window_is_not_counted(db):
    account, rent = _rent_setup(db)
    _expense(db, account, rent, date(2026, 8, 2), "-1500.00", budget_date=date(2026, 7, 31))
    db.commit()

    actual = _actual_by_month(budget_report(db, date(2026, 8, 1), date(2026, 8, 31)))

    assert actual[date(2026, 8, 1)] == Decimal("0")


def test_reassigned_spending_lands_on_the_category_row_for_that_month(db):
    account, rent = _rent_setup(db)
    _expense(db, account, rent, date(2026, 8, 1), "-1500.00", budget_date=date(2026, 7, 31))
    db.commit()

    report = budget_report(db, date(2026, 7, 1), date(2026, 8, 31), date(2026, 7, 1))
    rows = {r["category_name"]: r for r in report["rows"]}

    assert rows["Rent"]["actual_amount"] == Decimal("1500.00")
    assert rows["Rent"]["transaction_count"] == 1


def test_monthly_breakdown_follows_the_same_attribution(db):
    """The Analytics month chart and the budget report must not disagree."""
    account, rent = _rent_setup(db)
    _expense(db, account, rent, date(2026, 8, 1), "-1500.00", budget_date=date(2026, 7, 31))
    db.commit()

    months = {m["month"]: m for m in monthly_breakdown(db, date(2026, 7, 1), date(2026, 8, 31))}

    assert months["2026-07"]["expenses_total"] == Decimal("1500.00")
    assert "2026-08" not in months or months["2026-08"]["expenses_total"] == Decimal("0")


def test_recurrence_detection_still_sees_the_real_dates(db):
    """budget_date is an attribution overlay, not a rewrite of history —
    anything describing when money actually moved keeps using `date`."""
    account, rent = _rent_setup(db)
    paid = [
        _expense(db, account, rent, date(2026, 6, 1), "-1500.00"),
        _expense(db, account, rent, date(2026, 7, 1), "-1500.00"),
        _expense(db, account, rent, date(2026, 8, 1), "-1500.00", budget_date=date(2026, 7, 31)),
    ]
    for tx in paid:
        tx.merchant = "RENT"  # the key recurrence groups on
    db.commit()

    rows = recurring_merchants(db, start=date(2026, 6, 1), end=date(2026, 8, 31))
    row = next(r for r in rows if r["merchant"] == "RENT")

    assert row["last_seen"] == date(2026, 8, 1)
    assert row["occurrences"] == 3
