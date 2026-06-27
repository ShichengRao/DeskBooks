"""SQLAlchemy 2.x declarative models.

Mirrors the data model described in docs/ARCHITECTURE.md.
"""
from __future__ import annotations

import enum
from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import (
    JSON,
    Boolean,
    Date,
    DateTime,
    Enum as SAEnum,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


# ---------- enums ----------


class AccountCategory(str, enum.Enum):
    bank = "bank"
    investment = "investment"
    nonsense = "nonsense"  # crypto/wallets/cash/misc
    tax_advantaged = "tax_advantaged"
    credit = "credit"
    liability = "liability"
    cash = "cash"


class AccountType(str, enum.Enum):
    checking = "checking"
    savings = "savings"
    cd = "cd"
    brokerage = "brokerage"
    crypto = "crypto"
    wallet = "wallet"
    retirement = "retirement"
    college = "college"
    hsa = "hsa"
    credit_card = "credit_card"
    cash = "cash"
    other = "other"


class SignConvention(str, enum.Enum):
    outflow_negative = "outflow_negative"  # Chase CC, WF checking
    outflow_positive = "outflow_positive"  # Amex CSV


class TransactionKind(str, enum.Enum):
    expense = "expense"
    income = "income"
    transfer = "transfer"
    investment = "investment"
    donation = "donation"
    tax = "tax"
    cc_payment = "cc_payment"
    refund = "refund"
    reimbursement = "reimbursement"
    other_non_expense = "other_non_expense"
    uncategorized = "uncategorized"


class CategoryKind(str, enum.Enum):
    expense = "expense"
    income = "income"
    transfer = "transfer"
    investment = "investment"
    donation = "donation"
    tax = "tax"
    cc_payment = "cc_payment"
    refund = "refund"
    reimbursement = "reimbursement"
    other_non_expense = "other_non_expense"


class GoalKind(str, enum.Enum):
    savings = "savings"
    purchase = "purchase"
    retirement = "retirement"
    other = "other"


class GoalStatus(str, enum.Enum):
    active = "active"
    met = "met"
    abandoned = "abandoned"
    paused = "paused"


class ImportStatus(str, enum.Enum):
    preview = "preview"
    applied = "applied"
    rolled_back = "rolled_back"


# ---------- tables ----------


class Account(Base):
    __tablename__ = "accounts"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(120), unique=True)
    institution: Mapped[str | None] = mapped_column(String(120))
    account_category: Mapped[AccountCategory] = mapped_column(
        SAEnum(AccountCategory, name="account_category")
    )
    type: Mapped[AccountType] = mapped_column(SAEnum(AccountType, name="account_type"))
    is_liquid: Mapped[bool] = mapped_column(Boolean, default=True)
    is_taxable: Mapped[bool] = mapped_column(Boolean, default=True)
    currency: Mapped[str] = mapped_column(String(3), default="USD")
    sign_convention: Mapped[SignConvention] = mapped_column(
        SAEnum(SignConvention, name="sign_convention"),
        default=SignConvention.outflow_negative,
    )
    url: Mapped[str | None] = mapped_column(Text)
    notes: Mapped[str | None] = mapped_column(Text)
    is_closed: Mapped[bool] = mapped_column(Boolean, default=False)
    opened_at: Mapped[date | None] = mapped_column(Date)
    closed_at: Mapped[date | None] = mapped_column(Date)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())

    transactions: Mapped[list["Transaction"]] = relationship(back_populates="account")
    balances: Mapped[list["AccountBalance"]] = relationship(back_populates="account")


class Category(Base):
    __tablename__ = "categories"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(120), unique=True)
    parent_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"))
    kind: Mapped[CategoryKind] = mapped_column(SAEnum(CategoryKind, name="category_kind"))
    color: Mapped[str | None] = mapped_column(String(16))
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    archived: Mapped[bool] = mapped_column(Boolean, default=False)

    parent: Mapped["Category | None"] = relationship(remote_side=[id])
    transactions: Mapped[list["Transaction"]] = relationship(back_populates="category")
    budget_defaults: Mapped[list["BudgetDefault"]] = relationship(back_populates="category")
    budget_overrides: Mapped[list["BudgetOverride"]] = relationship(back_populates="category")


