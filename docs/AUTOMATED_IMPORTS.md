# Optional Connectors & Automated Imports

DeskBooks is strictly local by default. This document covers the optional,
off-by-default connector pipeline that removes friction when you want it:
fetching transactions and account balances from institutions you configure,
on a schedule you control.

The design keeps the privacy boundary sharp:

1. **Only the Node automation layer touches the network.** The backend cannot
   even import HTTP libraries (`backend/tests/test_no_external_network.py`),
   and `automation/tests/network-guard.test.mjs` enforces that only
   `automation/src/connector-http.mjs` may open connections.
2. Connectors write **staged files** into a local staging directory and
   append entries to a manifest.
3. `python -m app.automation_import` (fully offline) previews those staged
   files with the same importer logic as the UI. With `--apply` it first
   creates a profile backup, then applies transactions as an import batch
   (rollback-able from the Backups/Import panels) and balances as net-worth
   snapshots.

Fail-closed rules: every connector must pin the hosts it may reach (missing
allowlist = refuse to run), browser helpers refuse dangerous financial-site
actions by label, fetchers must return non-empty files, and the importer
refuses files outside the staging directory. One failing connector no longer
aborts the run — other sources still stage and import, and the run exits
nonzero so the failure is visible in logs.

## Connector types

- **API connectors** (`"browser": false`) — talk to a provider's HTTPS API
  through `connector-http` (host-pinned, HTTPS-only, GET/JSON only).
  Shipped: **Teller** (`fetchers/teller.mjs`), free for personal use.
  Template: `fetchers/example-json-connector.mjs`.
- **Static file** (`fetchers/example-static-csv.mjs`) — stages a CSV you
  already have; useful for testing the pipeline end to end.
- **Browser template** (`fetchers/template-playwright-fetcher.mjs`) —
  experimental Playwright lane for institutions with no API. Requires an
  explicit host allowlist and is best used in `manual` style flows where you
  log in yourself. Expect breakage when sites change.

## Staged file formats

API connectors normalize provider data into two JSON formats
(`automation/src/staged-formats.mjs` builds them; the backend parses them):

```jsonc
// deskbooks.staged-transactions/v1 — one file per DeskBooks account
{
  "format": "deskbooks.staged-transactions/v1",
  "account_id": 3,
  "transactions": [
    { "id": "txn_x", "date": "2026-07-01", "description": "COFFEE",
      "amount": "-4.50", "pending": false, "post_date": null,
      "merchant": null }
  ]
}
```

Amounts are decimal **strings**, outflow-negative. Rows with
`"pending": true` are staged for visibility but skipped at import time
(pending rows can change date and amount when they post). The full provider
row is preserved on each imported transaction (`Transaction.raw`), including
the provider transaction id.

```jsonc
// deskbooks.staged-balances/v1 — one file per run
{
  "format": "deskbooks.staged-balances/v1",
  "as_of": "2026-07-30",
  "balances": [ { "account_id": 2, "balance": "1234.56" } ]
}
```

Balances become a net-worth snapshot dated `as_of`: created if missing,
merged if a snapshot for that date already exists (only changed values are
written, so re-applying is a no-op). Null balances are skipped — in
DeskBooks a NULL balance means "account did not exist yet", and a connector
must never assert that implicitly. Unknown account ids are reported and
skipped.

## Manifest

Each staged file gets one JSONL entry in `manifest.jsonl` (append-only
history) and `latest-manifest.jsonl` (this run only):

```json
{ "source": "teller", "kind": "statement", "account_id": 3,
  "importer_name": "staged_json", "path": "/…/2026-07-30-….json",
  "sha256": "…", "downloaded_at": "2026-07-30T09:00:00.000Z" }
```

`kind` is `statement` (CSV/XLSX/staged-JSON transactions) or `balances`
(`account_id`/`importer_name` are null — balance files carry per-row account
ids). Entries without `kind` are treated as statements, so old manifests
keep working.

## Setup

```sh
make install-automation          # only needed for browser connectors (Playwright)
cp automation/config.example.json automation/config.local.json
```

