#!/usr/bin/env python3
"""Генерация blockstates/models/items/lang для мраморного набора Teyvat."""
import json, os

ROOT = "src/main/resources/assets/teyvat"
BS = f"{ROOT}/blockstates"
MB = f"{ROOT}/models/block"
MI = f"{ROOT}/models/item"
IID = f"{ROOT}/items"
os.makedirs(BS, exist_ok=True)
os.makedirs(MB, exist_ok=True)
os.makedirs(MI, exist_ok=True)
os.makedirs(IID, exist_ok=True)

def w(path, data):
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")

def item_def(bid, model_id):
    """Item definition для нового item-model системы (assets/<ns>/items/<id>.json)."""
    w(f"{IID}/{bid}.json", {"model": {"type": "minecraft:model", "model": model_id}})

# ---------- cube_all ----------
CUBES = {
    "marble": "marble",
    "polished_marble": "marble_polished",
    "marble_bricks": "marble_bricks",
    "marble_tiles": "marble_tiles",
    "chiseled_marble": "marble_chiseled",
    "marble_lamp": "marble_lamp",
}
for bid, tex in CUBES.items():
    w(f"{BS}/{bid}.json", {"variants": {"": {"model": f"teyvat:block/{bid}"}}})
    w(f"{MB}/{bid}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": f"teyvat:block/{tex}"}})
    w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
    item_def(bid, f"teyvat:block/{bid}")

# ---------- gold trimmed (top/bottom marble, side gold band) ----------
bid = "gold_trimmed_marble"
w(f"{BS}/{bid}.json", {"variants": {"": {"model": f"teyvat:block/{bid}"}}})
w(f"{MB}/{bid}.json", {"parent": "minecraft:block/cube_bottom_top",
   "textures": {"top": "teyvat:block/marble_gold_top", "bottom": "teyvat:block/marble_gold_top", "side": "teyvat:block/marble_gold"}})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
item_def(bid, f"teyvat:block/{bid}")

# ---------- axis blocks (pillar, beam) ----------
for bid, side in (("marble_pillar", "marble_pillar"), ("marble_beam", "marble_beam")):
    w(f"{BS}/{bid}.json", {"variants": {
        "axis=x": {"model": f"teyvat:block/{bid}_horizontal", "x": 90, "y": 90},
        "axis=y": {"model": f"teyvat:block/{bid}"},
        "axis=z": {"model": f"teyvat:block/{bid}_horizontal"}}})
    for m in (bid, f"{bid}_horizontal"):
        w(f"{MB}/{m}.json", {"parent": "minecraft:block/cube_bottom_top",
           "textures": {"top": "teyvat:block/marble_gold_top", "bottom": "teyvat:block/marble_gold_top", "side": f"teyvat:block/{side}"}})
    w(f"{MB}/{bid}_horizontal.json", {"parent": f"teyvat:block/{bid}", "x": 90})
    w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
    item_def(bid, f"teyvat:block/{bid}")

# ---------- custom 3D column/pedestal models ----------
def el(ids, from_, to_, tex=None, tex_face=None):
    """Элемент модели с автоматическим cullface: грань на границе блока
    (0 или 16) отсекается соседом, остальные всегда видны. Это убирает
    z-fighting и «просвечивание» у тонких колонн."""
    faces = {}
    names = ("down", "up", "north", "south", "west", "east")
    for n in names:
        t = (tex_face or {}).get(n, tex)
        f = {"uv": [0, 0, 16, 16], "texture": t}
        if n == "down" and from_[1] == 0: f["cullface"] = "down"
        if n == "up" and to_[1] == 16: f["cullface"] = "up"
        if n == "north" and from_[2] == 0: f["cullface"] = "north"
        if n == "south" and to_[2] == 16: f["cullface"] = "south"
        if n == "west" and from_[0] == 0: f["cullface"] = "west"
        if n == "east" and to_[0] == 16: f["cullface"] = "east"
        faces[n] = f
    return {"from": from_, "to": to_, "faces": faces}

def col_model(bid, elements):
    w(f"{MB}/{bid}.json", {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "teyvat:block/marble",
            "marble": "teyvat:block/marble",
            "polished": "teyvat:block/marble_polished",
            "top": "teyvat:block/marble_top",
            "side": f"teyvat:block/{bid}",
        },
        "elements": elements,
    })
    w(f"{BS}/{bid}.json", {"variants": {"": {"model": f"teyvat:block/{bid}"}}})
    w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
    item_def(bid, f"teyvat:block/{bid}")

