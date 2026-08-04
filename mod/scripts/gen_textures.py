#!/usr/bin/env python3
"""Генерация мраморных текстур Teyvat v4: белоснежный мрамор + узорные золотые окантовки (Celestia).
Единая палитра для всех блоков, чтобы монолит и вперемешку смотрелись цельно.

Золото больше НЕ светится: specular-карты (*_s.png) в labPBR-каналах:
  R = smoothness, G = metalness, B = F0, A = эмиссия (255 = нет эмиссии).
Золото: (200, 255, 230, 255) — гладкий металл с сильным френелем.
Мрамор: (60, 0, 40, 255) — матовый.
Фонарь: ядро остаётся светящимся (A < 255), окантовка металлическая.
"""
import math, os, random
from PIL import Image, ImageDraw

S = 16
OUT = "src/main/resources/assets/teyvat/textures/block"
random.seed(20260804)

# Единая палитра «Целестия» — мрамор белоснежный, без прожилок и серых пятен
SNOW   = (252, 252, 250)
SNOW_D = (242, 242, 238)   # мягкая тень
GLASS  = (226, 226, 220)
MORTAR = (233, 233, 229)
GOLD   = (200, 156, 56)
GOLD_HI= (242, 210, 118)
GOLD_D = (142, 106, 34)
LAMP_C = (255, 246, 220)

# labPBR specular-карты (RGBA)
GOLD_SPEC   = (200, 255, 230, 255)   # металл, без эмиссии
MARBLE_SPEC = (60, 0, 40, 255)       # матовый мрамор, без эмиссии
LAMP_SPEC   = (60, 0, 40, 235)       # светящееся ядро фонаря

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
    spec = Image.new("RGBA", (S, S), MARBLE_SPEC)
    return img, ImageDraw.Draw(img), spec, ImageDraw.Draw(spec)

def save(name, img, spec=None):
    img.save(f"{OUT}/{name}.png")
    if spec is not None:
        spec.save(f"{OUT}/{name}_s.png")

# ---------- золото: рисование сразу в цвет и в spec-карту ----------
def gold_px(d, sd, x, y):
    if 0 <= x < S and 0 <= y < S:
        d.point((x, y), fill=GOLD)
        sd.point((x, y), GOLD_SPEC)

def gold_rect(d, sd, box, width=1):
    d.rectangle(box, outline=GOLD, width=width)
    sd.rectangle(box, outline=GOLD_SPEC, width=width)

def gold_line(d, sd, a, b):
    d.line([a, b], fill=GOLD)
    sd.line([a, b], GOLD_SPEC)

def diamond(d, sd, cx, cy, r, fill=GOLD):
    for dy in range(-r, r + 1):
        w = r - abs(dy)
        for dx in range(-w, w + 1):
            gold_px(d, sd, cx + dx, cy + dy)

def diamond_outline(d, sd, cx, cy, r):
    for a, b in (((cx, cy - r), (cx + r, cy)), ((cx + r, cy), (cx, cy + r)),
                 ((cx, cy + r), (cx - r, cy)), ((cx - r, cy), (cx, cy - r))):
        gold_line(d, sd, a, b)

def band_ornate(d, sd, y, studs=True):
    """Тонкий золотой пояс-бисер: линия 1px + ромбики на ней."""
    d.line([(0, y), (S - 1, y)], fill=GOLD)
    sd.line([(0, y), (S - 1, y)], GOLD_SPEC)
    if studs:
        for cx in (2, 6, 10, 14):
            diamond(d, sd, cx, y, 1)

def meander_hook(d, sd, cx, cy, mirror_x=False, mirror_y=False):
    """Маленький золотой крючок-меандр в углу (2x2) для узорных рамок."""
    pts = [(0, 0), (1, 0), (0, 1)]
    for dx, dy in pts:
        x = cx + (dx if not mirror_x else -dx)
        y = cy + (dy if not mirror_y else -dy)
        gold_px(d, sd, x, y)

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
img, d, spec, sd = base(); grain(img, 2); save("marble", img, spec)

