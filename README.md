# DeskBooks

A local personal finance app for tracking transactions, net worth,
planning notes, rules, and spending analytics.

Data lives on your machine in your operating system's user data directory,
not in the repo by default. A read-only demo with entirely synthetic data
runs at <https://deskbooks-demo.vercel.app>.

> Developer/setup internals live in `docs/DEVELOPMENT.md`.

## Start The App

Requirements: macOS, Python >= 3.11, Node >= 18, and
[`uv`](https://github.com/astral-sh/uv).

```bash
# install uv + node if you don't have them
brew install uv node

# from the repo root
./run.sh
```

This starts:

- the app UI at <http://localhost:5173>
- the backend API at <http://127.0.0.1:8765>

The first run installs dependencies, creates the active local profile
database, seeds starter data when the profile is empty, and opens the app.
Later runs are faster.

If the servers are already running and you just need to reopen the app:

```bash
make open
```

To stop the app, press `Ctrl-C` in the terminal running `./run.sh`.

If another local copy is already using the default ports, run DeskBooks on
alternate ports:

```bash
make dev PORT=5172 API_PORT=8766
# or:
./run.sh --port 5172 --api-port 8766
```

When `PORT` is not `5173`, the launcher defaults the backend to port `8766`.
Both ports can be any valid TCP port from `1` to `65535`. `FRONTEND_PORT`
and `BACKEND_PORT` are accepted as aliases for `PORT` and `API_PORT`.

## App Panels

### Dashboard

High-level view of current net worth, recent trends, and active goal
progress.

### Transactions

The main transaction table. Use it to search, filter, edit categories,
bulk-edit, delete, and manually add transactions.

Useful filters include:

- date range
- account or account type
- category
- transaction kind
- signed amount range
- free-text search

Amounts use the app's signed convention: expenses/outflows are negative,
income/inflows are positive.

### Net Worth

Create and edit dated net-worth snapshots. Charts show totals by account
category (including real estate and liabilities), plus side-by-side
asset-allocation and liability-mix breakdowns.

### Planning

Track goals and journal entries (both keep revision history), and run the
FIRE projection: per-category real growth rates compound your latest
snapshot toward a spending / withdrawal-rate target, with an
amount-at-retirement-age readout when the target isn't reached.

### Budgets

Set default category targets, add month-specific overrides, and compare
planned spending with actual expense transactions across a range.

### Analytics

Explore spending and money flow:

- date-range Sankey chart
- monthly expenses by category
- monthly income/expense summaries
- recurring merchant detection

Most charts support custom time ranges and interactive filtering.

### Import

Upload CSVs, preview parsed transactions, review duplicates, and apply an
import batch. Applied batches can be rolled back.

Supported import formats:

- Chase credit card CSV
- Wells Fargo checking CSV
- Amex CSV and XLSX
- 401(k) contribution-history CSV
- staged JSON from the automation connectors

Synthetic examples for these formats live in `samples/`.

Optional local automation can fetch institution exports into a staging
directory and preview or apply them through the same import path. See
`docs/AUTOMATED_IMPORTS.md`.

### Rules

Create and manage regex rules that categorize transactions. Rules can be
applied to unreviewed transactions, and proposed rules can be edited,
backtested, promoted, or rejected.

The Rules panel shows:

- current active-rule coverage
- generated rule proposals
- raw proposal coverage
- net-new coverage added by a proposal
- historical correctness and breakdown examples

### Splits & Netting

Track shared expenses (who owes what per split group) and net out
offsetting transactions: the app suggests unlinked equal-and-opposite
pairs — refunds, reversals, reimbursements — and linking a pair drops
both rows out of spending analytics.

### Organize

Self-service taxonomy cleanup: rename, merge, nest (one level), and
archive categories with usage counts and warnings; hide unused
transaction kinds; and regroup accounts across net-worth categories.

### Backups

Create and restore profile-scoped SQLite snapshots from the local app UI.

## Local Data

By default, profile databases live outside the repo:

- macOS: `~/Library/Application Support/DeskBooks/`
- Windows: `%APPDATA%/DeskBooks/`
- Linux: `${XDG_DATA_HOME:-~/.local/share}/deskbooks/`

The default profile database is `app.db`. Additional profiles use separate
SQLite files under `profiles/` inside that data directory.

Set `PFA_DATA_DIR` to use a different location, such as repo-local data during
development. A profile registry is expected in the active data directory.
For machine-local settings that should never be committed, create `.env.local`
with shell-style exports such as:

```bash
export PFA_DATA_DIR="$HOME/Library/Application Support/DeskBooks"
```

To wipe local app state and rebuild generic starter data when using the
repo-local development data directory:

```bash
make reset-db
```

Profiles are local workspace selectors, not web accounts. Use them when
multiple people share a computer account or when you want a throwaway demo
database. Each browser window pins its own profile, so two people can use
two profiles side by side at the same time.

## Privacy

The core app is local-only and has no telemetry; with no connectors enabled,
routine use does not require internet access after dependencies are
installed. Optional, off-by-default connectors under `automation/` can fetch
transactions and balances from institutions you configure — data flows only
between your machine and hosts you pin, with credentials in private local
files. See `PRIVACY.md`, `SECURITY.md`, and `docs/AUTOMATED_IMPORTS.md`.


## Maintenance

- `docs/MAINTENANCE_AUDIT.md` records the current audit status, verification commands, and backlog.
- `docs/ARCHITECTURE.md` maps the maintainer-facing project structure when present.
- `docs/DEPENDENCIES.md` records dependency update guidance when present.
