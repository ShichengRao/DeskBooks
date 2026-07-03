# Development

Technical notes for working on the app. The user-facing guide is in
`README.md`.

## Quick Commands

```bash
make dev              # full app: backend API + frontend UI, then open 127.0.0.1:5173
make dev-java         # opt-in Java backend slice + frontend UI
make dev PORT=5172 API_PORT=8766
make backend          # API only, at http://127.0.0.1:8765/docs
make backend-java     # opt-in Java API only, at http://127.0.0.1:8765
make frontend         # UI only, at http://127.0.0.1:5173
make open             # open the frontend URL if servers are already running
make reset-db         # wipe repo-local dev app state and rebuild starter data
make typecheck        # TypeScript on the frontend
make build            # production build of frontend
make clean            # nuke venv and node_modules
```

`./run.sh` starts both servers. The frontend is Vite on port 5173. The
backend is FastAPI/uvicorn on port 8765 with auto-reload enabled. Use
`./run.sh --port 5172 --api-port 8766` or
`make dev PORT=5172 API_PORT=8766` to run a second local copy. Both ports can
be any valid TCP port from `1` to `65535`; `FRONTEND_PORT` and `BACKEND_PORT`
are accepted as aliases for `PORT` and `API_PORT`.

The Java rewrite is available as an opt-in backend while endpoints are being
migrated:

```bash
./run.sh --backend java
make dev-java
make backend-java
make test-java
```

The current Java backend slice covers health/admin, profiles, backups,
accounts, categories, transactions, CSV imports, rules, planning, snapshots,
budgets, FIRE settings/projection, reconciliation, split summaries, monthly
analytics, and recurring merchant analytics. Sankey analytics is still pending,
so the Python backend remains the default.

The default launcher still uses the Python backend until the Java backend has
feature parity. If Gradle's machine-local cache is unhealthy, use
`JAVA_GRADLE_USER_HOME=/private/tmp/deskbooks-gradle-home make test-java` or
the same variable with `make dev-java`.

The backend OpenAPI docs are available at:

<http://127.0.0.1:8765/docs>

## Layout

```text
backend/             FastAPI + SQLAlchemy + SQLite
frontend/            React + Vite + TypeScript + Tailwind + Recharts/Plotly
docs/                Architecture, roadmap, and development notes
samples/             Synthetic import examples
```

## Stack

| Layer | Choice | Why |
|---|---|---|
| Backend | FastAPI + SQLAlchemy 2 + SQLite + Pydantic v2 | Local-first; OpenAPI; typed |
| Frontend | React 18 + Vite + TS + Tailwind | Fast iteration; no SSR needed |
| Charts | Recharts + Plotly.js | Recharts for everyday charts; Plotly for Sankey |
| Tooling | uv, npm, Makefile, bash | Minimum ceremony |

See `docs/ARCHITECTURE.md` for longer-form tradeoffs and data model notes.

## Startup Behavior

First run:

- creates `backend/.venv`
- installs Python dependencies
- installs frontend dependencies
- creates the active profile database in the OS user data directory
- seeds starter data if the active profile is empty

Later runs are faster and mostly just start the servers.

`backend/app/main.py` calls `init_db()` on startup, so additive tables are
created automatically when the backend reloads.

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

To add a new CSV format, add a module under `backend/app/importers/` that
subclasses `CsvImporter` and decorates with `@register`. See
`backend/app/importers/chase_credit.py` for a compact example.

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
