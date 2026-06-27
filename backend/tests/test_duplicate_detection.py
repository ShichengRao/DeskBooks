from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.models import (
    Account,
    AccountCategory,
    AccountType,
    Base,
    SignConvention,
    Transaction,
    TransactionKind,
)
from app.routers.imports import _existing_key_counts


def test_duplicate_detection_counts_repeated_same_day_transactions():
    engine = create_engine("sqlite:///:memory:", future=True)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, future=True)
    db = Session()
    try:
        account = Account(
            name="Credit Card",
            institution=None,
            account_category=AccountCategory.credit,
            type=AccountType.credit_card,
            is_liquid=True,
            is_taxable=True,
            sign_convention=SignConvention.outflow_negative,
        )
        db.add(account)
        db.flush()
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

        counts = _existing_key_counts(db, account.id)

        assert counts[(date(2026, 6, 1), Decimal("-2.90"), "CITY TRANSIT")] == 2
    finally:
        db.close()
