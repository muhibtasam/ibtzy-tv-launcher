#!/usr/bin/env bash
# Dev helper: tools/stalls.sh "setup keys" "keys" label — sets the scene (tools/tv_shot.sh), resets the frame stats,
# presses the keys (adb keycodes, "s" = 1 s pause) and prints what the remote feels: freezes (frames over 50 and
# 100 ms), the worst frame and the main thread's worst frame. Don't judge by "frames over 16.7 ms" on this TV: each
# frame waits ~15 ms for the display, even an empty one. Compare builds A/B, alternated, 3 runs each.
cd "$(dirname "$0")/.." || exit 1
TV=${TV:-192.168.1.69:5555}; A="adb -s $TV"
bash tools/tv_shot.sh "$1" x >/dev/null 2>&1
PID=$($A shell pidof io.ibtzy.tvlauncher:home | tr -d '\r')
$A shell dumpsys gfxinfo "$PID" reset >/dev/null
for k in $2; do if [ "$k" = s ]; then sleep 1; else $A shell input keyevent "$k"; sleep 0.25; fi; done
sleep 1
$A shell dumpsys gfxinfo "$PID" framestats | python3 tools/stalls.py "${3:-run}"
