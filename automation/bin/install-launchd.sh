#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LABEL="${DESKBOOKS_LAUNCHD_LABEL:-com.deskbooks.fetch}"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
APPLY="${DESKBOOKS_IMPORT_APPLY:-0}"
DAY="${DESKBOOKS_FETCH_DAY:-}"
WEEKDAY="${DESKBOOKS_FETCH_WEEKDAY:-1}"
HOUR="${DESKBOOKS_FETCH_HOUR:-9}"
MINUTE="${DESKBOOKS_FETCH_MINUTE:-0}"
LOG_DIR="${DESKBOOKS_FETCH_LOG_DIR:-$HOME/Library/Logs/DeskBooks}"
PATH_VALUE="${PATH:-/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin}"

mkdir -p "$HOME/Library/LaunchAgents" "$LOG_DIR"

# Optional overrides documented in docs/AUTOMATED_IMPORTS.md: when set at
# install time they are baked into the job's environment, so the scheduled
# run resolves the same manifest/staging/env paths as the shell you tested in.
EXTRA_ENV_XML=""
for VAR in DESKBOOKS_IMPORT_MANIFEST DESKBOOKS_IMPORT_STAGING_DIR DESKBOOKS_ENV_FILE PFA_DATA_DIR; do
  VALUE="${!VAR:-}"
  if [[ -n "$VALUE" ]]; then
    EXTRA_ENV_XML+="    <key>${VAR}</key>
    <string>${VALUE}</string>
"
  fi
done

if [[ -n "$DAY" ]]; then
  CALENDAR_XML="    <key>Day</key>
    <integer>${DAY}</integer>
    <key>Hour</key>
    <integer>${HOUR}</integer>
    <key>Minute</key>
    <integer>${MINUTE}</integer>"
  SCHEDULE_LABEL="day=${DAY} hour=${HOUR} minute=${MINUTE}"
else
  CALENDAR_XML="    <key>Weekday</key>
    <integer>${WEEKDAY}</integer>
    <key>Hour</key>
    <integer>${HOUR}</integer>
    <key>Minute</key>
    <integer>${MINUTE}</integer>"
  SCHEDULE_LABEL="weekday=${WEEKDAY} hour=${HOUR} minute=${MINUTE}"
fi

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${LABEL}</string>

  <key>ProgramArguments</key>
  <array>
    <string>${ROOT}/automation/bin/run-scheduled-fetch.sh</string>
  </array>

  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>${PATH_VALUE}</string>
    <key>DESKBOOKS_FETCH_CONFIG</key>
    <string>${ROOT}/automation/config.local.json</string>
    <key>DESKBOOKS_IMPORT_APPLY</key>
    <string>${APPLY}</string>
${EXTRA_ENV_XML}  </dict>

  <key>StartCalendarInterval</key>
  <dict>
${CALENDAR_XML}
  </dict>

  <key>StandardOutPath</key>
  <string>${LOG_DIR}/fetch.out.log</string>
  <key>StandardErrorPath</key>
  <string>${LOG_DIR}/fetch.err.log</string>
</dict>
</plist>
EOF

launchctl unload "$PLIST" >/dev/null 2>&1 || true
launchctl load "$PLIST"

echo "Installed ${LABEL}"
echo "Schedule: ${SCHEDULE_LABEL} apply=${APPLY}"
echo "Plist: ${PLIST}"
echo "Logs: ${LOG_DIR}/fetch.out.log and ${LOG_DIR}/fetch.err.log"
