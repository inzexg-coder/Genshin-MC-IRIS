#!/usr/bin/env python3
"""Добавляет биом в biome_source уже созданного мира без потери построек.

Новые биомы генерируются только в НОВЫХ чанках; уже построенное не трогается.
Биом должен существовать в реестре: положи JSON биома в мод (или серверный датапак)
и обнови сервер ДО запуска этого скрипта.

Использование:
  python3 add_biome.py <путь/level.dat> <биом> [temperature humidity continentalness erosion depth weirdness]

Пример:
  python3 add_biome.py world/level.dat teyvat:teyvat_forest 0.0 0.5 0.0 0.0 0.0 0.0
"""
import shutil
import sys
from pathlib import Path

import nbtlib


def main() -> None:
    args = sys.argv[1:]
    if len(args) < 2:
        print(__doc__)
        sys.exit(1)

    path = Path(args[0])
    biome = args[1]
    try:
        params = {
            "temperature": float(args[2]) if len(args) > 2 else 0.0,
            "humidity": float(args[3]) if len(args) > 3 else 0.0,
            "continentalness": float(args[4]) if len(args) > 4 else 0.0,
            "erosion": float(args[5]) if len(args) > 5 else 0.0,
            "depth": float(args[6]) if len(args) > 6 else 0.0,
            "weirdness": float(args[7]) if len(args) > 7 else 0.0,
        }
    except ValueError as exc:
        print("Параметры должны быть числами:", exc)
        sys.exit(1)

    if not path.exists():
        print("Файл не найден:", path)
        sys.exit(1)

    backup = path.with_name(path.name + ".bak")
    if not backup.exists():
        shutil.copy2(path, backup)
        print("Бэкап:", backup)

    file = nbtlib.load(path)
    root = file
    data = root["Data"] if "Data" in root else root
    if "WorldGenSettings" not in data:
        print("В level.dat нет WorldGenSettings — это не мир Minecraft 1.18+?")
        sys.exit(1)
    dimensions = data["WorldGenSettings"]["dimensions"]

    added = 0
    for dim_key, dim in dimensions.items():
        generator = dim.get("generator")
        if generator is None:
            continue
        biome_source = generator.get("biome_source")
        if biome_source is None or str(biome_source.get("type", "")) != "minecraft:multi_noise":
            continue
        biomes = biome_source.get("biomes")
        if biomes is None:
            print(f"В измерении {dim_key} biome_source без списка 'biomes' — пропуск")
            continue
        if any(str(b.get("biome", "")) == biome for b in biomes):
            print(f"Биом {biome} уже есть в измерении {dim_key}")
            continue
        entry = nbtlib.Compound({
            "biome": nbtlib.String(biome),
            "parameters": nbtlib.Compound({
                key: nbtlib.Double(value) for key, value in params.items()
            }),
        })
        biomes.append(entry)
        added += 1
        print(f"+ {biome} добавлен в измерение {dim_key}")

    if added == 0:
        print("Ничего не добавлено")
        sys.exit(1)

    file.save(path)
    print("Готово. Запусти сервер — новые чанки будут с новым биомом.")


if __name__ == "__main__":
    main()
