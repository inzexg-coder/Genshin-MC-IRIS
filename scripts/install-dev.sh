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

# Мод Teyvat (мраморные блоки и т.д.) + Fabric API
mkdir -p "$MC_DIR/mods"
if [ -f "$REPO/dist/mods/teyvat.jar" ]; then
    rm -f "$MC_DIR/mods"/teyvat-*.jar
    cp "$REPO/dist/mods/teyvat.jar" "$MC_DIR/mods/teyvat.jar"
fi
FABRIC_API="fabric-api-0.138.4+1.21.10.jar"
if [ ! -f "$MC_DIR/mods/$FABRIC_API" ]; then
    echo "== Устанавливаю единый бандл Fabric API (нужен для мода)..."
    # Удаляем отдельные модули fabric-api-*.jar, чтобы не было дублей и путаницы
    rm -f "$MC_DIR/mods"/fabric-api-*.jar
    curl -L -o "$MC_DIR/mods/$FABRIC_API" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.138.4+1.21.10/$FABRIC_API"
fi
echo "OK. Установлено:"
echo "  $MC_DIR/shaderpacks/TeyvatShader"
echo "  $MC_DIR/resourcepacks/Teyvat"
echo "  $MC_DIR/mods/teyvat.jar (+ Fabric API при необходимости)"
echo "Проверка: ./scripts/check-install.sh"
echo "В игре: Options -> Video Settings -> Shader Packs -> TeyvatShader -> Apply, затем F3+R (шейдер) / F3+T (ресурспак). Мод подхватится после перезапуска игры."
