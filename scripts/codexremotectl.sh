#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
UID_NUM="$(id -u)"
DOMAIN="gui/$UID_NUM"
SERVER_LABEL="$DOMAIN/dev.codexremote.server"
WEB_LABEL="$DOMAIN/dev.codexremote.web"
SERVER_PLIST="$HOME/Library/LaunchAgents/dev.codexremote.server.plist"
WEB_PLIST="$HOME/Library/LaunchAgents/dev.codexremote.web.plist"

usage() {
  echo "Usage: $0 {status|restart|stop|start|logs}"
  exit 1
}

[[ $# -eq 1 ]] || usage

case "$1" in
  status)
    echo "== server =="
    launchctl print "$SERVER_LABEL" | sed -n '1,40p' || true
    echo
    echo "== web =="
    launchctl print "$WEB_LABEL" | sed -n '1,40p' || true
    ;;
  restart)
    launchctl kickstart -k "$SERVER_LABEL"
    launchctl kickstart -k "$WEB_LABEL"
    ;;
  stop)
    launchctl bootout "$DOMAIN" "$SERVER_PLIST" >/dev/null 2>&1 || true
    launchctl bootout "$DOMAIN" "$WEB_PLIST" >/dev/null 2>&1 || true
    ;;
  start)
    launchctl bootstrap "$DOMAIN" "$SERVER_PLIST"
    launchctl bootstrap "$DOMAIN" "$WEB_PLIST"
    ;;
  logs)
    tail -n 80 "$REPO_ROOT/.run/server.log"
    echo
    echo "----"
    echo
    tail -n 80 "$REPO_ROOT/.run/web.log"
    ;;
  *)
    usage
    ;;
esac
