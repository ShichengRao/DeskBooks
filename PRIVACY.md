# Privacy

The core app is local-only, and staying fully offline is always possible.

- No telemetry is collected.
- No analytics service is configured.
- The app itself (backend and UI) never sends account data anywhere. A test
  suite enforces that the backend cannot even import network libraries.
- Imported files and generated SQLite databases stay on your machine.
- Profile databases are stored in your operating system's user data directory
  by default, or in `PFA_DATA_DIR` if you set it.

## Optional connectors

The `automation/` directory contains optional, off-by-default connectors that
can fetch transactions and balances from institutions you choose, on a
schedule you configure. When you enable one:

- Data flows only between your machine and the hosts you pin for that
  connector (for example `api.teller.io`). A connector with no host allowlist
  refuses to run.
- Credentials and tokens live in the macOS Keychain, never in the repo or in
  config files.
- Fetched files land in a local staging directory and enter the app through
  the same preview / apply / rollback pipeline as manual imports, with an
  automatic pre-apply backup.
- Nothing is ever sent to the DeskBooks project or any third party you did
  not explicitly configure.

Normal setup can require internet access to download Python and Node
dependencies. With no connectors enabled, routine app usage does not need
internet access unless you click an account URL that you saved in the app.

Local data is plaintext. Treat SQLite files, staged downloads, and imported
documents as sensitive financial records.
