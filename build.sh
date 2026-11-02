#!/usr/bin/env bash
# Builds FreeMyTV.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK (17+) and the Android SDK command-line tools (build-tools 34+, platform android-34). See README.md.
#   ./build.sh             with config.properties (your city, your TMDB key)
#   ./build.sh --generic   without it, as the APK on GitHub's releases: no key, and the TV's time zone gives the city
set -euo pipefail
cd "$(dirname "$0")"
# FREEMYTV_OUT / FREEMYTV_APK: another build folder and APK name, so two builds can run side by side.
OUT="${FREEMYTV_OUT:-out}"
APK="${FREEMYTV_APK:-FreeMyTV.apk}"
GENERIC=false
for arg in "$@"; do [ "$arg" = "--generic" ] && GENERIC=true; done

# --- Tools
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Install it and set ANDROID_HOME (see README)."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found. Run: sdkmanager \"platforms;android-34\""; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || { echo "JDK not found. Install JDK 17 and set JAVA_HOME."; exit 1; }

# --- Settings: config.properties -> Config.java
CONFIG=config.properties
if $GENERIC; then
  CONFIG=/dev/null   # every value at its default; the place comes from the TV's time zone (Place)
elif [ ! -f config.properties ]; then
  cp config.example.properties config.properties
  echo "Created config.properties: add your TMDB key and your city there (optional), then build again."
fi
# Notepad's "Unicode" is UTF-16: grep would find no keys in it and build every default without a word.
case "$(head -c 2 "$CONFIG" 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
  fffe|feff) echo "config.properties is saved as UTF-16 (Notepad's \"Unicode\"). Save it as UTF-8 and build again." >&2; exit 1 ;;
esac
prop() {
  local v
  v="$(grep -E "^[[:space:]]*$1[[:space:]]*=" "$CONFIG" | tail -1 | cut -d= -f2- || true)"
  v="$(printf '%s' "$v" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | tr -d '"\\')"
  printf '%s' "${v:-$2}"
}
number() {
  local v; v="$(prop "$1" "$2")"
  [[ "$v" =~ ^-?[0-9]+(\.[0-9]+)?$ ]] || { echo "config.properties: $1 must be a number (got \"$v\")" >&2; exit 1; }
  printf '%s' "$v"
}
if $GENERIC; then
  LAT=""; LON=""; REGION=""; CURRENCY=""
else
  LAT="$(number latitude 40.7128)"
  LON="$(number longitude -74.0060)"
  REGION="$(prop tmdb_region US)"
  CURRENCY="$(prop currency USD)"
fi
ASSIST=false; [ "$(prop disable_assistant false)" = "true" ] && ASSIST=true
CRYPTO=false; [ "$(prop show_crypto false)" = "true" ] && CRYPTO=true
ANIMATION=false; [ "$(prop hide_animation false)" = "true" ] && ANIMATION=true
SAVER=true; [ "$(prop screensaver true)" = "false" ] && SAVER=false
# preview=true: sample picks with generated photos (preview/art) instead of TMDB, to see the Top Shelf without a key.
PREVIEW=false; [ "$(prop preview false)" = "true" ] && PREVIEW=true
# Welly (WELLY-HOME-REMOTE-SPEC.md §3.2) and the Home tab's Kasa devices (§4.2).
WELLY_MODEL="$(prop welly_model gemini-3.8-flash)"; WELLY_LITE="$(prop welly_lite_model gemini-3.5-flash-lite)"
OLLAMA_MODEL="$(prop ollama_model qwen3:8b)"
# Profiles (MAHITTV-PROFILES-SPEC.md §1): id:Name:role:PIN[:avatar],… The PINs never go into the app: each becomes
# sha256(salt + PIN) with a salt made for this build. The old form Name:avatar.png still works (no PIN).
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi
}
PIN_SALT="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
PROFILES=""
IFS=',' read -ra ENTRIES <<< "$(prop profiles '')"
for entry in "${ENTRIES[@]}"; do
  entry="$(printf '%s' "$entry" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [ -z "$entry" ] && continue
  IFS=':' read -r a b c d e <<< "$entry"
  if [ -z "$c" ]; then   # Name[:avatar.png]
    id="$(printf '%s' "$a" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')"
    name="$a"; role=standard; pin=""; avatar="$b"
  else
    id="$(printf '%s' "$a" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')"; name="$b"; role="$c"; pin="$d"; avatar="$e"
  fi
  case "$role" in manager|standard|guest) ;; *) echo "config.properties: profiles: role must be manager, standard or guest (got \"$role\")" >&2; exit 1 ;; esac
  if [ -n "$pin" ] && ! [[ "$pin" =~ ^[0-9]{4}$ ]]; then
    echo "config.properties: profiles: $name's PIN must be 4 digits" >&2; exit 1
  fi
  hash=""; [ -n "$pin" ] && hash="$(sha256 "$PIN_SALT$pin")"
  PROFILES="$PROFILES${PROFILES:+,}$id:$name:$role:$hash:$avatar"
