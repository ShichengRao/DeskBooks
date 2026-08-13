// Mirror of backend Pydantic schemas. Keep additive when backend changes.

export interface Profile {
  slug: string;
  name: string;
  db_file: string;
  is_active: boolean;
}

export interface ProfileList {
  profiles: Profile[];
  active_slug: string;
}

export interface Backup {
  name: string;
  profile_slug: string;
  /** Why the backup was taken, from the filename. null = manual. */
  label: string | null;
  size_bytes: number;
  created_at: string;
  path: string;
}

export interface BackupList {
  profile_slug: string;
  backups: Backup[];
}

export type AccountCategory =
  | "bank"
  | "investment"
  | "nonsense"
  | "property"
  | "tax_advantaged"
  | "credit"
  | "liability"
  | "cash";

export type AccountType =
  | "checking"
  | "savings"
  | "cd"
  | "brokerage"
  | "crypto"
  | "wallet"
  | "retirement"
  | "college"
  | "hsa"
  | "credit_card"
  | "cash"
  | "other";

export type SignConvention = "outflow_negative" | "outflow_positive";

export interface Account {
  id: number;
  name: string;
  institution: string | null;
  account_category: AccountCategory;
  type: AccountType;
  currency: string;
  sign_convention: SignConvention;
  url: string | null;
  notes: string | null;
  is_closed: boolean;
  opened_at: string | null;
  closed_at: string | null;
  sort_order: number;
}

export type CategoryKind =
  | "expense"
  | "income"
  | "transfer"
  | "investment"
  | "donation"
  | "tax"
  | "cc_payment"
  | "refund"
  | "reimbursement"
  | "other_non_expense";

export type TransactionKind = CategoryKind | "uncategorized";

export interface Category {
  id: number;
  name: string;
  parent_id: number | null;
  kind: CategoryKind;
  color: string | null;
  sort_order: number;
  archived: boolean;
}

export interface Tag {
  id: number;
  name: string;
  color: string | null;
}

export interface Transaction {
  id: number;
  account_id: number;
  date: string;
  post_date: string | null;
  /** Month this counts toward when it isn't the month it landed in. */
  budget_date: string | null;
  description_raw: string;
  description_normalized: string | null;
  merchant: string | null;
  amount: string; // Decimal serialized as string
  category_id: number | null;
  kind: TransactionKind;
  is_user_categorized: boolean;
  is_excluded_from_totals: boolean;
  notes: string | null;
  transfer_pair_id: number | null;
  import_batch_id: number | null;
  matched_rule_id: number | null;
  tags: Tag[];
  split: TransactionSplit | null;
}

export interface TransactionSplit {
  transaction_id: number;
  group_name: string;
  personal_share: string;
  notes: string | null;
}

export interface SplitGroupSummary {
  group_name: string;
  shared_outflows: string;
  personal_outflows: string;
  expected_reimbursement: string;
  received_reimbursement: string;
  remaining_owed: string;
  transaction_count: number;
}

export interface Rule {
  id: number;
  name: string;
  priority: number;
  is_active: boolean;
  match_account_id: number | null;
  match_description_pattern: string | null;
  match_amount_min: string | null;
  match_amount_max: string | null;
  set_category_id: number | null;
  set_kind: TransactionKind | null;
  set_merchant: string | null;
  set_tags: string[] | null;
  set_is_excluded_from_totals: boolean | null;
  pair_with_account_id: number | null;
  pair_within_days: number | null;
  notes: string | null;
  apply_count: number;
  last_applied_at: string | null;
}

export interface RuleProposalBreakdown {
  category_id: number | null;
  kind: TransactionKind;
  count: number;
}

export interface RuleProposalExample {
  transaction_id: number;
  date: string;
  description: string;
  amount: string;
  category_id: number | null;
  kind: TransactionKind;
  correct: boolean;
}

export interface PairProposalExample {
  source_transaction_id: number;
  target_transaction_id: number;
  date: string;
  amount: string;
  description: string;
  day_gap: number;
}

