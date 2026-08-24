#!/usr/bin/env bash
# Собирает Fabric-мод из исходников и публикует его в dist/mods/teyvat.jar.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
MOD_DIR="$REPO/mod"
DIST_JAR="$REPO/dist/mods/teyvat.jar"

cd "$MOD_DIR"
./gradlew --no-daemon clean build

JAR="$(find "$MOD_DIR/build/libs" -maxdepth 1 -type f -name 'teyvat-*.jar' ! -name '*-sources.jar' -print -quit)"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "! собранный teyvat-*.jar не найден в $MOD_DIR/build/libs" >&2
    exit 1
fi

EXPECTED_VERSION="$(sed -n 's/^mod_version=//p' "$MOD_DIR/gradle.properties" | tail -1)"
ACTUAL_VERSION="$(unzip -p "$JAR" fabric.mod.json | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | cut -d'"' -f4)"
if [ "$ACTUAL_VERSION" != "$EXPECTED_VERSION" ]; then
    echo "! версия сборки $ACTUAL_VERSION не совпадает с gradle.properties ($EXPECTED_VERSION)" >&2
    exit 1
fi

mkdir -p "$(dirname "$DIST_JAR")"
cp "$JAR" "$DIST_JAR"
echo "== Teyvat mod собран: $(basename "$JAR") (версия $ACTUAL_VERSION) -> ${DIST_JAR#$REPO/}"
