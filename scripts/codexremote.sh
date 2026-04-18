#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${CODEXREMOTE_ENV_FILE:-$REPO_ROOT/.env.local}"
SERVER_DIST="$REPO_ROOT/apps/server/dist/server.js"
WEB_BUILD_ID="$REPO_ROOT/apps/web/.next/BUILD_ID"
WEB_ROUTES_MANIFEST="$REPO_ROOT/apps/web/.next/routes-manifest.json"
WEB_NEXT_BIN="$REPO_ROOT/node_modules/next/dist/bin/next"
LOG_DIR="$HOME/Library/Logs/CodexRemote"
NODE_BIN="${NODE_BIN:-$(command -v node)}"

usage() {
  cat <<'EOF'
Usage: ./scripts/codexremote.sh <command>

Commands:
  up       Build missing artifacts if needed, then install/start local launchd services
  pair     Create and print a one-time pairing code for the local server
  status   Show launchd service state
  logs     Tail recent launchd logs
  restart  Restart launchd services
  web      Open the local web console in the default browser
  doctor   Validate the local CodexRemote environment
EOF
  exit 1
}

fail() {
  echo "[codexremote] $*" >&2
  exit 1
}

warn() {
  echo "[codexremote] $*"
}

open_url() {
  local url="$1"
  if command -v open >/dev/null 2>&1; then
    open "$url"
    return 0
  fi
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$url"
    return 0
  fi
  return 1
}

ensure_env_loaded() {
  if [[ -f "$ENV_FILE" ]]; then
    set -a
    source "$ENV_FILE"
    set +a
  fi
}

expected_web_api_url() {
  local host_value="${HOST:-0.0.0.0}"
  local port_value="${PORT:-31807}"

  if [[ -n "${CODEXREMOTE_API_URL:-}" ]]; then
    printf '%s\n' "$CODEXREMOTE_API_URL"
    return 0
  fi

  case "$host_value" in
    ""|"0.0.0.0"|"::")
      printf 'http://127.0.0.1:%s\n' "$port_value"
      ;;
    *)
      printf 'http://%s:%s\n' "$host_value" "$port_value"
      ;;
  esac
}

local_api_url() {
  local candidate
  candidate="$(expected_web_api_url)"
  printf '%s\n' "$candidate"
}

web_build_matches_runtime() {
  [[ -f "$WEB_ROUTES_MANIFEST" ]] || return 1

  local expected_url
  expected_url="$(expected_web_api_url)"

  EXPECTED_WEB_API_URL="$expected_url" "$NODE_BIN" --input-type=module -e '
    import { readFileSync } from "node:fs";

    const manifestPath = process.argv[1];
    const expectedUrl = process.env.EXPECTED_WEB_API_URL;
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    const rewrites = [
      ...(manifest.rewrites?.beforeFiles ?? []),
      ...(manifest.rewrites?.afterFiles ?? []),
      ...(manifest.rewrites?.fallback ?? []),
    ];
    const apiRewrite = rewrites.find((entry) => entry.source === "/api/:path*");

    if (apiRewrite?.destination === `${expectedUrl}/api/:path*`) {
      process.exit(0);
    }

    process.exit(1);
  ' "$WEB_ROUTES_MANIFEST"
}

require_darwin() {
  [[ "$(uname -s)" == "Darwin" ]] || fail "This command currently expects macOS launchd."
}

ensure_builds() {
  local built_any=0

  if [[ ! -f "$SERVER_DIST" ]]; then
    warn "Missing server build artifact, running server build..."
    (cd "$REPO_ROOT" && npm run build --workspace @codexremote/shared && npm run build --workspace @codexremote/server)
    built_any=1
  fi

  if [[ ! -f "$WEB_BUILD_ID" ]]; then
    warn "Missing web build artifact, running web build..."
    (cd "$REPO_ROOT" && npm run build --workspace @codexremote/web)
    built_any=1
  elif ! web_build_matches_runtime; then
    warn "Web build API target is stale, rebuilding web..."
    (cd "$REPO_ROOT" && npm run build --workspace @codexremote/web)
    built_any=1
  fi

  if [[ "$built_any" -eq 0 ]]; then
    warn "Build artifacts already present."
  fi
}

wait_for_server() {
  local base_url
  local url
  local attempts=0

  base_url="$(local_api_url)"
  url="${base_url}/api/health"

  while [[ "$attempts" -lt 30 ]]; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    attempts=$((attempts + 1))
  done

  return 1
}

