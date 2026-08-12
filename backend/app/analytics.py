"""Aggregations for charts.

All money is normalized to outflow-negative before aggregation. Filters
on `kind` are the standard way to include or exclude transfers, taxes,
donations, etc.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal

from sqlalchemy import and_, case, func, or_, select
from sqlalchemy.orm import Session

from . import models
from .budgets import budget_date_column
from .models import TransactionKind

EXPENSE_KINDS = {TransactionKind.expense}
INCOME_KINDS = {TransactionKind.income}
NON_EXPENSE_KINDS = {
    TransactionKind.transfer,
    TransactionKind.investment,
    TransactionKind.cc_payment,
    TransactionKind.refund,
    TransactionKind.reimbursement,
    TransactionKind.other_non_expense,
}
DONATION_KINDS = {TransactionKind.donation}
TAX_KINDS = {TransactionKind.tax}


@dataclass
class _SankeyTransactionRollup:
    income_leaves: dict[str, Decimal]
    expenses: dict[str, dict[str, Decimal]]
    donations_total: Decimal
    taxes_total: Decimal


@dataclass
class _SankeySnapshotRollup:
    start_snap: models.NetWorthSnapshot | None
    end_snap: models.NetWorthSnapshot | None
    delta_by_bucket: dict[str, Decimal]
    positive_delta_by_growth_source: dict[str, Decimal]
    total_account_delta: Decimal


@dataclass
class _SankeyFlowTotals:
    income: Decimal
    expenses: Decimal
    growth: Decimal
    inflows: Decimal


class _SankeyGraph:
    def __init__(self) -> None:
        self.nodes: list[str] = []
        self.node_idx: dict[str, int] = {}
        self.links: list[dict] = []

    def node(self, name: str) -> int:
        if name not in self.node_idx:
            self.node_idx[name] = len(self.nodes)
            self.nodes.append(name)
        return self.node_idx[name]

    def link(self, source: int, target: int, value: Decimal, label: str) -> None:
        self.links.append(
            {"source": source, "target": target, "value": float(value), "label": label}
        )


def _effective_amount(amount: Decimal, personal_share: Decimal | None) -> Decimal:
    if personal_share is None:
        return amount
    return amount * Decimal(personal_share)


def monthly_breakdown(db: Session, start: date, end: date) -> list[dict]:
    """One row per month. `by_expense_category` only contains expense-kind
    rows so the stacked-bar chart doesn't mix salary into the expense
    breakdown. Income/donation/tax are separate fields.

    Buckets by `budget_date` where the user set one, matching the budget
    report — these two are the month-attribution views, and they would
    contradict each other otherwise."""
    counted_on = budget_date_column()
    stmt = (
        select(
            counted_on,
            models.Transaction.kind,
            models.Transaction.amount,
            models.TransactionSplit.personal_share,
            models.Category.name.label("category_name"),
        )
        .join(models.Category, models.Category.id == models.Transaction.category_id, isouter=True)
        .join(
            models.TransactionSplit,
            models.TransactionSplit.transaction_id == models.Transaction.id,
            isouter=True,
        )
        .where(
            counted_on >= start,
            counted_on <= end,
            models.Transaction.is_excluded_from_totals.is_(False),
        )
    )
    by_month: dict[str, dict] = defaultdict(
        lambda: {
            "by_kind": defaultdict(lambda: Decimal("0")),
            "by_expense_category": defaultdict(lambda: Decimal("0")),
            "by_income_category": defaultdict(lambda: Decimal("0")),
            "expenses_total": Decimal("0"),
            "income_total": Decimal("0"),
            "donations_total": Decimal("0"),
            "taxes_total": Decimal("0"),
        }
    )
    for d, kind, amount, personal_share, cat_name in db.execute(stmt):
        amount = _effective_amount(amount, personal_share)
        m = f"{d.year:04d}-{d.month:02d}"
        bucket = by_month[m]
        bucket["by_kind"][kind.value] += amount
        cat_label = cat_name or "Uncategorized"
        if kind in EXPENSE_KINDS:
            bucket["by_expense_category"][cat_label] += -amount  # outflows -> positive
            bucket["expenses_total"] += -amount
        elif kind == TransactionKind.uncategorized and amount < 0:
            bucket["by_expense_category"]["Uncategorized"] += -amount
            bucket["expenses_total"] += -amount
        elif kind in INCOME_KINDS:
            bucket["by_income_category"][cat_label] += amount
            bucket["income_total"] += amount
        elif kind in DONATION_KINDS:
            bucket["donations_total"] += -amount
        elif kind in TAX_KINDS:
            bucket["taxes_total"] += -amount

    out: list[dict] = []
    for month in sorted(by_month.keys()):
        b = by_month[month]
        net = b["income_total"] - b["expenses_total"] - b["donations_total"] - b["taxes_total"]
        out.append(
            {
                "month": month,
                "by_kind": dict(b["by_kind"]),
                "by_expense_category": dict(b["by_expense_category"]),
                "by_income_category": dict(b["by_income_category"]),
                "expenses_total": b["expenses_total"],
                "income_total": b["income_total"],
                "donations_total": b["donations_total"],
                "taxes_total": b["taxes_total"],
                "net": net,
            }
        )
    return out


def split_group_summary(db: Session, start: date, end: date) -> list[dict]:
    stmt = (
        select(
            models.TransactionSplit.group_name,
            models.TransactionSplit.personal_share,
            models.Transaction.amount,
        )
        .join(models.Transaction, models.Transaction.id == models.TransactionSplit.transaction_id)
        .where(
            models.Transaction.date >= start,
            models.Transaction.date <= end,
            models.Transaction.is_excluded_from_totals.is_(False),
        )
    )
    groups: dict[str, dict] = defaultdict(
        lambda: {
            "shared_outflows": Decimal("0"),
            "personal_outflows": Decimal("0"),
            "expected_reimbursement": Decimal("0"),
            "received_reimbursement": Decimal("0"),
            "transaction_count": 0,
        }
    )
    for group_name, personal_share, amount in db.execute(stmt):
        g = groups[group_name]
        g["transaction_count"] += 1
        share = Decimal(personal_share)
        if amount < 0:
            full_outflow = -amount
            personal = full_outflow * share
            g["shared_outflows"] += full_outflow
            g["personal_outflows"] += personal
            g["expected_reimbursement"] += full_outflow - personal
        elif amount > 0:
            # Split inflows are treated as reimbursements. In analytics they
            # should usually have personal_share=0, but reconciliation uses
            # the raw received amount.
            g["received_reimbursement"] += amount

    out: list[dict] = []
    for group_name, g in sorted(groups.items()):
        remaining = g["expected_reimbursement"] - g["received_reimbursement"]
        out.append(
            {
                "group_name": group_name,
                "shared_outflows": g["shared_outflows"],
                "personal_outflows": g["personal_outflows"],
                "expected_reimbursement": g["expected_reimbursement"],
                "received_reimbursement": g["received_reimbursement"],
                "remaining_owed": remaining,
                "transaction_count": g["transaction_count"],
            }
        )
    return out


def _category_group_map(db: Session) -> dict[int, tuple[str, str]]:
    """Map category_id -> (leaf_name, group_name). The group is the
    parent if one exists, else the category is its own group."""
    out: dict[int, tuple[str, str]] = {}
    cats = list(db.scalars(select(models.Category)).all())
    by_id = {c.id: c for c in cats}
    for c in cats:
        if c.parent_id and c.parent_id in by_id:
            out[c.id] = (c.name, by_id[c.parent_id].name)
        else:
            out[c.id] = (c.name, c.name)
    return out


def _nearest_snapshot(
    db: Session, target: date, window_before_days: int = 60, window_after_days: int = 60
) -> models.NetWorthSnapshot | None:
    """The snapshot closest to `target` within a ±window. Ties go to the
    earlier date (matters when a year boundary sits between two snapshots
    equidistant from it)."""
    earliest = target - timedelta(days=window_before_days)
    latest = target + timedelta(days=window_after_days)
    rows = list(
        db.scalars(
            select(models.NetWorthSnapshot)
            .where(models.NetWorthSnapshot.snapshot_date >= earliest)
            .where(models.NetWorthSnapshot.snapshot_date <= latest)
        ).all()
    )
    if not rows:
        return None
    return min(
        rows,
        key=lambda s: (abs((s.snapshot_date - target).days), s.snapshot_date),
    )


def _bracketing_snapshots_for_period(
    db: Session, start: date, end: date
) -> tuple[models.NetWorthSnapshot | None, models.NetWorthSnapshot | None]:
    """Return (start_snapshot, end_snapshot) bracketing a selected period.

    A full-year analysis wants the balance change from Jan 1 to Jan 1 of
    next year. A custom period uses the same idea with the user's chosen
    boundaries. With monthly snapshots, the closest available snapshot is
    usually near rather than exactly on the boundary.

    Falls back to earliest/latest available if no snapshot sits within
    the ±60-day window of a boundary (e.g., very early years).
    """
    end_anchor = end + timedelta(days=1)

    start_snap = _nearest_snapshot(db, start)
    if start_snap is None:
        start_snap = db.scalar(
            select(models.NetWorthSnapshot).order_by(models.NetWorthSnapshot.snapshot_date.asc())
        )
    end_snap = _nearest_snapshot(db, end_anchor)
    if end_snap is None:
        # Period is partial / current — fall back to the latest snapshot at all.
        end_snap = db.scalar(
            select(models.NetWorthSnapshot).order_by(models.NetWorthSnapshot.snapshot_date.desc())
        )
    return start_snap, end_snap


def _bracketing_snapshots(
    db: Session, year: int
) -> tuple[models.NetWorthSnapshot | None, models.NetWorthSnapshot | None]:
    return _bracketing_snapshots_for_period(db, date(year, 1, 1), date(year, 12, 31))


def _growth_bucket_for_account(acc: models.Account) -> str:
    """Map an account to one of the user's "growth source" buckets.

    The user's mental model: CD Interest, Bond Payments, Stock Growth,
    Bank Interest. Everything else falls into "Other growth".
    """
    name = (acc.name or "").lower()
    if acc.type == models.AccountType.cd:
        return "CD Interest"
    if "bond" in name:
        return "Bond Payments"
    if acc.account_category == models.AccountCategory.investment:
        return "Stock Growth"
    if acc.account_category == models.AccountCategory.tax_advantaged:
        return "Stock Growth"  # 401k/IRA/HSA mostly track the market
    if acc.account_category == models.AccountCategory.property:
        return "Home Appreciation"
    if acc.type in (models.AccountType.checking, models.AccountType.savings):
        return "Bank Interest"
    return "Other growth"


def _delta_bucket_for_account(acc: models.Account) -> str:
    """Where on the right-hand side of the Sankey does this account's
    delta land? Mirrors the user's spreadsheet groupings."""
    name = (acc.name or "").lower()
    if "bond" in name:
        return "Bond Account"
    if acc.account_category in (models.AccountCategory.investment, models.AccountCategory.tax_advantaged):
        return "Stock Account"
    if acc.account_category == models.AccountCategory.bank:
        return "CDs + Bank Accounts"
    if acc.account_category == models.AccountCategory.property:
        return "Real Estate"
    return "Other Accounts"


