"""Pairing rules proposed from the links you made by hand.

Categorization proposals learn from rows you categorized. This is the
same idea one step over: rows you linked are the training signal, and a
candidate is judged by replaying the real matcher over them.
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
from app.rules import generate_pair_proposals, link_transfers


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


def _tx(db, account: Account, day: date, amount: str, description: str) -> Transaction:
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


def _link(db, a: Transaction, b: Transaction) -> None:
    for tx in (a, b):
        tx.kind_before_pair = tx.kind
        tx.kind = TransactionKind.transfer
    a.transfer_pair_id = b.id
    b.transfer_pair_id = a.id
    db.flush()


def _seed_manual_links(db, source, target, *, count: int, gap_days: int = 0, amount: str = "-2000"):
    pairs = []
    for week in range(count):
        day = date(2026, 1, 5) + timedelta(days=7 * week)
        out = _tx(db, source, day, amount, f"FID BKG SVC MONEYLINE {week:04d}")
        into = _tx(
            db, target, day + timedelta(days=gap_days), amount.lstrip("-"), "TRANSFER RECEIVED"
        )
        _link(db, out, into)
        pairs.append((out, into))
    return pairs


def test_no_links_means_no_proposals(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _tx(db, checking, date(2026, 1, 5), "-2000", "FID BKG SVC MONEYLINE")
    _tx(db, brokerage, date(2026, 1, 5), "2000", "TRANSFER RECEIVED")

    assert generate_pair_proposals(db) == []


def test_proposes_a_rule_from_repeated_manual_links(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _seed_manual_links(db, checking, brokerage, count=4)

    [proposal] = generate_pair_proposals(db)

    assert proposal["match_account_id"] == checking.id
    assert proposal["pair_with_account_id"] == brokerage.id
    assert proposal["support"] == 4
    assert proposal["reproduces"] == 4
    assert proposal["conflicts"] == 0
    assert "FID" in proposal["match_description_pattern"]
    assert len(proposal["examples"]) == 4


def test_support_threshold_holds_back_one_offs(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _seed_manual_links(db, checking, brokerage, count=2)

    assert generate_pair_proposals(db, min_support=3) == []
    assert len(generate_pair_proposals(db, min_support=2)) == 1


def test_window_comes_from_the_gaps_actually_observed(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _seed_manual_links(db, checking, brokerage, count=3, gap_days=2)

    [proposal] = generate_pair_proposals(db)

    assert proposal["pair_within_days"] == 2


def test_counts_what_promoting_would_newly_link(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _seed_manual_links(db, checking, brokerage, count=3)
    # Two more of the same shape that were never linked by hand.
    for week in (10, 11):
        day = date(2026, 1, 5) + timedelta(days=7 * week)
        _tx(db, checking, day, "-2000", f"FID BKG SVC MONEYLINE {week:04d}")
        _tx(db, brokerage, day, "2000", "TRANSFER RECEIVED")

    [proposal] = generate_pair_proposals(db)

    assert proposal["would_link"] == 2


def test_a_direction_already_covered_by_a_rule_is_not_proposed_again(db):
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _seed_manual_links(db, checking, brokerage, count=4)
    db.add(
        Rule(
            name="already have this",
            match_account_id=checking.id,
            pair_with_account_id=brokerage.id,
            pair_within_days=1,
        )
    )
    db.flush()

    assert generate_pair_proposals(db) == []


def test_each_pair_is_counted_once_not_once_per_side(db):
    """The link is stored on both rows; a naive walk proposes it twice,
    once per direction."""
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _seed_manual_links(db, checking, brokerage, count=3)

    proposals = generate_pair_proposals(db)

    assert len(proposals) == 1
    assert proposals[0]["support"] == 3


def test_a_nearer_unlinked_row_stealing_the_partner_shows_as_a_conflict(db):
    """The failure that matters, and the reason the backtest replays over
    every row: an unlinked row sits closer than the partner you chose, so
    the rule would pair the wrong two. Identical repeating amounts make
    this the normal case, not an exotic one."""
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    for week in range(3):
        day = date(2026, 1, 5) + timedelta(days=7 * week)
        out = _tx(db, checking, day, "-2000", f"FID BKG SVC MONEYLINE {week:04d}")
        into = _tx(db, brokerage, day + timedelta(days=6), "2000", "TRANSFER RECEIVED")
        _link(db, out, into)
        # Never linked, and nearer than the partner actually chosen.
        _tx(db, brokerage, day + timedelta(days=1), "2000", "SOMETHING ELSE")

    [proposal] = generate_pair_proposals(db)

    assert proposal["pair_within_days"] == 6
    assert proposal["conflicts"] == 3
    assert proposal["reproduces"] == 0


def test_promoting_a_clean_proposal_links_exactly_what_it_predicted(db):
    """End to end: the number shown is the number you get."""
    checking = _account(db, "Checking")
    brokerage = _account(db, "Brokerage")
    _seed_manual_links(db, checking, brokerage, count=3)
    for week in (10, 11):
        day = date(2026, 1, 5) + timedelta(days=7 * week)
        _tx(db, checking, day, "-2000", f"FID BKG SVC MONEYLINE {week:04d}")
        _tx(db, brokerage, day, "2000", "TRANSFER RECEIVED")

    [proposal] = generate_pair_proposals(db)
    predicted = proposal["would_link"]

    db.add(
        Rule(
            name=proposal["name"],
            priority=50,
            match_account_id=proposal["match_account_id"],
            match_description_pattern=proposal["match_description_pattern"],
            pair_with_account_id=proposal["pair_with_account_id"],
            pair_within_days=proposal["pair_within_days"],
        )
    )
    db.flush()

    linked, _ = link_transfers(db)
    assert linked == predicted == 2
