#!/usr/bin/env python3
"""Генерация мраморных текстур Teyvat v3: белоснежный мрамор + золотые окантовки (Celestia).
Единая палитра для всех блоков, чтобы монолит и вперемешку смотрелись цельно.
Для золота генерируются specular-карты (*_s.png): альфа задаёт labPBR-эмиссию,
которая включается в шейдере через IPBR_EMISSIVE_MODE=3 (золото светится и подсвечивает блок)."""
import math, os, random
from PIL import Image, ImageDraw

S = 16
OUT = "src/main/resources/assets/teyvat/textures/block"
random.seed(20260804)

# Единая палитра «Целестия» — мрамор белоснежный, без прожилок и серых пятен
SNOW   = (252, 252, 250)
SNOW_D = (242, 242, 238)   # мягкая тень
MORTAR = (233, 233, 229)
GOLD   = (200, 156, 56)
GOLD_HI= (242, 210, 118)
GOLD_D = (142, 106, 34)
LAMP_C = (255, 246, 220)

# Альфа эмиссии в specular-картах (0..255; 255 = «эмиссии нет» для labPBR)
A_GOLD = 120    # золото: умеренное золотистое свечение (emission ~1.4)
A_LAMP = 235    # фонарь: яркое тёплое свечение (emission ~2.8)

def noise(px, amt=5):
    r, g, b = px
    d = random.randint(-amt, amt)
    return (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)))

def grain(img, amt=2):
    for _ in range(S * S):
        x, y = random.randrange(S), random.randrange(S)
        img.putpixel((x, y), noise(img.getpixel((x, y)), amt))

def base():
    img = Image.new("RGB", (S, S), SNOW)
    mask = Image.new("L", (S, S), 0)
    return img, ImageDraw.Draw(img), mask, ImageDraw.Draw(mask)

def save(name, img, mask=None):
    img.save(f"{OUT}/{name}.png")
    if mask is not None and mask.getextrema()[1] > 0:
        sm = Image.new("RGB", (S, S), (255, 255, 255))
        sm.putalpha(mask)
        sm.save(f"{OUT}/{name}_s.png")

def gold_band(d, md, y0, y1, w=S):
    d.rectangle([0, y0, w - 1, y1], fill=GOLD)
    d.rectangle([0, y0, w - 1, y0], fill=GOLD_HI)
    d.rectangle([0, y1, w - 1, y1], fill=GOLD_D)
    md.rectangle([0, y0, w - 1, y1], fill=A_GOLD)

def flutes(img, horizontal=False):
    """Каннелюры: вертикальные/горизонтальные бороздки с мягкой тенью (без серых пятен)."""
    n = 8
    step = S / n
    for i in range(n):
        a = int(i * step); b = int((i + 1) * step)
        for t in range(a, b):
            tt = (t - a) / (b - a)
            c = (int(SNOW[0] * (1 - 0.15 * tt)), int(SNOW[1] * (1 - 0.15 * tt)), int(SNOW[2] * (1 - 0.15 * tt)))
            for k in range(S):
                img.putpixel((k, t) if horizontal else (t, k), noise(c, 2))
    for i in range(n + 1):
        p = min(int(i * step), S - 1)
        for k in range(S):
            img.putpixel((k, p) if horizontal else (p, k), noise(SNOW_D, 2))

# --- базовые блоки ---
img, d, m, md = base(); grain(img, 2); save("marble", img)

img, d, m, md = base(); grain(img, 1); save("marble_polished", img)

