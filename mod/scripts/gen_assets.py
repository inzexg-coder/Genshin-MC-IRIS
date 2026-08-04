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
   "textures": {"top": "teyvat:block/marble", "bottom": "teyvat:block/marble", "side": "teyvat:block/marble_gold"}})
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
           "textures": {"top": "teyvat:block/marble", "bottom": "teyvat:block/marble", "side": f"teyvat:block/{side}"}})
    w(f"{MB}/{bid}_horizontal.json", {"parent": f"teyvat:block/{bid}", "x": 90})
    w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
    item_def(bid, f"teyvat:block/{bid}")

# ---------- custom 3D column/pedestal models ----------
def el(ids, from_, to_, tex=None, tex_face=None):
    faces = {}
    names = ("down", "up", "north", "south", "west", "east")
    for n in names:
        t = (tex_face or {}).get(n, tex)
        faces[n] = {"uv": [0, 0, 16, 16], "texture": t}
    return {"from": from_, "to": to_, "faces": faces}

def col_model(bid, elements):
    w(f"{MB}/{bid}.json", {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "teyvat:block/marble",
            "marble": "teyvat:block/marble",
            "polished": "teyvat:block/marble_polished",
            "side": f"teyvat:block/{bid}",
        },
        "elements": elements,
    })
    w(f"{BS}/{bid}.json", {"variants": {"": {"model": f"teyvat:block/{bid}"}}})
    w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
    item_def(bid, f"teyvat:block/{bid}")

M = "#marble"; P = "#polished"
def S(t):  # shaft with side texture t
    return el("shaft", [3, 0, 3], [13, 16, 13], t, {"up": M, "down": M})
def wide(y0, y1, t=P):
    return el("wide", [1, y0, 1], [15, y1, 15], t)
def ring(y0, y1):
    return el("ring", [4, y0, 4], [12, y1, 12], P)

# Колонны бесшовные: ствол на всю высоту, без широких баз/капителей и золота на краях,
# чтобы две колонны, поставленные друг на друга, сливались в одну длинную.
col_model("marble_column", [S("#side")])
col_model("marble_column_base", [
    wide(0, 3), el("shaft", [3, 3, 3], [13, 16, 13], "#side", {"up": M, "down": M})])
col_model("marble_column_mid", [S("#side")])
col_model("marble_column_capital", [
    el("shaft", [3, 0, 3], [13, 13, 13], "#side", {"up": M, "down": M}),
    wide(13, 16)])
col_model("marble_column_small", [
    el("shaft", [5, 0, 5], [11, 16, 11], "#side", {"up": M, "down": M})])
col_model("marble_pedestal", [
    wide(0, 3), el("mid", [3, 3, 3], [13, 10, 13], "#side", {"up": M, "down": M}),
    el("top", [2, 10, 2], [14, 16, 14], P)])

# ---------- arch (сплошной куб с нарисованным проёмом, ничем не просвечивает) ----------
w(f"{BS}/marble_arch.json", {"variants": {"": {"model": "teyvat:block/marble_arch"}}})
w(f"{MB}/marble_arch.json", {"parent": "minecraft:block/cube",
   "textures": {"up": "teyvat:block/marble", "down": "teyvat:block/marble",
                "north": "teyvat:block/marble_arch", "south": "teyvat:block/marble_arch",
                "east": "teyvat:block/marble", "west": "teyvat:block/marble"}})
w(f"{MI}/marble_arch.json", {"parent": "teyvat:block/marble_arch"})
item_def("marble_arch", "teyvat:block/marble_arch")

# ---------- gate (cube with carved front/back) ----------
w(f"{BS}/marble_gate.json", {"variants": {"": {"model": "teyvat:block/marble_gate"}}})
w(f"{MB}/marble_gate.json", {"parent": "minecraft:block/cube",
   "textures": {"up": "teyvat:block/marble", "down": "teyvat:block/marble",
                "north": "teyvat:block/marble_gate", "south": "teyvat:block/marble_gate",
                "east": "teyvat:block/marble", "west": "teyvat:block/marble"}})
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
    variants = {}
    ymap = {"east": 0, "south": 90, "west": 180, "north": 270}
    for facing, y0 in ymap.items():
        for half in ("bottom", "top"):
            rot = {"x": 180} if half == "top" else {}
            for shape in ("straight", "inner_left", "outer_left"):
                v = {"model": base if shape == "straight" else f"{base}_{shape}"}
                if y0: v["y"] = y0
                v.update(rot)
                variants[f"facing={facing},half={half},shape={shape}"] = v
            for shape in ("inner_right", "outer_right"):
                v = {"model": f"{base}_{shape}"}
                y = (y0 + 270) % 360
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

