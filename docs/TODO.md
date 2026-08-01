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
- [ ] **Merchant-field rule matching** (`match_merchant_pattern`): some feeds
      put the merchant only in the merchant field with an unrelated
      description, which description-regex rules cannot see.
- [ ] **Fetch from the UI**: trigger the automation connectors from the app
      instead of the CLI (needs a design pass on how the backend spawns the
      Node runner).
- [ ] **Backup export/import artifact**: package a profile backup with any
      future uploaded artifacts if the app starts storing original files.

## Current Limitations

- No field-level undo for transaction edits; rollback exists only for whole
  import batches.
- Local SQLite data is plaintext. Use OS disk encryption for sensitive data.
- Bank/balance fetching exists only as optional local connectors under
  `automation/` (see `docs/AUTOMATED_IMPORTS.md`). Fetching is CLI-only —
  there is no fetch button in the UI yet.
- Dates are date-only. Importers discard time-of-day data when present.
