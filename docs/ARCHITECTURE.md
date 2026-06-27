# Architecture

## Stack

- **Backend**: Python 3.11+ (managed by `uv`), FastAPI, SQLAlchemy 2.x,
  SQLite, Pydantic v2, stdlib CSV parsing, and OpenPyXL for `.xlsx` imports.
- **Frontend**: React 18 + TypeScript + Vite, Tailwind CSS, TanStack Query,
  TanStack Table, Recharts (general charts), Plotly.js (Sankey).
- **Storage**: local SQLite profile files in the OS user data directory.
- **Launcher**: `./run.sh` (and `make dev`) start uvicorn + Vite and open the
  browser. No Electron/Tauri shell for v1 — the browser is the UI.

## Design Rationale

- **Local-first SQLite** keeps the app inspectable, backup-friendly, and free
  of hosted infrastructure. The expected data volume is small enough that
  SQLite is comfortably fast.
- **FastAPI + Pydantic** gives a typed API surface and local OpenAPI docs
  without much framework ceremony. Handlers are sync because the app is local
  and single-process.
- **SQLAlchemy over raw SQL** keeps model relationships easier to reason about
  while preserving SQLite portability.
- **React + Vite** fits the app as a local SPA. There is no server-side
  rendering need, and Vite keeps iteration fast.
- **Recharts + Plotly split** keeps common charts lightweight while using
  Plotly only for Sankey, where it is materially better than hand-rolled
  charting.
- **Tailwind with small local components** avoids a large design-system
  dependency while keeping the interface consistent.
- **Browser launcher instead of native shell** keeps packaging optional. A
  Tauri or packaged-app layer can wrap the same backend/frontend later.

## Repo layout

```
deskbooks/
├── backend/
│   ├── app/
│   │   ├── main.py            # FastAPI entrypoint
│   │   ├── db.py              # engine, session, init
│   │   ├── models.py          # SQLAlchemy models
│   │   ├── schemas.py         # Pydantic models
│   │   ├── onboarding.py      # starter data loader
│   │   ├── profiles.py        # local profile registry
│   │   ├── backups.py         # profile-scoped SQLite backups
│   │   ├── budgets.py         # budget rollups
│   │   ├── importers/
│   │   │   ├── base.py        # CsvImporter ABC + sniffing
│   │   │   ├── chase_credit.py
│   │   │   ├── wells_fargo_checking.py
│   │   │   └── amex.py
│   │   ├── rules.py           # rule engine
│   │   ├── analytics.py       # rollups, sankey, trends
│   │   └── routers/           # one router per concept
│   │       ├── accounts.py
│   │       ├── transactions.py
│   │       ├── categories.py
│   │       ├── rules.py
│   │       ├── snapshots.py
│   │       ├── goals.py
│   │       ├── journal.py
│   │       └── analytics.py
│   ├── data/                  # gitignored development data only
│   └── pyproject.toml
├── frontend/
│   ├── src/
│   │   ├── main.tsx
│   │   ├── App.tsx
│   │   ├── api/               # typed fetch wrappers
│   │   ├── pages/
│   │   │   ├── Dashboard.tsx
│   │   │   ├── Transactions.tsx
│   │   │   ├── NetWorth.tsx
│   │   │   ├── Planning.tsx
│   │   │   ├── Analytics.tsx   # Sankey + trends
│   │   │   └── Import.tsx
│   │   ├── components/
│   │   └── lib/
│   ├── index.html
│   ├── package.json
│   └── vite.config.ts
├── docs/
├── samples/                   # synthetic import examples
├── run.sh
├── Makefile
└── README.md
```

## Data model

