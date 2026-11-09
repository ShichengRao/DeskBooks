# Architecture

## Stack

- **Backend**: Python 3.11+ (managed by `uv`), FastAPI, SQLAlchemy 2.x,
  SQLite, Pydantic v2, stdlib CSV parsing, and OpenPyXL for `.xlsx` imports.
- **Frontend**: React 18 + TypeScript + Vite, Tailwind CSS, TanStack Query,
  Recharts (general charts), and a hand-rolled SVG Sankey component.
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
- **Recharts + a custom SVG Sankey** keeps the chart stack to one small
  dependency. Plotly was dropped: the backend aggregates Sankey nodes/links,
  and the frontend component does layout itself (barycenter column ordering,
  minimum ribbon widths, label de-overlap) with plain SVG.
- **Tailwind with small local components** avoids a large design-system
  dependency while keeping the interface consistent.
- **Browser launcher instead of native shell** keeps packaging optional. A
  Tauri or packaged-app layer can wrap the same backend/frontend later.

## Repo layout

```
deskbooks/
├── backend/
│   ├── app/
│   │   ├── main.py            # FastAPI entrypoint (+ demo-mode guard)
│   │   ├── db.py              # per-profile engines, additive migrations
│   │   ├── app_paths.py       # data-dir resolution (PFA_DATA_DIR)
│   │   ├── models.py          # SQLAlchemy models
│   │   ├── schemas.py         # Pydantic models
│   │   ├── onboarding.py      # starter data loader
│   │   ├── profiles.py        # local profile registry
│   │   ├── backups.py         # profile-scoped SQLite backups
│   │   ├── budgets.py         # budget report
│   │   ├── importers/         # CsvImporter ABC + per-format modules
│   │   │   (chase_credit, wells_fargo_checking, us_banks, amex,
│   │   │    amex_xlsx, contribution_history, staged_json)
│   │   ├── rules.py           # rule engine + proposals
│   │   ├── analytics.py       # rollups, sankey, FIRE, cancel-pairs
│   │   ├── trips.py           # trip totals and membership rules
│   │   └── routers/           # one router per concept
│   │       (accounts, transactions, categories, rules, snapshots,
│   │        goals, journal, budgets, imports, backups, profiles,
│   │        settings, analytics, trips)
│   ├── scripts/
│   │   └── seed_demo_profile.py  # synthetic demo/family personas
│   ├── data/                  # gitignored development data only
│   └── pyproject.toml
├── frontend/
│   ├── src/
│   │   ├── main.tsx
│   │   ├── App.tsx
│   │   ├── api/               # typed fetch wrappers (+ per-tab profile pin)
│   │   ├── pages/             # Dashboard, Transactions, NetWorth,
│   │   │                      # Planning, Budgets, Trips, Analytics, Import,
│   │   │                      # Reconcile (Splits & netting), Rules,
│   │   │                      # Organize, Backups
│   │   ├── components/        # incl. SankeySvg
│   │   └── lib/
│   ├── index.html
│   ├── package.json
│   └── vite.config.ts
├── automation/                # optional local fetch connectors (Node)
├── api/                       # Vercel entrypoint for the read-only demo
├── docs/                      # incl. contracts/ (frozen OpenAPI snapshot)
├── samples/                   # synthetic import examples
├── run.sh
├── vercel.json
├── Makefile
└── README.md
```

## Data model

