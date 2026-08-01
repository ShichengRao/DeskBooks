# Release And Deployment

The app itself is local-only and unreleased. Two deployment-adjacent paths exist:

- **CI** runs the verification commands below on every pull request, plus the frozen OpenAPI contract check (`make api-contract-python`).
- **Read-only demo**: Vercel builds the frontend statically and runs the FastAPI app as a Python function (`vercel.json` + `api/index.py`), seeding synthetic profiles on cold start with `PFA_DEMO_MODE=1`. No user data is deployed.

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
