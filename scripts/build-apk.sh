#!/bin/sh
# Arma el APK de debug contra el backend público (túnel ngrok con dominio fijo) y lo deja
# en dist/el-puesto.apk, que el backend sirve en /descargas (última versión).
# Uso: scripts/build-apk.sh [URL_DEL_API]   (default: el dominio fijo de ngrok)
set -e
cd "$(dirname "$0")/.."
API="${1:-https://sensibly-glad-dodo.ngrok-free.app}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.11+10}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
GRADLE="${GRADLE:-$HOME/gradle-dist/gradle-8.10.2/bin/gradle}"
"$GRADLE" :androidApp:assembleDebug -PapiBaseUrl="$API" --console=plain -q
mkdir -p dist
cp androidApp/build/outputs/apk/debug/androidApp-debug.apk dist/el-puesto.apk
# La página /descargas muestra esta versión (la de androidApp/build.gradle.kts).
sed -n 's/^ *versionName = "\(.*\)"/\1/p' androidApp/build.gradle.kts > dist/VERSION
echo "APK listo: dist/el-puesto.apk v$(cat dist/VERSION) (API: $API) — descarga: $API/descargas"
