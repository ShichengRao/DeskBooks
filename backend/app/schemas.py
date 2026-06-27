"""Pydantic v2 request/response schemas."""
# Note: `date` is aliased to `_date` because a field literally named `date`
# shadows the imported type once Pydantic re-resolves hints via
# get_type_hints (which sees the class attribute set by the default value).
# Aliasing the import sidesteps the shadow without renaming any JSON field.
from datetime import date as _date, datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field

from .models import (
    AccountCategory,
    AccountType,
    CategoryKind,
    GoalKind,
    GoalStatus,
    ImportStatus,
    SignConvention,
    TransactionKind,
)


class ORMBase(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# ---------- profiles ----------


class ProfileCreate(BaseModel):
    name: str = Field(min_length=1, max_length=80)


class ProfileActivate(BaseModel):
    slug: str


class ProfileOut(BaseModel):
    slug: str
    name: str
    db_file: str
    is_active: bool


class ProfileList(BaseModel):
    profiles: list[ProfileOut]
    active_slug: str


# ---------- backups ----------


class BackupOut(BaseModel):
    name: str
    profile_slug: str
    size_bytes: int
    created_at: datetime
    path: str


class BackupList(BaseModel):
    profile_slug: str
    backups: list[BackupOut]


# ---------- accounts ----------


class AccountIn(BaseModel):
    name: str
    institution: str | None = None
    account_category: AccountCategory
    type: AccountType
    is_liquid: bool = True
    is_taxable: bool = True
    currency: str = "USD"
    sign_convention: SignConvention = SignConvention.outflow_negative
    url: str | None = None
    notes: str | None = None
    is_closed: bool = False
    opened_at: _date | None = None
    closed_at: _date | None = None
    sort_order: int = 0


class AccountUpdate(BaseModel):
    name: str | None = None
    institution: str | None = None
    account_category: AccountCategory | None = None
    type: AccountType | None = None
    is_liquid: bool | None = None
    is_taxable: bool | None = None
    currency: str | None = None
    sign_convention: SignConvention | None = None
    url: str | None = None
    notes: str | None = None
    is_closed: bool | None = None
    opened_at: _date | None = None
    closed_at: _date | None = None
    sort_order: int | None = None


class AccountOut(ORMBase):
    id: int
    name: str
    institution: str | None
    account_category: AccountCategory
    type: AccountType
    is_liquid: bool
    is_taxable: bool
    currency: str
    sign_convention: SignConvention
    url: str | None
    notes: str | None
    is_closed: bool
    opened_at: _date | None
    closed_at: _date | None
    sort_order: int


# ---------- categories ----------


class CategoryIn(BaseModel):
    name: str
    parent_id: int | None = None
    kind: CategoryKind
    color: str | None = None
    sort_order: int = 0
    archived: bool = False


class CategoryUpdate(BaseModel):
    name: str | None = None
    parent_id: int | None = None
    kind: CategoryKind | None = None
    color: str | None = None
    sort_order: int | None = None
    archived: bool | None = None


class CategoryOut(ORMBase):
    id: int
    name: str
    parent_id: int | None
    kind: CategoryKind
    color: str | None
    sort_order: int
    archived: bool


# ---------- transactions ----------


class TagOut(ORMBase):
    id: int
    name: str
    color: str | None


class TransactionSplitOut(ORMBase):
    transaction_id: int
    group_name: str
    personal_share: Decimal
    notes: str | None


class TransactionOut(ORMBase):
    id: int
    account_id: int
    date: _date
    post_date: _date | None
    description_raw: str
    description_normalized: str | None
    merchant: str | None
    amount: Decimal
    category_id: int | None
    kind: TransactionKind
    is_user_categorized: bool
    is_excluded_from_totals: bool
    notes: str | None
    transfer_pair_id: int | None
    import_batch_id: int | None
    matched_rule_id: int | None
    tags: list[TagOut] = []
    split: TransactionSplitOut | None = None


class TransactionSplitIn(BaseModel):
    group_name: str | None = None
    personal_share: Decimal = Decimal("0.5")
    notes: str | None = None


class TransactionIn(BaseModel):
    account_id: int
    date: _date
    post_date: _date | None = None
    description_raw: str
    description_normalized: str | None = None
    merchant: str | None = None
    amount: Decimal
    category_id: int | None = None
    kind: TransactionKind = TransactionKind.uncategorized
    is_excluded_from_totals: bool = False
    notes: str | None = None


class TransactionUpdate(BaseModel):
    date: _date | None = None
    post_date: _date | None = None
    description_raw: str | None = None
    description_normalized: str | None = None
    merchant: str | None = None
    amount: Decimal | None = None
    category_id: int | None = None
    kind: TransactionKind | None = None
    is_excluded_from_totals: bool | None = None
    notes: str | None = None
    transfer_pair_id: int | None = None


class TransactionBulkUpdate(BaseModel):
    ids: list[int]
    category_id: int | None = None
    kind: TransactionKind | None = None
    is_excluded_from_totals: bool | None = None
    split_group_name: str | None = None
    split_personal_share: Decimal | None = None
    split_notes: str | None = None
    clear_split: bool = False
    add_tag_ids: list[int] | None = None
    remove_tag_ids: list[int] | None = None


class TransactionPair(BaseModel):
    transaction_a_id: int
    transaction_b_id: int


# ---------- rules ----------


class RuleIn(BaseModel):
    name: str
    priority: int = 100
    is_active: bool = True
    match_account_id: int | None = None
    match_description_pattern: str | None = None
    match_amount_min: Decimal | None = None
    match_amount_max: Decimal | None = None
    set_category_id: int | None = None
    set_kind: TransactionKind | None = None
    set_merchant: str | None = None
    set_tags: list[str] | None = None
    notes: str | None = None


class RuleUpdate(BaseModel):
    name: str | None = None
    priority: int | None = None
    is_active: bool | None = None
    match_account_id: int | None = None
    match_description_pattern: str | None = None
    match_amount_min: Decimal | None = None
    match_amount_max: Decimal | None = None
    set_category_id: int | None = None
    set_kind: TransactionKind | None = None
    set_merchant: str | None = None
    set_tags: list[str] | None = None
    notes: str | None = None


class RuleBulkDelete(BaseModel):
    ids: list[int]


class RuleOut(ORMBase):
    id: int
    name: str
    priority: int
    is_active: bool
    match_account_id: int | None
    match_description_pattern: str | None
    match_amount_min: Decimal | None
    match_amount_max: Decimal | None
    set_category_id: int | None
    set_kind: TransactionKind | None
    set_merchant: str | None
    set_tags: list[str] | None
    notes: str | None
    apply_count: int
    last_applied_at: datetime | None


class RuleProposalBreakdown(BaseModel):
    category_id: int | None
    kind: TransactionKind
    count: int


class RuleProposalExample(BaseModel):
    transaction_id: int
    date: _date
    description: str
    amount: Decimal
    category_id: int | None
    kind: TransactionKind
    correct: bool


class RuleProposalOut(BaseModel):
    key: str
    name: str
    match_description_pattern: str
    match_account_id: int | None = None
    set_category_id: int | None
    set_kind: TransactionKind
    set_merchant: str | None = None
    support: int
    total_user_labeled_matches: int
    all_transaction_matches: int
    added_transaction_matches: int
    correct_matches: int
    incorrect_matches: int
    accuracy: float
    labeled_coverage_percent: float
    all_coverage_percent: float
    added_coverage_percent: float
    breakdown: list[RuleProposalBreakdown]
    examples: list[RuleProposalExample]


class RuleProposalBacktestIn(BaseModel):
    key: str
    name: str
    match_description_pattern: str
    match_account_id: int | None = None
    set_category_id: int | None
    set_kind: TransactionKind
    set_merchant: str | None = None


class RuleProposalRejectIn(RuleProposalBacktestIn):
    pass


class RuleCoverageOut(BaseModel):
    active_rule_count: int
    total_transactions: int
    matched_transactions: int
    coverage_percent: float
    labeled_transactions: int
    labeled_matched_transactions: int
    labeled_correct_matches: int
    labeled_incorrect_matches: int
    labeled_accuracy: float | None


class SplitGroupSummary(BaseModel):
    group_name: str
    shared_outflows: Decimal
    personal_outflows: Decimal
    expected_reimbursement: Decimal
    received_reimbursement: Decimal
    remaining_owed: Decimal
    transaction_count: int


# ---------- net worth ----------


class AccountBalanceIn(BaseModel):
    account_id: int
    balance: Decimal | None = None
    notes: str | None = None


class AccountBalanceOut(ORMBase):
    account_id: int
    balance: Decimal | None
    notes: str | None


class NetWorthSnapshotIn(BaseModel):
    snapshot_date: _date
    notes: str | None = None
    balances: list[AccountBalanceIn] = Field(default_factory=list)


class NetWorthSnapshotUpdate(BaseModel):
    snapshot_date: _date | None = None
    notes: str | None = None
    balances: list[AccountBalanceIn] | None = None


class NetWorthSnapshotOut(ORMBase):
    id: int
    snapshot_date: _date
    notes: str | None
    balances: list[AccountBalanceOut]


class NetWorthSeriesPoint(BaseModel):
    snapshot_date: _date
    total: Decimal
    by_category: dict[str, Decimal]
    by_account: dict[str, Decimal]
    liquid: Decimal
    illiquid: Decimal
    taxable: Decimal
    tax_advantaged: Decimal


# ---------- goals & journal ----------


class GoalIn(BaseModel):
    title: str
    target_amount: Decimal | None = None
    target_date: _date | None = None
    kind: GoalKind = GoalKind.savings
    status: GoalStatus = GoalStatus.active
    linked_account_ids: list[int] | None = None
    notes_markdown: str | None = None
    sort_order: int = 0


class GoalUpdate(BaseModel):
    title: str | None = None
    target_amount: Decimal | None = None
    target_date: _date | None = None
    kind: GoalKind | None = None
    status: GoalStatus | None = None
    linked_account_ids: list[int] | None = None
    notes_markdown: str | None = None
    sort_order: int | None = None
    archived: bool | None = None
    change_summary: str | None = None


class GoalOut(ORMBase):
    id: int
    title: str
    target_amount: Decimal | None
    target_date: _date | None
    kind: GoalKind
    status: GoalStatus
    linked_account_ids: list[int] | None
    notes_markdown: str | None
    sort_order: int
    archived: bool
    created_at: datetime
    updated_at: datetime


class GoalRevisionOut(ORMBase):
    id: int
    goal_id: int
    snapshot: dict
    changed_at: datetime
    change_summary: str | None


class JournalEntryIn(BaseModel):
    entry_date: _date
    title: str
    body_markdown: str
    goal_id: int | None = None


class JournalEntryUpdate(BaseModel):
    entry_date: _date | None = None
    title: str | None = None
    body_markdown: str | None = None
    goal_id: int | None = None
    change_summary: str | None = None


class JournalEntryOut(ORMBase):
    id: int
    entry_date: _date
    title: str
    body_markdown: str
    goal_id: int | None
    created_at: datetime
    updated_at: datetime


class JournalEntryRevisionOut(ORMBase):
    id: int
    entry_id: int
    title: str | None
    body_markdown: str
    entry_date: _date | None
    goal_id: int | None
    changed_at: datetime
    change_summary: str | None


# ---------- import pipeline ----------


class ImportDraftRow(BaseModel):
    row_index: int
    date: _date
    post_date: _date | None = None
    description_raw: str
    description_normalized: str | None = None
    merchant: str | None = None
    amount: Decimal  # already normalized to outflow-negative
    suggested_category_id: int | None = None
    suggested_kind: TransactionKind = TransactionKind.uncategorized
    suggested_tags: list[str] = []
    suggested_matched_rule_id: int | None = None
    is_duplicate: bool = False
    raw: dict | None = None


class ImportPreview(BaseModel):
    importer_name: str
    account_id: int
    source_filename: str
    rows: list[ImportDraftRow]
    sniff_notes: list[str] = []


class ImportApplyRequest(BaseModel):
    importer_name: str
    account_id: int
    source_filename: str
    rows: list[ImportDraftRow]
    skip_duplicates: bool = True


class ImportBatchOut(ORMBase):
    id: int
    source_filename: str
    importer_name: str
    account_id: int
    imported_at: datetime
    row_count_total: int
    row_count_applied: int
    row_count_duplicate: int
    status: ImportStatus
    notes: str | None


# ---------- analytics ----------


class MonthlyPoint(BaseModel):
    month: str  # YYYY-MM
    by_kind: dict[str, Decimal]
    by_expense_category: dict[str, Decimal]
    by_income_category: dict[str, Decimal]
    expenses_total: Decimal
    income_total: Decimal
    donations_total: Decimal
    taxes_total: Decimal
    net: Decimal


class ReconcileIn(BaseModel):
    account_id: int
    year: int
    month: int
    statement_total: Decimal | None = None
    notes: str | None = None


class BudgetDefaultIn(BaseModel):
    category_id: int
    amount: Decimal
    notes: str | None = None


class BudgetDefaultOut(ORMBase):
    id: int
    category_id: int
    amount: Decimal
    notes: str | None
    updated_at: datetime


class BudgetOverrideIn(BaseModel):
    month: _date
    category_id: int
    amount: Decimal
    notes: str | None = None


class BudgetOverrideOut(ORMBase):
    id: int
    month: _date
    category_id: int
    amount: Decimal
    notes: str | None
    updated_at: datetime


class BudgetMonthSummary(BaseModel):
    month: _date
    planned_total: Decimal
    actual_total: Decimal
    delta_total: Decimal
    budgeted_actual_total: Decimal
    unbudgeted_actual_total: Decimal
    uncategorized_actual: Decimal


class BudgetReportRow(BaseModel):
    category_id: int
    category_name: str
    parent_id: int | None
    parent_name: str | None
    depth: int
    has_children: bool
    default_budget_id: int | None
    default_amount: Decimal | None
    override_budget_id: int | None
    override_amount: Decimal | None
    target_amount: Decimal | None
    actual_amount: Decimal
    delta: Decimal | None
    transaction_count: int
    default_notes: str | None
    override_notes: str | None


class BudgetReport(BaseModel):
    start: _date
    end: _date
    focus_month: _date | None
    months: list[BudgetMonthSummary]
    planned_total: Decimal
    actual_total: Decimal
    delta_total: Decimal
    budgeted_actual_total: Decimal
    unbudgeted_actual_total: Decimal
    uncategorized_actual: Decimal
    rows: list[BudgetReportRow]


class FireSettingsIn(BaseModel):
    growth_bank: Decimal
    growth_investment: Decimal
    growth_tax_advantaged: Decimal
    growth_nonsense: Decimal
    growth_cash: Decimal
    growth_credit: Decimal
    annual_retirement_spending: Decimal
    withdrawal_rate: Decimal


class FireSettingsOut(ORMBase):
    growth_bank: Decimal
    growth_investment: Decimal
    growth_tax_advantaged: Decimal
    growth_nonsense: Decimal
    growth_cash: Decimal
    growth_credit: Decimal
    annual_retirement_spending: Decimal
    withdrawal_rate: Decimal
    updated_at: datetime


class FireProjectionYear(BaseModel):
    year: int
    age: int | None = None
    total: Decimal
    by_category: dict[str, Decimal]
    pct_of_target: float


class FireProjection(BaseModel):
    target_total: Decimal
    current_total: Decimal
    current_by_category: dict[str, Decimal]
    retirement_year: int | None
    years: list[FireProjectionYear]
    notes: list[str] = []


class ReconcileResponse(BaseModel):
    account_id: int
    year: int | None = None
    month: int | None = None
    start: _date
    end: _date
    transaction_count: int
    imported_total: Decimal
    imported_inflows: Decimal
    imported_outflows: Decimal
    by_kind: dict[str, Decimal]
    statement_total: Decimal | None
    statement_notes: str | None
    delta: Decimal | None


class SankeyNode(BaseModel):
    name: str


class SankeyLink(BaseModel):
    source: int
    target: int
    # float, not Decimal — Plotly consumes a number on the wire and we don't
    # need cent-precision for a Sankey display (the analytics layer already
    # downcasts to float before building the link).
    value: float
    label: str | None = None


class SankeyResponse(BaseModel):
    year: int
    label: str | None = None
    nodes: list[SankeyNode]
    links: list[SankeyLink]
    notes: list[str] = []


class RecurringMerchant(BaseModel):
    merchant: str
    occurrences: int
    avg_amount: Decimal
    total_amount: Decimal
    last_seen: _date
    cadence_days_estimate: float | None
