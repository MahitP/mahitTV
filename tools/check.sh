#!/usr/bin/env bash
# What CI runs on every pull request (.github/workflows/check.yml). Run it before you push:
#
#   tools/check.sh           # everything
#   tools/check.sh android   # only the Google TV build (needs what build.sh needs)
#   tools/check.sh web       # translations, the LG and Samsung packages, and ES5 (needs Python 3 and Node)
#
# One difference: here ./build.sh uses the newest SDK and JDK you have, while CI uses build-tools 34, android-34 and
# JDK 17, as the README says. A call newer than Android 34 can pass here and fail on the pull request.
set -euo pipefail
cd "$(dirname "$0")/.."
what="${1:-all}"
case "$what" in
  all|android|web) ;;
  *) echo "Usage: tools/check.sh [all|android|web]" >&2; exit 2 ;;
esac

packages="FreeMyTV-webos.ipk FreeMyTV-tizen.wgt"
kept=""
wallpaper_classes=""
watch_classes=""
restore_packages=false
cleanup() {
  if [ -n "$kept" ] && [ -d "$kept" ]; then
    if [ "$restore_packages" = true ]; then
      for p in $packages; do
        if [ -f "$kept/$p" ]; then mv "$kept/$p" "$p"; else rm -f "$p"; fi
      done
    fi
    rm -rf "$kept"
  fi
  if [ -n "$wallpaper_classes" ] && [ -d "$wallpaper_classes" ]; then rm -rf "$wallpaper_classes"; fi
  if [ -n "$watch_classes" ] && [ -d "$watch_classes" ]; then rm -rf "$watch_classes"; fi
}
trap cleanup EXIT

