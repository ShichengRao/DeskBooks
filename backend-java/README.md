# DeskBooks Java Backend

This module is the gradual Java rewrite of the existing Python FastAPI backend.
It is opt-in while endpoints are migrated.

## Run

```bash
cd backend-java
BACKEND_PORT=8765 \
PFA_CORS_ORIGINS="http://localhost:5173,http://127.0.0.1:5173" \
gradle bootRun
```

The Java backend currently exposes these migrated endpoint groups:

- `GET /api/health`
- `POST /api/admin/shutdown`
- `/api/profiles`
- `/api/backups`
- `/api/accounts`
- `/api/categories`
- `GET`, `POST`, `PATCH`, and `DELETE /api/transactions`
- `GET /api/transactions/count`
- `PUT /api/transactions/{id}/split`
- `PATCH /api/transactions/bulk/update`
- `POST /api/transactions/pair`
- `POST /api/transactions/{id}/unpair`
- `/api/imports` for CSV importer discovery, preview, apply, listing, and rollback
- `/api/rules` for rule CRUD, coverage, reapply, and proposals
- `/api/budgets` for budget reports, defaults, and monthly overrides
- `/api/analytics/reconcile` and `/api/analytics/splits`
- `/api/goals`
- `/api/journal`
- `GET`, `POST`, `PATCH`, and `DELETE /api/snapshots`
- `GET /api/snapshots/series`
- `/api/analytics/fire/settings`
- `/api/analytics/fire/projection`

The default app still uses the Python backend. Use `../run.sh --backend java`
or `make dev-java` from the repo root to test the migrated Java slice with the
frontend.

## Test

```bash
cd backend-java
gradle test
```

If Gradle has trouble with the machine-local cache, use a temporary cache:

```bash
GRADLE_USER_HOME=/private/tmp/deskbooks-gradle-home gradle test
```
