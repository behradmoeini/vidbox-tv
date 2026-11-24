#!/usr/bin/env bash
# Build Vidbox TV Android TV app (plain Java, Android framework only)
# javac -> d8 -> aapt2 -> zipalign -> apksigner. Output: build/VidboxTV.apk
# Usage: scripts/build.sh [--install]
#   VC / VN                               versionCode / versionName (default 1 / dev)
#   VB_KS VB_KS_PASS VB_ALIAS VB_KEY_PASS signing key (default: local debug keystore)
set -euo pipefail
cd "$(dirname "$0")/.."

# Prefer JDK 17 for D8 compatibility
if [ -d /opt/homebrew/opt/openjdk@17/bin ]; then
  export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
elif [ -d /opt/homebrew/opt/openjdk/bin ]; then
  export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools; do
    [ -d "$d/platforms" ] && SDK="$d" && break
  done
fi
[ -n "$SDK" ] || { echo "Android SDK not found (set ANDROID_HOME)." >&2; exit 1; }

ANDROID_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
BT="${ANDROID_BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
D8_JAR="$BT/lib/d8.jar"

KS="${VB_KS:-keystore/debug.jks}"; KS_PASS="${VB_KS_PASS:-android}"
ALIAS="${VB_ALIAS:-vidbox}"; KEY_PASS="${VB_KEY_PASS:-android}"
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias vidbox -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Vidbox TV"
fi

OUT=build/target
mkdir -p build "$OUT/classes" "$OUT/dex"
rm -rf "$OUT/classes"/* "$OUT/dex"/*

echo "Compiling Java sources..."
javac -g:none -Xlint:-options -encoding UTF-8 --release 11 -classpath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src -name '*.java')

echo "Dexing with D8..."
if [ -f "$D8_JAR" ]; then
  java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')
else
  "$BT/d8" --release --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')
fi

echo "Compiling resources with aapt2..."
"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest app/AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "${VC:-1}" --version-name "${VN:-dev}" "$OUT/res.zip"

echo "Packaging APK..."
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)

echo "Aligning and signing..."
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias "$ALIAS" \
  --key-pass "pass:$KEY_PASS" --out build/VidboxTV.apk "$OUT/aligned.apk"
rm -f build/VidboxTV.apk.idsig

echo "Successfully built build/VidboxTV.apk ($(du -h build/VidboxTV.apk | cut -f1))"

if [ "${1:-}" = "--install" ]; then
  echo "Installing to connected device/TV via adb..."
  adb install -r build/VidboxTV.apk
fi