```
Account
  id, name, institution, account_category[bank|investment|nonsense|
    tax_advantaged|credit|liability|cash|property], type[checking|savings|
    cd|brokerage|crypto|wallet|retirement|college|hsa|credit_card|cash|
    other], currency, sign_convention[outflow_negative|outflow_positive],
  url, notes, is_closed, opened_at, closed_at, sort_order

Category
  id, name, parent_id (nullable, hierarchical), kind[expense|income|
    transfer|investment|donation|tax|cc_payment|refund|reimbursement|
    other_non_expense], color, sort_order, archived

Transaction
  id, account_id, date, post_date, budget_date (nullable; see below),
  description_raw, description_normalized, merchant, amount
  (numeric(14,2), signed in account convention), category_id (nullable),
  kind (mirrors Category.kind but stored for fast filtering and
  pre-categorization), notes, transfer_pair_id (nullable, FK self),
  kind_before_pair (nullable; the kind to restore on unlink),
  import_batch_id, matched_rule_id (nullable), is_user_categorized,
  raw (JSON), is_excluded_from_totals (manual hide), created_at,
  updated_at

  `budget_date` reassigns which month a transaction counts toward
  without touching `date` — rent paid on the 1st for the month just
  gone, so a ~30-day cycle stops swinging the monthly totals. It is an
  attribution overlay, not a correction: the budget report and the
  Analytics month chart bucket by `budgets.budget_date_column()`
  (coalesce(budget_date, date)) and filter on that same expression, so a
  transaction reassigned across a window boundary is counted where the
  user put it. Everything describing when money actually moved — Sankey
  period reconciliation against net-worth snapshots, recurrence
  detection, cancel-pair matching — keeps using `date`.

Tag, TransactionTag (m2m)

TransactionSplit
  transaction_id (PK/FK), group_name, personal_share (0..1), notes
  -- shared expenses: analytics count amount * personal_share

Trip
  id, name, start_date (nullable), end_date (nullable), notes, archived,
  created_at, updated_at
  -- both dates or neither. They seed the trip page's date filter and
     give a per-night figure; membership never depends on them.

TripLeg
  id, trip_id, place, start_date, end_date, sort_order, notes
  -- a stop. Legs need not tile the trip: a travel day between two
     stays belongs to neither.

TripExpense
  transaction_id (PK/FK, ON DELETE CASCADE), trip_id, leg_id (nullable),
  group_name (nullable), share (0..1, default 1), notes
  -- `transaction_id` is the PK, so a row belongs to at most one trip and
     two overlapping trips cannot both count it. `group_name` is the
     trip's own label; NULL means "use the category name", so the
     default follows a recategorized row. `share` is the trip's own
     fraction, independent of `TransactionSplit.personal_share` — a row
     that is both shared and partly trip-related multiplies the two.

TripItem
  id, trip_id, leg_id (nullable), category_id (nullable), group_name
  (nullable), label, amount (positive = cost, negative = credit),
  date (nullable), notes
  -- a cost or credit with no transaction behind it. There is no
     account here whose sign convention it could inherit.

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

FireSettings (singleton)
  growth_{bank,investment,tax_advantaged,nonsense,cash,credit,property}
  (real annual rates), annual_retirement_spending, withdrawal_rate,
  birth_year (nullable), retirement_age

AppSettings (singleton)
  hidden_kinds (JSON) — transaction kinds hidden from pickers/filters
```

Schema evolution: `create_all` creates missing tables; additive columns on
existing tables are applied by a small PRAGMA-check + `ALTER TABLE`
registry in `db.py` (`_ADDITIVE_COLUMNS`). Anything beyond additive
columns needs a real migration tool.

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
- The same link nets out a refund or reversal against its original
  charge, either from a suggestion on the Splits page or by selecting
  two rows on Transactions and choosing **Link as pair**. Linking sets
  both sides to `kind=transfer` — that, not the link itself, is what
  drops them out of spending — and stashes each row's previous kind in
  `kind_before_pair` so unlinking (or deleting one side) restores it
  rather than stranding the survivor as a transfer. While linked, the
  rule engine skips the rows: their kind belongs to the pairing, and
  re-categorizing one side alone would break the cancellation.
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

Every request may carry an `X-DeskBooks-Profile` header naming the profile
it belongs to (the UI pins one per browser tab, enabling two windows on two
profiles at once); requests without the header use the registry's active
profile.

