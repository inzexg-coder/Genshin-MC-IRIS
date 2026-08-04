#!/usr/bin/env python3
"""Генерация blockstates/models/items/lang для мраморного набора Teyvat."""
import json, os

ROOT = "src/main/resources/assets/teyvat"
BS = f"{ROOT}/blockstates"
MB = f"{ROOT}/models/block"
MI = f"{ROOT}/models/item"
os.makedirs(BS, exist_ok=True)
os.makedirs(MB, exist_ok=True)
os.makedirs(MI, exist_ok=True)

def w(path, data):
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")

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

# ---------- gold trimmed (top/bottom marble, side gold band) ----------
bid = "gold_trimmed_marble"
w(f"{BS}/{bid}.json", {"variants": {"": {"model": f"teyvat:block/{bid}"}}})
w(f"{MB}/{bid}.json", {"parent": "minecraft:block/cube_bottom_top",
   "textures": {"top": "teyvat:block/marble", "bottom": "teyvat:block/marble", "side": "teyvat:block/marble_gold"}})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})

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

M = "#marble"; P = "#polished"
def S(t):  # shaft with side texture t
    return el("shaft", [3, 0, 3], [13, 16, 13], t, {"up": M, "down": M})
def wide(y0, y1, t=P):
    return el("wide", [1, y0, 1], [15, y1, 15], t)
def ring(y0, y1):
    return el("ring", [4, y0, 4], [12, y1, 12], P)

col_model("marble_column", [
    wide(0, 2), S("#side"), wide(14, 16)])
col_model("marble_column_base", [
    wide(0, 3), el("shaft", [3, 3, 3], [13, 16, 13], "#side", {"up": M, "down": M})])
col_model("marble_column_mid", [
    S("#side"), ring(0, 1), ring(15, 16)])
col_model("marble_column_capital", [
    el("shaft", [3, 0, 3], [13, 13, 13], "#side", {"up": M, "down": M}),
    wide(13, 16)])
col_model("marble_column_small", [
    wide(0, 2), el("shaft", [5, 2, 5], [11, 14, 11], "#side", {"up": M, "down": M}), wide(14, 16)])
col_model("marble_pedestal", [
    wide(0, 3), el("mid", [3, 3, 3], [13, 10, 13], "#side", {"up": M, "down": M}),
    el("top", [2, 10, 2], [14, 16, 14], P)])

# ---------- arch ----------
w(f"{BS}/marble_arch.json", {"variants": {"": {"model": "teyvat:block/marble_arch"}}})
w(f"{MB}/marble_arch.json", {
    "parent": "minecraft:block/block",
    "textures": {
        "particle": "teyvat:block/marble",
        "marble": "teyvat:block/marble",
        "gold": "teyvat:block/marble_gold",
    },
    "elements": [
        {"from": [0, 0, 6], "to": [4, 16, 10], "faces": {f: {"uv": [0, 0, 16, 16], "texture": "#marble"} for f in ("down","up","north","south","west","east")}},
        {"from": [12, 0, 6], "to": [16, 16, 10], "faces": {f: {"uv": [0, 0, 16, 16], "texture": "#marble"} for f in ("down","up","north","south","west","east")}},
        {"from": [0, 12, 6], "to": [16, 16, 10], "faces": {f: {"uv": [0, 0, 16, 16], "texture": "#marble"} for f in ("down","up","north","south","west","east")}},
        {"from": [0, 12, 5.5], "to": [16, 13, 6], "faces": {f: {"uv": [0, 0, 16, 16], "texture": "#gold"} for f in ("north","south","west","east","up","down")}},
    ],
})
w(f"{MI}/marble_arch.json", {"parent": "teyvat:block/marble_arch"})

# ---------- gate (cube with carved front/back) ----------
w(f"{BS}/marble_gate.json", {"variants": {"": {"model": "teyvat:block/marble_gate"}}})
w(f"{MB}/marble_gate.json", {"parent": "minecraft:block/cube",
   "textures": {"up": "teyvat:block/marble", "down": "teyvat:block/marble",
                "north": "teyvat:block/marble_gate", "south": "teyvat:block/marble_gate",
                "east": "teyvat:block/marble", "west": "teyvat:block/marble"}})
w(f"{MI}/marble_gate.json", {"parent": "teyvat:block/marble_gate"})

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

