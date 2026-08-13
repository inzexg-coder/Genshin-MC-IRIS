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
    # --- УДАР 1: запечено из smooth-дизайна (41 кадр, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=-10, rPitch=-40, rRoll=0, lYaw=0, lPitch=-5, lRoll=0, bYaw=-4, bPitch=0, rlPitch=0, llPitch=0)),
        (0.025, E_LINEAR, P(rYaw=-15, rPitch=-41, rRoll=1, lYaw=0, lPitch=-11, lRoll=2, bYaw=-5, bPitch=0, rlPitch=-3, llPitch=2)),
        (0.050, E_LINEAR, P(rYaw=-27, rPitch=-42, rRoll=4, lYaw=0, lPitch=-25, lRoll=7, bYaw=-6, bPitch=0, rlPitch=-10, llPitch=7)),
        (0.075, E_LINEAR, P(rYaw=-40, rPitch=-44, rRoll=7, lYaw=0, lPitch=-39, lRoll=13, bYaw=-7, bPitch=0, rlPitch=-17, llPitch=13)),
        (0.100, E_LINEAR, P(rYaw=-45, rPitch=-45, rRoll=8, lYaw=0, lPitch=-45, lRoll=15, bYaw=-8, bPitch=0, rlPitch=-20, llPitch=15)),
        (0.125, E_LINEAR, P(rYaw=-47, rPitch=-47, rRoll=8, lYaw=0, lPitch=-49, lRoll=16, bYaw=-8, bPitch=0, rlPitch=-22, llPitch=16)),
        (0.150, E_LINEAR, P(rYaw=-53, rPitch=-52, rRoll=7, lYaw=0, lPitch=-60, lRoll=20, bYaw=-9, bPitch=0, rlPitch=-27, llPitch=20)),
        (0.175, E_LINEAR, P(rYaw=-60, rPitch=-58, rRoll=5, lYaw=0, lPitch=-71, lRoll=24, bYaw=-10, bPitch=0, rlPitch=-33, llPitch=24)),
        (0.200, E_LINEAR, P(rYaw=-62, rPitch=-60, rRoll=5, lYaw=0, lPitch=-75, lRoll=25, bYaw=-10, bPitch=0, rlPitch=-35, llPitch=25)),
        (0.225, E_LINEAR, P(rYaw=-60, rPitch=-60, rRoll=4, lYaw=-0, lPitch=-74, lRoll=24, bYaw=-10, bPitch=0, rlPitch=-33, llPitch=24)),
        (0.250, E_LINEAR, P(rYaw=-56, rPitch=-59, rRoll=3, lYaw=-1, lPitch=-71, lRoll=21, bYaw=-9, bPitch=0, rlPitch=-28, llPitch=21)),
        (0.275, E_LINEAR, P(rYaw=-49, rPitch=-58, rRoll=-0, lYaw=-1, lPitch=-66, lRoll=16, bYaw=-8, bPitch=1, rlPitch=-21, llPitch=17)),
        (0.300, E_LINEAR, P(rYaw=-41, rPitch=-57, rRoll=-4, lYaw=-2, lPitch=-60, lRoll=10, bYaw=-7, bPitch=1, rlPitch=-11, llPitch=12)),
        (0.325, E_LINEAR, P(rYaw=-32, rPitch=-55, rRoll=-7, lYaw=-3, lPitch=-54, lRoll=4, bYaw=-5, bPitch=2, rlPitch=-2, llPitch=7)),
        (0.350, E_LINEAR, P(rYaw=-23, rPitch=-54, rRoll=-10, lYaw=-4, lPitch=-48, lRoll=-2, bYaw=-4, bPitch=2, rlPitch=7, llPitch=2)),
        (0.375, E_LINEAR, P(rYaw=-17, rPitch=-53, rRoll=-13, lYaw=-5, lPitch=-43, lRoll=-7, bYaw=-3, bPitch=3, rlPitch=15, llPitch=-2)),
        (0.400, E_LINEAR, P(rYaw=-13, rPitch=-52, rRoll=-15, lYaw=-5, lPitch=-41, lRoll=-9, bYaw=-2, bPitch=3, rlPitch=19, llPitch=-4)),
        (0.425, E_LINEAR, P(rYaw=-12, rPitch=-52, rRoll=-15, lYaw=-5, lPitch=-40, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=20, llPitch=-5)),
        (0.450, E_LINEAR, P(rYaw=-12, rPitch=-52, rRoll=-15, lYaw=-5, lPitch=-40, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=20, llPitch=-5)),
        (0.475, E_LINEAR, P(rYaw=-11, rPitch=-52, rRoll=-15, lYaw=-5, lPitch=-39, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=22, llPitch=-6)),
        (0.500, E_LINEAR, P(rYaw=-8, rPitch=-53, rRoll=-16, lYaw=-6, lPitch=-38, lRoll=-11, bYaw=-1, bPitch=3, rlPitch=25, llPitch=-8)),
        (0.525, E_LINEAR, P(rYaw=-3, rPitch=-53, rRoll=-17, lYaw=-6, lPitch=-36, lRoll=-12, bYaw=-0, bPitch=3, rlPitch=31, llPitch=-11)),
        (0.550, E_LINEAR, P(rYaw=6, rPitch=-54, rRoll=-19, lYaw=-7, lPitch=-32, lRoll=-14, bYaw=1, bPitch=4, rlPitch=40, llPitch=-17)),
        (0.575, E_LINEAR, P(rYaw=15, rPitch=-53, rRoll=-17, lYaw=-6, lPitch=-27, lRoll=-12, bYaw=3, bPitch=3, rlPitch=42, llPitch=-17)),
        (0.600, E_LINEAR, P(rYaw=20, rPitch=-51, rRoll=-14, lYaw=-3, lPitch=-24, lRoll=-9, bYaw=5, bPitch=3, rlPitch=39, llPitch=-14)),
        (0.625, E_LINEAR, P(rYaw=24, rPitch=-49, rRoll=-12, lYaw=-1, lPitch=-22, lRoll=-7, bYaw=6, bPitch=2, rlPitch=37, llPitch=-12)),
        (0.650, E_LINEAR, P(rYaw=25, rPitch=-48, rRoll=-10, lYaw=-0, lPitch=-20, lRoll=-5, bYaw=7, bPitch=2, rlPitch=35, llPitch=-10)),
        (0.675, E_LINEAR, P(rYaw=26, rPitch=-48, rRoll=-10, lYaw=-0, lPitch=-20, lRoll=-5, bYaw=7, bPitch=2, rlPitch=35, llPitch=-10)),
        (0.700, E_LINEAR, P(rYaw=26, rPitch=-48, rRoll=-10, lYaw=0, lPitch=-20, lRoll=-5, bYaw=7, bPitch=2, rlPitch=35, llPitch=-10)),
        (0.725, E_LINEAR, P(rYaw=30, rPitch=-48, rRoll=-6, lYaw=0, lPitch=-18, lRoll=-3, bYaw=7, bPitch=1, rlPitch=28, llPitch=-6)),
        (0.750, E_LINEAR, P(rYaw=33, rPitch=-48, rRoll=-3, lYaw=0, lPitch=-16, lRoll=-1, bYaw=8, bPitch=1, rlPitch=24, llPitch=-3)),
        (0.775, E_LINEAR, P(rYaw=35, rPitch=-48, rRoll=-1, lYaw=0, lPitch=-16, lRoll=-1, bYaw=8, bPitch=0, rlPitch=22, llPitch=-1)),
        (0.800, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=-0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=8, bPitch=0, rlPitch=20, llPitch=-0)),
        (0.825, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=-0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=8, bPitch=0, rlPitch=20, llPitch=-0)),
        (0.850, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.875, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.900, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.925, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.950, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.975, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (1.000, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
    ],
    # --- УДАР 2: запечено из smooth-дизайна (41 кадр, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=36, rPitch=-48, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.025, E_LINEAR, P(rYaw=35, rPitch=-43, rRoll=1, lYaw=0, lPitch=-19, lRoll=2, bYaw=8, bPitch=0, rlPitch=13, llPitch=2)),
        (0.050, E_LINEAR, P(rYaw=32, rPitch=-32, rRoll=5, lYaw=0, lPitch=-28, lRoll=7, bYaw=7, bPitch=0, rlPitch=-2, llPitch=7)),
        (0.075, E_LINEAR, P(rYaw=29, rPitch=-20, rRoll=9, lYaw=0, lPitch=-36, lRoll=13, bYaw=6, bPitch=0, rlPitch=-18, llPitch=13)),
        (0.100, E_LINEAR, P(rYaw=28, rPitch=-15, rRoll=10, lYaw=0, lPitch=-40, lRoll=15, bYaw=6, bPitch=0, rlPitch=-25, llPitch=15)),
        (0.125, E_LINEAR, P(rYaw=27, rPitch=-17, rRoll=9, lYaw=0, lPitch=-44, lRoll=16, bYaw=6, bPitch=0, rlPitch=-27, llPitch=16)),
        (0.150, E_LINEAR, P(rYaw=25, rPitch=-22, rRoll=8, lYaw=0, lPitch=-55, lRoll=18, bYaw=5, bPitch=0, rlPitch=-32, llPitch=20)),
        (0.175, E_LINEAR, P(rYaw=23, rPitch=-28, rRoll=6, lYaw=0, lPitch=-66, lRoll=19, bYaw=4, bPitch=0, rlPitch=-38, llPitch=24)),
        (0.200, E_LINEAR, P(rYaw=22, rPitch=-30, rRoll=5, lYaw=0, lPitch=-70, lRoll=20, bYaw=4, bPitch=0, rlPitch=-40, llPitch=25)),
        (0.225, E_LINEAR, P(rYaw=22, rPitch=-31, rRoll=5, lYaw=0, lPitch=-69, lRoll=20, bYaw=4, bPitch=0, rlPitch=-39, llPitch=24)),
        (0.250, E_LINEAR, P(rYaw=21, rPitch=-36, rRoll=4, lYaw=0, lPitch=-67, lRoll=18, bYaw=4, bPitch=0, rlPitch=-36, llPitch=23)),
        (0.275, E_LINEAR, P(rYaw=19, rPitch=-42, rRoll=4, lYaw=1, lPitch=-64, lRoll=16, bYaw=4, bPitch=1, rlPitch=-31, llPitch=20)),
        (0.300, E_LINEAR, P(rYaw=17, rPitch=-51, rRoll=3, lYaw=2, lPitch=-60, lRoll=13, bYaw=4, bPitch=1, rlPitch=-24, llPitch=17)),
        (0.325, E_LINEAR, P(rYaw=14, rPitch=-61, rRoll=2, lYaw=3, lPitch=-55, lRoll=10, bYaw=4, bPitch=2, rlPitch=-17, llPitch=13)),
        (0.350, E_LINEAR, P(rYaw=11, rPitch=-73, rRoll=0, lYaw=4, lPitch=-50, lRoll=6, bYaw=4, bPitch=3, rlPitch=-8, llPitch=9)),
        (0.375, E_LINEAR, P(rYaw=8, rPitch=-84, rRoll=-1, lYaw=5, lPitch=-44, lRoll=3, bYaw=3, bPitch=3, rlPitch=0, llPitch=5)),
        (0.400, E_LINEAR, P(rYaw=5, rPitch=-96, rRoll=-2, lYaw=6, lPitch=-39, lRoll=-1, bYaw=3, bPitch=4, rlPitch=8, llPitch=1)),
        (0.425, E_LINEAR, P(rYaw=3, rPitch=-106, rRoll=-3, lYaw=6, lPitch=-34, lRoll=-4, bYaw=3, bPitch=5, rlPitch=16, llPitch=-3)),
        (0.450, E_LINEAR, P(rYaw=1, rPitch=-114, rRoll=-4, lYaw=7, lPitch=-30, lRoll=-7, bYaw=3, bPitch=5, rlPitch=22, llPitch=-6)),
        (0.475, E_LINEAR, P(rYaw=-1, rPitch=-120, rRoll=-5, lYaw=8, lPitch=-27, lRoll=-9, bYaw=3, bPitch=6, rlPitch=27, llPitch=-8)),
        (0.500, E_LINEAR, P(rYaw=-2, rPitch=-124, rRoll=-5, lYaw=8, lPitch=-25, lRoll=-10, bYaw=3, bPitch=6, rlPitch=29, llPitch=-10)),
        (0.525, E_LINEAR, P(rYaw=-2, rPitch=-125, rRoll=-5, lYaw=8, lPitch=-25, lRoll=-10, bYaw=3, bPitch=6, rlPitch=30, llPitch=-10)),
        (0.550, E_LINEAR, P(rYaw=-2, rPitch=-125, rRoll=-5, lYaw=8, lPitch=-25, lRoll=-10, bYaw=3, bPitch=6, rlPitch=30, llPitch=-10)),
        (0.575, E_LINEAR, P(rYaw=-3, rPitch=-127, rRoll=-5, lYaw=8, lPitch=-25, lRoll=-10, bYaw=3, bPitch=5, rlPitch=31, llPitch=-11)),
        (0.600, E_LINEAR, P(rYaw=-5, rPitch=-132, rRoll=-5, lYaw=7, lPitch=-26, lRoll=-11, bYaw=4, bPitch=4, rlPitch=34, llPitch=-14)),
        (0.625, E_LINEAR, P(rYaw=-9, rPitch=-142, rRoll=-5, lYaw=7, lPitch=-28, lRoll=-11, bYaw=5, bPitch=2, rlPitch=40, llPitch=-20)),
        (0.650, E_LINEAR, P(rYaw=-13, rPitch=-151, rRoll=-4, lYaw=5, lPitch=-28, lRoll=-11, bYaw=6, bPitch=0, rlPitch=42, llPitch=-21)),
        (0.675, E_LINEAR, P(rYaw=-14, rPitch=-154, rRoll=-2, lYaw=3, lPitch=-25, lRoll=-8, bYaw=7, bPitch=0, rlPitch=37, llPitch=-12)),
        (0.700, E_LINEAR, P(rYaw=-15, rPitch=-156, rRoll=-1, lYaw=1, lPitch=-22, lRoll=-7, bYaw=8, bPitch=0, rlPitch=34, llPitch=-6)),
        (0.725, E_LINEAR, P(rYaw=-16, rPitch=-157, rRoll=-1, lYaw=1, lPitch=-21, lRoll=-6, bYaw=8, bPitch=0, rlPitch=32, llPitch=-3)),
        (0.750, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=-0, lYaw=0, lPitch=-20, lRoll=-5, bYaw=8, bPitch=0, rlPitch=30, llPitch=-1)),
        (0.775, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=-0, lYaw=0, lPitch=-20, lRoll=-5, bYaw=8, bPitch=0, rlPitch=30, llPitch=-0)),
        (0.800, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-20, lRoll=-5, bYaw=8, bPitch=0, rlPitch=30, llPitch=0)),
        (0.825, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-18, lRoll=-2, bYaw=8, bPitch=0, rlPitch=27, llPitch=0)),
        (0.850, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-17, lRoll=1, bYaw=8, bPitch=0, rlPitch=24, llPitch=0)),
        (0.875, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-16, lRoll=3, bYaw=8, bPitch=0, rlPitch=22, llPitch=0)),
        (0.900, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-16, lRoll=4, bYaw=8, bPitch=0, rlPitch=21, llPitch=0)),
        (0.925, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-15, lRoll=4, bYaw=8, bPitch=0, rlPitch=21, llPitch=0)),
        (0.950, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-15, lRoll=5, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.975, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-15, lRoll=5, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (1.000, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-15, lRoll=5, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
    ],
    # --- УДАР 3: запечено из smooth-дизайна (41 кадр, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=-16, rPitch=-158, rRoll=0, lYaw=0, lPitch=-15, lRoll=5, bYaw=8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.025, E_LINEAR, P(rYaw=-15, rPitch=-159, rRoll=0, lYaw=0, lPitch=-22, lRoll=6, bYaw=8, bPitch=0, rlPitch=13, llPitch=3)),
        (0.050, E_LINEAR, P(rYaw=-14, rPitch=-163, rRoll=0, lYaw=0, lPitch=-38, lRoll=10, bYaw=7, bPitch=0, rlPitch=-5, llPitch=10)),
        (0.075, E_LINEAR, P(rYaw=-13, rPitch=-167, rRoll=0, lYaw=0, lPitch=-53, lRoll=14, bYaw=6, bPitch=0, rlPitch=-23, llPitch=17)),
        (0.100, E_LINEAR, P(rYaw=-12, rPitch=-168, rRoll=0, lYaw=0, lPitch=-60, lRoll=15, bYaw=6, bPitch=0, rlPitch=-30, llPitch=20)),
        (0.125, E_LINEAR, P(rYaw=-11, rPitch=-168, rRoll=-1, lYaw=0, lPitch=-63, lRoll=16, bYaw=6, bPitch=0, rlPitch=-29, llPitch=21)),
        (0.150, E_LINEAR, P(rYaw=-10, rPitch=-169, rRoll=-2, lYaw=0, lPitch=-71, lRoll=19, bYaw=5, bPitch=0, rlPitch=-28, llPitch=24)),
        (0.175, E_LINEAR, P(rYaw=-8, rPitch=-169, rRoll=-3, lYaw=0, lPitch=-81, lRoll=22, bYaw=5, bPitch=0, rlPitch=-27, llPitch=27)),
        (0.200, E_LINEAR, P(rYaw=-6, rPitch=-170, rRoll=-5, lYaw=0, lPitch=-88, lRoll=24, bYaw=4, bPitch=0, rlPitch=-25, llPitch=29)),
        (0.225, E_LINEAR, P(rYaw=-6, rPitch=-170, rRoll=-5, lYaw=0, lPitch=-90, lRoll=25, bYaw=4, bPitch=0, rlPitch=-25, llPitch=30)),
        (0.250, E_LINEAR, P(rYaw=-6, rPitch=-168, rRoll=-5, lYaw=0, lPitch=-89, lRoll=24, bYaw=4, bPitch=0, rlPitch=-24, llPitch=29)),
        (0.275, E_LINEAR, P(rYaw=-5, rPitch=-163, rRoll=-5, lYaw=1, lPitch=-86, lRoll=23, bYaw=4, bPitch=0, rlPitch=-21, llPitch=28)),
        (0.300, E_LINEAR, P(rYaw=-5, rPitch=-155, rRoll=-6, lYaw=1, lPitch=-82, lRoll=20, bYaw=3, bPitch=1, rlPitch=-18, llPitch=26)),
        (0.325, E_LINEAR, P(rYaw=-4, rPitch=-146, rRoll=-6, lYaw=2, lPitch=-77, lRoll=16, bYaw=3, bPitch=1, rlPitch=-13, llPitch=23)),
        (0.350, E_LINEAR, P(rYaw=-2, rPitch=-135, rRoll=-7, lYaw=3, lPitch=-70, lRoll=13, bYaw=3, bPitch=2, rlPitch=-7, llPitch=19)),
        (0.375, E_LINEAR, P(rYaw=-1, rPitch=-122, rRoll=-7, lYaw=4, lPitch=-64, lRoll=8, bYaw=2, bPitch=2, rlPitch=-1, llPitch=16)),
        (0.400, E_LINEAR, P(rYaw=-0, rPitch=-110, rRoll=-8, lYaw=5, lPitch=-57, lRoll=4, bYaw=2, bPitch=3, rlPitch=5, llPitch=12)),
        (0.425, E_LINEAR, P(rYaw=1, rPitch=-99, rRoll=-9, lYaw=6, lPitch=-51, lRoll=0, bYaw=1, bPitch=4, rlPitch=11, llPitch=9)),
        (0.450, E_LINEAR, P(rYaw=2, rPitch=-88, rRoll=-9, lYaw=7, lPitch=-45, lRoll=-4, bYaw=1, bPitch=4, rlPitch=16, llPitch=5)),
        (0.475, E_LINEAR, P(rYaw=3, rPitch=-80, rRoll=-10, lYaw=7, lPitch=-40, lRoll=-7, bYaw=0, bPitch=5, rlPitch=20, llPitch=3)),
        (0.500, E_LINEAR, P(rYaw=4, rPitch=-74, rRoll=-10, lYaw=8, lPitch=-37, lRoll=-9, bYaw=0, bPitch=5, rlPitch=23, llPitch=1)),
        (0.525, E_LINEAR, P(rYaw=4, rPitch=-71, rRoll=-10, lYaw=8, lPitch=-35, lRoll=-10, bYaw=0, bPitch=5, rlPitch=25, llPitch=0)),
        (0.550, E_LINEAR, P(rYaw=4, rPitch=-70, rRoll=-10, lYaw=8, lPitch=-35, lRoll=-10, bYaw=-0, bPitch=5, rlPitch=25, llPitch=-0)),
        (0.575, E_LINEAR, P(rYaw=4, rPitch=-70, rRoll=-10, lYaw=8, lPitch=-35, lRoll=-10, bYaw=-0, bPitch=5, rlPitch=25, llPitch=-0)),
        (0.600, E_LINEAR, P(rYaw=4, rPitch=-68, rRoll=-9, lYaw=7, lPitch=-34, lRoll=-9, bYaw=-0, bPitch=5, rlPitch=27, llPitch=-2)),
        (0.625, E_LINEAR, P(rYaw=5, rPitch=-65, rRoll=-8, lYaw=5, lPitch=-31, lRoll=-8, bYaw=-1, bPitch=4, rlPitch=32, llPitch=-7)),
        (0.650, E_LINEAR, P(rYaw=7, rPitch=-58, rRoll=-6, lYaw=2, lPitch=-27, lRoll=-6, bYaw=-2, bPitch=3, rlPitch=40, llPitch=-15)),
        (0.675, E_LINEAR, P(rYaw=9, rPitch=-56, rRoll=-4, lYaw=0, lPitch=-24, lRoll=-4, bYaw=-4, bPitch=2, rlPitch=41, llPitch=-17)),
        (0.700, E_LINEAR, P(rYaw=9, rPitch=-58, rRoll=-2, lYaw=0, lPitch=-22, lRoll=-2, bYaw=-5, bPitch=1, rlPitch=35, llPitch=-14)),
        (0.725, E_LINEAR, P(rYaw=10, rPitch=-59, rRoll=-1, lYaw=0, lPitch=-21, lRoll=-1, bYaw=-6, bPitch=0, rlPitch=32, llPitch=-12)),
        (0.750, E_LINEAR, P(rYaw=10, rPitch=-60, rRoll=-0, lYaw=0, lPitch=-20, lRoll=-0, bYaw=-6, bPitch=0, rlPitch=31, llPitch=-10)),
        (0.775, E_LINEAR, P(rYaw=10, rPitch=-60, rRoll=-0, lYaw=0, lPitch=-20, lRoll=-0, bYaw=-6, bPitch=0, rlPitch=30, llPitch=-10)),
        (0.800, E_LINEAR, P(rYaw=10, rPitch=-60, rRoll=0, lYaw=0, lPitch=-20, lRoll=0, bYaw=-6, bPitch=0, rlPitch=30, llPitch=-10)),
        (0.825, E_LINEAR, P(rYaw=10, rPitch=-66, rRoll=3, lYaw=0, lPitch=-17, lRoll=0, bYaw=-7, bPitch=0, rlPitch=24, llPitch=-4)),
        (0.850, E_LINEAR, P(rYaw=10, rPitch=-69, rRoll=4, lYaw=0, lPitch=-16, lRoll=0, bYaw=-8, bPitch=0, rlPitch=21, llPitch=-1)),
        (0.875, E_LINEAR, P(rYaw=10, rPitch=-70, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=-0)),
        (0.900, E_LINEAR, P(rYaw=10, rPitch=-70, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.925, E_LINEAR, P(rYaw=10, rPitch=-70, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.950, E_LINEAR, P(rYaw=10, rPitch=-70, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.975, E_LINEAR, P(rYaw=10, rPitch=-70, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (1.000, E_LINEAR, P(rYaw=10, rPitch=-70, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
    ],
    # --- УДАР 4: запечено из smooth-дизайна (41 кадр, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=10, rPitch=-70, rRoll=5, lYaw=0, lPitch=-15, lRoll=0, bYaw=-8, bPitch=0, rlPitch=20, llPitch=0)),
        (0.025, E_LINEAR, P(rYaw=12, rPitch=-74, rRoll=4, lYaw=0, lPitch=-20, lRoll=3, bYaw=-9, bPitch=0, rlPitch=14, llPitch=2)),
        (0.050, E_LINEAR, P(rYaw=18, rPitch=-82, rRoll=0, lYaw=0, lPitch=-32, lRoll=9, bYaw=-10, bPitch=0, rlPitch=0, llPitch=7)),
        (0.075, E_LINEAR, P(rYaw=23, rPitch=-91, rRoll=-4, lYaw=0, lPitch=-45, lRoll=15, bYaw=-11, bPitch=0, rlPitch=-14, llPitch=13)),
        (0.100, E_LINEAR, P(rYaw=25, rPitch=-95, rRoll=-5, lYaw=0, lPitch=-50, lRoll=18, bYaw=-12, bPitch=0, rlPitch=-20, llPitch=15)),
        (0.125, E_LINEAR, P(rYaw=26, rPitch=-102, rRoll=-5, lYaw=0, lPitch=-55, lRoll=19, bYaw=-12, bPitch=0, rlPitch=-22, llPitch=16)),
        (0.150, E_LINEAR, P(rYaw=30, rPitch=-120, rRoll=-6, lYaw=0, lPitch=-67, lRoll=22, bYaw=-12, bPitch=0, rlPitch=-27, llPitch=20)),
        (0.175, E_LINEAR, P(rYaw=34, rPitch=-138, rRoll=-8, lYaw=0, lPitch=-80, lRoll=24, bYaw=-13, bPitch=0, rlPitch=-33, llPitch=24)),
        (0.200, E_LINEAR, P(rYaw=35, rPitch=-145, rRoll=-8, lYaw=0, lPitch=-85, lRoll=25, bYaw=-13, bPitch=0, rlPitch=-35, llPitch=25)),
        (0.225, E_LINEAR, P(rYaw=34, rPitch=-142, rRoll=-7, lYaw=0, lPitch=-84, lRoll=24, bYaw=-13, bPitch=0, rlPitch=-33, llPitch=24)),
        (0.250, E_LINEAR, P(rYaw=32, rPitch=-134, rRoll=-5, lYaw=1, lPitch=-80, lRoll=21, bYaw=-12, bPitch=0, rlPitch=-28, llPitch=21)),
        (0.275, E_LINEAR, P(rYaw=29, rPitch=-121, rRoll=-2, lYaw=2, lPitch=-73, lRoll=16, bYaw=-10, bPitch=1, rlPitch=-21, llPitch=17)),
        (0.300, E_LINEAR, P(rYaw=25, rPitch=-105, rRoll=2, lYaw=3, lPitch=-66, lRoll=10, bYaw=-8, bPitch=1, rlPitch=-11, llPitch=12)),
        (0.325, E_LINEAR, P(rYaw=21, rPitch=-89, rRoll=6, lYaw=5, lPitch=-58, lRoll=4, bYaw=-6, bPitch=2, rlPitch=-2, llPitch=7)),
        (0.350, E_LINEAR, P(rYaw=17, rPitch=-73, rRoll=10, lYaw=6, lPitch=-50, lRoll=-2, bYaw=-5, bPitch=2, rlPitch=7, llPitch=2)),
        (0.375, E_LINEAR, P(rYaw=14, rPitch=-61, rRoll=13, lYaw=7, lPitch=-44, lRoll=-7, bYaw=-3, bPitch=3, rlPitch=15, llPitch=-2)),
        (0.400, E_LINEAR, P(rYaw=12, rPitch=-54, rRoll=15, lYaw=8, lPitch=-41, lRoll=-9, bYaw=-2, bPitch=3, rlPitch=19, llPitch=-4)),
        (0.425, E_LINEAR, P(rYaw=12, rPitch=-52, rRoll=15, lYaw=8, lPitch=-40, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=20, llPitch=-5)),
        (0.450, E_LINEAR, P(rYaw=12, rPitch=-52, rRoll=15, lYaw=8, lPitch=-40, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=20, llPitch=-5)),
        (0.475, E_LINEAR, P(rYaw=11, rPitch=-52, rRoll=15, lYaw=8, lPitch=-39, lRoll=-10, bYaw=-2, bPitch=3, rlPitch=22, llPitch=-6)),
        (0.500, E_LINEAR, P(rYaw=8, rPitch=-53, rRoll=16, lYaw=8, lPitch=-38, lRoll=-11, bYaw=-1, bPitch=3, rlPitch=25, llPitch=-8)),
        (0.525, E_LINEAR, P(rYaw=2, rPitch=-55, rRoll=16, lYaw=7, lPitch=-36, lRoll=-12, bYaw=-0, bPitch=3, rlPitch=31, llPitch=-11)),
        (0.550, E_LINEAR, P(rYaw=-7, rPitch=-57, rRoll=17, lYaw=6, lPitch=-32, lRoll=-14, bYaw=1, bPitch=4, rlPitch=40, llPitch=-17)),
        (0.575, E_LINEAR, P(rYaw=-16, rPitch=-55, rRoll=16, lYaw=4, lPitch=-27, lRoll=-12, bYaw=3, bPitch=3, rlPitch=42, llPitch=-17)),
        (0.600, E_LINEAR, P(rYaw=-21, rPitch=-52, rRoll=13, lYaw=2, lPitch=-24, lRoll=-9, bYaw=5, bPitch=1, rlPitch=39, llPitch=-14)),
        (0.625, E_LINEAR, P(rYaw=-24, rPitch=-50, rRoll=11, lYaw=1, lPitch=-22, lRoll=-7, bYaw=6, bPitch=1, rlPitch=37, llPitch=-12)),
        (0.650, E_LINEAR, P(rYaw=-25, rPitch=-48, rRoll=10, lYaw=0, lPitch=-20, lRoll=-5, bYaw=7, bPitch=0, rlPitch=35, llPitch=-10)),
        (0.675, E_LINEAR, P(rYaw=-26, rPitch=-48, rRoll=10, lYaw=0, lPitch=-20, lRoll=-5, bYaw=7, bPitch=0, rlPitch=35, llPitch=-10)),
        (0.700, E_LINEAR, P(rYaw=-26, rPitch=-48, rRoll=10, lYaw=0, lPitch=-20, lRoll=-5, bYaw=7, bPitch=0, rlPitch=35, llPitch=-10)),
        (0.725, E_LINEAR, P(rYaw=-30, rPitch=-46, rRoll=6, lYaw=0, lPitch=-18, lRoll=-3, bYaw=8, bPitch=0, rlPitch=28, llPitch=-6)),
        (0.750, E_LINEAR, P(rYaw=-32, rPitch=-45, rRoll=3, lYaw=0, lPitch=-16, lRoll=-1, bYaw=9, bPitch=0, rlPitch=24, llPitch=-3)),
        (0.775, E_LINEAR, P(rYaw=-33, rPitch=-44, rRoll=1, lYaw=0, lPitch=-16, lRoll=-1, bYaw=10, bPitch=0, rlPitch=22, llPitch=-1)),
        (0.800, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=10, bPitch=0, rlPitch=20, llPitch=-0)),
        (0.825, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=10, bPitch=0, rlPitch=20, llPitch=-0)),
        (0.850, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=20, llPitch=0)),
        (0.875, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=20, llPitch=0)),
        (0.900, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=20, llPitch=0)),
        (0.925, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=20, llPitch=0)),
        (0.950, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=20, llPitch=0)),
        (0.975, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=20, llPitch=0)),
        (1.000, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=20, llPitch=0)),
    ],
    # --- УДАР 5: запечено из smooth-дизайна (41 кадр, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=-34, rPitch=-44, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=10, bPitch=0, rlPitch=20, llPitch=0)),
        (0.025, E_LINEAR, P(rYaw=-39, rPitch=-41, rRoll=-1, lYaw=0, lPitch=-23, lRoll=3, bYaw=10, bPitch=0, rlPitch=10, llPitch=3)),
        (0.050, E_LINEAR, P(rYaw=-49, rPitch=-34, rRoll=-3, lYaw=0, lPitch=-39, lRoll=10, bYaw=11, bPitch=0, rlPitch=-11, llPitch=10)),
        (0.075, E_LINEAR, P(rYaw=-55, rPitch=-30, rRoll=-5, lYaw=0, lPitch=-50, lRoll=15, bYaw=12, bPitch=0, rlPitch=-25, llPitch=15)),
        (0.100, E_LINEAR, P(rYaw=-57, rPitch=-34, rRoll=-5, lYaw=0, lPitch=-54, lRoll=16, bYaw=12, bPitch=0, rlPitch=-26, llPitch=16)),
        (0.125, E_LINEAR, P(rYaw=-64, rPitch=-48, rRoll=-7, lYaw=0, lPitch=-68, lRoll=21, bYaw=13, bPitch=1, rlPitch=-31, llPitch=19)),
        (0.150, E_LINEAR, P(rYaw=-69, rPitch=-59, rRoll=-8, lYaw=0, lPitch=-79, lRoll=25, bYaw=13, bPitch=2, rlPitch=-35, llPitch=22)),
        (0.175, E_LINEAR, P(rYaw=-70, rPitch=-61, rRoll=-8, lYaw=0, lPitch=-81, lRoll=25, bYaw=13, bPitch=2, rlPitch=-35, llPitch=22)),
        (0.200, E_LINEAR, P(rYaw=-71, rPitch=-68, rRoll=-8, lYaw=0, lPitch=-88, lRoll=26, bYaw=13, bPitch=2, rlPitch=-38, llPitch=24)),
        (0.225, E_LINEAR, P(rYaw=-73, rPitch=-77, rRoll=-9, lYaw=0, lPitch=-97, lRoll=28, bYaw=13, bPitch=3, rlPitch=-41, llPitch=27)),
        (0.250, E_LINEAR, P(rYaw=-74, rPitch=-86, rRoll=-10, lYaw=0, lPitch=-106, lRoll=29, bYaw=13, bPitch=3, rlPitch=-44, llPitch=29)),
        (0.275, E_LINEAR, P(rYaw=-75, rPitch=-90, rRoll=-10, lYaw=0, lPitch=-110, lRoll=30, bYaw=13, bPitch=3, rlPitch=-45, llPitch=30)),
        (0.300, E_LINEAR, P(rYaw=-74, rPitch=-89, rRoll=-10, lYaw=0, lPitch=-108, lRoll=29, bYaw=13, bPitch=3, rlPitch=-44, llPitch=29)),
        (0.325, E_LINEAR, P(rYaw=-69, rPitch=-86, rRoll=-10, lYaw=1, lPitch=-103, lRoll=26, bYaw=12, bPitch=3, rlPitch=-38, llPitch=27)),
        (0.350, E_LINEAR, P(rYaw=-61, rPitch=-81, rRoll=-10, lYaw=2, lPitch=-93, lRoll=21, bYaw=10, bPitch=4, rlPitch=-29, llPitch=23)),
        (0.375, E_LINEAR, P(rYaw=-51, rPitch=-75, rRoll=-11, lYaw=3, lPitch=-80, lRoll=14, bYaw=8, bPitch=5, rlPitch=-17, llPitch=18)),
        (0.400, E_LINEAR, P(rYaw=-41, rPitch=-68, rRoll=-11, lYaw=5, lPitch=-67, lRoll=7, bYaw=6, bPitch=5, rlPitch=-5, llPitch=13)),
        (0.425, E_LINEAR, P(rYaw=-31, rPitch=-62, rRoll=-11, lYaw=6, lPitch=-55, lRoll=0, bYaw=3, bPitch=6, rlPitch=7, llPitch=8)),
        (0.450, E_LINEAR, P(rYaw=-22, rPitch=-57, rRoll=-12, lYaw=7, lPitch=-44, lRoll=-5, bYaw=2, bPitch=7, rlPitch=16, llPitch=4)),
        (0.475, E_LINEAR, P(rYaw=-17, rPitch=-53, rRoll=-12, lYaw=8, lPitch=-37, lRoll=-9, bYaw=0, bPitch=7, rlPitch=23, llPitch=1)),
        (0.500, E_LINEAR, P(rYaw=-15, rPitch=-52, rRoll=-12, lYaw=8, lPitch=-35, lRoll=-10, bYaw=0, bPitch=7, rlPitch=25, llPitch=0)),
        (0.525, E_LINEAR, P(rYaw=-15, rPitch=-52, rRoll=-12, lYaw=8, lPitch=-35, lRoll=-10, bYaw=-0, bPitch=7, rlPitch=25, llPitch=-0)),
        (0.550, E_LINEAR, P(rYaw=-13, rPitch=-53, rRoll=-12, lYaw=8, lPitch=-35, lRoll=-10, bYaw=-0, bPitch=7, rlPitch=27, llPitch=-2)),
        (0.575, E_LINEAR, P(rYaw=-9, rPitch=-55, rRoll=-13, lYaw=8, lPitch=-34, lRoll=-11, bYaw=-1, bPitch=7, rlPitch=32, llPitch=-6)),
        (0.600, E_LINEAR, P(rYaw=-2, rPitch=-60, rRoll=-15, lYaw=7, lPitch=-32, lRoll=-13, bYaw=-3, bPitch=6, rlPitch=42, llPitch=-14)),
        (0.625, E_LINEAR, P(rYaw=10, rPitch=-64, rRoll=-17, lYaw=5, lPitch=-29, lRoll=-14, bYaw=-5, bPitch=6, rlPitch=54, llPitch=-24)),
        (0.650, E_LINEAR, P(rYaw=16, rPitch=-60, rRoll=-13, lYaw=3, lPitch=-25, lRoll=-10, bYaw=-7, bPitch=4, rlPitch=50, llPitch=-20)),
        (0.675, E_LINEAR, P(rYaw=20, rPitch=-57, rRoll=-10, lYaw=1, lPitch=-22, lRoll=-7, bYaw=-8, bPitch=4, rlPitch=47, llPitch=-17)),
        (0.700, E_LINEAR, P(rYaw=23, rPitch=-56, rRoll=-9, lYaw=0, lPitch=-21, lRoll=-6, bYaw=-9, bPitch=3, rlPitch=46, llPitch=-16)),
        (0.725, E_LINEAR, P(rYaw=24, rPitch=-55, rRoll=-8, lYaw=0, lPitch=-20, lRoll=-5, bYaw=-9, bPitch=3, rlPitch=45, llPitch=-15)),
        (0.750, E_LINEAR, P(rYaw=24, rPitch=-55, rRoll=-8, lYaw=0, lPitch=-20, lRoll=-5, bYaw=-9, bPitch=3, rlPitch=45, llPitch=-15)),
        (0.775, E_LINEAR, P(rYaw=27, rPitch=-52, rRoll=-5, lYaw=0, lPitch=-18, lRoll=-3, bYaw=-10, bPitch=2, rlPitch=40, llPitch=-10)),
        (0.800, E_LINEAR, P(rYaw=30, rPitch=-49, rRoll=-2, lYaw=0, lPitch=-16, lRoll=-1, bYaw=-10, bPitch=1, rlPitch=34, llPitch=-4)),
        (0.825, E_LINEAR, P(rYaw=31, rPitch=-47, rRoll=-1, lYaw=0, lPitch=-15, lRoll=-0, bYaw=-11, bPitch=0, rlPitch=31, llPitch=-1)),
        (0.850, E_LINEAR, P(rYaw=32, rPitch=-46, rRoll=-0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=-11, bPitch=0, rlPitch=30, llPitch=-0)),
        (0.875, E_LINEAR, P(rYaw=32, rPitch=-46, rRoll=-0, lYaw=0, lPitch=-15, lRoll=-0, bYaw=-11, bPitch=0, rlPitch=30, llPitch=-0)),
        (0.900, E_LINEAR, P(rYaw=32, rPitch=-46, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-11, bPitch=0, rlPitch=30, llPitch=0)),
        (0.925, E_LINEAR, P(rYaw=32, rPitch=-46, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-11, bPitch=0, rlPitch=30, llPitch=0)),
        (0.950, E_LINEAR, P(rYaw=32, rPitch=-46, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-11, bPitch=0, rlPitch=30, llPitch=0)),
        (0.975, E_LINEAR, P(rYaw=32, rPitch=-46, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-11, bPitch=0, rlPitch=30, llPitch=0)),
        (1.000, E_LINEAR, P(rYaw=32, rPitch=-46, rRoll=0, lYaw=0, lPitch=-15, lRoll=0, bYaw=-11, bPitch=0, rlPitch=30, llPitch=0)),
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
