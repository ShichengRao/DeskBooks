# Contributing

Thanks for helping improve the app.

## Local Setup

```bash
./run.sh
```

Useful checks:

```bash
cd backend && uv run pytest
cd frontend && npm run typecheck
```

## Privacy Rules

- Do not commit real bank exports, account numbers, balances, payees, addresses,
  or screenshots from a real profile.
- Use `samples/` for synthetic importer fixtures.
- Use a demo or throwaway local profile for screenshots.
- Keep profile databases, `backend/data/`, and local tool caches out of Git.

## Code Style

- Keep changes small and local to the workflow being improved.
- Prefer importer tests for new CSV/XLSX formats.
- Add or update documentation when a feature changes startup, import, profile,
  or local-data behavior.
