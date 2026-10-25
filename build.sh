#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ECJ_JAR="${ECJ_JAR:-${PROJECT_DIR}/../build-deps/ecj-3.40.0.jar}"
# Use a locally installed SDK instead of requiring exactly API/Tools 36.
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    for candidate in "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk; do
        if [[ -d "$candidate/platforms" && -d "$candidate/build-tools" ]]; then
            SDK_DIR="$candidate"; break
        fi
    done
fi
PLATFORM="$(find "${SDK_DIR}/platforms" -maxdepth 1 -type d -name 'android-*' 2>/dev/null | sort -V | tail -1 || true)"
TOOLS="$(find "${SDK_DIR}/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)"
ANDROID_JAR="${PLATFORM}/android.jar"
OUTPUT_DIR="${PROJECT_DIR}/build"
mkdir -p "${OUTPUT_DIR}/classes" "${OUTPUT_DIR}/generated" "${OUTPUT_DIR}/dex"

if [[ ! -f "$ANDROID_JAR" || ! -x "$TOOLS/aapt2" ]]; then
    echo "Não encontrei Android SDK completo em $SDK_DIR." >&2
    echo "Instale Android SDK Platform API 34+ e Build-Tools 34+, por exemplo pelo Android Studio." >&2
    echo "Esperados: platforms/android-*/android.jar e build-tools/*/aapt2" >&2
    exit 1
fi
if [[ ! -f "$ECJ_JAR" ]] && ! command -v javac >/dev/null 2>&1; then
    echo "Instale um JDK (javac) ou configure ECJ_JAR com o compilador ECJ." >&2
    exit 1
fi

"${TOOLS}/aapt2" compile --dir "${PROJECT_DIR}/app/src/main/res" -o "${OUTPUT_DIR}/resources.zip"
"${TOOLS}/aapt2" link -o "${OUTPUT_DIR}/uncompiled.apk" -I "${ANDROID_JAR}" \
    --manifest "${PROJECT_DIR}/app/src/main/AndroidManifest.xml" \
    --java "${OUTPUT_DIR}/generated" -R "${OUTPUT_DIR}/resources.zip" --auto-add-overlay \
    --min-sdk-version 26 --target-sdk-version 34 --version-code 14 --version-name 0.2.1-rc1

find "${PROJECT_DIR}/app/src/main/java" "${OUTPUT_DIR}/generated" -type f -name '*.java' -print > "${OUTPUT_DIR}/sources.txt"
if command -v javac >/dev/null; then
    javac -source 8 -target 8 -cp "$ANDROID_JAR" -d "${OUTPUT_DIR}/classes" @"${OUTPUT_DIR}/sources.txt"
else
    java -jar "$ECJ_JAR" -1.8 -classpath "$ANDROID_JAR" -d "${OUTPUT_DIR}/classes" @"${OUTPUT_DIR}/sources.txt"
fi

find "${OUTPUT_DIR}/classes" -name '*.class' -print > "${OUTPUT_DIR}/classes.txt"
mapfile -t CLASS_FILES < "${OUTPUT_DIR}/classes.txt"
java -cp "${TOOLS}/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
    --lib "$ANDROID_JAR" --output "${OUTPUT_DIR}/dex" "${CLASS_FILES[@]}"
cp "${OUTPUT_DIR}/uncompiled.apk" "${OUTPUT_DIR}/unsigned.apk"
(cd "${OUTPUT_DIR}/dex" && zip -q -u "${OUTPUT_DIR}/unsigned.apk" classes.dex)

"${TOOLS}/zipalign" -p -f 4 "${OUTPUT_DIR}/unsigned.apk" "${OUTPUT_DIR}/aligned.apk"
KEYSTORE="${OUTPUT_DIR}/vieraflow-test.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
    keytool -genkeypair -noprompt -keystore "$KEYSTORE" -alias vieraflow -storepass android -keypass android \
        -dname "CN=VieraFlow Teste,O=Sebasty" -keyalg RSA -keysize 2048 -validity 3650
fi
"${TOOLS}/apksigner" sign --ks "$KEYSTORE" --ks-key-alias vieraflow --ks-pass pass:android \
    --key-pass pass:android --out "${PROJECT_DIR}/VieraFlow-0.2.1-rc1-debug.apk" "${OUTPUT_DIR}/aligned.apk"
"${TOOLS}/apksigner" verify --verbose "${PROJECT_DIR}/VieraFlow-0.2.1-rc1-debug.apk"
echo "APK gerado: ${PROJECT_DIR}/VieraFlow-0.2.1-rc1-debug.apk"
