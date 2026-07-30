# Automated Imports

DeskBooks can run a local, non-LLM import pipeline:

1. A deterministic Playwright fetcher downloads account exports into a staging
   directory.
2. The fetcher writes a manifest entry with the target DeskBooks account and
   importer name.
3. `python -m app.automation_import` previews those staged files with the same
   importer logic used by the UI.
4. If `--apply` is set, DeskBooks creates a profile backup and applies the
   import batch with duplicate skipping.

The default path is fail-closed. Fetchers must return at least one non-empty
file, guarded browser helpers refuse dangerous financial-site actions by label,
and the importer refuses files outside the staging directory.

## Setup

Run these commands from the repository root.
The scheduled wrapper sources `.env.local`, so `PFA_DATA_DIR` matches the
profile directory used by `./run.sh`.

Install the automation dependencies once:

```sh
make install-automation
```

Create a local config:

```sh
cp automation/config.example.json automation/config.local.json
```

Edit `automation/config.local.json` and enable one source at a time. The sample
source copies a synthetic CSV from `samples/`; real institutions should get
one dedicated fetcher module per institution.

## Dry Run

Fetch files and preview imports without changing the profile database:

```sh
automation/bin/run-scheduled-fetch.sh
```

The scheduled wrapper previews the latest fetch run by default. It also keeps a
long-lived `manifest.jsonl` history in the staging directory for auditability.
Preview mode writes `latest-preview.html` and `latest-preview.json` in the
staging directory.

To process a manifest directly:

```sh
cd backend
uv run python -m app.automation_import
```

## Chase Credit Card

The built-in Chase fetcher starts in manual-download capture mode. It opens a
dedicated Playwright browser profile, lets you log in and use Chase's own CSV
download flow, then stages the downloaded file for DeskBooks. The script does
not click Chase controls.

Set `"mode": "auto"` to let the fetcher click the account activity/download
controls using an already-authenticated remembered Chase browser session.
Set `"mode": "auto-login"` to let it retrieve your Chase password from macOS
Keychain and type it into Chase's login iframe. It will not enter 2FA codes; if
Chase shows a verification challenge, the run fails closed and writes
diagnostics in the source download directory.

Store the Chase password in macOS Keychain:

```sh
automation/bin/store-keychain-password.sh DeskBooks.Chase your-chase-username
```

Your local config should look like this, with `accountId` set to the DeskBooks
credit-card account:

```json
{
  "name": "chase_credit_manual",
  "enabled": true,
  "browser": true,
  "module": "./fetchers/chase-credit.mjs",
  "accountId": 8,
  "importerName": "chase_credit",
  "mode": "auto-login",
  "credentialService": "DeskBooks.Chase",
  "username": "your-chase-username",
  "accountText": "1234",
  "dateRangeDays": 365,
  "activityPreset": "choose a date range",
  "startUrl": "https://secure.chase.com/",
  "allowedHostSuffixes": ["chase.com"],
  "downloadTimeoutMs": 600000
}
```

Run `make fetch-preview`, log in to Chase in the opened browser, download the
credit-card activity CSV, and confirm that DeskBooks previews the rows. Once
that works reliably, keep using preview mode until you are comfortable enabling
`make fetch-apply`.

## Auto Apply

After a source has passed dry runs, opt into applying imports:

```sh
DESKBOOKS_IMPORT_APPLY=1 automation/bin/run-scheduled-fetch.sh
```

Each apply run creates a profile-scoped backup unless `--no-backup` is passed
to `app.automation_import`. Applied files are tracked by SHA-256 so rerunning
the same manifest does not create another batch.

## macOS Schedule

Install a weekly Monday 09:00 preview job:

```sh
make schedule-fetch-preview
```

Install the same schedule with auto-apply enabled:

```sh
make schedule-fetch-apply
```

Customize the schedule with launchd's weekday numbers, where Sunday is `0` and
Monday is `1`:

```sh
DESKBOOKS_FETCH_WEEKDAY=1 DESKBOOKS_FETCH_HOUR=9 DESKBOOKS_FETCH_MINUTE=0 make schedule-fetch-preview
```

Run monthly on a day of the month:

```sh
DESKBOOKS_FETCH_DAY=7 DESKBOOKS_FETCH_HOUR=9 DESKBOOKS_FETCH_MINUTE=0 make schedule-fetch-apply
```

Remove the scheduled job:

```sh
make unschedule-fetch
```

Logs are written to `~/Library/Logs/DeskBooks/fetch.out.log` and
`~/Library/Logs/DeskBooks/fetch.err.log`.
If you change `stagingDir` away from the default DeskBooks data directory,
set `DESKBOOKS_IMPORT_MANIFEST` and `DESKBOOKS_IMPORT_STAGING_DIR` in the
scheduled job as well.

## Writing A Fetcher

Copy `automation/fetchers/template-playwright-fetcher.mjs` and customize it for
one institution. Keep these rules:

- Let the user log in and handle 2FA in the persistent Playwright profile.
- Set `allowedHosts` in config for the institution.
- Use the guarded helpers from `src/fetcher-api.mjs`.
- Match export controls exactly. If the expected control is absent or appears
  more than once, fail instead of guessing.
- Never click payment, transfer, trading, profile, settings, password, or
  security controls.

Example source shape:

```json
{
  "name": "my_bank_checking",
  "enabled": true,
  "module": "./fetchers/my-bank-checking.mjs",
  "accountId": 1,
  "importerName": "wells_fargo_checking",
  "startUrl": "https://example.com/accounts/activity",
  "allowedHosts": ["example.com"]
}
```