class Transaction(Base):
    __tablename__ = "transactions"

    id: Mapped[int] = mapped_column(primary_key=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id"))
    date: Mapped[date] = mapped_column(Date, index=True)
    post_date: Mapped[date | None] = mapped_column(Date)
    description_raw: Mapped[str] = mapped_column(Text)
    description_normalized: Mapped[str | None] = mapped_column(Text)
    merchant: Mapped[str | None] = mapped_column(String(255))
    # signed amount in the user's preferred convention (outflow negative).
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2))
    category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"))
    kind: Mapped[TransactionKind] = mapped_column(
        SAEnum(TransactionKind, name="transaction_kind"),
        default=TransactionKind.uncategorized,
        index=True,
    )
    is_user_categorized: Mapped[bool] = mapped_column(Boolean, default=False)
    is_excluded_from_totals: Mapped[bool] = mapped_column(Boolean, default=False)
    notes: Mapped[str | None] = mapped_column(Text)
    transfer_pair_id: Mapped[int | None] = mapped_column(ForeignKey("transactions.id"))
    import_batch_id: Mapped[int | None] = mapped_column(ForeignKey("import_batches.id"))
    # Which rule (if any) was responsible for the current categorization.
    # NULL = either uncategorized, or user-categorized, or pre-rules data.
    matched_rule_id: Mapped[int | None] = mapped_column(ForeignKey("rules.id", ondelete="SET NULL"))
    raw: Mapped[dict | None] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    account: Mapped[Account] = relationship(back_populates="transactions")
    category: Mapped[Category | None] = relationship(back_populates="transactions")
    batch: Mapped["ImportBatch | None"] = relationship(back_populates="transactions")
    tags: Mapped[list["Tag"]] = relationship(secondary="transaction_tags", back_populates="transactions")
    split: Mapped["TransactionSplit | None"] = relationship(
        back_populates="transaction", cascade="all, delete-orphan", uselist=False
    )

    __table_args__ = (
        Index("ix_transactions_date_kind", "date", "kind"),
        Index("ix_transactions_account_date", "account_id", "date"),
    )


class Tag(Base):
    __tablename__ = "tags"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(64), unique=True)
    color: Mapped[str | None] = mapped_column(String(16))

    transactions: Mapped[list[Transaction]] = relationship(
        secondary="transaction_tags", back_populates="tags"
    )


class TransactionTag(Base):
    __tablename__ = "transaction_tags"
    transaction_id: Mapped[int] = mapped_column(
        ForeignKey("transactions.id", ondelete="CASCADE"), primary_key=True
    )
    tag_id: Mapped[int] = mapped_column(ForeignKey("tags.id", ondelete="CASCADE"), primary_key=True)


class TransactionSplit(Base):
    __tablename__ = "transaction_splits"

    transaction_id: Mapped[int] = mapped_column(
        ForeignKey("transactions.id", ondelete="CASCADE"), primary_key=True
    )
    group_name: Mapped[str] = mapped_column(String(120), index=True)
    # Fraction of the transaction that belongs in personal analytics.
    # Example: shared utility expense = 0.5; reimbursement inflow = 0.
    personal_share: Mapped[Decimal] = mapped_column(Numeric(5, 4), default=Decimal("0.5"))
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())

    transaction: Mapped[Transaction] = relationship(back_populates="split")


class Rule(Base):
    __tablename__ = "rules"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(120))
    priority: Mapped[int] = mapped_column(Integer, default=100, index=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    match_account_id: Mapped[int | None] = mapped_column(ForeignKey("accounts.id"))
    match_description_pattern: Mapped[str | None] = mapped_column(Text)
    match_amount_min: Mapped[Decimal | None] = mapped_column(Numeric(14, 2))
    match_amount_max: Mapped[Decimal | None] = mapped_column(Numeric(14, 2))
    set_category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"))
    set_kind: Mapped[TransactionKind | None] = mapped_column(
        SAEnum(TransactionKind, name="rule_set_kind")
    )
    set_merchant: Mapped[str | None] = mapped_column(String(255))
    set_tags: Mapped[list | None] = mapped_column(JSON)
    notes: Mapped[str | None] = mapped_column(Text)
    last_applied_at: Mapped[datetime | None] = mapped_column(DateTime)
    apply_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class RuleProposalRejection(Base):
    __tablename__ = "rule_proposal_rejections"

    id: Mapped[int] = mapped_column(primary_key=True)
    signature: Mapped[str] = mapped_column(String(512), unique=True, index=True)
    key: Mapped[str] = mapped_column(String(255))
    name: Mapped[str] = mapped_column(String(255))
    match_account_id: Mapped[int | None] = mapped_column(ForeignKey("accounts.id"))
    match_description_pattern: Mapped[str] = mapped_column(Text)
    set_category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"))
    set_kind: Mapped[TransactionKind] = mapped_column(SAEnum(TransactionKind, name="proposal_rejection_kind"))
    set_merchant: Mapped[str | None] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class NetWorthSnapshot(Base):
    __tablename__ = "net_worth_snapshots"

    id: Mapped[int] = mapped_column(primary_key=True)
    snapshot_date: Mapped[date] = mapped_column(Date, unique=True, index=True)
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())

    balances: Mapped[list["AccountBalance"]] = relationship(
        back_populates="snapshot", cascade="all, delete-orphan"
    )


