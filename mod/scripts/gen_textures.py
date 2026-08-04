#!/usr/bin/env python3
"""Генерация мраморных текстур Teyvat v8: античная резьба, прожилки золота, орнамент на всех гранях.

Стиль v8 («Антика»):
  - тёплая слоновая кость вместо белоснежного мрамора, античное золото;
  - все блоки РЕЗНЫЕ: утопленные панели, фризы-меандры, каннелюры, барельефные линии;
  - по мрамору идут тонкие прожилки золота (gold veins) — металл в spec-картах;
  - ромбы убраны: медальоны теперь античные розетки (концентрические окружности);
  - декорированные блоки несут орнамент на ВСЕХ гранях: для top/bottom есть
    отдельные текстуры (marble_gold_top / marble_top), а не пустой мрамор;
  - фонарь светится сильно: ядро и стекло — полная эмиссия в spec-канале A.

ЕДИНАЯ СИСТЕМА ОКАНТОВОК (как в v7): меандр всегда в рядах 3..5 и 11..13,
поэтому блоки сочетаются в стопке бесшовно, окантовка на одном уровне.

labPBR spec-карты (*_s.png): R = smoothness, G = metalness, B = F0, A = эмиссия.
"""
import math, os, random
from PIL import Image, ImageDraw

S = 16
OUT = "src/main/resources/assets/teyvat/textures/block"
random.seed(20260805)

# Палитра «Антика» — тёплая слоновая кость + античное золото
IVORY  = (250, 248, 242)   # основной мрамор
IVORY_D= (235, 232, 222)   # тень резьбы/утопленных панелей
GLASS  = (222, 218, 207)   # тень ниши арки
MORTAR = (240, 237, 228)   # швы кладки/плитки
GOLD   = (221, 195, 139)   # античное золото
GOLD_HI= (247, 233, 192)   # блик на золоте
GOLD_D = (172, 146, 92)    # тень золота
LAMP_C = (255, 246, 220)   # тёплый свет фонаря

# labPBR specular-карты (RGBA)
GOLD_SPEC   = (250, 255, 244, 255)   # почти зеркальный металл: R=0.98, сильные блики
MARBLE_SPEC = (60, 0, 40, 255)       # матовый мрамор
POLISH_SPEC = (215, 0, 40, 255)      # глянцевый полированный (без металла)
LAMP_SPEC   = (60, 0, 40, 254)       # фонарь: A=254 => максимальная эмиссия (см. GetCustomEmission)

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
    img = Image.new("RGB", (S, S), IVORY)
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

def panel_shadow(d, box):
    """Тень по нижней и правой грани утопленной панели — эффект резьбы."""
    x0, y0, x1, y1 = box
    d.line([(x0, y1), (x1, y1)], fill=IVORY_D)
    d.line([(x1, y0), (x1, y1)], fill=IVORY_D)

def rosette(d, sd, cx, cy, r=2, fill=GOLD_HI):
    """Античная розетка: концентрические окружности вместо ромбов."""
    for radius in (r, max(1, r - 1)):
        d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], outline=GOLD, width=1)
        sd.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], GOLD_SPEC, width=1)
    d.point((cx, cy), fill=fill)
    sd.point((cx, cy), GOLD_SPEC)

def gold_veins(img, spec, count=3, gold=True):
    """Тонкие волнистые прожилки золота по мрамору (1px, случайный маршрут)."""
    d = ImageDraw.Draw(img)
    sd = ImageDraw.Draw(spec)
    for _ in range(count):
        x, y = random.randrange(2, S - 2), random.randrange(2, S - 2)
        length = random.randrange(S, S * 3)
        for _ in range(length):
            gold_px(d, sd, x, y)
            step = random.choice((-1, -1, 0, 1, 1))
            if random.random() < 0.6:
                x += step
            else:
                y += step
            x = max(1, min(S - 2, x))
            y = max(1, min(S - 2, y))

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

def meander_band_v(d, sd, x0):
    """Вертикальный пояс-меандр 3x16 (столбцы x0..x0+2) — для рамок top/bottom."""
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

