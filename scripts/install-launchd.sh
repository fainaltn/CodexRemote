#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
APP_SUPPORT_DIR="$HOME/Library/Application Support/CodexRemote/launchd"
LOG_DIR="$HOME/Library/Logs/CodexRemote"
SERVER_PLIST="$LAUNCH_AGENTS_DIR/dev.codexremote.server.plist"
WEB_PLIST="$LAUNCH_AGENTS_DIR/dev.codexremote.web.plist"
SERVER_LAUNCHER="$APP_SUPPORT_DIR/dev.codexremote.server.sh"
WEB_LAUNCHER="$APP_SUPPORT_DIR/dev.codexremote.web.sh"
DOMAIN="gui/$(id -u)"
ENV_FILE="${CODEXREMOTE_ENV_FILE:-$REPO_ROOT/.env.local}"
NODE_BIN="${NODE_BIN:-$(command -v node)}"
HOST_VALUE="${HOST:-0.0.0.0}"
PORT_VALUE="${PORT:-31807}"
PATH_VALUE="${PATH}"
WEB_API_URL_VALUE="${CODEXREMOTE_API_URL:-}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

: "${CODEXREMOTE_PASSWORD:?Set CODEXREMOTE_PASSWORD or provide it in .env.local}"
HOST_VALUE="${HOST:-$HOST_VALUE}"
PORT_VALUE="${PORT:-$PORT_VALUE}"

if [[ -z "$WEB_API_URL_VALUE" ]]; then
  case "$HOST_VALUE" in
    ""|"0.0.0.0"|"::")
      WEB_API_URL_VALUE="http://127.0.0.1:${PORT_VALUE}"
      ;;
    *)
      WEB_API_URL_VALUE="http://${HOST_VALUE}:${PORT_VALUE}"
      ;;
  esac
fi

mkdir -p "$LAUNCH_AGENTS_DIR"
mkdir -p "$APP_SUPPORT_DIR"
mkdir -p "$LOG_DIR"

cat > "$SERVER_LAUNCHER" <<EOF
#!/bin/zsh
set -euo pipefail

REPO_ROOT="$REPO_ROOT"
SERVER_DIR="\$REPO_ROOT/apps/server"
NODE_BIN="${NODE_BIN}"

: "\${CODEXREMOTE_PASSWORD:?Set CODEXREMOTE_PASSWORD or provide it in .env.local}"
export HOST="\${HOST:-$HOST_VALUE}"
export PORT="\${PORT:-$PORT_VALUE}"

cd "\$SERVER_DIR"
exec "\$NODE_BIN" dist/server.js
EOF

cat > "$WEB_LAUNCHER" <<EOF
#!/bin/zsh
set -euo pipefail

REPO_ROOT="$REPO_ROOT"
WEB_DIR="\$REPO_ROOT/apps/web"
NODE_BIN="${NODE_BIN}"
NEXT_BIN="\$REPO_ROOT/node_modules/next/dist/bin/next"

cd "\$WEB_DIR"
exec "\$NODE_BIN" "\$NEXT_BIN" start -p 31817
EOF

chmod +x "$SERVER_LAUNCHER" "$WEB_LAUNCHER"

sed \
  -e "s|__NODE_BIN__|$NODE_BIN|g" \
  -e "s|__REPO_ROOT__|$REPO_ROOT|g" \
  -e "s|__ENV_FILE__|$ENV_FILE|g" \
  -e "s|__HOME_DIR__|$HOME|g" \
  -e "s|__LOG_DIR__|$LOG_DIR|g" \
  -e "s|__SERVER_LAUNCHER__|$SERVER_LAUNCHER|g" \
  -e "s|__CODEXREMOTE_PASSWORD__|$CODEXREMOTE_PASSWORD|g" \
  -e "s|__HOST__|$HOST_VALUE|g" \
  -e "s|__PATH__|$PATH_VALUE|g" \
  -e "s|__PORT__|$PORT_VALUE|g" \
  "$REPO_ROOT/infra/launchd/dev.codexremote.server.plist" > "$SERVER_PLIST"

sed \
  -e "s|__NODE_BIN__|$NODE_BIN|g" \
  -e "s|__REPO_ROOT__|$REPO_ROOT|g" \
  -e "s|__ENV_FILE__|$ENV_FILE|g" \
  -e "s|__HOME_DIR__|$HOME|g" \
  -e "s|__LOG_DIR__|$LOG_DIR|g" \
  -e "s|__CODEXREMOTE_API_URL__|$WEB_API_URL_VALUE|g" \
  -e "s|__WEB_LAUNCHER__|$WEB_LAUNCHER|g" \
  -e "s|__PATH__|$PATH_VALUE|g" \
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
