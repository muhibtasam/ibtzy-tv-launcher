#!/bin/bash
# Copies the photos in screensaver/ to the TV's Screensaver folder (the screensaver shows them before the picks).
# Only new or changed files are sent; photos deleted here are deleted on the TV too.
cd "$(dirname "$0")/.."
A="adb -s ${TV:-192.168.1.69:5555}"
$A shell mkdir -p /sdcard/Screensaver
$A shell pm grant io.ibtzy.tvlauncher android.permission.READ_MEDIA_IMAGES 2>/dev/null
on_tv="$($A shell ls /sdcard/Screensaver 2>/dev/null | tr -d '\r')"
for f in screensaver/*.{jpg,jpeg,png,webp,JPG,JPEG,PNG,WEBP}; do
  [ -f "$f" ] || continue
  n="$(basename "$f")"
  echo "$on_tv" | grep -qxF "$n" || { $A push "$f" "/sdcard/Screensaver/$n" >/dev/null && echo "sent $n"; }
done
echo "$on_tv" | while read -r n; do [ -n "$n" ] && [ ! -f "screensaver/$n" ] && $A shell rm "/sdcard/Screensaver/$n" && echo "removed $n"; done
echo "Screensaver photos up to date."