def flutes(img, horizontal=False):
    """Мягкие каннелюры: 6 желобков с плавным градиентом, без грязных границ."""
    n = 6
    for y in range(S):
        for x in range(S):
            p = (y if horizontal else x) + 0.5
            shade = 0.05 * (1.0 - math.cos((p / S) * n * math.pi * 2.0)) / 2.0
            c = (int(IVORY[0] * (1 - shade)), int(IVORY[1] * (1 - shade)), int(IVORY[2] * (1 - shade)))
            img.putpixel((x, y), noise(c, 1))

# ---------- базовые блоки: мрамор с прожилками золота ----------
img, d, spec, sd = base(); grain(img, 1); gold_veins(img, spec, 3); save("marble", img, spec)

# полированный: гладкий, мягкий вертикальный блик, глянцевый spec + прожилки
img, d, spec, sd = base(POLISH_SPEC)
for y in range(S):
    for x in range(S):
        sheen = 0.035 * ((x - 7.5) / 8.0) ** 2
        c = (int(IVORY[0] * (1 - sheen)), int(IVORY[1] * (1 - sheen)), int(IVORY[2] * (1 - sheen)))
        img.putpixel((x, y), c)
gold_veins(img, spec, 2)
save("marble_polished", img, spec)

# кирпичи: резная 2-рядная кладка 8x4 со швами и прожилками
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        row = y // 4
        brick_x = (x + (4 if row % 2 else 0)) % 8
        if y % 4 == 3 or brick_x == 7:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(IVORY, 1))
# лёгкая фаска по нижней грани каждого кирпича (резьба)
for y in range(0, S, 4):
    for x in range(S):
        row = y // 4
        brick_x = (x + (4 if row % 2 else 0)) % 8
        if not (brick_x == 7 or y % 4 == 3):
            img.putpixel((x, y + 3), IVORY_D)
gold_veins(img, spec, 3)
save("marble_bricks", img, spec)

# плитка: резная сетка 4x4 + прожилки
img, d, spec, sd = base()
for y in range(S):
    for x in range(S):
        if x % 4 == 3 or y % 4 == 3:
            img.putpixel((x, y), MORTAR)
        else:
            img.putpixel((x, y), noise(IVORY, 1))
for y in range(0, S, 4):
    for x in range(0, S, 4):
        if x < S - 1 and y < S - 1:
            img.putpixel((x + 3, y + 3), IVORY_D)   # фаска плитки
gold_veins(img, spec, 3)
save("marble_tiles", img, spec)

# ---------- top-текстуры: орнамент на верхней/нижней грани ----------
# marble_top: тонкая золотая рамка + розетка (для стволов колонн и пр.)
img, d, spec, sd = base()
gold_rect(d, sd, [2, 2, 13, 13])
gold_rect(d, sd, [3, 3, 12, 12])
rosette(d, sd, 7.5, 7.5, r=3)
gold_veins(img, spec, 2)
save("marble_top", img, spec)

# marble_gold_top: рама + меандровая рамка по периметру + резная панель с розеткой.
# Используется на top/bottom у gold_trimmed_marble, pillar, beam, arch, gate.
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])            # внешняя рама
meander_band(d, sd, BAND_TOP)               # верхний фриз
meander_band(d, sd, BAND_BOT)               # нижний фриз
meander_band_v(d, sd, 2)                    # левый вертикальный фриз
meander_band_v(d, sd, 11)                   # правый вертикальный фриз
gold_rect(d, sd, [5, 5, 10, 10])            # внутренняя панель
panel_shadow(d, [5, 5, 10, 10])
rosette(d, sd, 7.5, 7.5, r=2)
gold_veins(img, spec, 3)
save("marble_gold_top", img, spec)

# ---------- gold trimmed: стороны с поясами-меандрами + резная панель ----------
img, d, spec, sd = base()
add_bands(d, sd)
gold_rect(d, sd, [1, 6, 14, 10])            # утопленная панель между поясами
panel_shadow(d, [1, 6, 14, 10])
gold_veins(img, spec, 3)
save("marble_gold", img, spec)