done
DEFAULT_DOCK="$(prop default_dock_apps '')"
STREMIO=false; [ "$(prop open_in_stremio false)" = "true" ] && STREMIO=true

# Servers on your own network (Immich, and the next feature that needs one) often answer in plain HTTP. One rule for
# all of them: a server given as http:// must be on the local network, and only its host may skip HTTPS
# (network_security_config.xml, below). A new one is read with `lan_server <key>`, then `prop <key>`.
LAN_HTTP_HOSTS=""
lan_host() {   # a private IPv4 address, or a .local, .lan or .home.arpa name, or a one-word name
  local h a b
  h="$(printf '%s' "$1" | tr 'A-Z' 'a-z')"
  if [[ "$h" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
    a=$((10#${BASH_REMATCH[1]})); b=$((10#${BASH_REMATCH[2]}))
    [ $a -eq 10 ] || { [ $a -eq 172 ] && [ $b -ge 16 ] && [ $b -le 31 ]; } || { [ $a -eq 192 ] && [ $b -eq 168 ]; }
    return
  fi
  [[ "$h" =~ ^[a-z0-9-]+$ || "$h" =~ ^[a-z0-9.-]+\.(local|lan|home\.arpa)$ ]]
}
lan_server() {   # lan_server <key>: checks it; the host of an http:// one joins LAN_HTTP_HOSTS
  local v host
  v="$(prop "$1" '')"
  case "$v" in
    "" | https://*) ;;
    http://*)
      host="$(printf '%s' "$v" | sed -E -e 's#^http://##' -e 's#[/?].*$##' -e 's#:[0-9]+$##' | tr 'A-Z' 'a-z')"
      lan_host "$host" || {
        echo "config.properties: $1 in plain http:// must be on your own network (a private address like" >&2
        echo "192.168.1.20, or a .local, .lan or .home.arpa name); use https:// for anything else (over Tailscale, the" >&2
        echo "server's https://<machine>.<tailnet>.ts.net address). Got \"$v\"" >&2
        exit 1
      }
      case " $LAN_HTTP_HOSTS " in *" $host "*) ;; *) LAN_HTTP_HOSTS="$LAN_HTTP_HOSTS $host" ;; esac
      ;;
    *) echo "config.properties: $1 must start with http:// or https:// (got \"$v\")" >&2; exit 1 ;;
  esac
}
lan_server immich_server
# Keys that would let another app on the TV into the user's own servers stay out of the APK: tools/send_key.py sends
# them to the TV (Secrets).
for key in immich_api_key; do
  if [ -n "$(prop "$key" '')" ]; then
    echo "config.properties: $key isn't built into the app (any app on the TV could read it there)." >&2
    echo "Send it to the TV instead (python3 tools/send_key.py <TV IP> $key), then remove it here." >&2
  fi
done

rm -rf "$OUT" && mkdir -p "$OUT/gen/io/freemytv" "$OUT/classes"
cat > $OUT/gen/io/freemytv/Config.java <<EOF
package io.freemytv;

/** Generated by build.sh from config.properties: edit that file, not this one. */
final class Config {
    private Config() {}

    static final String TMDB_KEY = "$(prop tmdb_key '')";
    static final String WATCHMODE_KEY = "$(prop watchmode_key '')";
    static final String PLEX_URL = "$(prop plex_url '')";
    static final String PLEX_TOKEN = "$(prop plex_token '')";
    static final String TRAKT_CLIENT_ID = "$(prop trakt_client_id '')";
    static final String TMDB_REGION = "$REGION";
    static final String LATITUDE = "$LAT";
    static final String LONGITUDE = "$LON";
    static final String CITY = "$(prop city '')";
    static final String LANGUAGE = "$(prop language auto)";
    static final String CURRENCY = "$CURRENCY";
    static final String TEMPERATURE = "$(prop temperature auto)";
    static final String HIDE_SERVICES = "$(prop hide_services '')";
    static final String THEME = "$(prop theme auto)";
    static final String PROFILES = "$PROFILES";   // id:Name:role:sha256(PIN_SALT+PIN):avatar,… (ProfileManager)
    static final String PIN_SALT = "$PIN_SALT";
    static final String DEFAULT_DOCK = "$DEFAULT_DOCK";   // packages: each new profile's Dock, in order
    static final String IMMICH_SERVER = "$(prop immich_server '')";
    static final boolean DISABLE_ASSISTANT = $ASSIST;
    static final boolean SHOW_CRYPTO = $CRYPTO;
    static final boolean HIDE_ANIMATION = $ANIMATION;
    static final boolean SCREENSAVER = $SAVER;   // false: the TV keeps its own
    static final boolean GENERIC = $GENERIC;   // built with --generic, as the APK on GitHub's releases (Updates)
    static final boolean OPEN_IN_STREMIO = $STREMIO;
    static final boolean PREVIEW = $PREVIEW;   // sample picks (Preview), never in a release
    static final String GEMINI_KEY = "$(prop gemini_key '')";   // Welly's first brain (secret: config.properties only)
    static final String WELLY_MODEL = "$WELLY_MODEL";
    static final String WELLY_LITE_MODEL = "$WELLY_LITE";
    static final String OLLAMA_URL = "$(prop ollama_url '')";   // e.g. http://192.168.1.10:11434 (the Mac)
    static final String OLLAMA_MODEL = "$OLLAMA_MODEL";
    static final String KASA = "$(prop kasa '')";   // fixture=IP:type,… (SmartHome)
}
EOF

# --- Plain HTTP: HTTPS everywhere, except to this TV itself (127.0.0.1: a local brain), to ollama_url's host (the
# Mac's Ollama on the LAN) and to the hosts lan_server let through. Android refuses cleartext otherwise and Welly
# would skip those brains.
OLLAMA_HOST="$(prop ollama_url '' | sed -E -e 's#^[A-Za-z]+://##' -e 's#[/?].*$##' -e 's#:[0-9]+$##')"
if [ -n "$OLLAMA_HOST" ] && ! [[ "$OLLAMA_HOST" =~ ^[A-Za-z0-9.-]+$ ]]; then
  echo "config.properties: ollama_url needs a host name or an IPv4 address (got \"$OLLAMA_HOST\")" >&2; exit 1
fi
mkdir -p $OUT/res-gen/xml
{
  echo '<?xml version="1.0" encoding="utf-8"?>'
  echo '<!-- Generated by build.sh from config.properties: edit that file, not this one. -->'
  echo '<network-security-config>'
  echo '    <base-config cleartextTrafficPermitted="false" />'
  echo '    <domain-config cleartextTrafficPermitted="true">'
  echo '        <domain includeSubdomains="false">127.0.0.1</domain>'
  echo '        <domain includeSubdomains="false">localhost</domain>'
  if [ -n "$OLLAMA_HOST" ]; then echo "        <domain includeSubdomains=\"false\">$OLLAMA_HOST</domain>"; fi
  for host in $LAN_HTTP_HOSTS; do echo "        <domain includeSubdomains=\"false\">$host</domain>"; done
  echo '    </domain-config>'
  echo '</network-security-config>'
} > $OUT/res-gen/xml/network_security_config.xml

# --- Build
"$BT/aapt2" compile --dir app/res -o $OUT/res.zip
"$BT/aapt2" compile --dir $OUT/res-gen -o $OUT/res-gen.zip
ASSETS=(-A app/assets)
if $PREVIEW; then
  mkdir -p $OUT/preview-assets/preview
  cp preview/art/*.jpg $OUT/preview-assets/preview/
  for f in preview/art/*.png; do if [ -f "$f" ]; then cp "$f" $OUT/preview-assets/preview/; fi; done   # title logos
  ASSETS+=(-A $OUT/preview-assets)
fi
# Fonts uncompressed: Android reads them straight from the APK.
"$BT/aapt2" link -o $OUT/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java $OUT/gen $OUT/res.zip $OUT/res-gen.zip \
  --min-sdk-version 26 --target-sdk-version 33 "${ASSETS[@]}" -0 ttf -0 jpg
# Windows aapt2 packs assets with backslash separators ("assets/symbols\x.png") and Android
# cannot find them: the home screen loses its icons and fonts. Rename the zip entries to "/".
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)
    cat > $OUT/FixAssets.java <<'EOF'
import java.nio.file.*;
import java.util.zip.*;

class FixAssets {
    public static void main(String[] args) throws Exception {
        Path apk = Paths.get(args[0]);
        Path tmp = apk.resolveSibling(apk.getFileName() + ".fixed");
        int renamed = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(apk));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                String name = e.getName().replace('\\', '/');
                if (!name.equals(e.getName())) renamed++;
                ZipEntry n = new ZipEntry(name);
                if (e.getMethod() == ZipEntry.STORED) { // Android reads fonts straight from the APK
                    n.setMethod(ZipEntry.STORED);
                    n.setSize(data.length);
                    n.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    n.setCrc(crc.getValue());
                }
                out.putNextEntry(n);
                out.write(data);
                out.closeEntry();
            }
        }
        if (renamed > 0) Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
        else Files.delete(tmp);
        System.out.println("FixAssets: renamed " + renamed + " asset paths in " + apk.getFileName());
    }
}
EOF
    "$JAVA" $OUT/FixAssets.java $OUT/base.apk
    ;;
esac
# On Windows javac separates paths with ";", and d8 and apksigner are .bat files. Git Bash can't start a .bat whose
# path has a space (and the wrappers trip over a JAVA_HOME that has one), so there the jars inside them are run with the
# JDK's java instead; on macOS and Linux the usual executables are used.
CPSEP=:; BAT=; case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";"; BAT=.bat ;; esac
d8() {
  if [ -n "$BAT" ]; then "$JAVA" -Xmx1024M -Xss1m -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"
  else "$BT/d8" "$@"; fi
}
apksigner() {
  if [ -n "$BAT" ]; then "$JAVA" -jar "$BT/lib/apksigner.jar" "$@"
  else "$BT/apksigner" "$@"; fi
}
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ$CPSEP$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -encoding UTF-8 -d $OUT/classes $(find app/src $OUT/gen -name '*.java')
d8 --release --min-api 26 --lib "$AJ" --output "$OUT" $(find $OUT/classes -name '*.class')
if command -v zip >/dev/null; then (cd "$OUT" && zip -qj base.apk classes.dex)
else "${JAVA_HOME:+$JAVA_HOME/bin/}jar" -u -M -f $OUT/base.apk -C "$OUT" classes.dex; fi # Git Bash has no zip
"$BT/zipalign" -f -p 4 $OUT/base.apk $OUT/aligned.apk

# --- Sign with your own key (created once; updates need the same key, so keep freemytv.keystore).
# Releases sign with the project's key instead: FREEMYTV_KEYSTORE=<its file>, its password in FREEMYTV_KEYSTORE_PASS.
if [ -n "${FREEMYTV_KEYSTORE:-}" ]; then
  [ -n "${FREEMYTV_KEYSTORE_PASS:-}" ] || { echo "FREEMYTV_KEYSTORE_PASS is empty"; exit 1; }
  apksigner sign --ks "$FREEMYTV_KEYSTORE" --ks-pass env:FREEMYTV_KEYSTORE_PASS --key-pass env:FREEMYTV_KEYSTORE_PASS \
    --out "$APK" $OUT/aligned.apk
else
  if [ ! -f freemytv.keystore ]; then
    "$KEYTOOL" -genkeypair -keystore freemytv.keystore -alias freemytv -keyalg RSA -keysize 2048 -validity 20000 \
      -storepass freemytv -keypass freemytv -dname "CN=Free My TV" >/dev/null 2>&1
  fi
  apksigner sign --ks freemytv.keystore --ks-pass pass:freemytv --key-pass pass:freemytv --out "$APK" $OUT/aligned.apk
fi
echo "Built $APK ($(du -h "$APK" | cut -f1)). Install: adb install -r $APK"
