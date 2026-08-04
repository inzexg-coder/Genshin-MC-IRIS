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

# Мод Teyvat (мраморные блоки и т.д.) + Fabric API.
# Папку модов ищем: стандартная $MC_DIR/mods + любые папки, где уже лежат
# iris/sodium/euphoria (TLauncher и другие лаунчеры могут задавать свой путь).
FABRIC_API="fabric-api-0.138.4+1.21.10.jar"
FABRIC_API_URL="https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.138.4+1.21.10/$FABRIC_API"

install_mods_to() {
    local dir="$1"
    [ -d "$dir" ] || return 0
    if [ -f "$REPO/dist/mods/teyvat.jar" ]; then
        rm -f "$dir"/teyvat-*.jar "$dir/teyvat.jar"
        cp "$REPO/dist/mods/teyvat.jar" "$dir/teyvat.jar"
        echo "  мод -> $dir/teyvat.jar"
    fi
    if [ ! -f "$dir/$FABRIC_API" ]; then
        # Удаляем отдельные/старые модули fabric-api-*.jar, чтобы не было дублей
        rm -f "$dir"/fabric-api-*.jar
        echo "== Скачиваю Fabric API в $dir ..."
        curl -L -o "$dir/$FABRIC_API" "$FABRIC_API_URL" || echo "  ! не удалось скачать fabric-api в $dir"
    fi
    return 0
}

MODS_DIRS="$MC_DIR/mods $HOME/.tlauncher/mods"
# Папки с iris/sodium/euphoria (любая глубина) — это гарантированно клиент.
while IFS= read -r f; do
    [ -n "$f" ] && MODS_DIRS="$MODS_DIRS $(dirname "$f")"
done < <(find "$HOME" -maxdepth 12 -name "*.jar" \( -iname "iris*.jar" -o -iname "sodium*.jar" -o -iname "euphoria*.jar" \) 2>/dev/null)
# Плюс ВСЕ папки mods под $HOME (TLauncher и другие лаунчеры держат их где угодно).
while IFS= read -r d; do
    [ -n "$d" ] && MODS_DIRS="$MODS_DIRS $d"
done < <(find "$HOME" -maxdepth 12 -type d -name mods 2>/dev/null)

echo "== Папки модов:"
for d in $(echo "$MODS_DIRS" | tr ' ' '\n' | sort -u); do
    echo "   - $d"
    install_mods_to "$d"
done

echo "OK. Установлено:"
echo "  $MC_DIR/shaderpacks/TeyvatShader"
echo "  $MC_DIR/resourcepacks/Teyvat"
echo "  моды: teyvat.jar + $FABRIC_API (см. список папок выше)"
echo "Проверка: ./scripts/check-install.sh"
echo "В игре: Options -> Video Settings -> Shader Packs -> TeyvatShader -> Apply, затем F3+R (шейдер) / F3+T (ресурспак). Мод подхватится после перезапуска игры."
