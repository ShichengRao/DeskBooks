# Release And Deployment

This repository does not yet declare a fully verified release process. Use these notes as the maintainer baseline until a project-specific release path is confirmed.

## Local Verification
- `cd frontend && npm run typecheck`
- `cd frontend && npm test`
- `cd frontend && npm run build`
- `cd backend && python3 -m pytest`
- `cd backend && python3 -m compileall app tests`
- `cd backend && python3 -m ruff check .`

## Release Rules
- Keep dependency updates, product behavior changes, and deployment changes in separate commits or PRs when possible.
- Record any required secrets, hosting targets, or manual deployment steps here before treating this repo as production-maintained.
- If this repo is local-only, keep this document as the statement of that expectation.
