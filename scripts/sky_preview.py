#!/usr/bin/env python3
"""Превью палитры TeyvatShader: рендер из формул skyColors.glsl / lightAndAmbientColors.glsl.
До/после: оригинал Complementary vs Мондштадт. Тонмаппинг — ACES (приближение), гамма 2.2."""
import math
from PIL import Image, ImageDraw

SKY_COLOR = (0.471, 0.655, 1.0)  # plains sky_color 0x78A7FF (vanilla)

ORIG = {
    "noonUp": (0.85, 0.92, 0.81), "noonMid": (1.3,), "noonDown": (0.9,),
    "sunUp": (0.72, 0.522, 0.47), "sunMid": (1.8, 1.3, 1.2), "sunDown": (1.45, 0.86, 0.5),
    "night": (0.07, 0.14, 0.24), "light": (1.3325, 1.1275, 0.76875), "ambMul": 0.85,
    "nightAmb": (0.09, 0.12, 0.17),
}
MOND = {
    "noonUp": (0.97, 1.0, 0.86), "noonMid": (1.42, 1.36, 1.30), "noonDown": (1.02, 1.0, 0.95),
    "sunUp": (0.74, 0.54, 0.50), "sunMid": (1.9, 1.36, 1.14), "sunDown": (1.55, 0.92, 0.6),
    "night": (0.06, 0.115, 0.27), "light": (1.435, 1.23, 0.902), "ambMul": 0.92,
    "nightAmb": (0.075, 0.11, 0.21),
}

def v3(a):
    if len(a) == 1:
        return (a[0], a[0], a[0])
    return tuple(a)

def mul(a, b):
    return tuple(x * y for x, y in zip(a, b))

def add(a, b):
    return tuple(x + y for x, y in zip(a, b))

def scal(a, s):
    return tuple(x * s for x in a)

def pow3(a, p):
    return tuple(x ** p for x in a)

def mix(a, b, t):
    if isinstance(t, tuple):
        return tuple(x + (y - x) * z for x, y, z in zip(a, b, t))
    return tuple(x + (y - x) * t for x, y in zip(a, b))

def smoothstep1(x):
    return x * x * (3.0 - 2.0 * x)

def sky_bands(c, sun_factor, inv_nf2, sky_color=SKY_COLOR):
    """skyColors.glsl (OVERWORLD), без дождя."""
    inv_rain2 = 1.0
    sky_sqrt = tuple(math.sqrt(x) for x in sky_color)
    skyM = tuple(max(a, b) for a, b in zip(sky_sqrt, (0.63, 0.67, 0.73)))
    skyM2 = tuple(max(a, b) for a, b in zip(sky_color, scal((0.265, 0.295, 0.35), sun_factor)))
    noonUp = scal(pow3(skyM, 2.9), mul(c["noonUp"], (1.0, 1.0, 1.0))[0] if len(c["noonUp"]) == 3 else 1.0)
    # выше упрощение: ниже точный расчёт
    noonUp = mul(pow3(skyM, 2.9), v3(c["noonUp"]))
    noonMid = add(mul(pow3(skyM, 1.5), v3(c["noonMid"])), scal(noonUp, 0.65))
    noonDown = add(mul(skyM, v3(c["noonDown"])), scal(noonUp, 0.25))
    sunUp = mul(skyM2, v3(c["sunUp"]))
    sunMid = mul(skyM2, v3(c["sunMid"]))
    sunDownP = v3(c["sunDown"])
    sunDown = add(scal(sunDownP, 0.5), scal(sunMid, 0.25))
    dayUp = mix(noonUp, sunUp, inv_nf2)
    dayMid = mix(noonMid, sunMid, inv_nf2)
    dayDown = mix(noonDown, sunDown, inv_nf2)
    ncf = add(scal(c["night"], 0.9), sky_color)
    nightUp = scal(pow3(ncf, 0.90), 0.45)
    nightMid = scal(tuple(math.sqrt(x) for x in nightUp), 0.65)
    nightDown = mul(nightMid, (0.82, 0.82, 0.88))
    return dayUp, dayMid, dayDown, nightUp, nightMid, nightDown

