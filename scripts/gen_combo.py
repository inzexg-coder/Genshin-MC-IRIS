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
    # --- hit1: запечено из smooth-дизайна v7 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rPitch=-20.0, lPitch=-10.0)),
        (0.025, E_LINEAR, P(rYaw=-6.7, rPitch=-23.3, rRoll=1.1, lPitch=-15.6, lRoll=2.2, bYaw=-1.3, rlPitch=-3.3, llPitch=2.2)),
        (0.050, E_LINEAR, P(rYaw=-20.7, rPitch=-30.4, rRoll=3.5, lPitch=-27.3, lRoll=6.9, bYaw=-4.1, rlPitch=-10.4, llPitch=6.9)),
        (0.075, E_LINEAR, P(rYaw=-29.7, rPitch=-34.9, rRoll=5.0, lPitch=-34.8, lRoll=9.9, bYaw=-5.9, rlPitch=-14.9, llPitch=9.9)),
        (0.100, E_LINEAR, P(rYaw=-32.7, rPitch=-37.4, rRoll=5.0, lPitch=-38.3, lRoll=11.1, bYaw=-6.4, rlPitch=-16.4, llPitch=11.0)),
        (0.125, E_LINEAR, P(rYaw=-41.8, rPitch=-45.5, rRoll=5.0, lPitch=-49.8, lRoll=15.1, bYaw=-7.7, rlPitch=-21.3, llPitch=14.2)),
        (0.150, E_LINEAR, P(rYaw=-52.2, rPitch=-54.8, rRoll=5.0, lPitch=-62.8, lRoll=19.5, bYaw=-9.2, rlPitch=-26.9, llPitch=17.9)),
        (0.175, E_LINEAR, P(rYaw=-57.8, rPitch=-59.8, rRoll=5.0, lPitch=-69.8, lRoll=21.9, bYaw=-10.0, rlPitch=-29.9, llPitch=19.9)),
        (0.200, E_LINEAR, P(rYaw=-57.1, rPitch=-59.9, rRoll=4.6, lYaw=-0.1, lPitch=-69.4, lRoll=21.4, bYaw=-9.8, bPitch=0.1, rlPitch=-29.0, llPitch=19.5)),
        (0.225, E_LINEAR, P(rYaw=-53.4, rPitch=-59.5, rRoll=3.0, lYaw=-0.5, lPitch=-67.0, lRoll=18.8, bYaw=-9.2, bPitch=0.3, rlPitch=-25.0, llPitch=17.5)),
        (0.250, E_LINEAR, P(rYaw=-47.4, rPitch=-58.9, rRoll=0.4, lYaw=-1.1, lPitch=-63.1, lRoll=14.7, bYaw=-8.2, bPitch=0.7, rlPitch=-18.5, llPitch=14.3)),
        (0.275, E_LINEAR, P(rYaw=-39.9, rPitch=-58.0, rRoll=-2.9, lYaw=-2.0, lPitch=-58.2, lRoll=9.4, bYaw=-6.9, bPitch=1.2, rlPitch=-10.3, llPitch=10.2)),
        (0.300, E_LINEAR, P(rYaw=-31.7, rPitch=-57.1, rRoll=-6.4, lYaw=-2.9, lPitch=-52.9, lRoll=3.7, bYaw=-5.4, bPitch=1.7, rlPitch=-1.4, llPitch=5.7)),
        (0.325, E_LINEAR, P(rYaw=-24.0, rPitch=-56.3, rRoll=-9.8, lYaw=-3.7, lPitch=-47.8, lRoll=-1.7, bYaw=-4.1, bPitch=2.2, rlPitch=7.0, llPitch=1.5)),
        (0.350, E_LINEAR, P(rYaw=-17.6, rPitch=-55.6, rRoll=-12.6, lYaw=-4.4, lPitch=-43.7, lRoll=-6.1, bYaw=-3.0, bPitch=2.6, rlPitch=13.9, llPitch=-1.9)),
        (0.375, E_LINEAR, P(rYaw=-13.5, rPitch=-55.2, rRoll=-14.4, lYaw=-4.8, lPitch=-40.9, lRoll=-9.0, bYaw=-2.3, bPitch=2.9, rlPitch=18.4, llPitch=-4.2)),
        (0.400, E_LINEAR, P(rYaw=-12.0, rPitch=-55.0, rRoll=-15.0, lYaw=-5.0, lPitch=-40.0, lRoll=-10.0, bYaw=-2.0, bPitch=3.0, rlPitch=20.0, llPitch=-5.0)),
        (0.425, E_LINEAR, P(rYaw=-11.8, rPitch=-55.0, rRoll=-15.0, lYaw=-5.0, lPitch=-39.9, lRoll=-10.0, bYaw=-2.0, bPitch=3.0, rlPitch=20.2, llPitch=-5.1)),
        (0.450, E_LINEAR, P(rYaw=-10.4, rPitch=-55.2, rRoll=-15.4, lYaw=-5.2, lPitch=-39.3, lRoll=-10.4, bYaw=-1.7, bPitch=3.1, rlPitch=21.4, llPitch=-5.7)),
        (0.475, E_LINEAR, P(rYaw=-6.6, rPitch=-55.7, rRoll=-16.2, lYaw=-5.7, lPitch=-37.6, lRoll=-11.2, bYaw=-1.0, bPitch=3.2, rlPitch=24.9, llPitch=-7.4)),
        (0.500, E_LINEAR, P(rYaw=0.7, rPitch=-56.7, rRoll=-17.9, lYaw=-6.7, lPitch=-34.2, lRoll=-12.9, bYaw=0.3, bPitch=3.6, rlPitch=31.6, llPitch=-10.8)),
        (0.525, E_LINEAR, P(rYaw=11.7, rPitch=-57.9, rRoll=-19.0, lYaw=-7.2, lPitch=-29.0, lRoll=-14.0, bYaw=2.4, bPitch=3.8, rlPitch=39.5, llPitch=-14.5)),
        (0.550, E_LINEAR, P(rYaw=18.2, rPitch=-57.5, rRoll=-14.9, lYaw=-3.9, lPitch=-24.9, lRoll=-9.9, bYaw=4.1, bPitch=3.0, rlPitch=37.4, llPitch=-12.4)),
        (0.575, E_LINEAR, P(rYaw=22.4, rPitch=-57.2, rRoll=-12.2, lYaw=-1.8, lPitch=-22.2, lRoll=-7.2, bYaw=5.1, bPitch=2.4, rlPitch=36.1, llPitch=-11.1)),
        (0.600, E_LINEAR, P(rYaw=24.7, rPitch=-57.1, rRoll=-10.8, lYaw=-0.6, lPitch=-20.8, lRoll=-5.8, bYaw=5.7, bPitch=2.2, rlPitch=35.4, llPitch=-10.4)),
        (0.625, E_LINEAR, P(rYaw=25.8, rPitch=-57.0, rRoll=-10.2, lYaw=-0.1, lPitch=-20.2, lRoll=-5.2, bYaw=5.9, bPitch=2.0, rlPitch=35.1, llPitch=-10.1)),
        (0.650, E_LINEAR, P(rYaw=26.0, rPitch=-57.0, rRoll=-10.0, lPitch=-20.0, lRoll=-5.0, bYaw=6.0, bPitch=2.0, rlPitch=35.0, llPitch=-10.0)),
        (0.675, E_LINEAR, P(rYaw=28.0, rPitch=-57.0, rRoll=-7.4, lPitch=-18.7, lRoll=-3.7, bYaw=6.5, bPitch=2.0, rlPitch=34.2, llPitch=-10.5)),
        (0.700, E_LINEAR, P(rYaw=30.6, rPitch=-57.0, rRoll=-4.2, lPitch=-17.1, lRoll=-2.1, bYaw=7.2, bPitch=2.0, rlPitch=33.3, llPitch=-11.2)),
        (0.725, E_LINEAR, P(rYaw=32.3, rPitch=-57.0, rRoll=-2.1, lPitch=-16.0, lRoll=-1.0, bYaw=7.6, bPitch=2.0, rlPitch=32.6, llPitch=-11.6)),
        (0.750, E_LINEAR, P(rYaw=33.3, rPitch=-57.0, rRoll=-0.8, lPitch=-15.4, lRoll=-0.4, bYaw=7.8, bPitch=2.0, rlPitch=32.3, llPitch=-11.8)),
        (0.775, E_LINEAR, P(rYaw=33.8, rPitch=-57.0, rRoll=-0.2, lPitch=-15.1, lRoll=-0.1, bYaw=8.0, bPitch=2.0, rlPitch=32.1, llPitch=-12.0)),
        (0.800, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (0.825, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (0.850, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (0.875, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (0.900, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (0.925, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (0.950, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (0.975, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (1.000, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
    ],
    # --- hit2: запечено из smooth-дизайна v7 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=34.0, rPitch=-57.0, lPitch=-15.0, bYaw=8.0, bPitch=2.0, rlPitch=32.0, llPitch=-12.0)),
        (0.025, E_LINEAR, P(rYaw=33.3, rPitch=-53.7, rRoll=1.9, lPitch=-20.6, lRoll=4.4, bYaw=7.6, bPitch=1.6, rlPitch=26.1, llPitch=-6.1)),
        (0.050, E_LINEAR, P(rYaw=32.1, rPitch=-48.6, rRoll=4.7, lPitch=-29.0, lRoll=11.2, bYaw=7.1, bPitch=1.1, rlPitch=17.1, llPitch=2.9)),
        (0.075, E_LINEAR, P(rYaw=31.7, rPitch=-46.5, rRoll=5.7, lPitch=-32.2, lRoll=12.6, bYaw=6.7, bPitch=1.0, rlPitch=13.1, llPitch=5.3)),
        (0.100, E_LINEAR, P(rYaw=30.5, rPitch=-40.5, rRoll=8.8, lPitch=-41.2, lRoll=15.0, bYaw=5.5, bPitch=1.0, rlPitch=1.0, llPitch=10.8)),
        (0.125, E_LINEAR, P(rYaw=30.0, rPitch=-37.9, rRoll=10.0, lPitch=-45.1, lRoll=16.0, bYaw=5.0, bPitch=1.0, rlPitch=-4.1, llPitch=13.1)),
        (0.150, E_LINEAR, P(rYaw=28.8, rPitch=-35.3, rRoll=10.4, lPitch=-49.1, lRoll=16.8, bYaw=4.6, bPitch=0.8, rlPitch=-8.9, llPitch=15.5)),
        (0.175, E_LINEAR, P(rYaw=26.5, rPitch=-30.5, rRoll=11.2, lPitch=-56.6, lRoll=18.3, bYaw=3.8, bPitch=0.4, rlPitch=-17.9, llPitch=19.9)),
        (0.200, E_LINEAR, P(rYaw=24.6, rPitch=-26.2, rRoll=11.8, lPitch=-63.1, lRoll=19.6, bYaw=3.2, bPitch=0.1, rlPitch=-25.7, llPitch=23.9)),
        (0.225, E_LINEAR, P(rYaw=24.0, rPitch=-25.2, rRoll=11.9, lPitch=-65.0, lRoll=20.0, bYaw=3.0, rlPitch=-27.9, llPitch=25.0)),
        (0.250, E_LINEAR, P(rYaw=24.1, rPitch=-31.0, rRoll=10.0, lYaw=0.4, lPitch=-63.9, lRoll=18.9, bYaw=3.1, bPitch=0.3, rlPitch=-25.1, llPitch=23.4)),
        (0.275, E_LINEAR, P(rYaw=24.4, rPitch=-44.3, rRoll=5.5, lYaw=1.4, lPitch=-61.5, lRoll=16.5, bYaw=3.4, bPitch=0.9, rlPitch=-18.7, llPitch=19.7)),
        (0.300, E_LINEAR, P(rYaw=24.7, rPitch=-63.0, rRoll=-0.8, lYaw=2.8, lPitch=-58.1, lRoll=13.1, bYaw=3.7, bPitch=1.7, rlPitch=-9.7, llPitch=14.6)),
        (0.325, E_LINEAR, P(rYaw=25.1, rPitch=-84.3, rRoll=-8.0, lYaw=4.3, lPitch=-54.2, lRoll=9.2, bYaw=4.1, bPitch=2.7, rlPitch=0.6, llPitch=8.8)),
        (0.350, E_LINEAR, P(rYaw=25.5, rPitch=-105.0, rRoll=-14.9, lYaw=5.8, lPitch=-50.5, lRoll=5.5, bYaw=4.5, bPitch=3.6, rlPitch=10.5, llPitch=3.2)),
        (0.375, E_LINEAR, P(rYaw=25.8, rPitch=-121.8, rRoll=-20.6, lYaw=7.0, lPitch=-47.4, lRoll=2.4, bYaw=4.8, bPitch=4.4, rlPitch=18.7, llPitch=-1.4)),
        (0.400, E_LINEAR, P(rYaw=26.0, rPitch=-132.3, rRoll=-24.1, lYaw=7.8, lPitch=-45.5, lRoll=0.5, bYaw=5.0, bPitch=4.9, rlPitch=23.7, llPitch=-4.3)),
        (0.425, E_LINEAR, P(rYaw=26.0, rPitch=-135.0, rRoll=-25.0, lYaw=8.0, lPitch=-45.0, bYaw=5.0, bPitch=5.0, rlPitch=25.0, llPitch=-5.0)),
        (0.450, E_LINEAR, P(rYaw=26.0, rPitch=-135.0, rRoll=-25.0, lYaw=8.0, lPitch=-44.9, lRoll=-0.1, bYaw=5.0, bPitch=5.0, rlPitch=25.1, llPitch=-5.1)),
        (0.475, E_LINEAR, P(rYaw=26.2, rPitch=-135.1, rRoll=-25.1, lYaw=7.9, lPitch=-44.6, lRoll=-0.3, bYaw=5.0, bPitch=4.9, rlPitch=25.6, llPitch=-5.4)),
        (0.500, E_LINEAR, P(rYaw=26.5, rPitch=-135.4, rRoll=-25.4, lYaw=7.8, lPitch=-43.8, lRoll=-1.0, bYaw=5.1, bPitch=4.8, rlPitch=26.9, llPitch=-6.3)),
        (0.525, E_LINEAR, P(rYaw=27.1, rPitch=-135.8, rRoll=-25.8, lYaw=7.4, lPitch=-42.2, lRoll=-2.3, bYaw=5.3, bPitch=4.4, rlPitch=29.2, llPitch=-7.8)),
        (0.550, E_LINEAR, P(rYaw=28.1, rPitch=-136.6, rRoll=-26.6, lYaw=6.9, lPitch=-39.6, lRoll=-4.3, bYaw=5.5, bPitch=3.9, rlPitch=33.0, llPitch=-10.4)),
        (0.575, E_LINEAR, P(rYaw=29.6, rPitch=-137.7, rRoll=-27.7, lYaw=6.2, lPitch=-35.9, lRoll=-7.3, bYaw=5.9, bPitch=3.2, rlPitch=38.6, llPitch=-14.1)),
        (0.600, E_LINEAR, P(rYaw=30.6, rPitch=-138.6, rRoll=-28.0, lYaw=4.1, lPitch=-30.3, lRoll=-6.4, bYaw=6.3, bPitch=2.1, rlPitch=36.9, llPitch=-12.8)),
        (0.625, E_LINEAR, P(rYaw=31.2, rPitch=-139.2, rRoll=-28.0, lYaw=2.4, lPitch=-26.0, lRoll=-5.0, bYaw=6.6, bPitch=1.2, rlPitch=34.0, llPitch=-10.8)),
        (0.650, E_LINEAR, P(rYaw=31.6, rPitch=-139.6, rRoll=-28.0, lYaw=1.2, lPitch=-23.1, lRoll=-4.0, bYaw=6.8, bPitch=0.6, rlPitch=32.0, llPitch=-9.4)),
        (0.675, E_LINEAR, P(rYaw=31.8, rPitch=-139.8, rRoll=-28.0, lYaw=0.5, lPitch=-21.3, lRoll=-3.4, bYaw=6.9, bPitch=0.3, rlPitch=30.9, llPitch=-8.6)),
        (0.700, E_LINEAR, P(rYaw=31.9, rPitch=-139.9, rRoll=-28.0, lYaw=0.2, lPitch=-20.4, lRoll=-3.1, bYaw=7.0, bPitch=0.1, rlPitch=30.3, llPitch=-8.2)),
        (0.725, E_LINEAR, P(rYaw=32.0, rPitch=-140.0, rRoll=-28.0, lPitch=-20.0, lRoll=-3.0, bYaw=7.0, rlPitch=30.0, llPitch=-8.0)),
        (0.750, E_LINEAR, P(rYaw=32.0, rPitch=-140.0, rRoll=-28.0, lPitch=-20.0, lRoll=-3.0, bYaw=7.0, rlPitch=30.0, llPitch=-8.0)),
        (0.775, E_LINEAR, P(rYaw=32.5, rPitch=-140.5, rRoll=-28.5, lPitch=-18.6, lRoll=-0.8, bYaw=7.0, rlPitch=28.6, llPitch=-7.2)),
        (0.800, E_LINEAR, P(rYaw=33.0, rPitch=-141.0, rRoll=-29.0, lPitch=-17.6, lRoll=0.9, bYaw=7.0, rlPitch=27.6, llPitch=-6.5)),
        (0.825, E_LINEAR, P(rYaw=33.3, rPitch=-141.3, rRoll=-29.3, lPitch=-16.7, lRoll=2.3, bYaw=7.0, rlPitch=26.7, llPitch=-6.0)),
        (0.850, E_LINEAR, P(rYaw=33.6, rPitch=-141.6, rRoll=-29.6, lPitch=-16.1, lRoll=3.3, bYaw=7.0, rlPitch=26.1, llPitch=-5.6)),
        (0.875, E_LINEAR, P(rYaw=33.8, rPitch=-141.8, rRoll=-29.8, lPitch=-15.6, lRoll=4.0, bYaw=7.0, rlPitch=25.6, llPitch=-5.4)),
        (0.900, E_LINEAR, P(rYaw=33.9, rPitch=-141.9, rRoll=-29.9, lPitch=-15.3, lRoll=4.5, bYaw=7.0, rlPitch=25.3, llPitch=-5.2)),
        (0.925, E_LINEAR, P(rYaw=33.9, rPitch=-141.9, rRoll=-29.9, lPitch=-15.1, lRoll=4.8, bYaw=7.0, rlPitch=25.1, llPitch=-5.1)),
        (0.950, E_LINEAR, P(rYaw=34.0, rPitch=-142.0, rRoll=-30.0, lPitch=-15.0, lRoll=4.9, bYaw=7.0, rlPitch=25.0, llPitch=-5.0)),
        (0.975, E_LINEAR, P(rYaw=34.0, rPitch=-142.0, rRoll=-30.0, lPitch=-15.0, lRoll=5.0, bYaw=7.0, rlPitch=25.0, llPitch=-5.0)),
        (1.000, E_LINEAR, P(rYaw=34.0, rPitch=-142.0, rRoll=-30.0, lPitch=-15.0, lRoll=5.0, bYaw=7.0, rlPitch=25.0, llPitch=-5.0)),
    ],
    # --- hit3: запечено из smooth-дизайна v7 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=34.0, rPitch=-142.0, rRoll=-30.0, lPitch=-15.0, lRoll=5.0, bYaw=7.0, rlPitch=25.0, llPitch=-5.0)),
        (0.025, E_LINEAR, P(rYaw=33.1, rPitch=-144.3, rRoll=-28.8, lPitch=-20.9, lRoll=6.5, bYaw=6.7, rlPitch=17.7, llPitch=-1.3)),
        (0.050, E_LINEAR, P(rYaw=31.0, rPitch=-150.0, rRoll=-26.0, lPitch=-35.0, lRoll=10.0, bYaw=6.0, llPitch=7.5)),
        (0.075, E_LINEAR, P(rYaw=28.9, rPitch=-155.7, rRoll=-23.2, lPitch=-49.1, lRoll=13.5, bYaw=5.3, rlPitch=-17.7, llPitch=16.3)),
        (0.100, E_LINEAR, P(rYaw=28.0, rPitch=-158.0, rRoll=-22.0, lPitch=-55.0, lRoll=15.0, bYaw=5.0, rlPitch=-25.0, llPitch=20.0)),
        (0.125, E_LINEAR, P(rYaw=27.0, rPitch=-159.4, rRoll=-21.0, lPitch=-58.1, lRoll=15.7, bYaw=4.9, rlPitch=-24.5, llPitch=20.8)),
        (0.150, E_LINEAR, P(rYaw=24.3, rPitch=-163.2, rRoll=-18.3, lPitch=-66.1, lRoll=17.6, bYaw=4.6, rlPitch=-23.1, llPitch=23.0)),
        (0.175, E_LINEAR, P(rYaw=21.1, rPitch=-167.7, rRoll=-15.1, lPitch=-75.7, lRoll=19.8, bYaw=4.3, rlPitch=-21.5, llPitch=25.5)),
        (0.200, E_LINEAR, P(rYaw=18.7, rPitch=-171.1, rRoll=-12.7, lPitch=-83.0, lRoll=21.5, bYaw=4.1, rlPitch=-20.3, llPitch=27.5)),
        (0.225, E_LINEAR, P(rYaw=18.0, rPitch=-172.0, rRoll=-12.0, lPitch=-85.0, lRoll=22.0, bYaw=4.0, rlPitch=-20.0, llPitch=28.0)),
        (0.250, E_LINEAR, P(rYaw=17.8, rPitch=-170.3, rRoll=-11.9, lYaw=0.2, lPitch=-84.1, lRoll=21.4, bYaw=3.9, bPitch=0.1, rlPitch=-19.1, llPitch=27.4)),
        (0.275, E_LINEAR, P(rYaw=17.4, rPitch=-166.5, rRoll=-11.7, lYaw=0.6, lPitch=-82.2, lRoll=20.1, bYaw=3.7, bPitch=0.4, rlPitch=-17.2, llPitch=26.0)),
        (0.300, E_LINEAR, P(rYaw=16.8, rPitch=-160.7, rRoll=-11.4, lYaw=1.2, lPitch=-79.1, lRoll=18.0, bYaw=3.4, bPitch=0.7, rlPitch=-14.1, llPitch=23.9)),
        (0.325, E_LINEAR, P(rYaw=16.1, rPitch=-153.3, rRoll=-11.0, lYaw=1.9, lPitch=-75.3, lRoll=15.4, bYaw=3.0, bPitch=1.2, rlPitch=-10.3, llPitch=21.2)),
        (0.350, E_LINEAR, P(rYaw=15.2, rPitch=-144.7, rRoll=-10.6, lYaw=2.8, lPitch=-70.8, lRoll=12.4, bYaw=2.6, bPitch=1.8, rlPitch=-5.8, llPitch=18.1)),
        (0.375, E_LINEAR, P(rYaw=14.2, rPitch=-135.4, rRoll=-10.1, lYaw=3.8, lPitch=-66.0, lRoll=9.2, bYaw=2.1, bPitch=2.4, rlPitch=-1.0, llPitch=14.7)),
        (0.400, E_LINEAR, P(rYaw=13.2, rPitch=-126.0, rRoll=-9.6, lYaw=4.8, lPitch=-61.1, lRoll=5.9, bYaw=1.6, bPitch=3.0, rlPitch=3.9, llPitch=11.3)),
        (0.425, E_LINEAR, P(rYaw=12.3, rPitch=-117.0, rRoll=-9.1, lYaw=5.7, lPitch=-56.4, lRoll=2.7, bYaw=1.1, bPitch=3.6, rlPitch=8.6, llPitch=8.0)),
        (0.450, E_LINEAR, P(rYaw=11.5, rPitch=-109.1, rRoll=-8.7, lYaw=6.5, lPitch=-52.3, lRoll=-0.1, bYaw=0.7, bPitch=4.1, rlPitch=12.7, llPitch=5.1)),
        (0.475, E_LINEAR, P(rYaw=10.8, rPitch=-102.6, rRoll=-8.4, lYaw=7.2, lPitch=-48.9, lRoll=-2.3, bYaw=0.4, bPitch=4.5, rlPitch=16.1, llPitch=2.8)),
        (0.500, E_LINEAR, P(rYaw=10.3, rPitch=-97.9, rRoll=-8.2, lYaw=7.7, lPitch=-46.5, lRoll=-4.0, bYaw=0.2, bPitch=4.8, rlPitch=18.5, llPitch=1.1)),
        (0.525, E_LINEAR, P(rYaw=10.0, rPitch=-95.4, rRoll=-8.0, lYaw=8.0, lPitch=-45.2, lRoll=-4.9, bPitch=5.0, rlPitch=19.8, llPitch=0.2)),
        (0.550, E_LINEAR, P(rYaw=10.0, rPitch=-95.0, rRoll=-8.0, lYaw=8.0, lPitch=-45.0, lRoll=-5.0, bPitch=5.0, rlPitch=20.0)),
        (0.575, E_LINEAR, P(rYaw=9.9, rPitch=-94.1, rRoll=-7.9, lYaw=7.8, lPitch=-44.6, lRoll=-5.0, bYaw=-0.1, bPitch=5.0, rlPitch=20.5, llPitch=-0.4)),
        (0.600, E_LINEAR, P(rYaw=9.3, rPitch=-90.6, rRoll=-7.6, lYaw=7.0, lPitch=-43.1, lRoll=-5.0, bYaw=-0.4, bPitch=4.8, rlPitch=22.5, llPitch=-1.9)),
        (0.625, E_LINEAR, P(rYaw=7.9, rPitch=-82.6, rRoll=-6.9, lYaw=5.2, lPitch=-39.7, lRoll=-5.0, bYaw=-1.1, bPitch=4.3, rlPitch=27.1, llPitch=-5.3)),
        (0.650, E_LINEAR, P(rYaw=5.4, rPitch=-68.0, rRoll=-5.7, lYaw=1.8, lPitch=-33.4, lRoll=-5.0, bYaw=-2.3, bPitch=3.5, rlPitch=35.4, llPitch=-11.6)),
        (0.675, E_LINEAR, P(rYaw=5.2, rPitch=-61.4, rRoll=-3.6, lPitch=-27.1, lRoll=-3.6, bYaw=-3.9, bPitch=2.1, rlPitch=36.5, llPitch=-13.0)),
        (0.700, E_LINEAR, P(rYaw=6.5, rPitch=-63.2, rRoll=-1.8, lPitch=-23.6, lRoll=-1.8, bYaw=-4.9, bPitch=1.1, rlPitch=32.4, llPitch=-10.6)),
        (0.725, E_LINEAR, P(rYaw=7.4, rPitch=-64.2, rRoll=-0.8, lPitch=-21.5, lRoll=-0.8, bYaw=-5.5, bPitch=0.5, rlPitch=29.8, llPitch=-9.1)),
        (0.750, E_LINEAR, P(rYaw=7.8, rPitch=-64.8, rRoll=-0.2, lPitch=-20.5, lRoll=-0.2, bYaw=-5.9, bPitch=0.1, rlPitch=28.5, llPitch=-8.3)),
        (0.775, E_LINEAR, P(rYaw=8.0, rPitch=-65.0, lPitch=-20.1, bYaw=-6.0, rlPitch=28.1, llPitch=-8.0)),
        (0.800, E_LINEAR, P(rYaw=8.0, rPitch=-65.0, lPitch=-20.0, bYaw=-6.0, rlPitch=28.0, llPitch=-8.0)),
        (0.825, E_LINEAR, P(rYaw=9.2, rPitch=-69.0, rRoll=2.9, lPitch=-17.1, bYaw=-7.2, rlPitch=23.4, llPitch=-3.4)),
        (0.850, E_LINEAR, P(rYaw=9.8, rPitch=-71.1, rRoll=4.4, lPitch=-15.6, bYaw=-7.7, rlPitch=21.0, llPitch=-1.0)),
        (0.875, E_LINEAR, P(rYaw=10.0, rPitch=-71.9, rRoll=4.9, lPitch=-15.1, bYaw=-8.0, rlPitch=20.1, llPitch=-0.1)),
        (0.900, E_LINEAR, P(rYaw=10.0, rPitch=-72.0, rRoll=5.0, lPitch=-15.0, bYaw=-8.0, rlPitch=20.0)),
        (0.925, E_LINEAR, P(rYaw=10.0, rPitch=-72.0, rRoll=5.0, lPitch=-15.0, bYaw=-8.0, rlPitch=20.0)),
        (0.950, E_LINEAR, P(rYaw=10.0, rPitch=-72.0, rRoll=5.0, lPitch=-15.0, bYaw=-8.0, rlPitch=20.0)),
        (0.975, E_LINEAR, P(rYaw=10.0, rPitch=-72.0, rRoll=5.0, lPitch=-15.0, bYaw=-8.0, rlPitch=20.0)),
        (1.000, E_LINEAR, P(rYaw=10.0, rPitch=-72.0, rRoll=5.0, lPitch=-15.0, bYaw=-8.0, rlPitch=20.0)),
    ],
    # --- hit4: запечено из smooth-дизайна v7 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=10.0, rPitch=-72.0, rRoll=5.0, lPitch=-15.0, bYaw=-8.0, rlPitch=20.0)),
        (0.025, E_LINEAR, P(rYaw=13.3, rPitch=-78.2, rRoll=2.8, lPitch=-21.7, lRoll=3.3, bYaw=-8.9, rlPitch=12.2, llPitch=2.7)),
        (0.050, E_LINEAR, P(rYaw=20.4, rPitch=-91.4, rRoll=-1.9, lPitch=-35.7, lRoll=10.4, bYaw=-10.8, rlPitch=-4.2, llPitch=8.3)),
        (0.075, E_LINEAR, P(rYaw=24.9, rPitch=-99.7, rRoll=-4.9, lPitch=-44.7, lRoll=14.9, bYaw=-12.0, rlPitch=-14.7, llPitch=11.9)),
        (0.100, E_LINEAR, P(rYaw=25.9, rPitch=-104.3, rRoll=-5.3, lPitch=-48.3, lRoll=15.7, bYaw=-12.1, rlPitch=-16.4, llPitch=12.8)),
        (0.125, E_LINEAR, P(rYaw=28.8, rPitch=-119.0, rRoll=-6.3, lPitch=-59.8, lRoll=18.0, bYaw=-12.4, rlPitch=-21.3, llPitch=15.4)),
        (0.150, E_LINEAR, P(rYaw=32.1, rPitch=-135.7, rRoll=-7.4, lPitch=-72.8, lRoll=20.6, bYaw=-12.8, rlPitch=-26.9, llPitch=18.4)),
        (0.175, E_LINEAR, P(rYaw=33.9, rPitch=-144.7, rRoll=-8.0, lPitch=-79.8, lRoll=22.0, bYaw=-13.0, rlPitch=-29.9, llPitch=20.0)),
        (0.200, E_LINEAR, P(rYaw=33.6, rPitch=-143.2, rRoll=-7.5, lYaw=0.2, lPitch=-79.2, lRoll=21.4, bYaw=-12.8, bPitch=0.1, rlPitch=-29.0, llPitch=19.5)),
        (0.225, E_LINEAR, P(rYaw=31.8, rPitch=-136.0, rRoll=-5.7, lYaw=0.8, lPitch=-76.0, lRoll=18.8, bYaw=-11.9, bPitch=0.3, rlPitch=-25.0, llPitch=17.5)),
        (0.250, E_LINEAR, P(rYaw=28.9, rPitch=-124.3, rRoll=-2.7, lYaw=1.8, lPitch=-70.8, lRoll=14.7, bYaw=-10.5, bPitch=0.7, rlPitch=-18.5, llPitch=14.3)),
        (0.275, E_LINEAR, P(rYaw=25.3, rPitch=-109.6, rRoll=1.1, lYaw=3.1, lPitch=-64.3, lRoll=9.4, bYaw=-8.7, bPitch=1.2, rlPitch=-10.3, llPitch=10.2)),
        (0.300, E_LINEAR, P(rYaw=21.4, rPitch=-93.6, rRoll=5.1, lYaw=4.6, lPitch=-57.2, lRoll=3.7, bYaw=-6.7, bPitch=1.7, rlPitch=-1.4, llPitch=5.7)),
        (0.325, E_LINEAR, P(rYaw=17.7, rPitch=-78.4, rRoll=9.0, lYaw=5.9, lPitch=-50.4, lRoll=-1.7, bYaw=-4.9, bPitch=2.2, rlPitch=7.0, llPitch=1.5)),
        (0.350, E_LINEAR, P(rYaw=14.7, rPitch=-66.0, rRoll=12.2, lYaw=7.0, lPitch=-44.9, lRoll=-6.1, bYaw=-3.3, bPitch=2.6, rlPitch=13.9, llPitch=-1.9)),
        (0.375, E_LINEAR, P(rYaw=12.7, rPitch=-57.8, rRoll=14.3, lYaw=7.7, lPitch=-41.3, lRoll=-9.0, bYaw=-2.3, bPitch=2.9, rlPitch=18.4, llPitch=-4.2)),
        (0.400, E_LINEAR, P(rYaw=12.0, rPitch=-55.0, rRoll=15.0, lYaw=8.0, lPitch=-40.0, lRoll=-10.0, bYaw=-2.0, bPitch=3.0, rlPitch=20.0, llPitch=-5.0)),
        (0.425, E_LINEAR, P(rYaw=11.8, rPitch=-55.0, rRoll=15.0, lYaw=8.0, lPitch=-39.9, lRoll=-10.0, bYaw=-2.0, bPitch=3.0, rlPitch=20.2, llPitch=-5.1)),
        (0.450, E_LINEAR, P(rYaw=10.3, rPitch=-55.2, rRoll=15.2, lYaw=7.9, lPitch=-39.3, lRoll=-10.4, bYaw=-1.7, bPitch=3.1, rlPitch=21.4, llPitch=-5.7)),
        (0.475, E_LINEAR, P(rYaw=6.1, rPitch=-55.7, rRoll=15.7, lYaw=7.5, lPitch=-37.6, lRoll=-11.2, bYaw=-1.0, bPitch=3.2, rlPitch=24.9, llPitch=-7.4)),
        (0.500, E_LINEAR, P(rYaw=-1.9, rPitch=-56.7, rRoll=16.7, lYaw=6.8, lPitch=-34.2, lRoll=-12.9, bYaw=0.3, bPitch=3.6, rlPitch=31.6, llPitch=-10.8)),
        (0.525, E_LINEAR, P(rYaw=-13.7, rPitch=-58.4, rRoll=17.2, lYaw=5.4, lPitch=-29.0, lRoll=-14.0, bYaw=2.4, bPitch=3.8, rlPitch=39.2, llPitch=-14.5)),
        (0.550, E_LINEAR, P(rYaw=-20.2, rPitch=-60.1, rRoll=13.9, lYaw=2.9, lPitch=-24.9, lRoll=-9.9, bYaw=4.1, bPitch=3.0, rlPitch=35.9, llPitch=-12.4)),
        (0.575, E_LINEAR, P(rYaw=-24.4, rPitch=-61.1, rRoll=11.8, lYaw=1.3, lPitch=-22.2, lRoll=-7.2, bYaw=5.1, bPitch=2.4, rlPitch=33.8, llPitch=-11.1)),
        (0.600, E_LINEAR, P(rYaw=-26.7, rPitch=-61.7, rRoll=10.6, lYaw=0.5, lPitch=-20.8, lRoll=-5.8, bYaw=5.7, bPitch=2.2, rlPitch=32.6, llPitch=-10.4)),
        (0.625, E_LINEAR, P(rYaw=-27.8, rPitch=-61.9, rRoll=10.1, lYaw=0.1, lPitch=-20.2, lRoll=-5.2, bYaw=5.9, bPitch=2.0, rlPitch=32.1, llPitch=-10.1)),
        (0.650, E_LINEAR, P(rYaw=-28.0, rPitch=-62.0, rRoll=10.0, lPitch=-20.0, lRoll=-5.0, bYaw=6.0, bPitch=2.0, rlPitch=32.0, llPitch=-10.0)),
        (0.675, E_LINEAR, P(rYaw=-30.0, rPitch=-65.1, rRoll=9.5, lPitch=-18.7, lRoll=-3.7, bYaw=6.8, bPitch=1.5, rlPitch=29.4, llPitch=-7.4)),
        (0.700, E_LINEAR, P(rYaw=-32.6, rPitch=-68.9, rRoll=8.8, lPitch=-17.1, lRoll=-2.1, bYaw=7.7, bPitch=0.8, rlPitch=26.2, llPitch=-4.2)),
        (0.725, E_LINEAR, P(rYaw=-34.3, rPitch=-71.5, rRoll=8.4, lPitch=-16.0, lRoll=-1.0, bYaw=8.4, bPitch=0.4, rlPitch=24.1, llPitch=-2.1)),
        (0.750, E_LINEAR, P(rYaw=-35.3, rPitch=-73.0, rRoll=8.2, lPitch=-15.4, lRoll=-0.4, bYaw=8.7, bPitch=0.2, rlPitch=22.8, llPitch=-0.8)),
        (0.775, E_LINEAR, P(rYaw=-35.8, rPitch=-73.7, rRoll=8.0, lPitch=-15.1, lRoll=-0.1, bYaw=8.9, rlPitch=22.2, llPitch=-0.2)),
        (0.800, E_LINEAR, P(rYaw=-36.0, rPitch=-74.0, rRoll=8.0, lPitch=-15.0, bYaw=9.0, rlPitch=22.0)),
        (0.825, E_LINEAR, P(rYaw=-36.2, rPitch=-74.3, rRoll=8.0, lPitch=-15.0, bYaw=9.0, rlPitch=21.8)),
        (0.850, E_LINEAR, P(rYaw=-37.0, rPitch=-75.8, rRoll=8.0, lPitch=-15.0, bYaw=9.2, rlPitch=20.8)),
        (0.875, E_LINEAR, P(rYaw=-37.8, rPitch=-77.4, rRoll=8.0, lPitch=-15.0, bYaw=9.3, rlPitch=19.9)),
        (0.900, E_LINEAR, P(rYaw=-38.7, rPitch=-78.9, rRoll=8.0, lPitch=-15.0, bYaw=9.4, rlPitch=18.9)),
        (0.925, E_LINEAR, P(rYaw=-39.5, rPitch=-80.4, rRoll=8.0, lPitch=-15.0, bYaw=9.6, rlPitch=17.9)),
        (0.950, E_LINEAR, P(rYaw=-40.3, rPitch=-81.9, rRoll=8.0, lPitch=-15.0, bYaw=9.7, rlPitch=16.9)),
        (0.975, E_LINEAR, P(rYaw=-41.2, rPitch=-83.5, rRoll=8.0, lPitch=-15.0, bYaw=9.9, rlPitch=16.0)),
        (1.000, E_LINEAR, P(rYaw=-42.0, rPitch=-85.0, rRoll=8.0, lPitch=-15.0, bYaw=10.0, rlPitch=15.0)),
    ],
    # --- hit5: запечено из smooth-дизайна v7 (41 кадров, E_LINEAR) ---
    [
        (0.000, E_LINEAR, P(rYaw=-42.0, rPitch=-85.0, rRoll=8.0, lPitch=-15.0, bYaw=10.0, rlPitch=15.0)),
        (0.025, E_LINEAR, P(rYaw=-45.6, rPitch=-89.6, rRoll=7.3, lPitch=-25.7, lRoll=5.4, bYaw=10.7, rlPitch=4.3, llPitch=5.4)),
        (0.050, E_LINEAR, P(rYaw=-49.1, rPitch=-94.3, rRoll=6.6, lPitch=-36.4, lRoll=10.7, bYaw=11.4, rlPitch=-6.4, llPitch=10.7)),
        (0.075, E_LINEAR, P(rYaw=-52.9, rPitch=-98.7, rRoll=5.9, lPitch=-47.1, lRoll=15.5, bYaw=12.1, bPitch=0.1, rlPitch=-16.1, llPitch=15.5)),
        (0.100, E_LINEAR, P(rYaw=-57.1, rPitch=-102.3, rRoll=5.1, lPitch=-57.9, lRoll=18.0, bYaw=12.4, bPitch=0.9, rlPitch=-21.4, llPitch=18.0)),
        (0.125, E_LINEAR, P(rYaw=-61.4, rPitch=-105.9, rRoll=4.4, lPitch=-68.6, lRoll=20.5, bYaw=12.8, bPitch=1.6, rlPitch=-26.8, llPitch=20.5)),
        (0.150, E_LINEAR, P(rYaw=-65.0, rPitch=-108.7, rRoll=3.6, lPitch=-77.5, lRoll=22.6, bYaw=13.0, bPitch=2.1, rlPitch=-31.0, llPitch=22.6)),
        (0.175, E_LINEAR, P(rYaw=-67.5, rPitch=-110.5, rRoll=2.6, lPitch=-83.8, lRoll=24.1, bYaw=13.0, bPitch=2.3, rlPitch=-33.5, llPitch=24.1)),
        (0.200, E_LINEAR, P(rYaw=-70.0, rPitch=-112.2, rRoll=1.6, lPitch=-90.0, lRoll=25.6, bYaw=13.0, bPitch=2.6, rlPitch=-36.0, llPitch=25.6)),
        (0.225, E_LINEAR, P(rYaw=-72.5, rPitch=-114.0, rRoll=0.6, lPitch=-96.2, lRoll=27.1, bYaw=13.0, bPitch=2.9, rlPitch=-38.5, llPitch=27.1)),
        (0.250, E_LINEAR, P(rYaw=-73.8, rPitch=-114.8, rRoll=-0.1, lPitch=-99.8, lRoll=27.9, bYaw=13.0, bPitch=3.0, rlPitch=-39.7, llPitch=27.9)),
        (0.275, E_LINEAR, P(rYaw=-71.3, rPitch=-112.6, rRoll=-0.7, lYaw=0.4, lPitch=-97.4, lRoll=26.3, bYaw=12.4, bPitch=3.2, rlPitch=-36.9, llPitch=26.8)),
        (0.300, E_LINEAR, P(rYaw=-66.2, rPitch=-108.1, rRoll=-1.9, lYaw=1.0, lPitch=-92.5, lRoll=23.2, bYaw=11.4, bPitch=3.6, rlPitch=-31.2, llPitch=24.5)),
        (0.325, E_LINEAR, P(rYaw=-59.0, rPitch=-101.7, rRoll=-3.6, lYaw=1.9, lPitch=-85.5, lRoll=18.8, bYaw=9.9, bPitch=4.2, rlPitch=-23.1, llPitch=21.2)),
        (0.350, E_LINEAR, P(rYaw=-50.4, rPitch=-94.1, rRoll=-5.7, lYaw=3.0, lPitch=-77.2, lRoll=13.5, bYaw=8.1, bPitch=4.9, rlPitch=-13.4, llPitch=17.4)),
        (0.375, E_LINEAR, P(rYaw=-41.1, rPitch=-85.8, rRoll=-8.0, lYaw=4.2, lPitch=-68.2, lRoll=7.9, bYaw=6.1, bPitch=5.7, rlPitch=-2.9, llPitch=13.2)),
        (0.400, E_LINEAR, P(rYaw=-32.0, rPitch=-77.7, rRoll=-10.2, lYaw=5.4, lPitch=-59.4, lRoll=2.3, bYaw=4.2, bPitch=6.4, rlPitch=7.4, llPitch=9.0)),
        (0.425, E_LINEAR, P(rYaw=-23.9, rPitch=-70.5, rRoll=-12.1, lYaw=6.5, lPitch=-51.5, lRoll=-2.7, bYaw=2.5, bPitch=7.0, rlPitch=16.6, llPitch=5.4)),
        (0.450, E_LINEAR, P(rYaw=-17.5, rPitch=-64.9, rRoll=-13.7, lYaw=7.3, lPitch=-45.3, lRoll=-6.6, bYaw=1.2, bPitch=7.6, rlPitch=23.8, llPitch=2.5)),
        (0.475, E_LINEAR, P(rYaw=-13.4, rPitch=-61.2, rRoll=-14.7, lYaw=7.8, lPitch=-41.4, lRoll=-9.1, bYaw=0.3, bPitch=7.9, rlPitch=28.4, llPitch=0.6)),
        (0.500, E_LINEAR, P(rYaw=-12.0, rPitch=-60.0, rRoll=-15.0, lYaw=8.0, lPitch=-40.0, lRoll=-10.0, bPitch=8.0, rlPitch=30.0)),
        (0.525, E_LINEAR, P(rYaw=-11.8, rPitch=-60.0, rRoll=-15.0, lYaw=8.0, lPitch=-39.9, lRoll=-10.0, bPitch=8.0, rlPitch=30.2, llPitch=-0.2)),
        (0.550, E_LINEAR, P(rYaw=-10.4, rPitch=-60.0, rRoll=-15.2, lYaw=7.9, lPitch=-39.3, lRoll=-10.4, bYaw=-0.4, bPitch=7.9, rlPitch=31.8, llPitch=-1.4)),
        (0.575, E_LINEAR, P(rYaw=-6.6, rPitch=-60.0, rRoll=-15.7, lYaw=7.5, lPitch=-37.6, lRoll=-11.2, bYaw=-1.2, bPitch=7.8, rlPitch=36.1, llPitch=-4.9)),
        (0.600, E_LINEAR, P(rYaw=0.7, rPitch=-60.0, rRoll=-16.7, lYaw=6.8, lPitch=-34.2, lRoll=-12.9, bYaw=-2.9, bPitch=7.4, rlPitch=44.5, llPitch=-11.6)),
        (0.625, E_LINEAR, P(rYaw=11.4, rPitch=-59.8, rRoll=-17.0, lYaw=5.4, lPitch=-29.0, lRoll=-14.0, bYaw=-5.4, bPitch=6.7, rlPitch=54.0, llPitch=-19.2)),
        (0.650, E_LINEAR, P(rYaw=17.2, rPitch=-59.0, rRoll=-12.9, lYaw=2.9, lPitch=-24.9, lRoll=-9.9, bYaw=-7.1, bPitch=5.5, rlPitch=49.9, llPitch=-15.9)),
        (0.675, E_LINEAR, P(rYaw=20.9, rPitch=-58.4, rRoll=-10.2, lYaw=1.3, lPitch=-22.2, lRoll=-7.2, bYaw=-8.1, bPitch=4.7, rlPitch=47.2, llPitch=-13.8)),
        (0.700, E_LINEAR, P(rYaw=22.9, rPitch=-58.2, rRoll=-8.8, lYaw=0.5, lPitch=-20.8, lRoll=-5.8, bYaw=-8.7, bPitch=4.2, rlPitch=45.8, llPitch=-12.6)),
        (0.725, E_LINEAR, P(rYaw=23.8, rPitch=-58.0, rRoll=-8.2, lYaw=0.1, lPitch=-20.2, lRoll=-5.2, bYaw=-8.9, bPitch=4.0, rlPitch=45.2, llPitch=-12.1)),
        (0.750, E_LINEAR, P(rYaw=24.0, rPitch=-58.0, rRoll=-8.0, lPitch=-20.0, lRoll=-5.0, bYaw=-9.0, bPitch=4.0, rlPitch=45.0, llPitch=-12.0)),
        (0.775, E_LINEAR, P(rYaw=26.8, rPitch=-58.0, rRoll=-4.3, lPitch=-17.7, lRoll=-2.7, bYaw=-9.5, bPitch=3.5, rlPitch=40.4, llPitch=-10.1)),
        (0.800, E_LINEAR, P(rYaw=29.3, rPitch=-58.0, rRoll=-1.0, lPitch=-15.6, lRoll=-0.6, bYaw=-9.9, bPitch=3.1, rlPitch=36.2, llPitch=-8.5)),
        (0.825, E_LINEAR, P(rYaw=30.0, rPitch=-58.0, rRoll=-0.1, lPitch=-15.0, bYaw=-10.0, bPitch=3.0, rlPitch=35.1, llPitch=-8.0)),
        (0.850, E_LINEAR, P(rYaw=30.0, rPitch=-58.0, lPitch=-15.0, bYaw=-10.0, bPitch=3.0, rlPitch=34.9, llPitch=-7.7)),
        (0.875, E_LINEAR, P(rYaw=30.0, rPitch=-58.0, lPitch=-15.0, bYaw=-10.0, bPitch=3.4, rlPitch=33.8, llPitch=-4.8)),
        (0.900, E_LINEAR, P(rYaw=30.0, rPitch=-58.0, lPitch=-15.0, bYaw=-10.0, bPitch=3.9, rlPitch=32.4, llPitch=-1.2)),
        (0.925, E_LINEAR, P(rYaw=30.0, rPitch=-58.0, lPitch=-15.0, bYaw=-10.0, bPitch=3.9, rlPitch=31.9, llPitch=-0.4)),
        (0.950, E_LINEAR, P(rYaw=30.0, rPitch=-58.0, lPitch=-15.0, bYaw=-10.0, bPitch=3.3, rlPitch=31.2, llPitch=-2.2)),
        (0.975, E_LINEAR, P(rYaw=30.0, rPitch=-58.0, lPitch=-15.0, bYaw=-10.0, bPitch=2.6, rlPitch=30.6, llPitch=-4.1)),
        (1.000, E_LINEAR, P(rYaw=30.0, rPitch=-58.0, lPitch=-15.0, bYaw=-10.0, bPitch=2.0, rlPitch=30.0, llPitch=-6.0)),
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
