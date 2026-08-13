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
    Rule,
    SignConvention,
    Transaction,
    TransactionKind,
)
from app.routers import transactions as transactions_router
from app.rules import reapply_to_unreviewed


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


def _tx(
    db, account, amount, *, kind=TransactionKind.expense, description="COFFEE BAR"
) -> Transaction:
    tx = Transaction(
        account_id=account.id,
        date=date(2026, 6, 1),
        description_raw=description,
        description_normalized=description,
        amount=Decimal(amount),
        kind=kind,
    )
    db.add(tx)
    db.flush()
    return tx


def _pair(db, a, b):
    return transactions_router.pair_transactions(
        schemas.TransactionPair(transaction_a_id=a.id, transaction_b_id=b.id), db
    )


def test_pairing_marks_both_transfer_and_remembers_the_previous_kind(db):
    account = _account(db)
    charge = _tx(db, account, "-40.00", kind=TransactionKind.expense)
    refund = _tx(db, account, "40.00", kind=TransactionKind.refund)
    db.commit()

    _pair(db, charge, refund)

    assert charge.kind == TransactionKind.transfer
    assert refund.kind == TransactionKind.transfer
    assert charge.transfer_pair_id == refund.id
    assert refund.transfer_pair_id == charge.id
    assert charge.kind_before_pair == TransactionKind.expense
    assert refund.kind_before_pair == TransactionKind.refund


def test_unlinking_puts_both_kinds_back(db):
    account = _account(db)
    charge = _tx(db, account, "-40.00", kind=TransactionKind.expense)
    refund = _tx(db, account, "40.00", kind=TransactionKind.refund)
    db.commit()
    _pair(db, charge, refund)

    transactions_router.unpair_transaction(charge.id, db)

    assert charge.kind == TransactionKind.expense
    assert refund.kind == TransactionKind.refund
    assert charge.transfer_pair_id is None
    assert refund.transfer_pair_id is None
    assert charge.kind_before_pair is None
    assert refund.kind_before_pair is None


def test_unlinking_a_pair_made_before_this_column_existed_leaves_kinds_alone(db):
    """Rows linked by an older build have nothing stored to restore, so
    unlinking must not invent a kind for them."""
    account = _account(db)
    a = _tx(db, account, "-40.00", kind=TransactionKind.transfer)
    b = _tx(db, account, "40.00", kind=TransactionKind.transfer)
    a.transfer_pair_id = b.id
    b.transfer_pair_id = a.id
    db.commit()

    transactions_router.unpair_transaction(a.id, db)

    assert a.kind == TransactionKind.transfer
    assert b.kind == TransactionKind.transfer
    assert a.transfer_pair_id is None
    assert b.transfer_pair_id is None


def test_a_transaction_cannot_be_paired_with_itself(db):
    account = _account(db)
    only = _tx(db, account, "-40.00")
    db.commit()

    with pytest.raises(HTTPException) as exc:
        _pair(db, only, only)

    assert exc.value.status_code == 400
    assert only.transfer_pair_id is None


def test_pairing_an_already_linked_transaction_is_refused(db):
    """Re-pairing would leave the original partner pointing at a row that
    no longer points back."""
    account = _account(db)
    charge = _tx(db, account, "-40.00")
    refund = _tx(db, account, "40.00")
    other = _tx(db, account, "40.00")
    db.commit()
    _pair(db, charge, refund)

    with pytest.raises(HTTPException) as exc:
        _pair(db, charge, other)

    assert exc.value.status_code == 400
    assert charge.transfer_pair_id == refund.id
    assert refund.transfer_pair_id == charge.id
    assert other.transfer_pair_id is None


def test_deleting_one_side_gives_the_survivor_its_kind_back(db):
    account = _account(db)
    charge = _tx(db, account, "-40.00", kind=TransactionKind.expense)
    refund = _tx(db, account, "40.00", kind=TransactionKind.refund)
    db.commit()
    _pair(db, charge, refund)

    transactions_router.delete_transaction(charge.id, db)

    assert refund.kind == TransactionKind.refund
    assert refund.transfer_pair_id is None
    assert refund.kind_before_pair is None


def test_reapplying_rules_leaves_linked_transactions_alone(db):
    """A rule must not pull one side of a pair back into spending while
    the other side stays out."""
    account = _account(db)
    charge = _tx(db, account, "-40.00", description="COFFEE BAR")
    refund = _tx(db, account, "40.00", description="COFFEE BAR")
    loose = _tx(db, account, "-9.00", kind=TransactionKind.uncategorized, description="COFFEE BAR")
    db.add(
        Rule(
            name="coffee",
            priority=10,
            is_active=True,
            match_description_pattern="COFFEE",
            set_kind=TransactionKind.expense,
        )
    )
    db.commit()
    _pair(db, charge, refund)

    reapply_to_unreviewed(db)

    assert charge.kind == TransactionKind.transfer
    assert refund.kind == TransactionKind.transfer
    assert loose.kind == TransactionKind.expense
