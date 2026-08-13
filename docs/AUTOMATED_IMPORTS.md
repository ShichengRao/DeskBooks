# Optional Connectors & Automated Imports

DeskBooks is strictly local by default. This document covers the optional,
off-by-default connector pipeline that removes friction when you want it:
fetching transactions and account balances from institutions you configure.

The design keeps the privacy boundary sharp:

1. **Only the Node automation layer touches the network.** The backend cannot
   even import HTTP libraries (`backend/tests/test_no_external_network.py`),
   and `automation/tests/network-guard.test.mjs` enforces that only
   `automation/src/connector-http.mjs` may open connections — HTTPS GET/POST
   of JSON against an explicit host allowlist, nothing else. Connectors are
   API/file based; there is no browser automation.
2. Connectors write **staged files** into a local staging directory and
   append entries to a manifest.
3. `python -m app.automation_import` (fully offline) previews those staged
   files with the same importer logic as the UI. With `--apply` it first
   creates a profile backup, then applies transactions as an import batch
   (rollback-able from the Import panel) and balances as net-worth
   snapshots.
4. The **Import page** shows the same staged files ("Staged from
   connectors"): per-file status for the active profile (new rows, already
   imported, staged for another profile, unknown account), one-click import
   per file or **Import all new** — the same backup + duplicate + empty-file
   contract as the CLI, still fully offline. Only the fetch itself needs the
   command line.

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

That id is what duplicate detection matches on, and it matters: a
provider may revise a transaction's date after you have already imported
it (Plaid backfills `authorized_date` a day or three after a card
transaction posts, which moves the reported date earlier). Matching on
date + amount + description alone would read the revised row as a new
transaction and import it a second time. Rows from sources that carry no
id — CSV and XLSX — still match on that key, counted so that genuine
same-day, same-amount repeats (several identical transit fares) are kept
rather than collapsed. A re-import keeps the row you already have; it
does not rewrite its date with the provider's revision.

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

Both formats accept an optional `"profile"` — a DeskBooks profile slug.
Account ids only mean something inside one profile's database, so a stamped
file is skipped by `automation_import` (and by the snapshot editor's
fill-from-connections prefill) whenever a different profile is active; it
stays pending until its profile is active again. Unstamped files keep the
old behavior, but the prefill still only offers account ids that exist in
the active profile. Stamp your files by setting `profile` in the fetch
config (below).

## Manifest

Each staged file gets one JSONL entry in `manifest.jsonl` (append-only
history) and `latest-manifest.jsonl` (this run only):

