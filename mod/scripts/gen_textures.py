#!/usr/bin/env python3
"""Генерация мраморных текстур Teyvat v7: белоснежный мрамор + бледное палевое золото (Celestia).

ЕДИНАЯ СИСТЕМА ОКАНТОВОК:
  - все декорированные боковые текстуры несут тонкий меандр в ОДНИХ рядах:
    верх 3..5, низ 11..13. Блоки сочетаются вперемешку и в стопке бесшовно,
    окантовка всегда на одном уровне.
  - золото бледное (палевое), металлическое: spec-карты (*_s.png) в labPBR:
    R = smoothness, G = metalness, B = F0, A = эмиссия.
  - обычный мрамор матовый; полированный — глянцевый (R=0.84 в spec),
    поэтому в шейдере ловит блики от солнца, фонарей и факелов.
"""
import math, os, random
from PIL import Image, ImageDraw

S = 16
OUT = "src/main/resources/assets/teyvat/textures/block"
random.seed(20260805)

# Палитра «Целестия» — белоснежный мрамор, без серых пятен
SNOW   = (252, 252, 250)
SNOW_D = (247, 247, 243)   # едва заметная тень
GLASS  = (237, 237, 231)   # тень ниши арки
MORTAR = (243, 243, 239)   # швы кирпичей/плитки
GOLD   = (228, 210, 160)   # бледное палевое золото
GOLD_HI= (250, 242, 214)   # блик на золоте
GOLD_D = (200, 180, 126)   # тень золота
LAMP_C = (255, 248, 226)   # тёплый свет фонаря

# labPBR specular-карты (RGBA)
GOLD_SPEC   = (250, 255, 244, 255)   # почти зеркальный металл: R=0.98, сильные блики
MARBLE_SPEC = (60, 0, 40, 255)       # матовый мрамор
POLISH_SPEC = (215, 0, 40, 255)      # глянцевый полированный (без металла)
LAMP_SPEC   = (60, 0, 40, 235)       # светящееся ядро фонаря

# Единые ряды окантовок
BAND_TOP = 3    # меандр в рядах 3..5
BAND_BOT = 11   # меандр в рядах 11..13

def noise(px, amt=2):
    r, g, b = px
    d = random.randint(-amt, amt)
    return (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)))

def grain(img, amt=1):
    for _ in range(S * S):
        x, y = random.randrange(S), random.randrange(S)
        img.putpixel((x, y), noise(img.getpixel((x, y)), amt))

def base(spec_color=MARBLE_SPEC):
    img = Image.new("RGB", (S, S), SNOW)
    spec = Image.new("RGBA", (S, S), spec_color)
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
    """Горизонтальный пояс-меандр 16x3 (ряды y0..y0+2)."""
    meander_tile(d, sd, 0, y0)
    meander_tile(d, sd, 8, y0)

def add_bands(d, sd, small=False):
    if small:
        gold_line(d, sd, (0, BAND_TOP), (S - 1, BAND_TOP))
        gold_line(d, sd, (0, BAND_TOP + 2), (S - 1, BAND_TOP + 2))
        gold_line(d, sd, (0, BAND_BOT), (S - 1, BAND_BOT))
        gold_line(d, sd, (0, BAND_BOT + 2), (S - 1, BAND_BOT + 2))
    else:
        meander_band(d, sd, BAND_TOP)
        meander_band(d, sd, BAND_BOT)

def flutes(img, horizontal=False):
    """Мягкие каннелюры: 6 желобков с плавным градиентом, без грязных границ."""
    n = 6
    for y in range(S):
        for x in range(S):
            p = (y if horizontal else x) + 0.5
            shade = 0.05 * (1.0 - math.cos((p / S) * n * math.pi * 2.0)) / 2.0
            c = (int(SNOW[0] * (1 - shade)), int(SNOW[1] * (1 - shade)), int(SNOW[2] * (1 - shade)))
            img.putpixel((x, y), noise(c, 1))

# ---------- базовые блоки ----------
img, d, spec, sd = base(); grain(img, 1); save("marble", img, spec)

# полированный: гладкий, с мягким вертикальным бликом, глянцевый spec
img, d, spec, sd = base(POLISH_SPEC)
for y in range(S):
    for x in range(S):
        sheen = 0.035 * ((x - 7.5) / 8.0) ** 2
        c = (int(SNOW[0] * (1 - sheen)), int(SNOW[1] * (1 - sheen)), int(SNOW[2] * (1 - sheen)))
        img.putpixel((x, y), c)
save("marble_polished", img, spec)

# кирпичи: 2-рядная кладка 8x4, очень светлые швы
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        row = y // 4
        brick_x = (x + (4 if row % 2 else 0)) % 8
        if y % 4 == 3 or brick_x == 7:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(SNOW, 1))
save("marble_bricks", img, spec)

# плитка: сетка 4x4
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        if x % 4 == 3 or y % 4 == 3:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(SNOW, 1))
save("marble_tiles", img, spec)

# gold trimmed: мрамор с поясами-меандрами в канонических рядах
img, d, spec, sd = base()
add_bands(d, sd)
save("marble_gold", img, spec)

# chiseled: тонкая рамка + крючки-меандры по углам + ромб-медальон
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
for cx, mx in ((1, False), (14, True)):
    for cy, my in ((1, False), (14, True)):
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            gold_px(d, sd, cx + (dx if not mx else -dx), cy + (dy if not my else -dy))
d.rectangle([4, 4, 11, 11], outline=SNOW_D)
diamond_outline(d, sd, 7, 7, 3)
diamond(d, sd, 7, 7, 1, fill=GOLD_HI)
save("marble_chiseled", img, spec)