`config.local.json` is gitignored. Top-level keys: `stagingDir` (defaults to
`$PFA_DATA_DIR/import-staging`, falling back to the OS data dir — the fetch
and import halves always agree), `browserProfileDir`, `headless`, and
`sources[]`.

Per-source keys: `name`, `module`, `enabled`, `browser` (set `false` for API
connectors), plus whatever the fetcher documents. Statement-producing
sources need `accountId` + `importerName`; connectors that stage
per-account files (like Teller) map accounts themselves. **Browser sources
must set `allowedHosts` or `allowedHostSuffixes` — the runner refuses to
start them otherwise.**

## Teller setup (free, API-based)

Teller's developer tier is free for up to 100 bank connections. The live
API path follows their documented contract but has not been exercised
against a real enrollment yet — run your first fetches with a **sandbox**
token and preview-only (no `--apply`), and check the preview output before
trusting it. If a provider reports amounts with the opposite sign, set
`"invertAmounts": true` on the source.

1. Sign up at <https://teller.io/> and download your mTLS certificate pair.
   Put them somewhere private, e.g. `~/.config/deskbooks/teller/`.
2. Enroll your bank via Teller Connect to get an access token, then store it
   in the Keychain:

   ```sh
   automation/bin/store-keychain-password.sh DeskBooks.Teller teller
   ```

3. Discover account ids and map them to DeskBooks accounts:

   ```sh
   cd automation
   node bin/list-teller-accounts.mjs --cert ~/.config/deskbooks/teller/certificate.pem \
     --key ~/.config/deskbooks/teller/private_key.pem
   ```

4. Enable the `teller` source in `config.local.json` (see
   `config.example.json`) and fill in `certPath`, `keyPath`, and the
   `accounts` mapping (`tellerAccountId` → `deskbooksAccountId`).

Each run stages one transactions file per mapped account plus one balances
file, so scheduled runs keep both your transactions and your net-worth
series current.

## Running

```sh
make fetch-preview   # fetch + stage + preview (writes nothing to the DB)
make fetch-apply     # fetch + stage + backup + apply
```

Under the hood the wrapper runs `npm run fetch` and then
`uv run python -m app.automation_import` with `--manifest` pointed at the
latest run. Useful importer flags: `--manifest`, `--staging-dir`, `--state`,
`--source`, `--apply`, `--no-backup`.

## Scheduling (macOS launchd)

```sh
make schedule-fetch-preview   # weekly preview (Mon 09:00 by default)
make schedule-fetch-apply     # weekly fetch + apply
make unschedule-fetch
```

Schedule knobs: `DESKBOOKS_FETCH_WEEKDAY`/`HOUR`/`MINUTE`, or
`DESKBOOKS_FETCH_DAY` for monthly. Set `DESKBOOKS_IMPORT_MANIFEST`,
`DESKBOOKS_IMPORT_STAGING_DIR`, or `PFA_DATA_DIR` when installing and they
are baked into the job's environment. Logs go to `~/Library/Logs/DeskBooks/`.
Note: the login Keychain must be unlocked (user logged in) for connector
secrets to be readable; a locked-Keychain run fails closed.

## Verifying the automation layer

```sh
make test-automation   # node --test: runner, formats, connectors, network guard
cd automation && npm run check
```

The network guard rejects any module outside `connector-http.mjs` that
imports HTTP/socket libraries. It cannot see dynamic `import(expr)` or the
global `fetch()` — the PR template's outbound-network checkbox covers what
static analysis cannot.

## Data hygiene

- Staged downloads, manifests, and previews live under
  `<data dir>/import-staging/` with `0600` permissions where possible;
  treat the whole tree as sensitive financial data.
- Browser-page failure diagnostics (screenshot + HTML of a possibly
  logged-in page) are **off by default**; helpers only write them when a
  fetcher passes `{ enabled: true }`, and they are `0600`.
- `automation/bin/store-keychain-password.sh` passes the secret to
  `security` as an argument, which is briefly visible to local process
  listings while it runs.
