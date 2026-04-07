#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
DATA_DIR="${CODEXREMOTE_DATA_DIR:-$REPO_ROOT/data}"
DB_PATH="$DATA_DIR/codexremote.db"

DRY_RUN=0
MATCH_ALL=0
HOST_ID=""
SUBMISSION_ID=""
TITLE_KEYWORD=""

usage() {
  cat <<'EOF'
Usage:
  ./scripts/clear-inbox.sh [--dry-run] [--host-id <id>] [--submission-id <id>] [--title-contains <text>]
  ./scripts/clear-inbox.sh --all [--dry-run]

Examples:
  ./scripts/clear-inbox.sh --dry-run --host-id local
  ./scripts/clear-inbox.sh --submission-id 1234
  ./scripts/clear-inbox.sh --host-id local --title-contains test
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --all)
      MATCH_ALL=1
      shift
      ;;
    --host-id)
      HOST_ID="${2:-}"
      shift 2
      ;;
    --submission-id)
      SUBMISSION_ID="${2:-}"
      shift 2
      ;;
    --title-contains)
      TITLE_KEYWORD="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "sqlite3 is required" >&2
  exit 1
fi

if [[ ! -f "$DB_PATH" ]]; then
  echo "No database found at $DB_PATH"
  exit 0
fi

if [[ "$MATCH_ALL" -eq 0 && -z "$HOST_ID" && -z "$SUBMISSION_ID" && -z "$TITLE_KEYWORD" ]]; then
  echo "Refusing to clear inbox without filters. Use --all or provide at least one filter." >&2
  exit 1
fi

sql_escape() {
  printf "%s" "$1" | sed "s/'/''/g"
}

HOST_SQL="$(sql_escape "$HOST_ID")"
SUBMISSION_SQL="$(sql_escape "$SUBMISSION_ID")"
TITLE_SQL="$(sql_escape "$TITLE_KEYWORD")"

WHERE_CLAUSES=("1=1")
if [[ "$MATCH_ALL" -eq 0 ]]; then
  if [[ -n "$HOST_ID" ]]; then
    WHERE_CLAUSES+=("host_id = '$HOST_SQL'")
  fi
  if [[ -n "$SUBMISSION_ID" ]]; then
    WHERE_CLAUSES+=("submission_id = '$SUBMISSION_SQL'")
  fi
  if [[ -n "$TITLE_KEYWORD" ]]; then
    WHERE_CLAUSES+=("COALESCE(title, original_name, '') LIKE '%' || '$TITLE_SQL' || '%'")
  fi
fi

WHERE_SQL="${(j: AND :)WHERE_CLAUSES}"
QUERY="
  SELECT
    id,
    host_id,
    COALESCE(submission_id, '') AS submission_id,
    COALESCE(title, original_name, '') AS display_title,
    COALESCE(staging_dir, '') AS staging_dir
  FROM inbox_items
  WHERE $WHERE_SQL
  ORDER BY created_at DESC;
"

ROWS="$(sqlite3 -separator $'\t' "$DB_PATH" "$QUERY")"
if [[ -z "$ROWS" ]]; then
  echo "No matching inbox rows found."
  exit 0
fi

echo "Matched inbox rows:"
while IFS=$'\t' read -r row_id row_host row_submission row_title row_dir; do
  [[ -n "$row_id" ]] || continue
  echo "  id=$row_id host=$row_host submission_id=${row_submission:-<none>} title=${row_title:-<untitled>} staging_dir=${row_dir:-<missing>}"
done <<< "$ROWS"

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "Dry run only. No rows or staging directories were removed."
  exit 0
fi

while IFS=$'\t' read -r row_id row_host row_submission row_title row_dir; do
  [[ -n "$row_id" ]] || continue
  if [[ -n "$row_dir" && -d "$row_dir" ]]; then
    rm -rf "$row_dir"
  fi
done <<< "$ROWS"

sqlite3 "$DB_PATH" "DELETE FROM inbox_items WHERE $WHERE_SQL;"
echo "Removed matching inbox rows and staging directories."
