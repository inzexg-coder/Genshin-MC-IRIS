#!/usr/bin/env python3
"""Генерация мраморных текстур Teyvat v2: белоснежный мрамор + золотые окантовки (Celestia).
Единая палитра для всех блоков, чтобы монолит и вперемешку смотрелись цельно."""
import math, random
from PIL import Image, ImageDraw

S = 16
OUT = "src/main/resources/assets/teyvat/textures/block"
random.seed(20260804)

# Единая палитра «Целестия»
SNOW   = (250, 250, 248)   # белоснежный мрамор
SNOW_D = (240, 240, 236)   # тень на мраморе
VEIN   = (231, 231, 226)
VEIN_W = (240, 233, 215)   # тёплая прожилка
MORTAR = (233, 233, 229)
GOLD   = (200, 156, 56)    # золото
GOLD_HI= (242, 210, 118)   # блик золота
GOLD_D = (142, 106, 34)    # тень золота
LAMP_C = (255, 246, 220)   # тёплый свет

def noise(px, amt=5):
    r, g, b = px
    d = random.randint(-amt, amt)
    return (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)))

def base():
    img = Image.new("RGB", (S, S), SNOW)
    return img, ImageDraw.Draw(img)

def grain(img, amt=3):
    for _ in range(S * S):
        x, y = random.randrange(S), random.randrange(S)
        img.putpixel((x, y), noise(img.getpixel((x, y)), amt))

def veins(img, n=3):
    d = ImageDraw.Draw(img)
    for _ in range(n):
        x, y = random.uniform(0, S), random.uniform(0, S)
        ang = random.uniform(0, math.tau)
        col = random.choice([VEIN, VEIN_W, VEIN])
        for _ in range(random.randint(6, 10)):
            x += math.cos(ang) * 1.6
            y += math.sin(ang) * 1.6
            r = random.uniform(0.6, 1.3)
            d.ellipse([x - r, y - r, x + r, y + r], fill=col)
            if random.random() < 0.3:
                ang += random.uniform(-0.9, 0.9)

def gold_band(d, y0, y1, hi=True):
    d.rectangle([0, y0, 15, y1], fill=GOLD)
    if hi:
        d.rectangle([0, y0, 15, y0], fill=GOLD_HI)
    d.rectangle([0, y1, 15, y1], fill=GOLD_D)

def flutes(img, horizontal=False, band_gold=True):
    d = ImageDraw.Draw(img)
    n = 8
    step = S / n
    for i in range(n):
        a = int(i * step); b = int((i + 1) * step)
        rng = range(a, b)
        for t in rng:
            tt = (t - a) / (b - a)
            c = (int(SNOW[0] * (1 - 0.18 * tt)), int(SNOW[1] * (1 - 0.18 * tt)), int(SNOW[2] * (1 - 0.18 * tt)))
            for k in range(S):
                img.putpixel((k, t) if horizontal else (t, k), noise(c, 2))
    # тонкие тени между каннелюрами
    for i in range(n + 1):
        p = min(int(i * step), S - 1)
        for k in range(S):
            img.putpixel((k, p) if horizontal else (p, k), noise(SNOW_D, 2))
    if band_gold:
        gold_band(d, 0, 2)
        gold_band(d, 13, 15)

# --- базовые блоки ---
img, d = base(); veins(img, 3); grain(img, 2); img.save(f"{OUT}/marble.png")

img, d = base(); veins(img, 1); grain(img, 1); img.save(f"{OUT}/marble_polished.png")

