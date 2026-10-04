#!/usr/bin/env bash
# Builds a signed APK without Gradle, using the Android tools packaged by Debian/Ubuntu:
#   sudo apt-get install aapt apksigner zipalign dalvik-exchange
# plus a full Android 14 framework jar (from Maven Central): its classes and resource table
# are what we compile and link against.
# Output: build/ClipManager.apk
set -euo pipefail

cd "$(dirname "$0")"
ROOT=$(pwd)
SRC=app/src/main
OUT=build
TOOLS=${ANDROID_BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/debian}
CACHE=${CLIPMANAGER_CACHE:-$HOME/.cache/clipmanager}
FRAMEWORK_JAR=$CACHE/android-all-14.jar
FRAMEWORK_URL=https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
KEYSTORE=keystore/clipmanager.jks
KS_PASS=clipmanager

if [ ! -f "$FRAMEWORK_JAR" ]; then
    mkdir -p "$CACHE"
    echo "Downloading Android 14 framework jar..."
    curl -fsSL -o "$FRAMEWORK_JAR.part" "$FRAMEWORK_URL"
    mv "$FRAMEWORK_JAR.part" "$FRAMEWORK_JAR"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "[1/5] Resources"
"$TOOLS/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$TOOLS/aapt2" link -o "$OUT/unsigned.apk" \
    -I "$FRAMEWORK_JAR" \
    --manifest "$SRC/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 24 --target-sdk-version 34 \
    "$OUT/res.zip"

echo "[2/5] Java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# java.* comes from the JDK (Java 8 API, so dx can handle the bytecode), android.* from the jar
javac -nowarn --release 8 -encoding UTF-8 \
    -classpath "$FRAMEWORK_JAR" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/5] Dex"
"$TOOLS/dx" --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "[4/5] Align"
"$TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] Sign"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p keystore
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias clipmanager -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Clip Manager, O=simog, C=IT"
fi
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --min-sdk-version 24 --out "$OUT/ClipManager.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify "$OUT/ClipManager.apk"

echo "OK -> $ROOT/$OUT/ClipManager.apk"
