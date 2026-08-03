#!/usr/bin/env bash
# Твой ежедневный цикл (Arch): git pull + гарантия симлинков в .minecraft.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Это не git-репозиторий. Сначала клонируй проект."
    exit 1
fi

# Если settings.json менялся в игре — сохраняем локально и ставим наш (иначе pull конфликтует)
if [ -n "$(git status --porcelain -- shader/TeyvatShader/shaders/settings.json)" ]; then
    cp shader/TeyvatShader/shaders/settings.json /tmp/settings.local.json
    git checkout -- shader/TeyvatShader/shaders/settings.json
    echo "Твои правки настроек сохранены в /tmp/settings.local.json"
fi

if [ -n "$(git status --porcelain | grep -v 'shader/TeyvatShader/shaders/settings.json')" ]; then
    echo "ВНИМАНИЕ: есть и другие локальные изменения:"
    git status --short | grep -v 'shader/TeyvatShader/shaders/settings.json'
    echo "PULL пропущен — разберись с изменениями или сбрось их (git checkout -- <файл>)."
    exit 1
fi

git pull

MC_DIR="${MC_DIR:-$HOME/.minecraft}"
mkdir -p "$MC_DIR/shaderpacks" "$MC_DIR/resourcepacks"
ln -sfn "$REPO/shader/TeyvatShader" "$MC_DIR/shaderpacks/TeyvatShader"
rm -rf "$MC_DIR/resourcepacks/Teyvat"
cp -r "$REPO/resourcepack" "$MC_DIR/resourcepacks/Teyvat"

echo "OK. В игре: F3+R — шейдер, F3+T — ресурспак."
