#!/usr/bin/env bash
set -euo pipefail

# Stores a connector secret in the macOS Keychain.
#
#   automation/bin/store-keychain-password.sh <service> [account]
#
# Examples:
#   automation/bin/store-keychain-password.sh DeskBooks.Teller teller
#
# The secret is prompted for silently and never echoed. Note: `security`
# receives the secret as an argument, so it is briefly visible to local
# process listings while the command runs.

SERVICE="${1:-}"
ACCOUNT="${2:-}"

if [[ -z "$SERVICE" ]]; then
  echo "usage: $0 <keychain-service> [account]" >&2
  exit 2
fi
if [[ -z "$ACCOUNT" ]]; then
  read -r -p "Account name for ${SERVICE}: " ACCOUNT
fi
if [[ -z "$ACCOUNT" ]]; then
  echo "account is required" >&2
  exit 2
fi

read -r -s -p "Secret for ${SERVICE} (${ACCOUNT}): " SECRET
echo ""
if [[ -z "$SECRET" ]]; then
  echo "secret is required" >&2
  exit 2
fi

security add-generic-password -U -s "$SERVICE" -a "$ACCOUNT" -w "$SECRET"
echo "Stored secret in macOS Keychain service=$SERVICE account=$ACCOUNT"
