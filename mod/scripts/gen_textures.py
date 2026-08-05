#!/usr/bin/env python3
"""Генерация мраморных текстур Teyvat v9: БЕЛОСНЕЖНЫЙ чистый мрамор + элегантное золото.

Стиль v9 («Белоснежный резной»):
  - основной цвет — чистый белый (255,255,255), НИКАКИХ пятен, зерна, прожилок;
  - золото — только элегантная окантовка на отдельных блоках (рамки, меандр);
  - блоки НЕ цельные: внутри вырезаны пиксели — настоящие прорези (alpha=0):
    chiseled — решётка с квадратными окнами, арка — сквозной проём, ворота —
    резные створки с окнами; прорези рендерятся через CUTOUT-слой;
  - фонарь потусклее: свет 13, ядро меньше, эмиссия A=230 (без шейдерного буста);
  - ромбов нет: медальоны — золотые кольца.

labPBR spec-карты (*_s.png): R = smoothness, G = metalness, B = F0, A = эмиссия.
"""
import math, os
from PIL import Image, ImageDraw

S = 16
OUT = "src/main/resources/assets/teyvat/textures/block"

# Палитра: белоснежный мрамор + элегантное золото
SNOW   = (255, 255, 255)   # чистый белый
SNOW_D = (238, 238, 238)   # едва заметная тень резьбы (только для объёма)
GLASS  = (245, 245, 245)   # стекло фонаря
MORTAR = (238, 238, 238)   # швы кладки/плитки
GOLD   = (230, 198, 140)   # элегантное золото
GOLD_HI= (250, 240, 212)   # блик на золоте
GOLD_D = (185, 155, 100)   # тень золота
LAMP_C = (255, 240, 200)   # тёплый свет фонаря

# labPBR specular-карты (RGBA)
GOLD_SPEC   = (250, 255, 244, 255)   # зеркальный металл: R=0.98, сильные блики
MARBLE_SPEC = (30, 0, 40, 255)       # матовый белый мрамор
POLISH_SPEC = (220, 0, 40, 255)      # глянцевый полированный (без металла)
LAMP_SPEC   = (30, 0, 40, 230)       # фонарь: A=230 — умеренная эмиссия

# Единые ряды окантовок (меандр всегда в рядах 3..5 и 11..13)
BAND_TOP = 3
BAND_BOT = 11

def base(spec_color=MARBLE_SPEC):
    """RGBA-полотно: белый, alpha=255; spec-карта отдельно."""
    img = Image.new("RGBA", (S, S), SNOW + (255,))
    spec = Image.new("RGBA", (S, S), spec_color)
    return img, ImageDraw.Draw(img), spec, ImageDraw.Draw(spec)

def save(name, img, spec=None):
    img.save(f"{OUT}/{name}.png")
    if spec is not None:
        spec.save(f"{OUT}/{name}_s.png")

# ---------- золото: рисуем в цвет и в spec-карту ----------
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

def ring(d, sd, cx, cy, r, width=1):
    """Золотое кольцо (медальон вместо ромбов)."""
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=GOLD, width=width)
    sd.ellipse([cx - r, cy - r, cx + r, cy + r], GOLD_SPEC, width=width)
    d.point((cx, cy), fill=GOLD_HI)
    sd.point((cx, cy), GOLD_SPEC)

def cutout(img, box):
    """Вырезать пиксели: прозрачные (alpha=0) внутри блока — настоящая прорезь."""
    x0, y0, x1, y1 = box
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if 0 <= x < S and 0 <= y < S:
                img.putpixel((x, y), (0, 0, 0, 0))

# Греческий ключ (меандр), плитка 8x3, линия 1px. Стыкуется по горизонтали.
MEANDER_ROWS = [
    "11111111",
    "10000001",
    "10111111",
]

def meander_tile(d, sd, x0, y0):
    for dy, row in enumerate(MEANDER_ROWS):
        for dx, ch in enumerate(row):
            if ch == "1":
                gold_px(d, sd, x0 + dx, y0 + dy)

def meander_band(d, sd, y0):
    meander_tile(d, sd, 0, y0)
    meander_tile(d, sd, 8, y0)

