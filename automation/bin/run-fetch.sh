#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCAL_ENV="${DESKBOOKS_ENV_FILE:-$ROOT/.env.local}"
# .env.local provides defaults (so automation points at the same data
# directory as ./run.sh), but explicitly-set environment always wins —
# sourcing must never clobber a caller's PFA_DATA_DIR.
_PRESET_PFA_DATA_DIR="${PFA_DATA_DIR:-}"
if [[ -f "$LOCAL_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$LOCAL_ENV"
fi
if [[ -n "$_PRESET_PFA_DATA_DIR" ]]; then
  export PFA_DATA_DIR="$_PRESET_PFA_DATA_DIR"
fi

CONFIG="${DESKBOOKS_FETCH_CONFIG:-$ROOT/automation/config.local.json}"
DEFAULT_STAGING_DIR="${PFA_DATA_DIR:-$HOME/Library/Application Support/DeskBooks}/import-staging"

cd "$ROOT/automation"
# No npm install needed: the connector layer has zero dependencies.
node src/run-fetchers.mjs --config "$CONFIG"

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
# ${arr[@]+...} guard: macOS ships bash 3.2, where expanding an empty array
# under `set -u` is a fatal "unbound variable" error.
uv run python -m app.automation_import ${IMPORT_ARGS[@]+"${IMPORT_ARGS[@]}"}
