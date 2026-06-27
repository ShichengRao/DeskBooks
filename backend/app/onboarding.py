from __future__ import annotations

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import (
    Account,
    AccountCategory,
    AccountType,
    Category,
    CategoryKind,
    JournalEntry,
    JournalEntryRevision,
    SignConvention,
)


STARTER_ACCOUNTS = [
    {
        "name": "Checking",
        "institution": None,
        "account_category": AccountCategory.bank,
        "type": AccountType.checking,
        "is_liquid": True,
        "is_taxable": True,
        "sign_convention": SignConvention.outflow_negative,
        "sort_order": 10,
    },
    {
        "name": "Savings",
        "institution": None,
        "account_category": AccountCategory.bank,
        "type": AccountType.savings,
        "is_liquid": True,
        "is_taxable": True,
        "sign_convention": SignConvention.outflow_negative,
        "sort_order": 20,
    },
    {
        "name": "Credit Card",
        "institution": None,
        "account_category": AccountCategory.credit,
        "type": AccountType.credit_card,
        "is_liquid": True,
        "is_taxable": True,
        "sign_convention": SignConvention.outflow_negative,
        "sort_order": 30,
    },
]


STARTER_CATEGORIES = [
    ("Housing", CategoryKind.expense, ["Rent", "Utilities"]),
    ("Food", CategoryKind.expense, ["Groceries", "Restaurants"]),
    ("Transportation", CategoryKind.expense, []),
    ("Health", CategoryKind.expense, []),
    ("Subscriptions", CategoryKind.expense, []),
    ("Misc", CategoryKind.expense, []),
    ("Income", CategoryKind.income, ["Paycheck", "Other Income"]),
    ("Transfer", CategoryKind.transfer, []),
    ("Credit Card Payment", CategoryKind.cc_payment, []),
    ("Refund", CategoryKind.refund, []),
]


def seed_starter_data(db: Session) -> dict[str, int]:
    accounts_added = 0
    categories_added = 0
    journal_added = 0

    for payload in STARTER_ACCOUNTS:
        if db.scalar(select(Account).where(Account.name == payload["name"])):
            continue
        db.add(Account(**payload))
        accounts_added += 1
    db.commit()

    sort_order = 0
    parent_ids: dict[str, int] = {}
    for group_name, group_kind, _leaves in STARTER_CATEGORIES:
        obj = db.scalar(select(Category).where(Category.name == group_name))
        if obj is None:
            obj = Category(
                name=group_name,
                kind=group_kind,
                parent_id=None,
                sort_order=sort_order,
            )
            db.add(obj)
            db.flush()
            categories_added += 1
        parent_ids[group_name] = obj.id
        sort_order += 1

    for group_name, group_kind, leaves in STARTER_CATEGORIES:
        parent_id = parent_ids[group_name]
        for leaf_name in leaves:
            if db.scalar(select(Category).where(Category.name == leaf_name)):
                continue
            db.add(
                Category(
                    name=leaf_name,
                    kind=group_kind,
                    parent_id=parent_id,
                    sort_order=sort_order,
                )
            )
            categories_added += 1
            sort_order += 1
    db.commit()

    if not db.scalar(select(JournalEntry)):
        entry = JournalEntry(
            entry_date=date.today(),
            title="Welcome",
            body_markdown=(
                "This local profile stores its data in a separate SQLite file.\n\n"
                "Add accounts, import transactions, and create snapshots to get started."
            ),
        )
        db.add(entry)
        db.flush()
        db.add(
            JournalEntryRevision(
                entry_id=entry.id,
                title=entry.title,
                body_markdown=entry.body_markdown,
                entry_date=entry.entry_date,
                goal_id=None,
                change_summary="initial starter seed",
            )
        )
        journal_added = 1
        db.commit()

    return {
        "accounts_added": accounts_added,
        "categories_added": categories_added,
        "journal_added": journal_added,
    }
