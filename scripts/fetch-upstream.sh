#!/usr/bin/env bash
# Скачивает оригинальную базу Complementary Reimagined для диффов и обновлений.
set -euo pipefail
VER="r5.8.1"
URL="https://cdn.modrinth.com/data/HVnmMxH1/versions/yCCduG44/ComplementaryReimagined_${VER}.zip"
DEST="shader/upstream"
mkdir -p "$DEST"
curl -sL -o "$DEST/ComplementaryReimagined_${VER}.zip" "$URL"
rm -rf "$DEST/ComplementaryReimagined"
unzip -q -o "$DEST/ComplementaryReimagined_${VER}.zip" -d "$DEST/ComplementaryReimagined"
echo "OK: $DEST/ComplementaryReimagined ($VER)"