# chiseled: рамка + фризы + резная панель с розеткой (ромб убран)
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
meander_band(d, sd, BAND_TOP)
meander_band(d, sd, BAND_BOT)
gold_rect(d, sd, [4, 6, 11, 10])
panel_shadow(d, [4, 6, 11, 10])
rosette(d, sd, 7.5, 7.5, r=3)
gold_veins(img, spec, 3)
save("marble_chiseled", img, spec)

# ---------- арка: резной фасад-врата. Фриз сверху, ступенчатый контур, ниша ----------
img, d, spec, sd = base()
def arch_gold(x, y):
    gold_px(d, sd, x, y)
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
# резьба ниши: тень по внутренней кромке + прожилки золота по фасаду
for y in range(7, 13):
    img.putpixel((6, y), IVORY_D)
    img.putpixel((9, y), IVORY_D)
gold_veins(img, spec, 4)
save("marble_arch_front", img, spec)

# ---------- колонны / балки: каннелюры + пояса + прожилки ----------
img, d, spec, sd = base(); flutes(img); add_bands(d, sd); gold_veins(img, spec, 2); save("marble_pillar", img, spec)
img, d, spec, sd = base(); flutes(img, True); add_bands(d, sd); gold_veins(img, spec, 2); save("marble_beam", img, spec)

for name in ("marble_column", "marble_column_base", "marble_column_mid",
             "marble_column_capital", "marble_pedestal"):
    img, d, spec, sd = base(); flutes(img); add_bands(d, sd); gold_veins(img, spec, 2); save(name, img, spec)

# маленькая колонна: тонкие линии вместо меандра
img, d, spec, sd = base(); flutes(img); add_bands(d, sd, small=True); gold_veins(img, spec, 2)
save("marble_column_small", img, spec)

# ---------- ворота: фриз сверху/снизу + две резные створки с розетками (ромбы убраны) ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
meander_band(d, sd, BAND_TOP)
meander_band(d, sd, BAND_BOT)
gold_line(d, sd, (7, 6), (8, 6)); gold_line(d, sd, (7, 10), (8, 10))
# две створки
for x0 in (1, 8):
    gold_rect(d, sd, [x0, 6, x0 + 6, 10])
    panel_shadow(d, [x0, 6, x0 + 6, 10])
    rosette(d, sd, x0 + 3.5, 8.5, r=2)
gold_veins(img, spec, 3)
save("marble_gate", img, spec)

# ---------- side stairs (горизонтальные ступени): пол 2x2 из 4 столбиков.
# Текстура = мозаика: рама по периметру, крест по центру, резные панели-квадранты.
# Каждый квадрант 8x8 ложится на верх столбика (UV-срезы в модели) ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])                    # рама по периметру блока
gold_line(d, sd, (7, 1), (7, 14)); gold_line(d, sd, (8, 1), (8, 14))
gold_line(d, sd, (1, 7), (14, 7)); gold_line(d, sd, (1, 8), (14, 8))
for qx in (0, 8):
    for qy in (0, 8):
        gold_rect(d, sd, [qx + 1, qy + 1, qx + 6, qy + 6])   # рамка квадранта
        panel_shadow(d, [qx + 1, qy + 1, qx + 6, qy + 6])
        rosette(d, sd, qx + 3.5, qy + 3.5, r=2)
gold_veins(img, spec, 4)
save("marble_side_stairs", img, spec)

# ---------- фонарь: рама, резные уголки-розетки, стекло, яркое ядро ----------
img, d, spec, sd = base()
gold_rect(d, sd, [0, 0, 15, 15])
for cx, cy in ((2, 2), (13, 2), (2, 13), (13, 13)):
    rosette(d, sd, cx, cy, r=1)