```
GET    /api/accounts
POST   /api/accounts            # also /bulk for many at once
PATCH  /api/accounts/{id}
DELETE /api/accounts/{id}

GET    /api/profiles
POST   /api/profiles
POST   /api/profiles/duplicate
POST   /api/profiles/active
PATCH  /api/profiles/{slug}     # rename (display name only)
DELETE /api/profiles/{slug}

GET    /api/backups
POST   /api/backups
POST   /api/backups/{name}/restore

GET    /api/categories?include_archived=
GET    /api/categories/usage    # per-category txn/rule/budget counts
POST   /api/categories
PATCH  /api/categories/{id}     # incl. one-level nesting via parent_id
POST   /api/categories/{id}/merge
DELETE /api/categories/{id}     # soft archive

GET    /api/settings/kinds      # hidden transaction kinds
PUT    /api/settings/kinds

GET    /api/rules
POST   /api/rules
PATCH  /api/rules/{id}
POST   /api/rules/{id}/reapply           # reruns the rule over all txns

GET    /api/transactions?...filters...   # category filter includes descendants;
                                         # trip_id= / not_in_trip= for trips
GET    /api/transactions/count
GET    /api/transactions/{id}
POST   /api/transactions
PATCH  /api/transactions/{id}
PUT    /api/transactions/{id}/split
PATCH  /api/transactions/bulk/update     # bulk categorize/exclude/tag
POST   /api/transactions/pair            # link two rows as a pair
POST   /api/transactions/{id}/unpair
DELETE /api/transactions/{id}

GET    /api/imports/importers
POST   /api/imports/preview              # multipart CSV/XLSX → preview JSON
POST   /api/imports/preview-path         # preview a staged local file
POST   /api/imports/apply                # commit a previewed batch
GET    /api/imports
GET    /api/imports/staged               # connector-staged files
POST   /api/imports/staged/apply
POST   /api/imports/{id}/rollback

GET    /api/snapshots
POST   /api/snapshots                    # create new (with all balances)
POST   /api/snapshots/import-workbook
GET    /api/snapshots/prefill            # balances from staged connector data
PATCH  /api/snapshots/{id}
DELETE /api/snapshots/{id}
GET    /api/snapshots/series             # for charts: (date, total, by_category, ...)

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

GET    /api/trips?include_archived=
POST   /api/trips                        # a name is enough; dates optional
GET    /api/trips/groups                 # trip groups in use, for suggestions
GET    /api/trips/{id}
PATCH  /api/trips/{id}
DELETE /api/trips/{id}                   # transactions are untouched
PUT    /api/trips/{id}/expenses          # tick / untick rows
PATCH  /api/trips/{id}/expenses/{tx_id}  # one row's group, stop, share
POST   /api/trips/{id}/legs
PATCH  /api/trips/{id}/legs/{leg_id}
DELETE /api/trips/{id}/legs/{leg_id}
POST   /api/trips/{id}/items
PATCH  /api/trips/{id}/items/{item_id}
DELETE /api/trips/{id}/items/{item_id}

GET    /api/analytics/monthly?start=&end=
GET    /api/analytics/sankey?year=       # or ?start=&end=
GET    /api/analytics/recurring          # merchant frequency detection
GET    /api/analytics/splits             # split-group summaries
GET    /api/analytics/cancel-candidates  # equal-and-opposite pair suggestions
GET    /api/analytics/cancel-pairs       # already-linked pairs
GET    /api/analytics/fire/settings
PUT    /api/analytics/fire/settings
GET    /api/analytics/fire/projection
```

The frozen OpenAPI snapshot in `docs/contracts/` is regenerated by
`make api-contract-python`; CI fails when routes drift from it.

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
- The snapshot editor starts blank (no autofill from the previous
  snapshot); balances can be pulled on demand from connector-staged data
  via `GET /api/snapshots/prefill`, or whole histories imported from a
  workbook.
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
- FIRE settings/projections are stored locally. The projection compounds
  the latest snapshot by per-category real growth rates (no future
  contributions are modeled) toward spending / withdrawal-rate. With a
  birth year set, a missed target reports the projected amount at
  retirement age instead of "never". Planning math only, not financial
  advice.

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
- A category with no default and no override is planned at zero, so its row
  reports the full spend as overspend rather than a blank. "Nothing budgeted
  anywhere in this subtree" stays a distinct internal state, which is what
  lets a parent fall back to its own target and what keeps
  `unbudgeted_actual_total` meaningful.