export interface PairProposal {
  key: string;
  name: string;
  match_account_id: number;
  match_description_pattern: string;
  pair_with_account_id: number;
  pair_within_days: number;
  support: number;
  reproduces: number;
  conflicts: number;
  would_link: number;
  examples: PairProposalExample[];
}

export interface RuleProposal {
  key: string;
  name: string;
  match_description_pattern: string;
  match_account_id: number | null;
  set_category_id: number | null;
  set_kind: TransactionKind;
  set_merchant: string | null;
  support: number;
  total_user_labeled_matches: number;
  all_transaction_matches: number;
  added_transaction_matches: number;
  correct_matches: number;
  incorrect_matches: number;
  accuracy: number;
  labeled_coverage_percent: number;
  all_coverage_percent: number;
  added_coverage_percent: number;
  breakdown: RuleProposalBreakdown[];
  examples: RuleProposalExample[];
}

export type RuleProposalBacktestInput = Pick<
  RuleProposal,
  | "key"
  | "name"
  | "match_description_pattern"
  | "match_account_id"
  | "set_category_id"
  | "set_kind"
  | "set_merchant"
>;

export interface RuleCoverage {
  active_rule_count: number;
  total_transactions: number;
  matched_transactions: number;
  coverage_percent: number;
  labeled_transactions: number;
  labeled_matched_transactions: number;
  labeled_correct_matches: number;
  labeled_incorrect_matches: number;
  labeled_accuracy: number | null;
}

export interface CategoryUsage {
  category_id: number;
  transactions: number;
  rules: number;
  budgets: number;
}

export interface KindSettings {
  hidden: string[];
  counts: Record<string, number>;
}

export interface AccountBalance {
  account_id: number;
  balance: string | null;
  notes: string | null;
}

export interface NetWorthSnapshot {
  id: number;
  snapshot_date: string;
  notes: string | null;
  balances: AccountBalance[];
}

export interface SnapshotPrefillBalance {
  account_id: number;
  balance: string; // money as string, matching snapshot balance inputs
  as_of: string;
  source: string;
}

export interface NetWorthSeriesPoint {
  snapshot_date: string;
  total: number;
  by_category: Record<string, number>;
  by_account: Record<string, number>;
  taxable: number;
  tax_advantaged: number;
}

export type GoalKind = "savings" | "purchase" | "retirement" | "other";
export type GoalStatus = "active" | "met" | "abandoned" | "paused";

export interface Goal {
  id: number;
  title: string;
  target_amount: string | null;
  target_date: string | null;
  kind: GoalKind;
  status: GoalStatus;
  linked_account_ids: number[] | null;
  notes_markdown: string | null;
  sort_order: number;
  archived: boolean;
  created_at: string;
  updated_at: string;
}

export interface GoalRevision {
  id: number;
  goal_id: number;
  snapshot: Record<string, unknown>;
  changed_at: string;
  change_summary: string | null;
}

export interface JournalEntry {
  id: number;
  entry_date: string;
  title: string;
  body_markdown: string;
  goal_id: number | null;
  created_at: string;
  updated_at: string;
}

export interface JournalEntryRevision {
  id: number;
  entry_id: number;
  title: string | null;
  body_markdown: string;
  entry_date: string | null;
  goal_id: number | null;
  changed_at: string;
  change_summary: string | null;
}

export interface ImportDraftRow {
  row_index: number;
  date: string;
  post_date: string | null;
  description_raw: string;
  description_normalized: string | null;
  merchant: string | null;
  amount: string;
  suggested_category_id: number | null;
  suggested_kind: TransactionKind;
  suggested_tags: string[];
  suggested_is_excluded_from_totals: boolean;
  is_duplicate: boolean;
  raw: Record<string, unknown> | null;
}

export interface ImportPreview {
  importer_name: string;
  account_id: number;
  source_filename: string;
  rows: ImportDraftRow[];
  sniff_notes: string[];
}