```
Account
  id, name, institution, account_category[bank|investment|nonsense|
    tax_advantaged|credit|liability|cash], type[checking|savings|cd|
    brokerage|crypto|wallet|retirement|college|hsa|credit_card|cash],
  is_liquid, is_taxable, currency, sign_convention[outflow_negative|
    outflow_positive], url, notes, is_closed, opened_at, closed_at

Category
  id, name, parent_id (nullable, hierarchical), kind[expense|income|
    transfer|investment|donation|tax|cc_payment|refund|reimbursement|
    other_non_expense], color, sort_order, archived

Transaction
  id, account_id, date, post_date, description_raw,
  description_normalized, merchant, amount (numeric(14,2), signed in
  account convention), category_id (nullable), kind (mirrors
  Category.kind but stored for fast filtering and pre-categorization),
  notes, transfer_pair_id (nullable, FK self), import_batch_id,
  raw (JSON), is_excluded_from_totals (manual hide), created_at,
  updated_at

Tag, TransactionTag (m2m)

Rule
  id, name, priority, is_active, match_account_id (nullable),
  match_description_pattern (regex), match_amount_min, match_amount_max,
  set_category_id, set_kind, set_merchant, set_tags (JSON), notes,
  created_at, last_applied_at, apply_count

NetWorthSnapshot
  id, snapshot_date, notes

AccountBalance
  snapshot_id, account_id, balance (NULL allowed = account didn't exist),
  notes
  -- PK (snapshot_id, account_id)

Goal
  id, title, target_amount, target_date (nullable), kind[savings|
    purchase|retirement|other], status[active|met|abandoned|paused],
  linked_account_ids (JSON), notes_markdown, sort_order, archived,
  created_at, updated_at

GoalRevision
  id, goal_id, snapshot (JSON of full goal at that moment),
  changed_at, change_summary (markdown)

JournalEntry
  id, entry_date, title, body_markdown, goal_id (nullable), created_at,
  updated_at

JournalEntryRevision
  id, entry_id, title, body_markdown, entry_date, goal_id,
  changed_at, change_summary

BudgetDefault
  id, category_id, amount (positive planned monthly spending), notes,
  created_at, updated_at
  -- unique(category_id)

BudgetOverride
  id, month (first day of calendar month), category_id, amount
  (positive planned spending for this month only), notes, created_at,
  updated_at
  -- unique(month, category_id)

ImportBatch
  id, source_filename, importer_name, account_id, imported_at,
  row_count_total, row_count_applied, row_count_duplicate, status[
    preview|applied|rolled_back], notes
```

Key invariants:

- Transactions are stored with the **account's** sign convention but
  analytics always normalize to "outflow-negative" before aggregating.
- Per-account sign convention is deliberate: different institutions export
  charges and payments with different signs, and storing the convention keeps
  that variation explicit.
- Transfers between two accounts produce **two transactions** linked by
  `transfer_pair_id`. Analytics that compute "spend" exclude rows whose
  `kind` is one of: transfer, investment, cc_payment, refund,
  reimbursement, donation (configurable), tax (configurable).
- A transaction can be in any category, but `Transaction.kind` is a
  denormalized copy of `Category.kind` — written explicitly by the rule
  engine, manual PATCH, and the category-update cascade. Every analytic
  filters on it directly.
- The Donations / Taxes carve-out is implemented by `kind` (not by
  category name) so the user can split a single category into multiple
  groups later without breaking analytics.
- `AccountBalance.balance = NULL` means "this account did not exist or has no
  entry for this snapshot", which is distinct from a true zero balance.
- `ImportBatch` is the rollback unit. A bad import can be removed without
  hand-deleting rows.
- Once `is_user_categorized` is true, rules stop overwriting that transaction.

## API Shape

```
GET    /api/accounts
POST   /api/accounts
PATCH  /api/accounts/{id}
DELETE /api/accounts/{id}

GET    /api/profiles
POST   /api/profiles
POST   /api/profiles/active

GET    /api/backups
POST   /api/backups
POST   /api/backups/{name}/restore

GET    /api/categories
POST   /api/categories
PATCH  /api/categories/{id}

GET    /api/rules
POST   /api/rules
PATCH  /api/rules/{id}
POST   /api/rules/{id}/reapply           # reruns the rule over all txns

GET    /api/transactions?...filters...
PATCH  /api/transactions/{id}
PUT    /api/transactions/{id}/split
PATCH  /api/transactions/bulk            # bulk categorize/exclude/tag
POST   /api/transactions/{id}/pair       # mark two as a transfer pair
POST   /api/transactions/{id}/unpair
DELETE /api/transactions/{id}

POST   /api/imports/preview              # multipart CSV → preview JSON
POST   /api/imports/apply                # commit a previewed batch
GET    /api/imports
POST   /api/imports/{id}/rollback

GET    /api/snapshots
POST   /api/snapshots                    # create new (with all balances)
PATCH  /api/snapshots/{id}
DELETE /api/snapshots/{id}
GET    /api/snapshots/series             # for charts: list of (date, total, by_category, ...)

GET    /api/goals
POST   /api/goals
PATCH  /api/goals/{id}
GET    /api/goals/{id}/revisions

GET    /api/journal
POST   /api/journal
PATCH  /api/journal/{id}
GET    /api/journal/{id}/revisions

GET    /api/budgets?start=&end=&focus_month=  # month= is also accepted
PUT    /api/budgets/defaults
PUT    /api/budgets/overrides
DELETE /api/budgets/defaults/{id}
DELETE /api/budgets/overrides/{id}

GET    /api/analytics/monthly?start=&end=
GET    /api/analytics/sankey?year=       # or ?start=&end=
GET    /api/analytics/recurring          # merchant frequency detection
GET    /api/analytics/fire/settings
PUT    /api/analytics/fire/settings
GET    /api/analytics/fire/projection
GET    /api/analytics/reconcile
PUT    /api/analytics/reconcile
GET    /api/analytics/splits
```

