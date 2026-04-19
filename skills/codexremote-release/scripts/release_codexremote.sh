#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SKILL_DIR}/../.." && pwd)"
APK_PREPARE_SCRIPT="${REPO_ROOT}/skills/apk-smb-sync/scripts/prepare_apk_artifact.sh"
ANDROID_DIR="${REPO_ROOT}/apps/android"
ANDROID_APK_PATH="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_ASSET_OUTPUT_DIR="${TMPDIR:-/tmp}/findeck-release-assets"

DRY_RUN=0
SKIP_PUSH=0
SKIP_RELEASE=0
SKIP_VALIDATE=0
SKIP_APK=0
NOTES_FILE=""

usage() {
  cat <<'EOF'
Usage: release_codexremote.sh [options]

Options:
  --dry-run         Print the planned actions without mutating git or GitHub
  --skip-validate   Skip release validation commands
  --skip-push       Skip git push
  --skip-release    Skip GitHub release creation
  --skip-apk        Do not build or attach the Android APK to the GitHub release
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

detect_sdk_dir() {
  local candidates=()
  [[ -n "${ANDROID_SDK_ROOT:-}" ]] && candidates+=("${ANDROID_SDK_ROOT}")
  [[ -n "${ANDROID_HOME:-}" ]] && candidates+=("${ANDROID_HOME}")
  candidates+=("${HOME}/Library/Android/sdk")
  candidates+=("/opt/homebrew/share/android-commandlinetools")

  local candidate=""
  for candidate in "${candidates[@]}"; do
    if [[ -d "${candidate}" ]]; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  return 1
}

prepare_android_env() {
  if [[ -f "${ANDROID_DIR}/local.properties" ]]; then
    return 0
  fi

  local sdk_dir=""
  sdk_dir="$(detect_sdk_dir)" || fail "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or create apps/android/local.properties."

  export ANDROID_HOME="${sdk_dir}"
  export ANDROID_SDK_ROOT="${sdk_dir}"
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
    --skip-apk)
      SKIP_APK=1
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
[[ -x "${APK_PREPARE_SCRIPT}" ]] || fail "missing APK prepare script: ${APK_PREPARE_SCRIPT}"

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
  prepare_android_env
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

RELEASE_APK_PATH=""
if [[ "${SKIP_RELEASE}" -ne 1 && "${SKIP_APK}" -ne 1 ]]; then
  prepare_android_env
  if [[ "${DRY_RUN}" -eq 1 ]]; then
    RELEASE_APK_PATH="<prepared-apk>"
    printf '[dry-run] (cd apps/android && ./gradlew assembleDebug)\n'
    printf '[dry-run] %s --source %s --output-dir %s --version-name %s --print-path-only\n' \
      "${APK_PREPARE_SCRIPT}" "${ANDROID_APK_PATH}" "${RELEASE_ASSET_OUTPUT_DIR}" "${VERSION}"
  else
    (
      cd "${ANDROID_DIR}"
      ./gradlew assembleDebug
    )
    RELEASE_APK_PATH="$("${APK_PREPARE_SCRIPT}" \
      --source "${ANDROID_APK_PATH}" \
      --output-dir "${RELEASE_ASSET_OUTPUT_DIR}" \
      --version-name "${VERSION}" \
      --print-path-only)"
    [[ -f "${RELEASE_APK_PATH}" ]] || fail "prepared release APK not found: ${RELEASE_APK_PATH}"
    printf 'Prepared release APK asset: %s\n' "${RELEASE_APK_PATH}"
  fi
fi

STATUS_OUTPUT="$(git status --short)"
if [[ -z "${STATUS_OUTPUT}" ]]; then
  fail "nothing to release; working tree is clean"
fi

run_cmd git add README.md apps docs package.json package-lock.json packages scripts skills
run_cmd git commit -m "Release ${TAG}"
run_cmd git tag "${TAG}"

if [[ "${SKIP_PUSH}" -ne 1 ]]; then
  run_cmd git push origin main
  run_cmd git push origin "${TAG}"
fi

if [[ "${SKIP_RELEASE}" -ne 1 ]]; then
  if [[ "${SKIP_APK}" -ne 1 ]]; then
    if [[ "${DRY_RUN}" -ne 1 && -z "${RELEASE_APK_PATH}" ]]; then
      fail "release APK path missing before GitHub release creation"
    fi
    run_cmd gh release create "${TAG}" "${RELEASE_APK_PATH}" --verify-tag --title "${TAG}" --notes-file "${NOTES_FILE}"
  else
    run_cmd gh release create "${TAG}" --verify-tag --title "${TAG}" --notes-file "${NOTES_FILE}"
  fi
fi

printf 'Release flow completed for %s\n' "${TAG}"
