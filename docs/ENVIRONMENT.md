# Environment

This document tracks local configuration, credentials, and environment variables for maintainers.

## App Environment Variables

- `PFA_DATA_DIR` — optional. Overrides the data directory holding profile databases, backups, and `import-staging/`. Both the backend and the automation fetch runner honor it, so the two halves of the pipeline always agree.
- `PFA_DB_FILE` — optional, development. Overrides the active profile database file.
- `PFA_ALLOW_SHUTDOWN` — optional. Set to `1` by the launcher so the UI can stop the backend via `POST /api/admin/shutdown`.
- `PFA_CORS_ORIGINS` — optional. Comma-separated allowed origins; set by `run.sh`/`make` to the exact frontend origin.
- `PFA_API_TARGET` — optional, development. Where the Vite dev server proxies `/api` (set by the launcher).
- `PFA_SEED_STARTER_DATA` — optional. `0` disables starter-data seeding on bootstrap.
- `FRONTEND_PORT` / `BACKEND_PORT` — optional aliases for the launcher's `PORT` / `API_PORT`.
- `APPDATA` / `XDG_DATA_HOME` — platform defaults consulted when picking the data directory on Windows/Linux.

## Automation (optional connectors)

- `DESKBOOKS_FETCH_CONFIG` — optional. Path to the connector config; defaults to `automation/config.local.json`.
- `DESKBOOKS_IMPORT_APPLY` — optional. `1` makes `make fetch-apply`/`run-fetch.sh` apply staged rows (after a backup) instead of preview-only.
- `DESKBOOKS_IMPORT_MANIFEST` — optional. Manifest path for `app.automation_import`; the wrapper defaults to the latest run's manifest.
- `DESKBOOKS_IMPORT_STAGING_DIR` — optional. Staging directory override for `app.automation_import`.
- `DESKBOOKS_ENV_FILE` — optional. Alternate `.env.local` sourced by `run.sh` and `run-fetch.sh`.

## Credentials

- Connector credentials are private files you manage (`chmod 600`), outside the repo, referenced by path from `automation/config.local.json`. For Plaid: `clientIdPath`, `secretPath`, and `accessTokenPath` (see `docs/AUTOMATED_IMPORTS.md`). Nothing secret belongs in config files or the repo.

## Secret-Like Local Paths

- `.env.local` — keep untracked unless it is a safe sample fixture.
- `automation/config.local.json` — untracked; holds account mappings and connector settings (no secrets).
- The staging tree under the data directory (`import-staging/`) contains downloaded financial data; it never belongs in the repo.

## Maintainer Rules

- Never commit real secrets; rotate any credential that was accidentally committed.
- Keep `credentials/`, `tokens/`, and machine-local assistant settings ignored unless a file is explicitly a sanitized fixture.
