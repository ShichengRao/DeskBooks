# Maintenance Audit

- Source: `/Users/shichengrao/Projects/IdeaProjects/DeskBooks`
- Category: **useful for the future**
- Maintenance branch: `codex/repo-health-audit-20260629`
- Generated: 2026-06-29 05:08:09 UTC

## Repo Map
- `.env.local` - project file or directory
- `.gitignore` - ignored local/generated files
- `.mdlr/` - project file or directory
- `CONTRIBUTING.md` - project file or directory
- `LICENSE` - project file or directory
- `Makefile` - task runner shortcuts
- `PRIVACY.md` - project file or directory
- `README.md` - project overview
- `SECURITY.md` - project file or directory
- `backend/` - backend application
- `docs/` - project documentation
- `frontend/` - frontend application
- `repro_v0` - project file or directory
- `repro_v1` - project file or directory
- `repro_v2` - project file or directory
- `run.sh` - project file or directory
- `samples/` - fixtures, sample data, or local runtime data

## Setup
- From `frontend`, install Node dependencies with `npm install`.
- From `frontend`, run the local app with `npm run dev`.
- From `frontend`, build with `npm run build`.
- From `backend`, install the Python project with `python3 -m pip install -e .` when editable local development is needed.
- From `backend`, run Python tests with `python3 -m pytest`.

## Verification Status
- `cd frontend && npm run typecheck` (typecheck): pass in 1.6s
- `cd frontend && npm run build` (build): pass in 15.4s
- `cd backend && python3 -m pytest` (test): fail (exit 1) in 0.0s
- `cd backend && python3 -m compileall app tests` (syntax): pass in 0.0s
- `cd backend && python3 -m ruff check .` (lint): fail (exit 1) in 0.0s

## Top Maintenance Issues
1. Add CI to run the documented verification commands on pull requests.
2. Review secret-like local paths and keep them untracked: .env.local
3. Clarify which data files are sample fixtures versus private/local runtime data.
4. Resolve existing uncommitted work before handing the repo to another maintainer.
5. Document environment variables and external services required for local development.
6. Document release/deploy steps or explicitly mark the repo as local-only.
7. Add an architecture or repo-map document for non-obvious code paths.
8. Add dependency update guidance, including known incompatible versions.
9. Add issue labels or TODO triage notes for easy future PRs.

## Low-Risk Fixes In This Branch
- Added this maintainer handoff document.
- Updated ignore-file hygiene where missing.

## Product Behavior
- No product behavior changes were made.

## Remaining Backlog
- Add CI to run the documented verification commands on pull requests.
- Review secret-like local paths and keep them untracked: .env.local
- Clarify which data files are sample fixtures versus private/local runtime data.
- Resolve existing uncommitted work before handing the repo to another maintainer.
- Document environment variables and external services required for local development.
- Document release/deploy steps or explicitly mark the repo as local-only.
- Add an architecture or repo-map document for non-obvious code paths.
- Add dependency update guidance, including known incompatible versions.
- Add issue labels or TODO triage notes for easy future PRs.
