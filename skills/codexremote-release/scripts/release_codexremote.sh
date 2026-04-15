#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SKILL_DIR}/../.." && pwd)"

DRY_RUN=0
SKIP_PUSH=0
SKIP_RELEASE=0
SKIP_VALIDATE=0
NOTES_FILE=""

usage() {
  cat <<'EOF'
Usage: release_codexremote.sh [options]

Options:
  --dry-run         Print the planned actions without mutating git or GitHub
  --skip-validate   Skip release validation commands
  --skip-push       Skip git push
  --skip-release    Skip GitHub release creation
  --notes-file PATH Override the release notes file path
  --help            Show this help
EOF
}

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

run_cmd() {
  if [[ "${DRY_RUN}" -eq 1 ]]; then
    printf '[dry-run] %s\n' "$*"
    return 0
  fi
  "$@"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --skip-validate)
      SKIP_VALIDATE=1
      shift
      ;;
    --skip-push)
      SKIP_PUSH=1
      shift
      ;;
    --skip-release)
      SKIP_RELEASE=1
      shift
      ;;
    --notes-file)
      NOTES_FILE="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

require_command git
require_command gh
require_command node
require_command python3

cd "${REPO_ROOT}"

CURRENT_BRANCH="$(git branch --show-current)"
[[ "${CURRENT_BRANCH}" == "main" ]] || fail "release flow must run from main; current branch: ${CURRENT_BRANCH}"

ROOT_VERSION="$(node -p "require('./package.json').version")"
WEB_VERSION="$(node -p "require('./apps/web/package.json').version")"
SERVER_VERSION="$(node -p "require('./apps/server/package.json').version")"
SHARED_VERSION="$(node -p "require('./packages/shared/package.json').version")"
SDK_VERSION="$(node -p "require('./packages/sdk/package.json').version")"
ANDROID_VERSION="$(python3 - <<'PY'
import pathlib
import re
content = pathlib.Path("apps/android/app/build.gradle.kts").read_text(encoding="utf-8")
match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
if not match:
    raise SystemExit(1)
print(match.group(1))
PY
)"

for candidate in "${WEB_VERSION}" "${SERVER_VERSION}" "${SHARED_VERSION}" "${SDK_VERSION}" "${ANDROID_VERSION}"; do
  [[ "${candidate}" == "${ROOT_VERSION}" ]] || fail "version mismatch detected; expected ${ROOT_VERSION}, found ${candidate}"
done

VERSION="${ROOT_VERSION}"
TAG="v${VERSION}"

if [[ -z "${NOTES_FILE}" ]]; then
  NOTES_FILE="docs/release-notes-${TAG}.md"
fi

[[ -f "${NOTES_FILE}" ]] || fail "release notes file not found: ${NOTES_FILE}"

if git rev-parse "${TAG}" >/dev/null 2>&1; then
  if [[ "${DRY_RUN}" -eq 1 ]]; then
    printf '[dry-run] local git tag already exists: %s\n' "${TAG}"
  else
    fail "git tag already exists locally: ${TAG}"
  fi
fi

if gh release view "${TAG}" >/dev/null 2>&1; then
  if [[ "${DRY_RUN}" -eq 1 ]]; then
    printf '[dry-run] GitHub release already exists: %s\n' "${TAG}"
  else
    fail "GitHub release already exists: ${TAG}"
  fi
fi

if git ls-remote --exit-code --tags origin "refs/tags/${TAG}" >/dev/null 2>&1; then
  if [[ "${DRY_RUN}" -eq 1 ]]; then
    printf '[dry-run] remote git tag already exists: %s\n' "${TAG}"
  else
    fail "remote git tag already exists: ${TAG}"
  fi
fi

printf 'Release version: %s\n' "${VERSION}"
printf 'Release notes: %s\n' "${NOTES_FILE}"
printf 'Branch: %s\n' "${CURRENT_BRANCH}"

if [[ "${SKIP_PUSH}" -eq 1 && "${SKIP_RELEASE}" -ne 1 ]]; then
  fail "--skip-push cannot be combined with GitHub release creation; push the release tag first or add --skip-release"
fi

if [[ "${SKIP_VALIDATE}" -ne 1 ]]; then
  run_cmd npm run typecheck --workspace @codexremote/web
  run_cmd npm run build --workspace @codexremote/web
  if [[ "${DRY_RUN}" -eq 1 ]]; then
    printf '[dry-run] (cd apps/android && ./gradlew :app:compileDebugKotlin)\n'
  else
    (
      cd apps/android
      ./gradlew :app:compileDebugKotlin
    )
  fi
fi

STATUS_OUTPUT="$(git status --short)"
if [[ -z "${STATUS_OUTPUT}" ]]; then
  fail "nothing to release; working tree is clean"
fi

run_cmd git add README.md apps docs package.json package-lock.json packages skills
run_cmd git commit -m "Release ${TAG}"
run_cmd git tag "${TAG}"

if [[ "${SKIP_PUSH}" -ne 1 ]]; then
  run_cmd git push origin main
  run_cmd git push origin "${TAG}"
fi

if [[ "${SKIP_RELEASE}" -ne 1 ]]; then
  run_cmd gh release create "${TAG}" --verify-tag --title "${TAG}" --notes-file "${NOTES_FILE}"
fi

printf 'Release flow completed for %s\n' "${TAG}"
