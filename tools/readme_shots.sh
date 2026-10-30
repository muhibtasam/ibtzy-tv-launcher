#!/bin/bash
# Takes the README's screenshots on the TV (screenshots/*.jpg, 1600 px wide).
cd "$(dirname "$0")/.."
A="adb -s ${TV:-192.168.1.69:5555}"
mkdir -p screenshots
k() { for x in "$@"; do if [ "$x" = s ]; then sleep 1; else $A shell input keyevent "$x"; sleep 0.4; fi; done; }
shot() { $A exec-out screencap -p > "/tmp/$1.png" && sips -s format jpeg -s formatOptions 82 -Z 1600 "/tmp/$1.png" --out "screenshots/$1.jpg" >/dev/null; }
k 3 s s s; shot home
k 19 19 21 21 s s; shot movies
k 20 22 s s s; shot games
k 3 s s 19 19 21 s s; shot apps
k 3 s s 19 19 22 22 23 s s; shot control-center
k 4 3 s
$A shell am start -n io.ibtzy.tvlauncher/.SettingsActivity >/dev/null; sleep 2; shot settings
k 20 23 s s s s s s; shot screensaver
k 4 4 3
ls -la screenshots