## Import Pipeline

1. **Upload** multipart file → server parses it in memory and returns a
   preview.
2. **Sniff** — try each registered importer's `can_handle(headers)`;
   first match wins. User can override the choice.
3. **Parse** — produce a list of normalized `TransactionDraft` records:
   `{date, post_date?, description_raw, amount_in_account_convention,
    raw_columns: {…}}`.
4. **Match account** — by user choice. Sign convention is inherited from the
   selected account.
5. **Detect duplicates** — within the same account, the
   `(date, amount, normalized_description)` triple is the dedup key. The
   key is also re-evaluated at apply time against current DB state so a
   stale preview can't slip duplicates through.
6. **Apply rules** — run all active rules in priority order against
   each draft to seed category/kind/merchant/tags.
7. **Preview** — return JSON: rows + flags + suggested categorization.
8. **Apply** — user accepts; rows insert; `ImportBatch.status = applied`.
9. **Rollback** — delete all transactions with `import_batch_id = X`.

## Categorization

Two layers:

1. **Rule engine** (deterministic, user-editable). Runs at import time
   *and* can be re-run by a button "Re-categorize all unreviewed
   transactions". Priority lowest-number-first. A rule sets any subset
   of `{category_id, kind, merchant, tags}`. Once a transaction has a
   user-manually-set category, rules **do not overwrite** it (an
   `is_user_categorized` bit).
2. **Manual override** in the transactions table (single or bulk).

Recurring-merchant detection is purely analytic (frequency of the
normalized merchant string) — no auto-categorization unless the user
turns a recurring merchant into a rule.

## Net worth

- A `NetWorthSnapshot` corresponds to "I opened all my accounts on date
  X and wrote down the values."
- `AccountBalance` rows can be NULL → "this account didn't exist on that
  date" (distinct from 0).
- The snapshot-creation UI starts from the *previous* snapshot,
  pre-fills values, and the user edits.
- All chart series are computed in the backend so the frontend just
  consumes pre-aggregated JSON.

## Planning / Goals

- `Goal` has a target amount and optional target date.
- Linked accounts let the app compute current progress as
  `SUM(latest balance of linked accounts) / target_amount`.
- Every `PATCH` to a goal writes a `GoalRevision` snapshot. The detail
  view shows a github-like "this field changed from X to Y on date Z"
  history.
- `JournalEntry` is freeform markdown (think obsidian). Entries can be
  tied to a goal or standalone. Editing an entry creates a
  `JournalEntryRevision` so the user keeps the github-blame
  view they explicitly asked for.
- FIRE settings/projections are stored locally and computed from current
  balance, contribution, target, and return assumptions. They are planning
  math only, not financial advice.

## Budgets

- `BudgetDefault` stores the standing monthly target for a category.
- `BudgetOverride` stores month-specific exceptions. An override replaces
  the default for that category/month instead of adding to it.
- Budget reports are range-first: the API returns month summaries for the
  requested period plus category rows for either a focused month or the whole
  range when no focus month is selected.
- Actuals come from expense-kind transactions, with shared transactions
  reduced by `TransactionSplit.personal_share`.
- Parent category targets roll up child targets when any child budget exists;
  otherwise the parent uses its own direct target. Overall planned and actual
  totals count each category tree once, so parent and child display rows do
  not inflate the summary cards.

## Backups

- Backups are profile-scoped SQLite snapshots under the OS user data
  directory.
- Creating a backup uses SQLite's online backup API so the live database can
  be copied consistently.
- Restoring a backup disposes the active DB engine, saves a pre-restore
  snapshot, replaces the active profile DB, removes SQLite sidecar files, and
  reinitializes tables.

## Sankey

A Sankey aggregates any requested date range:

- source inflows and positive net-worth movement
- income/growth groupings
- a central inflows hub
- outflow groupings such as expenses, donations, taxes, and investments
- leaf categories/accounts

The diagram is computed server-side; the frontend only renders the response.

## Current Non-Goals

- Hosted web service or commercial SaaS
- Multi-user auth; profiles are local database selectors, not accounts
- Cloud sync, Plaid, brokerage APIs, or automatic balance fetching
- Mobile app
- Notifications / scheduled jobs
- Native macOS wrapper, though the app can be packaged later
