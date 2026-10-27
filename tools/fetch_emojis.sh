#!/usr/bin/env bash
# Downloads the greeting's emoji (Microsoft Fluent Emoji, 3D style, MIT license) into app/assets/emoji/ once, made
# 128 px (macOS sips). Without them the greeting draws the TV's own emoji font. Run: bash tools/fetch_emojis.sh
cd "$(dirname "$0")/../app/assets/emoji" || exit 1
BASE="https://raw.githubusercontent.com/microsoft/fluentui-emoji/main/assets"
ok=0; failed=""
get() {   # key "Folder name in the Fluent repo"
  local key="$1" folder="$2" slug enc
  [ -s "$key.png" ] && { ok=$((ok+1)); return 0; }
  slug=$(echo "$folder" | tr '[:upper:]' '[:lower:]' | tr ' ' '_')
  enc=$(echo "$folder" | sed 's/ /%20/g')
  # Emoji without skin tones: <folder>/3D/<slug>_3d.png; with them: <folder>/Default/3D/<slug>_3d_default.png.
  for path in "$enc/3D/${slug}_3d.png" "$enc/Default/3D/${slug}_3d_default.png"; do
    if curl -fsSL --max-time 60 -o "$key.tmp" "$BASE/$path"; then
      mv "$key.tmp" "$key.png"
      command -v sips >/dev/null && sips -Z 128 "$key.png" >/dev/null 2>&1
      echo "  emoji: $key.png ($folder)"; ok=$((ok+1)); return 0
    fi
  done
  rm -f "$key.tmp"; failed="$failed $key"; echo "  emoji: $key ($folder) not downloaded (the TV's emoji stays for it)"
}
get wave "Waving hand"
get smile "Smiling face with smiling eyes"
get sparkles "Sparkles"
get pray "Folded hands"
get star "Glowing star"
get grin "Grinning face with smiling eyes"
get palm "Palm tree"
get handshake "Handshake"
get blossom "Cherry blossom"
get slight_smile "Slightly smiling face"
get lantern "Red paper lantern"
get coffee "Hot beverage"
get sun "Sun with face"
get grinning "Grinning face"
get hibiscus "Hibiscus"
get raised_hands "Raising hands"
get popcorn "Popcorn"            # For You: Movies & Shows
get controller "Video game"       # For You: Games
get calendar "Calendar"           # Games bento: release date
get busts "Busts in silhouette"   # Games bento: players
get trophy "Trophy"               # Games bento: critics
echo "$ok of 21 emoji in app/assets/emoji${failed:+ (missing:$failed)}"
