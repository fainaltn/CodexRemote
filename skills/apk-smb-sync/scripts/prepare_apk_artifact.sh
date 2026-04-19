#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SKILL_DIR}/../.." && pwd)"

DEFAULT_APK_PATH="${REPO_ROOT}/apps/android/app/build/outputs/apk/debug/app-debug.apk"
DEFAULT_GRADLE_PATH="${REPO_ROOT}/apps/android/app/build.gradle.kts"
DEFAULT_OUTPUT_DIR="${TMPDIR:-/tmp}/apk-smb-sync"
DEFAULT_LABEL="findeck"

APK_PATH="${DEFAULT_APK_PATH}"
GRADLE_PATH="${DEFAULT_GRADLE_PATH}"
OUTPUT_DIR="${DEFAULT_OUTPUT_DIR}"
VERSION_NAME=""
VERSION_DATE=""
LABEL="${DEFAULT_LABEL}"
PRINT_PATH_ONLY=0

usage() {
  cat <<'EOF'
Usage: prepare_apk_artifact.sh [options]

Options:
  --source PATH          APK file to package
  --gradle PATH          Gradle file used to read versionName
  --output-dir PATH      Local output directory for generated APK
  --version-name VALUE   Override versionName
  --version-date VALUE   Override version date, format YYYY-MM-DD
  --label VALUE          Override package label used in the APK filename
  --print-path-only      Print only the generated APK path
  --help                 Show this help
EOF
}

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      APK_PATH="$2"
      shift 2
      ;;
    --gradle)
      GRADLE_PATH="$2"
      shift 2
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --version-name)
      VERSION_NAME="$2"
      shift 2
      ;;
    --version-date)
      VERSION_DATE="$2"
      shift 2
      ;;
    --label)
      LABEL="$2"
      shift 2
      ;;
    --print-path-only)
      PRINT_PATH_ONLY=1
      shift
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

require_command python3

[[ -f "${APK_PATH}" ]] || fail "APK not found: ${APK_PATH}"

if [[ -z "${VERSION_NAME}" ]]; then
  [[ -f "${GRADLE_PATH}" ]] || fail "Gradle file not found: ${GRADLE_PATH}"
  VERSION_NAME="$(python3 - "${GRADLE_PATH}" <<'PY'
import pathlib
import re
import sys

content = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
if not match:
    raise SystemExit(1)
print(match.group(1))
PY
)" || fail "could not read versionName from ${GRADLE_PATH}"
fi

if [[ -z "${VERSION_DATE}" ]]; then
  VERSION_DATE="$(date -r "${APK_PATH}" '+%Y-%m-%d')"
fi

if [[ -z "${LABEL}" ]]; then
  LABEL="${DEFAULT_LABEL}"
fi

APK_NAME="${VERSION_DATE}_${LABEL}_v${VERSION_NAME}.apk"
mkdir -p "${OUTPUT_DIR}"
OUTPUT_APK_PATH="$(cd "${OUTPUT_DIR}" && pwd)/${APK_NAME}"
cp -f "${APK_PATH}" "${OUTPUT_APK_PATH}"

if [[ "${PRINT_PATH_ONLY}" -eq 1 ]]; then
  printf '%s\n' "${OUTPUT_APK_PATH}"
  exit 0
fi

printf 'Prepared APK: %s\n' "${OUTPUT_APK_PATH}"
printf 'Resolved version: %s\n' "${VERSION_NAME}"
printf 'Resolved version date: %s\n' "${VERSION_DATE}"
