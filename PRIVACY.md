# Privacy

This app is local-only.

- No telemetry is collected.
- No analytics service is configured.
- No account data is sent to a hosted service by the app.
- Imported files and generated SQLite databases stay on your machine.
- Profile databases are stored in your operating system's user data directory
  by default, or in `PFA_DATA_DIR` if you set it.

Normal setup can require internet access to download Python and Node
dependencies. After dependencies are installed, routine app usage does not need
internet access unless you click an account URL that you saved in the app.

Local data is plaintext. Treat SQLite files and imported documents as sensitive
financial records.