print_pairing_code() {
  local base_url
  local url
  local response

  base_url="$(local_api_url)"
  url="${base_url}/api/pairing/code"

  if ! wait_for_server; then
    fail "The local server did not become ready in time. Run ./scripts/codexremote.sh status or logs to inspect it."
  fi

  response="$(curl -fsS -X POST "$url")" || fail "Could not obtain a pairing code from the local server."

  printf '%s' "$response" | "$NODE_BIN" --input-type=module -e '
    import { readFileSync } from "node:fs";
    const raw = readFileSync(process.stdin.fd, "utf8");
    const data = JSON.parse(raw);
    console.log("[codexremote] Pairing code ready");
    console.log(`code: ${data.code}`);
    console.log(`expiresAt: ${data.expiresAt}`);
    console.log("Android: choose the pairing flow, enter this code, then reconnect later with the saved trusted credentials.");
  '
}

run_doctor() {
  local issues=0

  echo "== CodexRemote doctor =="
  echo "repo: $REPO_ROOT"
  echo "env:  $ENV_FILE"
  echo

  if [[ -f "$ENV_FILE" ]]; then
    echo "[ok] .env.local found"
  else
    echo "[warn] Missing env file: $ENV_FILE"
    issues=1
  fi

  ensure_env_loaded

  if [[ -n "${CODEXREMOTE_PASSWORD:-}" && "${CODEXREMOTE_PASSWORD}" != "change-me" ]]; then
    echo "[ok] CODEXREMOTE_PASSWORD configured"
  else
    echo "[warn] CODEXREMOTE_PASSWORD missing or still using the placeholder value"
    issues=1
  fi

  if [[ -f "$SERVER_DIST" ]]; then
    echo "[ok] Server build artifact present"
  else
    echo "[warn] Missing server build artifact: $SERVER_DIST"
    issues=1
  fi

  if [[ -f "$WEB_BUILD_ID" ]]; then
    echo "[ok] Web build artifact present"
  else
    echo "[warn] Missing web build artifact: $WEB_BUILD_ID"
    issues=1
  fi

  if [[ -x "$WEB_NEXT_BIN" ]]; then
    echo "[ok] Next runtime present in node_modules"
  else
    echo "[warn] Missing Next runtime: $WEB_NEXT_BIN"
    issues=1
  fi

  if [[ "$(uname -s)" == "Darwin" ]]; then
    if [[ -f "$HOME/Library/LaunchAgents/dev.codexremote.server.plist" && -f "$HOME/Library/LaunchAgents/dev.codexremote.web.plist" ]]; then
      echo "[ok] launchd plists present"
    else
      echo "[warn] launchd plists missing; `./scripts/codexremote.sh up` will install them"
    fi

    if [[ -d "$LOG_DIR" ]]; then
      echo "[ok] Launchd log directory present"
    else
      echo "[warn] Launchd log directory not created yet: $LOG_DIR"
    fi
  else
    echo "[warn] Non-macOS host detected; launchd commands are currently unsupported"
  fi

  echo
  if [[ "$issues" -eq 0 ]]; then
    echo "[codexremote] Doctor checks passed."
  else
    echo "[codexremote] Doctor found setup issues."
    return 1
  fi
}

[[ $# -eq 1 ]] || usage

case "$1" in
  up)
    require_darwin
    ensure_env_loaded
    : "${CODEXREMOTE_PASSWORD:?Set CODEXREMOTE_PASSWORD or provide it in .env.local}"
    ensure_builds
    "$SCRIPT_DIR/install-launchd.sh"
    warn "Local stack is starting. Use ./scripts/codexremote.sh status to inspect it."
    warn "Need a pairing code? Run ./scripts/codexremote.sh pair after the server is ready."
    ;;
  pair)
    ensure_env_loaded
    print_pairing_code
    ;;
  status)
    require_darwin
    "$SCRIPT_DIR/codexremotectl.sh" status
    ;;
  logs)
    require_darwin
    "$SCRIPT_DIR/codexremotectl.sh" logs
    ;;
  restart)
    require_darwin
    "$SCRIPT_DIR/codexremotectl.sh" restart
    ;;
  web)
    if ! open_url "http://127.0.0.1:31817"; then
      fail "Could not open the browser automatically."
    fi
    ;;
  doctor)
    run_doctor
    ;;
  *)
    usage
    ;;
esac
