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

Клипы ЗАПЕКАЮТСЯ: якоря из smooth-дизайна (scripts/gen_combo.py) сэмплируются
в 41 линейный кадр — в игре нет скачков углов (макс. дельта между соседними
кадрами <= ~21°), траектория клинка непрерывна и плавная.

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
CLIPS = [
    # --- УДАР 1: запечено из smooth-дизайна v6 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=0, rPitch=-20, rRoll=0, lYaw=0, lPitch=-10, lRoll=0, bYaw=0, bPitch=0, rlPitch=0, llPitch=0)),
        (0.025, E_LINEAR, P(rYaw=-7, rPitch=-23, rRoll=1, lYaw=0, lPitch=-16, lRoll=2, bYaw=-1, bPitch=0, rlPitch=-3, llPitch=2)),
        (0.050, E_LINEAR, P(rYaw=-21, rPitch=-30, rRoll=3, lYaw=0, lPitch=-27, lRoll=7, bYaw=-4, bPitch=0, rlPitch=-10, llPitch=7)),
        (0.075, E_LINEAR, P(rYaw=-30, rPitch=-35, rRoll=5, lYaw=0, lPitch=-35, lRoll=10, bYaw=-6, bPitch=0, rlPitch=-15, llPitch=10)),
        (0.100, E_LINEAR, P(rYaw=-33, rPitch=-37, rRoll=5, lYaw=0, lPitch=-38, lRoll=11, bYaw=-6, bPitch=0, rlPitch=-16, llPitch=11)),
        (0.125, E_LINEAR, P(rYaw=-42, rPitch=-46, rRoll=5, lYaw=0, lPitch=-50, lRoll=15, bYaw=-8, bPitch=0, rlPitch=-21, llPitch=14)),
        (0.150, E_LINEAR, P(rYaw=-52, rPitch=-55, rRoll=5, lYaw=0, lPitch=-63, lRoll=20, bYaw=-9, bPitch=0, rlPitch=-27, llPitch=18)),
        (0.175, E_LINEAR, P(rYaw=-58, rPitch=-60, rRoll=5, lYaw=0, lPitch=-70, lRoll=22, bYaw=-10, bPitch=0, rlPitch=-30, llPitch=20)),
        (0.200, E_LINEAR, P(rYaw=-57, rPitch=-60, rRoll=5, lYaw=-0, lPitch=-69, lRoll=21, bYaw=-10, bPitch=0, rlPitch=-29, llPitch=19)),
        (0.225, E_LINEAR, P(rYaw=-53, rPitch=-60, rRoll=3, lYaw=-0, lPitch=-67, lRoll=19, bYaw=-9, bPitch=0, rlPitch=-25, llPitch=18)),
        (0.250, E_LINEAR, P(rYaw=-47, rPitch=-59, rRoll=0, lYaw=-1, lPitch=-63, lRoll=15, bYaw=-8, bPitch=1, rlPitch=-19, llPitch=14)),
        (0.275, E_LINEAR, P(rYaw=-40, rPitch=-58, rRoll=-3, lYaw=-2, lPitch=-58, lRoll=9, bYaw=-7, bPitch=1, rlPitch=-10, llPitch=10)),
        (0.300, E_LINEAR, P(rYaw=-32, rPitch=-57, rRoll=-6, lYaw=-3, lPitch=-53, lRoll=4, bYaw=-5, bPitch=2, rlPitch=-1, llPitch=6)),
        (0.325, E_LINEAR, P(rYaw=-24, rPitch=-56, rRoll=-10, lYaw=-4, lPitch=-48, lRoll=-2, bYaw=-4, bPitch=2, rlPitch=7, llPitch=2)),
        (0.350, E_LINEAR, P(rYaw=-18, rPitch=-56, rRoll=-13, lYaw=-4, lPitch=-44, lRoll=-6, bYaw=-3, bPitch=3, rlPitch=14, llPitch=-2)),
        (0.375, E_LINEAR, P(rYaw=-13, rPitch=-55, rRoll=-14, lYaw=-5, lPitch=-41, lRoll=-9, bYaw=-2, bPitch=3, rlPitch=18, llPitch=-4)),
        (0.400, E_LINEAR, P(rYaw=-12, rPitch=-55, rRoll=-15, lYaw=-5, lPitch=-40, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=20, llPitch=-5)),
        (0.425, E_LINEAR, P(rYaw=-12, rPitch=-55, rRoll=-15, lYaw=-5, lPitch=-40, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=20, llPitch=-5)),
        (0.450, E_LINEAR, P(rYaw=-10, rPitch=-55, rRoll=-15, lYaw=-5, lPitch=-39, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=21, llPitch=-6)),
        (0.475, E_LINEAR, P(rYaw=-7, rPitch=-56, rRoll=-16, lYaw=-6, lPitch=-38, lRoll=-11, bYaw=-1, bPitch=3, rlPitch=25, llPitch=-7)),
        (0.500, E_LINEAR, P(rYaw=1, rPitch=-57, rRoll=-18, lYaw=-7, lPitch=-34, lRoll=-13, bYaw=0, bPitch=4, rlPitch=32, llPitch=-11)),
        (0.525, E_LINEAR, P(rYaw=12, rPitch=-58, rRoll=-19, lYaw=-7, lPitch=-29, lRoll=-14, bYaw=2, bPitch=4, rlPitch=39, llPitch=-14)),
        (0.550, E_LINEAR, P(rYaw=18, rPitch=-57, rRoll=-15, lYaw=-4, lPitch=-25, lRoll=-10, bYaw=4, bPitch=3, rlPitch=37, llPitch=-12)),
        (0.575, E_LINEAR, P(rYaw=22, rPitch=-57, rRoll=-12, lYaw=-2, lPitch=-22, lRoll=-7, bYaw=5, bPitch=2, rlPitch=36, llPitch=-11)),
        (0.600, E_LINEAR, P(rYaw=25, rPitch=-57, rRoll=-11, lYaw=-1, lPitch=-21, lRoll=-6, bYaw=6, bPitch=2, rlPitch=35, llPitch=-10)),
        (0.625, E_LINEAR, P(rYaw=26, rPitch=-57, rRoll=-10, lYaw=-0, lPitch=-20, lRoll=-5, bYaw=6, bPitch=2, rlPitch=35, llPitch=-10)),
        (0.650, E_LINEAR, P(rYaw=26, rPitch=-57, rRoll=-10, lYaw=-0, lPitch=-20, lRoll=-5, bYaw=6, bPitch=2, rlPitch=35, llPitch=-10)),
        (0.675, E_LINEAR, P(rYaw=28, rPitch=-57, rRoll=-7, lYaw=0, lPitch=-19, lRoll=-4, bYaw=7, bPitch=2, rlPitch=34, llPitch=-11)),
        (0.700, E_LINEAR, P(rYaw=31, rPitch=-57, rRoll=-4, lYaw=0, lPitch=-17, lRoll=-2, bYaw=7, bPitch=2, rlPitch=33, llPitch=-11)),
        (0.725, E_LINEAR, P(rYaw=32, rPitch=-57, rRoll=-2, lYaw=0, lPitch=-16, lRoll=-1, bYaw=8, bPitch=2, rlPitch=33, llPitch=-12)),
        (0.750, E_LINEAR, P(rYaw=33, rPitch=-57, rRoll=-1, lYaw=0, lPitch=-15, lRoll=-0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.775, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=-0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.800, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=-0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.825, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.850, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.875, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.900, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.925, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.950, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.975, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (1.000, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
    ],
    # --- УДАР 2: запечено из smooth-дизайна v6 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=34, rPitch=-57, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=2, rlPitch=32, llPitch=-12)),
        (0.025, E_LINEAR, P(rYaw=33, rPitch=-52, rRoll=1, lYaw=0, lPitch=-18, lRoll=2, bYaw=8, bPitch=2, rlPitch=24, llPitch=-8)),
        (0.050, E_LINEAR, P(rYaw=30, rPitch=-38, rRoll=4, lYaw=0, lPitch=-25, lRoll=7, bYaw=6, bPitch=2, rlPitch=6, llPitch=1)),
        (0.075, E_LINEAR, P(rYaw=27, rPitch=-25, rRoll=7, lYaw=0, lPitch=-32, lRoll=13, bYaw=5, bPitch=1, rlPitch=-12, llPitch=11)),
        (0.100, E_LINEAR, P(rYaw=26, rPitch=-20, rRoll=8, lYaw=0, lPitch=-35, lRoll=15, bYaw=5, bPitch=1, rlPitch=-20, llPitch=15)),
        (0.125, E_LINEAR, P(rYaw=26, rPitch=-22, rRoll=8, lYaw=0, lPitch=-39, lRoll=16, bYaw=5, bPitch=1, rlPitch=-22, llPitch=16)),
        (0.150, E_LINEAR, P(rYaw=25, rPitch=-27, rRoll=7, lYaw=0, lPitch=-50, lRoll=18, bYaw=4, bPitch=1, rlPitch=-27, llPitch=20)),
        (0.175, E_LINEAR, P(rYaw=24, rPitch=-33, rRoll=5, lYaw=0, lPitch=-61, lRoll=19, bYaw=3, bPitch=0, rlPitch=-33, llPitch=24)),
        (0.200, E_LINEAR, P(rYaw=24, rPitch=-35, rRoll=5, lYaw=0, lPitch=-65, lRoll=20, bYaw=3, bPitch=0, rlPitch=-35, llPitch=25)),
        (0.225, E_LINEAR, P(rYaw=24, rPitch=-36, rRoll=5, lYaw=0, lPitch=-65, lRoll=20, bYaw=3, bPitch=0, rlPitch=-34, llPitch=25)),
        (0.250, E_LINEAR, P(rYaw=23, rPitch=-40, rRoll=5, lYaw=0, lPitch=-64, lRoll=19, bYaw=3, bPitch=0, rlPitch=-31, llPitch=23)),
        (0.275, E_LINEAR, P(rYaw=23, rPitch=-46, rRoll=4, lYaw=1, lPitch=-62, lRoll=17, bYaw=3, bPitch=1, rlPitch=-27, llPitch=21)),
        (0.300, E_LINEAR, P(rYaw=22, rPitch=-54, rRoll=3, lYaw=2, lPitch=-61, lRoll=16, bYaw=3, bPitch=1, rlPitch=-22, llPitch=18)),
        (0.325, E_LINEAR, P(rYaw=21, rPitch=-63, rRoll=2, lYaw=3, lPitch=-58, lRoll=13, bYaw=2, bPitch=2, rlPitch=-15, llPitch=15)),
        (0.350, E_LINEAR, P(rYaw=20, rPitch=-73, rRoll=1, lYaw=4, lPitch=-56, lRoll=11, bYaw=2, bPitch=2, rlPitch=-8, llPitch=11)),
        (0.375, E_LINEAR, P(rYaw=19, rPitch=-84, rRoll=0, lYaw=5, lPitch=-54, lRoll=9, bYaw=2, bPitch=3, rlPitch=-1, llPitch=8)),
        (0.400, E_LINEAR, P(rYaw=18, rPitch=-94, rRoll=-1, lYaw=6, lPitch=-51, lRoll=6, bYaw=2, bPitch=3, rlPitch=6, llPitch=4)),
        (0.425, E_LINEAR, P(rYaw=17, rPitch=-103, rRoll=-1, lYaw=6, lPitch=-49, lRoll=4, bYaw=1, bPitch=4, rlPitch=13, llPitch=1)),
        (0.450, E_LINEAR, P(rYaw=16, rPitch=-110, rRoll=-2, lYaw=7, lPitch=-47, lRoll=2, bYaw=1, bPitch=4, rlPitch=18, llPitch=-2)),
        (0.475, E_LINEAR, P(rYaw=15, rPitch=-116, rRoll=-3, lYaw=8, lPitch=-46, lRoll=1, bYaw=1, bPitch=5, rlPitch=22, llPitch=-4)),
        (0.500, E_LINEAR, P(rYaw=15, rPitch=-119, rRoll=-3, lYaw=8, lPitch=-45, lRoll=0, bYaw=1, bPitch=5, rlPitch=24, llPitch=-5)),
        (0.525, E_LINEAR, P(rYaw=15, rPitch=-120, rRoll=-3, lYaw=8, lPitch=-45, lRoll=-0, bYaw=1, bPitch=5, rlPitch=25, llPitch=-5)),
        (0.550, E_LINEAR, P(rYaw=15, rPitch=-120, rRoll=-3, lYaw=8, lPitch=-45, lRoll=-0, bYaw=1, bPitch=5, rlPitch=25, llPitch=-5)),
        (0.575, E_LINEAR, P(rYaw=16, rPitch=-123, rRoll=-3, lYaw=8, lPitch=-44, lRoll=-1, bYaw=1, bPitch=5, rlPitch=26, llPitch=-6)),
        (0.600, E_LINEAR, P(rYaw=18, rPitch=-128, rRoll=-4, lYaw=7, lPitch=-42, lRoll=-2, bYaw=2, bPitch=4, rlPitch=29, llPitch=-8)),
        (0.625, E_LINEAR, P(rYaw=22, rPitch=-139, rRoll=-5, lYaw=7, lPitch=-38, lRoll=-5, bYaw=4, bPitch=4, rlPitch=35, llPitch=-12)),
        (0.650, E_LINEAR, P(rYaw=27, rPitch=-149, rRoll=-6, lYaw=5, lPitch=-32, lRoll=-7, bYaw=5, bPitch=2, rlPitch=38, llPitch=-14)),
        (0.675, E_LINEAR, P(rYaw=30, rPitch=-151, rRoll=-7, lYaw=3, lPitch=-27, lRoll=-5, bYaw=6, bPitch=1, rlPitch=35, llPitch=-11)),
        (0.700, E_LINEAR, P(rYaw=31, rPitch=-153, rRoll=-8, lYaw=1, lPitch=-24, lRoll=-4, bYaw=7, bPitch=1, rlPitch=32, llPitch=-10)),
        (0.725, E_LINEAR, P(rYaw=32, rPitch=-153, rRoll=-8, lYaw=1, lPitch=-22, lRoll=-4, bYaw=7, bPitch=0, rlPitch=31, llPitch=-9)),
        (0.750, E_LINEAR, P(rYaw=33, rPitch=-154, rRoll=-8, lYaw=0, lPitch=-20, lRoll=-3, bYaw=7, bPitch=0, rlPitch=30, llPitch=-8)),
        (0.775, E_LINEAR, P(rYaw=33, rPitch=-154, rRoll=-8, lYaw=0, lPitch=-20, lRoll=-3, bYaw=7, bPitch=0, rlPitch=30, llPitch=-8)),
        (0.800, E_LINEAR, P(rYaw=33, rPitch=-154, rRoll=-8, lYaw=0, lPitch=-20, lRoll=-3, bYaw=7, bPitch=0, rlPitch=30, llPitch=-8)),
        (0.825, E_LINEAR, P(rYaw=34, rPitch=-155, rRoll=-9, lYaw=0, lPitch=-18, lRoll=-0, bYaw=7, bPitch=0, rlPitch=28, llPitch=-7)),
        (0.850, E_LINEAR, P(rYaw=35, rPitch=-155, rRoll=-9, lYaw=0, lPitch=-17, lRoll=2, bYaw=7, bPitch=0, rlPitch=27, llPitch=-6)),
        (0.875, E_LINEAR, P(rYaw=35, rPitch=-156, rRoll=-10, lYaw=0, lPitch=-16, lRoll=3, bYaw=7, bPitch=0, rlPitch=26, llPitch=-6)),
        (0.900, E_LINEAR, P(rYaw=36, rPitch=-156, rRoll=-10, lYaw=0, lPitch=-16, lRoll=4, bYaw=7, bPitch=0, rlPitch=26, llPitch=-5)),
        (0.925, E_LINEAR, P(rYaw=36, rPitch=-156, rRoll=-10, lYaw=0, lPitch=-15, lRoll=5, bYaw=7, bPitch=0, rlPitch=25, llPitch=-5)),
        (0.950, E_LINEAR, P(rYaw=36, rPitch=-156, rRoll=-10, lYaw=0, lPitch=-15, lRoll=5, bYaw=7, bPitch=0, rlPitch=25, llPitch=-5)),
        (0.975, E_LINEAR, P(rYaw=36, rPitch=-156, rRoll=-10, lYaw=0, lPitch=-15, lRoll=5, bYaw=7, bPitch=0, rlPitch=25, llPitch=-5)),
        (1.000, E_LINEAR, P(rYaw=36, rPitch=-156, rRoll=-10, lYaw=0, lPitch=-15, lRoll=5, bYaw=7, bPitch=0, rlPitch=25, llPitch=-5)),
    ],
    # --- УДАР 3: запечено из smooth-дизайна v6 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=36, rPitch=-156, rRoll=-10, lYaw=0, lPitch=-15, lRoll=5, bYaw=7, bPitch=0, rlPitch=25, llPitch=-5)),
        (0.025, E_LINEAR, P(rYaw=35, rPitch=-157, rRoll=-10, lYaw=0, lPitch=-21, lRoll=6, bYaw=7, bPitch=0, rlPitch=18, llPitch=-1)),
        (0.050, E_LINEAR, P(rYaw=32, rPitch=-161, rRoll=-8, lYaw=0, lPitch=-35, lRoll=10, bYaw=6, bPitch=0, rlPitch=0, llPitch=7)),
        (0.075, E_LINEAR, P(rYaw=29, rPitch=-165, rRoll=-7, lYaw=0, lPitch=-49, lRoll=14, bYaw=5, bPitch=0, rlPitch=-18, llPitch=16)),
        (0.100, E_LINEAR, P(rYaw=28, rPitch=-166, rRoll=-7, lYaw=0, lPitch=-55, lRoll=15, bYaw=5, bPitch=0, rlPitch=-25, llPitch=20)),
        (0.125, E_LINEAR, P(rYaw=27, rPitch=-167, rRoll=-7, lYaw=0, lPitch=-58, lRoll=16, bYaw=5, bPitch=0, rlPitch=-24, llPitch=21)),
        (0.150, E_LINEAR, P(rYaw=24, rPitch=-169, rRoll=-6, lYaw=0, lPitch=-66, lRoll=18, bYaw=5, bPitch=0, rlPitch=-23, llPitch=23)),
        (0.175, E_LINEAR, P(rYaw=21, rPitch=-172, rRoll=-4, lYaw=0, lPitch=-76, lRoll=20, bYaw=4, bPitch=0, rlPitch=-22, llPitch=26)),
        (0.200, E_LINEAR, P(rYaw=19, rPitch=-173, rRoll=-3, lYaw=0, lPitch=-83, lRoll=22, bYaw=4, bPitch=0, rlPitch=-20, llPitch=27)),
        (0.225, E_LINEAR, P(rYaw=18, rPitch=-174, rRoll=-3, lYaw=0, lPitch=-85, lRoll=22, bYaw=4, bPitch=0, rlPitch=-20, llPitch=28)),
        (0.250, E_LINEAR, P(rYaw=18, rPitch=-172, rRoll=-3, lYaw=0, lPitch=-84, lRoll=21, bYaw=4, bPitch=0, rlPitch=-19, llPitch=27)),
        (0.275, E_LINEAR, P(rYaw=17, rPitch=-168, rRoll=-3, lYaw=1, lPitch=-82, lRoll=20, bYaw=4, bPitch=0, rlPitch=-17, llPitch=26)),
        (0.300, E_LINEAR, P(rYaw=17, rPitch=-162, rRoll=-4, lYaw=1, lPitch=-79, lRoll=18, bYaw=3, bPitch=1, rlPitch=-14, llPitch=24)),
        (0.325, E_LINEAR, P(rYaw=16, rPitch=-155, rRoll=-4, lYaw=2, lPitch=-75, lRoll=15, bYaw=3, bPitch=1, rlPitch=-10, llPitch=21)),
        (0.350, E_LINEAR, P(rYaw=15, rPitch=-146, rRoll=-5, lYaw=3, lPitch=-71, lRoll=12, bYaw=3, bPitch=2, rlPitch=-6, llPitch=18)),
        (0.375, E_LINEAR, P(rYaw=14, rPitch=-136, rRoll=-5, lYaw=4, lPitch=-66, lRoll=9, bYaw=2, bPitch=2, rlPitch=-1, llPitch=15)),
        (0.400, E_LINEAR, P(rYaw=13, rPitch=-127, rRoll=-6, lYaw=5, lPitch=-61, lRoll=6, bYaw=2, bPitch=3, rlPitch=4, llPitch=11)),
        (0.425, E_LINEAR, P(rYaw=12, rPitch=-118, rRoll=-7, lYaw=6, lPitch=-56, lRoll=3, bYaw=1, bPitch=4, rlPitch=9, llPitch=8)),
        (0.450, E_LINEAR, P(rYaw=11, rPitch=-109, rRoll=-7, lYaw=7, lPitch=-52, lRoll=-0, bYaw=1, bPitch=4, rlPitch=13, llPitch=5)),
        (0.475, E_LINEAR, P(rYaw=11, rPitch=-103, rRoll=-8, lYaw=7, lPitch=-49, lRoll=-2, bYaw=0, bPitch=5, rlPitch=16, llPitch=3)),
        (0.500, E_LINEAR, P(rYaw=10, rPitch=-98, rRoll=-8, lYaw=8, lPitch=-47, lRoll=-4, bYaw=0, bPitch=5, rlPitch=18, llPitch=1)),
        (0.525, E_LINEAR, P(rYaw=10, rPitch=-95, rRoll=-8, lYaw=8, lPitch=-45, lRoll=-5, bYaw=0, bPitch=5, rlPitch=20, llPitch=0)),
        (0.550, E_LINEAR, P(rYaw=10, rPitch=-95, rRoll=-8, lYaw=8, lPitch=-45, lRoll=-5, bYaw=-0, bPitch=5, rlPitch=20, llPitch=-0)),
        (0.575, E_LINEAR, P(rYaw=10, rPitch=-94, rRoll=-8, lYaw=8, lPitch=-45, lRoll=-5, bYaw=-0, bPitch=5, rlPitch=20, llPitch=-0)),
        (0.600, E_LINEAR, P(rYaw=9, rPitch=-91, rRoll=-8, lYaw=7, lPitch=-43, lRoll=-5, bYaw=-0, bPitch=5, rlPitch=22, llPitch=-2)),
        (0.625, E_LINEAR, P(rYaw=8, rPitch=-83, rRoll=-7, lYaw=5, lPitch=-40, lRoll=-5, bYaw=-1, bPitch=4, rlPitch=27, llPitch=-5)),
        (0.650, E_LINEAR, P(rYaw=5, rPitch=-68, rRoll=-6, lYaw=2, lPitch=-33, lRoll=-5, bYaw=-2, bPitch=3, rlPitch=35, llPitch=-12)),
        (0.675, E_LINEAR, P(rYaw=5, rPitch=-61, rRoll=-4, lYaw=0, lPitch=-27, lRoll=-4, bYaw=-4, bPitch=2, rlPitch=37, llPitch=-13)),
        (0.700, E_LINEAR, P(rYaw=7, rPitch=-63, rRoll=-2, lYaw=0, lPitch=-24, lRoll=-2, bYaw=-5, bPitch=1, rlPitch=32, llPitch=-11)),
        (0.725, E_LINEAR, P(rYaw=7, rPitch=-64, rRoll=-1, lYaw=0, lPitch=-22, lRoll=-1, bYaw=-6, bPitch=0, rlPitch=30, llPitch=-9)),
        (0.750, E_LINEAR, P(rYaw=8, rPitch=-65, rRoll=-0, lYaw=0, lPitch=-20, lRoll=-0, bYaw=-6, bPitch=0, rlPitch=29, llPitch=-8)),
        (0.775, E_LINEAR, P(rYaw=8, rPitch=-65, rRoll=-0, lYaw=0, lPitch=-20, lRoll=-0, bYaw=-6, bPitch=0, rlPitch=28, llPitch=-8)),
        (0.800, E_LINEAR, P(rYaw=8, rPitch=-65, rRoll=0, lYaw=0, lPitch=-20, lRoll=0, bYaw=-6, bPitch=0, rlPitch=28, llPitch=-8)),
        (0.825, E_LINEAR, P(rYaw=9, rPitch=-69, rRoll=3, lYaw=0, lPitch=-17, lRoll=0, bYaw=-7, bPitch=0, rlPitch=23, llPitch=-3)),
        (0.850, E_LINEAR, P(rYaw=10, rPitch=-71, rRoll=4, lYaw=0, lPitch=-16, lRoll=0, bYaw=-8, bPitch=0, rlPitch=21, llPitch=-1)),
        (0.875, E_LINEAR, P(rYaw=10, rPitch=-72, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=-0)),
        (0.900, E_LINEAR, P(rYaw=10, rPitch=-72, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.925, E_LINEAR, P(rYaw=10, rPitch=-72, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.950, E_LINEAR, P(rYaw=10, rPitch=-72, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.975, E_LINEAR, P(rYaw=10, rPitch=-72, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (1.000, E_LINEAR, P(rYaw=10, rPitch=-72, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
    ],
    # --- УДАР 4: запечено из smooth-дизайна v6 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=10, rPitch=-72, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.025, E_LINEAR, P(rYaw=13, rPitch=-78, rRoll=3, lYaw=0, lPitch=-22, lRoll=3, bYaw=-9, bPitch=0, rlPitch=12, llPitch=3)),
        (0.050, E_LINEAR, P(rYaw=20, rPitch=-91, rRoll=-2, lYaw=0, lPitch=-36, lRoll=10, bYaw=-11, bPitch=0, rlPitch=-4, llPitch=8)),
        (0.075, E_LINEAR, P(rYaw=25, rPitch=-100, rRoll=-5, lYaw=0, lPitch=-45, lRoll=15, bYaw=-12, bPitch=0, rlPitch=-15, llPitch=12)),
        (0.100, E_LINEAR, P(rYaw=26, rPitch=-104, rRoll=-5, lYaw=0, lPitch=-48, lRoll=16, bYaw=-12, bPitch=0, rlPitch=-16, llPitch=13)),
        (0.125, E_LINEAR, P(rYaw=29, rPitch=-119, rRoll=-6, lYaw=0, lPitch=-60, lRoll=18, bYaw=-12, bPitch=0, rlPitch=-21, llPitch=15)),
        (0.150, E_LINEAR, P(rYaw=32, rPitch=-136, rRoll=-7, lYaw=0, lPitch=-73, lRoll=21, bYaw=-13, bPitch=0, rlPitch=-27, llPitch=18)),
        (0.175, E_LINEAR, P(rYaw=34, rPitch=-145, rRoll=-8, lYaw=0, lPitch=-80, lRoll=22, bYaw=-13, bPitch=0, rlPitch=-30, llPitch=20)),
        (0.200, E_LINEAR, P(rYaw=34, rPitch=-143, rRoll=-8, lYaw=0, lPitch=-79, lRoll=21, bYaw=-13, bPitch=0, rlPitch=-29, llPitch=19)),
        (0.225, E_LINEAR, P(rYaw=32, rPitch=-136, rRoll=-6, lYaw=1, lPitch=-76, lRoll=19, bYaw=-12, bPitch=0, rlPitch=-25, llPitch=18)),
        (0.250, E_LINEAR, P(rYaw=29, rPitch=-124, rRoll=-3, lYaw=2, lPitch=-71, lRoll=15, bYaw=-10, bPitch=1, rlPitch=-19, llPitch=14)),
        (0.275, E_LINEAR, P(rYaw=25, rPitch=-110, rRoll=1, lYaw=3, lPitch=-64, lRoll=9, bYaw=-9, bPitch=1, rlPitch=-10, llPitch=10)),
        (0.300, E_LINEAR, P(rYaw=21, rPitch=-94, rRoll=5, lYaw=5, lPitch=-57, lRoll=4, bYaw=-7, bPitch=2, rlPitch=-1, llPitch=6)),
        (0.325, E_LINEAR, P(rYaw=18, rPitch=-78, rRoll=9, lYaw=6, lPitch=-50, lRoll=-2, bYaw=-5, bPitch=2, rlPitch=7, llPitch=2)),
        (0.350, E_LINEAR, P(rYaw=15, rPitch=-66, rRoll=12, lYaw=7, lPitch=-45, lRoll=-6, bYaw=-3, bPitch=3, rlPitch=14, llPitch=-2)),
        (0.375, E_LINEAR, P(rYaw=13, rPitch=-58, rRoll=14, lYaw=8, lPitch=-41, lRoll=-9, bYaw=-2, bPitch=3, rlPitch=18, llPitch=-4)),
        (0.400, E_LINEAR, P(rYaw=12, rPitch=-55, rRoll=15, lYaw=8, lPitch=-40, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=20, llPitch=-5)),
        (0.425, E_LINEAR, P(rYaw=12, rPitch=-55, rRoll=15, lYaw=8, lPitch=-40, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=20, llPitch=-5)),
        (0.450, E_LINEAR, P(rYaw=10, rPitch=-55, rRoll=15, lYaw=8, lPitch=-39, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=21, llPitch=-6)),
        (0.475, E_LINEAR, P(rYaw=6, rPitch=-56, rRoll=16, lYaw=8, lPitch=-38, lRoll=-11, bYaw=-1, bPitch=3, rlPitch=25, llPitch=-7)),
        (0.500, E_LINEAR, P(rYaw=-2, rPitch=-57, rRoll=17, lYaw=7, lPitch=-34, lRoll=-13, bYaw=0, bPitch=4, rlPitch=32, llPitch=-11)),
        (0.525, E_LINEAR, P(rYaw=-14, rPitch=-58, rRoll=17, lYaw=5, lPitch=-29, lRoll=-14, bYaw=2, bPitch=4, rlPitch=39, llPitch=-14)),
        (0.550, E_LINEAR, P(rYaw=-20, rPitch=-60, rRoll=14, lYaw=3, lPitch=-25, lRoll=-10, bYaw=4, bPitch=3, rlPitch=36, llPitch=-12)),
        (0.575, E_LINEAR, P(rYaw=-24, rPitch=-61, rRoll=12, lYaw=1, lPitch=-22, lRoll=-7, bYaw=5, bPitch=2, rlPitch=34, llPitch=-11)),
        (0.600, E_LINEAR, P(rYaw=-27, rPitch=-62, rRoll=11, lYaw=0, lPitch=-21, lRoll=-6, bYaw=6, bPitch=2, rlPitch=33, llPitch=-10)),
        (0.625, E_LINEAR, P(rYaw=-28, rPitch=-62, rRoll=10, lYaw=0, lPitch=-20, lRoll=-5, bYaw=6, bPitch=2, rlPitch=32, llPitch=-10)),
        (0.650, E_LINEAR, P(rYaw=-28, rPitch=-62, rRoll=10, lYaw=0, lPitch=-20, lRoll=-5, bYaw=6, bPitch=2, rlPitch=32, llPitch=-10)),
        (0.675, E_LINEAR, P(rYaw=-30, rPitch=-65, rRoll=9, lYaw=0, lPitch=-19, lRoll=-4, bYaw=7, bPitch=1, rlPitch=29, llPitch=-7)),
        (0.700, E_LINEAR, P(rYaw=-33, rPitch=-69, rRoll=9, lYaw=0, lPitch=-17, lRoll=-2, bYaw=8, bPitch=1, rlPitch=26, llPitch=-4)),
        (0.725, E_LINEAR, P(rYaw=-34, rPitch=-71, rRoll=8, lYaw=0, lPitch=-16, lRoll=-1, bYaw=8, bPitch=0, rlPitch=24, llPitch=-2)),
        (0.750, E_LINEAR, P(rYaw=-35, rPitch=-73, rRoll=8, lYaw=0, lPitch=-15, lRoll=-0, bYaw=9, bPitch=0, rlPitch=23, llPitch=-1)),
        (0.775, E_LINEAR, P(rYaw=-36, rPitch=-74, rRoll=8, lYaw=0, lPitch=-15, lRoll=-0, bYaw=9, bPitch=0, rlPitch=22, llPitch=-0)),
        (0.800, E_LINEAR, P(rYaw=-36, rPitch=-74, rRoll=8, lYaw=0, lPitch=-15, lRoll=-0, bYaw=9, bPitch=0, rlPitch=22, llPitch=-0)),
        (0.825, E_LINEAR, P(rYaw=-36, rPitch=-74, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=9, bPitch=0, rlPitch=22, llPitch=0)),
        (0.850, E_LINEAR, P(rYaw=-37, rPitch=-76, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=9, bPitch=0, rlPitch=21, llPitch=0)),
        (0.875, E_LINEAR, P(rYaw=-38, rPitch=-77, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=9, bPitch=0, rlPitch=20, llPitch=0)),
        (0.900, E_LINEAR, P(rYaw=-39, rPitch=-79, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=9, bPitch=0, rlPitch=19, llPitch=0)),
        (0.925, E_LINEAR, P(rYaw=-40, rPitch=-80, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=18, llPitch=0)),
        (0.950, E_LINEAR, P(rYaw=-40, rPitch=-82, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=17, llPitch=0)),
        (0.975, E_LINEAR, P(rYaw=-41, rPitch=-83, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=16, llPitch=0)),
        (1.000, E_LINEAR, P(rYaw=-42, rPitch=-85, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=15, llPitch=0)),
    ],
    # --- УДАР 5: запечено из smooth-дизайна v6 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=-42, rPitch=-85, rRoll=8, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=15, llPitch=0)),
        (0.025, E_LINEAR, P(rYaw=-46, rPitch=-90, rRoll=7, lYaw=0, lPitch=-26, lRoll=5, bYaw=11, bPitch=0, rlPitch=4, llPitch=5)),
        (0.050, E_LINEAR, P(rYaw=-49, rPitch=-94, rRoll=7, lYaw=0, lPitch=-36, lRoll=11, bYaw=11, bPitch=0, rlPitch=-6, llPitch=11)),
        (0.075, E_LINEAR, P(rYaw=-53, rPitch=-99, rRoll=6, lYaw=0, lPitch=-47, lRoll=15, bYaw=12, bPitch=0, rlPitch=-16, llPitch=15)),
        (0.100, E_LINEAR, P(rYaw=-57, rPitch=-102, rRoll=5, lYaw=0, lPitch=-58, lRoll=18, bYaw=12, bPitch=1, rlPitch=-21, llPitch=18)),
        (0.125, E_LINEAR, P(rYaw=-61, rPitch=-106, rRoll=4, lYaw=0, lPitch=-69, lRoll=20, bYaw=13, bPitch=2, rlPitch=-27, llPitch=20)),
        (0.150, E_LINEAR, P(rYaw=-65, rPitch=-109, rRoll=4, lYaw=0, lPitch=-78, lRoll=23, bYaw=13, bPitch=2, rlPitch=-31, llPitch=23)),
        (0.175, E_LINEAR, P(rYaw=-68, rPitch=-110, rRoll=3, lYaw=0, lPitch=-84, lRoll=24, bYaw=13, bPitch=2, rlPitch=-34, llPitch=24)),
        (0.200, E_LINEAR, P(rYaw=-70, rPitch=-112, rRoll=2, lYaw=0, lPitch=-90, lRoll=26, bYaw=13, bPitch=3, rlPitch=-36, llPitch=26)),
        (0.225, E_LINEAR, P(rYaw=-72, rPitch=-114, rRoll=1, lYaw=0, lPitch=-96, lRoll=27, bYaw=13, bPitch=3, rlPitch=-38, llPitch=27)),
        (0.250, E_LINEAR, P(rYaw=-74, rPitch=-115, rRoll=-0, lYaw=0, lPitch=-100, lRoll=28, bYaw=13, bPitch=3, rlPitch=-40, llPitch=28)),
        (0.275, E_LINEAR, P(rYaw=-71, rPitch=-113, rRoll=-1, lYaw=0, lPitch=-97, lRoll=26, bYaw=12, bPitch=3, rlPitch=-37, llPitch=27)),
        (0.300, E_LINEAR, P(rYaw=-66, rPitch=-108, rRoll=-2, lYaw=1, lPitch=-92, lRoll=23, bYaw=11, bPitch=4, rlPitch=-31, llPitch=24)),
        (0.325, E_LINEAR, P(rYaw=-59, rPitch=-102, rRoll=-4, lYaw=2, lPitch=-86, lRoll=19, bYaw=10, bPitch=4, rlPitch=-23, llPitch=21)),
        (0.350, E_LINEAR, P(rYaw=-50, rPitch=-94, rRoll=-6, lYaw=3, lPitch=-77, lRoll=14, bYaw=8, bPitch=5, rlPitch=-13, llPitch=17)),
        (0.375, E_LINEAR, P(rYaw=-41, rPitch=-86, rRoll=-8, lYaw=4, lPitch=-68, lRoll=8, bYaw=6, bPitch=6, rlPitch=-3, llPitch=13)),
        (0.400, E_LINEAR, P(rYaw=-32, rPitch=-78, rRoll=-10, lYaw=5, lPitch=-59, lRoll=2, bYaw=4, bPitch=6, rlPitch=7, llPitch=9)),
        (0.425, E_LINEAR, P(rYaw=-24, rPitch=-71, rRoll=-12, lYaw=6, lPitch=-51, lRoll=-3, bYaw=2, bPitch=7, rlPitch=17, llPitch=5)),
        (0.450, E_LINEAR, P(rYaw=-17, rPitch=-65, rRoll=-14, lYaw=7, lPitch=-45, lRoll=-7, bYaw=1, bPitch=8, rlPitch=24, llPitch=2)),
        (0.475, E_LINEAR, P(rYaw=-13, rPitch=-61, rRoll=-15, lYaw=8, lPitch=-41, lRoll=-9, bYaw=0, bPitch=8, rlPitch=28, llPitch=1)),
        (0.500, E_LINEAR, P(rYaw=-12, rPitch=-60, rRoll=-15, lYaw=8, lPitch=-40, lRoll=-10, bYaw=0, bPitch=8, rlPitch=30, llPitch=0)),
        (0.525, E_LINEAR, P(rYaw=-12, rPitch=-60, rRoll=-15, lYaw=8, lPitch=-40, lRoll=-10, bYaw=-0, bPitch=8, rlPitch=30, llPitch=-0)),
        (0.550, E_LINEAR, P(rYaw=-10, rPitch=-60, rRoll=-15, lYaw=8, lPitch=-39, lRoll=-10, bYaw=-0, bPitch=8, rlPitch=32, llPitch=-1)),
        (0.575, E_LINEAR, P(rYaw=-7, rPitch=-60, rRoll=-16, lYaw=8, lPitch=-38, lRoll=-11, bYaw=-1, bPitch=8, rlPitch=36, llPitch=-5)),
        (0.600, E_LINEAR, P(rYaw=1, rPitch=-60, rRoll=-17, lYaw=7, lPitch=-34, lRoll=-13, bYaw=-3, bPitch=7, rlPitch=44, llPitch=-12)),
        (0.625, E_LINEAR, P(rYaw=11, rPitch=-60, rRoll=-17, lYaw=5, lPitch=-29, lRoll=-14, bYaw=-5, bPitch=7, rlPitch=54, llPitch=-19)),
        (0.650, E_LINEAR, P(rYaw=17, rPitch=-59, rRoll=-13, lYaw=3, lPitch=-25, lRoll=-10, bYaw=-7, bPitch=5, rlPitch=50, llPitch=-16)),
        (0.675, E_LINEAR, P(rYaw=21, rPitch=-58, rRoll=-10, lYaw=1, lPitch=-22, lRoll=-7, bYaw=-8, bPitch=5, rlPitch=47, llPitch=-14)),
        (0.700, E_LINEAR, P(rYaw=23, rPitch=-58, rRoll=-9, lYaw=0, lPitch=-21, lRoll=-6, bYaw=-9, bPitch=4, rlPitch=46, llPitch=-13)),
        (0.725, E_LINEAR, P(rYaw=24, rPitch=-58, rRoll=-8, lYaw=0, lPitch=-20, lRoll=-5, bYaw=-9, bPitch=4, rlPitch=45, llPitch=-12)),
        (0.750, E_LINEAR, P(rYaw=24, rPitch=-58, rRoll=-8, lYaw=0, lPitch=-20, lRoll=-5, bYaw=-9, bPitch=4, rlPitch=45, llPitch=-12)),
        (0.775, E_LINEAR, P(rYaw=27, rPitch=-58, rRoll=-4, lYaw=0, lPitch=-18, lRoll=-3, bYaw=-9, bPitch=4, rlPitch=40, llPitch=-10)),
        (0.800, E_LINEAR, P(rYaw=29, rPitch=-58, rRoll=-1, lYaw=0, lPitch=-16, lRoll=-1, bYaw=-10, bPitch=3, rlPitch=36, llPitch=-8)),
        (0.825, E_LINEAR, P(rYaw=30, rPitch=-58, rRoll=-0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=-10, bPitch=3, rlPitch=35, llPitch=-8)),
        (0.850, E_LINEAR, P(rYaw=30, rPitch=-58, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-10, bPitch=3, rlPitch=35, llPitch=-8)),
        (0.875, E_LINEAR, P(rYaw=30, rPitch=-58, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-10, bPitch=3, rlPitch=34, llPitch=-5)),
        (0.900, E_LINEAR, P(rYaw=30, rPitch=-58, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-10, bPitch=4, rlPitch=32, llPitch=-1)),
        (0.925, E_LINEAR, P(rYaw=30, rPitch=-58, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-10, bPitch=4, rlPitch=32, llPitch=-0)),
        (0.950, E_LINEAR, P(rYaw=30, rPitch=-58, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-10, bPitch=3, rlPitch=31, llPitch=-2)),
        (0.975, E_LINEAR, P(rYaw=30, rPitch=-58, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-10, bPitch=3, rlPitch=31, llPitch=-4)),
        (1.000, E_LINEAR, P(rYaw=30, rPitch=-58, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-10, bPitch=2, rlPitch=30, llPitch=-6)),
    ],
]

NAMES = ["hit1", "hit2", "hit3", "hit4", "hit5"]


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
