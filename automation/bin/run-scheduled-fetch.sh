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
  export DESKBOOKS_IMPORT_MANIFEST="$DEFAULT_STAGING_DIR/latest-manifest.jsonl"
  IMPORT_ARGS+=(--manifest "$DESKBOOKS_IMPORT_MANIFEST")
fi
if [[ -n "${DESKBOOKS_IMPORT_STAGING_DIR:-}" ]]; then
  IMPORT_ARGS+=(--staging-dir "$DESKBOOKS_IMPORT_STAGING_DIR")
fi
if [[ "${DESKBOOKS_IMPORT_APPLY:-0}" == "1" ]]; then
  IMPORT_ARGS+=(--apply)
fi
if [[ -n "${DESKBOOKS_IMPORT_SOURCE:-}" ]]; then
  IMPORT_ARGS+=(--source "$DESKBOOKS_IMPORT_SOURCE")
fi
if [[ "${DESKBOOKS_IMPORT_NO_BACKUP:-0}" == "1" ]]; then
  IMPORT_ARGS+=(--no-backup)
fi

case "${DESKBOOKS_IMPORT_BACKEND:-java}" in
  java)
    cd "$ROOT/backend-java"
    JAVA_GRADLE="${JAVA_GRADLE:-gradle}"
    [[ -n "${JAVA_GRADLE_USER_HOME:-}" ]] && export GRADLE_USER_HOME="$JAVA_GRADLE_USER_HOME"
    "$JAVA_GRADLE" automationImport
    ;;
  python)
    cd "$ROOT/backend"
    uv run python -m app.automation_import "${IMPORT_ARGS[@]}"
    ;;
  *)
    echo "DESKBOOKS_IMPORT_BACKEND must be java or python" >&2
    exit 2
    ;;
esac
