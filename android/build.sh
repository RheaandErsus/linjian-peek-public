#!/bin/bash
set -e

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0

PROJECT="$(cd "$(dirname "$0")" && pwd)"
SRC=$PROJECT/app/src/main
OUT=$PROJECT/build
PKG_PATH=dev/linjian/peek
APP_ID=${APP_ID:-dev.rhea.erses.guardian}
APP_VERSION=${APP_VERSION:-0.4.0}

if [ ! -f "$PLATFORM" ]; then
  echo "Android platform not found: $PLATFORM"
  echo "Install Android SDK platform 34 first."
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk" "$OUT/compiled_res"

echo "=== Compiling resources ==="
$BUILD_TOOLS/aapt2 compile --dir "$SRC/res" -o "$OUT/compiled_res/"

echo "=== Linking resources ==="
$BUILD_TOOLS/aapt2 link \
    -o "$OUT/apk/app.unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$SRC/AndroidManifest.xml" \
    --rename-manifest-package "$APP_ID" \
    --java "$OUT/gen" \
    --auto-add-overlay \
    -R "$OUT/compiled_res"/*.flat

echo "=== Compiling AIDL ==="
if [ -d "$SRC/aidl" ]; then
    while IFS= read -r aidl_file; do
        $BUILD_TOOLS/aidl \
            -p"$ANDROID_HOME/platforms/android-34/framework.aidl" \
            -I"$SRC/aidl" \
            -o"$OUT/gen" \
            "$aidl_file"
    done < <(find "$SRC/aidl" -name "*.aidl" -type f | sort)
fi

echo "=== Compiling Java ==="
find "$SRC/java" "$OUT/gen" -name "*.java" -type f | sort > "$OUT/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "=== Creating DEX ==="
$BUILD_TOOLS/d8 --output "$OUT/apk/" --lib "$PLATFORM" $(find "$OUT/classes" -name "*.class")

echo "=== Building APK ==="
cd "$OUT/apk"
cp app.unsigned.apk app.tmp.apk
zip -d app.tmp.apk classes.dex 2>/dev/null || true
zip -j app.tmp.apk classes.dex
mv app.tmp.apk app.unsigned.apk

KEYSTORE=$OUT/signing.jks
KEY_ALIAS=${ANDROID_KEY_ALIAS:-erses-guardian}
STORE_PASSWORD=${ANDROID_STORE_PASSWORD:-erses-guardian-debug}
KEY_PASSWORD=${ANDROID_KEY_PASSWORD:-$STORE_PASSWORD}

if [ -n "${ANDROID_KEYSTORE_B64:-}" ]; then
    echo "=== Loading stable release signing key ==="
    printf '%s' "$ANDROID_KEYSTORE_B64" | base64 --decode > "$KEYSTORE"
else
    echo "=== Generating ephemeral DEBUG signing key ==="
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -alias "$KEY_ALIAS" \
        -storepass "$STORE_PASSWORD" \
        -keypass "$KEY_PASSWORD" \
        -dname "CN=Erses Guardian Debug"
fi

echo "=== Aligning ==="
$BUILD_TOOLS/zipalign -f 4 app.unsigned.apk app.aligned.apk

echo "=== Signing APK ==="
$BUILD_TOOLS/apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:"$STORE_PASSWORD" \
    --key-pass pass:"$KEY_PASSWORD" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$PROJECT/ErsesGuardian-v$APP_VERSION.apk" \
    app.aligned.apk

echo ""
echo "=== Done ==="
echo "APK: $PROJECT/ErsesGuardian-v$APP_VERSION.apk"
ls -lh "$PROJECT/ErsesGuardian-v$APP_VERSION.apk"
