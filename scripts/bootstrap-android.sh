#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p .tools/jdk-host .tools/android-sdk/cmdline-tools
ARCH=$(uname -m)
if [ "$ARCH" = arm64 ]; then JDK_ARCH=aarch64; else JDK_ARCH=x64; fi
if ! compgen -G '.tools/jdk-host/*/Contents/Home/bin/java' >/dev/null; then
 curl -fL "https://api.adoptium.net/v3/binary/latest/17/ga/mac/$JDK_ARCH/jdk/hotspot/normal/eclipse" -o .tools/jdk-host.tar.gz
 tar -xzf .tools/jdk-host.tar.gz -C .tools/jdk-host
 rm .tools/jdk-host.tar.gz
fi
export JAVA_HOME="$(echo "$ROOT"/.tools/jdk-host/*/Contents/Home)"
if [ ! -d .tools/gradle-8.14.3 ]; then
 curl -fL https://services.gradle.org/distributions/gradle-8.14.3-bin.zip -o .tools/gradle.zip
 unzip -q .tools/gradle.zip -d .tools
 rm .tools/gradle.zip
fi
if [ ! -d .tools/android-sdk/cmdline-tools/latest ]; then
 curl -fL https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip -o .tools/sdk.zip
 unzip -q .tools/sdk.zip -d .tools/sdk-unpack
 mv .tools/sdk-unpack/cmdline-tools .tools/android-sdk/cmdline-tools/latest
 rm .tools/sdk.zip
fi
# Invoke Java directly: Google's launcher scripts mishandle paths with spaces.
SDK="$ROOT/.tools/android-sdk"
set +o pipefail
yes | "$JAVA_HOME/bin/java" -cp "$SDK/cmdline-tools/latest/lib/*" com.android.sdklib.tool.sdkmanager.SdkManagerCli --sdk_root="$SDK" --licenses
set -o pipefail
"$JAVA_HOME/bin/java" -cp "$SDK/cmdline-tools/latest/lib/*" com.android.sdklib.tool.sdkmanager.SdkManagerCli --sdk_root="$SDK" 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
echo 'Build tools are ready. Run scripts/build-android.sh.'
