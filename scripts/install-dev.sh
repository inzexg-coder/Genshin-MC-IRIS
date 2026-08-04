#!/usr/bin/env bash
# Установка: реальная копия шейдера и ресурспака в ~/.minecraft (никаких симлинков).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${MC_DIR:-}" ]; then
  case "$(uname -s)" in
    Darwin) MC_DIR="$HOME/Library/Application Support/minecraft" ;;
    *)      MC_DIR="$HOME/.minecraft" ;;
  esac
fi
mkdir -p "$MC_DIR/shaderpacks" "$MC_DIR/resourcepacks"
rm -rf "$MC_DIR/shaderpacks/TeyvatShader"
cp -r "$REPO/shader/TeyvatShader" "$MC_DIR/shaderpacks/TeyvatShader"
rm -rf "$MC_DIR/resourcepacks/Teyvat"
cp -r "$REPO/resourcepack" "$MC_DIR/resourcepacks/Teyvat"
echo "OK. Установлено:"
echo "  $MC_DIR/shaderpacks/TeyvatShader"
echo "  $MC_DIR/resourcepacks/Teyvat"
echo "Проверка: ./scripts/check-install.sh"
echo "В игре: Options -> Video Settings -> Shader Packs -> TeyvatShader -> Apply, затем F3+R (шейдер) / F3+T (ресурспак)."