def meander_band_v(d, sd, x0):
    """Вертикальный пояс-меандр 3x16 (для рамок top/bottom)."""
    for dy, row in enumerate(MEANDER_ROWS):
        for dx, ch in enumerate(row):
            if ch == "1":
                gold_px(d, sd, x0 + dy, dx)

def add_bands(d, sd, small=False):
    if small:
        gold_line(d, sd, (0, BAND_TOP), (S - 1, BAND_TOP))
        gold_line(d, sd, (0, BAND_TOP + 2), (S - 1, BAND_TOP + 2))
        gold_line(d, sd, (0, BAND_BOT), (S - 1, BAND_BOT))
        gold_line(d, sd, (0, BAND_BOT + 2), (S - 1, BAND_BOT + 2))
    else:
        meander_band(d, sd, BAND_TOP)
        meander_band(d, sd, BAND_BOT)

def flutes(img):
    """Мягкие каннелюры: едва заметные желобки (чистый белый + тень резьбы)."""
    n = 6
    for y in range(S):
        for x in range(S):
            p = x + 0.5
            shade = 0.04 * (1.0 - math.cos((p / S) * n * math.pi * 2.0)) / 2.0
            c = (int(255 - 12 * shade), int(255 - 12 * shade), int(255 - 12 * shade))
            img.putpixel((x, y), c + (255,))

# ---------- базовые блоки: чистый белый, без зерна ----------
img, d, spec, sd = base(); save("marble", img, spec)

# полированный: белый, отличается только глянцевым spec (без металла)
img, d, spec, sd = base(POLISH_SPEC); save("marble_polished", img, spec)

# кирпичи: белая кладка 8x4 с едва заметными швами
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        row = y // 4
        brick_x = (x + (4 if row % 2 else 0)) % 8
        if y % 4 == 3 or brick_x == 7:
            img.putpixel((x, y), MORTAR + (255,))
save("marble_bricks", img, spec)

# плитка: белая сетка 4x4
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        if x % 4 == 3 or y % 4 == 3:
            img.putpixel((x, y), MORTAR + (255,))
save("marble_tiles", img, spec)

# ---------- top-текстуры ----------
# marble_top: тонкая золотая рамка (крышки колонн)
img, d, spec, sd = base()
gold_rect(d, sd, [2, 2, 13, 13])
gold_rect(d, sd, [3, 3, 12, 12])
save("marble_top", img, spec)

# marble_gold_top: рама + меандровая рамка по периметру (top/bottom декора)
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
meander_band(d, sd, BAND_TOP)
meander_band(d, sd, BAND_BOT)
meander_band_v(d, sd, 2)
meander_band_v(d, sd, 11)
gold_rect(d, sd, [5, 5, 10, 10])
ring(d, sd, 7.5, 7.5, r=2)
save("marble_gold_top", img, spec)

# ---------- gold trimmed: стороны с поясами-меандрами ----------
img, d, spec, sd = base()
add_bands(d, sd)
save("marble_gold", img, spec)

# ---------- chiseled: РЕЗНАЯ РЕШЁТКА с настоящими прорезями (окна вырезаны) ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
# решётка: золотые прутья делят поле на 4 окна 5x5
for x in (0, 7, 8, 15):
    gold_line(d, sd, (x, 0), (x, 15))
for y in (0, 7, 8, 15):
    gold_line(d, sd, (0, y), (15, y))
# прорези: окна прозрачны (alpha=0) — сквозь блок видно дальше
cutout(img, [2, 2, 6, 6])
cutout(img, [9, 2, 13, 6])
cutout(img, [2, 9, 6, 13])
cutout(img, [9, 9, 13, 13])
save("marble_chiseled", img, spec)

# ---------- арка: РЕЗНОЙ ФАСАД-ВРАТА со сквозным проёмом (внутри арки прорезь) ----------
img, d, spec, sd = base()
def arch_gold(x, y):
    gold_px(d, sd, x, y)
for x in range(S):
    arch_gold(x, 0)
