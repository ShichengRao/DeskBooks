from __future__ import annotations

from collections import defaultdict
from datetime import date
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models


def normalize_month(value: date) -> date:
    return date(value.year, value.month, 1)


def month_end_exclusive(month: date) -> date:
    year = month.year + (1 if month.month == 12 else 0)
    next_month = 1 if month.month == 12 else month.month + 1
    return date(year, next_month, 1)


def month_range(start: date, end: date) -> list[date]:
    current = normalize_month(start)
    final = normalize_month(end)
    out: list[date] = []
    while current <= final:
        out.append(current)
        current = month_end_exclusive(current)
    return out


def _category_context(db: Session) -> dict:
    categories = list(
        db.scalars(
            select(models.Category)
            .where(models.Category.kind == models.CategoryKind.expense)
            .order_by(models.Category.sort_order, models.Category.name)
        )
    )
    category_by_id = {c.id: c for c in categories}
    children_by_parent: dict[int | None, list[models.Category]] = defaultdict(list)
    for category in categories:
        children_by_parent[category.parent_id].append(category)
    for children in children_by_parent.values():
        children.sort(key=lambda c: (c.sort_order, c.name.lower()))

    ordered_categories: list[models.Category] = []
    roots = [c for c in categories if c.parent_id not in category_by_id]

    def walk(category: models.Category) -> None:
        ordered_categories.append(category)
        for child in children_by_parent.get(category.id, []):
            walk(child)

    for root in roots:
        walk(root)

    descendant_cache: dict[int, set[int]] = {}

    def descendants(category_id: int) -> set[int]:
        if category_id in descendant_cache:
            return descendant_cache[category_id]
        out = {category_id}
        for child in children_by_parent.get(category_id, []):
            out.update(descendants(child.id))
        descendant_cache[category_id] = out
        return out

    def depth(category: models.Category) -> int:
        current = category
        count = 0
        seen: set[int] = set()
        while current.parent_id and current.parent_id in category_by_id and current.parent_id not in seen:
            seen.add(current.parent_id)
            count += 1
            current = category_by_id[current.parent_id]
        return count

    return {
        "categories": categories,
        "roots": roots,
        "ordered_categories": ordered_categories,
        "category_by_id": category_by_id,
        "children_by_parent": children_by_parent,
        "descendants": descendants,
        "depth": depth,
    }


