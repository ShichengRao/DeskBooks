#!/usr/bin/env bash
set -euo pipefail

SERVICE="${1:-DeskBooks.Chase}"
ACCOUNT="${2:-${DESKBOOKS_CHASE_USERNAME:-}}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG="${DESKBOOKS_FETCH_CONFIG:-$ROOT/automation/config.local.json}"

if [[ -z "$ACCOUNT" ]]; then
  read -r -p "Chase username: " ACCOUNT
fi
if [[ -z "$ACCOUNT" ]]; then
  echo "username is required" >&2
  exit 2
fi

read -r -s -p "Chase password: " PASSWORD
echo ""
if [[ -z "$PASSWORD" ]]; then
  echo "password is required" >&2
  exit 2
fi

security add-generic-password -U -s "$SERVICE" -a "$ACCOUNT" -w "$PASSWORD"
echo "Stored password in macOS Keychain service=$SERVICE account=$ACCOUNT"

if [[ -f "$CONFIG" ]]; then
  CONFIG_PATH="$CONFIG" SERVICE_VALUE="$SERVICE" ACCOUNT_VALUE="$ACCOUNT" node <<'JS'
const fs = require("fs");

const configPath = process.env.CONFIG_PATH;
const service = process.env.SERVICE_VALUE;
const account = process.env.ACCOUNT_VALUE;
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));

for (const source of config.sources ?? []) {
  if (source.module === "./fetchers/chase-credit.mjs" || source.importerName === "chase_credit") {
    source.credentialService = service;
    source.username = account;
  }
}

fs.writeFileSync(configPath, `${JSON.stringify(config, null, 2)}\n`);
JS
  echo "Updated $CONFIG with the Chase username and Keychain service"
fi