img, d, m, md = base()
for y in range(S):
    for x in range(S):
        if y % 4 == 0 or (x + (2 if (y // 4) % 2 else 0)) % 8 == 0:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(SNOW, 3))
grain(img, 2); save("marble_bricks", img)

img, d, m, md = base()
for y in range(S):
    for x in range(S):
        if x % 4 == 0 or y % 4 == 0:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(SNOW, 4))
grain(img, 1); save("marble_tiles", img)

# --- chiseled: золотая окантовка с эмиссией ---
img, d, m, md = base(); grain(img, 1)
d.rectangle([0, 0, 15, 15], outline=GOLD);       md.rectangle([0, 0, 15, 15], outline=A_GOLD)
d.rectangle([1, 1, 14, 14], outline=GOLD_HI);    md.rectangle([1, 1, 14, 14], outline=A_GOLD)
d.rectangle([2, 2, 13, 13], outline=SNOW_D)
d.rectangle([4, 4, 11, 11], outline=(226, 226, 220))
d.rectangle([5, 5, 10, 10], outline=GOLD);       md.rectangle([5, 5, 10, 10], outline=A_GOLD)
save("marble_chiseled", img, m)

# --- gold trimmed: белый мрамор с золотыми полосами ---
img, d, m, md = base(); grain(img, 1)
gold_band(d, md, 1, 3); gold_band(d, md, 12, 14)
save("marble_gold", img, m)

# --- колонны / балки: золотые пояски НЕ на краях, чтобы стопка колонн была бесшовной ---
img, d, m, md = base(); flutes(img, False); gold_band(d, md, 2, 4); gold_band(d, md, 12, 14); save("marble_pillar", img, m)
img, d, m, md = base(); flutes(img, True);  gold_band(d, md, 2, 4); gold_band(d, md, 12, 14); save("marble_beam", img, m)

img, d, m, md = base(); flutes(img, False); gold_band(d, md, 2, 4); gold_band(d, md, 12, 14); save("marble_column", img, m)
img, d, m, md = base(); flutes(img, False); gold_band(d, md, 2, 3); gold_band(d, md, 12, 13); save("marble_column_small", img, m)
img, d, m, md = base(); flutes(img, False); gold_band(d, md, 3, 5);                        save("marble_column_base", img, m)
img, d, m, md = base(); flutes(img, False); gold_band(d, md, 7, 8);                        save("marble_column_mid", img, m)
img, d, m, md = base(); flutes(img, False); gold_band(d, md, 10, 12);                      save("marble_column_capital", img, m)
img, d, m, md = base(); flutes(img, False); gold_band(d, md, 3, 5); gold_band(d, md, 10, 12); save("marble_pedestal", img, m)

# --- ворота (куб с ромбом): ромб по целочисленным координатам, симметричный ---
img, d, m, md = base(); grain(img, 1)
d.rectangle([0, 0, 15, 15], outline=GOLD);    md.rectangle([0, 0, 15, 15], outline=A_GOLD)
d.rectangle([1, 1, 14, 14], outline=GOLD_HI); md.rectangle([1, 1, 14, 14], outline=A_GOLD)
d.rectangle([2, 2, 13, 13], outline=SNOW_D)
# ромб с осью симметрии x=7, y=8: вершины (7,3),(12,8),(7,13),(2,8) — все рёбра ±1
for a, b in (((7, 3), (12, 8)), ((12, 8), (7, 13)), ((7, 13), (2, 8)), ((2, 8), (7, 3))):
    d.line([a, b], fill=GOLD); md.line([a, b], fill=A_GOLD)
for a, b in (((7, 5), (10, 8)), ((10, 8), (7, 11)), ((7, 11), (4, 8)), ((4, 8), (7, 5))):
    d.line([a, b], fill=SNOW_D)
d.ellipse([6, 6, 9, 9], fill=GOLD, outline=GOLD_D); md.ellipse([6, 6, 9, 9], fill=A_GOLD)
save("marble_gate", img, m)

# --- фонарь: текстуру НЕ меняем, только specular-карта (эмиссия ядра и золотой рамы) ---
lamp = Image.open(f"{OUT}/marble_lamp.png").convert("RGB")
m = Image.new("L", (S, S), 0)
cx = cy = 7.5
r_core = 2.7
for y in range(S):
    for x in range(S):
        if x in (1, 14) or y in (1, 14):
            m.putpixel((x, y), A_GOLD)  # золотая окантовка фонаря
            continue
        dist = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if dist <= 1.2:
            m.putpixel((x, y), A_LAMP)
        elif dist <= r_core:
            m.putpixel((x, y), int(A_LAMP * (1 - (dist - 1.2) / (r_core - 1.2))))
sm = Image.new("RGB", (S, S), (255, 255, 255)); sm.putalpha(m)
sm.save(f"{OUT}/marble_lamp_s.png")

# --- дверь (64x64, золотая рама с эмиссией) ---
W = H = 64
def door_base():
    img = Image.new("RGB", (W, H), SNOW)
    d = ImageDraw.Draw(img)
    for y in range(H):
        for x in range(W):
            img.putpixel((x, y), noise(SNOW, 2))
    mask = Image.new("L", (W, H), 0)
    return img, d, mask, ImageDraw.Draw(mask)

def door_gold_rect(d, md, x0, y0, x1, y1, w=2):
    d.rectangle([x0, y0, x1, y1], outline=GOLD, width=w)
    md.rectangle([x0, y0, x1, y1], outline=A_GOLD, width=w)

def door_save(name, img, m):
    img.save(f"{OUT}/{name}.png")
    sm = Image.new("RGB", (W, H), (255, 255, 255)); sm.putalpha(m)
    sm.save(f"{OUT}/{name}_s.png")

# верхняя половина: рама, филёнка, ручка, золотой пояс внизу (середина двери)
img, d, m, md = door_base()
door_gold_rect(d, md, 2, 2, 61, 61)
d.rectangle([4, 4, 59, 59], outline=SNOW_D)
d.rectangle([8, 8, 55, 55], outline=(226, 226, 220))
d.rectangle([10, 10, 53, 53], outline=SNOW_D)
gold_band(d, md, 58, 61, w=W)
d.ellipse([46, 44, 54, 52], fill=GOLD, outline=GOLD_D)
md.ellipse([46, 44, 54, 52], fill=A_GOLD, outline=A_GOLD)
door_save("marble_door_top", img, m)

# нижняя половина: рама, филёнка, золотой пояс вверху (середина двери)
img, d, m, md = door_base()
door_gold_rect(d, md, 2, 2, 61, 61)
d.rectangle([4, 4, 59, 59], outline=SNOW_D)
d.rectangle([8, 8, 55, 55], outline=(226, 226, 220))
d.rectangle([10, 10, 53, 53], outline=SNOW_D)
gold_band(d, md, 0, 3, w=W)
d.ellipse([30, 46, 34, 50], fill=GOLD_D)
door_save("marble_door_bottom", img, m)

# --- иконка двери в инвентаре (item): как фасад двери, с золотой рамой ---
IW = 16
it = Image.new("RGB", (IW, IW), SNOW)
di = ImageDraw.Draw(it)
for y in range(IW):
    for x in range(IW):
        it.putpixel((x, y), noise(SNOW, 2))
di.rectangle([0, 0, 15, 15], outline=GOLD)
di.rectangle([1, 1, 14, 14], outline=GOLD_HI)
di.rectangle([2, 2, 13, 13], outline=SNOW_D)
di.rectangle([3, 3, 12, 12], outline=(226, 226, 220))
di.line([3, 7, 12, 7], fill=SNOW_D)
di.line([3, 8, 12, 8], fill=(226, 226, 220))
di.ellipse([9, 4, 11, 6], fill=GOLD, outline=GOLD_D)
os.makedirs("src/main/resources/assets/teyvat/textures/item", exist_ok=True)
it.save("src/main/resources/assets/teyvat/textures/item/marble_door.png")
print("textures v3 done")