def _collect_sankey_transactions(
    db: Session,
    start: date,
    end: date,
    group_map: dict[int, tuple[str, str]],
) -> _SankeyTransactionRollup:
    tx_stmt = select(
        models.Transaction.kind,
        models.Transaction.amount,
        models.TransactionSplit.personal_share,
        models.Transaction.merchant,
        models.Transaction.category_id,
        models.Transaction.account_id,
    ).join(
        models.TransactionSplit,
        models.TransactionSplit.transaction_id == models.Transaction.id,
        isouter=True,
    ).where(
        models.Transaction.date >= start,
        models.Transaction.date <= end,
        models.Transaction.is_excluded_from_totals.is_(False),
    )

    income_leaves: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    expenses: dict[str, dict[str, Decimal]] = defaultdict(lambda: defaultdict(lambda: Decimal("0")))
    donations_total = Decimal("0")
    taxes_total = Decimal("0")

    for kind, amount, personal_share, merchant, cat_id, _acc_id in db.execute(tx_stmt):
        amount = _effective_amount(amount, personal_share)
        leaf, group = group_map.get(cat_id, (None, None)) if cat_id else (None, None)
        leaf = leaf or merchant or "(uncategorized)"
        group = group or "(Uncategorized)"
        if kind == TransactionKind.income:
            income_leaves[leaf] += amount
        elif kind == TransactionKind.expense:
            expenses[group][leaf] += -amount
        elif kind == TransactionKind.donation:
            donations_total += -amount
        elif kind == TransactionKind.tax:
            taxes_total += -amount
        # transfers, cc_payments, investments, refunds: do nothing — they
        # net to zero across accounts and aren't a flow in this model.

    return _SankeyTransactionRollup(
        income_leaves=income_leaves,
        expenses=expenses,
        donations_total=donations_total,
        taxes_total=taxes_total,
    )


