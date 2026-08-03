#!/usr/bin/env bash
# Живая установка: симлинки шейдера и ресурспака в .minecraft (Linux/macOS).
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
ln -sfn "$REPO/resourcepack"        "$MC_DIR/resourcepacks/Teyvat"
echo "OK:"
echo "  $MC_DIR/shaderpacks/TeyvatShader"
echo "  $MC_DIR/resourcepacks/Teyvat"
echo "В игре: F3+R — шейдеры, F3+T — ресурспаки."
