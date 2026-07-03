# Development

Technical notes for working on the app. The user-facing guide is in
`README.md`.

## Quick Commands

```bash
make dev              # full app: backend API + frontend UI, then open 127.0.0.1:5173
make dev-java         # explicit Java backend + frontend UI
make dev-python       # legacy Python backend + frontend UI
make dev PORT=5172 API_PORT=8766
make backend          # Java API only, at http://127.0.0.1:8765
make backend-java     # same as make backend, explicit Java spelling
make backend-python   # legacy Python API only, at http://127.0.0.1:8765/docs
make frontend         # UI only, at http://127.0.0.1:5173
make open             # open the frontend URL if servers are already running
make reset-db         # wipe repo-local dev app state and rebuild starter data
make typecheck        # TypeScript on the frontend
make build            # production build of frontend
make clean            # nuke venv and node_modules
```

`./run.sh` starts both servers. The frontend is Vite on port 5173. The
backend is Spring Boot on port 8765. Use
`./run.sh --port 5172 --api-port 8766` or
`make dev PORT=5172 API_PORT=8766` to run a second local copy. Both ports can
be any valid TCP port from `1` to `65535`; `FRONTEND_PORT` and `BACKEND_PORT`
are accepted as aliases for `PORT` and `API_PORT`.

The legacy Python backend remains available for verification:

```bash
./run.sh --backend python
make dev-python
make backend-python
```

Java verification commands:

```bash
make test-java
make parity-java
```

It starts fresh Python and Java backends on temporary ports/data directories,
then compares starter profile data, importer metadata, sample CSV previews,
manual transaction creation, budget reporting, monthly analytics, and Sankey
analytics.

The Java backend covers health/admin, profiles, backups, accounts, categories,
transactions, CSV/Amex workbook imports, net-worth workbook import, rules,
planning, snapshots, budgets, FIRE settings/projection, reconciliation, split
summaries, monthly analytics, Sankey analytics, and recurring merchant
analytics. The Java launch path also seeds starter accounts, categories, and
the welcome journal entry for empty profiles.

If Gradle's machine-local cache is unhealthy, use
`JAVA_GRADLE_USER_HOME=/private/tmp/deskbooks-gradle-home make test-java` or
the same variable with `make dev`.

The legacy Python backend OpenAPI docs are available when running
`make backend-python`:

<http://127.0.0.1:8765/docs>

## Layout

```text
backend-java/        Spring Boot + SQLite
backend/             legacy FastAPI + SQLAlchemy + SQLite
frontend/            React + Vite + TypeScript + Tailwind + Recharts/Plotly
docs/                Architecture, roadmap, and development notes
samples/             Synthetic import examples
```

## Stack

| Layer | Choice | Why |
|---|---|---|
| Backend | Java 21 + Spring Boot + SQLite JDBC | Local-first; typed; production default |
| Legacy backend | FastAPI + SQLAlchemy 2 + SQLite + Pydantic v2 | Parity checks and fallback |
| Frontend | React 18 + Vite + TS + Tailwind | Fast iteration; no SSR needed |
| Charts | Recharts + Plotly.js | Recharts for everyday charts; Plotly for Sankey |
| Tooling | Gradle, uv, npm, Makefile, bash | Minimum ceremony |

See `docs/ARCHITECTURE.md` for longer-form tradeoffs and data model notes.

## Startup Behavior

First run:

- downloads Java dependencies through Gradle
- installs frontend dependencies
- creates the active profile database in the OS user data directory
- seeds starter data if the active profile is empty

Later runs are faster and mostly just start the servers.

The Java backend ensures additive SQLite tables when it opens the active
profile. The legacy Python backend remains available with
`./run.sh --backend python` and still calls `init_db()` on startup.

## Importers

Built-in CSV formats:

- Chase credit card:
  `Transaction Date, Post Date, Description, Category, Type, Amount, Memo`
- Wells Fargo checking:
  `DATE, DESCRIPTION, AMOUNT, CHECK #, STATUS`
- Amex:
  `Date, Description, Amount`

Amex exports charges as positive values; the importer converts them to the
app's outflow-negative convention.

To add a new CSV format to the production backend, extend
`backend-java/src/main/java/com/deskbooks/backend/imports/ImportController.java`
and add a focused Java importer test. Keep the Python importer registry in sync
when the parity harness or legacy backend needs that format.

## Local Data

By default, SQLite data lives outside the repo:

- macOS: `~/Library/Application Support/DeskBooks/`
- Windows: `%APPDATA%/DeskBooks/`
- Linux: `${XDG_DATA_HOME:-~/.local/share}/deskbooks/`

Set `PFA_DATA_DIR` to use a different location. `make reset-db` is deliberately
limited to the repo-local development path at `backend/data/`.

Machine-local settings can live in a gitignored `.env.local` file:

```bash
export PFA_DATA_DIR="$HOME/Library/Application Support/DeskBooks"
```

Reset repo-local development state:

```bash
make reset-db
```

## Related Docs

- `docs/ARCHITECTURE.md`
- `docs/TODO.md`