def get_sky(c, vdotu, vdots, sf, sv, nf, inv_nf, rain=0.0, night_factor=0.0):
    """Порт GetSky() из sky.glsl (без glare/облаков)."""
    dayUp, dayMid, dayDown, nightUp, nightMid, nightDown = sky_bands(c, sf, inv_nf ** 2)
    nfsq = math.sqrt(night_factor)
    nfm = math.sqrt(nfsq) * 0.4
    vsm1 = max(vdots, 0.0) ** 2
    vsm2 = vsm1 ** 2
    vsm3 = (max(-vdots, 0.0) ** 2) ** 2
    vdumax = max(vdotu, 0.0)
    up = mix(scal(nightUp, 1.5 - 0.5 * nfsq + nfm * vsm3 * 1.5), dayUp, sf)
    mid = mix(scal(nightMid, 3.0 - 2.0 * nfsq), scal(dayMid, 1.0 + vsm2 * 0.3), sf)
    down = mix(nightDown, dayDown, (sf + sv) * 0.5)
    vdum1 = (1.0 - vdumax) ** 2
    vdum1 = vdum1 ** (1.0 - vsm2 * 0.4)
    sky = mix(up, mid, vdum1)
    vdum2 = (1.0 - abs(vdotu)) ** 2
    vdum2 = smoothstep1(vdum2)
    vdum2 *= (0.7 - nfm + vsm1 * (0.3 + nfm)) * inv_nf * sf
    sun_down_p = v3(c["sunDown"])
    sky = mix(sky, scal(sun_down_p, 1.0 + vsm1 * 0.3), vdum2)
    vdum3 = min(max(-vdotu + 0.08, 0.0) / 0.35, 1.0)
    vdum3 = smoothstep1(vdum3)
    sgm = (vdum3 ** 2, math.sqrt(vdum3), vdum3 ** (1.0 / 3.0))
    sgm = mix((vdum3, vdum3, vdum3), sgm, 0.75)
    sky = mix(sky, down, sgm)
    sky = scal(sky, smoothstep1((1.0 + min(vdotu, 0.0)) ** 2))
    return sky

def toon_band(x, s1=0.45, s2=0.85, sw=0.05):
    x = min(max(x, 0.0), 1.0)
    def ss(t):
        t = min(max(t, 0.0), 1.0)
        return t * t * (3.0 - 2.0 * t)
    b = ss((x - (s1 - sw)) / (2.0 * sw)) * 0.5
    b += ss((x - (s2 - sw)) / (2.0 * sw)) * 0.5
    return b

def aces(x):
    x = max(x, 0.0)
    return (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)

def tonemap(c):
    return tuple(min(max(aces(v) ** (1.0 / 2.2), 0.0), 1.0) for v in c)

def to_rgb(c):
    return tuple(int(round(min(max(v, 0.0), 1.0) * 255)) for v in c)

TIMES = {
    "noon":   dict(sf=1.0, sv=1.0, nf=1.0, inv_nf=0.0, night=0.0, vdots=0.95),
    "sunset": dict(sf=0.45, sv=0.6, nf=0.28, inv_nf=0.72, night=0.12, vdots=0.18),
    "night":  dict(sf=0.0, sv=0.0, nf=0.0, inv_nf=1.0, night=1.0, vdots=-0.35),
}

def render_sky2(c, t, W=420, H=280):
    img = Image.new("RGB", (W, H))
    px = img.load()
    p = TIMES[t]
    for y in range(H):
        vdotu = 1.0 - 2.0 * y / (H - 1)
        for x in range(W):
            col = get_sky(c, vdotu, p["vdots"], p["sf"], p["sv"], p["nf"], p["inv_nf"], night_factor=p["night"])
            px[x, y] = to_rgb(tonemap(col))
    return img

def light_colors(c, t):
    """lightAndAmbientColors.glsl (OVERWORLD, без дождя, non-COMPOSITE1)."""
    p = TIMES[t]
    vs = 1.0
    noonL = c["light"]
    noonA = scal(pow3(SKY_COLOR, 0.75), c["ambMul"])
    inv_nf = p["inv_nf"]
    sunsetL = scal(pow3((0.64, 0.45, 0.3), 1.5 + inv_nf), 5.0)
    sunsetA = mul(noonA, (1.21 * 0.95, 0.92 * 0.95, 0.76 * 0.95))
    nightL = scal((0.15, 0.14, 0.20), 0.9 * (0.4 + 0.4))
    nightA = scal(c["nightAmb"], 0.9 * (1.55 + 0.77))
    dayL = mix(sunsetL, noonL, p["nf"])
    dayA = mix(sunsetA, noonA, p["nf"])
    L = mix(nightL, dayL, p["sv"] ** 2)
    A = mix(nightA, dayA, p["sv"] ** 2)
    return L, A

# --- тестовая сцена: трава + кубы, ламберт + жёсткие тени ---
def ray_box(ro, rd, bmin, bmax):
    tmin, tmax = -1e9, 1e9
    for i in range(3):
        if abs(rd[i]) < 1e-9:
            if ro[i] < bmin[i] or ro[i] > bmax[i]:
                return None
        else:
            t1 = (bmin[i] - ro[i]) / rd[i]
            t2 = (bmax[i] - ro[i]) / rd[i]
            if t1 > t2:
                t1, t2 = t2, t1
            tmin, tmax = max(tmin, t1), min(tmax, t2)
            if tmin > tmax:
                return None
    return tmin

