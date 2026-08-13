#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Геометрия клинка для поз комбо — воспроизводит РЕАЛЬНУЮ геометрию игры.

Игровой рендер руки (ModelPart.applyTransform): точка v умножается на
Quaternionf.rotationZYX(roll, yaw, pitch). Проверено запуском JOML 1.10.8
(GeoTest): для (pitch=-90°, roll=0) вектор (0,10,0) -> (0,0,-10) — клинок
вперёд. Это стандартная матрица R = Rz(roll)*Ry(yaw)*Rx(pitch) для
вектора-столбца (НЕ транспонированная, как в старом anim_preview).

Клинок в руке: HeldItemFeatureRenderer рисует предмет после
  setArmAngle -> Rx(-90) -> Ry(180) -> translate(±1/16, 0.125, -0.625)
  -> display handheld (thirdperson_righthand: translate(0,4,0.5)/16,
     rotation (0,-90,55), scale 0.85).
Из этой цепочки (SwordDir.java) в локальной системе руки:
  кисть    hand_arm = (-1.08,  9.52, -5.92) px (от пивота плеча)
  кончик   tip_arm  = (-1.08, 20.72,-13.76) px
  направление клинка c = normalize(tip-hand) = (0, 0.819, -0.574).
"""
import math

# ---- константы клинка в системе руки (px, от пивота правого плеча) ----
HAND_ARM = (-1.08, 9.52, -5.92)
TIP_ARM = (-1.08, 20.72, -13.76)
BLADE_C = (0.0, 0.819, -0.574)          # направление клинка в системе руки
BLADE_LEN = math.sqrt(sum((a - b) ** 2 for a, b in zip(TIP_ARM, HAND_ARM)))  # ~13.7 px
SHOULDER = (-5.0, 2.0, 0.0)             # пивот правого плеча в модели (px)

# Мировое преобразование модели (LivingEntityRenderer.render):
#   world = Ry(180-bodyYaw) * S(-1,-1,1) * T(0,-1.501,0) * root * point/16
MODEL_ROOT_OFFSET = 1.501               # высота root модели над ногами (блоки)


def rot_zyx(roll, yaw, pitch):
    """Матрица Rz(roll)*Ry(yaw)*Rx(pitch) (стандартная, векторы-столбцы) —
    то же, что Quaternionf.rotationZYX(roll,yaw,pitch) в игре."""
    cz, sz = math.cos(roll), math.sin(roll)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cx, sx = math.cos(pitch), math.sin(pitch)
    return (
        (cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx),
        (sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx),
        (-sy, cy * sx, cy * cx),
    )


def mul(m, v):
    """m * v (вектор-столбец)."""
    return (m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2])


def norm(v):
    n = math.sqrt(sum(x * x for x in v))
    return (v[0] / n, v[1] / n, v[2] / n) if n else (0.0, 0.0, 0.0)


def arm_blade_dir(roll, yaw, pitch):
    """Направление клинка в системе МОДЕЛИ (px-система, +Y вниз) для углов руки."""
    return mul(rot_zyx(roll, yaw, pitch), BLADE_C)


def arm_hand(roll, yaw, pitch):
    """Кисть в системе модели (px)."""
    h = mul(rot_zyx(roll, yaw, pitch), HAND_ARM)
    return (h[0] + SHOULDER[0], h[1] + SHOULDER[1], h[2] + SHOULDER[2])


def arm_tip(roll, yaw, pitch, length=BLADE_LEN):
    """Кончик клинка в системе модели (px)."""
    h = arm_hand(roll, yaw, pitch)
    d = mul(rot_zyx(roll, yaw, pitch), BLADE_C)
    return (h[0] + d[0] * length, h[1] + d[1] * length, h[2] + d[2] * length)


def world(px_pos, root_yaw=0.0, body_yaw_deg=0.0, feet=(0.0, 0.0, 0.0)):
    """px модели -> мировые блоки относительно ног игрока.

    world = Ry(180-bodyYaw) * S(-1,-1,1) * T(0,-1.501,0) * Ry(rootYaw) * (p/16)
    """
    q = rot_zyx(0.0, root_yaw, 0.0)
    p = mul(q, (px_pos[0] / 16.0, px_pos[1] / 16.0, px_pos[2] / 16.0))
    # S(-1,-1,1) * T(0,-1.501,0)
    s = (-p[0], -p[1] + MODEL_ROOT_OFFSET, p[2])
    yaw = math.radians(180.0 - body_yaw_deg)
    r = rot_zyx(0.0, yaw, 0.0)
    w = mul(r, s)
    return (feet[0] + w[0], feet[1] + w[1], feet[2] + w[2])


def _reachable_grid(step=2.0):
    """Предрасчёт направлений клинка для сетки (roll, pitch), yaw=0."""
    import numpy as np
    rolls = np.deg2rad(np.arange(-180.0, 180.0 + 1e-6, step))
    pitches = np.deg2rad(np.arange(-179.0, 180.0 + 1e-6, step))
    RR, PP = np.meshgrid(rolls, pitches, indexing="ij")
    cz, sz = np.cos(RR), np.sin(RR)
    cy = 1.0
    sy = 0.0
    cx, sx = np.cos(PP), np.sin(PP)
    m00 = cz * cy
    m01 = cz * sy * sx - sz * cx
    m02 = cz * sy * cx + sz * sx
    m10 = sz * cy
    m11 = sz * sy * sx + cz * cx
    m12 = sz * sy * cx - cz * sx
    m20 = -sy
    m21 = cy * sx
    m22 = cy * cx
    cxv, cyv, czv = BLADE_C
    bx = m00 * cxv + m01 * cyv + m02 * czv
    by = m10 * cxv + m11 * cyv + m12 * czv
    bz = m20 * cxv + m21 * cyv + m22 * czv
    return rolls, pitches, np.stack([bx, by, bz], axis=-1)


_GRID = None


def solve_blade(target_dir, prefer_roll=None, yaw_range=(-12, 12), step=2.0,
                pitch_range=None):
    """Подобрать (roll, yaw, pitch) правой руки под направление клинка.

    target_dir — направление клинка в системе МОДЕЛИ (+X вправо, +Y вниз,
    -Z вперёд). prefer_roll — окно по roll (непрерывность поз),
    pitch_range — ограничение по pitch (например, для нейтрали «рука вниз»).
    Возвращает (roll, yaw, pitch, err) в градусах.
    """
    global _GRID
    if _GRID is None:
        _GRID = _reachable_grid(step)
    import numpy as np
    rolls, pitches, dirs = _GRID
    d = np.asarray(norm(target_dir), dtype=float)
    err = np.sum((dirs - d) ** 2, axis=-1)
    if prefer_roll is not None:
        r_pref = math.radians(prefer_roll)
        dr = np.arctan2(np.sin(rolls - r_pref), np.cos(rolls - r_pref))[:, None]
        err = np.where(np.abs(dr) > math.radians(120.0), 1e9, err)
    if pitch_range is not None:
        p0, p1 = pitch_range
        err = np.where((pitches < math.radians(p0)) | (pitches > math.radians(p1)), 1e9, err)
    bi, bj = np.unravel_index(np.argmin(err), err.shape)
    r0 = math.degrees(rolls[bi])
    p0 = math.degrees(pitches[bj])
    best = (r0, 0.0, p0)
    best_err = err[bi, bj]
    # полировка с малым шагом и yaw
    y0, y1 = yaw_range
    sub = 0.5
    for roll in _frange(r0 - 2 * step, r0 + 2 * step, sub):
        for pitch in _frange(p0 - 2 * step, p0 + 2 * step, sub):
            for yaw in _frange(max(y0, -8), min(y1, 8), 2.0):
                b = arm_blade_dir(math.radians(roll), math.radians(yaw), math.radians(pitch))
                e = (b[0] - d[0]) ** 2 + (b[1] - d[1]) ** 2 + (b[2] - d[2]) ** 2
                if e < best_err:
                    best_err = e
                    best = (roll, yaw, pitch)
    return best[0], best[1], best[2], math.sqrt(best_err)


def _frange(a, b, step):
    if step <= 0:
        return [a]
    out = []
    x = a
    while x <= b + 1e-9:
        out.append(x)
        x += step
    return out


if __name__ == "__main__":
    # Самопроверка против GeoTest (JOML): pitch -90 -> (0,0,-10)
    d = arm_blade_dir(0.0, 0.0, math.radians(-90))
    print(f"pitch -90: blade={tuple(round(x, 2) for x in d)} (ожид. (0.0, 0.0, -1.0) в единичном)")
    hand = arm_hand(0.0, 0.0, math.radians(-90))
    print(f"pitch -90: hand={tuple(round(x, 1) for x in hand)} (ожид. (-5.0, 2.0, -10.0) px)")
    for label, tgt in [("вперёд", (0.0, 0.0, -1.0)),
                       ("влево-вперёд", (-0.6, 0.0, -0.8)),
                       ("вправо-вперёд", (0.8, 0.0, -0.6)),
                       ("вверх", (0.0, -0.7, -0.7)),
                       ("вниз", (0.0, 0.7, -0.7)),
                       ("за спину влево", (-0.5, -0.2, 0.8))]:
        r, y, p, e = solve_blade(tgt)
        d = arm_blade_dir(math.radians(r), math.radians(y), math.radians(p))
        print(f"{label:16s} -> roll={r:6.1f} yaw={y:6.1f} pitch={p:7.1f} err={e:.3f} got=({d[0]:+.2f},{d[1]:+.2f},{d[2]:+.2f})")
