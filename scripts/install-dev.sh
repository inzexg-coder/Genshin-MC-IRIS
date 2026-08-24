#!/usr/bin/env bash
# Установка: реальная копия шейдера и ресурспака в ~/.minecraft (никаких симлинков).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
echo "== Teyvat installer: $(git -C "$REPO" log --oneline -1 2>/dev/null || echo 'не git-репо')"
echo "   dist/mods/teyvat.jar: $([ -f "$REPO/dist/mods/teyvat.jar" ] && echo 'есть' || echo 'НЕТ')"
if [ -z "${MC_DIR:-}" ]; then
  case "$(uname -s)" in
    Darwin) MC_DIR="$HOME/Library/Application Support/minecraft" ;;
    *)      MC_DIR="$HOME/.minecraft" ;;
  esac
fi
echo "   MC_DIR=$MC_DIR  HOME=$HOME"
mkdir -p "$MC_DIR/mods" "$MC_DIR/shaderpacks" "$MC_DIR/resourcepacks"
rm -rf "$MC_DIR/shaderpacks/TeyvatShader"
cp -r "$REPO/shader/TeyvatShader" "$MC_DIR/shaderpacks/TeyvatShader"
rm -rf "$MC_DIR/resourcepacks/Teyvat"
cp -r "$REPO/resourcepack" "$MC_DIR/resourcepacks/Teyvat"

# Мод Teyvat (мраморные блоки и т.д.) + Fabric API.
# Папку модов ищем: стандартная $MC_DIR/mods + любые папки, где уже лежат
# iris/sodium/euphoria (TLauncher и другие лаунчеры могут задавать свой путь).
FABRIC_API="fabric-api-0.138.4+1.21.10.jar"
FABRIC_API_URL="https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.138.4+1.21.10/$FABRIC_API"

# Если jar потерялся или битый (например, был вручную перенесён в папку модов) — восстанавливаем:
# 1) из git origin/main, 2) если git не помогает — качаем свежий прямо с GitHub.
JAR="$REPO/dist/mods/teyvat.jar"

jar_ok() {
    [ -f "$JAR" ] && [ "$(stat -c%s "$JAR" 2>/dev/null || echo 0)" -gt 50000 ] \
        && unzip -t "$JAR" >/dev/null 2>&1
}

if ! jar_ok; then
    echo "== teyvat.jar отсутствует или битый — восстанавливаю из origin/main..."
    git -C "$REPO" fetch origin main --prune 2>/dev/null || true
    git -C "$REPO" checkout origin/main -- dist/mods/teyvat.jar 2>/dev/null || true
fi
if ! jar_ok; then
    echo "== git не помог — скачиваю teyvat.jar с GitHub..."
    mkdir -p "$REPO/dist/mods"
    curl -sL -o "$JAR" \
        "https://raw.githubusercontent.com/inzexg-coder/Genshin-MC-IRIS/main/dist/mods/teyvat.jar" || true
fi
if ! jar_ok; then
    echo "! teyvat.jar так и не восстановлен. Проверь сеть и запусти ./scripts/update.sh ещё раз."
else
    echo "   teyvat.jar: $([ -f "$JAR" ] && stat -c%s "$JAR") байт"
    TV_VERSION=$(unzip -p "$JAR" fabric.mod.json 2>/dev/null | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
    echo "   версия мода: ${TV_VERSION:-?}"
fi

install_mods_to() {
    local dir="$1"
    [ -d "$dir" ] || return 0
    # Никогда не трогаем dist/mods самого репо — это источник jar.
    [ "$(readlink -f "$dir")" = "$(readlink -f "$REPO/dist/mods")" ] && return 0
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
# Папки с iris/sodium/euphoria (по всему /home и /opt) — это гарантированно клиент.
while IFS= read -r f; do
    [ -n "$f" ] && MODS_DIRS="$MODS_DIRS $(dirname "$f")"
done < <(find /home /opt -maxdepth 15 -name "*.jar" \( -iname "iris*.jar" -o -iname "sodium*.jar" -o -iname "euphoria*.jar" \) 2>/dev/null)
# Плюс ВСЕ папки mods под /home и /opt (TLauncher и другие лаунчеры держат их где угодно),
# кроме папок внутри самого репо (dist/mods — источник jar, его трогать нельзя).
while IFS= read -r d; do
    case "$d" in "$REPO"/*) ;; *) MODS_DIRS="$MODS_DIRS $d" ;; esac
done < <(find /home /opt -maxdepth 15 -type d -name mods 2>/dev/null)

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