if [ "$what" = all ] || [ "$what" = android ]; then
  # mahitTV's Home hold and volume run on the key service, so it can't go off with the buttons, as upstream's does.
  if sed -n '/static boolean serviceWanted/,/^    }/p' app/src/io/freemytv/RemoteKeys.java | grep -q 'keyApp'; then
    echo "RemoteKeys.serviceWanted turns the key service off with the buttons: Home hold and volume need it." >&2
    exit 1
  fi
  # Upstream's "all buttons off" state would leave TCL's Settings button dead: its default opens TV Settings.
  if grep -n 'keysAllOff' app/src/io/freemytv/Prefs.java; then
    echo "Prefs: keysAllOff drops a key's default, and the Settings button stops working." >&2
    exit 1
  fi
  # SoundPool's calls can wait on the audio server: 5 to 16 ms per key on a MediaTek TV, so never in the key handler.
  if grep -n 'pool\.play(' app/src/io/freemytv/Click.java; then
    echo "Click: sounds play on the UI thread (above)." >&2
    exit 1
  fi
  # Left leaves the key screen as Back does: its page on the network and the kept-on screen must stop too.
  if ! sed -n '/private void toSidebar/,/^        }/p' app/src/io/freemytv/SettingsActivity.java | grep -q 'stopKeyPage'; then
    echo "SettingsActivity.toSidebar leaves the key page running." >&2
    exit 1
  fi
  wallpaper_classes="$(mktemp -d)"
  javac -d "$wallpaper_classes" app/src/io/freemytv/Wallpaper.java app/src/io/freemytv/WallpaperPicker.java app/src/io/freemytv/AerialCache.java app/src/io/freemytv/AerialRequest.java tools/WallpaperTest.java tools/WallpaperPickerTest.java tools/AerialCacheTest.java tools/AerialRequestTest.java tools/DockRecessTest.java app/src/io/freemytv/RowAccess.java tools/RowAccessTest.java
  java -cp "$wallpaper_classes" io.freemytv.WallpaperTest
  java -cp "$wallpaper_classes" io.freemytv.WallpaperPickerTest
  java -cp "$wallpaper_classes" io.freemytv.AerialCacheTest
  java -cp "$wallpaper_classes" io.freemytv.AerialRequestTest
  java -cp "$wallpaper_classes" io.freemytv.DockRecessTest   # the Top Shelf never hides the Dock
  java -cp "$wallpaper_classes" io.freemytv.RowAccessTest   # only the manager changes keys and sign-ins
  ./build.sh
  watch_classes="$(mktemp -d)"
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ]; then
    for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
             /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
      if [ -d "$d/platforms" ]; then sdk="$d"; break; fi
    done
  fi
  android_jar="$(ls -d "$sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
  javac -cp "out/classes:$android_jar" -d "$watch_classes" tools/RecsWatchTest.java tools/DebloatTest.java tools/WellyBotTest.java
  java -cp "$watch_classes:out/classes:$android_jar" io.freemytv.RecsWatchTest
  java -cp "$watch_classes:out/classes:$android_jar" io.freemytv.DebloatTest
  java -cp "$watch_classes:out/classes:$android_jar" io.freemytv.WellyBotTest
  # The weather parser: android.jar's org.json is only stubs on a plain JVM, so tools/testjson's stands in, ahead of it.
  javac -d "$watch_classes/json" tools/testjson/org/json/*.java
  javac -cp "$watch_classes/json:out/classes:$android_jar" -d "$watch_classes" tools/WeatherParseTest.java
  java -cp "$watch_classes:$watch_classes/json:out/classes:$android_jar" io.freemytv.WeatherParseTest
  bash tools/test-remote.sh
  bash tools/test-order.sh
fi

if [ "$what" = all ] || [ "$what" = web ]; then
  python3 tools/test_merge_i18n.py
  python3 tools/translate.py --check
  node tools/test_welly_bot.js
  node tools/wallpaper-web-test.js
  node tools/wallpaper-pref-test.js
  node tools/wallpaper-policy-test.js
  node tools/aerial-video-web-test.js
  node tools/home-background-test.js
  node tools/home-background-dom-test.js
  node tools/web-keys-test.js   # H (Dock / Recent Apps), S (screensaver), + and − (volume HUD), one Inputs tile

  # The generic LG and Samsung packages land where your own build leaves yours (with your city and TMDB key):
  # keep yours and put them back afterwards, so the next install isn't the generic one.
  kept="$(mktemp -d)"
  for p in $packages; do if [ -f "$p" ]; then cp "$p" "$kept/"; fi; done
  restore_packages=true
  python3 webos/build.py --generic
  python3 tizen/build.py --generic

  # webOS 3 and Tizen 3 run a Chromium from 2015 to 2017: web/ has to be ES5, in syntax...
  for f in web/*.js webos/service/*.js; do npx --yes acorn@8.14.0 --ecma5 --silent "$f"; done
  # ...and in what it calls. These parse fine but don't exist there (wrap a feature check in try, as strings.js does).
  if grep -nE '\.(includes|startsWith|endsWith|padStart|padEnd|repeat|find|findIndex|closest)\(|Object\.(assign|entries|values)\(|Array\.from\(|\bPromise\b|\bfetch\(|new (Map|Set|WeakMap)\b' web/*.js; then
    echo "web/ calls something older TVs don't have (above)." >&2
    exit 1
  fi
  # Nor do they have CSS custom properties, grid or backdrop-filter (home.css says why).
  if grep -nE '(^|[{;])[[:space:]]*(--[a-zA-Z-]+[[:space:]]*:|[a-z-]+[[:space:]]*:[^;{}]*var\(|display[[:space:]]*:[[:space:]]*(inline-)?grid([^a-z-]|$)|grid-[a-z-]+[[:space:]]*:|(-webkit-)?backdrop-filter[[:space:]]*:)' web/*.css; then
    echo "web/ uses CSS older TVs don't have (above)." >&2
    exit 1
  fi
  # On screen it's mahitTV: code and catalogues merged from upstream bring the old name back.
  if grep -nE 'trf?\("[^"]*Free My TV|setContentDescription\("Free My TV|Free My TV</' app/src/io/freemytv/*.java; then
    echo "On-screen text says Free My TV (above): it's mahitTV here." >&2
    exit 1
  fi
  if grep -l 'Free My TV' app/assets/i18n/*.json; then
    echo "A catalogue says Free My TV (above): python3 tools/merge_i18n.py rebrands them." >&2
    exit 1
  fi
fi

echo "All checks passed."
