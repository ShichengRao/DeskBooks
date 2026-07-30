#!/usr/bin/env bash
set -euo pipefail

LABEL="${DESKBOOKS_LAUNCHD_LABEL:-com.deskbooks.fetch}"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"

launchctl unload "$PLIST" >/dev/null 2>&1 || true
rm -f "$PLIST"

echo "Uninstalled ${LABEL}"
