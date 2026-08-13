"""A rule can stamp matched rows out of every total.

The motivating case is a donor-advised fund: the giving is already
counted as a donation on the way in, from the account that funded it, so
the fund's own rows — the mirrored contribution and the grants out — must
stay visible in the ledger without being counted a second time.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from app import analytics
from app.models import (
    Account,
    AccountCategory,
    AccountType,
    Category,
    CategoryKind,
    Rule,
    SignConvention,
    Transaction,
    TransactionKind,
)
from app.rules import evaluate, load_active_rules, reapply_to_unreviewed


def _account(db, name: str) -> Account:
    account = Account(
        name=name,
        account_category=AccountCategory.investment,
        type=AccountType.brokerage,
        sign_convention=SignConvention.outflow_negative,
    )
    db.add(account)
    db.flush()
    return account


def _transaction(db, account: Account, description: str, amount: str, **kwargs) -> Transaction:
    tx = Transaction(
        account_id=account.id,
        date=date(2026, 3, 23),
        description_raw=description,
        amount=Decimal(amount),
        kind=kwargs.pop("kind", TransactionKind.uncategorized),
        is_user_categorized=kwargs.pop("is_user_categorized", False),
        is_excluded_from_totals=kwargs.pop("is_excluded_from_totals", False),
        **kwargs,
    )
    db.add(tx)
    db.flush()
    return tx


def _exclude_account_rule(db, account: Account) -> Rule:
    rule = Rule(
        name=f"{account.name}: informational only",
        match_account_id=account.id,
        set_is_excluded_from_totals=True,
    )
    db.add(rule)
    db.flush()
    return rule


def test_evaluate_returns_the_exclusion_for_a_matching_account(db):
    fund = _account(db, "Giving Fund")
    other = _account(db, "Taxable")
    _exclude_account_rule(db, fund)
    rules = load_active_rules(db)

    matched = evaluate(rules, account_id=fund.id, description="GRANT", amount=Decimal("-500"))
    assert matched.is_excluded_from_totals is True

    unmatched = evaluate(rules, account_id=other.id, description="GRANT", amount=Decimal("-500"))
    assert unmatched.is_excluded_from_totals is None


def test_rules_without_the_action_leave_the_flag_alone(db):
    account = _account(db, "Taxable")
    db.add(Rule(name="tag it", match_account_id=account.id, set_merchant="Somebody"))
    db.flush()

    ev = evaluate(
        load_active_rules(db), account_id=account.id, description="X", amount=Decimal("-1")
    )
    assert ev.is_excluded_from_totals is None


def test_reapply_excludes_existing_unreviewed_rows(db):
    fund = _account(db, "Giving Fund")
    grant = _transaction(db, fund, "GRANT TO A CHARITY", "-500")
    _exclude_account_rule(db, fund)

    rows_changed, _ = reapply_to_unreviewed(db)

    assert rows_changed == 1
    assert grant.is_excluded_from_totals is True


def test_sankey_counts_the_contribution_and_ignores_the_fund(db):
    """The donation lands on the contribution date, once."""
    taxable = _account(db, "Taxable")
    fund = _account(db, "Giving Fund")
    giving = Category(name="Giving", kind=CategoryKind.expense)
    db.add(giving)
    db.flush()

    # The contribution out of the taxable account: this is the donation.
    _transaction(
        db,
        taxable,
        "Contribution to the fund",
        "-1000",
        kind=TransactionKind.donation,
        category_id=giving.id,
        is_user_categorized=True,
    )
    # The fund's own mirrored inflow and a later grant out.
    _transaction(db, fund, "Contribution received", "1000", kind=TransactionKind.income)
    _transaction(db, fund, "Grant to a charity", "-400", kind=TransactionKind.donation)
    _exclude_account_rule(db, fund)
    reapply_to_unreviewed(db)
    db.commit()

    result = analytics.cashflow_sankey(db, date(2026, 1, 1), date(2026, 12, 31), "2026")
    links = {link["label"]: link["value"] for link in result["links"]}

    # 1000 counted once, on the contribution date — not 1400 (contribution
    # plus grant), and not 600 (contribution netted against the fund's
    # mirrored inflow).
    assert links["Donations"] == 1000.0