def _snapshot_balances(snapshot: models.NetWorthSnapshot | None) -> dict[int, Decimal]:
    if snapshot is None:
        return {}
    return {
        balance.account_id: Decimal(balance.balance)
        for balance in snapshot.balances
        if balance.balance is not None
    }


def _collect_sankey_snapshot_deltas(
    db: Session,
    start: date,
    end: date,
) -> _SankeySnapshotRollup:
    start_snap, end_snap = _bracketing_snapshots_for_period(db, start, end)
    accounts = {a.id: a for a in db.scalars(select(models.Account)).all()}
    start_balances = _snapshot_balances(start_snap)
    end_balances = _snapshot_balances(end_snap)

    delta_by_bucket: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    positive_delta_by_growth_source: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    total_account_delta = Decimal("0")
    for acc_id, acc in accounts.items():
        if acc.account_category in (models.AccountCategory.credit, models.AccountCategory.liability):
            continue
        start_bal = start_balances.get(acc_id, Decimal("0"))
        end_bal = end_balances.get(acc_id, Decimal("0"))
        delta = end_bal - start_bal
        delta_by_bucket[_delta_bucket_for_account(acc)] += delta
        total_account_delta += delta
        if delta > 0:
            positive_delta_by_growth_source[_growth_bucket_for_account(acc)] += delta

    return _SankeySnapshotRollup(
        start_snap=start_snap,
        end_snap=end_snap,
        delta_by_bucket=delta_by_bucket,
        positive_delta_by_growth_source=positive_delta_by_growth_source,
        total_account_delta=total_account_delta,
    )


