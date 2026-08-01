from __future__ import annotations

from datetime import date
from decimal import Decimal

from app.analytics import cancel_out_candidates, linked_cancel_pairs
from app.models import (
    Account,
    AccountCategory,
    AccountType,
    SignConvention,
    Transaction,
    TransactionKind,
)


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


def _tx(db, account, txn_date, amount, *, kind=TransactionKind.expense, pair=None, excluded=False):
    tx = Transaction(
        account_id=account.id,
        date=txn_date,
        description_raw=f"ROW {txn_date} {amount}",
        amount=Decimal(amount),
        kind=kind,
        transfer_pair_id=pair.id if pair else None,
        is_excluded_from_totals=excluded,
    )
    db.add(tx)
    db.flush()
    return tx


def test_cancel_candidates_matches_opposites_within_window(db):
    account = _account(db)
    fee = _tx(db, account, date(2026, 3, 3), "-120.00")
    refund = _tx(db, account, date(2026, 3, 9), "120.00", kind=TransactionKind.refund)
    # same magnitude but too far apart
    _tx(db, account, date(2026, 1, 2), "-75.00")
    _tx(db, account, date(2026, 4, 20), "75.00")
    # unmatched magnitude
    _tx(db, account, date(2026, 3, 5), "-33.33")
    # transfers never suggested
    _tx(db, account, date(2026, 3, 6), "-120.00", kind=TransactionKind.transfer)
    db.commit()

    candidates = cancel_out_candidates(db, date(2026, 1, 1), date(2026, 12, 31), window_days=45)

    assert len(candidates) == 1
    only = candidates[0]
    assert {only["a"].id, only["b"].id} == {fee.id, refund.id}
    assert only["gap_days"] == 6
    # earlier-dated side is always "a"
    assert only["a"].id == fee.id


def test_cancel_candidates_skip_already_linked_and_each_row_used_once(db):
    account = _account(db)
    a1 = _tx(db, account, date(2026, 5, 1), "-50.00")
    b1 = _tx(db, account, date(2026, 5, 2), "50.00")
    a1.transfer_pair_id = b1.id
    b1.transfer_pair_id = a1.id
    # two negatives compete for one positive: nearest date wins, other unmatched
    near = _tx(db, account, date(2026, 6, 10), "-40.00")
    _tx(db, account, date(2026, 6, 1), "-40.00")
    plus = _tx(db, account, date(2026, 6, 12), "40.00", kind=TransactionKind.refund)
    db.commit()

    candidates = cancel_out_candidates(db, date(2026, 1, 1), date(2026, 12, 31))

    assert len(candidates) == 1
    assert {candidates[0]["a"].id, candidates[0]["b"].id} == {near.id, plus.id}


def test_linked_cancel_pairs_dedupes_and_reaches_out_of_range_partner(db):
    account = _account(db)
    inside = _tx(db, account, date(2026, 7, 2), "-90.00")
    outside = _tx(db, account, date(2026, 5, 20), "90.00")
    inside.transfer_pair_id = outside.id
    outside.transfer_pair_id = inside.id
    db.commit()

    pairs = linked_cancel_pairs(db, date(2026, 7, 1), date(2026, 7, 31))

    assert len(pairs) == 1
    assert {pairs[0]["a"].id, pairs[0]["b"].id} == {inside.id, outside.id}


def test_cancel_candidates_skip_deliberate_money_moves(db):
    account = _account(db)
    _tx(db, account, date(2026, 6, 20), "-100.00", kind=TransactionKind.investment)
    _tx(db, account, date(2026, 6, 20), "100.00", kind=TransactionKind.investment)
    _tx(db, account, date(2026, 6, 21), "-200.00", kind=TransactionKind.cc_payment)
    _tx(db, account, date(2026, 6, 21), "200.00", kind=TransactionKind.cc_payment)
    db.commit()

    assert cancel_out_candidates(db, date(2026, 1, 1), date(2026, 12, 31)) == []