# ---------- арка: цельный блок-врата. Фасад: меандровый фриз сверху,
# ниже — золотой контур ступенчатой арки с тёмной нишей внутри ----------
img, d, spec, sd = base()
def arch_gold(x, y):
    gold_px(d, sd, x, y)
# фриз по верху: линия + меандр
for x in range(S):
    arch_gold(x, 0)
meander_band(d, sd, 1)
# ниша проёма: мягкий тёмный мрамор внутри контура
for y in range(6, 14):
    for x in range(6, 10):
        img.putpixel((x, y), noise(GLASS, 1))
# контур проёма: ступенчатая арка (1px золота)
for y in range(5, 15):
    arch_gold(5, y); arch_gold(10, y)     # вертикали
for x in range(5, 11):
    arch_gold(x, 14)                      # низ проёма
for x in range(6, 10):
    arch_gold(x, 5)                       # плечи
for x in range(7, 9):
    arch_gold(x, 4)                       # верх арки
save("marble_arch_front", img, spec)

# ---------- колонны / балки: каннелюры + пояса в канонических рядах.
# Текстуры периодичны по 16px => стопка бесшовна, окантовки на одном уровне ----------
img, d, spec, sd = base(); flutes(img); add_bands(d, sd); save("marble_pillar", img, spec)
img, d, spec, sd = base(); flutes(img, True); add_bands(d, sd); save("marble_beam", img, spec)

for name in ("marble_column", "marble_column_base", "marble_column_mid",
             "marble_column_capital", "marble_pedestal"):
    img, d, spec, sd = base(); flutes(img); add_bands(d, sd); save(name, img, spec)

# маленькая колонна: тонкие линии вместо меандра
img, d, spec, sd = base(); flutes(img); add_bands(d, sd, small=True); save("marble_column_small", img, spec)

# ---------- ворота: рамка + крючки по углам + двойной ромб ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
for cx, mx in ((1, False), (14, True)):
    for cy, my in ((1, False), (14, True)):
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            gold_px(d, sd, cx + (dx if not mx else -dx), cy + (dy if not my else -dy))
d.rectangle([4, 4, 11, 11], outline=SNOW_D)
diamond_outline(d, sd, 7, 8, 4)
diamond_outline(d, sd, 7, 8, 2)
diamond(d, sd, 7, 8, 1, fill=GOLD_HI)
for cx, cy in ((3, 3), (11, 3), (3, 13), (11, 13)):
    diamond(d, sd, cx, cy, 1, fill=GOLD)
save("marble_gate", img, spec)

# ---------- side stairs: диагональный орнамент пола (ромбы под 45°) ----------
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        on_border = x == 0 or x == S - 1 or y == 0 or y == S - 1
        c1 = (x + y) % 4
        c2 = (x - y) % 4
        cross = c1 == 0 and c2 == 0
        diag = c1 == 0 or c2 == 0
        if on_border or diag:
            px = GOLD_HI if cross else GOLD
            img.putpixel((x, y), px)
            spec.putpixel((x, y), GOLD_SPEC)
        else:
            cell = ((x + y) // 4 + (x - y) // 4) % 2
            img.putpixel((x, y), noise(SNOW_D if cell else SNOW, 1))
save("marble_side_stairs", img, spec)

# ---------- фонарь: рамка с меандровыми уголками, стекло, светящееся ядро ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
for cx, mx in ((1, False), (14, True)):
    for cy, my in ((1, False), (14, True)):
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            gold_px(d, sd, cx + (dx if not mx else -dx), cy + (dy if not my else -dy))
d.rectangle([2, 2, 13, 13], outline=GLASS)
diamond(d, sd, 3, 3, 1); diamond(d, sd, 12, 3, 1); diamond(d, sd, 3, 12, 1); diamond(d, sd, 12, 12, 1)
gold_line(d, sd, (3, 7), (12, 7)); gold_line(d, sd, (7, 3), (7, 12))
cx = cy = 7.5
r_core = 3.2
for y in range(S):
    for x in range(S):
        dist = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if dist <= 1.2:
            img.putpixel((x, y), (255, 252, 240))
        elif dist <= r_core:
            t = 1.0 - (dist - 1.2) / (r_core - 1.2)
            c = (int(LAMP_C[0] + (255 - LAMP_C[0]) * t), int(LAMP_C[1] + (255 - LAMP_C[1]) * t),
                 int(LAMP_C[2] + (255 - LAMP_C[2]) * t))
            img.putpixel((x, y), c)
diamond_outline(d, sd, 7, 7, 2)
# spec: ядро светится, окантовка — металл
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

# ---------- дверь: стандартная ванильная 2-блочная (16x16 top/bottom + иконка) ----------
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

# иконка двери в инвентаре: рамка + меандр + ручка
img, d, spec, sd = door_base(); door_frame(d, sd)
door_meander(d, sd, 0, 11); door_meander(d, sd, 8, 11)
door_gold_line(d, sd, 2, 14, W - 3, 14)
d.ellipse([11, 5, 14, 8], outline=GOLD, width=1)
sd.ellipse([11, 5, 14, 8], outline=GOLD_SPEC, width=1)

os.makedirs("src/main/resources/assets/teyvat/textures/item", exist_ok=True)
img.save("src/main/resources/assets/teyvat/textures/item/marble_door.png")
print("textures v7 done")