## Backups

- Backups are profile-scoped SQLite snapshots under the OS user data
  directory.
- Creating a backup uses SQLite's online backup API so the live database can
  be copied consistently.
- Restoring a backup disposes the active DB engine, saves a pre-restore
  snapshot, replaces the active profile DB, removes SQLite sidecar files, and
  reinitializes tables.

## Trips

A trip is a name plus the transactions the user ticks into it, in their
own time. Membership is an explicit set (`TripExpense`), never a stored
date filter and never proposed:

- A date window over-captures — rent, subscriptions and payroll land
  mid-trip — and under-captures, since flights and admissions are bought
  weeks or months ahead.
- Proposing membership was tried and dropped (it is in the history, in
  the commit that added a Trips tab proposing spending from dates). It
  needed correcting on every real trip, put every decision in front of
  the user at once, and could never reach the one-off booking made a
  year early. Ticking a few dozen rows is cheaper than reviewing a
  guess, and it covers every case.
- An explicit set also keeps a finished trip's total stable:
  recategorizing a row later cannot change what the trip cost.

The trip page is the transactions endpoint with two extra filters,
`trip_id` (rows in this trip) and `not_in_trip` (rows in no trip), so
browsing for a trip uses exactly the Transactions tab's filters. Every
`TransactionOut` carries `trip` (id, name, group, stop, share), which is
how both tables show membership. A tick is `PUT .../expenses` with ids to
add or remove; re-adding a row already in the trip is a no-op, so a page
of ticks can be resent without resetting anyone's edits. Per-row details
change through `PATCH .../expenses/{tx_id}`, which only touches the
fields sent.

Rules that hold whatever gets ticked:

- **One trip per row** — `transaction_id` is the primary key; a second
  trip gets a 409.
- **Money moving never counts.** Costs are negated to read positive, so
  an inflow would quietly subtract. Only expense, uncategorized, refund
  and reimbursement rows may join (`trips.COUNTABLE_KINDS`).
- **Rows file onto stops by date**, both when ticked and when a stop is
  added or moved, but only rows with no stop yet — an explicit choice is
  never second-guessed. Deleting a stop leaves its rows on the trip.
- **Deleting a transaction** removes its membership through the foreign
  key's cascade, as for splits and tags.

Totals follow `budgets.py`: `-(amount * personal_share) * share`,
rounded per row so the rows add up to the total. Rounding is
half-away-from-zero, explicitly: Decimal's default banker's rounding
would make half of $28.01 $14.00 while the trip page — which computes
each row's figure in exact integer arithmetic to show it — gets $14.01.
The report groups by `group_name`, else the category name, else "Other",
and splits each group across stops.

## Sankey

A Sankey aggregates any requested date range:

- source inflows and positive net-worth movement
- income/growth groupings
- a central inflows hub
- outflow groupings such as expenses, donations, taxes, and investments
- leaf categories/accounts

Node/link aggregation is server-side; the frontend component performs
layout (column ordering, ribbon thickness floors, label placement) and
renders plain SVG.

## Current Non-Goals

- Hosted web service or commercial SaaS
- Multi-user auth; profiles are local database selectors, not accounts
- Cloud sync or any hosted storage of user data
- Mobile app
- Native macOS wrapper, though the app can be packaged later

One deliberate exception: a **read-only hosted demo** (Vercel; see
`vercel.json` + `api/index.py`) serves the app with entirely synthetic
profiles seeded on cold start. `PFA_DEMO_MODE=1` turns the API read-only
and blocks filesystem-touching routes; no user data is ever hosted.

Optional, off-by-default connectors under `automation/` can fetch
transactions and balances from institutions the user configures (see
`docs/AUTOMATED_IMPORTS.md`). The backend itself stays offline by
design — `tests/test_no_external_network.py` enforces that it cannot
import network libraries; all fetching lives in the Node automation
layer and enters the app as staged local files.
