#!/usr/bin/env bash
# Диагностика: что реально установлено на этом ПК и совпадает ли с репозиторием.
set -uo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${MC_DIR:-}" ]; then
  case "$(uname -s)" in
    Darwin) MC_DIR="$HOME/Library/Application Support/minecraft" ;;
    *)      MC_DIR="$HOME/.minecraft" ;;
  esac
fi
PACK="$MC_DIR/shaderpacks/TeyvatShader"
FAIL=0

echo "== 1) Конфиг Iris ($MC_DIR/config/iris.properties)"
if [ -f "$MC_DIR/config/iris.properties" ]; then
  grep -E '^(shaderPack|enableShaders)=' "$MC_DIR/config/iris.properties"
else
  echo "   НЕТ файла — Iris ещё не запускался на этом ПК."
fi

echo "== 2) Шейдерпак ($PACK)"
if [ ! -e "$PACK" ]; then
  echo "   НЕТ папки. Установи: ./scripts/install-dev.sh"
  exit 1
fi
[ -L "$PACK" ] && echo "   это симлинк -> $(readlink -f "$PACK")"
if [ ! -d "$PACK/shaders" ]; then
  echo "   НЕТ shaders/ — это не шейдерпак (проверь, нет ли вложенной папки TeyvatShader/TeyvatShader)"
  FAIL=1
fi
if [ ! -f "$PACK/shaders/shaders.properties" ]; then
  echo "   НЕТ shaders/shaders.properties — пак повреждён"
  FAIL=1
fi
if [ -f "$PACK/shaders/pack.json" ]; then
  echo "   описание: $(grep -o 'Teyvat Shader[^\"]*' "$PACK/shaders/pack.json" | head -1)"
fi
echo "   файлов: $(find "$PACK" -type f | wc -l) | симлинков внутри: $(find "$PACK" -type l | wc -l)"

echo "== 3) Совпадение с репозиторием (ниже должно быть пусто)"
if ! diff -rq --exclude='settings.json' "$REPO/shader/TeyvatShader" "$PACK" 2>/dev/null; then
  echo "   ^ есть отличия — установлена не текущая версия. Запусти ./scripts/update.sh"
  FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
  echo "== ВСЁ ОК: пак установлен и совпадает с репозиторием."
else
  echo "== ЕСТЬ ПРОБЛЕМЫ (см. выше)."
fi
