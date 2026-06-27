#!/usr/bin/env bash
# Single-command launcher: starts the backend, the Vite dev server, and
# opens the browser. Ctrl-C cleans both up.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

LOCAL_ENV="${DESKBOOKS_ENV_FILE:-$ROOT/.env.local}"
if [[ -f "$LOCAL_ENV" ]]; then
  # Local, gitignored machine settings. This is intentionally shell syntax so
  # paths with spaces can be quoted normally.
  # shellcheck disable=SC1090
  source "$LOCAL_ENV"
fi

FRONTEND_PORT="${PORT:-${FRONTEND_PORT:-5173}}"
BACKEND_PORT="${API_PORT:-${BACKEND_PORT:-}}"
OPEN_BROWSER="${OPEN_BROWSER:-1}"

usage() {
  cat <<'EOF'
Usage: ./run.sh [--port PORT] [--api-port PORT] [--data-dir PATH] [--no-open]

Examples:
  ./run.sh
  ./run.sh --port 5172
  ./run.sh --port 5172 --api-port 8766 --data-dir "$HOME/Library/Application Support/DeskBooks"
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--port)
      FRONTEND_PORT="${2:?missing port}"
      shift 2
      ;;
    --port=*)
      FRONTEND_PORT="${1#*=}"
      shift
      ;;
    -a|--api-port)
      BACKEND_PORT="${2:?missing api port}"
      shift 2
      ;;
    --api-port=*)
      BACKEND_PORT="${1#*=}"
      shift
      ;;
    --data-dir)
      export PFA_DATA_DIR="${2:?missing data dir}"
      shift 2
      ;;
    --data-dir=*)
      export PFA_DATA_DIR="${1#*=}"
      shift
      ;;
    --no-open)
      OPEN_BROWSER=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$BACKEND_PORT" ]]; then
  if [[ "$FRONTEND_PORT" == "5173" ]]; then
    BACKEND_PORT=8765
  else
    BACKEND_PORT=8766
  fi
fi

BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
  echo ""
  echo "stopping…"
  [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" 2>/dev/null || true
  [[ -n "$FRONTEND_PID" ]] && kill "$FRONTEND_PID" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

# --- prereqs ---
command -v uv >/dev/null 2>&1 || { echo "uv not found. Install: https://docs.astral.sh/uv/" >&2; exit 1; }
command -v npm >/dev/null 2>&1 || { echo "npm not found. Install Node.js first." >&2; exit 1; }

# --- backend ---
cd "$ROOT/backend"

if [[ ! -d .venv ]]; then
  echo "[setup] creating Python venv with uv…"
  uv venv --python 3.11 .venv
  uv pip install -e .
fi

# Bootstrap is idempotent and seeds generic starter data for empty profiles.
echo "[setup] bootstrapping active profile (idempotent)…"
uv run python -m app.bootstrap

echo "[backend] uvicorn http://127.0.0.1:${BACKEND_PORT} (auto-reload)"
PFA_ALLOW_SHUTDOWN=1 uv run uvicorn app.main:app --host 127.0.0.1 --port "$BACKEND_PORT" --log-level warning --reload --reload-dir app &
BACKEND_PID=$!

# --- frontend ---
cd "$ROOT/frontend"
if [[ ! -d node_modules ]]; then
  echo "[setup] installing frontend deps…"
  npm install --silent
fi

echo "[frontend] vite http://localhost:${FRONTEND_PORT}"
PFA_API_TARGET="http://127.0.0.1:${BACKEND_PORT}" npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT" --strictPort &
FRONTEND_PID=$!

# Wait for Vite to actually listen before opening the browser. Otherwise the
# first tab can hit a connection-refused on slow machines.
echo -n "[frontend] waiting for vite to listen…"
for _ in $(seq 1 60); do
  if (echo > "/dev/tcp/127.0.0.1/${FRONTEND_PORT}") 2>/dev/null; then
    echo " ready"
    break
  fi
  # Bail if the backend or frontend has already died.
  if ! kill -0 "$BACKEND_PID" 2>/dev/null || ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo " process exited before vite was ready"
    exit 1
  fi
  sleep 0.5
done

if [[ "$OPEN_BROWSER" != "0" ]] && command -v open >/dev/null 2>&1; then
  open "http://localhost:${FRONTEND_PORT}" || true
fi

while true; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "[backend] stopped"
    exit 0
  fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo "[frontend] stopped"
    exit 0
  fi
  sleep 1
done