def _sankey_flow_totals(
    transactions: _SankeyTransactionRollup,
    total_account_delta: Decimal,
) -> _SankeyFlowTotals:
    expense_total = Decimal("0")
    for leaves in transactions.expenses.values():
        gt = sum(leaves.values(), Decimal("0"))
        if gt > 0:
            expense_total += gt
    income_total = sum((v for v in transactions.income_leaves.values() if v > 0), Decimal("0"))

    # Bookkeeping identity, clamped at 0 (a negative result means the
    # transaction imports captured *more* "income" than the NLV grew by,
    # usually because some money flowed out via untracked transfers).
    net_cashflow_realized = (
        income_total
        - expense_total
        - transactions.donations_total
        - transactions.taxes_total
    )
    growth_total = max(Decimal("0"), total_account_delta - net_cashflow_realized)
    return _SankeyFlowTotals(
        income=income_total,
        expenses=expense_total,
        growth=growth_total,
        inflows=income_total + growth_total,
    )


def _add_income_links(
    graph: _SankeyGraph,
    hub: int,
    income_leaves: dict[str, Decimal],
    income_total: Decimal,
) -> None:
    if income_total <= 0:
        return
    income_group_idx = graph.node("Income")
    for leaf, val in sorted(income_leaves.items(), key=lambda kv: -kv[1]):
        if val <= 0:
            continue
        graph.link(graph.node(leaf), income_group_idx, val, leaf)
    graph.link(income_group_idx, hub, income_total, "Income")


def _add_expense_links(
    graph: _SankeyGraph,
    hub: int,
    expenses: dict[str, dict[str, Decimal]],
    expense_total: Decimal,
) -> None:
    if expense_total <= 0:
        return
    exp_group = graph.node("Expenses")
    graph.link(hub, exp_group, expense_total, "Expenses")
    sorted_groups = sorted(expenses.items(), key=lambda kv: -sum(kv[1].values(), Decimal("0")))
    for group, leaves in sorted_groups:
        gt = sum(leaves.values(), Decimal("0"))
        if gt <= 0:
            continue
        _add_expense_group_links(graph, exp_group, group, leaves, gt)


def _add_expense_group_links(
    graph: _SankeyGraph,
    exp_group: int,
    group: str,
    leaves: dict[str, Decimal],
    group_total: Decimal,
) -> None:
    if len(leaves) >= 2 and group not in leaves:
        group_node = graph.node(group)
        graph.link(exp_group, group_node, group_total, group)
        _add_grouped_expense_leaves(graph, group_node, leaves)
    else:
        _add_collapsed_expense_leaves(graph, exp_group, leaves)


def _add_grouped_expense_leaves(
    graph: _SankeyGraph,
    group_node: int,
    leaves: dict[str, Decimal],
) -> None:
    for leaf, val in sorted(leaves.items(), key=lambda kv: -kv[1]):
        if val <= 0:
            continue
        graph.link(group_node, graph.node(leaf), val, leaf)


def _add_collapsed_expense_leaves(
    graph: _SankeyGraph,
    exp_group: int,
    leaves: dict[str, Decimal],
) -> None:
    for leaf, val in leaves.items():
        if val <= 0:
            continue
        graph.link(exp_group, graph.node(leaf), val, leaf)


def _add_growth_links(
    graph: _SankeyGraph,
    hub: int,
    growth_total: Decimal,
    positive_delta_by_growth_source: dict[str, Decimal],
) -> None:
    if growth_total <= 0:
        return
    growth_group_idx = graph.node("Growth")
    total_pos_share = sum(positive_delta_by_growth_source.values(), Decimal("0"))
    if total_pos_share > 0:
        _add_apportioned_growth_links(
            graph,
            growth_group_idx,
            growth_total,
            positive_delta_by_growth_source,
            total_pos_share,
        )
    else:
        graph.link(
            graph.node("Unallocated growth"),
            growth_group_idx,
            growth_total,
            "Unallocated growth",
        )
    graph.link(growth_group_idx, hub, growth_total, "Growth")


def _add_apportioned_growth_links(
    graph: _SankeyGraph,
    growth_group_idx: int,
    growth_total: Decimal,
    positive_delta_by_growth_source: dict[str, Decimal],
    total_pos_share: Decimal,
) -> None:
    for src, share_basis in sorted(positive_delta_by_growth_source.items(), key=lambda kv: -kv[1]):
        if share_basis <= 0:
            continue
        val = (share_basis / total_pos_share) * growth_total
        if val <= 0:
            continue
        graph.link(graph.node(src), growth_group_idx, val, src)


def _implied_account_delta(
    totals: _SankeyFlowTotals,
    tx: _SankeyTransactionRollup,
    snapshot: _SankeySnapshotRollup,
) -> Decimal:
    if snapshot.total_account_delta > 0 and totals.growth > 0:
        return snapshot.total_account_delta
    return totals.inflows - totals.expenses - tx.donations_total - tx.taxes_total


