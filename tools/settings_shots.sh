#!/bin/bash
# Installs the build and screenshots Settings: main list, Tabs, Personalize (snapshots/s_*.png).
cd "$(dirname "$0")/.."
A="adb -s ${TV:-192.168.1.69:5555}"
k() { for x in "$@"; do $A shell input keyevent "$x"; sleep 0.35; done; }
$A install -r ibtzyTVLauncher.apk | tail -1
$A shell cmd package compile -m speed -f io.ibtzy.tvlauncher | tail -1
$A shell am start -n io.ibtzy.tvlauncher/.SettingsActivity >/dev/null; sleep 3
$A exec-out screencap -p > snapshots/s_main.png
k 20 20 20 23; sleep 1; $A exec-out screencap -p > snapshots/s_tabs.png
k 4 19 23; sleep 1; $A exec-out screencap -p > snapshots/s_pers.png
k 4
echo done
