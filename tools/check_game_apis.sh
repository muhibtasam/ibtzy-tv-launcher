#!/usr/bin/env bash
# Checks the Games keys in config.properties (never prints them): SteamGridDB art for a game, OpenCritic's endpoints.
cd "$(dirname "$0")/.." || exit 1
SG=$(grep '^steamgriddb_key=' config.properties | cut -d= -f2- | tr -d ' \r')
OC=$(grep '^opencritic_key=' config.properties | cut -d= -f2- | tr -d ' \r')
echo "keys: steamgriddb ${#SG} chars, opencritic ${#OC} chars"
G=5309313   # Grand Theft Auto VI on SteamGridDB
echo "== SteamGridDB heroes"
curl -s -H "Authorization: Bearer $SG" "https://www.steamgriddb.com/api/v2/heroes/game/$G?types=static&nsfw=false&humor=false" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('success'), len(d.get('data',[])), [(h.get('width'),h.get('height'),h.get('score'),h.get('url','')[:70]) for h in d.get('data',[])[:2]], d.get('errors'))"
echo "== SteamGridDB logos"
curl -s -H "Authorization: Bearer $SG" "https://www.steamgriddb.com/api/v2/logos/game/$G?types=static&nsfw=false&humor=false" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('success'), len(d.get('data',[])), [(h.get('mime'),h.get('score'),h.get('url','')[:70]) for h in d.get('data',[])[:2]], d.get('errors'))"
for P in /game/reviewed-this-week /game/recently-released /game/upcoming; do
  echo "== OpenCritic $P"
  curl -s -H "X-RapidAPI-Key: $OC" -H "X-RapidAPI-Host: opencritic-api.p.rapidapi.com" "https://opencritic-api.p.rapidapi.com$P" | head -c 600
  echo
done