def _add_account_delta_links(
    graph: _SankeyGraph,
    hub: int,
    implied_to_accounts: Decimal,
    delta_by_bucket: dict[str, Decimal],
) -> None:
    if implied_to_accounts > 0:
        accounts_node = graph.node("Account deltas (pos)")
        graph.link(hub, accounts_node, implied_to_accounts, "Account deltas")
        _add_account_delta_bucket_links(graph, accounts_node, implied_to_accounts, delta_by_bucket)
    elif implied_to_accounts < 0:
        # Outflows exceeded inflows. Show a "Drawn from savings" inflow.
        graph.link(graph.node("Drawn from savings"), hub, -implied_to_accounts, "Drawn from savings")


def _add_account_delta_bucket_links(
    graph: _SankeyGraph,
    accounts_node: int,
    implied_to_accounts: Decimal,
    delta_by_bucket: dict[str, Decimal],
) -> None:
    positive_buckets = {k: v for k, v in delta_by_bucket.items() if v > 0}
    bucket_sum = sum(positive_buckets.values(), Decimal("0"))
    if bucket_sum > 0:
        for bucket, val in sorted(positive_buckets.items(), key=lambda kv: -kv[1]):
            share = (val / bucket_sum) * implied_to_accounts
            graph.link(accounts_node, graph.node(bucket), share, bucket)
    else:
        graph.link(accounts_node, graph.node("(unknown)"), implied_to_accounts, "(unknown)")


def _sankey_notes(
    start_snap: models.NetWorthSnapshot | None,
    end_snap: models.NetWorthSnapshot | None,
) -> list[str]:
    return [
        "Five-level Sankey. Source → Group (Income/Growth) → Inflows hub → Outflow split → Leaf.",
        "Growth uses the bookkeeping identity ΔNLV = Income − Expenses − Donations − Taxes + Growth, then splits by each NLV account-type's positive-delta share (CD Interest / Stock Growth / Bank Interest / Bond Payments).",
        "Account deltas (pos) is sized to balance the diagram, then split into account-category buckets by their positive-delta share.",
        "Snapshot bracketing picks snapshots nearest to the selected period boundaries (within ±60 days).",
        "Transfers and credit-card payments are intentionally excluded from cashflow (they net to zero between accounts).",
        f"Snapshot window used: {start_snap.snapshot_date if start_snap else '—'} → {end_snap.snapshot_date if end_snap else '—'}.",
    ]


def sankey_for_period(db: Session, start: date, end: date, label: str) -> dict:
    """Five-level Sankey mixing transaction cashflow with snapshot balance
    deltas, modelled on the user's NLV-tracking sheet.

    Sources  ->  Group  ->  Inflows hub  ->  Outflow split  ->  Leaf

    Inflow sources:
      - income-kind transactions group under "Income" (leaves: Salary,
        RSU/Stock, Tutoring, Tax Refund, ...)
      - per-account unrealized growth groups under "Growth"
        (leaves: Bank Interest, CD Interest, Stock Growth, Bond Payments)

    Outflow buckets:
      - Expenses    -> expense leaf categories (via Category.parent_id)
      - Donations   (single bucket)
      - Taxes       (single bucket)
      - Account deltas (positive) -> per-account-category delta
        (CDs + Bank Accounts / Stock Account / Bond Account / Other)

    Bookkeeping identity (which we publish in the notes):
        Income + Growth == Expenses + Donations + Taxes + Σ(account deltas)
    """
    group_map = _category_group_map(db)
    transactions = _collect_sankey_transactions(db, start, end, group_map)
    snapshots = _collect_sankey_snapshot_deltas(db, start, end)
    totals = _sankey_flow_totals(transactions, snapshots.total_account_delta)

    graph = _SankeyGraph()
    hub = graph.node("Inflows")
    _add_income_links(graph, hub, transactions.income_leaves, totals.income)
    _add_growth_links(graph, hub, totals.growth, snapshots.positive_delta_by_growth_source)
    _add_expense_links(graph, hub, transactions.expenses, totals.expenses)
    if transactions.donations_total > 0:
        graph.link(hub, graph.node("Donations"), transactions.donations_total, "Donations")
    if transactions.taxes_total > 0:
        graph.link(hub, graph.node("Taxes"), transactions.taxes_total, "Taxes")
    _add_account_delta_links(
        graph,
        hub,
        _implied_account_delta(totals, transactions, snapshots),
        snapshots.delta_by_bucket,
    )

    return {
        "year": start.year,
        "label": label,
        "nodes": [{"name": n} for n in graph.nodes],
        "links": graph.links,
        "notes": _sankey_notes(snapshots.start_snap, snapshots.end_snap),
    }


def yearly_sankey(db: Session, year: int) -> dict:
    return sankey_for_period(db, date(year, 1, 1), date(year, 12, 31), str(year))


