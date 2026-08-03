#!/usr/bin/env bash
# Живая установка: шейдер — симлинк (Iris его понимает),
# ресурспак — реальная копия (ваниль блокирует симлинки предупреждением).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${MC_DIR:-}" ]; then
  case "$(uname -s)" in
    Darwin) MC_DIR="$HOME/Library/Application Support/minecraft" ;;
    *)      MC_DIR="$HOME/.minecraft" ;;
  esac
fi
mkdir -p "$MC_DIR/shaderpacks" "$MC_DIR/resourcepacks"
ln -sfn "$REPO/shader/TeyvatShader" "$MC_DIR/shaderpacks/TeyvatShader"
rm -rf "$MC_DIR/resourcepacks/Teyvat"
cp -r "$REPO/resourcepack" "$MC_DIR/resourcepacks/Teyvat"
echo "OK:"
echo "  $MC_DIR/shaderpacks/TeyvatShader  (симлинк)"
echo "  $MC_DIR/resourcepacks/Teyvat      (копия, без симлинков)"
echo "В игре: F3+R — шейдеры, F3+T — ресурспаки."
