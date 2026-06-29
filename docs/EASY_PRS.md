# Easy PRs

Small, reviewable maintenance ideas for future cleanup.

## Candidates
- Review secret-like local paths and keep them untracked: .env.local
- Resolve existing uncommitted work before handing the repo to another maintainer.
- Add issue labels or TODO triage notes for easy future PRs.

## Guardrails
- Keep these PRs behavior-neutral unless the behavior change is explicitly called out.
- Prefer one concern per PR: docs, dependency updates, tests, or dead-file cleanup.
- Run the verification commands from `docs/MAINTENANCE_AUDIT.md` before handing off.