M = "#marble"; P = "#polished"; T = "#top"
def S(t):  # shaft with side texture t, top/bottom = резная крышка
    return el("shaft", [3, 0, 3], [13, 16, 13], t, {"up": T, "down": T})
def wide(y0, y1, t=P):
    return el("wide", [1, y0, 1], [15, y1, 15], t)
def ring(y0, y1):
    return el("ring", [4, y0, 4], [12, y1, 12], P)

# Колонны бесшовные: ствол на всю высоту, без широких баз/капителей и золота на краях,
# чтобы две колонны, поставленные друг на друга, сливались в одну длинную.
col_model("marble_column", [S("#side")])
col_model("marble_column_base", [
    wide(0, 3), el("shaft", [3, 3, 3], [13, 16, 13], "#side", {"up": T, "down": M})])
col_model("marble_column_mid", [S("#side")])
col_model("marble_column_capital", [
    el("shaft", [3, 0, 3], [13, 13, 13], "#side", {"up": T, "down": M}),
    wide(13, 16)])
col_model("marble_column_small", [
    el("shaft", [5, 0, 5], [11, 16, 11], "#side", {"up": M, "down": M})])
col_model("marble_pedestal", [
    wide(0, 3), el("mid", [3, 3, 3], [13, 10, 13], "#side", {"up": T, "down": M}),
    el("top", [2, 10, 2], [14, 16, 14], P)])

# ---------- arch (цельный блок-врата: резной фасад, никаких сквозных проёмов) ----------
w(f"{BS}/marble_arch.json", {"variants": {"": {"model": "teyvat:block/marble_arch"}}})
w(f"{MB}/marble_arch.json", {"parent": "minecraft:block/cube",
   "textures": {"up": "teyvat:block/marble_gold_top", "down": "teyvat:block/marble_gold_top",
                "north": "teyvat:block/marble_arch_front", "south": "teyvat:block/marble_arch_front",
                "east": "teyvat:block/marble_gold", "west": "teyvat:block/marble_gold"}})
w(f"{MI}/marble_arch.json", {"parent": "teyvat:block/marble_arch"})
item_def("marble_arch", "teyvat:block/marble_arch")

# ---------- gate (cube with carved front/back) ----------
w(f"{BS}/marble_gate.json", {"variants": {"": {"model": "teyvat:block/marble_gate"}}})
w(f"{MB}/marble_gate.json", {"parent": "minecraft:block/cube",
   "textures": {"up": "teyvat:block/marble_gold_top", "down": "teyvat:block/marble_gold_top",
                "north": "teyvat:block/marble_gate", "south": "teyvat:block/marble_gate",
                "east": "teyvat:block/marble_gold", "west": "teyvat:block/marble_gold"}})
w(f"{MI}/marble_gate.json", {"parent": "teyvat:block/marble_gate"})
item_def("marble_gate", "teyvat:block/marble_gate")

# ---------- stairs (4 sets) ----------
STAIRS = {
    "marble_stairs": "marble",
    "polished_marble_stairs": "marble_polished",
    "marble_brick_stairs": "marble_bricks",
    "marble_tile_stairs": "marble_tiles",
}
for sid, tex in STAIRS.items():
    for suffix, parent in (("", "stairs"), ("_inner", "inner_stairs"), ("_outer", "outer_stairs")):
        w(f"{MB}/{sid}{suffix}.json", {"parent": f"minecraft:block/{parent}",
           "textures": {"bottom": f"teyvat:block/{tex}", "top": f"teyvat:block/{tex}", "side": f"teyvat:block/{tex}"}})
    w(f"{MI}/{sid}.json", {"parent": f"teyvat:block/{sid}"})
    item_def(sid, f"teyvat:block/{sid}")
    base = f"teyvat:block/{sid}"
    # Точная копия ванильного blockstate 1.21.10 (oak_stairs.json):
    #   низ:  left-формы = base+270, right-формы = base
    #   верх: left-формы = base,     right-формы = base+90
    #   всё с uvlock, верх дополнительно x=180
    variants = {}
    ymap = {"east": 0, "south": 90, "west": 180, "north": 270}
    for facing, y0 in ymap.items():
        for half in ("bottom", "top"):
            rot = {"x": 180} if half == "top" else {}
            if half == "bottom":
                y_left = (y0 + 270) % 360
                y_right = y0
            else:
                y_left = y0
                y_right = (y0 + 90) % 360
            for shape, y in (("straight", y0), ("inner_left", y_left),
                             ("inner_right", y_right), ("outer_left", y_left),
                             ("outer_right", y_right)):
                if shape == "straight":
                    model = base
                else:
                    model = f"{base}_{'inner' if 'inner' in shape else 'outer'}"
                v = {"model": model, "uvlock": True}
                if y: v["y"] = y
                v.update(rot)
                variants[f"facing={facing},half={half},shape={shape}"] = v
    w(f"{BS}/{sid}.json", {"variants": variants})

