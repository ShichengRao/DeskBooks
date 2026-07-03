# Java Backend Rewrite Plan

This plan rewrites the Python FastAPI backend as a Java backend without
breaking the current local app during the transition. The Python backend stays
available until the Java backend reaches feature parity and the launcher is
switched.

## Goals

- Preserve the existing React frontend contract: same `/api/...` paths, query
  parameters, request bodies, response shapes, status codes, and validation
  behavior where practical.
- Keep the app runnable after every commit.
- Migrate in small, reviewable slices.
- Use SQLite profile files exactly as the current backend does so existing
  local data remains usable.
- Build tests around API compatibility and finance calculations before
  replacing the default backend.

## Non-Goals

- Rewriting the frontend.
- Changing the product model, database schema, or local-first storage model.
- Adding hosted infrastructure, auth, telemetry, or external services.
- Dropping the Python backend before the Java backend is complete.

## Proposed Java Stack

- Java 21.
- Spring Boot 3 for HTTP, validation, dependency injection, and test support.
- SQLite JDBC for local profile databases.
- Flyway for additive schema migrations once the Java backend becomes the
  owner of schema evolution.
- jOOQ for SQL-heavy reporting and analytics.
- Spring JDBC or small repository classes for simple CRUD and file-adjacent
  flows.
- Jackson for JSON, including enum values that match the current API.
- JUnit 5 plus MockMvc for endpoint tests.

The first Java module can use direct SQL repositories before jOOQ generation is
introduced. That keeps the scaffold small, then adds type-safe generated SQL
when the reporting layer is migrated.

## Branch And Commit Strategy

Use the branch `codex/java-backend-rewrite`.

Each commit should leave the repository in one of these states:

- Existing Python backend and frontend still work unchanged.
- Java backend builds and passes its currently scoped tests.
- Any launcher changes are opt-in until Java reaches parity.

Suggested commit sequence:

1. Add this migration plan.
2. Add `backend-java/` Spring Boot scaffold with health/admin endpoints and
   basic documentation.
3. Add shared compatibility fixtures generated from the current API behavior.
4. Port profile registry and active SQLite database selection.
5. Port schema creation/bootstrap for starter data.
6. Port accounts and categories CRUD.
7. Port transactions CRUD, filtering, pairing, splitting, and bulk update.
8. Port imports preview/apply/rollback and importer registry.
9. Port rules and rule proposal workflows.
10. Port snapshots, goals, journal, backups, and budgets.
11. Port analytics, recurring detection, Sankey, FIRE projection, and
    reconciliation.
12. Add an opt-in launcher path for Java, for example
    `./run.sh --backend java` or `make backend-java`.
13. Run side-by-side parity tests against Python and Java.
14. Switch the default launcher only after parity is verified.
15. Remove the Python backend only in a final cleanup PR, if desired.

## Compatibility Rules

- Keep all Java endpoints under `/api`.
- Keep enum JSON values identical to Python `StrEnum` values.
- Serialize dates as `YYYY-MM-DD`.
- Serialize timestamps in a stable ISO format.
- Preserve decimal precision for money. Use `BigDecimal`, never `double`.
- Treat SQLite `NULL` distinctly from zero for balances.
- Preserve transaction sign semantics:
  - stored amounts use the account convention captured by the existing data
    model;
  - analytics normalize to outflow-negative before aggregating.
- Preserve `is_user_categorized` as the guard that prevents rules from
  overwriting manual choices.
- Preserve import batch rollback as the safety boundary for bad imports.
- Preserve profile-specific SQLite files and profile-scoped backups.

## Endpoint Migration Order

### Foundation

- `GET /api/health`
- `POST /api/admin/shutdown`
- CORS behavior matching the current launched frontend ports.
- SQLite connection setup with foreign keys, WAL, and `synchronous=NORMAL`.

### Local File And Profile Layer

- `GET /api/profiles`
- `POST /api/profiles`
- `POST /api/profiles/duplicate`
- `POST /api/profiles/active`
- `DELETE /api/profiles/{slug}`
- `GET /api/backups`
- `POST /api/backups`
- `POST /api/backups/{name}/restore`
- `DELETE /api/backups/{name}`

This layer should be migrated early because it defines which SQLite file the
rest of the backend uses.

### Simple Domain CRUD

- Accounts.
- Categories, including parent validation and kind cascades.
- Goals and revisions.
- Journal entries and revisions.
- Net worth snapshots and account balances.

### Transactions

- Listing with all filters.
- Counts with the same filter semantics as listing.
- Manual create/update/delete.
- Split create/remove.
- Bulk updates.
- Transfer pair and unpair.
- Category validation.
- Tag creation and assignment behavior.

### Imports

- Importer discovery.
- CSV preview.
- Amex `.xlsx` preview.
- Duplicate detection.
- Apply batch.
- Rollback batch.
- Net worth workbook import.

Importer ports should start with direct fixtures from
`backend/tests/test_importers.py` and add Java tests with the same examples.

### Rules

- Rule CRUD.
- Rule matching and ordered application.
- Rule reapply.
- Coverage.
- Proposal generation.
- Proposal backtesting.
- Proposal rejection.

### Budgets And Analytics

- Budget defaults and overrides.
- Budget report.
- Monthly analytics.
- Sankey.
- Recurring merchant detection.
- FIRE settings/projection.
- Reconcile read/update.
- Split summaries.

This should be the last major migration block because it depends on all earlier
domain semantics.

## Test Strategy

### Existing Tests

Keep the Python tests green until the launcher is switched. They protect the
current production behavior while Java catches up.

### Java Unit And Endpoint Tests

Add Java tests as each slice is ported:

- profile registry tests with temporary data directories;
- SQLite connection/PRAGMA tests;
- endpoint tests with MockMvc;
- importer fixture tests;
- analytics golden tests using the same sample data as Python.

### Contract Tests

Create a small set of JSON fixtures for key requests and responses. Run them
against Python first, then Java. Good first targets:

- `GET /api/health`
- accounts list/create/update/delete;
- categories parent validation;
- import preview for each sample CSV;
- budget report;
- Sankey analytics.

### Side-By-Side Parity

Before switching defaults, run both backends against copies of the same SQLite
profile and compare:

- response status;
- response JSON shape;
- money/date values;
- row counts and mutation side effects.

## Data Migration Strategy

No user-facing data migration should be needed at first. The Java backend
should read and write the same SQLite files.

Schema changes should be additive and compatible while both backends exist. If
Java introduces Flyway, initialize it from the current schema and use migrations
only for future changes. Avoid destructive migrations in the rewrite PR.

## Launcher Strategy

Keep the current Python launcher path as default during migration.

Add opt-in commands:

- `make backend-java`
- `make dev-java`
- `./run.sh --backend java`

Only switch default `make dev` and `./run.sh` after Java parity tests pass.

## PR Strategy

This rewrite branch should open a draft PR early once the scaffold and initial
tests exist. The PR description should include:

- current migrated scope;
- compatibility guarantees;
- remaining endpoint groups;
- verification commands;
- known gaps.

The PR can become ready for review when the Java backend supports the frontend
end-to-end and the Python backend is no longer required for routine use.
