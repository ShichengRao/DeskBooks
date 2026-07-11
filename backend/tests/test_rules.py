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
    Category,
    CategoryKind,
    SignConvention,
    Transaction,
    TransactionKind,
)
from app.rules import generate_rule_proposals


def _session():
    engine = create_engine("sqlite:///:memory:", future=True)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, future=True)
    return Session()


def _credit_account(db) -> Account:
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


def _category(db, name: str) -> Category:
    category = Category(name=name, kind=CategoryKind.expense)
    db.add(category)
    db.flush()
    return category


def _transaction(
    db,
    account: Account,
    *,
    merchant: str,
    category: Category | None,
    kind: TransactionKind = TransactionKind.expense,
) -> Transaction:
    tx = Transaction(
        account_id=account.id,
        date=date(2026, 6, 1),
        description_raw=merchant.upper(),
        description_normalized=merchant.upper(),
        merchant=merchant,
        amount=Decimal("-2.99"),
        category_id=category.id if category else None,
        kind=kind,
    )
    db.add(tx)
    db.flush()
    return tx


def test_rule_proposals_include_specific_single_token_merchants():
    db = _session()
    try:
        account = _credit_account(db)
        subscriptions = _category(db, "Subscriptions")
        for _ in range(3):
            _transaction(db, account, merchant="Apple.Com/Bill", category=subscriptions)
        _transaction(
            db,
            account,
            merchant="Apple.Com/Bill",
            category=None,
            kind=TransactionKind.uncategorized,
        )
        db.commit()

        proposals = generate_rule_proposals(db, min_support=3, limit=10)

        assert [
            (
                proposal["key"],
                proposal["set_category_id"],
                proposal["set_kind"],
                proposal["support"],
                proposal["all_transaction_matches"],
                proposal["added_transaction_matches"],
            )
            for proposal in proposals
        ] == [
            (
                "Apple.Com/Bill",
                subscriptions.id,
                TransactionKind.expense,
                3,
                4,
                4,
            )
        ]
    finally:
        db.close()


def test_rule_proposals_still_hide_generic_single_word_keys():
    db = _session()
    try:
        account = _credit_account(db)
        subscriptions = _category(db, "Subscriptions")
        for _ in range(3):
            _transaction(db, account, merchant="Payment", category=subscriptions)
        db.commit()

        assert generate_rule_proposals(db, min_support=3, limit=10) == []
    finally:
        db.close()


def test_rule_proposals_group_processor_style_payment_variants():
    db = _session()
    try:
        account = _credit_account(db)
        health = _category(db, "Health")
        for merchant in [
            "Carepay Central Clinic 021000027514801 Jane Doe",
            "Carepay Faculty Practice 021000024054934 Jane Doe",
            "Carepay Bridges Eye 021000029376573 Jane Doe",
        ]:
            _transaction(db, account, merchant=merchant, category=health)
        _transaction(
            db,
            account,
            merchant="Carepay City Medical 021000022960684 Jane Doe",
            category=None,
            kind=TransactionKind.uncategorized,
        )
        db.commit()

        proposals = generate_rule_proposals(db, min_support=3, limit=10)

        assert [
            (
                proposal["key"],
                proposal["match_description_pattern"],
                proposal["set_category_id"],
                proposal["set_kind"],
                proposal["support"],
                proposal["all_transaction_matches"],
                proposal["added_transaction_matches"],
            )
            for proposal in proposals
        ] == [
            (
                "Carepay",
                "Carepay",
                health.id,
                TransactionKind.expense,
                3,
                4,
                4,
            )
        ]
    finally:
        db.close()
