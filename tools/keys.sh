#!/bin/bash
# keys.sh [settings] KEY... : optionally opens Settings first, then sends key codes ("s" = 1 s pause).
A="adb -s ${TV:-192.168.1.69:5555}"
if [ "$1" = settings ]; then $A shell am start -n io.ibtzy.tvlauncher/.SettingsActivity >/dev/null; sleep 2; shift; fi
for x in "$@"; do
  if [ "$x" = s ]; then sleep 1; else $A shell input keyevent "$x"; sleep 0.4; fi
done
