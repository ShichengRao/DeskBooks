# Environment

This document tracks local configuration, credentials, and environment variables for maintainers.

## Environment Variables
- `APPDATA` - document whether this is required, optional, or only used in local development.
- `FRONTEND_PORT` - document whether this is required, optional, or only used in local development.
- `PFA_ALLOW_SHUTDOWN` - document whether this is required, optional, or only used in local development.
- `PFA_API_TARGET` - document whether this is required, optional, or only used in local development.
- `PFA_CORS_ORIGINS` - document whether this is required, optional, or only used in local development.
- `PFA_DATA_DIR` - document whether this is required, optional, or only used in local development.
- `PFA_DB_FILE` - document whether this is required, optional, or only used in local development.
- `XDG_DATA_HOME` - document whether this is required, optional, or only used in local development.

## Secret-Like Local Paths
- `.env.local` - keep untracked unless it is a safe sample fixture.

## Maintainer Rules
- Use `.env.example` for shareable placeholders, never real secrets.
- Keep `credentials/`, `tokens/`, and machine-local assistant settings ignored unless a file is explicitly a sanitized fixture.
- Rotate any real credential that was accidentally committed.