img, d, spec, sd = base(); grain(img, 1); save("marble_polished", img, spec)

img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        if y % 4 == 0 or (x + (2 if (y // 4) % 2 else 0)) % 8 == 0:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(SNOW, 3))
grain(img, 2); save("marble_bricks", img, spec)

img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        if x % 4 == 0 or y % 4 == 0:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(SNOW, 4))
grain(img, 1); save("marble_tiles", img, spec)

# --- chiseled: золотая рамка с меандром в углах + ромб-медальон ---
img, d, spec, sd = base(); grain(img, 1)
gold_rect(d, sd, [0, 0, 15, 15])
meander_hook(d, sd, 1, 1); meander_hook(d, sd, 14, 1, mirror_x=True)
meander_hook(d, sd, 1, 14, mirror_y=True); meander_hook(d, sd, 14, 14, mirror_x=True, mirror_y=True)
d.rectangle([2, 2, 13, 13], outline=SNOW_D)
d.rectangle([4, 4, 11, 11], outline=(226, 226, 220))
diamond_outline(d, sd, 7, 8, 4)
diamond(d, sd, 7, 8, 1, fill=GOLD_HI)
save("marble_chiseled", img, spec)

# --- gold trimmed: белый мрамор с узорными золотыми полосами ---
img, d, spec, sd = base(); grain(img, 1)
band_ornate(d, sd, 1); band_ornate(d, sd, 12)
save("marble_gold", img, spec)

# --- колонны / балки: узорные пояски НЕ на краях, чтобы стопка колонн была бесшовной ---
img, d, spec, sd = base(); flutes(img, False); band_ornate(d, sd, 2); band_ornate(d, sd, 12); save("marble_pillar", img, spec)
img, d, spec, sd = base(); flutes(img, True);  band_ornate(d, sd, 2); band_ornate(d, sd, 12); save("marble_beam", img, spec)

img, d, spec, sd = base(); flutes(img, False); band_ornate(d, sd, 2); band_ornate(d, sd, 12); save("marble_column", img, spec)
img, d, spec, sd = base(); flutes(img, False); band_ornate(d, sd, 2, studs=False); band_ornate(d, sd, 12, studs=False); save("marble_column_small", img, spec)
img, d, spec, sd = base(); flutes(img, False); band_ornate(d, sd, 3);                        save("marble_column_base", img, spec)
img, d, spec, sd = base(); flutes(img, False); band_ornate(d, sd, 7, studs=False);          save("marble_column_mid", img, spec)
img, d, spec, sd = base(); flutes(img, False); band_ornate(d, sd, 10);                     save("marble_column_capital", img, spec)
img, d, spec, sd = base(); flutes(img, False); band_ornate(d, sd, 3); band_ornate(d, sd, 10); save("marble_pedestal", img, spec)

# --- ворота (куб с орнаментом): двойной ромб + точки, симметричный ---
img, d, spec, sd = base(); grain(img, 1)
gold_rect(d, sd, [0, 0, 15, 15])
d.rectangle([1, 1, 14, 14], outline=GOLD_HI); sd.rectangle([1, 1, 14, 14], GOLD_SPEC)
d.rectangle([2, 2, 13, 13], outline=SNOW_D)
# внешний ромб с осью x=7, y=8
for a, b in (((7, 3), (12, 8)), ((12, 8), (7, 13)), ((7, 13), (2, 8)), ((2, 8), (7, 3))):
    gold_line(d, sd, a, b)
# внутренний ромб
for a, b in (((7, 5), (10, 8)), ((10, 8), (7, 11)), ((7, 11), (4, 8)), ((4, 8), (7, 5))):
    gold_line(d, sd, a, b)
diamond(d, sd, 7, 8, 1, fill=GOLD_HI)
diamond(d, sd, 2, 8, 0); diamond(d, sd, 12, 8, 0); diamond(d, sd, 7, 3, 0); diamond(d, sd, 7, 13, 0)
save("marble_gate", img, spec)

# --- арка (лицевая сторона с нарисованным проёмом, полностью непрозрачная) ---
img, d, spec, sd = base(); grain(img, 2)
def in_opening(x, y):
    if y >= 9:
        return 5 <= x <= 10 and y <= 15
    if y >= 5:
        dx = x - 7.5
        return dx * dx + (y - 8.5) * (y - 8.5) <= 13.0 and dx <= 3.4
    return False
for y in range(S):
    for x in range(S):
        if in_opening(x, y):
            img.putpixel((x, y), (84, 84, 84))
# золотой контур проёма (граница тёмной области)
for y in range(S):
    for x in range(S):
        if in_opening(x, y):
            continue
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < S and 0 <= ny < S and in_opening(nx, ny):
                gold_px(d, sd, x, y)
                break
# капители у основания арки
diamond(d, sd, 4, 14, 1); diamond(d, sd, 11, 14, 1)
save("marble_arch", img, spec)

# --- side stairs: диагональный орнамент пола (ромбы под 45°) ---
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        if (x + y) % 4 == 0 or (x - y) % 4 == 0:
            img.putpixel((x, y), GOLD)
            spec.putpixel((x, y), GOLD_SPEC)
        else:
            cell = ((x + y) // 4 + (x - y) // 4) % 2
            img.putpixel((x, y), noise(SNOW_D if cell else SNOW, 2))
save("marble_side_stairs", img, spec)

# --- фонарь: рамка с узорами, светящееся ядро ---
img, d, spec, sd = base(); grain(img, 1)
# золотая рамка
gold_rect(d, sd, [1, 1, 14, 14])
# стекло
d.rectangle([2, 2, 13, 13], outline=GLASS)
# узор: ромбики на стекле по углам
diamond(d, sd, 3, 3, 1); diamond(d, sd, 12, 3, 1); diamond(d, sd, 3, 12, 1); diamond(d, sd, 12, 12, 1)
# светящееся ядро с золотым ромбом-кольцом вокруг
cx = cy = 7.5
r_core = 3.2
for y in range(S):
    for x in range(S):
        dist = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if dist <= 1.2:
            img.putpixel((x, y), (255, 252, 240))
        elif dist <= r_core:
            t = 1.0 - (dist - 1.2) / (r_core - 1.2)
            c = (int(LAMP_C[0] + (255 - LAMP_C[0]) * t), int(LAMP_C[1] + (255 - LAMP_C[1]) * t), int(LAMP_C[2] + (255 - LAMP_C[2]) * t))
            img.putpixel((x, y), c)
diamond_outline(d, sd, 7, 7, 2)
# spec: ядро светится
m = Image.new("L", (S, S), 255)
for y in range(S):
    for x in range(S):
        dist = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if dist <= 1.2:
            m.putpixel((x, y), LAMP_SPEC[3])
        elif dist <= r_core:
            m.putpixel((x, y), int(LAMP_SPEC[3] * (1 - (dist - 1.2) / (r_core - 1.2))))
for y in range(S):
    for x in range(S):
        if spec.getpixel((x, y)) == GOLD_SPEC:
            m.putpixel((x, y), 255)  # золотая окантовка фонаря: металл, не светится
sm = Image.new("RGBA", (S, S))
for y in range(S):
    for x in range(S):
        r, g, b, a = spec.getpixel((x, y))
        sm.putpixel((x, y), (r, g, b, m.getpixel((x, y))))
save("marble_lamp", img, sm)

# --- дверь (64x64, узорная: золотая рама, медальоны, окантовка сегментов) ---
W = H = 64
def door_base():
    img = Image.new("RGB", (W, H), SNOW)
    for y in range(H):
        for x in range(W):
            img.putpixel((x, y), noise(SNOW, 2))
    spec = Image.new("RGBA", (W, H), MARBLE_SPEC)
    return img, ImageDraw.Draw(img), spec, ImageDraw.Draw(spec)

def door_gold_rect(d, sd, x0, y0, x1, y1, w=2):
    d.rectangle([x0, y0, x1, y1], outline=GOLD, width=w)
    sd.rectangle([x0, y0, x1, y1], outline=GOLD_SPEC, width=w)

def door_diamond(d, sd, cx, cy, r):
    for a, b in (((cx, cy - r), (cx + r, cy)), ((cx + r, cy), (cx, cy + r)),
                 ((cx, cy + r), (cx - r, cy)), ((cx - r, cy), (cx, cy - r))):
        d.line([a, b], fill=GOLD)
        sd.line([a, b], GOLD_SPEC)

def door_medallion(d, sd, cx, cy):
    door_diamond(d, sd, cx, cy, 5)
    door_diamond(d, sd, cx, cy, 2)
    d.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=GOLD_HI)
    sd.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], GOLD_SPEC)

