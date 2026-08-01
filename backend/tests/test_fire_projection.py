from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import create_engine

from app.analytics import fire_projection
from app.db import _apply_additive_columns
from app.models import (
    Account,
    AccountBalance,
    AccountCategory,
    AccountType,
    FireSettings,
    NetWorthSnapshot,
    SignConvention,
)


def _snapshot(db, balance: str) -> None:
    account = Account(
        name="Brokerage",
        institution=None,
        account_category=AccountCategory.investment,
        type=AccountType.brokerage,
        sign_convention=SignConvention.outflow_negative,
    )
    db.add(account)
    db.flush()
    snap = NetWorthSnapshot(snapshot_date=date(2026, 7, 1))
    db.add(snap)
    db.flush()
    db.add(AccountBalance(snapshot_id=snap.id, account_id=account.id, balance=Decimal(balance)))
    db.commit()


def test_projection_reports_amount_at_retirement_age_when_target_missed(db):
    db.add(
        FireSettings(
            annual_retirement_spending=Decimal("45000.00"),
            withdrawal_rate=Decimal("0.0400"),
            birth_year=1996,
            retirement_age=65,
        )
    )
    _snapshot(db, "40000.00")

    result = fire_projection(db, max_years=60)

    assert result["retirement_year"] is None  # 40k at 5% never reaches 1.125M
    assert result["retirement_age"] == 65
    assert result["retirement_age_year"] == 2061
    expected = next(row["total"] for row in result["years"] if row["year"] == 2061)
    assert result["total_at_retirement_age"] == expected
    assert expected > Decimal("40000")
    first = result["years"][0]
    assert first["age"] == first["year"] - 1996


def test_projection_without_birth_year_leaves_age_fields_null(db):
    db.add(FireSettings())
    _snapshot(db, "40000.00")

    result = fire_projection(db, max_years=10)

    assert result["retirement_age_year"] is None
    assert result["total_at_retirement_age"] is None
    assert result["years"][0]["age"] is None


def test_additive_column_migration_adds_missing_columns():
    engine = create_engine("sqlite:///:memory:", future=True)
    with engine.begin() as conn:
        conn.exec_driver_sql(
            "CREATE TABLE fire_settings (id INTEGER PRIMARY KEY, withdrawal_rate NUMERIC)"
        )
    _apply_additive_columns(engine)
    with engine.begin() as conn:
        cols = {row[1] for row in conn.exec_driver_sql("PRAGMA table_info(fire_settings)")}
    assert {"birth_year", "retirement_age"} <= cols
    engine.dispose()
