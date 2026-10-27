#!/usr/bin/env bash
# Checks OpenCritic's endpoints on RapidAPI with the key in config.properties (never printed): status and a short start.
set -uo pipefail
cd "$(dirname "$0")/.."
K=$(grep -E '^opencritic_key=' config.properties | cut -d= -f2- | tr -d '[:space:]')
[ -z "$K" ] && { echo "opencritic_key is empty"; exit 1; }
H=opencritic-api.p.rapidapi.com
hit() {
  local out code
  out=$(curl -s -m 20 -D /tmp/oc_h.txt -w '\n%{http_code}' -H "X-RapidAPI-Key: $K" -H "X-RapidAPI-Host: $H" "https://$H$1")
  code=$(tail -n1 <<<"$out")
  echo "== $1 → HTTP $code, quota left $(grep -i 'x-ratelimit-requests-remaining' /tmp/oc_h.txt | tr -d '\r' | awk '{print $2}')"
  head -c 300 <<<"$(sed '$d' <<<"$out")"; echo; echo
}
hit "/game/recently-released"
hit "/game/popular"
hit "/game/search?criteria=elden%20ring"
ID=$(curl -s -m 20 -H "X-RapidAPI-Key: $K" -H "X-RapidAPI-Host: $H" "https://$H/game/search?criteria=elden%20ring" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
echo "(Elden Ring's OpenCritic id: ${ID:-not found})"
[ -n "$ID" ] && { hit "/game/$ID"; hit "/review/game/$ID"; }