def door_frame(d, sd):
    door_gold_rect(d, sd, 2, 2, 61, 61, 2)
    d.rectangle([6, 6, 57, 57], outline=SNOW_D)
    d.rectangle([8, 8, 55, 55], outline=(226, 226, 220))
    d.rectangle([10, 10, 53, 53], outline=SNOW_D)
    # меандр по углам рамы
    for cx, my in ((8, False), (55, True)):
        for cy, mx in ((8, False), (55, True)):
            for dx, dy in ((0, 0), (2, 0), (0, 2)):
                x = cx + (dx if not mx else -dx)
                y = cy + (dy if not my else -dy)
                d.point((x, y), fill=GOLD)
                sd.point((x, y), GOLD_SPEC)

def door_band(d, sd, y):
    """Горизонтальный золотой пояс-стык между сегментами двери."""
    d.rectangle([0, y, W - 1, y + 2], fill=GOLD)
    sd.rectangle([0, y, W - 1, y + 2], GOLD_SPEC)
    d.rectangle([0, y, W - 1, y], fill=GOLD_HI)
    sd.rectangle([0, y, W - 1, y], GOLD_SPEC)
    for cx in range(6, W, 16):
        for yy in (y + 1,):
            d.ellipse([cx, yy - 1, cx + 2, yy + 1], fill=GOLD_D)
            sd.ellipse([cx, yy - 1, cx + 2, yy + 1], GOLD_SPEC)

