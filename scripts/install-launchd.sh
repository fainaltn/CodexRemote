#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
SERVER_PLIST="$LAUNCH_AGENTS_DIR/dev.codexremote.server.plist"
WEB_PLIST="$LAUNCH_AGENTS_DIR/dev.codexremote.web.plist"
DOMAIN="gui/$(id -u)"
ENV_FILE="${CODEXREMOTE_ENV_FILE:-$REPO_ROOT/.env.local}"
NODE_BIN="${NODE_BIN:-$(command -v node)}"
HOST_VALUE="${HOST:-0.0.0.0}"
PORT_VALUE="${PORT:-31807}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

: "${CODEXREMOTE_PASSWORD:?Set CODEXREMOTE_PASSWORD or provide it in .env.local}"
HOST_VALUE="${HOST:-$HOST_VALUE}"
PORT_VALUE="${PORT:-$PORT_VALUE}"

mkdir -p "$LAUNCH_AGENTS_DIR"
mkdir -p "$REPO_ROOT/.run"

sed \
  -e "s|__NODE_BIN__|$NODE_BIN|g" \
  -e "s|__REPO_ROOT__|$REPO_ROOT|g" \
  -e "s|__CODEXREMOTE_PASSWORD__|$CODEXREMOTE_PASSWORD|g" \
  -e "s|__HOST__|$HOST_VALUE|g" \
  -e "s|__PORT__|$PORT_VALUE|g" \
  "$REPO_ROOT/infra/launchd/dev.codexremote.server.plist" > "$SERVER_PLIST"

sed \
  -e "s|__NODE_BIN__|$NODE_BIN|g" \
  -e "s|__REPO_ROOT__|$REPO_ROOT|g" \
  "$REPO_ROOT/infra/launchd/dev.codexremote.web.plist" > "$WEB_PLIST"

launchctl bootout "$DOMAIN" "$SERVER_PLIST" >/dev/null 2>&1 || true
launchctl bootout "$DOMAIN" "$WEB_PLIST" >/dev/null 2>&1 || true

launchctl bootstrap "$DOMAIN" "$SERVER_PLIST"
launchctl bootstrap "$DOMAIN" "$WEB_PLIST"

launchctl enable "$DOMAIN/dev.codexremote.server"
launchctl enable "$DOMAIN/dev.codexremote.web"

echo "Installed and started:"
echo "  dev.codexremote.server"
echo "  dev.codexremote.web"
