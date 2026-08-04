#!/usr/bin/env python3
"""Генерация мраморных текстур Teyvat v6: белоснежный мрамор + древнегреческие золотые окантовки (Celestia).

ЕДИНАЯ СИСТЕМА ОКАНТОВОК:
  - все декорированные боковые текстуры (marble_gold, pillar, beam, column*,
    pedestal) несут пояса-меандры в ОДНИХ И ТЕХ ЖЕ рядах: 2..6 и 10..14.
    Поэтому блоки сочетаются вперемешку: окантовки всегда на одном уровне.
  - текстура периодична по 16px, значит колонны, поставленные друг на друга,
    дают непрерывный повторяющийся узор без швов.

Золото НЕ светится: specular-карты (*_s.png) в labPBR-каналах:
  R = smoothness, G = metalness, B = F0, A = эмиссия (255 = нет эмиссии).
Золото: (235, 255, 230, 255) — гладкий зеркальный металл (R=0.92) с отражениями.
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
SNOW_D = (244, 244, 240)   # мягкая тень
GLASS  = (230, 230, 224)
MORTAR = (235, 235, 231)
GOLD   = (205, 159, 58)
GOLD_HI= (246, 214, 122)
GOLD_D = (150, 112, 36)
LAMP_C = (255, 246, 220)

# labPBR specular-карты (RGBA)
GOLD_SPEC   = (235, 255, 230, 255)   # металл, без эмиссии, R=0.92 -> зеркальные блики
MARBLE_SPEC = (60, 0, 40, 255)       # матовый мрамор, без эмиссии
LAMP_SPEC   = (60, 0, 40, 235)       # светящееся ядро фонаря

# Пояса окантовки — ОДИНАКОВЫЕ РЯДЫ для всех блоков
BAND_TOP = 2   # меандр в рядах 2..6
BAND_BOT = 10  # меандр в рядах 10..14

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

# Греческий ключ (меандр), плитка 8x5, линия 1px. Стыкуется по горизонтали.
MEANDER_ROWS = [
    "11111111",  # верхняя перекладина
    "10000001",
    "10111111",
    "10100000",
    "11100000",
]

def meander_tile(d, sd, x0, y0):
    for dy, row in enumerate(MEANDER_ROWS):
        for dx, ch in enumerate(row):
            if ch == "1":
                gold_px(d, sd, x0 + dx, y0 + dy)

def meander_band(d, sd, y0):
    """Горизонтальный пояс-меандр 16x5 (ряды y0..y0+4)."""
    for x0 in (0, 8):
        meander_tile(d, sd, x0, y0)

def band_top(d, sd):
    meander_band(d, sd, BAND_TOP)

def band_bottom(d, sd):
    meander_band(d, sd, BAND_BOT)

def meander_hook(d, sd, cx, cy, size=2):
    """Маленький золотой крючок-меандр в углу (size x size)."""
    pts = [(0, 0), (1, 0), (0, 1)]
    for dx, dy in pts:
        gold_px(d, sd, cx + dx * size, cy + dy * size)

def flutes(img, horizontal=False):
    """Каннелюры: вертикальные/горизонтальные бороздки с мягкой тенью (без серых пятен)."""
    n = 8
    step = S / n
    for i in range(n):
        a = int(i * step); b = int((i + 1) * step)
        for t in range(a, b):
            tt = (t - a) / (b - a)
            c = (int(SNOW[0] * (1 - 0.10 * tt)), int(SNOW[1] * (1 - 0.10 * tt)), int(SNOW[2] * (1 - 0.10 * tt)))
            for k in range(S):
                img.putpixel((k, t) if horizontal else (t, k), noise(c, 1))
    for i in range(n + 1):
        p = min(int(i * step), S - 1)
        for k in range(S):
            img.putpixel((k, p) if horizontal else (p, k), noise(SNOW_D, 1))

def add_bands(d, sd, with_meander=True):
    if with_meander:
        band_top(d, sd)
        band_bottom(d, sd)
    else:
        # мини-пояс: только перекладины меандра (1px линии с крючками)
        gold_line(d, sd, (0, BAND_TOP), (S - 1, BAND_TOP))
        gold_line(d, sd, (0, BAND_TOP + 2), (S - 1, BAND_TOP + 2))
        gold_line(d, sd, (0, BAND_BOT), (S - 1, BAND_BOT))
        gold_line(d, sd, (0, BAND_BOT + 2), (S - 1, BAND_BOT + 2))

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

# --- gold trimmed: белый мрамор с поясами-меандрами в канонических рядах ---
img, d, spec, sd = base(); grain(img, 1)
band_top(d, sd)
band_bottom(d, sd)
# тонкая разделительная линия по центру
gold_line(d, sd, (0, 8), (S - 1, 8))
save("marble_gold", img, spec)

# --- chiseled: тонкая рамка по краю + крючки-меандры по углам + ромб-медальон ---
img, d, spec, sd = base(); grain(img, 1)
gold_rect(d, sd, [0, 0, 15, 15])
for cx, mx in ((1, False), (14, True)):
    for cy, my in ((1, False), (14, True)):
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            x = cx + (dx if not mx else -dx)
            y = cy + (dy if not my else -dy)
            gold_px(d, sd, x, y)
d.rectangle([3, 3, 12, 12], outline=SNOW_D)
diamond_outline(d, sd, 7, 7, 3)
diamond(d, sd, 7, 7, 1, fill=GOLD_HI)
save("marble_chiseled", img, spec)

# --- арка: цельный блок-врата. Фасад 16x16: меандровый фриз сверху (ряды 0..3),
# ниже — золотой контур ступенчатой арки с тёмной нишей внутри (углублённый проём).
# Блок непрозрачный, поэтому никаких сквозных граней и просвечивающих пещер.
img, d, spec, sd = base(); grain(img, 1)
def arch_gold(x, y):
    gold_px(d, sd, x, y)
# фриз по верху
for x in range(S):
    arch_gold(x, 0); arch_gold(x, 3)
for cx in (2, 6, 10, 14):
    for dx, dy in ((0, 0), (1, 0), (0, 1)):
        arch_gold(cx + dx, 1 + dy)
# ниша проёма: мягкий тёмный мрамор внутри контура
for y in range(6, 14):
    for x in range(6, 10):
        img.putpixel((x, y), noise(GLASS, 2))
# контур проёма: ступенчатая арка (1px золота)
for y in range(6, 15):
    arch_gold(5, y); arch_gold(10, y)     # вертикали
for x in range(5, 11):
    arch_gold(x, 14)                      # низ проёма
for x in range(6, 10):
    arch_gold(x, 6)                       # плечи
for x in range(7, 9):
    arch_gold(x, 5)                       # верх арки
save("marble_arch_front", img, spec)

# --- колонны / балки: пояса в канонических рядах, периодичность 16px => стопка бесшовна ---
img, d, spec, sd = base(); flutes(img, False); add_bands(d, sd); save("marble_pillar", img, spec)
img, d, spec, sd = base(); flutes(img, True);  add_bands(d, sd); save("marble_beam", img, spec)

img, d, spec, sd = base(); flutes(img, False); add_bands(d, sd); save("marble_column", img, spec)
img, d, spec, sd = base(); flutes(img, False); add_bands(d, sd, with_meander=False); save("marble_column_small", img, spec)
img, d, spec, sd = base(); flutes(img, False); add_bands(d, sd); save("marble_column_base", img, spec)
img, d, spec, sd = base(); flutes(img, False); add_bands(d, sd); save("marble_column_mid", img, spec)
img, d, spec, sd = base(); flutes(img, False); add_bands(d, sd); save("marble_column_capital", img, spec)
img, d, spec, sd = base(); flutes(img, False); add_bands(d, sd); save("marble_pedestal", img, spec)

# --- ворота (куб с орнаментом): меандровая рама + двойной ромб, симметричный ---
img, d, spec, sd = base(); grain(img, 1)
gold_rect(d, sd, [0, 0, 15, 15])
for cx, mx in ((1, False), (14, True)):
    for cy, my in ((1, False), (14, True)):
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            x = cx + (dx if not mx else -dx)
            y = cy + (dy if not my else -dy)
            gold_px(d, sd, x, y)
d.rectangle([3, 3, 12, 12], outline=SNOW_D)
# внешний ромб с осью x=7, y=8
for a, b in (((7, 3), (12, 8)), ((12, 8), (7, 13)), ((7, 13), (2, 8)), ((2, 8), (7, 3))):
    gold_line(d, sd, a, b)
# внутренний ромб
for a, b in (((7, 5), (10, 8)), ((10, 8), (7, 11)), ((7, 11), (4, 8)), ((4, 8), (7, 5))):
    gold_line(d, sd, a, b)
diamond(d, sd, 7, 8, 1, fill=GOLD_HI)
diamond(d, sd, 2, 8, 0); diamond(d, sd, 12, 8, 0); diamond(d, sd, 7, 3, 0); diamond(d, sd, 7, 13, 0)
save("marble_gate", img, spec)

# --- side stairs: яркий диагональный орнамент пола (золотые ромбы под 45°).
# Тонкие диагонали 1-2px по ячейкам 4x4, рамка по краю — плитка видна издалека.
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        on_border = x == 0 or x == S - 1 or y == 0 or y == S - 1
        c1 = (x + y) % 4
        c2 = (x - y) % 4
        band = c1 == 0 or c2 == 0 or (c1 == 1 and c2 == 1) or (c1 == 2 and c2 == 3)
        if on_border or band:
            img.putpixel((x, y), GOLD)
            spec.putpixel((x, y), GOLD_SPEC)
        else:
            cell = ((x + y) // 4 + (x - y) // 4) % 2
            img.putpixel((x, y), noise(SNOW_D if cell else SNOW, 2))
# блики на пересечениях диагоналей
for y in range(2, S - 2):
    for x in range(2, S - 2):
        if (x + y) % 4 == 0 and (x - y) % 4 == 0:
            img.putpixel((x, y), GOLD_HI)
            spec.putpixel((x, y), GOLD_SPEC)
save("marble_side_stairs", img, spec)

# --- фонарь: тонкая рамка с меандровыми уголками, узор на стекле, светящееся ядро ---
img, d, spec, sd = base(); grain(img, 1)
gold_rect(d, sd, [0, 0, 15, 15])
# уголки-крючки
for cx, mx in ((1, False), (14, True)):
    for cy, my in ((1, False), (14, True)):
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            x = cx + (dx if not mx else -dx)
            y = cy + (dy if not my else -dy)
            gold_px(d, sd, x, y)
# стекло
d.rectangle([2, 2, 13, 13], outline=GLASS)
# узор: ромбики на стекле по углам + золотые перекладины креста
diamond(d, sd, 3, 3, 1); diamond(d, sd, 12, 3, 1); diamond(d, sd, 3, 12, 1); diamond(d, sd, 12, 12, 1)
gold_line(d, sd, (3, 7), (12, 7)); gold_line(d, sd, (7, 3), (7, 12))
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

# --- дверь: стандартная ванильная 2-блочная (16x16 top/bottom + иконка в инвентарь) ---
W = H = 16
def door_base():
    img = Image.new("RGB", (W, H), SNOW)
    for y in range(H):
        for x in range(W):
            img.putpixel((x, y), noise(SNOW, 1))
    spec = Image.new("RGBA", (W, H), MARBLE_SPEC)
    return img, ImageDraw.Draw(img), spec, ImageDraw.Draw(spec)

def door_gold_line(d, sd, x0, y0, x1, y1):
    d.line([x0, y0, x1, y1], fill=GOLD)
    sd.line([x0, y0, x1, y1], GOLD_SPEC)

def door_diamond(d, sd, cx, cy, r):
    for a, b in (((cx, cy - r), (cx + r, cy)), ((cx + r, cy), (cx, cy + r)),
                 ((cx, cy + r), (cx - r, cy)), ((cx - r, cy), (cx, cy - r))):
        door_gold_line(d, sd, a[0], a[1], b[0], b[1])

def door_medallion(d, sd, cx, cy, r=3):
    door_diamond(d, sd, cx, cy, r)
    door_diamond(d, sd, cx, cy, max(1, r - 2))
    d.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=GOLD_HI)
    sd.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], GOLD_SPEC)

def door_meander(d, sd, x0, y0):
    rows = ["11111111", "10000001", "10111111"]
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch == "1":
                d.point((x0 + dx, y0 + dy), fill=GOLD)
                sd.point((x0 + dx, y0 + dy), GOLD_SPEC)

def door_band(d, sd, y):
    """Меандр 8x3 + нижняя линия — целиком внутри половины полотна."""
    door_meander(d, sd, 0, y)
    door_meander(d, sd, 8, y)
    door_gold_line(d, sd, 2, y + 3, W - 3, y + 3)

def door_frame(d, sd):
    """Золотая рама 1px по периметру."""
    door_gold_line(d, sd, 1, 1, W - 2, 1)
    door_gold_line(d, sd, 1, H - 2, W - 2, H - 2)
    door_gold_line(d, sd, 1, 1, 1, H - 2)
    door_gold_line(d, sd, W - 2, 1, W - 2, H - 2)
    d.rectangle([4, 4, W - 5, H - 5], outline=SNOW_D)

def door_save(name, img, spec):
    img.save(f"{OUT}/{name}.png")
    spec.save(f"{OUT}/{name}_s.png")

# верхняя половина: медальон + пояс внизу
img, d, spec, sd = door_base(); door_frame(d, sd)
door_medallion(d, sd, 7, 5, r=3)
door_band(d, sd, 12)
door_save("marble_door_top", img, spec)

# нижняя половина: медальон + золотая ручка + пояс
img, d, spec, sd = door_base(); door_frame(d, sd)
door_medallion(d, sd, 7, 4, r=3)
d.ellipse([10, 6, 14, 10], outline=GOLD, width=1)
sd.ellipse([10, 6, 14, 10], outline=GOLD_SPEC, width=1)
d.ellipse([11, 7, 13, 9], outline=GOLD_HI, width=1)
sd.ellipse([11, 7, 13, 9], outline=GOLD_SPEC, width=1)
d.rectangle([11, 8, 13, 9], fill=GOLD_D)
sd.rectangle([11, 8, 13, 9], GOLD_SPEC)
door_band(d, sd, 12)
door_save("marble_door_bottom", img, spec)

# иконка двери в инвентаре (item/generated): рамка + меандр + ручка
img, d, spec, sd = door_base(); door_frame(d, sd)
door_meander(d, sd, 0, 11); door_meander(d, sd, 8, 11)
door_gold_line(d, sd, 2, 14, W - 3, 14)
d.ellipse([11, 5, 14, 8], outline=GOLD, width=1)
sd.ellipse([11, 5, 14, 8], outline=GOLD_SPEC, width=1)
os.makedirs("src/main/resources/assets/teyvat/textures/item", exist_ok=True)
print("textures v6 done")
