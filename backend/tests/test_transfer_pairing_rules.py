"""Pairing as a rule action.

Money moved between your own accounts leaves one and arrives in the
other. Counting both sides is double-counting, so a linked pair is held
at kind=transfer and the Sankey ignores it. Linking every such pair by
hand does not scale past a few, hence a rule that names the counterpart
account and a pass that runs it.
"""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from app.models import (
    Account,
    AccountCategory,
    AccountType,
    Rule,
    SignConvention,
    Transaction,
    TransactionKind,
)
from app.rules import link_transfers


def _account(db, name: str) -> Account:
    account = Account(
        name=name,
        account_category=AccountCategory.bank,
        type=AccountType.checking,
        sign_convention=SignConvention.outflow_negative,
    )
    db.add(account)
    db.flush()
    return account


def _tx(db, account: Account, day: date, amount: str, description: str = "TRANSFER") -> Transaction:
    tx = Transaction(
        account_id=account.id,
        date=day,
        description_raw=description,
        amount=Decimal(amount),
        kind=TransactionKind.uncategorized,
        is_user_categorized=False,
        is_excluded_from_totals=False,
    )
    db.add(tx)
    db.flush()
    return tx


def _pairing_rule(db, source: Account, target: Account, days: int | None = 3, **kwargs) -> Rule:
    rule = Rule(
        name=f"{source.name} to {target.name}",
        priority=kwargs.pop("priority", 10),
        match_account_id=source.id,
        pair_with_account_id=target.id,
        pair_within_days=days,
        **kwargs,
    )
    db.add(rule)
    db.flush()
    return rule


def test_links_the_two_sides_and_holds_both_at_transfer(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    out = _tx(db, checking, date(2026, 3, 2), "-2000")
    arrival = _tx(db, brokerage, date(2026, 3, 4), "2000")
    _pairing_rule(db, checking, brokerage)

    pairs, rules_fired = link_transfers(db)

    assert (pairs, rules_fired) == (1, 1)
    assert out.transfer_pair_id == arrival.id
    assert arrival.transfer_pair_id == out.id
    assert out.kind is TransactionKind.transfer
    assert arrival.kind is TransactionKind.transfer
    # Unlinking has to be able to put both back, the same as a hand link.
    assert out.kind_before_pair is TransactionKind.uncategorized
    assert arrival.kind_before_pair is TransactionKind.uncategorized


def test_ignores_a_counterpart_outside_the_window(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    out = _tx(db, checking, date(2026, 3, 2), "-2000")
    _tx(db, brokerage, date(2026, 3, 20), "2000")
    _pairing_rule(db, checking, brokerage, days=3)

    assert link_transfers(db) == (0, 0)
    assert out.transfer_pair_id is None


def test_requires_the_opposite_sign(db):
    """Same size, same direction is a different transaction that merely
    happens to cost the same."""
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    out = _tx(db, checking, date(2026, 3, 2), "-2000")
    _tx(db, brokerage, date(2026, 3, 3), "-2000")
    _pairing_rule(db, checking, brokerage)

    assert link_transfers(db) == (0, 0)
    assert out.transfer_pair_id is None


def test_each_row_is_claimed_once_and_the_nearest_date_wins(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    first = _tx(db, checking, date(2026, 3, 2), "-2000")
    second = _tx(db, checking, date(2026, 3, 9), "-2000")
    near = _tx(db, brokerage, date(2026, 3, 3), "2000")
    later = _tx(db, brokerage, date(2026, 3, 10), "2000")
    _pairing_rule(db, checking, brokerage)

    pairs, _ = link_transfers(db)

    assert pairs == 2
    assert first.transfer_pair_id == near.id
    assert second.transfer_pair_id == later.id


def test_leaves_already_linked_rows_alone(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    out = _tx(db, checking, date(2026, 3, 2), "-2000")
    arrival = _tx(db, brokerage, date(2026, 3, 3), "2000")
    _pairing_rule(db, checking, brokerage)
    assert link_transfers(db)[0] == 1

    # A second pass must be a no-op rather than re-pairing anything.
    assert link_transfers(db) == (0, 0)
    assert out.transfer_pair_id == arrival.id


def test_honors_the_description_pattern(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    transfer = _tx(db, checking, date(2026, 3, 2), "-2000", "TRANSFER TO BROKERAGE")
    groceries = _tx(db, checking, date(2026, 3, 2), "-2000", "SUPERMARKET")
    _tx(db, brokerage, date(2026, 3, 3), "2000")
    _tx(db, brokerage, date(2026, 3, 3), "2000")
    _pairing_rule(db, checking, brokerage, match_description_pattern="TRANSFER TO")

    pairs, _ = link_transfers(db)

    assert pairs == 1
    assert transfer.transfer_pair_id is not None
    assert groceries.transfer_pair_id is None


def test_rules_without_a_pairing_action_do_nothing_here(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _tx(db, checking, date(2026, 3, 2), "-2000")
    _tx(db, brokerage, date(2026, 3, 3), "2000")
    db.add(Rule(name="categorize only", match_account_id=checking.id, set_merchant="Somebody"))
    db.flush()

    assert link_transfers(db) == (0, 0)


def test_a_rule_pointing_at_its_own_account_needs_a_pattern(db):
    """Without one, two unrelated same-account rows could cancel each
    other out purely because they happen to offset."""
    checking = _account(db, "Checking")
    out = _tx(db, checking, date(2026, 3, 2), "-2000")
    _tx(db, checking, date(2026, 3, 3), "2000")
    _pairing_rule(db, checking, checking)

    assert link_transfers(db) == (0, 0)
    assert out.transfer_pair_id is None


def test_pairs_within_one_account_when_a_pattern_says_which_rows(db):
    """A dividend and its reinvestment, or a cash sweep out and back,
    cancel inside a single brokerage account."""
    brokerage = _account(db, "Brokerage")
    dividend = _tx(db, brokerage, date(2026, 7, 10), "193.82", "FUND - DIVIDEND RECEIVED")
    reinvest = _tx(db, brokerage, date(2026, 7, 10), "-193.82", "FUND - REINVESTMENT")
    unrelated = _tx(db, brokerage, date(2026, 7, 11), "-193.82", "SOMETHING ELSE")
    _pairing_rule(db, brokerage, brokerage, match_description_pattern="DIVIDEND RECEIVED")

    pairs, _ = link_transfers(db)

    assert pairs == 1
    assert dividend.transfer_pair_id == reinvest.id
    assert unrelated.transfer_pair_id is None


def test_counts_fire_against_the_rule(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    for week in range(3):
        day = date(2026, 3, 2) + timedelta(days=7 * week)
        _tx(db, checking, day, "-2000")
        _tx(db, brokerage, day + timedelta(days=1), "2000")
    rule = _pairing_rule(db, checking, brokerage)

    pairs, rules_fired = link_transfers(db)

    assert (pairs, rules_fired) == (3, 1)
    assert rule.apply_count == 3
    assert rule.last_applied_at is not None