gold_line(d, sd, (3, 7), (12, 7)); gold_line(d, sd, (7, 3), (7, 12))
cx = cy = 7.5
r_core = 4.2
for y in range(S):
    for x in range(S):
        dist = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if dist <= 1.4:
            img.putpixel((x, y), (255, 253, 242))
        elif dist <= r_core:
            t = 1.0 - (dist - 1.4) / (r_core - 1.4)
            c = (int(LAMP_C[0] + (255 - LAMP_C[0]) * t), int(LAMP_C[1] + (255 - LAMP_C[1]) * t),
                 int(LAMP_C[2] + (255 - LAMP_C[2]) * t))
            img.putpixel((x, y), c)
rosette(d, sd, 7, 7, r=1)   # маленькая розетка-навершие поверх ядра
# spec: ядро и стекло светятся (A=254 — максимум эмиссии), золото — металл
m = Image.new("L", (S, S), 255)
for y in range(S):
    for x in range(S):
        dist = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        if dist <= r_core + 0.8:
            m.putpixel((x, y), LAMP_SPEC[3])
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

# ---------- дверь: резные створки (меандр + розетки, ромбы убраны) ----------
W = H = 16
def door_base():
    img = Image.new("RGB", (W, H), IVORY)
    for y in range(H):
        for x in range(W):
            img.putpixel((x, y), noise(IVORY, 1))
    spec = Image.new("RGBA", (W, H), MARBLE_SPEC)
    return img, ImageDraw.Draw(img), spec, ImageDraw.Draw(spec)

def door_gold_line(d, sd, x0, y0, x1, y1):
    d.line([x0, y0, x1, y1], fill=GOLD)
    sd.line([x0, y0, x1, y1], GOLD_SPEC)

def door_rosette(d, sd, cx, cy, r=3):
    for radius in (r, max(1, r - 1)):
        d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], outline=GOLD, width=1)
        sd.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], GOLD_SPEC, width=1)
    d.point((cx, cy), fill=GOLD_HI)
    sd.point((cx, cy), GOLD_SPEC)

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
    d.rectangle([4, 4, W - 5, H - 5], outline=IVORY_D)
    d.line([(4, H - 6), (W - 5, H - 6)], fill=IVORY_D)

def door_veins(img, spec):
    gold_veins(img, spec, 2)

def door_save(name, img, spec):
    img.save(f"{OUT}/{name}.png")
    spec.save(f"{OUT}/{name}_s.png")

# верхняя половина: меандр по низу + резная панель с розеткой
img, d, spec, sd = door_base(); door_frame(d, sd)
door_rosette(d, sd, 7.5, 5.5, r=3)
door_band(d, sd, 12)
door_veins(img, spec)
door_save("marble_door_top", img, spec)

# нижняя половина: меандр по низу + розетка + золотая ручка
img, d, spec, sd = door_base(); door_frame(d, sd)
door_rosette(d, sd, 7.5, 4.5, r=3)
d.ellipse([10, 6, 14, 10], outline=GOLD, width=1)
sd.ellipse([10, 6, 14, 10], outline=GOLD_SPEC, width=1)
d.ellipse([11, 7, 13, 9], outline=GOLD_HI, width=1)
sd.ellipse([11, 7, 13, 9], outline=GOLD_SPEC, width=1)
d.rectangle([11, 8, 13, 9], fill=GOLD_D)
sd.rectangle([11, 8, 13, 9], GOLD_SPEC)
door_band(d, sd, 12)
door_veins(img, spec)
door_save("marble_door_bottom", img, spec)

# иконка двери в инвентаре: рамка + меандр + розетка + ручка
img, d, spec, sd = door_base(); door_frame(d, sd)
door_rosette(d, sd, 7.5, 5.5, r=2)
door_meander(d, sd, 0, 11); door_meander(d, sd, 8, 11)
door_gold_line(d, sd, 2, 14, W - 3, 14)
d.ellipse([11, 5, 14, 8], outline=GOLD, width=1)
sd.ellipse([11, 5, 14, 8], outline=GOLD_SPEC, width=1)

os.makedirs("src/main/resources/assets/teyvat/textures/item", exist_ok=True)
img.save("src/main/resources/assets/teyvat/textures/item/marble_door.png")
print("textures v8 done")
