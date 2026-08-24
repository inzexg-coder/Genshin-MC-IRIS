#!/usr/bin/env bash
# Ежедневный цикл (Arch): fetch + переустановка пака копией.
# Если SSH-remote не работает — сам переключается на HTTPS (репозиторий публичный).
# ВАЖНО: скрипт приводит локальный клон к состоянию origin/main, поэтому
# ручные правки в этом клоне будут потеряны (см. docs/WORKFLOW.md -> "Хочу откат").
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Это не git-репозиторий. Сначала: git clone https://github.com/inzexg-coder/Genshin-MC-IRIS.git"
    exit 1
fi

echo "== Teyvat update: локальный коммит $(git log --oneline -1 2>/dev/null || echo '?')"

fetch_ok() {
    git fetch origin --prune
}

if ! fetch_ok; then
    url="$(git remote get-url origin)"
    if [[ "$url" == git@* ]]; then
        echo "== SSH-remote не читается на этом ПК. Переключаю на HTTPS (репо публичное)..."
        git remote set-url origin https://github.com/inzexg-coder/Genshin-MC-IRIS.git
    fi
    echo "== Повторный fetch..."
    fetch_ok || { echo "! не удалось достучаться до GitHub. Проверь сеть: curl -I https://github.com"; exit 1; }
fi

# Локальные правки (например, случайно изменённый jar) не должны мешать:
# сначала пробуем fast-forward, при расхождении — жёсткий сброс на origin/main.
git checkout -- dist/mods/teyvat.jar 2>/dev/null || true
if git diff --quiet HEAD origin/main -- . 2>/dev/null; then
    echo "Уже актуально (origin/main)."
else
    if git merge-base --is-ancestor HEAD origin/main 2>/dev/null; then
        echo "== Обновляю до origin/main (fast-forward)..."
        git merge --ff-only origin/main
    else
        echo "!! Локальная ветка разошлась с origin/main — делаю сброс на актуальный main"
        echo "   (все ручные правки в этом клоне будут потеряны)"
        git reset --hard origin/main
    fi
fi

# Собираем мод из актуальных исходников. Это исключает установку устаревшего бинарника.
./scripts/build-mod.sh

echo "== Teyvat update: теперь коммит $(git log --oneline -1)"
./scripts/install-dev.sh
echo "OK. В игре: F3+R — шейдер, F3+T — ресурспак. Если что-то не так: ./scripts/check-install.sh"