export interface ImportBatch {
  id: number;
  source_filename: string;
  importer_name: string;
  account_id: number;
  imported_at: string;
  row_count_total: number;
  row_count_applied: number;
  row_count_duplicate: number;
  status: "preview" | "applied" | "rolled_back";
  notes: string | null;
}

export interface StagedEntry {
  sha256: string;
  source: string;
  kind: "statement" | "balances";
  file_name: string;
  path: string;
  account_id: number | null;
  account_name: string | null;
  importer_name: string | null;
  profile: string | null;
  downloaded_at: string | null;
  row_count: number | null;
  new_count: number | null;
  as_of: string | null;
  status:
    | "new"
    | "imported"
    | "empty"
    | "other_profile"
    | "unknown_account"
    | "missing_file"
    | "invalid";
  detail: string | null;
}

export interface StagedApplyOutcome {
  sha256: string;
  file_name: string;
  status: string;
  batch_id: number | null;
  snapshot_id: number | null;
  rows_applied: number | null;
  duplicates: number | null;
  detail: string | null;
}

export interface StagedApplyResult {
  outcomes: StagedApplyOutcome[];
  backup_name: string | null;
}

export interface MonthlyPoint {
  month: string;
  by_kind: Record<string, number>;
  by_expense_category: Record<string, number>;
  by_income_category: Record<string, number>;
  expenses_total: number;
  income_total: number;
  donations_total: number;
  taxes_total: number;
  net: number;
}

export interface FireSettings {
  growth_bank: string;
  growth_investment: string;
  growth_tax_advantaged: string;
  growth_nonsense: string;
  growth_cash: string;
  growth_credit: string;
  growth_property: string;
  annual_retirement_spending: string;
  withdrawal_rate: string;
  birth_year: number | null;
  retirement_age: number;
  updated_at: string;
}

export interface FireProjectionYear {
  year: number;
  age: number | null;
  total: string;
  by_category: Record<string, string>;
  pct_of_target: number;
}

export interface FireProjection {
  target_total: string;
  current_total: string;
  current_by_category: Record<string, string>;
  retirement_year: number | null;
  retirement_age: number;
  retirement_age_year: number | null;
  total_at_retirement_age: string | null;
  years: FireProjectionYear[];
  notes: string[];
}

export interface CancelPair {
  a: Transaction;
  b: Transaction;
}

export interface CancelCandidate extends CancelPair {
  gap_days: number;
}

export interface BudgetDefault {
  id: number;
  category_id: number;
  amount: string;
  notes: string | null;
  updated_at: string;
}

export interface BudgetOverride extends BudgetDefault {
  month: string;
}

export interface BudgetMonthSummary {
  month: string;
  planned_total: string;
  actual_total: string;
  delta_total: string;
  budgeted_actual_total: string;
  unbudgeted_actual_total: string;
  uncategorized_actual: string;
}

export interface BudgetReportRow {
  category_id: number;
  category_name: string;
  parent_id: number | null;
  parent_name: string | null;
  depth: number;
  has_children: boolean;
  default_budget_id: number | null;
  default_amount: string | null;
  override_budget_id: number | null;
  override_amount: string | null;
  target_amount: string;
  actual_amount: string;
  delta: string;
  transaction_count: number;
  default_notes: string | null;
  override_notes: string | null;
}

export interface BudgetReport {
  start: string;
  end: string;
  focus_month: string | null;
  months: BudgetMonthSummary[];
  planned_total: string;
  actual_total: string;
  delta_total: string;
  budgeted_actual_total: string;
  unbudgeted_actual_total: string;
  uncategorized_actual: string;
  rows: BudgetReportRow[];
}

export interface SankeyResponse {
  year: number;
  label: string | null;
  nodes: { name: string }[];
  links: { source: number; target: number; value: number; label: string | null }[];
  notes: string[];
}

export interface RecurringMerchant {
  merchant: string;
  occurrences: number;
  avg_amount: number;
  total_amount: number;
  last_seen: string;
  cadence_days_estimate: number | null;
  is_expense: boolean;
}