def door_save(name, img, spec):
    img.save(f"{OUT}/{name}.png")
    spec.save(f"{OUT}/{name}_s.png")

# верхний сегмент: рама, медальон выше центра, золотой пояс снизу (стык)
img, d, spec, sd = door_base()
door_frame(d, sd)
door_medallion(d, sd, 32, 24)
door_band(d, sd, 60)
door_save("marble_door_top", img, spec)

# средний сегмент: рама, медальон по центру, пояса сверху и снизу
img, d, spec, sd = door_base()
door_frame(d, sd)
door_medallion(d, sd, 32, 32)
door_band(d, sd, 0)
door_band(d, sd, 60)
door_save("marble_door_middle", img, spec)

# нижний сегмент: рама, медальон ниже центра, пояс сверху, ручка справа
img, d, spec, sd = door_base()
door_frame(d, sd)
door_medallion(d, sd, 32, 40)
door_band(d, sd, 0)
# ручка: золотое кольцо + пластина (на текстуре справа = у игрока справа при взгляде на дверь)
d.ellipse([44, 40, 54, 50], outline=GOLD, width=2)
sd.ellipse([44, 40, 54, 50], outline=GOLD_SPEC, width=2)
d.ellipse([46, 42, 52, 48], outline=GOLD_HI, width=1)
sd.ellipse([46, 42, 52, 48], outline=GOLD_SPEC, width=1)
d.rectangle([47, 45, 51, 47], fill=GOLD_D)
sd.rectangle([47, 45, 51, 47], GOLD_SPEC)
door_save("marble_door_bottom", img, spec)

os.makedirs("src/main/resources/assets/teyvat/textures/item", exist_ok=True)
print("textures v4 done")
