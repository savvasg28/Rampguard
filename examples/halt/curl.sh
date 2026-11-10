#!/usr/bin/env bash
# Halt the live rollout from a shell. Needs a token with Contents: write on the app repository.
#   GITHUB_TOKEN=... ./curl.sh OWNER/REPO 101 "crash spike" "on-call"
set -euo pipefail
repo="${1:?owner/repo}"
version_code="${2:?version-code}"
reason="${3:-manual halt}"
source="${4:-$(whoami)}"

curl -sS -X POST "https://api.github.com/repos/${repo}/dispatches" \
  -H "Authorization: Bearer ${GITHUB_TOKEN:?set GITHUB_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -d "$(jq -n --arg v "$version_code" --arg r "$reason" --arg s "$source" \
        '{event_type: "rollout-halt", client_payload: {"version-code": ($v | tonumber), reason: $r, source: $s}}')"
echo "dispatched; watch the Rollout command workflow in the Actions tab"
