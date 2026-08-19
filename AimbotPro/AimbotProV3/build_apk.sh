#!/usr/bin/env bash
# AimbotPro v4.0 — APK build script
# Usage:
#   bash build_apk.sh [debug|release]
#
# Release signing via env vars (picked up by ~/.gradle/init.d/):
#   export AIMBOT_KEYSTORE_FILE=/path/to/release.jks
#   export AIMBOT_KEYSTORE_PASSWORD=storepass
#   export AIMBOT_KEY_ALIAS=alias
#   export AIMBOT_KEY_PASSWORD=keypass
#   bash build_apk.sh release
#
# If no keystore env vars are set, release builds fall back to the debug
# keystore (convenient for CI / local testing — NOT for production).
set -euo pipefail

cd "$(dirname "$0")"

VERSION="4.0.0"
BUILD_TYPE="${1:-debug}"
BUILD_HASH="${GITHUB_SHA:-${CI_COMMIT_SHA:-dev}}"

# ── Android SDK discovery ──
if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
    if [[ -f local.properties ]]; then
        SDK_DIR=$(grep -E "^sdk\.dir=" local.properties | cut -d= -f2)
        export ANDROID_HOME="$SDK_DIR"
        export ANDROID_SDK_ROOT="$SDK_DIR"
    else
        for p in "/home/z/android-sdk" "/opt/android-sdk" "$HOME/Android/Sdk"; do
            if [[ -d "$p" ]]; then
                export ANDROID_HOME="$p"
                export ANDROID_SDK_ROOT="$p"
                break
            fi
        done
    fi
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
    echo "[!] Android SDK not found. Set ANDROID_HOME or create local.properties with:"
    echo "    sdk.dir=/path/to/Android/Sdk"
    exit 1
fi
echo "[i] Using SDK at: $ANDROID_HOME"

# ── Keystore validation for release ──
if [[ "$BUILD_TYPE" == "release" && -n "${AIMBOT_KEYSTORE_FILE:-}" ]]; then
    if [[ ! -f "$AIMBOT_KEYSTORE_FILE" ]]; then
        echo "[!] AIMBOT_KEYSTORE_FILE=$AIMBOT_KEYSTORE_FILE not found"
        exit 1
    fi
    echo "[i] Release signing: $AIMBOT_KEYSTORE_FILE (alias=${AIMBOT_KEY_ALIAS:-?})"
else
    if [[ "$BUILD_TYPE" == "release" ]]; then
        echo "[w] No AIMBOT_KEYSTORE_FILE set — release will use debug keystore"
    fi
fi

# ── Gradle binary ──
GRADLE_BIN="./gradlew"
if ! [[ -x "$GRADLE_BIN" ]]; then
    if command -v gradle >/dev/null 2>&1; then
        GRADLE_BIN="gradle"
    else
        echo "[!] No gradle wrapper and no system gradle. Run:"
        echo "    gradle wrapper --gradle-version 8.5 --distribution-type bin"
        exit 1
    fi
fi

case "$BUILD_TYPE" in
    debug|release) ;;
    *)
        echo "[!] Build type must be 'debug' or 'release' (got: $BUILD_TYPE)"
        exit 1
        ;;
esac

# ── Verify model asset ──
MODEL_FILE="app/src/main/assets/models/yolov8n.tflite"
if [[ -f "$MODEL_FILE" ]]; then
    MODEL_SIZE=$(stat -c%s "$MODEL_FILE" 2>/dev/null || stat -f%z "$MODEL_FILE" 2>/dev/null || echo "?")
    echo "[i] TFLite model: $MODEL_FILE ($MODEL_SIZE bytes)"
else
    echo "[w] No model at $MODEL_FILE — app will run in DEMO MODE"
fi

# ── Build ──
TASK="assemble${BUILD_TYPE^}"
echo "[*] Building AimbotPro v$VERSION ($BUILD_TYPE, hash=$BUILD_HASH)..."

GRADLE_ARGS=("$TASK" "--console=plain" "-PbuildHash=$BUILD_HASH")

"$GRADLE_BIN" "${GRADLE_ARGS[@]}"

# ── Locate APK ──
APK="app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk"
if [[ -f "$APK" ]]; then
    APK_SIZE=$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK" 2>/dev/null || echo "?")
    echo "[OK] APK built: $APK ($APK_SIZE bytes)"
    OUT_NAME="AimbotPro-v${VERSION}-${BUILD_TYPE}.apk"
    cp -v "$APK" "$OUT_NAME" || true
else
    echo "[!] Build completed but APK not found at: $APK"
    exit 2
fi
