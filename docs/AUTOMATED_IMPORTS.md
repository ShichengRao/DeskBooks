# Optional Connectors & Automated Imports

DeskBooks is strictly local by default. This document covers the optional,
off-by-default connector pipeline that removes friction when you want it:
fetching transactions and account balances from institutions you configure.

The design keeps the privacy boundary sharp:

1. **Only the Node automation layer touches the network.** The backend cannot
   even import HTTP libraries (`backend/tests/test_no_external_network.py`),
   and `automation/tests/network-guard.test.mjs` enforces that only
   `automation/src/connector-http.mjs` may open connections — HTTPS GETs of
   JSON against an explicit host allowlist, nothing else. Connectors are
   API/file based; there is no browser automation.
2. Connectors write **staged files** into a local staging directory and
   append entries to a manifest.
3. `python -m app.automation_import` (fully offline) previews those staged
   files with the same importer logic as the UI. With `--apply` it first
   creates a profile backup, then applies transactions as an import batch
   (rollback-able from the Import panel) and balances as net-worth
   snapshots.

Additional fail-closed rules: fetchers must return non-empty files, the
importer refuses files outside the staging directory, and duplicate
detection plus sha256 idempotency make re-runs safe. One failing connector
does not abort the run — other sources still stage and import, and the run
exits nonzero so the failure is visible.

The connector layer has **zero npm dependencies**; nothing needs installing.

## Staged file formats

Connectors normalize provider data into two JSON formats
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
cp automation/config.example.json automation/config.local.json
```

`config.local.json` is gitignored. Top-level keys: `stagingDir` (defaults to
`$PFA_DATA_DIR/import-staging`, falling back to the OS data dir — the fetch
and import halves always agree) and `sources[]`. Per-source keys: `name`,
`module`, `enabled`, plus whatever the fetcher documents.

## Teller setup (free, API-based)

Teller's developer tier is free for up to 100 bank connections. The live
API path follows their documented contract but has not been exercised
against a real enrollment yet — run your first fetches with a **sandbox**
token and preview-only (no apply), and check the preview output before
trusting it. If a provider reports amounts with the opposite sign, set
`"invertAmounts": true` on the source.

1. Sign up at <https://teller.io/> and download your mTLS certificate pair.
2. Enroll your bank via Teller Connect to get an access token.
3. Put all three in a private directory:

   ```sh
   mkdir -p ~/.config/deskbooks/teller && chmod 700 ~/.config/deskbooks/teller
   # certificate.pem and private_key.pem from Teller, then:
   printf '%s' 'token_...' > ~/.config/deskbooks/teller/access-token
   chmod 600 ~/.config/deskbooks/teller/*
   ```

4. Discover account ids and map them to DeskBooks accounts:

   ```sh
   cd automation
   node bin/list-teller-accounts.mjs \
     --cert ~/.config/deskbooks/teller/certificate.pem \
     --key ~/.config/deskbooks/teller/private_key.pem \
     --token ~/.config/deskbooks/teller/access-token
   ```

5. Enable the `teller` source in `config.local.json` (see
   `config.example.json`) and fill in `certPath`, `keyPath`, `tokenPath`,
   and the `accounts` mapping (`tellerAccountId` → `deskbooksAccountId`).

Each run stages one transactions file per mapped account plus one balances
file, so runs keep both your transactions and your net-worth series current.

## Running

```sh
make fetch-preview   # fetch + stage + preview (writes nothing to the DB)
make fetch-apply     # fetch + stage + backup + apply
```

Under the hood `automation/bin/run-fetch.sh` runs the fetchers and then
`uv run python -m app.automation_import` with `--manifest` pointed at the
latest run. Useful importer flags: `--manifest`, `--staging-dir`, `--state`,
`--source`, `--apply`, `--no-backup`.

There is no built-in scheduler; run the make targets when you want fresh
data, or wire `make fetch-apply` into cron/launchd yourself if you want it
periodic.

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
- Connector credentials are files you manage (`chmod 600`), outside the
  repo, referenced by path from the untracked `config.local.json`.