```json
{ "source": "plaid_mybank", "kind": "statement", "account_id": 3,
  "importer_name": "staged_json", "profile": "personal",
  "path": "/…/2026-07-30-….json",
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
and import halves always agree), `profile` (recommended: the profile slug
your account-id mappings belong to; stamps every staged file and manifest
entry so imports and prefill refuse to touch the wrong profile), and
`sources[]`. Per-source keys: `name`, `module`, `enabled`, an optional
`profile` override, plus whatever the fetcher documents.

## Plaid setup (free Trial plan)

Plaid's Trial plan (teams created on/after 2026-04-15) is free and supports
up to 10 production Items — one Item is one bank login — including the big
OAuth institutions. Every user brings their own Plaid keys: nothing shared
ships in this repo, so one person's usage can never bill another's account.
Trust note: with this connector your bank data flows through Plaid (the
aggregator you signed up with), between Plaid and your machine only.

The live API path follows Plaid's documented contract but has not been
exercised against a real Item yet — run your first fetches with
`"environment": "sandbox"` and preview-only (no apply), and check the
preview output before trusting it. Sign convention is normalized
automatically (Plaid reports outflows as positive; DeskBooks stores them
negative); `"invertAmounts": true` exists only for institutions that
misreport against Plaid's own convention.

1. Create a team at <https://dashboard.plaid.com/> and copy your
   `client_id` and the sandbox (later production) `secret` into private
   files:

   ```sh
   mkdir -p ~/.config/deskbooks/plaid && chmod 700 ~/.config/deskbooks/plaid
   printf '%s' 'your-client-id' > ~/.config/deskbooks/plaid/client-id
   printf '%s' 'your-secret'    > ~/.config/deskbooks/plaid/secret
   chmod 600 ~/.config/deskbooks/plaid/*
   ```

2. Link a bank from the CLI (Hosted Link — the script prints a URL you
   complete in your browser, then writes the access token):

   ```sh
   cd automation
   node bin/plaid-link-setup.mjs \
     --client-id ~/.config/deskbooks/plaid/client-id \
     --secret ~/.config/deskbooks/plaid/secret \
     --env sandbox \
     --out ~/.config/deskbooks/plaid/access-token-mybank
   ```

   Sandbox tip: pick any institution and log in with `user_good` /
   `pass_good`.

   Add `--products transactions,investments` if the login holds a
   brokerage, IRA, 401(k), or donor-advised fund — see
   [Investment accounts](#investment-accounts). Consent is far cheaper to
   grant now than to add later.

3. Discover account ids and map them to DeskBooks accounts:

   ```sh
   node bin/list-plaid-accounts.mjs \
     --client-id ~/.config/deskbooks/plaid/client-id \
     --secret ~/.config/deskbooks/plaid/secret \
     --env sandbox \
     --access-token ~/.config/deskbooks/plaid/access-token-mybank
   ```

4. Enable the `plaid_mybank` source in `config.local.json` (see
   `config.example.json`) and fill in `environment`, the three credential
   paths, and the `accounts` mapping
   (`plaidAccountId` → `deskbooksAccountId`). One source per Item; add
   more sources for more banks.

Each run stages one transactions file per mapped account plus one balances
file, so runs keep both your transactions and your net-worth series current.

These knobs are worth knowing:

- `lookbackDays` is the whole history control — the fetcher asks Plaid for
  `[today - lookbackDays, today]`, with no cursor, so nothing older than
  that window is ever staged. Set it to `0` on a first run to start an
  account from today with no back history, then raise it to at least your
  run cadence (a window shorter than the gap between runs silently drops
  the days in between). Re-fetching an overlapping window is safe:
  duplicate detection matches on the provider transaction id.
- `"balances": false` on an individual account mapping stages that
  account's transactions but never its balance, keeping it out of the
  net-worth series (net worth is the sum of snapshot balance rows).
  Donor-advised funds are the motivating case: the giving belongs in your
  spending history, the balance is money you no longer own.
- `"transactions": false` is the mirror: the account's balance keeps
  feeding net worth, but none of its rows are staged. Useful once
  `investments` is on, since that switch covers every account in the Item
  and a retirement plan's dividend reinvestments are rarely worth
  importing to reach the one account whose activity you wanted. Setting
  both to `false` on the same mapping is an error — it would fetch
  nothing.

## Investment accounts

Plaid splits transaction history across two endpoints by account type, and
this is the single most confusing thing about the connector:
`/transactions/get` covers depository and credit accounts and returns an
**empty list, not an error**, for a brokerage, IRA, 401(k), or
donor-advised fund. An investment account left on the default settings
therefore stages zero rows on every run and looks exactly like an account
with no activity.

Set `"investments": true` on the source to additionally read
`/investments/transactions/get`, which is where those accounts report.
Rows from both endpoints merge into the same per-account staged files, so
an Item holding a checking account beside a brokerage needs nothing
special. Buys arrive outflow-negative and sells positive, the same
convention as everything else; where Plaid names a security whose ticker
is not already in the description, the ticker is appended.

The Item must have consented to the `investments` product. Consent is
granted at link time, so an Item linked without it fails with
`ADDITIONAL_CONSENT_REQUIRED` until you re-consent. Do that in **update
mode**, which keeps the Item and its provider account ids so the mappings
in `config.local.json` stay valid:

```sh
node bin/plaid-link-setup.mjs \
  --client-id ~/.config/deskbooks/plaid/client-id \
  --secret ~/.config/deskbooks/plaid/secret-production \
  --env production \
  --products transactions,investments \
  --access-token ~/.config/deskbooks/plaid/access-token-mybank
```

Re-linking from scratch instead (`--out`) issues a new Item with fresh
provider account ids, silently orphaning every mapping that referenced the
old ones — and spends another of your ten Trial Items. New Items can take
`--products transactions,investments` up front to avoid the round trip.

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