meander_band(d, sd, 1)
# ступенчатый золотой контур арки
for y in range(5, 15):
    arch_gold(5, y); arch_gold(10, y)     # вертикали
for x in range(5, 11):
    arch_gold(x, 14)                      # низ проёма
for x in range(6, 10):
    arch_gold(x, 5)                       # плечи
for x in range(7, 9):
    arch_gold(x, 4)                       # верх арки
# ПРОРЕЗЬ: внутри контура — сквозное окно (видно сквозь арку)
cutout(img, [6, 6, 9, 13])
save("marble_arch_front", img, spec)

# ---------- колонны / балки: каннелюры + золотые пояса ----------
img, d, spec, sd = base(); flutes(img); add_bands(d, sd); save("marble_pillar", img, spec)
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        p = y + 0.5
        shade = 0.04 * (1.0 - math.cos((p / S) * 6 * math.pi * 2.0)) / 2.0
        c = (int(255 - 12 * shade), int(255 - 12 * shade), int(255 - 12 * shade))
        img.putpixel((x, y), c + (255,))
add_bands(d, sd)
save("marble_beam", img, spec)

for name in ("marble_column", "marble_column_base", "marble_column_mid",
             "marble_column_capital", "marble_pedestal"):
    img, d, spec, sd = base(); flutes(img); add_bands(d, sd); save(name, img, spec)

# маленькая колонна: тонкие линии вместо меандра
img, d, spec, sd = base(); flutes(img); add_bands(d, sd, small=True)
save("marble_column_small", img, spec)

# ---------- ворота: РЕЗНЫЕ СТВОРКИ с прорезями-окнами ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
meander_band(d, sd, BAND_TOP)
meander_band(d, sd, BAND_BOT)
gold_line(d, sd, (7, 6), (8, 6)); gold_line(d, sd, (7, 10), (8, 10))
# створки с золотыми рамами и маленькими окнами-прорезями
for x0 in (1, 8):
    gold_rect(d, sd, [x0, 6, x0 + 6, 10])
    cutout(img, [x0 + 2, 7, x0 + 4, 9])
    ring(d, sd, x0 + 3, 8, r=1, width=1)
save("marble_gate", img, spec)

# ---------- side stairs (горизонтальные ступени): белая мозаика с золотой рамой ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
gold_line(d, sd, (7, 1), (7, 14)); gold_line(d, sd, (8, 1), (8, 14))
gold_line(d, sd, (1, 7), (14, 7)); gold_line(d, sd, (1, 8), (14, 8))
for qx in (0, 8):
    for qy in (0, 8):
        gold_rect(d, sd, [qx + 1, qy + 1, qx + 6, qy + 6])
save("marble_side_stairs", img, spec)

# ---------- фонарь: белый корпус, золотая рама, приглушённое тёплое ядро ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
for cx, cy in ((2, 2), (13, 2), (2, 13), (13, 13)):
    ring(d, sd, cx, cy, r=1)
gold_line(d, sd, (3, 7), (12, 7)); gold_line(d, sd, (7, 3), (7, 12))
cx = cy = 7.5
r_core = 3.2
for y in range(S):
    for x in range(S):
        dist = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if dist <= 1.0:
            img.putpixel((x, y), (255, 250, 230, 255))
        elif dist <= r_core:
            t = 1.0 - (dist - 1.0) / (r_core - 1.0)
            c = (int(LAMP_C[0] + (255 - LAMP_C[0]) * t * 0.6),
                 int(LAMP_C[1] + (255 - LAMP_C[1]) * t * 0.6),
                 int(LAMP_C[2] + (255 - LAMP_C[2]) * t * 0.6))
            img.putpixel((x, y), c + (255,))
# эмиссия: ядро светится умеренно (A=230), золото — металл, не светится
m = Image.new("L", (S, S), 255)
for y in range(S):
    for x in range(S):
        dist = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if dist <= r_core:
            m.putpixel((x, y), LAMP_SPEC[3])
for y in range(S):
    for x in range(S):
        if spec.getpixel((x, y)) == GOLD_SPEC:
            m.putpixel((x, y), 255)
