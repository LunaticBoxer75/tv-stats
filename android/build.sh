#!/bin/sh
# Builds build/tvstats.apk with the Android SDK's command-line tools (no Gradle).
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
rm -rf build && mkdir -p build/classes build/gen
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen -o build/unsigned.apk build/res.zip \
    --debug-mode --min-sdk-version 26 --target-sdk-version 31 --version-code 1 --version-name 0.1
javac --release 11 -nowarn -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out build/tvstats.apk build/aligned.apk
echo "Built $(pwd)/build/tvstats.apk"
