#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCAL_ENV="${DESKBOOKS_ENV_FILE:-$ROOT/.env.local}"
if [[ -f "$LOCAL_ENV" ]]; then
  # Keep automation pointed at the same profile/data directory as ./run.sh.
  # shellcheck disable=SC1090
  source "$LOCAL_ENV"
fi

CONFIG="${DESKBOOKS_FETCH_CONFIG:-$ROOT/automation/config.local.json}"
DEFAULT_STAGING_DIR="${PFA_DATA_DIR:-$HOME/Library/Application Support/DeskBooks}/import-staging"

cd "$ROOT/automation"
npm run fetch -- --config "$CONFIG"

IMPORT_ARGS=()
if [[ -n "${DESKBOOKS_IMPORT_MANIFEST:-}" ]]; then
  IMPORT_ARGS+=(--manifest "$DESKBOOKS_IMPORT_MANIFEST")
elif [[ -f "$DEFAULT_STAGING_DIR/latest-manifest.jsonl" ]]; then
  IMPORT_ARGS+=(--manifest "$DEFAULT_STAGING_DIR/latest-manifest.jsonl")
fi
if [[ -n "${DESKBOOKS_IMPORT_STAGING_DIR:-}" ]]; then
  IMPORT_ARGS+=(--staging-dir "$DESKBOOKS_IMPORT_STAGING_DIR")
fi
if [[ "${DESKBOOKS_IMPORT_APPLY:-0}" == "1" ]]; then
  IMPORT_ARGS+=(--apply)
fi

cd "$ROOT/backend"
uv run python -m app.automation_import "${IMPORT_ARGS[@]}"