img, d = base()
for y in range(S):
    for x in range(S):
        if y % 4 == 0 or (x + (2 if (y // 4) % 2 else 0)) % 8 == 0:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(SNOW, 3))
grain(img, 2); img.save(f"{OUT}/marble_bricks.png")

img, d = base()
for y in range(S):
    for x in range(S):
        if x % 4 == 0 or y % 4 == 0:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(SNOW, 4))
grain(img, 1); img.save(f"{OUT}/marble_tiles.png")

img, d = base()
d.rectangle([0, 0, 15, 15], outline=GOLD)
d.rectangle([1, 1, 14, 14], outline=GOLD_HI)
d.rectangle([2, 2, 13, 13], outline=SNOW_D)
d.rectangle([4, 4, 11, 11], outline=(226, 226, 220))
d.rectangle([5, 5, 10, 10], outline=GOLD)
img.save(f"{OUT}/marble_chiseled.png")

img, d = base(); veins(img, 2); grain(img, 2)
gold_band(d, 1, 3); gold_band(d, 12, 14)
img.save(f"{OUT}/marble_gold.png")

# --- колонны / балки ---
img, d = base(); flutes(img, False); img.save(f"{OUT}/marble_pillar.png")
img, d = base(); flutes(img, True);  img.save(f"{OUT}/marble_beam.png")

def column(name, bands=None, gold=True):
    img, d = base(); flutes(img, False, band_gold=gold)
    for y0, y1, col in (bands or []):
        d.rectangle([0, y0, 15, y1], fill=col)
        d.line([0, y0, 15, y0], fill=GOLD_D)
        d.line([0, y1, 15, y1], fill=GOLD_D)
    img.save(f"{OUT}/{name}.png")

column("marble_column", bands=[(2, 4, SNOW_D), (12, 14, SNOW_D)])
# малая колонна (балясина): уже, с золотыми поясками по краям
img, d = base(); flutes(img, False, band_gold=False)
for y in range(S):
    for x in range(S):
        if x < 2 or x > 13:
            img.putpixel((x, y), noise(SNOW_D, 2))
gold_band(d, 0, 1); gold_band(d, 14, 15)
img.save(f"{OUT}/marble_column_small.png")
column("marble_column_base", bands=[(3, 7, SNOW_D)])
column("marble_column_mid", bands=[(0, 1, SNOW_D), (15, 16, SNOW_D)])
column("marble_column_capital", bands=[(9, 13, SNOW_D)])
img, d = base(); flutes(img, False, band_gold=False)
gold_band(d, 3, 5); gold_band(d, 10, 12)
img.save(f"{OUT}/marble_pedestal.png")

img, d = base(); grain(img, 3)
d.rectangle([0, 0, 15, 15], outline=GOLD)
d.rectangle([1, 1, 14, 14], outline=GOLD_HI)
d.rectangle([2, 2, 13, 13], outline=SNOW_D)
cx = cy = 7.5
d.polygon([(cx, 2), (14, cy), (cx, 13), (2, cy)], outline=GOLD)
d.polygon([(cx, 4), (12, cy), (cx, 12), (4, cy)], outline=SNOW_D)
d.ellipse([cx - 1.5, cy - 1.5, cx + 1.5, cy + 1.5], fill=GOLD)
img.save(f"{OUT}/marble_gate.png")

img, d = base(); grain(img, 2)
d.rectangle([1, 1, 14, 14], outline=GOLD)
d.rectangle([2, 2, 13, 13], outline=(226, 226, 220))
d.ellipse([5, 5, 10, 10], fill=LAMP_C)
d.ellipse([6, 6, 9, 9], fill=(255, 252, 240))
d.ellipse([7, 7, 8, 8], fill=(255, 255, 255))
img.save(f"{OUT}/marble_lamp.png")

# --- дверь ---
W, H = 64, 64
img = Image.new("RGB", (W, H), SNOW)
d = ImageDraw.Draw(img)
for y in range(H):
    for x in range(W):
        img.putpixel((x, y), noise(SNOW, 2))
d.rectangle([3, 3, 60, 60], outline=GOLD)
d.rectangle([5, 5, 58, 58], outline=(226, 226, 220))
d.rectangle([10, 10, 53, 27], outline=SNOW_D)
d.rectangle([10, 35, 53, 53], outline=SNOW_D)
d.rectangle([12, 12, 51, 25], outline=(236, 236, 232))
d.rectangle([12, 37, 51, 51], outline=(236, 236, 232))
d.rectangle([0, 30, 63, 33], fill=GOLD)
d.line([0, 30, 63, 30], fill=GOLD_HI)
d.line([0, 33, 63, 33], fill=GOLD_D)
d.ellipse([49, 45, 55, 51], fill=GOLD, outline=GOLD_D)
img.save(f"{OUT}/marble_door_top.png")
d2 = ImageDraw.Draw(img)
d2.rectangle([0, 61, 63, 63], fill=GOLD_D)
img.save(f"{OUT}/marble_door_bottom.png")

# --- иконка двери в инвентаре (item) ---
IW = 16
it = Image.new("RGB", (IW, IW), SNOW)
di = ImageDraw.Draw(it)
for y in range(IW):
    for x in range(IW):
        it.putpixel((x, y), noise(SNOW, 2))
di.rectangle([1, 1, 14, 14], outline=GOLD)
di.rectangle([2, 2, 13, 13], outline=(226, 226, 220))
di.rectangle([3, 3, 12, 12], outline=SNOW_D)
di.rectangle([4, 4, 11, 11], outline=(236, 236, 232))
di.line([3, 7, 12, 7], fill=(226, 226, 220))
di.line([3, 8, 12, 8], fill=(226, 226, 220))
di.ellipse([9, 4, 11, 6], fill=GOLD, outline=GOLD_D)
import os
os.makedirs("src/main/resources/assets/teyvat/textures/item", exist_ok=True)
it.save("src/main/resources/assets/teyvat/textures/item/marble_door.png")
print("textures v2 done")
