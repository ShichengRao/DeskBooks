# Roadmap

Focused follow-ups that still look valuable after the current local-first
version.

## High Value

- [ ] **Generic CSV mapping**: let users map arbitrary CSV columns to date,
      description, amount, post date, and sign convention, then save that
      mapping for future imports.

## Nice To Have

- [ ] **Full-text search** over transactions using SQLite FTS5 if normal
      description search becomes too slow or imprecise.
- [ ] **Native wrapper / packaged artifact**: Tauri or a simpler packaged
      backend + built frontend so non-developers do not need two dev servers.
- [ ] **Rule composition**: richer match logic than a single regex plus account
      and amount bounds.
- [ ] **Backup export/import artifact**: package a profile backup with any
      future uploaded artifacts if the app starts storing original files.

## Current Limitations

- No field-level undo for transaction edits; rollback exists only for whole
  import batches.
- Local SQLite data is plaintext. Use OS disk encryption for sensitive data.
- Snapshots are manual; there is no Plaid, brokerage, or bank integration.
- Dates are date-only. Importers discard time-of-day data when present.
