#!/usr/bin/env bash
# Упаковка релизных zip + sha1 (для раздачи, в т.ч. серверного ресурспака).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$REPO/dist"
mkdir -p "$DIST"

cd "$REPO/resourcepack"
rm -f "$DIST/Teyvat-Resources.zip"
zip -qr "$DIST/Teyvat-Resources.zip" .
SHA1=$(sha1sum "$DIST/Teyvat-Resources.zip" | awk '{print $1}')
echo "RESOURCE PACK: $DIST/Teyvat-Resources.zip"
echo "sha1: $SHA1"

cd "$REPO/shader/TeyvatShader"
rm -f "$DIST/TeyvatShader.zip"
zip -qr "$DIST/TeyvatShader.zip" .
echo "SHADER PACK:   $DIST/TeyvatShader.zip"

echo "--- server.properties ---"
echo "resource-pack=<URL http/https до $DIST/Teyvat-Resources.zip>"
echo "resource-pack-sha1=$SHA1"
echo "require-resource-pack=true"
echo "--- URL должен быть http/https; file:// подходит только для локального теста ---"
