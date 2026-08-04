#!/usr/bin/env python3
"""Генерация мраморных текстур Teyvat (оригинальный арт, стиль «Целестия»)."""
import math, random
from PIL import Image, ImageDraw

S = 16
OUT = "src/main/resources/assets/teyvat/textures/block"
random.seed(20260804)

WHITE = (242, 239, 233)
VEIN = (221, 214, 203)
VEIN_GOLD = (231, 222, 200)
SHADE = (204, 197, 186)
MORTAR = (213, 206, 194)
GOLD = (212, 175, 55)
GOLD_HI = (236, 198, 92)
LAMP_CORE = (222, 242, 255)
LAMP_EDGE = (180, 210, 235)

def noise(px, amt=6):
    r, g, b = px
    d = random.randint(-amt, amt)
    return (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)))

def base_img():
    img = Image.new("RGB", (S, S), WHITE)
    return img, ImageDraw.Draw(img)

def marble_texture(name, veins=3):
    img, d = base_img()
    for _ in range(S * S):
        x, y = random.randrange(S), random.randrange(S)
        img.putpixel((x, y), noise(img.getpixel((x, y)), 4))
    for i in range(veins):
        x, y = random.uniform(0, S), random.uniform(0, S)
        ang = random.uniform(0, math.tau)
        col = random.choice([VEIN, VEIN_GOLD, VEIN])
        for _ in range(random.randint(6, 10)):
            x += math.cos(ang) * 1.6
            y += math.sin(ang) * 1.6
            r = random.uniform(0.7, 1.6)
            d.ellipse([x - r, y - r, x + r, y + r], fill=col)
            if random.random() < 0.3:
                ang += random.uniform(-0.9, 0.9)
    img.save(f"{OUT}/{name}.png")
    return img

def polished_texture(name):
    img, d = base_img()
    for _ in range(S * S):
        x, y = random.randrange(S), random.randrange(S)
        img.putpixel((x, y), noise(img.getpixel((x, y)), 3))
    img.save(f"{OUT}/{name}.png")

def bricks_texture(name):
    img, d = base_img()
    for y in range(S):
        for x in range(S):
            row = y // 4
            off = (row % 2) * 4
            brick = (x + off) % 8 < 8
            if y % 4 == 0 or (x + off) % 8 == 0:
                img.putpixel((x, y), MORTAR)
            else:
                img.putpixel((x, y), noise(WHITE, 5))
    # subtle per-brick shade
    for row in range(4):
        off = (row % 2) * 4
        for bx in range(2):
            x0, y0 = bx * 8 - off, row * 4
            for y in range(1, 4):
                for x in range(1, 8):
                    px = img.getpixel((x0 + x, y0 + y))
                    if random.random() < 0.35:
                        img.putpixel((x0 + x, y0 + y), noise(px, 3))
    img.save(f"{OUT}/{name}.png")

def tiles_texture(name):
    img, d = base_img()
    for y in range(S):
        for x in range(S):
            if x % 4 == 0 or y % 4 == 0:
                img.putpixel((x, y), MORTAR)
            else:
                img.putpixel((x, y), noise(WHITE, 6))
    for ty in range(4):
        for tx in range(4):
            if random.random() < 0.5:
                for y in range(1, 4):
                    for x in range(1, 4):
                        px = img.getpixel((tx * 4 + x, ty * 4 + y))
                        img.putpixel((tx * 4 + x, ty * 4 + y), noise(px, 3))
    img.save(f"{OUT}/{name}.png")

def chiseled_texture(name):
    img, d = base_img()
    d.rectangle([0, 0, 15, 15], outline=(160, 152, 140))
    d.rectangle([1, 1, 14, 14], outline=WHITE)
    d.rectangle([3, 3, 12, 12], outline=SHADE)
    d.rectangle([4, 4, 11, 11], outline=(228, 224, 216))
    d.ellipse([6, 6, 9, 9], fill=(212, 205, 192), outline=GOLD)
    img.save(f"{OUT}/{name}.png")

def gold_trim_texture(name):
    img, d = base_img()
    for y in range(S):
        for x in range(S):
            img.putpixel((x, y), noise(WHITE, 4))
    d.rectangle([0, 0, 15, 2], fill=GOLD)
    d.rectangle([0, 13, 15, 15], fill=GOLD)
    d.rectangle([0, 2, 15, 2], fill=GOLD_HI)
    d.rectangle([0, 13, 15, 13], fill=(150, 118, 30))
    img.save(f"{OUT}/{name}.png")

def flutes(img, horizontal=False):
    d = ImageDraw.Draw(img)
    n = 8
    step = S / n
    for i in range(n):
        x0 = int(i * step)
        x1 = int((i + 1) * step)
        if horizontal:
            for y in range(x0, x1):
                t = (y - x0) / (x1 - x0)
                c = (int(WHITE[0] * (1 - 0.25 * t)), int(WHITE[1] * (1 - 0.25 * t)), int(WHITE[2] * (1 - 0.28 * t)))
                for x in range(S):
                    img.putpixel((x, y), noise(c, 3))
        else:
            for x in range(x0, x1):
                t = (x - x0) / (x1 - x0)
                c = (int(WHITE[0] * (1 - 0.25 * t)), int(WHITE[1] * (1 - 0.25 * t)), int(WHITE[2] * (1 - 0.28 * t)))
                for y in range(S):
                    img.putpixel((x, y), noise(c, 3))
    if horizontal:
        for y in range(S):
            if y % 2 == 0:
                for x in range(S):
                    img.putpixel((x, y), noise(SHADE, 3))
    else:
        for x in range(S):
            if x % 2 == 0:
                for y in range(S):
                    img.putpixel((x, y), noise(SHADE, 3))