def cashflow_sankey(db: Session, start: date, end: date, label: str) -> dict:
    """Cash-basis Sankey: only money that actually moved.

    Sources -> Cash in -> {Invested, Spending, Donations, Taxes, Cash
    build-up} -> spending category leaves.

    Unlike sankey_for_period (the NLV/wealth lens), this ignores snapshots
    and growth entirely: transfers between own accounts and both legs of
    credit-card payments are excluded, investment-kind outflows count as
    "Invested" (a savings destination, not spending), and the residual
    between cash in and cash out appears as "Cash build-up" (or "From cash
    reserves" on the inflow side when the period ran a deficit).
    """
    group_map = _category_group_map(db)
    stmt = (
        select(models.Transaction)
        .where(
            models.Transaction.date >= start,
            models.Transaction.date <= end,
            models.Transaction.is_excluded_from_totals.is_(False),
        )
    )
    income_by_source: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    spend_by_group: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    invested = donations = taxes = Decimal("0")
    for tx in db.scalars(stmt):
        kind = tx.kind
        if kind in (models.TransactionKind.transfer, models.TransactionKind.cc_payment):
            continue
        amount = tx.amount
        if kind in DONATION_KINDS:
            donations += -amount
        elif kind in TAX_KINDS:
            taxes += -amount
        elif kind == models.TransactionKind.investment:
            if amount < 0:
                invested += -amount
            else:
                income_by_source["Investment income"] += amount
        elif kind in INCOME_KINDS or kind == models.TransactionKind.refund:
            name = "Refunds" if kind == models.TransactionKind.refund else None
            if name is None and tx.category_id and tx.category_id in group_map:
                name = group_map[tx.category_id][0]
            # Distinct from any real category ("Other Income" exists) so the
            # fallback bucket can't masquerade as a category node.
            income_by_source[name or "Uncategorized income"] += amount
        elif amount > 0:
            income_by_source["Uncategorized income"] += amount
        else:
            group = "Not yet categorized"
            if tx.category_id and tx.category_id in group_map:
                group = group_map[tx.category_id][1]
            spend_by_group[group] += -amount

    total_in = sum(income_by_source.values(), Decimal("0"))
    total_spend = sum(spend_by_group.values(), Decimal("0"))
    residual = total_in - total_spend - donations - taxes - invested

    graph = _SankeyGraph()
    hub = graph.node("Cash in")
    for name, value in sorted(income_by_source.items(), key=lambda kv: -kv[1]):
        if value > 0:
            graph.link(graph.node(name), hub, value, name)
    if residual < 0:
        graph.link(graph.node("From cash reserves"), hub, -residual, "From cash reserves")
    if invested > 0:
        graph.link(hub, graph.node("Invested"), invested, "Invested")
    if total_spend > 0:
        spending = graph.node("Spending")
        graph.link(hub, spending, total_spend, "Spending")
        for name, value in sorted(spend_by_group.items(), key=lambda kv: -kv[1]):
            graph.link(spending, graph.node(name), value, name)
    if donations > 0:
        graph.link(hub, graph.node("Donations"), donations, "Donations")
    if taxes > 0:
        graph.link(hub, graph.node("Taxes"), taxes, "Taxes")
    if residual > 0:
        graph.link(hub, graph.node("Cash build-up"), residual, "Cash build-up")

    return {
        "year": start.year,
        "label": label,
        "nodes": [{"name": n} for n in graph.nodes],
        "links": graph.links,
        "notes": [
            "Cash basis: only money that actually moved between your accounts and the outside world.",
            "Transfers between your own accounts and both legs of credit-card payments are excluded.",
            "Investment-kind outflows count as Invested (a savings destination, not spending).",
            "Cash build-up (or From cash reserves) is the residual between cash in and cash out.",
            "Snapshots, market growth, and unrealized gains are intentionally not part of this view — see the wealth lens.",
        ],
    }


def networth_series(db: Session, start: date | None = None, end: date | None = None) -> list[dict]:
    """Per-snapshot totals and breakdowns."""
    stmt = select(models.NetWorthSnapshot).order_by(models.NetWorthSnapshot.snapshot_date.asc())
    if start is not None:
        stmt = stmt.where(models.NetWorthSnapshot.snapshot_date >= start)
    if end is not None:
        stmt = stmt.where(models.NetWorthSnapshot.snapshot_date <= end)
    snaps = db.execute(stmt).scalars().all()
    accounts = {a.id: a for a in db.execute(select(models.Account)).scalars().all()}

    out: list[dict] = []
    for snap in snaps:
        by_category: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
        by_account: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
        taxable = Decimal("0")
        tax_advantaged = Decimal("0")
        total = Decimal("0")
        for bal in snap.balances:
            if bal.balance is None:
                continue
            acc = accounts.get(bal.account_id)
            if acc is None:
                continue
            v = Decimal(bal.balance)
            if acc.account_category in (
                models.AccountCategory.credit,
                models.AccountCategory.liability,
            ):
                v = -abs(v)
            by_category[acc.account_category.value] += v
            by_account[acc.name] += v
            total += v
            if acc.account_category == models.AccountCategory.tax_advantaged:
                tax_advantaged += v
            else:
                taxable += v
        out.append(
            {
                "snapshot_date": snap.snapshot_date,
                "total": total,
                "by_category": {k: v for k, v in by_category.items()},
                "by_account": {k: v for k, v in by_account.items()},
                "taxable": taxable,
                "tax_advantaged": tax_advantaged,
            }
        )
    return out


