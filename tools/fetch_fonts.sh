#!/usr/bin/env bash
# Downloads the home screen's fonts (Google Fonts, SIL Open Font License) into app/assets/fonts/ once.
# Headlines: Fraunces. Body: Sora (Space Grotesk and DM Sans kept for going back). Without them: Inter.
cd "$(dirname "$0")/../app/assets/fonts" || exit 0
get() {   # file url
  [ -s "$1" ] && return 0
  curl -fsSL --max-time 60 -o "$1.tmp" "$2" && mv "$1.tmp" "$1" && echo "  font: $1" || { rm -f "$1.tmp"; echo "  font: $1 not downloaded (Inter stays)"; }
}
get SpaceGrotesk.ttf "https://github.com/google/fonts/raw/main/ofl/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf"
get DMSans.ttf "https://github.com/google/fonts/raw/main/ofl/dmsans/DMSans%5Bopsz,wght%5D.ttf"
get Fraunces.ttf "https://github.com/google/fonts/raw/main/ofl/fraunces/Fraunces%5BSOFT,WONK,opsz,wght%5D.ttf"
get Fraunces-Italic.ttf "https://github.com/google/fonts/raw/main/ofl/fraunces/Fraunces-Italic%5BSOFT,WONK,opsz,wght%5D.ttf"   # Games rails' titles
get Sora.ttf "https://github.com/google/fonts/raw/main/ofl/sora/Sora%5Bwght%5D.ttf"