def pillar_texture(name, horizontal=False):
    img, d = base_img()
    flutes(img, horizontal)
    img.save(f"{OUT}/{name}.png")

def column_texture(name, bands=None):
    img, d = base_img()
    flutes(img, False)
    bands = bands or [(0, 2, WHITE), (2, 4, SHADE), (12, 14, SHADE), (14, 16, WHITE)]
    for y0, y1, col in bands:
        d.rectangle([0, y0, 15, y1], fill=col)
        d.line([0, y0, 15, y0], fill=(180, 172, 160))
        d.line([0, y1, 15, y1], fill=(180, 172, 160))
    d.line([0, 2, 15, 2], fill=GOLD)
    d.line([0, 13, 15, 13], fill=GOLD)
    img.save(f"{OUT}/{name}.png")

def pedestal_texture(name):
    img, d = base_img()
    flutes(img, False)
    d.rectangle([0, 0, 15, 2], fill=WHITE)
    d.line([0, 2, 15, 2], fill=GOLD)
    d.rectangle([0, 3, 15, 6], fill=SHADE)
    d.line([0, 6, 15, 6], fill=(180, 172, 160))
    d.rectangle([0, 12, 15, 13], fill=SHADE)
    d.rectangle([0, 13, 15, 15], fill=WHITE)
    d.line([0, 13, 15, 13], fill=GOLD)
    img.save(f"{OUT}/{name}.png")

def gate_texture(name):
    img, d = base_img()
    for y in range(S):
        for x in range(S):
            img.putpixel((x, y), noise(WHITE, 4))
    d.rectangle([0, 0, 15, 15], outline=GOLD)
    d.rectangle([1, 1, 14, 14], outline=(160, 152, 140))
    d.rectangle([2, 2, 13, 13], outline=WHITE)
    # diamond emblem
    cx, cy, r = 7.5, 7.5, 5
    pts = [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]
    d.polygon(pts, outline=GOLD)
    d.polygon([(cx, cy - 2.5), (cx + 2.5, cy), (cx, cy + 2.5), (cx - 2.5, cy)], outline=SHADE)
    d.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=GOLD)
    img.save(f"{OUT}/{name}.png")

def lamp_texture(name):
    img, d = base_img()
    d.rectangle([0, 0, 15, 15], outline=(200, 216, 235))
    d.rectangle([1, 1, 14, 14], outline=WHITE)
    d.rectangle([3, 3, 12, 12], outline=LAMP_EDGE)
    d.ellipse([5, 5, 10, 10], fill=LAMP_CORE)
    d.ellipse([6, 6, 9, 9], fill=(240, 250, 255))
    d.ellipse([7, 7, 8, 8], fill=(255, 255, 255))
    img.save(f"{OUT}/{name}.png")

def door_texture(name_bottom, name_top):
    W, H = 64, 64
    img = Image.new("RGB", (W, H), WHITE)
    d = ImageDraw.Draw(img)
    for y in range(H):
        for x in range(W):
            img.putpixel((x, y), noise(WHITE, 4))
    # panels
    d.rectangle([4, 4, 59, 59], outline=(170, 162, 150))
    d.rectangle([10, 10, 53, 26], outline=SHADE)
    d.rectangle([10, 34, 53, 53], outline=SHADE)
    d.rectangle([12, 12, 51, 24], outline=(228, 224, 216))
    d.rectangle([12, 36, 51, 51], outline=(228, 224, 216))
    # gold bands
    d.rectangle([0, 30, 63, 33], fill=GOLD)
    d.line([0, 30, 63, 30], fill=GOLD_HI)
    d.line([0, 33, 63, 33], fill=(150, 118, 30))
    # handle (bottom only)
    d.ellipse([50, 44, 56, 50], fill=GOLD, outline=(150, 118, 30))
    img.save(f"{OUT}/{name_top}.png")
    img2 = img.copy()
    d2 = ImageDraw.Draw(img2)
    d2.rectangle([0, 0, 63, 3], outline=(170, 162, 150))
    d2.rectangle([0, 61, 63, 63], fill=SHADE)
    img2.save(f"{OUT}/{name_bottom}.png")

marble_texture("marble", veins=3)
polished_texture("marble_polished")
bricks_texture("marble_bricks")
tiles_texture("marble_tiles")
chiseled_texture("marble_chiseled")
gold_trim_texture("marble_gold")
pillar_texture("marble_pillar", horizontal=False)
pillar_texture("marble_beam", horizontal=True)
column_texture("marble_column")
column_texture("marble_column_base", bands=[(0, 5, WHITE), (5, 7, SHADE)])
column_texture("marble_column_mid", bands=[(0, 1, SHADE), (15, 16, SHADE)])
column_texture("marble_column_capital", bands=[(10, 14, SHADE), (14, 16, WHITE)])
pedestal_texture("marble_pedestal")
gate_texture("marble_gate")
lamp_texture("marble_lamp")
door_texture("marble_door_bottom", "marble_door_top")
print("textures done")
