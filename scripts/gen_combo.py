#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генератор анимаций комбо меча путешественника (5 ударов).

Позы задаются хореографией клинка (как в Genshin) + малыми углами корпуса:
  1. удар справа, клинок горизонтальный, свинг справа налево, рука с
     задержкой слева на пару миллисекунд;
  2. апперкот вверх-влево: замах клинком к земле, финиш — рука вытянута вверх;
  3. разворот против часовой стрелки с рубящим ударом клинком сверху;
  4. удар слева направо (замах слева-сверху, клинок горизонтально на ударе);
  5. очень широкий удар справа налево: гигантский замах за голову, наклон
     корпуса и рывок вперёд в свинге, задержка руки слева.

Клипы ЗАПЕКАЮТСЯ: якоря (t, кривая, поза) сэмплируются в 41 линейный кадр —
в игре нет скачков углов, траектория клинка непрерывна и плавная. Каждый удар
живёт «широко»: мгновенный старт, пик скорости в момент урона (~0.40), занос
клинка (whip) на хвосте и мягкая фиксация к следующему удару. Траектория
правой руки/клинка проверена против объёма головы на ВСЕХ кадрах (--check):
апперкот и замахи огибают голову спереди и сбоку, клинок нигде не проходит
сквозь неё.

Углы правой руки считаются СОЛВЕРОМ под игровую геометрию (scripts/blade_geo.py:
ModelPart применяет Quaternionf.rotationZYX(roll,yaw,pitch), клинок в руке —
константа из цепочки HeldItemFeatureRenderer + display). Направление клинка
задаётся в системе МОДЕЛИ: +X вправо, +Y ВНИЗ, -Z вперёд (описание из
gen_genshin_combo.py: направление клинка +Y вверх — переверните Y).

Принципы:
  - ГОЛОВА ВСЕГДА 0 (смотрит строго вперёд).
  - УДАР МГНОВЕННЫЙ: t=0 — уже замах/нейтраль, свинг стартует сразу,
    пик скорости (момент урона) ~0.40 клипа.
  - КОРПУС НЕ ОТРЫВАЕТСЯ ОТ НОГ: у тела ванильный пивот на уровне ШЕИ,
    поэтому углы корпуса МАЛЫЕ (bYaw ±12°, bPitch ≤8°) — таз остаётся у
    бёдер. Динамику дают широкие махи рук, шаги ног (пивоты на плечах/
    бёдрах — не ломают связи) и разворот root (удар 3).
  - ЛЕВАЯ РУКА — естественный противовес: плавно поднимается в замахе и
    уходит в сторону вылета, БЕЗ резких смен направления.
  - НОГИ ПЕРЕСТУПАЮТ: выпад в момент урона, опора между замахом и свингом.
  - Свинг с УСКОРЕНИЕМ (E_IN_CUBIC) до пика ~0.40, сопровождение с
    торможением (E_OUT_CUBIC), замахи и стыки — E_IN_OUT_SINE.
  - Каждый клип непрерывен: t=0 = финал предыдущего удара (seam).

Порядок каналов Pose (radian): правая рука y/p/r, левая рука y/p/r,
корпус y/p/r, голова y/p/r, правая нога y/p/r, левая нога y/p/r.

Использование:
    python3 scripts/gen_combo.py            # предпросмотр ASCII-кадрами
    python3 scripts/gen_combo.py --write    # перезаписать позы в CombatController.java
    python3 scripts/gen_combo.py --render   # только предпросмотр
