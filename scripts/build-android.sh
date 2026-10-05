#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(echo "$ROOT"/.tools/jdk-host/*/Contents/Home)}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$ROOT/.tools/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
cd "$ROOT"
DATA="${MEAL_DATA_DIR:-$ROOT/sample-household}"
# Artifacts belong beside the private data home, never inside a household.
if [[ -n "${MEAL_BUILD_DIR:-}" ]]; then
  BUILDS="$MEAL_BUILD_DIR"
elif [[ -n "${MEAL_HOME:-}" ]]; then
  BUILDS="$MEAL_HOME/builds"
else
  BUILDS="$(mktemp -d "${TMPDIR:-/tmp}/meal-garden-builds.XXXXXX")"
fi
mkdir -p "$BUILDS/releases"
mkdir -p app/android/app/src/main/assets
MEAL_DATA="$DATA" node --input-type=module -e 'import {snapshot} from "./companion/domain.mjs"; import fs from "node:fs"; fs.writeFileSync("app/android/app/src/main/assets/snapshot.json",JSON.stringify(snapshot(process.env.MEAL_DATA)));'
cd app/android
"$ROOT/.tools/gradle-8.14.3/bin/gradle" --console=plain assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$BUILDS/meal-garden.apk"
cd "$ROOT"
APP_VERSION="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/android/app/build.gradle.kts | head -n 1)"
APP_CODE="$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' app/android/app/build.gradle.kts | head -n 1)"
APK_HASH="$(shasum -a 256 "$BUILDS/meal-garden.apk" | cut -d ' ' -f 1)"
cp -n "$BUILDS/meal-garden.apk" "$BUILDS/releases/meal-garden-${APP_VERSION}-code${APP_CODE}-${APK_HASH:0:12}.apk"
tar -czf "$BUILDS/releases/meal-garden-source-${APP_VERSION}-code${APP_CODE}-${APK_HASH:0:12}.tar.gz" \
  app/android/app/src/main/java app/android/app/src/main/AndroidManifest.xml app/android/app/build.gradle.kts
printf '%s  meal-garden.apk\n' "$APK_HASH" > "$BUILDS/meal-garden.apk.sha256"
echo "APK: $BUILDS/meal-garden.apk"