# ---------- slabs (4 sets) ----------
SLABS = {
    "marble_slab": "marble",
    "polished_marble_slab": "marble_polished",
    "marble_brick_slab": "marble_bricks",
    "marble_tile_slab": "marble_tiles",
}
for sid, tex in SLABS.items():
    w(f"{MB}/{sid}.json", {"parent": "minecraft:block/slab",
       "textures": {"bottom": f"teyvat:block/{tex}", "top": f"teyvat:block/{tex}", "side": f"teyvat:block/{tex}"}})
    w(f"{MB}/{sid}_top.json", {"parent": "minecraft:block/slab_top",
       "textures": {"bottom": f"teyvat:block/{tex}", "top": f"teyvat:block/{tex}", "side": f"teyvat:block/{tex}"}})
    w(f"{MB}/{sid}_double.json", {"parent": "minecraft:block/cube_all", "textures": {"all": f"teyvat:block/{tex}"}})
    w(f"{BS}/{sid}.json", {"variants": {
        "type=bottom": {"model": f"teyvat:block/{sid}"},
        "type=double": {"model": f"teyvat:block/{sid}_double"},
        "type=top": {"model": f"teyvat:block/{sid}_top"}}})
    w(f"{MI}/{sid}.json", {"parent": f"teyvat:block/{sid}"})
    item_def(sid, f"teyvat:block/{sid}")

# ---------- wall ----------
bid = "marble_wall"
for suffix, parent in (("_post", "template_wall_post"), ("_side", "template_wall_side"), ("_side_tall", "template_wall_side_tall")):
    w(f"{MB}/{bid}{suffix}.json", {"parent": f"minecraft:block/{parent}",
       "textures": {"wall": "teyvat:block/marble"}})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}_post"})
item_def(bid, f"teyvat:block/{bid}_post")
parts = [{"when": {"up": "true"}, "apply": {"model": f"teyvat:block/{bid}_post"}}]
for name, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
    for val, suffix in (("low", "_side"), ("tall", "_side_tall")):
        parts.append({"when": {name: val}, "apply": {"model": f"teyvat:block/{bid}{suffix}", "y": y, "uvlock": True}})
w(f"{BS}/{bid}.json", {"multipart": parts})

# ---------- fence ----------
bid = "marble_fence"
for suffix, parent in (("_post", "fence_post"), ("_side", "fence_side")):
    w(f"{MB}/{bid}{suffix}.json", {"parent": f"minecraft:block/{parent}",
       "textures": {"texture": "teyvat:block/marble"}})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}_post"})
item_def(bid, f"teyvat:block/{bid}_post")
parts = [{"apply": {"model": f"teyvat:block/{bid}_post"}}]
for name, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
    parts.append({"when": {name: "true"}, "apply": {"model": f"teyvat:block/{bid}_side", "y": y, "uvlock": True}})
w(f"{BS}/{bid}.json", {"multipart": parts})

# ---------- fence gate ----------
bid = "marble_fence_gate"
for suffix, parent in (("", "template_fence_gate"), ("_open", "template_fence_gate_open"),
                       ("_wall", "template_fence_gate_wall"), ("_wall_open", "template_fence_gate_wall_open")):
    w(f"{MB}/{bid}{suffix}.json", {"parent": f"minecraft:block/{parent}",
       "textures": {"texture": "teyvat:block/marble"}})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
item_def(bid, f"teyvat:block/{bid}")
variants = {}
# Повороты как у ванильных калиток (oak_fence_gate): south=0, west=90, north=180, east=270
for facing, y in (("south", 0), ("west", 90), ("north", 180), ("east", 270)):
    for state, model in (("in_wall=false,open=false", bid),
                         ("in_wall=false,open=true", f"{bid}_open"),
                         ("in_wall=true,open=false", f"{bid}_wall"),
                         ("in_wall=true,open=true", f"{bid}_wall_open")):
        v = {"model": f"teyvat:block/{model}", "uvlock": True}
        if y:
            v["y"] = y
        variants[f"facing={facing},{state}"] = v
w(f"{BS}/{bid}.json", {"variants": variants})

