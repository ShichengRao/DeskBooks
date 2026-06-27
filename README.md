# DeskBooks

A local personal finance app for tracking transactions, net worth,
planning notes, rules, and spending analytics.

Data lives on your machine in your operating system's user data directory,
not in the repo by default.

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
category, including a percentage-based breakdown.

### Planning

Track goals and journal entries. Goals and journal edits keep revision
history so changes are easy to review later.

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
- Amex CSV

Synthetic examples for these formats live in `samples/`.

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

### Reconcile

Review imported data and account state when checking that the app matches
your source records.

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

To wipe local app state and rebuild generic starter data when using the
repo-local development data directory:

```bash
make reset-db
```

Profiles are local workspace selectors, not web accounts. Use them when
multiple people share a computer account or when you want a throwaway demo
database.

## Privacy

The app is local-only and has no telemetry. Routine use does not require
internet access after dependencies are installed, unless you click an account
URL you saved in the app. See `PRIVACY.md` and `SECURITY.md`.
