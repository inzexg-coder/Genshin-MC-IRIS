#!/usr/bin/env python3
"""Генератор анимаций комбо меча путешественника (5 ударов).

Клипы заданы вручную по описанию ударов из Genshin (см. README ниже):
  1. широкий горизонтальный удар слева направо;
  2. длинный апперкот справа снизу вверх влево;
  3. разворот через левое плечо на 360° с рубящим ударом по диагонали
     (оборот делает root модели — всё тело поворачивается вместе);
  4. горизонтальный удар справа налево;
  5. очень широкий замах, удар справа налево, увод клинка за спину и прокат.

Принципы:
  - Голова ВСЕГДА 0 (смотрит строго вперёд относительно корпуса).
  - Левая рука — статичная стойка, ноги — ровно: части тела не двигаются
    сами по себе.
  - Углы в физиологичном диапазоне: рука pitch −160°..+60°, roll −110°..+110°,
    без полных оборотов (никаких проворотов на ±360°).
  - Каждый клип непрерывен: t=0 — финальная поза предыдущего удара.
  - Замах занимает ~60% клипа (медленно), свинг ~15% (быстро и решительно),
    сопровождение ~25% (плавный вылет).
  - Разворот (удар 3) крутит root.yaw модели целиком.

Порядок каналов Pose (radian): правая рука y/p/r, левая рука y/p/r,
корпус y/p/r, голова y/p/r, правая нога y/p/r, левая нога y/p/r.

Использование:
    python3 scripts/gen_combo.py            # предпросмотр ASCII-кадрами
    python3 scripts/gen_combo.py --write    # перезаписать позы в CombatController.java
    python3 scripts/gen_combo.py --render-hit 3
"""
import argparse
import math
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAVA = REPO / "mod/src/main/java/net/teyvat/client/CombatController.java"

E_LINEAR = 0
E_IN_OUT_CUBIC = 1
E_OUT_CUBIC = 2
E_OUT_BACK = 3
E_IN_OUT_SINE = 4

CH = ["rYaw", "rPitch", "rRoll", "lYaw", "lPitch", "lRoll",
      "bYaw", "bPitch", "bRoll", "hYaw", "hPitch", "hRoll",
      "rlYaw", "rlPitch", "rlRoll", "llYaw", "llPitch", "llRoll"]


def P(rYaw=0, rPitch=0, rRoll=0, lYaw=0, lPitch=0, lRoll=0,
      bYaw=0, bPitch=0, bRoll=0, hYaw=0, hPitch=0, hRoll=0,
      rlYaw=0, rlPitch=0, rlRoll=0, llYaw=0, llPitch=0, llRoll=0):
    return dict(zip(CH, [rYaw, rPitch, rRoll, lYaw, lPitch, lRoll,
                         bYaw, bPitch, bRoll, hYaw, hPitch, hRoll,
                         rlYaw, rlPitch, rlRoll, llYaw, llPitch, llRoll]))


D = math.degrees
R = math.radians

# --- левая рука: статичная стойка (не двигается сама по себе) ---
L_GUARD = (R(-10), R(-30), R(-5))

# --- нейтраль / старт первого удара: меч внизу-справа, корпус ровно ---
NEUTRAL = P(rPitch=R(-25), rRoll=R(8), lYaw=L_GUARD[0], lPitch=L_GUARD[1], lRoll=L_GUARD[2])

def arm(lh, pose):
    """проставить левую руку-стойку и ноги=0 в позу."""
    pose["lYaw"], pose["lPitch"], pose["lRoll"] = L_GUARD
    pose["rlYaw"] = pose["rlPitch"] = pose["rlRoll"] = 0.0
    pose["llYaw"] = pose["llPitch"] = pose["llRoll"] = 0.0
    return pose

