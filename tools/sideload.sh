#!/usr/bin/env bash
# Sideloads an app you downloaded (an .apk, or a split bundle: .apks, .apkm, .xapk) onto the TV over ADB, after
# checking who signed it. Made for Apple Music (com.apple.android.music), which Google Play doesn't offer for TVs;
# the Apple folder in All Apps opens it once it's installed.
#
#   tools/sideload.sh <TV IP> <file> [expected signer, default "Apple Inc."]
#
# It refuses a file whose certificate doesn't name the expected signer: a repackaged APK from a mirror is how malware
# gets onto a TV. It installs only (adb install), never changes a setting, and `adb uninstall <package>` removes it.
set -euo pipefail

ip="${1:-}"; file="${2:-}"; signer="${3:-Apple Inc.}"
if [ -z "$ip" ] || [ -z "$file" ] || [ ! -f "$file" ]; then
  echo "Usage: tools/sideload.sh <TV IP> <file.apk|.apks|.apkm|.xapk> [expected signer]" >&2
  exit 2
fi

tools=""
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools \
         /usr/local/share/android-commandlinetools "$HOME/Android/Sdk"; do
  if [ -n "$d" ] && [ -d "$d/build-tools" ]; then tools="$(ls -d "$d"/build-tools/* | sort -V | tail -1)"; break; fi
done
if [ -z "$tools" ] || [ ! -x "$tools/apksigner" ]; then
  echo "Needs the Android build-tools (apksigner, aapt2): sdkmanager \"build-tools;34.0.0\"" >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
case "$file" in
  *.apk) apks=("$file"); base="$file" ;;
  *.apks|*.apkm|*.xapk|*.zip)
    unzip -q -o "$file" '*.apk' -d "$work"
    apks=(); while IFS= read -r f; do apks+=("$f"); done < <(find "$work" -name '*.apk' | sort)
    base="$(find "$work" -name 'base.apk' | head -1)"
    if [ -z "$base" ]; then base="$(ls -S "${apks[@]}" | head -1)"; fi ;;
  *) echo "Not an .apk or a split bundle: $file" >&2; exit 2 ;;
esac

pkg="$("$tools/aapt2" dump badging "$base" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
certs="$("$tools/apksigner" verify --print-certs "$base")"
echo "Package: $pkg"
echo "$certs" | grep -E "certificate DN|SHA-256" | sed 's/^/  /'
if ! echo "$certs" | grep -q "certificate DN:.*O=$signer"; then
  echo "Refused: the certificate doesn't name \"$signer\". Download it from a source that keeps the original signature." >&2
  exit 1
fi
for a in "${apks[@]}"; do
  if ! "$tools/apksigner" verify "$a" >/dev/null 2>&1; then echo "Refused: $(basename "$a") isn't validly signed." >&2; exit 1; fi
done

adb connect "$ip:5555" >/dev/null
target="$ip:5555"
# Split bundles carry pieces for every processor and screen: keep the ones this TV takes.
abis="$(adb -s "$target" shell getprop ro.product.cpu.abilist | tr -d '\r')"
keep=()
for a in "${apks[@]}"; do
  n="$(basename "$a")"
  case "$n" in
    *arm64_v8a*|*armeabi_v7a*|*x86_64*|*x86*)
      abi="$(echo "$n" | sed -E 's/.*(arm64_v8a|armeabi_v7a|x86_64|x86).*/\1/' | tr _ -)"
      case ",$abis," in *",$abi,"*) keep+=("$a") ;; esac ;;
    *) keep+=("$a") ;;
  esac
done
echo "Installing ${#keep[@]} file(s) on $target…"
if [ "${#keep[@]}" -eq 1 ]; then adb -s "$target" install -r "${keep[0]}"
else adb -s "$target" install-multiple -r "${keep[@]}"; fi
echo "Done. Open it from mahitTV's All Apps (the Apple folder for Apple Music)."
