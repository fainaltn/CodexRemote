#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SKILL_DIR}/../.." && pwd)"
PREPARE_SCRIPT="${SCRIPT_DIR}/prepare_apk_artifact.sh"

DEFAULT_APK_PATH="${REPO_ROOT}/apps/android/app/build/outputs/apk/debug/app-debug.apk"
DEFAULT_GRADLE_PATH="${REPO_ROOT}/apps/android/app/build.gradle.kts"
DEFAULT_TARGET_URL="smb://192.168.2.1/share/"
DEFAULT_OUTPUT_DIR="${TMPDIR:-/tmp}/apk-smb-sync"
DEFAULT_OPEN_WAIT_SECONDS=20

APK_PATH="${DEFAULT_APK_PATH}"
GRADLE_PATH="${DEFAULT_GRADLE_PATH}"
TARGET_URL="${DEFAULT_TARGET_URL}"
OUTPUT_DIR="${DEFAULT_OUTPUT_DIR}"
OPEN_WAIT_SECONDS="${DEFAULT_OPEN_WAIT_SECONDS}"
MOUNT_POINT=""
VERSION_NAME=""
VERSION_DATE=""
LABEL=""
DRY_RUN=0
NO_OPEN=0

usage() {
  cat <<'EOF'
Usage: publish_apk_to_smb.sh [options]

Options:
  --source PATH          APK file to package
  --gradle PATH          Gradle file used to read versionName
  --target-url URL       SMB target like smb://192.168.2.1/share/ or smb://192.168.2.1/share/releases/
  --mount-point PATH     Mounted SMB root path, defaults to /Volumes/<share>
  --version-name VALUE   Override versionName
  --version-date VALUE   Override version date, format YYYY-MM-DD
  --label VALUE          Override package label used in the APK filename
  --output-dir PATH      Local temp/output directory for generated APK
  --open-wait SECONDS    Wait time after opening Finder for the SMB share
  --dry-run              Print actions without copying to SMB
  --no-open              Do not try to open the SMB share automatically
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
    --target-url)
      TARGET_URL="$2"
      shift 2
      ;;
    --mount-point)
      MOUNT_POINT="$2"
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
    --output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --open-wait)
      OPEN_WAIT_SECONDS="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --no-open)
      NO_OPEN=1
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
[[ -x "${PREPARE_SCRIPT}" ]] || fail "prepare script is not executable: ${PREPARE_SCRIPT}"

parse_target() {
  python3 - "${TARGET_URL}" <<'PY'
import sys
from urllib.parse import urlparse

target = sys.argv[1]
parsed = urlparse(target)
if parsed.scheme != "smb":
    raise SystemExit("target URL must start with smb://")
if not parsed.netloc:
    raise SystemExit("missing SMB host")
path = parsed.path.lstrip("/")
parts = [part for part in path.split("/") if part]
if not parts:
    raise SystemExit("missing SMB share name")
share = parts[0]
sub_parts = parts[1:]
base_name = sub_parts[-1] if sub_parts else ""
remote_dir = "/".join(sub_parts[:-1]) if len(sub_parts) > 1 else ""
if sub_parts and sub_parts[-1].endswith("/"):
    remote_dir = "/".join(sub_parts)
    base_name = ""
print(parsed.netloc)
print(share)
print(remote_dir)
print(base_name)
PY
}

TARGET_PARTS="$(parse_target)" || fail "could not parse target URL: ${TARGET_URL}"
SMB_HOST="$(printf '%s\n' "${TARGET_PARTS}" | sed -n '1p')"
SMB_SHARE="$(printf '%s\n' "${TARGET_PARTS}" | sed -n '2p')"
REMOTE_SUBDIR="$(printf '%s\n' "${TARGET_PARTS}" | sed -n '3p')"
TARGET_BASENAME="$(printf '%s\n' "${TARGET_PARTS}" | sed -n '4p')"

