#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генератор анимаций комбо меча путешественника (5 ударов).

Позы задаются хореографией клинка (как в Genshin) + малыми углами корпуса:
  1. широкий горизонтальный удар слева направо;
  2. диагональ снизу справа вверх налево;
  3. разворот по кругу (оборот делает root модели);
  4. горизонтальный удар справа налево;
  5. очень широкий удар справа налево с замахом за спину и прокатом.

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
    # --- УДАР 1: очень широкий горизонтальный слева направо (замах через верх) ---
    [
        (0.00, E_IN_OUT_SINE, P(rPitch=35, lPitch=-10, lRoll=12)),
        (0.14, E_IN_OUT_SINE, P(rYaw=-35, rPitch=115, rRoll=20, lPitch=-110, lRoll=25,
                                bYaw=12, bPitch=2, rlPitch=-25, llPitch=15)),
        (0.28, E_IN_CUBIC,    P(rYaw=-20, rPitch=-40, rRoll=-20, lPitch=-80, lRoll=15,
                                bYaw=7, rlPitch=-15, llPitch=30)),
        (0.38, E_IN_CUBIC,    P(rYaw=-10, rPitch=-80, rRoll=-10, lPitch=-35,
                                bYaw=1, bPitch=4, rlPitch=20, llPitch=35)),
        (0.46, E_IN_CUBIC,    P(rYaw=0, rPitch=-90, rRoll=-10, lYaw=-10, lPitch=-25, lRoll=-15,
                                bYaw=-2, bPitch=5, rlPitch=55, llPitch=-25)),
        (0.56, E_OUT_CUBIC,   P(rYaw=15, rPitch=-85, rRoll=-30, lYaw=-8, lPitch=-35, lRoll=-20,
                                bYaw=-6, bPitch=3, rlPitch=40, llPitch=-10)),
        (0.70, E_OUT_CUBIC,   P(rYaw=25, rPitch=-55, rRoll=-20, lPitch=-25, lRoll=-5,
                                bYaw=-9, bPitch=2, rlPitch=25)),
        (0.84, E_OUT_CUBIC,   P(rYaw=30, rPitch=-20, rRoll=-5, lPitch=-15, bYaw=-8)),
        (1.00, E_LINEAR,      P(rYaw=35, rPitch=5, lPitch=-10, lRoll=5, bYaw=-6)),
    ],
    # --- УДАР 2: апперкот снизу-справа вверх-влево ---
    [
        (0.00, E_IN_OUT_SINE, P(rYaw=35, rPitch=5, lPitch=-10, lRoll=5, bYaw=-6)),
        (0.12, E_IN_OUT_SINE, P(rYaw=35, rPitch=-10, rRoll=5, lPitch=-40, lRoll=20,
                                bYaw=-6, rlPitch=-40, llPitch=-20)),
        (0.28, E_IN_CUBIC,    P(rYaw=15, rPitch=-60, rRoll=-10, lPitch=-25, lRoll=10,
                                bYaw=-2, rlPitch=-15, llPitch=25)),
        (0.40, E_IN_CUBIC,    P(rYaw=-15, rPitch=-105, rRoll=-20, lYaw=10, lPitch=-15, lRoll=-10,
                                bYaw=2, bPitch=5, rlPitch=50, llPitch=-20)),
        (0.54, E_OUT_CUBIC,   P(rYaw=-35, rPitch=-135, rRoll=-10, lYaw=8, lPitch=-30, lRoll=-15,
                                bYaw=8, rlPitch=30, llPitch=5)),
        (0.72, E_OUT_CUBIC,   P(rYaw=-45, rPitch=-155, lPitch=-25, lRoll=-5, bYaw=10)),
        (1.00, E_LINEAR,      P(rYaw=-30, rPitch=-160, rRoll=5, lPitch=-15, lRoll=5, bYaw=6)),
    ],
    # --- УДАР 3: разворот по кругу (полный оборот делает root) ---
    [
        (0.00, E_IN_OUT_SINE, P(rYaw=-30, rPitch=-160, rRoll=5, lPitch=-15, lRoll=5, bYaw=6)),
        (0.12, E_IN_OUT_SINE, P(rYaw=-45, rPitch=-115, lPitch=-95, lRoll=30,
                                bYaw=4, llPitch=35, rlPitch=-35)),
        (0.28, E_IN_CUBIC,    P(rYaw=-25, rPitch=-95, rRoll=-10, lPitch=-80, lRoll=15,
                                bYaw=-2, llPitch=15, rlPitch=-15)),
        (0.40, E_IN_CUBIC,    P(rYaw=-10, rPitch=-90, rRoll=-20, lPitch=-60, lRoll=5,
                                bYaw=-4, bPitch=4, llPitch=-25, rlPitch=45)),
        (0.52, E_IN_CUBIC,    P(rYaw=5, rPitch=-85, rRoll=-25, lPitch=-40,
                                bYaw=-5, llPitch=-10, rlPitch=30)),
        (0.64, E_OUT_CUBIC,   P(rYaw=15, rPitch=-80, rRoll=-15, lPitch=-25, lRoll=-5,
                                bYaw=-6, llPitch=25)),
        (0.78, E_OUT_CUBIC,   P(rYaw=10, rPitch=-70, rRoll=-5, lPitch=-15, lRoll=-5,
                                bYaw=-3, rlPitch=20)),
        (1.00, E_LINEAR,      P(rYaw=30, rPitch=-25, rRoll=5, lPitch=-15, lRoll=5, bYaw=-2)),
    ],
    # --- УДАР 4: очень широкий горизонтальный справа налево ---
    [
        (0.00, E_IN_OUT_SINE, P(rYaw=30, rPitch=-25, rRoll=5, lPitch=-15, lRoll=5, bYaw=-2)),
        (0.14, E_IN_OUT_SINE, P(rYaw=35, rPitch=35, rRoll=-10, lPitch=-110, lRoll=25,
                                bYaw=-10, bPitch=2, llPitch=-35, rlPitch=20)),
        (0.28, E_IN_CUBIC,    P(rYaw=25, rPitch=-25, rRoll=-5, lPitch=-80, lRoll=15,
                                bYaw=-6, llPitch=-15, rlPitch=30)),
        (0.38, E_IN_CUBIC,    P(rYaw=10, rPitch=-70, rRoll=5, lPitch=-35,
                                bYaw=-1, bPitch=4, llPitch=30, rlPitch=40)),
        (0.46, E_IN_CUBIC,    P(rYaw=0, rPitch=-90, rRoll=15, lYaw=12, lPitch=-25, lRoll=-15,
                                bYaw=2, bPitch=5, llPitch=55, rlPitch=-25)),
        (0.56, E_OUT_CUBIC,   P(rYaw=-12, rPitch=-85, rRoll=25, lYaw=8, lPitch=-35, lRoll=-20,
                                bYaw=6, llPitch=40, rlPitch=-10)),
        (0.70, E_OUT_CUBIC,   P(rYaw=-25, rPitch=-55, rRoll=15, lPitch=-25, lRoll=-5,
                                bYaw=9, llPitch=20)),
        (0.84, E_OUT_CUBIC,   P(rYaw=-30, rPitch=-20, rRoll=5, lPitch=-15, bYaw=8)),
        (1.00, E_LINEAR,      P(rYaw=-20, rPitch=-140, rRoll=10, lPitch=-10, lRoll=5, bYaw=5)),
    ],
    # --- УДАР 5: гигантский замах через голову, рубящий вперёд-влево, прокат ---
    [
        (0.00, E_IN_OUT_SINE, P(rYaw=-20, rPitch=-140, rRoll=10, lPitch=-10, lRoll=5, bYaw=5)),
        (0.12, E_IN_OUT_SINE, P(rYaw=-35, rPitch=-165, rRoll=15, lPitch=-115, lRoll=30,
                                bYaw=10, bPitch=2, rlPitch=-35, llPitch=10)),
        (0.24, E_IN_OUT_SINE, P(rYaw=-25, rPitch=-168, rRoll=10, lPitch=-125, lRoll=40,
                                bYaw=13, bPitch=3, rlPitch=-25, llPitch=20)),
        (0.36, E_IN_CUBIC,    P(rYaw=-10, rPitch=-120, rRoll=5, lPitch=-55, lRoll=10,
                                bYaw=4, bPitch=5, rlPitch=10, llPitch=35)),
        (0.46, E_IN_CUBIC,    P(rYaw=5, rPitch=-80, rRoll=-10, lYaw=14, lPitch=-30, lRoll=-20,
                                bYaw=-4, bPitch=8, rlPitch=-30, llPitch=60)),
        (0.58, E_OUT_CUBIC,   P(rYaw=-15, rPitch=-50, rRoll=-15, lYaw=10, lPitch=-40, lRoll=-15,
                                bYaw=-9, rlPitch=-10, llPitch=45)),
        (0.72, E_OUT_CUBIC,   P(rYaw=-25, rPitch=-20, rRoll=-5, lPitch=-25, lRoll=-5,
                                bYaw=-12, llPitch=20)),
        (0.86, E_OUT_CUBIC,   P(rYaw=-25, rPitch=10, lPitch=-20, bYaw=-9)),
        (1.00, E_LINEAR,      P(rPitch=35, lPitch=-10, lRoll=12)),
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