sm = Image.new("RGBA", (S, S))
for y in range(S):
    for x in range(S):
        r, g, b, a = spec.getpixel((x, y))
        sm.putpixel((x, y), (r, g, b, m.getpixel((x, y))))
save("marble_lamp", img, sm)

# ---------- дверь: белоснежные створки с золотой рамой, меандром и кольцом ----------
W = H = 16
def door_base():
    img = Image.new("RGBA", (W, H), SNOW + (255,))
    spec = Image.new("RGBA", (W, H), MARBLE_SPEC)
    return img, ImageDraw.Draw(img), spec, ImageDraw.Draw(spec)

def door_gold_line(d, sd, x0, y0, x1, y1):
    d.line([x0, y0, x1, y1], fill=GOLD)
    sd.line([x0, y0, x1, y1], GOLD_SPEC)

def door_ring(d, sd, cx, cy, r=3):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=GOLD, width=1)
    sd.ellipse([cx - r, cy - r, cx + r, cy + r], GOLD_SPEC, width=1)
    d.ellipse([cx - max(1, r - 2), cy - max(1, r - 2), cx + max(1, r - 2), cy + max(1, r - 2)],
              outline=GOLD_HI, width=1)
    sd.ellipse([cx - max(1, r - 2), cy - max(1, r - 2), cx + max(1, r - 2), cy + max(1, r - 2)],
               GOLD_SPEC, width=1)

def door_meander(d, sd, x0, y0):
    for dy, row in enumerate(MEANDER_ROWS):
        for dx, ch in enumerate(row):
            if ch == "1":
                d.point((x0 + dx, y0 + dy), fill=GOLD)
                sd.point((x0 + dx, y0 + dy), GOLD_SPEC)

def door_band(d, sd, y):
    door_meander(d, sd, 0, y)
    door_meander(d, sd, 8, y)
    door_gold_line(d, sd, 2, y + 3, W - 3, y + 3)

def door_frame(d, sd):
    door_gold_line(d, sd, 1, 1, W - 2, 1)
    door_gold_line(d, sd, 1, H - 2, W - 2, H - 2)
    door_gold_line(d, sd, 1, 1, 1, H - 2)
    door_gold_line(d, sd, W - 2, 1, W - 2, H - 2)

def door_save(name, img, spec):
    img.save(f"{OUT}/{name}.png")
    spec.save(f"{OUT}/{name}_s.png")

# верхняя половина: меандр по низу + золотое кольцо
img, d, spec, sd = door_base(); door_frame(d, sd)
door_ring(d, sd, 7.5, 5.5, r=3)
door_band(d, sd, 12)
door_save("marble_door_top", img, spec)

# нижняя половина: кольцо + золотая ручка + меандр
img, d, spec, sd = door_base(); door_frame(d, sd)
door_ring(d, sd, 7.5, 4.5, r=3)
d.ellipse([10, 6, 14, 10], outline=GOLD, width=1)
sd.ellipse([10, 6, 14, 10], outline=GOLD_SPEC, width=1)
d.ellipse([11, 7, 13, 9], outline=GOLD_HI, width=1)
sd.ellipse([11, 7, 13, 9], outline=GOLD_SPEC, width=1)
d.rectangle([11, 8, 13, 9], fill=GOLD_D)
sd.rectangle([11, 8, 13, 9], GOLD_SPEC)
door_band(d, sd, 12)
door_save("marble_door_bottom", img, spec)

# иконка двери в инвентаре
img, d, spec, sd = door_base(); door_frame(d, sd)
door_ring(d, sd, 7.5, 5.5, r=2)
door_meander(d, sd, 0, 11); door_meander(d, sd, 8, 11)
door_gold_line(d, sd, 2, 14, W - 3, 14)
d.ellipse([11, 5, 14, 8], outline=GOLD, width=1)
sd.ellipse([11, 5, 14, 8], outline=GOLD_SPEC, width=1)

os.makedirs("src/main/resources/assets/teyvat/textures/item", exist_ok=True)
img.save("src/main/resources/assets/teyvat/textures/item/marble_door.png")
print("textures v9 done")
