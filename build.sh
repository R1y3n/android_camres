#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-/home/hp/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-33/android.jar"

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-android}"

APP_NAME="CamRes"
PKG_APK="build/${APP_NAME}-unsigned.apk"
ALIGNED_APK="build/${APP_NAME}-aligned.apk"
OUT_APK="build/${APP_NAME}.apk"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  if [ ! -x "$tool" ]; then
    echo "Missing tool: $tool" >&2
    exit 1
  fi
done
if [ ! -f "$PLATFORM" ]; then
  echo "Missing platform jar: $PLATFORM" >&2
  exit 1
fi
if [ ! -f "$KEYSTORE" ]; then
  echo "Missing keystore: $KEYSTORE" >&2
  exit 1
fi

rm -rf build/gen build/obj build/apk build/compiled_res
mkdir -p build/gen build/obj build/apk build/compiled_res

echo "==> Compile resources"
"$AAPT2" compile --dir res -o build/compiled_res

echo "==> Link resources"
FLATS=(build/compiled_res/*.flat)
"$AAPT2" link \
  -o "$PKG_APK" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --auto-add-overlay \
  "${FLATS[@]}"

echo "==> Compile Java"
# Collect sources
SRC_FILES=( $(find src build/gen -name '*.java' | sort) )
# -parameters avoids null MethodParameters names that can crash some d8/R8 versions with JDK 21 javac
javac \
  -classpath "$PLATFORM" \
  -d build/obj \
  --release 8 \
  -parameters \
  -encoding UTF-8 \
  -Xlint:-options \
  "${SRC_FILES[@]}"

echo "==> DEX"
CLASS_FILES=( $(find build/obj -name '*.class' | sort) )
# d8 outputs classes.dex into --output directory
"$D8" --lib "$PLATFORM" --output build/apk --min-api 21 "${CLASS_FILES[@]}"

echo "==> Package APK"
# base APK from aapt2 link contains resources.arsc, manifest, res; add dex
cp "$PKG_APK" "$OUT_APK"
( cd build/apk && zip -u "../${APP_NAME}.apk" classes.dex >/dev/null )

echo "==> Zipalign"
"$ZIPALIGN" -f -p 4 "$OUT_APK" "$ALIGNED_APK"
mv "$ALIGNED_APK" "$OUT_APK"

echo "==> Sign"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" \
  "$OUT_APK"

"$APKSIGNER" verify --print-certs "$OUT_APK" || true

echo "==> Built: $ROOT/$OUT_APK"
ls -lh "$ROOT/$OUT_APK"