def render_scene(c, t, W=420, H=280):
    img = Image.new("RGB", (W, H))
    px = img.load()
    L, A = light_colors(c, t)
    p = TIMES[t]
    sun_dir = {"noon": (0.0, 1.0, 0.1), "sunset": (0.35, 0.45, 0.85), "night": (-0.5, 0.7, -0.4)}[t]
    sd = sun_dir
    boxes = [
        ((-1.2, 0.0, -3.0), (1.2, 2.6, -0.6), (0.62, 0.60, 0.55)),   # башня/камень
        ((-4.5, 0.0, -4.5), (-2.8, 1.4, -2.8), (0.55, 0.45, 0.33)),  # дом/дерево
        ((2.6, 0.0, -5.0), (4.4, 0.9, -3.2), (0.58, 0.55, 0.5)),     # скала
    ]
    cam = (0.0, 2.2, 4.5)
    fwd = (0.0, -0.06, -1.0)
    right = (1.0, 0.0, 0.0)
    up = (0.0, 1.0, 0.0)
    for y in range(H):
        for x in range(W):
            u = (x - W / 2) / (H / 2)
            v = (y - H / 2) / (H / 2)
            rd = (fwd[0] + right[0] * u + up[0] * v,
                  fwd[1] + right[1] * u + up[1] * v,
                  fwd[2] + right[2] * u + up[2] * v)
            # фон: небо
            vdotu = rd[1]
            col = get_sky(c, vdotu, p["vdots"], p["sf"], p["sv"], p["nf"], p["inv_nf"], night_factor=p["night"])
            hit = None
            for (bmin, bmax, alb) in boxes:
                th = ray_box(cam, rd, bmin, bmax)
                if th is not None and (hit is None or th < hit[0]):
                    hit = (th, bmin, bmax, alb)
            tplane = None
            if rd[1] < -1e-9:
                tplane = -cam[1] / rd[1]
                if tplane > 0 and (hit is None or tplane < hit[0]):
                    hit = (tplane, None, None, (0.44, 0.58, 0.32))  # трава
            if hit is not None:
                th, bmin, bmax, alb = hit
                pos = (cam[0] + rd[0] * th, cam[1] + rd[1] * th, cam[2] + rd[2] * th)
                if bmin is None:
                    n = (0.0, 1.0, 0.0)
                else:
                    eps = 1e-4
                    n = [0.0, 0.0, 0.0]
                    for i in range(3):
                        if abs(pos[i] - bmin[i]) < eps:
                            n[i] = -1.0
                        elif abs(pos[i] - bmax[i]) < eps:
                            n[i] = 1.0
                    n = tuple(n)
                ndl = max(sum(a * b for a, b in zip(n, sd)), 0.0)
                # тень: луч к солнцу
                sh = 1.0
                sp = tuple(a + b * 0.02 for a, b in zip(pos, sd))
                for (bb, bb2, _) in boxes:
                    th2 = ray_box(sp, sd, bb, bb2)
                    if th2 is not None and th2 > 0:
                        sh = 0.0
                        break
                if c is MOND:
                    light = scal(L, toon_band(ndl * sh))
                else:
                    light = scal(L, ndl * sh)
                amb = scal(A, 0.55)
                col = mul(alb, add(light, amb))
            px[x, y] = to_rgb(tonemap(col))
    return img

# --- сборка ---
names = {"ORIG": ORIG, "MOND": MOND}
W, H = 420, 280
cells = {}
for kind, fn in (("sky", render_sky2), ("scene", render_scene)):
    for label, c in names.items():
        for t in TIMES:
            cells[(kind, label, t)] = fn(c, t, W, H)

grid = Image.new("RGB", (W * 3 + 80, H * 2 + 40), (18, 18, 24))
d = ImageDraw.Draw(grid)
for kind, kname in (("sky", "SKY"), ("scene", "SCENE")):
    for ri, label in enumerate(names):
        for ci, t in enumerate(TIMES):
            y0 = ri * (H + 8) + 40
            x0 = ci * (W + 8)
            grid.paste(cells[(kind, label, t)], (x0, y0))
            d.text((x0 + 4, y0 - 18), f"{kname} {label} {t}", fill=(220, 220, 230))
grid.save("docs/previews/palette_comparison.png")

# свет/амбиент свотчи
sw = Image.new("RGB", (420, 120), (18, 18, 24))
d = ImageDraw.Draw(sw)
for label, c in names.items():
    for ci, t in enumerate(TIMES):
        L, A = light_colors(c, t)
        x0 = ci * 140
        d.rectangle([x0, 10 + (30 if label == "MOND" else 0), x0 + 120, 40 + (30 if label == "MOND" else 0)], fill=to_rgb(tonemap(L)))
        d.rectangle([x0, 50 + (30 if label == "MOND" else 0), x0 + 120, 70 + (30 if label == "MOND" else 0)], fill=to_rgb(tonemap(A)))
d.text((2, 2), "top=ORIG sun / bot=MOND sun  (bar1=light, bar2=ambient)  noon | sunset | night", fill=(200, 200, 210))
sw.save("docs/previews/light_swatches.png")
print("saved:", "docs/previews/palette_comparison.png", "docs/previews/light_swatches.png")