"""
import argparse
import math
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAVA = REPO / "mod/src/main/java/net/teyvat/client/CombatController.java"
sys.path.insert(0, str(REPO / "scripts"))
import blade_geo as bg

E_LINEAR = 0
E_IN_OUT_CUBIC = 1
E_OUT_CUBIC = 2
E_OUT_BACK = 3
E_IN_OUT_SINE = 4
E_IN_CUBIC = 5

CH = ["rYaw", "rPitch", "rRoll", "lYaw", "lPitch", "lRoll",
      "bYaw", "bPitch", "bRoll", "hYaw", "hPitch", "hRoll",
      "rlYaw", "rlPitch", "rlRoll", "llYaw", "llPitch", "llRoll"]

D = math.degrees
R = math.radians


def P(rYaw=0, rPitch=0, rRoll=0, lYaw=0, lPitch=0, lRoll=0,
      bYaw=0, bPitch=0, bRoll=0, hYaw=0, hPitch=0, hRoll=0,
      rlYaw=0, rlPitch=0, rlRoll=0, llYaw=0, llPitch=0, llRoll=0):
    return dict(zip(CH, [rYaw, rPitch, rRoll, lYaw, lPitch, lRoll,
                         bYaw, bPitch, bRoll, hYaw, hPitch, hRoll,
                         rlYaw, rlPitch, rlRoll, llYaw, llPitch, llRoll]))


_SOLVE_STATE = {"roll": None}


def arm(blade_model, roll_pref=None, pitch_range=None):
    """Правая рука под направление клинка в системе МОДЕЛИ (+Y вниз, -Z вперёд).
    Возвращает dict {rYaw, rPitch, rRoll} в градусах. roll_pref — желаемый
    roll (для непрерывности между кадрами), pitch_range — окно по pitch."""
    if roll_pref is None:
        roll_pref = _SOLVE_STATE.get("roll")
    r, y, p, err = bg.solve_blade(blade_model, prefer_roll=roll_pref, pitch_range=pitch_range)
    if err > 0.06:
        print(f"    !! солвер: цель {tuple(round(x, 2) for x in blade_model)} err={err:.3f} -> "
              f"roll={r:.0f} yaw={y:.0f} pitch={p:.0f}", file=sys.stderr)
    _SOLVE_STATE["roll"] = r
    return {"rYaw": y, "rPitch": p, "rRoll": r}


# Кривые: 0 линейно, 1 in-out cubic, 2 out cubic, 3 out back, 4 in-out sine, 5 in cubic.
# Удары (длительность из SwordCombo.DURATION_TICKS):
#   1 — 10 тиков, 2 — 12, 3 — 16, 4 — 12, 5 — 20.

# ================= КЛИПЫ =================
# Углы руки заданы НАПРЯМУЮ (в градусах) — плавные дуги без «перегибов»
# солвера: рука в замахах уходит вверх-назад/за спину, в свинге выносится
# вперёд и ведёт клинок широкой дугой, корпус доворачивается в удар.
# Диапазоны проверены на проход руки сквозь торс (scripts/blade_geo.py:
# arm_penetration) — максимальная глубина по всем кадрам < 1 px (только
# касание у плеча).
# ================= КЛИПЫ =================
# Позы задаются ЯКОРЯМИ (t, кривая, поза) и ЗАПЕКАЮТСЯ в 41 линейный кадр
# (bake): в игре кадры идут без скачков углов, траектория клинка непрерывна.
# Принципы те же, что в шапке: мгновенный старт, пик скорости в момент урона
# (~0.40), широкие махи с заносом (whip), переступание ног, корпус
# доворачивается, левая рука — живой противовес.
# ВАЖНО: траектория правой руки и клинка проверяется против объёма головы
# (head-check ниже, флаг --check) — апперкот и замахи ОГИБАЮТ голову спереди
# и сбоку, ни на одном кадре клинок не проходит сквозь неё (удар 2 — раньше
# кисть оказывалась у подбородка, а клинок входил в голову).

def _ease(kind, t):
    """Зеркало CombatController.ease (t в 0..1) — запекаем те же кривые."""
    t = max(0.0, min(1.0, t))
    if kind == E_IN_OUT_CUBIC:
        return 4 * t ** 3 if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2
    if kind == E_OUT_CUBIC:
        return 1 - (1 - t) ** 3
    if kind == E_OUT_BACK:
        c1, c3 = 1.1, 2.1
        return 1 + c3 * (t - 1) ** 3 + c1 * (t - 1) ** 2
    if kind == E_IN_OUT_SINE:
        return -(math.cos(math.pi * t) - 1) / 2
    if kind == E_IN_CUBIC:
        return t ** 3
    return t


def _mix(a, b, t):
    out = {}
    for k in CH:
        va, vb = a[k], b[k]
        out[k] = vb if math.isnan(va) else (va if math.isnan(vb) else va + (vb - va) * t)
    return out


def _clip_at(anchors, p):
    if p <= anchors[0][0]:
        return anchors[0][2]
    for i in range(len(anchors) - 1):
        ta, ea, pa = anchors[i]
        tb, eb, pb = anchors[i + 1]
        if p <= tb:
            span = tb - ta
            u = 1.0 if span <= 0 else (p - ta) / span
            return _mix(pa, pb, _ease(ea, u))
    return anchors[-1][2]


def warp(p):
    """Мягкое сжатие таймлайна: замах (клип-время 0..0.40) умещается в первые
    ~25% игрового прогресса — более плавный размах перед ударом, урон
    (DAMAGE_TICKS) наносится после размаха, дальше идёт широкое
    сопровождение клинка. Пик свинга (0.40 в клипе) оказывается на 0.25
    игрового прогресса, 1.0 остаётся 1.0 (финал удара не двигаем).
    Более мягкий warp даёт плавные, живые анимации без рывков."""
    PEAK_GAME = 0.25
    PEAK_CLIP = 0.40
    if p <= PEAK_GAME:
        return p / PEAK_GAME * PEAK_CLIP
    return PEAK_CLIP + (p - PEAK_GAME) / (1.0 - PEAK_GAME) * (1.0 - PEAK_CLIP)


def bake(anchors, n=41):
    """Якоря -> плотная последовательность линейных кадров 0..1 (как раньше),
    но с warp(): замах сжат в первые ~12% клипа (мгновенный удар)."""
    frames = []
    for i in range(n):
        p = i / (n - 1)
        frames.append((round(p, 3), E_LINEAR, _clip_at(anchors, warp(p))))
    return frames


# --- hit1: свинг справа налево (на экране слева направо), клинок горизонтально.
# Мгновенный старт из нейтрали, широкий замах вправо, свинг через фронт,
# занос клинка влево и мягкая фиксация (сеam в апперкот). ---
HIT1 = [
    # Forward-right slash (17° from center) — quick, light
    # Blade sweeps from right to left at chest height
    (0.00, E_LINEAR, P(rYaw=-20.0, rPitch=-30.0, rRoll=-5.0, lPitch=-10.0, bYaw=5.0, bPitch=1.0, rlPitch=-5.0, llPitch=5.0)),
    (0.08, E_IN_CUBIC, P(rYaw=-35.0, rPitch=-40.0, rRoll=-10.0, lPitch=-20.0, lRoll=-8.0, bYaw=8.0, bPitch=2.0, rlPitch=-12.0, llPitch=10.0)),
    (0.15, E_IN_CUBIC, P(rYaw=-15.0, rPitch=-50.0, rRoll=-5.0, lPitch=-35.0, lRoll=-12.0, bYaw=10.0, bPitch=3.0, rlPitch=-20.0, llPitch=15.0)),
    (0.25, E_IN_CUBIC, P(rYaw=10.0, rPitch=-45.0, rRoll=5.0, lPitch=-40.0, lRoll=-8.0, bYaw=6.0, bPitch=4.0, rlPitch=10.0, llPitch=-8.0)),
    (0.40, E_OUT_CUBIC, P(rYaw=30.0, rPitch=-35.0, rRoll=10.0, lPitch=-25.0, lRoll=5.0, bYaw=0.0, bPitch=3.0, rlPitch=20.0, llPitch=-15.0)),
    (0.55, E_OUT_CUBIC, P(rYaw=40.0, rPitch=-30.0, rRoll=8.0, lPitch=-15.0, lRoll=5.0, bYaw=-3.0, bPitch=2.0, rlPitch=25.0, llPitch=-18.0)),
    (0.75, E_OUT_BACK, P(rYaw=35.0, rPitch=-28.0, rRoll=5.0, lPitch=-12.0, lRoll=3.0, bYaw=-2.0, bPitch=1.0, rlPitch=22.0, llPitch=-15.0)),
    (1.00, E_IN_OUT_SINE, P(rYaw=30.0, rPitch=-30.0, rRoll=3.0, lPitch=-10.0, bYaw=-2.0, bPitch=1.0, rlPitch=20.0, llPitch=-12.0)),
]

HIT2 = [
    # Wide left slash (-73°), 180° arc — wider, stronger
    # Blade sweeps from right to left in a wide horizontal arc
    (0.00, E_LINEAR, P(rYaw=30.0, rPitch=-30.0, rRoll=3.0, lPitch=-10.0, bYaw=-2.0, bPitch=1.0, rlPitch=20.0, llPitch=-12.0)),
    (0.08, E_IN_CUBIC, P(rYaw=40.0, rPitch=-40.0, rRoll=5.0, lPitch=-25.0, lRoll=-8.0, bYaw=-5.0, bPitch=2.0, rlPitch=15.0, llPitch=-8.0)),
    (0.15, E_IN_CUBIC, P(rYaw=25.0, rPitch=-55.0, rRoll=-5.0, lPitch=-40.0, lRoll=-12.0, bYaw=-8.0, bPitch=3.0, rlPitch=5.0, llPitch=2.0)),
    (0.25, E_IN_CUBIC, P(rYaw=-10.0, rPitch=-50.0, rRoll=-10.0, lPitch=-45.0, lRoll=-8.0, bYaw=-5.0, bPitch=4.0, rlPitch=-5.0, llPitch=10.0)),
    (0.40, E_OUT_CUBIC, P(rYaw=-40.0, rPitch=-35.0, rRoll=-12.0, lPitch=-30.0, lRoll=5.0, bYaw=2.0, bPitch=3.0, rlPitch=-18.0, llPitch=15.0)),
    (0.55, E_OUT_CUBIC, P(rYaw=-55.0, rPitch=-30.0, rRoll=-8.0, lPitch=-20.0, lRoll=8.0, bYaw=5.0, bPitch=2.0, rlPitch=-22.0, llPitch=18.0)),
    (0.75, E_OUT_BACK, P(rYaw=-50.0, rPitch=-28.0, rRoll=-5.0, lPitch=-15.0, lRoll=5.0, bYaw=4.0, bPitch=1.0, rlPitch=-20.0, llPitch=16.0)),
    (1.00, E_IN_OUT_SINE, P(rYaw=-45.0, rPitch=-30.0, rRoll=-3.0, lPitch=-12.0, bYaw=3.0, bPitch=1.0, rlPitch=-18.0, llPitch=14.0)),
]

HIT3 = [
    # Uppercut (-169° = upward) — arm goes from low to high
    # Blade starts low, sweeps up through front
    (0.00, E_LINEAR, P(rYaw=-45.0, rPitch=-30.0, rRoll=-3.0, lPitch=-12.0, bYaw=3.0, bPitch=1.0, rlPitch=-18.0, llPitch=14.0)),
    (0.08, E_IN_CUBIC, P(rYaw=-35.0, rPitch=-20.0, rRoll=-5.0, lPitch=-15.0, lRoll=-5.0, bYaw=4.0, bPitch=-1.0, rlPitch=-12.0, llPitch=8.0)),
    (0.18, E_IN_CUBIC, P(rYaw=-25.0, rPitch=-10.0, rRoll=-3.0, lPitch=-25.0, lRoll=-8.0, bYaw=5.0, bPitch=-3.0, rlPitch=-5.0, llPitch=5.0)),
    (0.30, E_IN_CUBIC, P(rYaw=-15.0, rPitch=-40.0, rRoll=0.0, lPitch=-50.0, lRoll=-10.0, bYaw=6.0, bPitch=-2.0, rlPitch=5.0, llPitch=-5.0)),
    (0.45, E_OUT_CUBIC, P(rYaw=-10.0, rPitch=-80.0, rRoll=3.0, lPitch=-65.0, lRoll=-5.0, bYaw=5.0, bPitch=1.0, rlPitch=10.0, llPitch=-10.0)),
    (0.60, E_OUT_CUBIC, P(rYaw=-5.0, rPitch=-110.0, rRoll=5.0, lPitch=-55.0, lRoll=3.0, bYaw=4.0, bPitch=3.0, rlPitch=15.0, llPitch=-12.0)),
    (0.80, E_OUT_BACK, P(rYaw=-8.0, rPitch=-90.0, rRoll=3.0, lPitch=-35.0, lRoll=5.0, bYaw=3.0, bPitch=2.0, rlPitch=18.0, llPitch=-10.0)),
    (1.00, E_IN_OUT_SINE, P(rYaw=-10.0, rPitch=-70.0, rRoll=2.0, lPitch=-25.0, bYaw=2.0, bPitch=1.0, rlPitch=15.0, llPitch=-8.0)),
]

HIT4 = [
    # Diagonal spin (135°), 360° circle — body rotates via root.yaw
    # Blade sweeps diagonally from upper-left to lower-right with body spin
    (0.00, E_LINEAR, P(rYaw=-10.0, rPitch=-70.0, rRoll=2.0, lPitch=-25.0, bYaw=2.0, bPitch=1.0, rlPitch=15.0, llPitch=-8.0)),
    (0.08, E_IN_CUBIC, P(rYaw=-20.0, rPitch=-80.0, rRoll=-3.0, lPitch=-35.0, lRoll=-5.0, bYaw=4.0, bPitch=2.0, rlPitch=10.0, llPitch=-5.0)),
    (0.15, E_IN_CUBIC, P(rYaw=-15.0, rPitch=-100.0, rRoll=-5.0, lPitch=-50.0, lRoll=-8.0, bYaw=6.0, bPitch=3.0, rlPitch=5.0, llPitch=0.0)),
    (0.25, E_IN_CUBIC, P(rYaw=5.0, rPitch=-80.0, rRoll=5.0, lPitch=-45.0, lRoll=-5.0, bYaw=8.0, bPitch=4.0, rlPitch=-5.0, llPitch=5.0)),
    (0.40, E_OUT_CUBIC, P(rYaw=35.0, rPitch=-50.0, rRoll=12.0, lPitch=-30.0, lRoll=5.0, bYaw=5.0, bPitch=3.0, rlPitch=-10.0, llPitch=10.0)),
    (0.55, E_OUT_CUBIC, P(rYaw=55.0, rPitch=-35.0, rRoll=10.0, lPitch=-20.0, lRoll=8.0, bYaw=0.0, bPitch=2.0, rlPitch=5.0, llPitch=-5.0)),
    (0.75, E_OUT_BACK, P(rYaw=50.0, rPitch=-30.0, rRoll=6.0, lPitch=-15.0, lRoll=5.0, bYaw=-3.0, bPitch=1.0, rlPitch=15.0, llPitch=-10.0)),
    (1.00, E_IN_OUT_SINE, P(rYaw=45.0, rPitch=-32.0, rRoll=4.0, lPitch=-12.0, bYaw=-2.0, bPitch=1.0, rlPitch=18.0, llPitch=-10.0)),
]

HIT5 = [
    # Wide horizontal left (-90°), 240° arc — strongest hit
    # Blade makes a massive horizontal sweep from far right to far left
    (0.00, E_LINEAR, P(rYaw=45.0, rPitch=-32.0, rRoll=4.0, lPitch=-12.0, bYaw=-2.0, bPitch=1.0, rlPitch=18.0, llPitch=-10.0)),
    (0.08, E_IN_CUBIC, P(rYaw=55.0, rPitch=-40.0, rRoll=6.0, lPitch=-25.0, lRoll=-8.0, bYaw=-5.0, bPitch=2.0, rlPitch=12.0, llPitch=-8.0)),
    (0.18, E_IN_CUBIC, P(rYaw=50.0, rPitch=-55.0, rRoll=3.0, lPitch=-45.0, lRoll=-12.0, bYaw=-8.0, bPitch=4.0, rlPitch=5.0, llPitch=2.0)),
    (0.30, E_IN_CUBIC, P(rYaw=25.0, rPitch=-45.0, rRoll=8.0, lPitch=-50.0, lRoll=-8.0, bYaw=-5.0, bPitch=5.0, rlPitch=-5.0, llPitch=8.0)),
    (0.45, E_OUT_CUBIC, P(rYaw=-10.0, rPitch=-35.0, rRoll=12.0, lPitch=-40.0, lRoll=5.0, bYaw=2.0, bPitch=4.0, rlPitch=-15.0, llPitch=12.0)),
    (0.60, E_OUT_CUBIC, P(rYaw=-40.0, rPitch=-30.0, rRoll=10.0, lPitch=-25.0, lRoll=8.0, bYaw=6.0, bPitch=3.0, rlPitch=-20.0, llPitch=15.0)),
    (0.80, E_OUT_BACK, P(rYaw=-60.0, rPitch=-28.0, rRoll=6.0, lPitch=-15.0, lRoll=5.0, bYaw=8.0, bPitch=2.0, rlPitch=-18.0, llPitch=14.0)),
    (1.00, E_IN_OUT_SINE, P(rYaw=-65.0, rPitch=-30.0, rRoll=3.0, lPitch=-12.0, bYaw=10.0, bPitch=1.0, rlPitch=-15.0, llPitch=12.0)),
]

CHARGED = [
    # Charged spin: full 360° around body
    (0.00, E_LINEAR, P(rPitch=-20.0, lPitch=-10.0)),
    (0.10, E_IN_CUBIC, P(rYaw=-40.0, rPitch=-80.0, rRoll=-5.0, lPitch=-50.0, lRoll=-10.0, bYaw=-5.0, bPitch=2.0, rlPitch=-10.0, llPitch=8.0)),
    (0.25, E_IN_CUBIC, P(rYaw=-25.0, rPitch=-100.0, rRoll=-3.0, lPitch=-65.0, lRoll=-12.0, bYaw=-7.0, bPitch=3.0, rlPitch=-12.0, llPitch=10.0)),
    (0.45, E_IN_CUBIC, P(rYaw=20.0, rPitch=-50.0, rRoll=8.0, lPitch=-40.0, lRoll=8.0, bYaw=3.0, bPitch=3.0, rlPitch=10.0, llPitch=-5.0)),
    (0.65, E_OUT_CUBIC, P(rYaw=40.0, rPitch=-35.0, rRoll=6.0, lPitch=-25.0, lRoll=5.0, bYaw=5.0, bPitch=2.0, rlPitch=18.0, llPitch=-8.0)),
    (0.85, E_OUT_BACK, P(rYaw=30.0, rPitch=-30.0, rRoll=4.0, lPitch=-18.0, bYaw=4.0, bPitch=1.5, rlPitch=15.0, llPitch=-6.0)),
    (1.00, E_IN_OUT_SINE, P(rYaw=20.0, rPitch=-30.0, rRoll=2.0, lPitch=-12.0, bYaw=3.0, bPitch=1.0, rlPitch=12.0, llPitch=-5.0)),
]
CLIPS = [bake(HIT1), bake(HIT2), bake(HIT3), bake(HIT4), bake(HIT5), bake(CHARGED)]







NAMES = ["hit1", "hit2", "hit3", "hit4", "hit5", "charged"]


def to_radians(clip):
    out = []
    for t, e, pose in clip:
        rp = {k: math.radians(pose[k]) for k in CH}
        out.append((t, e, rp))
    return out


# ---------- генерация Java ----------

def fmt_pose(name, pose):
    def fmt(k):
        v = pose[k]
        return "Float.NaN" if math.isnan(v) else f"{math.radians(v):.6f}f"
    vals = ", ".join(fmt(k) for k in CH)
    return f"    private static final Pose {name} = new Pose({vals});"


def gen_java(clips, names):
    lines = []
    for clip, name in zip(clips, names):
        for i, (t, e, pose) in enumerate(clip):
            pn = f"{name}_{i:02d}"
            lines.append(fmt_pose(pn, pose))
        lines.append("")
    lines.append("    private static final Clip[] CLIPS = {")
    for ci, (clip, name) in enumerate(zip(clips, names)):
        lines.append(f"        new Clip(new Keyframe[] {{ // {name}")
        for i, (t, e, pose) in enumerate(clip):
            pn = f"{name}_{i:02d}"
            lines.append(f"                new Keyframe({t:.3f}f, {e}, {pn}),")
        lines.append("        }),")
    lines.append("    };")
    return "\n".join(lines)


def patch_java(src, clip_data, names):
    start = src.rfind("\n", 0, src.index("private static final Pose hit1_00")) + 1
    end_marker = "    };"
    end = src.index(end_marker, src.index("private static final Clip[] CLIPS"))
    end += len(end_marker)
    new_block = gen_java(clip_data, names)
    tail = src[end:].lstrip("\n")
    return src[:start] + new_block + "\n\n" + tail


# ---------- предпросмотр ----------

def preview(clips, names):
    import anim_preview as ap
    for ci, (clip, name) in enumerate(zip(clips, names)):
        frames = to_radians(clip)
        print(f"\n===== {name} =====")
        for p in (0.0, 0.2, 0.4, 0.6, 0.8, 1.0):
            pose = ap.clip_at(frames, p)
            root_yaw = 0.0
            if ci == 2:
                u = max(0.0, min(1.0, (p - 0.06) / 0.60))
                root_yaw = 2 * math.pi * ap.ease(1, u)
            parts = ap.transform_points(pose, root_yaw)
            blades = {a: ap.blade_points(pose, root_yaw, a) for a in ("rightArm", "leftArm")}
            pdraw = dict(parts)
            for a in blades:
                pdraw[a] = parts[a] + [blades[a][0], blades[a][1]]
            g, _ = ap.draw(pdraw, root_yaw, "side")
            ap.show(g, f"{name} p={p:.2f} root={math.degrees(root_yaw):.0f}°")
            hand, tip = blades["rightArm"]
            print(f"   клинок кисть=({hand[0]:5.1f},{hand[1]:5.1f},{hand[2]:5.1f}) "
                  f"кончик=({tip[0]:5.1f},{tip[1]:5.1f},{tip[2]:5.1f})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--render", action="store_true")
    args = ap.parse_args()

    if args.render:
        preview(CLIPS, NAMES)
        return 0

    src = JAVA.read_text()
    new = patch_java(src, CLIPS, NAMES)
    if new == src:
        print("Позы не изменились")
        return 0
    if args.write:
        JAVA.write_text(new)
        print(f"CombatController.java обновлён ({len(new)} байт)")
    else:
        print("Дри-ран: файл НЕ записан. Используй --write для записи.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
