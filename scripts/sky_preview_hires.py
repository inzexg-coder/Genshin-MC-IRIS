#!/usr/bin/env python3
"""Крупные превью неба TeyvatShader (Мондштадт): векторный рендер из формул skyColors.glsl."""
import numpy as np
from PIL import Image, ImageDraw

SKY_COLOR = np.array([0.471, 0.655, 1.0])
MOND = {
    "noonUp": (0.97, 1.0, 0.86), "noonMid": (1.42, 1.36, 1.30), "noonDown": (1.02, 1.0, 0.95),
    "sunUp": (0.74, 0.54, 0.50), "sunMid": (1.9, 1.36, 1.14), "sunDown": (1.55, 0.92, 0.6),
    "night": (0.06, 0.115, 0.27),
}
TIMES = {
    "noon":   dict(sf=1.0, sv=1.0, nf=1.0, inv_nf=0.0, night=0.0, vdots=0.95),
    "sunset": dict(sf=0.45, sv=0.6, nf=0.28, inv_nf=0.72, night=0.12, vdots=0.18),
    "night":  dict(sf=0.0, sv=0.0, nf=0.0, inv_nf=1.0, night=1.0, vdots=-0.35),
}

def smoothstep(x):
    return x * x * (3.0 - 2.0 * x)

def sky_np(c, t, W, H):
    p = TIMES[t]
    sf, sv, inv_nf, night, vdots = p["sf"], p["sv"], p["inv_nf"], p["night"], p["vdots"]
    sky_sqrt = np.sqrt(SKY_COLOR)
    skyM = np.maximum(sky_sqrt, np.array([0.63, 0.67, 0.73]))
    skyM2 = np.maximum(SKY_COLOR, np.array([0.265, 0.295, 0.35]) * sf)
    noonUp = skyM ** 2.9 * np.array(c["noonUp"])
    noonMid = skyM ** 1.5 * np.array(c["noonMid"]) + noonUp * 0.65
    noonDown = skyM * np.array(c["noonDown"]) + noonUp * 0.25
    sunUp = skyM2 * np.array(c["sunUp"])
    sunMid = skyM2 * np.array(c["sunMid"])
    sunDownP = np.array(c["sunDown"])
    sunDown = sunDownP * 0.5 + sunMid * 0.25
    i2 = inv_nf ** 2
    dayUp = noonUp * (1 - i2) + sunUp * i2
    dayMid = noonMid * (1 - i2) + sunMid * i2
    dayDown = noonDown * (1 - i2) + sunDown * i2
    ncf = np.array(c["night"]) * 0.9 + SKY_COLOR
    nightUp = ncf ** 0.9 * 0.45
    nightMid = np.sqrt(nightUp) * 0.65
    nightDown = nightMid * np.array([0.82, 0.82, 0.88])

    vdotu = np.linspace(1.0, -1.0, H)[:, None] * np.ones((1, W))
    nfsq = np.sqrt(night)
    nfm = np.sqrt(nfsq) * 0.4
    vsm1 = max(vdots, 0.0) ** 2
    vsm2 = vsm1 ** 2
    vsm3 = (max(-vdots, 0.0) ** 2) ** 2
    vdu = np.maximum(vdotu, 0.0)
    up = nightUp * (1.5 - 0.5 * nfsq + nfm * vsm3 * 1.5) * (1 - sf) + dayUp * sf
    mid = nightMid * (3.0 - 2.0 * nfsq) * (1 - sf) + dayMid * (1.0 + vsm2 * 0.3) * sf
    down = nightDown * (1 - (sf + sv) * 0.5) + dayDown * (sf + sv) * 0.5
    vdum1 = (1.0 - vdu) ** 2
    vdum1 = vdum1 ** (1.0 - vsm2 * 0.4)
    s = up[None, None, :] * (1 - vdum1[..., None]) + mid[None, None, :] * vdum1[..., None]
    vdum2 = (1.0 - np.abs(vdotu)) ** 2
    vdum2 = smoothstep(vdum2)
    vdum2 = vdum2 * (0.7 - nfm + vsm1 * (0.3 + nfm)) * inv_nf * sf
    s = s * (1 - vdum2[..., None]) + (sunDownP * (1.0 + vsm1 * 0.3))[None, None, :] * vdum2[..., None]
    vdum3 = np.clip((-vdotu + 0.08) / 0.35, 0.0, 1.0)
    vdum3 = smoothstep(vdum3)
    sgm = np.stack([vdum3 ** 2, np.sqrt(vdum3), vdum3 ** (1.0 / 3.0)], -1)
    sgm = np.stack([vdum3] * 3, -1) * 0.25 + sgm * 0.75
    s = s * (1 - sgm) + down[None, None, :] * sgm
    s = s * smoothstep((1.0 + np.minimum(vdotu, 0.0)) ** 2)[..., None]
    return s

def aces(x):
    return (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)

def tonemap(c):
    return np.clip(aces(np.maximum(c, 0.0)) ** (1.0 / 2.2), 0.0, 1.0)

W, H = 1000, 700
for t in TIMES:
    img = Image.fromarray((tonemap(sky_np(MOND, t, W, H)) * 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    d.text((14, 14), f"Mondstadt sky - {t}  (TeyvatShader, SHADER_STYLE 4)", fill=(255, 255, 255))
    p = f"docs/previews/mondstadt_sky_{t}.png"
    img.save(p)
    print("saved", p)
