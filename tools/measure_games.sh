#!/usr/bin/env bash
# Measures the Games section: a cold load (saved rows and game images removed) and a warm one (all saved).
# Run from the project folder: bash tools/measure_games.sh   → games-cold.txt, games-warm.txt
set -euo pipefail
TV="${1:-192.168.1.69}:5555"
F=/sdcard/Android/data/io.ibtzy.tvlauncher/files
adb connect "$TV" >/dev/null
restart() { adb -s "$TV" shell am force-stop io.ibtzy.tvlauncher; sleep 1; adb -s "$TV" shell input keyevent KEYCODE_HOME; }
adb -s "$TV" shell touch $F/games-cold
restart
read -r -p "COLD: on the TV open For You → Games, wait ~60 s, scroll down the rows once, then press Enter here… "
adb -s "$TV" shell cat $F/games-timing.txt > games-cold.txt
restart
read -r -p "WARM: open For You → Games again, wait ~30 s, then press Enter here… "
adb -s "$TV" shell cat $F/games-timing.txt > games-warm.txt
echo "Saved games-cold.txt ($(wc -l < games-cold.txt) lines) and games-warm.txt ($(wc -l < games-warm.txt) lines)."