# ---------- side stairs (плоская плитка 2px с диагональным орнаментом, 4 поворота) ----------
bid = "marble_side_stairs"

def ce(from_, to_, cull=None):
    faces = {}
    for n in ("down", "up", "north", "south", "west", "east"):
        f = {"uv": [0, 0, 16, 16], "texture": "#side"}
        if cull and n in cull:
            f["cullface"] = n
        faces[n] = f
    return {"from": from_, "to": to_, "faces": faces}

w(f"{MB}/{bid}.json", {
    "parent": "minecraft:block/block",
    "textures": {"particle": "teyvat:block/marble", "side": "teyvat:block/marble_side_stairs"},
    "elements": [
        ce([0, 0, 0], [16, 2, 16], ["down", "north", "south", "west", "east"]),
    ],
})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
item_def(bid, f"teyvat:block/{bid}")
w(f"{BS}/{bid}.json", {"variants": {
    "facing=south": {"model": f"teyvat:block/{bid}"},
    "facing=west": {"model": f"teyvat:block/{bid}", "y": 90},
    "facing=north": {"model": f"teyvat:block/{bid}", "y": 180},
    "facing=east": {"model": f"teyvat:block/{bid}", "y": 270}}})

# ---------- door (3 блока высотой, толщина 5px, анимацию рисует MarbleTallDoorRenderer) ----------
bid = "marble_door"
T = 5.0

# модель полотна одного сегмента: 5px пластина [0,0,0]-[5,16,16] (как у ванильных дверей),
# рендерер вращает её вокруг вертикальной оси блока. Текстура сегмента на всех гранях.
def door_segment_model(name, tex):
    faces = {}
    for n in ("down", "up", "north", "south", "west", "east"):
        faces[n] = {"uv": [0, 0, 16, 16], "texture": tex}
    w(f"{MB}/{name}.json", {
        "ambientocclusion": False,
        "parent": "minecraft:block/block",
        "textures": {"particle": "teyvat:block/marble", "side": f"teyvat:block/{tex}"},
        "elements": [{"from": [0, 0, 0], "to": [T, 16, 16], "faces": faces}],
    })

door_segment_model(f"{bid}_lower", "marble_door_bottom")
door_segment_model(f"{bid}_middle", "marble_door_middle")
door_segment_model(f"{bid}_upper", "marble_door_top")

w(f"{BS}/{bid}.json", {"variants": {
    "third=lower": {"model": f"teyvat:block/{bid}_lower"},
    "third=middle": {"model": f"teyvat:block/{bid}_middle"},
    "third=upper": {"model": f"teyvat:block/{bid}_upper"}}})

# статичная модель для иконки в инвентаре: полная дверь из 3 сегментов
def door_panel(y0, y1, tex):
    faces = {}
    for n in ("down", "up", "north", "south", "west", "east"):
        faces[n] = {"uv": [0, 0, 16, 16], "texture": tex}
    return {"from": [0, y0, 0], "to": [T, y1, 16], "faces": faces}

w(f"{MB}/{bid}_item.json", {
    "parent": "minecraft:block/block",
    "textures": {
        "particle": "teyvat:block/marble",
        "bottom": "teyvat:block/marble_door_bottom",
        "middle": "teyvat:block/marble_door_middle",
        "top": "teyvat:block/marble_door_top",
    },
    "display": {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.21, 0.625]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.125, 0.25]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.17, 0.5]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.125, 0.375]},
        "thirdperson_lefthand": {"rotation": [75, -45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.125, 0.375]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.13, 0.4]},
        "firstperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.13, 0.4]},
    },
    "elements": [
        door_panel(0, 16, "#bottom"),
        door_panel(16, 32, "#middle"),
        door_panel(32, 48, "#top"),
    ],
})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}_item"})
item_def(bid, f"teyvat:item/{bid}")


