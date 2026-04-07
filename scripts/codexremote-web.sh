#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
WEB_DIR="$REPO_ROOT/apps/web"
ENV_FILE="${CODEXREMOTE_ENV_FILE:-$REPO_ROOT/.env.local}"
NODE_BIN="${NODE_BIN:-$(command -v node)}"
NEXT_BIN="$REPO_ROOT/node_modules/next/dist/bin/next"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

cd "$WEB_DIR"
exec "$NODE_BIN" "$NEXT_BIN" start -p 31817
