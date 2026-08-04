#!/usr/bin/env bash
# Ежедневный цикл (Arch): git pull + переустановка пака копией.
# Если SSH-remote не работает — сам переключает на HTTPS (репозиторий публичный).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Это не git-репозиторий. Сначала: git clone https://github.com/inzexg-coder/Genshin-MC-IRIS.git"
    exit 1
fi

# Пользователь мог вручную перенести jar из репо — восстанавливаем, чтобы не ломал pull.
git checkout -- dist/mods/teyvat.jar 2>/dev/null \
    || git checkout origin/main -- dist/mods/teyvat.jar 2>/dev/null \
    || true

if [ -n "$(git status --porcelain | grep -v '^??')" ]; then
    echo "ВНИМАНИЕ: есть локальные изменения, мешающие pull:"
    git status --short | grep -v '^??'
    echo "Сбрось их: git checkout -- <файл>"
    exit 1
fi

if ! git pull --ff-only; then
    url="$(git remote get-url origin)"
    if [[ "$url" == git@* ]]; then
        echo "== SSH-remote не читается на этом ПК. Переключаю на HTTPS (репо публичное)..."
        git remote set-url origin https://github.com/inzexg-coder/Genshin-MC-IRIS.git
        git pull --ff-only
    else
        echo "== git pull не удался. Проверь сеть/доступ к репозиторию."
        exit 1
    fi
fi

./scripts/install-dev.sh
echo "OK. В игре: F3+R — шейдер, F3+T — ресурспак. Если что-то не так: ./scripts/check-install.sh"