# ================= КЛИПЫ =================
# Каждая запись: (t, easing, Pose) — углы в градусах, конвертируются в радианы.
CLIPS = [
    # --- УДАР 1: широкий горизонтальный слева направо ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-25, rRoll=8, bPitch=2))),
        (0.34, E_IN_OUT_CUBIC, arm(None, P(rPitch=-70, rRoll=-75, bYaw=18, bPitch=4))),   # замах влево-вверх
        (0.55, E_IN_OUT_CUBIC, arm(None, P(rPitch=-100, rRoll=-40, bYaw=8, bPitch=2))),   # начало свинга
        (0.68, E_LINEAR, arm(None, P(rPitch=-95, rRoll=-8, bYaw=-4))),                    # пролёт по центру (урон)
        (0.80, E_LINEAR, arm(None, P(rPitch=-100, rRoll=40, bYaw=-14))),                  # вылет вправо
        (1.00, E_OUT_CUBIC, arm(None, P(rPitch=-90, rRoll=72, bYaw=-18))),                # сопровождение вправо-вверх
    ],
    # --- УДАР 2: длинный апперкот справа снизу вверх влево ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-90, rRoll=72, bYaw=-18))),              # стык: конец удара 1
        (0.30, E_IN_OUT_CUBIC, arm(None, P(rPitch=-15, rRoll=60, bYaw=22, bPitch=6))),    # увод вниз-вправо
        (0.50, E_IN_OUT_CUBIC, arm(None, P(rPitch=22, rRoll=58, bYaw=26, bPitch=8))),     # глубокий замах вниз-вправо
        (0.64, E_LINEAR, arm(None, P(rPitch=-55, rRoll=10, bYaw=8, bPitch=2))),           # восходящий пролёт (урон)
        (0.78, E_LINEAR, arm(None, P(rPitch=-130, rRoll=-35, bYaw=-18))),                 # вверх-влево
        (1.00, E_OUT_CUBIC, arm(None, P(rPitch=-155, rRoll=-55, bYaw=-25, bPitch=-6))),   # финал вверху-слева
    ],
    # --- УДАР 3: разворот через левое плечо на 360°, рубящий удар по диагонали ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-155, rRoll=-55, bYaw=-25, bPitch=-6))), # стык: конец удара 2
        (0.30, E_IN_OUT_CUBIC, arm(None, P(rPitch=-150, rRoll=-25, bPitch=-4))),          # клинок поднят над головой
        (0.55, E_IN_OUT_CUBIC, arm(None, P(rPitch=-140, rRoll=-10, bPitch=2))),           # оборот продолжается
        (0.70, E_LINEAR, arm(None, P(rPitch=-85, rRoll=20, bPitch=8))),                   # рубящий удар вниз-вперёд (урон)
        (0.84, E_LINEAR, arm(None, P(rPitch=-45, rRoll=55, bPitch=10))),                  # довод вниз-вправо
        (1.00, E_OUT_CUBIC, arm(None, P(rPitch=-40, rRoll=60, bPitch=6))),                # сопровождение
    ],
    # --- УДАР 4: горизонтальный справа налево ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-40, rRoll=60, bPitch=6))),              # стык: конец удара 3
        (0.30, E_IN_OUT_CUBIC, arm(None, P(rPitch=-70, rRoll=78, bYaw=18, bPitch=2))),    # замах вправо-вверх
        (0.52, E_IN_OUT_CUBIC, arm(None, P(rPitch=-105, rRoll=40, bYaw=10))),             # начало свинга
        (0.66, E_LINEAR, arm(None, P(rPitch=-95, rRoll=-5, bYaw=-2))),                    # пролёт по центру (урон)
        (0.80, E_LINEAR, arm(None, P(rPitch=-100, rRoll=-45, bYaw=-12))),                 # вылет влево
        (1.00, E_OUT_CUBIC, arm(None, P(rPitch=-90, rRoll=-72, bYaw=-16))),               # сопровождение влево-вверх
    ],
    # --- УДАР 5: очень широкий замах, удар справа налево, клинок за спину, прокат ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-90, rRoll=-72, bYaw=-16))),             # стык: конец удара 4
        (0.22, E_IN_OUT_CUBIC, arm(None, P(rPitch=-70, rRoll=85, bYaw=30, bPitch=4))),    # огромный замах вправо
        (0.38, E_OUT_CUBIC, arm(None, P(rPitch=-150, rRoll=105, bYaw=38, bPitch=6))),     # пик замаха за правым плечом
        (0.52, E_LINEAR, arm(None, P(rYaw=-20, rPitch=-90, rRoll=40, bYaw=10, bPitch=4))),# свинг справа (урон)
        (0.66, E_LINEAR, arm(None, P(rYaw=-30, rPitch=-75, rRoll=-45, bYaw=-15, bPitch=-2))), # пролёт влево
        (0.84, E_OUT_CUBIC, arm(None, P(rYaw=-100, rPitch=60, rRoll=40, bYaw=-25, bPitch=-6))), # увод клинка за спину влево
        (1.00, E_OUT_CUBIC, arm(None, P(rYaw=-100, rPitch=55, rRoll=45, bYaw=-25, bPitch=-6))), # финал: клинок за спиной
    ],
]

NAMES = ["hit1", "hit2", "hit3", "hit4", "hit5"]


def to_radians(clip):
    out = []
    for t, e, pose in clip:
        rp = {k: math.radians(math.degrees(pose[k])) for k in CH}
        out.append((t, e, rp))
    return out


# ---------- генерация Java ----------

def fmt_pose(name, pose):
    # позы заданы в градусах — в Java пишем радианы (как ModelPart)
    vals = ", ".join(f"{math.radians(pose[k]):.6f}f" for k in CH)
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
    # начало: первый Pose удара 1 (или любой Pose-константы)
    start = src.index("private static final Pose hit1_00")
    # конец: "};" после private static final Clip[] CLIPS
    end_marker = "    };"
    end = src.index(end_marker, src.index("private static final Clip[] CLIPS"))
    end += len(end_marker)
    new_block = gen_java(clip_data, names)
    return src[:start] + new_block + "\n" + src[end:]


# ---------- предпросмотр ----------

def preview(clips, names):
    sys.path.insert(0, str(REPO / "scripts"))
    import anim_preview as ap
    for ci, (clip, name) in enumerate(zip(clips, names)):
        frames = [(t, e, {k: math.radians(pose[k]) for k in CH}) for t, e, pose in clip]
        print(f"\n===== {name} =====")
        for p in (0.0, 0.3, 0.5, 0.64, 0.8, 1.0):
            pose = ap.clip_at(frames, p)
            root_yaw = 0.0
            if ci == 2:  # удар 3 — разворот root
                u = max(0.0, min(1.0, (p - 0.14) / 0.62))
                root_yaw = 2 * math.pi * ap.ease(1, u)
            parts = ap.transform_points(pose, root_yaw)
            blades = {a: ap.blade_points(pose, root_yaw, a) for a in ("rightArm", "leftArm")}
            pdraw = dict(parts)
            for a in blades:
                pdraw[a] = parts[a] + [blades[a][0], blades[a][1]]
            g, _ = ap.draw(pdraw, root_yaw, "front")
            ap.show(g, f"{name} p={p:.2f} root={math.degrees(root_yaw):.0f}°")
            # краткая сводка клинка
            hand, tip = blades["rightArm"]
            print(f"   клинок кисть=({hand[0]:5.1f},{hand[1]:5.1f},{hand[2]:5.1f}) "
                  f"кончик=({tip[0]:5.1f},{tip[1]:5.1f},{tip[2]:5.1f})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="перезаписать позы в CombatController.java")
    ap.add_argument("--render", action="store_true", help="показать ASCII-предпросмотр")
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