if [[ -z "${MOUNT_POINT}" ]]; then
  resolve_existing_mount_point() {
    mount | awk -v host="${SMB_HOST}" -v share="${SMB_SHARE}" '
      index($0, "@" host "/" share " on ") > 0 {
        line = $0
        sub(/^.* on /, "", line)
        sub(/ \(.*$/, "", line)
        print line
        exit
      }
    '
  }

  MOUNT_POINT="$(resolve_existing_mount_point)"
  if [[ -z "${MOUNT_POINT}" ]]; then
    MOUNT_POINT="/Volumes/${SMB_SHARE}"
  fi
fi

derive_label() {
  local base_name="$1"
  local stem="$2"
  local cleaned=""
  if [[ -n "${stem}" ]]; then
    cleaned="$(printf '%s' "${stem}" | sed -E 's/^[0-9]{4}([0-9]{2}([0-9]{2})?)?([._-]|年)?//')"
    if [[ -n "${cleaned}" ]]; then
      printf '%s' "${cleaned}"
      return 0
    fi
    printf '%s' "${stem}"
    return 0
  fi
  printf '%s' "${base_name}"
}

if [[ -z "${LABEL}" ]]; then
  if [[ -n "${TARGET_BASENAME}" ]]; then
    TARGET_STEM="${TARGET_BASENAME%.*}"
    LABEL="$(derive_label "${SMB_SHARE}" "${TARGET_STEM}")"
  else
    LABEL="findeck"
  fi
fi

PREPARE_ARGS=(
  --source "${APK_PATH}"
  --gradle "${GRADLE_PATH}"
  --output-dir "${OUTPUT_DIR}"
  --label "${LABEL}"
  --print-path-only
)

if [[ -n "${VERSION_NAME}" ]]; then
  PREPARE_ARGS+=(--version-name "${VERSION_NAME}")
fi

if [[ -n "${VERSION_DATE}" ]]; then
  PREPARE_ARGS+=(--version-date "${VERSION_DATE}")
fi

OUTPUT_APK_PATH="$("${PREPARE_SCRIPT}" "${PREPARE_ARGS[@]}")"
APK_NAME="$(basename "${OUTPUT_APK_PATH}")"

if [[ -z "${VERSION_NAME}" ]]; then
  VERSION_NAME="$(python3 - "${OUTPUT_APK_PATH}" <<'PY'
import pathlib
import re
import sys

name = pathlib.Path(sys.argv[1]).name
match = re.search(r'_v([^/]+)\.apk$', name)
if not match:
    raise SystemExit(1)
print(match.group(1))
PY
)" || fail "could not derive versionName from prepared APK path"
fi

if [[ -z "${VERSION_DATE}" ]]; then
  VERSION_DATE="$(python3 - "${OUTPUT_APK_PATH}" <<'PY'
import pathlib
import re
import sys

name = pathlib.Path(sys.argv[1]).name
match = re.search(r'^(\d{4}-\d{2}-\d{2})_', name)
if not match:
    raise SystemExit(1)
print(match.group(1))
PY
)" || fail "could not derive version date from prepared APK path"
fi

REMOTE_DIR="${MOUNT_POINT}"
if [[ -n "${REMOTE_SUBDIR}" ]]; then
  REMOTE_DIR="${MOUNT_POINT}/${REMOTE_SUBDIR}"
fi
REMOTE_PATH="${REMOTE_DIR}/${APK_NAME}"

ensure_mount() {
  if [[ -d "${MOUNT_POINT}" ]]; then
    return 0
  fi
  if [[ "${NO_OPEN}" -eq 1 ]]; then
    fail "SMB mount point not found: ${MOUNT_POINT}"
  fi

  printf 'Opening SMB share in Finder: smb://%s/%s\n' "${SMB_HOST}" "${SMB_SHARE}"
  open "smb://${SMB_HOST}/${SMB_SHARE}" >/dev/null 2>&1 || true

  local waited=0
  while [[ "${waited}" -lt "${OPEN_WAIT_SECONDS}" ]]; do
    if [[ -d "${MOUNT_POINT}" ]]; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done

  fail "SMB share did not mount at ${MOUNT_POINT}; open smb://${SMB_HOST}/${SMB_SHARE} and retry"
}

printf 'Prepared APK: %s\n' "${OUTPUT_APK_PATH}"
printf 'Resolved version: %s\n' "${VERSION_NAME}"
printf 'Resolved version date: %s\n' "${VERSION_DATE}"
printf 'Resolved remote path: %s\n' "${REMOTE_PATH}"

if [[ "${DRY_RUN}" -eq 1 ]]; then
  printf 'Dry run enabled; APK was prepared locally and not copied.\n'
  exit 0
fi

ensure_mount
mkdir -p "${REMOTE_DIR}"
cp -f "${OUTPUT_APK_PATH}" "${REMOTE_PATH}"

printf 'Copied APK to SMB share: %s\n' "${REMOTE_PATH}"
