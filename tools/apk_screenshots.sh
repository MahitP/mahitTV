#!/usr/bin/env bash
# Screenshots of the Android home screen from a running emulator (or a test TV) into previews/apk-*.png.
# Builds with preview=true and each theme, installs, opens the home screen directly (no mode switch: nothing on the
# device is turned off), presses the D-pad and captures 1920×1080 PNGs. Restores config.properties afterwards.
#   tools/apk_screenshots.sh            # the emulator adb sees
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
cp config.properties out-config.backup
trap 'mv out-config.backup config.properties' EXIT
mkdir -p previews

set_prop() { if grep -q "^$1=" config.properties; then sed -i '' "s/^$1=.*/$1=$2/" config.properties; else echo "$1=$2" >> config.properties; fi; }
# PREFIX=legacy- names the files apk-legacy-*.png (for a build that forces the Android 8 to 12 drawing).
shot() { "$ADB" shell 'sleep 1.4'; "$ADB" exec-out screencap -p > "previews/apk-${PREFIX:-}$1.png"; echo "wrote previews/apk-${PREFIX:-}$1.png"; }
key() { "$ADB" shell input keyevent "$1"; }
open_home() {
  "$ADB" shell am force-stop io.freemytv
  "$ADB" shell am start -n io.freemytv/.HomeActivity >/dev/null
  "$ADB" shell 'sleep 6'
}

for theme in dark light; do
  set_prop theme "$theme"
  set_prop preview true
  ./build.sh >/dev/null 2>&1
  "$ADB" install -r FreeMyTV.apk >/dev/null
  "$ADB" shell pm clear io.freemytv >/dev/null   # no settings left from the other theme
  open_home
  shot "$theme-dock"
  key DPAD_UP; shot "$theme-hero"
  key DPAD_RIGHT; "$ADB" shell 'sleep 1'; shot "$theme-hero-next"
  key DPAD_DOWN; key DPAD_DOWN; shot "$theme-grid"
  key DPAD_RIGHT; shot "$theme-grid-next"
  key BACK; "$ADB" shell 'sleep 0.6'
  set_prop preview false
  ./build.sh >/dev/null 2>&1
  "$ADB" install -r FreeMyTV.apk >/dev/null
  open_home
  shot "$theme-clock"
done
