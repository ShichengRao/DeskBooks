# Dependencies

Use this as the maintainer checklist for dependency updates and reproducible setup.

## Inventory
- `frontend` Node package: 6 runtime deps, 8 dev deps, lockfile `frontend/package-lock.json`.
- `backend` Python project metadata is listed in `backend/pyproject.toml`.

## Update Guidance
- Prefer lockfile-preserving installs when a lockfile exists.
- Run the verification commands in `docs/MAINTENANCE_AUDIT.md` after dependency updates.
- Keep dependency updates separate from product behavior changes so regressions are easier to review.
- If a broad version range is intentional, document why before widening it further.
