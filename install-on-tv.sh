#!/usr/bin/env bash
# Builds ibtzy TV Launcher and installs it on the TCL TV at 192.168.1.69. Run from this folder: ./install-on-tv.sh
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:-192.168.1.69}"

# 1. Tools (only what's missing)
command -v brew >/dev/null || { echo "Install Homebrew first: https://brew.sh"; exit 1; }
command -v javac >/dev/null && javac -version 2>&1 | grep -qE ' (1[7-9]|[2-9][0-9])' || brew install --cask temurin
command -v sdkmanager >/dev/null || brew install --cask android-commandlinetools
command -v adb >/dev/null || brew install --cask android-platform-tools
export ANDROID_HOME="${ANDROID_HOME:-$(brew --prefix)/share/android-commandlinetools}"
[ -d "$ANDROID_HOME/build-tools/35.0.0" ] && [ -d "$ANDROID_HOME/platforms/android-34" ] || {
  yes | sdkmanager --licenses >/dev/null || true
  sdkmanager "build-tools;35.0.0" "platforms;android-34"
}

# 2. Build (with the latest official dock icons)
python3 tools/fetch_dock_icons.py || true
bash tools/fetch_fonts.sh || true
bash tools/fetch_emojis.sh || true   # once: the greeting's and For You's 3D emoji
./build.sh

# 3. Install (accept "Allow USB debugging?" on the TV, tick "Always allow")
adb kill-server >/dev/null 2>&1 || true
adb start-server
adb connect "$TV:5555"
echo "If the TV shows 'Allow USB debugging?', tick Always allow and press OK. Waiting..."
for i in $(seq 1 30); do adb -s "$TV:5555" get-state 2>/dev/null | grep -q device && break; sleep 2; done
adb -s "$TV:5555" install -r ibtzyTVLauncher.apk
# Compiled ahead now (a fresh install runs interpreted until the TV gets to it: its first minute had 0.7-2.4 s frames).
adb -s "$TV:5555" shell cmd package compile -m speed -f io.ibtzy.tvlauncher >/dev/null || true
# TCL blocks background jobs by default; allow ibtzy TV Launcher's
adb -s "$TV:5555" shell "appops set io.ibtzy.tvlauncher AUTO_START allow || appops set io.ibtzy.tvlauncher APP_AUTO_START allow" || true
# The dock shows your most used apps: let ibtzy TV Launcher read app usage
adb -s "$TV:5555" shell appops set io.ibtzy.tvlauncher GET_USAGE_STATS allow || true
# YouTube Music is on the dock: turn it back on if ibtzy TV Launcher turned it off
adb -s "$TV:5555" shell pm enable com.google.android.youtube.tvmusic >/dev/null 2>&1 || true
# Restart the home screen so the new version shows
adb -s "$TV:5555" shell am force-stop io.ibtzy.tvlauncher || true
adb -s "$TV:5555" shell input keyevent KEYCODE_HOME || true
echo
echo "Done. On the TV: open ibtzy TV Launcher -> Home Screen -> ibtzy TV Launcher, and accept 'Allow USB debugging?' once more (tick Always allow)."