class AccountBalance(Base):
    __tablename__ = "account_balances"

    snapshot_id: Mapped[int] = mapped_column(
        ForeignKey("net_worth_snapshots.id", ondelete="CASCADE"), primary_key=True
    )
    account_id: Mapped[int] = mapped_column(
        ForeignKey("accounts.id", ondelete="CASCADE"), primary_key=True
    )
    # NULL = account did not exist at this snapshot (distinct from 0)
    balance: Mapped[Decimal | None] = mapped_column(Numeric(14, 2))
    notes: Mapped[str | None] = mapped_column(Text)

    snapshot: Mapped[NetWorthSnapshot] = relationship(back_populates="balances")
    account: Mapped[Account] = relationship(back_populates="balances")


class Goal(Base):
    __tablename__ = "goals"

    id: Mapped[int] = mapped_column(primary_key=True)
    title: Mapped[str] = mapped_column(String(255))
    target_amount: Mapped[Decimal | None] = mapped_column(Numeric(14, 2))
    target_date: Mapped[date | None] = mapped_column(Date)
    kind: Mapped[GoalKind] = mapped_column(SAEnum(GoalKind, name="goal_kind"), default=GoalKind.savings)
    status: Mapped[GoalStatus] = mapped_column(
        SAEnum(GoalStatus, name="goal_status"), default=GoalStatus.active
    )
    linked_account_ids: Mapped[list[int] | None] = mapped_column(JSON)
    notes_markdown: Mapped[str | None] = mapped_column(Text)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    archived: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    revisions: Mapped[list["GoalRevision"]] = relationship(
        back_populates="goal", cascade="all, delete-orphan", order_by="GoalRevision.changed_at"
    )


class GoalRevision(Base):
    __tablename__ = "goal_revisions"

    id: Mapped[int] = mapped_column(primary_key=True)
    goal_id: Mapped[int] = mapped_column(ForeignKey("goals.id", ondelete="CASCADE"))
    snapshot: Mapped[dict] = mapped_column(JSON)
    changed_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    change_summary: Mapped[str | None] = mapped_column(Text)

    goal: Mapped[Goal] = relationship(back_populates="revisions")


class JournalEntry(Base):
    __tablename__ = "journal_entries"

    id: Mapped[int] = mapped_column(primary_key=True)
    entry_date: Mapped[date] = mapped_column(Date, index=True)
    title: Mapped[str] = mapped_column(String(255))
    body_markdown: Mapped[str] = mapped_column(Text)
    goal_id: Mapped[int | None] = mapped_column(ForeignKey("goals.id", ondelete="SET NULL"))
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    revisions: Mapped[list["JournalEntryRevision"]] = relationship(
        back_populates="entry", cascade="all, delete-orphan", order_by="JournalEntryRevision.changed_at"
    )


class JournalEntryRevision(Base):
    __tablename__ = "journal_entry_revisions"

    id: Mapped[int] = mapped_column(primary_key=True)
    entry_id: Mapped[int] = mapped_column(ForeignKey("journal_entries.id", ondelete="CASCADE"))
    body_markdown: Mapped[str] = mapped_column(Text)
    title: Mapped[str | None] = mapped_column(String(255))
    entry_date: Mapped[date | None] = mapped_column(Date)
    goal_id: Mapped[int | None] = mapped_column(ForeignKey("goals.id", ondelete="SET NULL"))
    changed_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    change_summary: Mapped[str | None] = mapped_column(Text)

    entry: Mapped[JournalEntry] = relationship(back_populates="revisions")