# ---------- wall ----------
bid = "marble_wall"
for suffix, parent in (("_post", "template_wall_post"), ("_side", "template_wall_side"), ("_side_tall", "template_wall_side_tall")):
    w(f"{MB}/{bid}{suffix}.json", {"parent": f"minecraft:block/{parent}",
       "textures": {"wall": "teyvat:block/marble"}})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}_post"})
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
variants = {}
rots = {"east": (0, 90, 90, 180), "south": (90, 180, 180, 270), "west": (180, 270, 270, 0), "north": (270, 0, 0, 90)}
for facing, (r0, r1, r2, r3) in rots.items():
    variants[f"facing={facing},in_wall=false,open=false"] = {"model": f"teyvat:block/{bid}", "y": r0, "uvlock": True}
    variants[f"facing={facing},in_wall=false,open=true"] = {"model": f"teyvat:block/{bid}_open", "y": r1, "uvlock": True}
    variants[f"facing={facing},in_wall=true,open=false"] = {"model": f"teyvat:block/{bid}_wall", "y": r2, "uvlock": True}
    variants[f"facing={facing},in_wall=true,open=true"] = {"model": f"teyvat:block/{bid}_wall_open", "y": r3, "uvlock": True}
w(f"{BS}/{bid}.json", {"variants": variants})

# ---------- side stairs (horizontal, 4 facings) ----------
bid = "marble_side_stairs"
w(f"{MB}/{bid}.json", {"parent": "minecraft:block/stairs",
   "textures": {"bottom": "teyvat:block/marble", "top": "teyvat:block/marble", "side": "teyvat:block/marble"}})
w(f"{MI}/{bid}.json", {"parent": f"teyvat:block/{bid}"})
w(f"{BS}/{bid}.json", {"variants": {
    "facing=east": {"model": f"teyvat:block/{bid}", "x": 90, "y": 90},
    "facing=south": {"model": f"teyvat:block/{bid}", "x": 90, "y": 180},
    "facing=west": {"model": f"teyvat:block/{bid}", "x": 90, "y": 270},
    "facing=north": {"model": f"teyvat:block/{bid}", "x": 90}}})

# ---------- door ----------
bid = "marble_door"
for half in ("bottom", "top"):
    for side in ("left", "right"):
        for suffix, parent in (("", f"door_{half}_{side}"), ("_open", f"door_{half}_{side}_open")):
            w(f"{MB}/{bid}_{half}_{side}{suffix}.json", {"parent": f"minecraft:block/{parent}",
               "textures": {"bottom": "teyvat:block/marble_door_bottom", "top": "teyvat:block/marble_door_top"}})
variants = {}
for facing, y0 in (("east", 0), ("south", 90), ("west", 180), ("north", 270)):
    for half in ("lower", "upper"):
        m = "bottom" if half == "lower" else "top"
        for hinge in ("left", "right"):
            for open_ in ("false", "true"):
                o = "open" if open_ == "true" else ""
                model = f"teyvat:block/{bid}_{m}_{hinge}{o}"
                if open_ == "true":
                    y = (y0 + 90) % 360 if hinge == "left" else y0
                    y = y0 if hinge == "right" and open_ == "true" else y
                    # vanilla: open left adds 90; open right keeps facing rotation
                    y = (y0 + 90) % 360 if hinge == "left" else y0
                else:
                    y = y0
                variants[f"facing={facing},half={half},hinge={hinge},open={open_}"] = {"model": model, "y": y}
w(f"{BS}/{bid}.json", {"variants": variants})

# item icon for door
from PIL import Image, ImageDraw
img = Image.new("RGB", (16, 16), (242, 239, 233))
d = ImageDraw.Draw(img)
d.rectangle([2, 1, 13, 14], outline=(170, 162, 150))
d.rectangle([3, 2, 12, 13], outline=(228, 224, 216))
d.rectangle([3, 6, 12, 8], fill=(212, 175, 55))
d.ellipse([10, 10, 12, 12], fill=(212, 175, 55), outline=(150, 118, 30))
os.makedirs(f"{ROOT}/textures/item", exist_ok=True)
img.save(f"{ROOT}/textures/item/marble_door.png")
w(f"{MI}/{bid}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "teyvat:item/marble_door"}})

print("assets done")
