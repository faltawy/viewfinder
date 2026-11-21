#!/usr/bin/env bash
# Prints the CHANGELOG.md section for a version (without its heading), and fails if there isn't one.
# Usage: tools/release-notes.sh 0.1.0 [CHANGELOG.md]
set -euo pipefail
version="${1:?usage: release-notes.sh VERSION [CHANGELOG]}"
changelog="${2:-CHANGELOG.md}"
notes="$(awk -v v="$version" '
  /^## \[/ { if (found) exit; found = index($0, "## [" v "]") == 1; next }
  /^\[[^]]+\]: / { next }
  found { print }
' "$changelog" | sed -e '/./,$!d')"
if [ -z "$(printf '%s' "$notes" | tr -d '[:space:]')" ]; then
  echo "CHANGELOG has no notes for $version. Add a '## [$version] - YYYY-MM-DD' section." >&2
  exit 1
fi
printf '%s\n' "$notes"
