#!/usr/bin/env python3
"""Генератор рецептов Teyvat: верстак (у всех 30 блоков) + камнерез (короткие пути).
Запуск: python3 scripts/gen_recipes.py из каталога mod/."""
import json, os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "teyvat", "recipe")
ID = "teyvat"
MC = "minecraft"

# 1.21.10: ингредиенты — строки ("minecraft:quartz"), НЕ объекты {"item": ...} —
# иначе рецепт не парсится и не попадает в книгу крафта.
def item(ns, name):
    return f"{ns}:{name}"

def shaped(name, pattern, keys, result, count=1):
    return {
        "type": "minecraft:crafting_shaped",
        "category": "building",
        "pattern": pattern,
        "key": keys,
        "result": {"id": f"{ID}:{result}", "count": count},
    }

def stonecut(ingredient_ns, ingredient, result, count=1):
    return {
        "type": "minecraft:stonecutting",
        "ingredient": item(ingredient_ns, ingredient),
        "result": {"id": f"{ID}:{result}", "count": count},
    }

def write(name, recipe):
    path = os.path.join(OUT, name + ".json")
    with open(path, "w") as f:
        json.dump(recipe, f, indent=2)
        f.write("\n")

M = item(ID, "marble")
P = item(ID, "polished_marble")
B = item(ID, "marble_bricks")
T = item(ID, "marble_tiles")
SLAB = item(ID, "marble_slab")
GOLD = item(MC, "gold_ingot")
QUARTZ = item(MC, "quartz")
STICK = item(MC, "stick")
GLOWSTONE = item(MC, "glowstone")
BASE = item(ID, "marble_column_base")
MID = item(ID, "marble_column_mid")
CAP = item(ID, "marble_column_capital")
PILLAR = item(ID, "marble_pillar")
F = item(ID, "marble_fence")

recipes = {
    # ---- источник: 8 кварца + 1 золото -> 1 мрамор ----
    "marble": shaped("marble", ["qqq", "qgq", "qqq"], {"q": QUARTZ, "g": GOLD}, "marble", 1),

    # ---- базовые превращения ----
    "polished_marble": shaped("polished_marble", ["mm", "mm"], {"m": M}, "polished_marble", 4),
    "marble_bricks": shaped("marble_bricks", ["pp", "pp"], {"p": P}, "marble_bricks", 4),
    "marble_tiles": shaped("marble_tiles", ["bb", "bb"], {"b": B}, "marble_tiles", 4),
    "chiseled_marble": shaped("chiseled_marble", ["m", "m"], {"m": SLAB}, "chiseled_marble", 1),
    "gold_trimmed_marble": shaped("gold_trimmed_marble", ["mmm", "mgm", "mmm"], {"m": M, "g": GOLD}, "gold_trimmed_marble", 4),

    # ---- пилон и балка ----
    "marble_pillar": shaped("marble_pillar", ["m", "m"], {"m": M}, "marble_pillar", 1),
    "marble_beam": shaped("marble_beam", ["mmm"], {"m": M}, "marble_beam", 6),

    # ---- ступени и плиты ----
    "marble_stairs": shaped("marble_stairs", ["m  ", "mm ", "mmm"], {"m": M}, "marble_stairs", 4),
    "polished_marble_stairs": shaped("polished_marble_stairs", ["p  ", "pp ", "ppp"], {"p": P}, "polished_marble_stairs", 4),
    "marble_brick_stairs": shaped("marble_brick_stairs", ["b  ", "bb ", "bbb"], {"b": B}, "marble_brick_stairs", 4),
    "marble_tile_stairs": shaped("marble_tile_stairs", ["t  ", "tt ", "ttt"], {"t": T}, "marble_tile_stairs", 4),
    "marble_slab": shaped("marble_slab", ["mmm"], {"m": M}, "marble_slab", 6),
    "polished_marble_slab": shaped("polished_marble_slab", ["ppp"], {"p": P}, "polished_marble_slab", 6),
    "marble_brick_slab": shaped("marble_brick_slab", ["bbb"], {"b": B}, "marble_brick_slab", 6),
    "marble_tile_slab": shaped("marble_tile_slab", ["ttt"], {"t": T}, "marble_tile_slab", 6),

    # ---- забор / калитка / стена ----
    "marble_wall": shaped("marble_wall", ["mmm", "mmm"], {"m": M}, "marble_wall", 6),
    "marble_fence": shaped("marble_fence", ["mmm", "mmm"], {"m": M}, "marble_fence", 6),
    "marble_fence_gate": shaped("marble_fence_gate", ["fmf", "fmf"], {"f": F, "m": M}, "marble_fence_gate", 1),

    # ---- особые ----
    "marble_side_stairs": shaped("marble_side_stairs", ["mm", "mm"], {"m": M}, "marble_side_stairs", 2),
    "marble_arch": shaped("marble_arch", ["m m", "mmm"], {"m": M}, "marble_arch", 1),
    "marble_gate": shaped("marble_gate", ["mmm", "m m"], {"m": M}, "marble_gate", 1),
    "marble_door": shaped("marble_door", ["mm", "mm", "mm"], {"m": M}, "marble_door", 2),
    "marble_lamp": shaped("marble_lamp", [" m ", "mfm", " m "], {"m": M, "f": GLOWSTONE}, "marble_lamp", 1),

    # ---- детали колонн (сборка на верстаке) ----
    "marble_column_base": shaped("marble_column_base", ["mmm"], {"m": M}, "marble_column_base", 1),
    "marble_column_capital": shaped("marble_column_capital", ["mm", "mm"], {"m": M}, "marble_column_capital", 1),
    "marble_column_mid": shaped("marble_column_mid", ["p", "p"], {"p": PILLAR}, "marble_column_mid", 1),
    "marble_column_small": shaped("marble_column_small", ["p"], {"p": PILLAR}, "marble_column_small", 1),
    "marble_pedestal": shaped("marble_pedestal", ["mmm", "mmm", "mmm"], {"m": M}, "marble_pedestal", 1),
    "marble_column": shaped("marble_column", ["c", "m", "b"], {"c": CAP, "m": MID, "b": BASE}, "marble_column", 1),
}

# ---- камнерез: короткие пути из семейств ----
FROM_MARBLE = [
    "polished_marble", "marble_bricks", "marble_tiles", "chiseled_marble",
    "marble_stairs", "marble_slab", "marble_wall", "marble_fence",
    "marble_pillar", "marble_beam", "marble_side_stairs", "marble_arch", "marble_gate",
    "marble_column", "marble_column_small", "marble_column_mid",
    "marble_column_base", "marble_column_capital", "marble_pedestal",
]
for out in FROM_MARBLE:
    recipes[f"stonecut_marble_to_{out}"] = stonecut(ID, "marble", out)

for out in ["polished_marble_stairs", "polished_marble_slab", "chiseled_marble"]:
    recipes[f"stonecut_polished_to_{out}"] = stonecut(ID, "polished_marble", out)
for out in ["marble_brick_stairs", "marble_brick_slab"]:
    recipes[f"stonecut_bricks_to_{out}"] = stonecut(ID, "marble_bricks", out)
for out in ["marble_tile_stairs", "marble_tile_slab"]:
    recipes[f"stonecut_tiles_to_{out}"] = stonecut(ID, "marble_tiles", out)

for out in ["marble_column_capital", "marble_pedestal"]:
    recipes[f"stonecut_gold_to_{out}"] = stonecut(ID, "gold_trimmed_marble", out)

os.makedirs(OUT, exist_ok=True)
for name, recipe in sorted(recipes.items()):
    write(name, recipe)
print(f"recipes: {len(recipes)}")
