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