# ---------- side stairs (горизонтальные ступени): блок делится на 4 вертикальных
# столбика 8x8, один столбик (северо-западный в локальных координатах) удалён =>
# L-образный блок 3/4. Верхние грани — квадранты мозаики marble_side_stairs,
# боковые грани — каннелюры (#side, UV 0..8 => меандр стыкуется каждые 8px).
# 4 поворота через y: south=0, west=90, north=180, east=270 ----------
bid = "marble_side_stairs"

def side_col(from_, to_, top_uv):
    x0, y0, z0 = from_
    x1, y1, z1 = to_
    to = to_
    faces = {}
    faces["down"] = {"uv": [0, 0, 16, 16], "texture": "#marble"}
    if y0 == 0:
        faces["down"]["cullface"] = "down"
    faces["up"] = {"uv": top_uv, "texture": "#top_side"}
    faces["north"] = {"uv": [0, 0, 8, 16], "texture": "#side"}
    faces["south"] = {"uv": [0, 0, 8, 16], "texture": "#side"}
    faces["west"] = {"uv": [0, 0, 8, 16], "texture": "#side"}
    faces["east"] = {"uv": [0, 0, 8, 16], "texture": "#side"}
    if z0 == 0:
        faces["north"]["cullface"] = "north"
    if z1 == 16:
        faces["south"]["cullface"] = "south"
    if x0 == 0:
        faces["west"]["cullface"] = "west"
    if x1 == 16:
        faces["east"]["cullface"] = "east"
    return {"from": from_, "to": to_, "faces": faces}

w(f"{MB}/{bid}.json", {
    "parent": "minecraft:block/block",
    "textures": {"particle": "teyvat:block/marble", "side": "teyvat:block/marble_column",
                 "top_side": "teyvat:block/marble_side_stairs", "marble": "teyvat:block/marble"},
    "elements": [
        side_col([8, 0, 0], [16, 16, 8], [8, 0, 16, 8]),    # СВ столбик
        side_col([8, 0, 8], [16, 16, 16], [8, 8, 16, 16]),  # ЮВ столбик
        side_col([0, 0, 8], [8, 16, 16], [0, 8, 8, 16]),    # ЮЗ столбик
    ],
})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
item_def(bid, f"teyvat:block/{bid}")
w(f"{BS}/{bid}.json", {"variants": {
    "facing=south": {"model": f"teyvat:block/{bid}"},
    "facing=west": {"model": f"teyvat:block/{bid}", "y": 90},
    "facing=north": {"model": f"teyvat:block/{bid}", "y": 180},
    "facing=east": {"model": f"teyvat:block/{bid}", "y": 270}}})

# ---------- door (стандартная ванильная 2-блочная дверь, как oak_door) ----------
bid = "marble_door"

# 8 моделей полотна — точные обёртки ванильных parents с текстурами top/bottom
DOOR_MODELS = {
    "bottom_left": "door_bottom_left", "bottom_left_open": "door_bottom_left_open",
    "bottom_right": "door_bottom_right", "bottom_right_open": "door_bottom_right_open",
    "top_left": "door_top_left", "top_left_open": "door_top_left_open",
    "top_right": "door_top_right", "top_right_open": "door_top_right_open",
}
for suffix, parent in DOOR_MODELS.items():
    w(f"{MB}/{bid}_{suffix}.json", {
        "parent": f"minecraft:block/{parent}",
        "textures": {"bottom": "teyvat:block/marble_door_bottom", "top": "teyvat:block/marble_door_top"}})

# блокстейт — точная копия ванильного oak_door.json (повороты в таблице ниже)
variants = {}
# (facing, закрыто y, открыто-left y, открыто-right y)
for facing, y0, yl, yr in (("east", 0, 90, 270), ("north", 270, 0, 180),
                           ("south", 90, 180, 0), ("west", 180, 270, 90)):
    for half in ("lower", "upper"):
        for hinge in ("left", "right"):
            for open_ in ("false", "true"):
                half_part = "bottom" if half == "lower" else "top"
                model = f"{bid}_{half_part}_{hinge}" + ("_open" if open_ == "true" else "")
                v = {"model": f"teyvat:block/{model}"}
                y = y0 if open_ == "false" else (yl if hinge == "left" else yr)
                if y:
                    v["y"] = y
                variants[f"facing={facing},half={half},hinge={hinge},open={open_}"] = v
w(f"{BS}/{bid}.json", {"variants": variants})

# иконка в инвентаре — как у ванильных дверей (item/generated)
w(f"{MI}/{bid}.json", {"parent": "minecraft:item/generated",
   "textures": {"layer0": "teyvat:item/marble_door"}})
item_def(bid, f"teyvat:item/{bid}")
