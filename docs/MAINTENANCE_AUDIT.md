# Maintenance Audit

- Source: `/Users/shichengrao/Projects/IdeaProjects/DeskBooks`
- Category: **useful for the future**
- Maintenance branch: `codex/repo-health-audit-20260629`
- Generated: 2026-06-29 05:08:09 UTC; status refreshed 2026-08-01

## Repo Map
- `.env.local` - project file or directory
- `.gitignore` - ignored local/generated files
- `.mdlr/` - untracked local tooling state
- `CONTRIBUTING.md` - project file or directory
- `LICENSE` - project file or directory
- `Makefile` - task runner shortcuts
- `PRIVACY.md` - project file or directory
- `README.md` - project overview
- `SECURITY.md` - project file or directory
- `backend/` - backend application
- `docs/` - project documentation
- `frontend/` - frontend application
- `run.sh` - project file or directory
- `samples/` - fixtures, sample data, or local runtime data

## Setup
- From `frontend`, install Node dependencies with `npm install`.
- From `frontend`, run the local app with `npm run dev`.
- From `frontend`, build with `npm run build`.
- From `backend`, install the Python project with `python3 -m pip install -e .` when editable local development is needed.
- From `backend`, run Python tests with `python3 -m pytest`.

## Verification Status (2026-08-01)
All verification commands pass and are enforced by CI on every pull
request (backend pytest + ruff, frontend typecheck + build, automation
node tests, and the frozen OpenAPI contract check via
`make api-contract-python`).

## Low-Risk Fixes In This Branch
- Added this maintainer handoff document.
- Updated ignore-file hygiene where missing.

## Product Behavior
- No product behavior changes were made.

## Remaining Backlog
Most of the original backlog is done: CI exists, environment variables are
documented (`docs/ENVIRONMENT.md`), architecture and dependency guidance
exist, and deployment posture is recorded (`docs/RELEASE.md`). Still open:

- Add issue labels or TODO triage notes for easy future PRs.