def budget_report(
    db: Session,
    start_value: date,
    end_value: date,
    focus_month_value: date | None = None,
) -> dict:
    months = month_range(start_value, end_value)
    if not months:
        months = [normalize_month(start_value)]
    focus_month = normalize_month(focus_month_value) if focus_month_value is not None else None
    if focus_month is not None and (focus_month < months[0] or focus_month > months[-1]):
        focus_month = months[-1]

    ctx = _category_context(db)
    category_by_id: dict[int, models.Category] = ctx["category_by_id"]
    roots: list[models.Category] = ctx["roots"]
    descendants = ctx["descendants"]
    depth = ctx["depth"]
    children_by_parent = ctx["children_by_parent"]

    defaults = list(db.scalars(select(models.BudgetDefault)))
    default_by_category = {b.category_id: b for b in defaults}
    overrides = list(
        db.scalars(
            select(models.BudgetOverride).where(
                models.BudgetOverride.month >= months[0],
                models.BudgetOverride.month <= months[-1],
            )
        )
    )
    override_by_month_category = {(o.month, o.category_id): o for o in overrides}

    actual_by_month_exact: dict[date, dict[int, Decimal]] = defaultdict(
        lambda: defaultdict(lambda: Decimal("0"))
    )
    count_by_month_exact: dict[date, dict[int, int]] = defaultdict(lambda: defaultdict(int))
    transaction_rows_by_month: dict[date, list[tuple[int | None, Decimal]]] = defaultdict(list)
    uncategorized_by_month: dict[date, Decimal] = defaultdict(lambda: Decimal("0"))

    stmt = (
        select(
            models.Transaction.date,
            models.Transaction.category_id,
            models.Transaction.amount,
            models.TransactionSplit.personal_share,
        )
        .join(
            models.TransactionSplit,
            models.TransactionSplit.transaction_id == models.Transaction.id,
            isouter=True,
        )
        .where(
            models.Transaction.date >= months[0],
            models.Transaction.date < month_end_exclusive(months[-1]),
            models.Transaction.kind == models.TransactionKind.expense,
            models.Transaction.is_excluded_from_totals.is_(False),
        )
    )

    for tx_date, category_id, amount, personal_share in db.execute(stmt):
        month = normalize_month(tx_date)
        effective = amount if personal_share is None else amount * Decimal(personal_share)
        spending = -effective
        transaction_rows_by_month[month].append((category_id, spending))
        if category_id is None:
            uncategorized_by_month[month] += spending
            continue
        actual_by_month_exact[month][category_id] += spending
        count_by_month_exact[month][category_id] += 1

    def direct_target_for(month: date, category_id: int) -> Decimal | None:
        override = override_by_month_category.get((month, category_id))
        if override is not None:
            return override.amount
        default = default_by_category.get(category_id)
        return default.amount if default is not None else None

    rollup_cache: dict[tuple[date, int], Decimal | None] = {}

    def rollup_target_for(month: date, category_id: int) -> Decimal | None:
        key = (month, category_id)
        if key in rollup_cache:
            return rollup_cache[key]
        child_targets = [
            target
            for child in children_by_parent.get(category_id, [])
            if (target := rollup_target_for(month, child.id)) is not None
        ]
        if child_targets:
            rollup_cache[key] = sum(child_targets, Decimal("0"))
        else:
            rollup_cache[key] = direct_target_for(month, category_id)
        return rollup_cache[key]

    def month_summary(month: date) -> dict:
        planned_total = Decimal("0")
        covered_category_ids: set[int] = set()
        for root in roots:
            target = rollup_target_for(month, root.id)
            if target is None:
                continue
            planned_total += target
            covered_category_ids.update(descendants(root.id))

        actual_total = sum((spending for _, spending in transaction_rows_by_month[month]), Decimal("0"))
        budgeted_actual_total = Decimal("0")
        for category_id, spending in transaction_rows_by_month[month]:
            if category_id in covered_category_ids:
                budgeted_actual_total += spending
        return {
            "month": month,
            "planned_total": planned_total,
            "actual_total": actual_total,
            "delta_total": planned_total - actual_total,
            "budgeted_actual_total": budgeted_actual_total,
            "unbudgeted_actual_total": actual_total - budgeted_actual_total,
            "uncategorized_actual": uncategorized_by_month[month],
        }

    row_months = [focus_month] if focus_month is not None else months
    rows: list[dict] = []
    for category in ctx["ordered_categories"]:
        ids = descendants(category.id)
        actual = sum(
            (actual_by_month_exact[month][cid] for month in row_months for cid in ids),
            Decimal("0"),
        )
        transaction_count = sum((count_by_month_exact[month][cid] for month in row_months for cid in ids), 0)
        default = default_by_category.get(category.id)
        override = (
            override_by_month_category.get((focus_month, category.id))
            if focus_month is not None
            else None
        )
        row_targets = [
            target
            for month in row_months
            if (target := rollup_target_for(month, category.id)) is not None
        ]
        target = sum(row_targets, Decimal("0")) if row_targets else None
        parent = category_by_id.get(category.parent_id) if category.parent_id else None
        rows.append(
            {
                "category_id": category.id,
                "category_name": category.name,
                "parent_id": category.parent_id,
                "parent_name": parent.name if parent else None,
                "depth": depth(category),
                "has_children": bool(children_by_parent.get(category.id)),
                "default_budget_id": default.id if default else None,
                "default_amount": default.amount if default else None,
                "override_budget_id": override.id if override else None,
                "override_amount": override.amount if override else None,
                "target_amount": target,
                "actual_amount": actual,
                "delta": target - actual if target is not None else None,
                "transaction_count": transaction_count,
                "default_notes": default.notes if default else None,
                "override_notes": override.notes if override else None,
            }
        )

    summaries = [month_summary(month) for month in months]
    range_planned = sum((m["planned_total"] for m in summaries), Decimal("0"))
    range_actual = sum((m["actual_total"] for m in summaries), Decimal("0"))
    range_budgeted_actual = sum((m["budgeted_actual_total"] for m in summaries), Decimal("0"))
    range_uncategorized = sum((m["uncategorized_actual"] for m in summaries), Decimal("0"))

    return {
        "start": months[0],
        "end": months[-1],
        "focus_month": focus_month,
        "months": summaries,
        "planned_total": range_planned,
        "actual_total": range_actual,
        "delta_total": range_planned - range_actual,
        "budgeted_actual_total": range_budgeted_actual,
        "unbudgeted_actual_total": range_actual - range_budgeted_actual,
        "uncategorized_actual": range_uncategorized,
        "rows": rows,
    }