class FireSettings(Base):
    """Singleton row holding the user's FIRE-calculator inputs.

    Real (inflation-adjusted) growth rates per account category, plus
    a target annual retirement spend and a withdrawal rate. The
    projection lives in analytics.py; this just holds the inputs.
    """

    __tablename__ = "fire_settings"

    id: Mapped[int] = mapped_column(primary_key=True)
    # Real (inflation-adjusted) annual growth rates, stored as decimals
    # (0.07 = 7% real return).
    growth_bank: Mapped[Decimal] = mapped_column(Numeric(6, 4), default=Decimal("0.0100"))
    growth_investment: Mapped[Decimal] = mapped_column(Numeric(6, 4), default=Decimal("0.0500"))
    growth_tax_advantaged: Mapped[Decimal] = mapped_column(Numeric(6, 4), default=Decimal("0.0500"))
    growth_nonsense: Mapped[Decimal] = mapped_column(Numeric(6, 4), default=Decimal("0.0000"))
    growth_cash: Mapped[Decimal] = mapped_column(Numeric(6, 4), default=Decimal("0.0000"))
    growth_credit: Mapped[Decimal] = mapped_column(Numeric(6, 4), default=Decimal("0.0000"))
    # Annual spend you want to support in retirement, in today's dollars.
    annual_retirement_spending: Mapped[Decimal] = mapped_column(
        Numeric(14, 2), default=Decimal("75000.00")
    )
    # 4 % SWR by default — overrideable for conservative/aggressive runs.
    withdrawal_rate: Mapped[Decimal] = mapped_column(Numeric(6, 4), default=Decimal("0.0400"))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )


class MonthlyReconciliation(Base):
    """Per-(account, month) user-entered statement total + notes.

    Lets the user record "the bank says I withdrew $X net in May" and
    compare against what the app's imported transactions sum to.
    """

    __tablename__ = "monthly_reconciliations"

    id: Mapped[int] = mapped_column(primary_key=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id", ondelete="CASCADE"))
    year: Mapped[int] = mapped_column(Integer)
    month: Mapped[int] = mapped_column(Integer)
    statement_total: Mapped[Decimal | None] = mapped_column(Numeric(14, 2))
    notes: Mapped[str | None] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (UniqueConstraint("account_id", "year", "month", name="uq_recon_acct_month"),)


class BudgetPlanMixin:
    id: Mapped[int] = mapped_column(primary_key=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id", ondelete="CASCADE"))
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2))
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )


class BudgetDefault(BudgetPlanMixin, Base):
    __tablename__ = "budget_defaults"

    # Positive planned monthly spending for this category.
    category: Mapped[Category] = relationship(back_populates="budget_defaults")

    __table_args__ = (UniqueConstraint("category_id", name="uq_budget_default_category"),)


class BudgetOverride(BudgetPlanMixin, Base):
    __tablename__ = "budget_overrides"

    month: Mapped[date] = mapped_column(Date, index=True)
    # Positive planned spending for this category in this month only.
    category: Mapped[Category] = relationship(back_populates="budget_overrides")

    __table_args__ = (UniqueConstraint("month", "category_id", name="uq_budget_override_month_category"),)


class ImportBatch(Base):
    __tablename__ = "import_batches"

    id: Mapped[int] = mapped_column(primary_key=True)
    source_filename: Mapped[str] = mapped_column(String(255))
    importer_name: Mapped[str] = mapped_column(String(64))
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id"))
    imported_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    row_count_total: Mapped[int] = mapped_column(Integer, default=0)
    row_count_applied: Mapped[int] = mapped_column(Integer, default=0)
    row_count_duplicate: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[ImportStatus] = mapped_column(
        SAEnum(ImportStatus, name="import_status"), default=ImportStatus.preview
    )
    notes: Mapped[str | None] = mapped_column(Text)

    transactions: Mapped[list[Transaction]] = relationship(back_populates="batch")


__all__ = [
    "Base",
    "Account",
    "AccountCategory",
    "AccountType",
    "SignConvention",
    "Category",
    "CategoryKind",
    "Transaction",
    "TransactionKind",
    "Tag",
    "TransactionTag",
    "Rule",
    "NetWorthSnapshot",
    "AccountBalance",
    "Goal",
    "GoalKind",
    "GoalStatus",
    "GoalRevision",
    "JournalEntry",
    "JournalEntryRevision",
    "ImportBatch",
    "ImportStatus",
    "MonthlyReconciliation",
    "FireSettings",
    "BudgetDefault",
    "BudgetOverride",
]
