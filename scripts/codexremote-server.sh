#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$REPO_ROOT/apps/server"
ENV_FILE="${CODEXREMOTE_ENV_FILE:-$REPO_ROOT/.env.local}"
NODE_BIN="${NODE_BIN:-$(command -v node)}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

: "${CODEXREMOTE_PASSWORD:?Set CODEXREMOTE_PASSWORD or provide it in .env.local}"
export HOST="${HOST:-0.0.0.0}"
export PORT="${PORT:-31807}"

cd "$SERVER_DIR"
exec "$NODE_BIN" dist/server.js