def recurring_merchants(
    db: Session,
    min_occurrences: int = 3,
    start: date | None = None,
    end: date | None = None,
) -> list[dict]:
    key = func.coalesce(models.Transaction.merchant, models.Transaction.description_normalized).label("k")
    where = [
        key.is_not(None),
        models.Transaction.is_excluded_from_totals.is_(False),
    ]
    if start:
        where.append(models.Transaction.date >= start)
    if end:
        where.append(models.Transaction.date <= end)
    # Spending-side rows vs money movement / income, so the UI can split
    # the table (interest and transfers next to restaurants read wrong).
    spendish = case(
        (
            or_(
                models.Transaction.kind.in_(
                    [
                        models.TransactionKind.expense,
                        models.TransactionKind.donation,
                        models.TransactionKind.tax,
                    ]
                ),
                and_(
                    models.Transaction.kind == models.TransactionKind.uncategorized,
                    models.Transaction.amount < 0,
                ),
            ),
            1,
        ),
        else_=0,
    )
    stmt = (
        select(
            key,
            func.count(models.Transaction.id).label("n"),
            func.avg(models.Transaction.amount).label("avg_amount"),
            func.sum(models.Transaction.amount).label("total_amount"),
            func.max(models.Transaction.date).label("last_seen"),
            func.min(models.Transaction.date).label("first_seen"),
            func.sum(spendish).label("spend_n"),
        )
        .where(*where)
        .group_by(key)
        .having(func.count(models.Transaction.id) >= min_occurrences)
        .order_by(func.count(models.Transaction.id).desc())
    )
    out = []
    for merchant, n, avg_amount, total_amount, last_seen, first_seen, spend_n in db.execute(stmt):
        span_days = (last_seen - first_seen).days if last_seen and first_seen else 0
        cadence = (span_days / (n - 1)) if n > 1 and span_days > 0 else None
        out.append(
            {
                "merchant": merchant,
                "occurrences": int(n),
                "avg_amount": Decimal(str(avg_amount)).quantize(Decimal("0.01")) if avg_amount is not None else Decimal("0"),
                "total_amount": Decimal(str(total_amount)).quantize(Decimal("0.01")) if total_amount is not None else Decimal("0"),
                "last_seen": last_seen,
                "cadence_days_estimate": cadence,
                # majority vote; ties count as spending
                "is_expense": int(spend_n or 0) * 2 >= int(n),
            }
        )
    return out


# ---------------------------------------------------------------------------
# Reconciliation
# ---------------------------------------------------------------------------

def fire_projection(db: Session, max_years: int = 60) -> dict:
    """Year-by-year projection of total NLV under the user's FIRE
    settings. Compounds each account-category's current balance by its
    real growth rate; reports the first year the total reaches the
    withdrawal-rule target.

    Contributions are intentionally NOT modeled — the user only asked
    for growth-rate inputs. (If they want contributions later, this is
    the place to add them.)
    """
    from datetime import date as _date

    settings = db.scalar(select(models.FireSettings))
    if settings is None:
        # No settings yet; create with defaults.
        settings = models.FireSettings()
        db.add(settings)
        db.commit()
        db.refresh(settings)

    latest = db.scalar(
        select(models.NetWorthSnapshot).order_by(models.NetWorthSnapshot.snapshot_date.desc())
    )
    by_category: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    if latest is not None:
        for bal in latest.balances:
            if bal.balance is None:
                continue
            acc = db.get(models.Account, bal.account_id)
            if acc is None:
                continue
            # Credit / liability are debt; they subtract from net worth.
            sign = -1 if acc.account_category in (
                models.AccountCategory.credit,
                models.AccountCategory.liability,
            ) else 1
            by_category[acc.account_category.value] += sign * Decimal(bal.balance)

    rates = {
        "bank": Decimal(settings.growth_bank),
        "investment": Decimal(settings.growth_investment),
        "tax_advantaged": Decimal(settings.growth_tax_advantaged),
        "nonsense": Decimal(settings.growth_nonsense),
        "cash": Decimal(settings.growth_cash),
        "credit": Decimal(settings.growth_credit),
        "liability": Decimal(settings.growth_credit),
        "property": Decimal(settings.growth_property),
    }

    target = (
        Decimal(settings.annual_retirement_spending) / Decimal(settings.withdrawal_rate)
        if settings.withdrawal_rate
        else Decimal("0")
    )

    today_year = _date.today().year
    current = {k: v for k, v in by_category.items()}
    years: list[dict] = []
    retirement_year: int | None = None
    for offset in range(0, max_years + 1):
        year = today_year + offset
        total = sum(current.values(), Decimal("0"))
        years.append(
            {
                "year": year,
                "age": (year - settings.birth_year) if settings.birth_year else None,
                "total": total.quantize(Decimal("0.01")),
                "by_category": {k: v.quantize(Decimal("0.01")) for k, v in current.items()},
                "pct_of_target": float(total / target * 100) if target > 0 else 0.0,
            }
        )
        if retirement_year is None and target > 0 and total >= target:
            retirement_year = year
        # Compound for the next iteration.
        for cat, rate in rates.items():
            if cat in current:
                current[cat] = current[cat] * (Decimal("1") + rate)

    # When the target is never reached, "never" is a dead end — anchor the
    # story to retirement age instead: "at this trajectory you'd have $X at
    # 65". Needs a birth year; clamped into the projected window.
    retirement_age = settings.retirement_age or 65
    retirement_age_year: int | None = None
    total_at_retirement_age: Decimal | None = None
    if settings.birth_year:
        retirement_age_year = settings.birth_year + retirement_age
        clamped = min(max(retirement_age_year, years[0]["year"]), years[-1]["year"])
        total_at_retirement_age = next(
            row["total"] for row in years if row["year"] == clamped
        )

    notes = [
        "Growth rates are real (inflation-adjusted) — no need to subtract inflation separately.",
        f"Target = annual retirement spending / withdrawal rate = "
        f"{settings.annual_retirement_spending} / {Decimal(settings.withdrawal_rate):.1%} = "
        f"{target.quantize(Decimal('0.01'))}.",
        f"Current NLV anchored to snapshot {latest.snapshot_date if latest else '(no snapshot)'}.",
        "Contributions are not modeled — projection assumes you stop adding to accounts today.",
    ]

    return {
        "target_total": target.quantize(Decimal("0.01")),
        "current_total": sum(by_category.values(), Decimal("0")).quantize(Decimal("0.01")),
        "current_by_category": {k: v.quantize(Decimal("0.01")) for k, v in by_category.items()},
        "retirement_year": retirement_year,
        "retirement_age": retirement_age,
        "retirement_age_year": retirement_age_year,
        "total_at_retirement_age": total_at_retirement_age,
        "years": years,
        "notes": notes,
    }


