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

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import models
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
    breakdown. Income/donation/tax are separate fields."""
    stmt = (
        select(
            models.Transaction.date,
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
            models.Transaction.date >= start,
            models.Transaction.date <= end,
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
        cat_label = cat_name or "(uncategorized)"
        if kind in EXPENSE_KINDS:
            bucket["by_expense_category"][cat_label] += -amount  # outflows -> positive
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
        liquid = Decimal("0")
        illiquid = Decimal("0")
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
            if acc.is_liquid:
                liquid += v
            else:
                illiquid += v
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
                "liquid": liquid,
                "illiquid": illiquid,
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
    stmt = (
        select(
            key,
            func.count(models.Transaction.id).label("n"),
            func.avg(models.Transaction.amount).label("avg_amount"),
            func.sum(models.Transaction.amount).label("total_amount"),
            func.max(models.Transaction.date).label("last_seen"),
            func.min(models.Transaction.date).label("first_seen"),
        )
        .where(*where)
        .group_by(key)
        .having(func.count(models.Transaction.id) >= min_occurrences)
        .order_by(func.count(models.Transaction.id).desc())
    )
    out = []
    for merchant, n, avg_amount, total_amount, last_seen, first_seen in db.execute(stmt):
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
                "age": None,  # the app doesn't track DOB; UI can compute if needed
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

    notes = [
        "Growth rates are real (inflation-adjusted) — no need to subtract inflation separately.",
        f"Target = annual retirement spending / withdrawal rate = "
        f"{settings.annual_retirement_spending} / {settings.withdrawal_rate} = "
        f"{target.quantize(Decimal('0.01'))}.",
        f"Current NLV anchored to snapshot {latest.snapshot_date if latest else '(no snapshot)'}.",
        "Contributions are not modeled — projection assumes you stop adding to accounts today.",
    ]

    return {
        "target_total": target.quantize(Decimal("0.01")),
        "current_total": sum(by_category.values(), Decimal("0")).quantize(Decimal("0.01")),
        "current_by_category": {k: v.quantize(Decimal("0.01")) for k, v in by_category.items()},
        "retirement_year": retirement_year,
        "years": years,
        "notes": notes,
    }


def reconcile_account_period(
    db: Session,
    account_id: int,
    start: date,
    end: date,
    *,
    year: int | None = None,
    month: int | None = None,
) -> dict:
    """Return imported transaction totals for an arbitrary account period."""
    stmt = select(
        models.Transaction.id,
        models.Transaction.date,
        models.Transaction.description_normalized,
        models.Transaction.amount,
        models.Transaction.kind,
        models.Transaction.category_id,
    ).where(
        models.Transaction.account_id == account_id,
        models.Transaction.date >= start,
        models.Transaction.date <= end,
        models.Transaction.is_excluded_from_totals.is_(False),
    )
    rows = list(db.execute(stmt))
    by_kind: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    total = Decimal("0")
    inflows = Decimal("0")
    outflows = Decimal("0")
    for _id, _d, _desc, amt, kind, _cat in rows:
        by_kind[kind.value] += amt
        total += amt
        if amt >= 0:
            inflows += amt
        else:
            outflows += amt
    recon = None
    if year is not None and month is not None:
        recon = db.scalar(
            select(models.MonthlyReconciliation).where(
                models.MonthlyReconciliation.account_id == account_id,
                models.MonthlyReconciliation.year == year,
                models.MonthlyReconciliation.month == month,
            )
        )
    statement_total = recon.statement_total if recon else None
    delta = (Decimal(total) - Decimal(statement_total)) if statement_total is not None else None
    return {
        "account_id": account_id,
        "year": year,
        "month": month,
        "start": start,
        "end": end,
        "transaction_count": len(rows),
        "imported_total": total,
        "imported_inflows": inflows,
        "imported_outflows": outflows,
        "by_kind": dict(by_kind),
        "statement_total": statement_total,
        "statement_notes": recon.notes if recon else None,
        "delta": delta,
    }


def reconcile_account_month(db: Session, account_id: int, year: int, month: int) -> dict:
    """Return the sum of imported transactions for (account, year, month)
    plus any user-saved statement total, plus a per-kind breakdown so the
    user can see which rows aren't standard expenses.
    """
    from calendar import monthrange

    start = date(year, month, 1)
    end = date(year, month, monthrange(year, month)[1])
    return reconcile_account_period(db, account_id, start, end, year=year, month=month)
