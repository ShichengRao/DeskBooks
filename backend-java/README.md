# DeskBooks Java Backend

This module is the gradual Java rewrite of the existing Python FastAPI backend.
It is now the default local backend; the Python backend remains in the repo as
a fallback and parity reference.

## Run

```bash
cd backend-java
BACKEND_PORT=8765 \
PFA_CORS_ORIGINS="http://localhost:5173,http://127.0.0.1:5173" \
gradle bootRun
```

The Java backend exposes these endpoint groups:

- `GET /api/health`
- `POST /api/admin/shutdown`
- `/api/profiles`
- starter data seeding for first-run launch and profile creation
- `/api/backups`
- `/api/accounts`
- `/api/categories`
- `GET`, `POST`, `PATCH`, and `DELETE /api/transactions`
- `GET /api/transactions/count`
- `PUT /api/transactions/{id}/split`
- `PATCH /api/transactions/bulk/update`
- `POST /api/transactions/pair`
- `POST /api/transactions/{id}/unpair`
- `/api/imports` for CSV/Amex workbook importer discovery, preview, apply, listing, and rollback
- `/api/rules` for rule CRUD, coverage, reapply, and proposals
- `/api/budgets` for budget reports, defaults, and monthly overrides
- `/api/analytics/reconcile` and `/api/analytics/splits`
- `/api/goals`
- `/api/journal`
- `GET`, `POST`, `PATCH`, and `DELETE /api/snapshots`
- `POST /api/snapshots/import-workbook`
- `GET /api/snapshots/series`
- `/api/analytics/fire/settings`
- `/api/analytics/fire/projection`

Use `../run.sh` or `make dev` from the repo root to run the Java backend with
the frontend. Those launch paths seed starter accounts, categories, and the
welcome journal entry into an empty active profile. Use
`../run.sh --backend python` only when checking the legacy backend.

## Test

```bash
cd backend-java
gradle test
```

If Gradle has trouble with the machine-local cache, use a temporary cache:

```bash
GRADLE_USER_HOME=/private/tmp/deskbooks-gradle-home gradle test
```