def linked_cancel_pairs(db: Session, start: date, end: date) -> list[dict]:
    """Transactions already linked via transfer_pair_id, as deduped pairs
    with at least one side inside the range; newest first."""
    txs = list(
        db.scalars(
            select(models.Transaction).where(
                models.Transaction.transfer_pair_id.is_not(None),
                models.Transaction.date >= start,
                models.Transaction.date <= end,
            )
        )
    )
    seen: set[int] = set()
    pairs: list[dict] = []
    for tx in txs:
        if tx.id in seen:
            continue
        other = db.get(models.Transaction, tx.transfer_pair_id)
        if other is None:
            continue
        seen.add(tx.id)
        seen.add(other.id)
        a, b = (tx, other) if tx.id < other.id else (other, tx)
        pairs.append({"a": a, "b": b})
    pairs.sort(key=lambda p: max(p["a"].date, p["b"].date), reverse=True)
    return pairs


def cancel_out_candidates(
    db: Session, start: date, end: date, *, window_days: int = 45, limit: int = 100
) -> list[dict]:
    """Suggest unlinked equal-and-opposite pairs (refunds, reversals,
    reimbursements) worth netting out. Each transaction is offered at most
    once, matched to its nearest-dated counterpart within window_days.
    Transfers, card payments, and investment flows are skipped — those are
    deliberate money moves, not accidental offsets."""
    stmt = (
        select(models.Transaction)
        .where(
            models.Transaction.date >= start,
            models.Transaction.date <= end,
            models.Transaction.transfer_pair_id.is_(None),
            models.Transaction.is_excluded_from_totals.is_(False),
            models.Transaction.amount != 0,
            models.Transaction.kind.not_in(
                [
                    models.TransactionKind.transfer,
                    models.TransactionKind.cc_payment,
                    models.TransactionKind.investment,
                ]
            ),
        )
        .order_by(models.Transaction.date)
    )
    rows = list(db.scalars(stmt))
    by_abs: dict[Decimal, list[models.Transaction]] = defaultdict(list)
    for tx in rows:
        by_abs[abs(tx.amount)].append(tx)

    used: set[int] = set()
    out: list[dict] = []
    for group in by_abs.values():
        positives = [t for t in group if t.amount > 0]
        negatives = [t for t in group if t.amount < 0]
        if not positives or not negatives:
            continue
        for p in positives:
            if p.id in used:
                continue
            best: models.Transaction | None = None
            best_gap: int | None = None
            for n in negatives:
                if n.id in used:
                    continue
                gap = abs((n.date - p.date).days)
                if gap > window_days:
                    continue
                if best_gap is None or gap < best_gap:
                    best, best_gap = n, gap
            if best is not None:
                used.add(p.id)
                used.add(best.id)
                a, b = (p, best) if (p.date, p.id) <= (best.date, best.id) else (best, p)
                out.append({"a": a, "b": b, "gap_days": best_gap})
    out.sort(key=lambda c: abs(c["a"].amount), reverse=True)
    return out[:limit]
