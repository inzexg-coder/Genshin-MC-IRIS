#!/usr/bin/env bash
# Один раз: завести приватный репозиторий и привязать remote origin.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

if git remote get-url origin >/dev/null 2>&1; then
    echo "remote origin уже есть: $(git remote get-url origin)"
    exit 0
fi

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh repo create teyvat-mc --private --source . --push
    echo "Готово: приватный репозиторий создан и запушен."
    exit 0
fi

cat <<'MSG'
Сделай вручную (1 раз):
1. GitHub/GitLab → New repository → приватный, НЕ создавай README (чтобы не конфликтовало).
2. Выполни одну из команд:
     git remote add origin git@github.com:ТВОЙ_ЛОГИН/teyvat-mc.git
     git remote add origin git@gitlab.com:ТВОЙ_ЛОГИН/teyvat-mc.git
3. git push -u origin main
MSG
