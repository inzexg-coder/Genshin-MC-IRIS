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
  - Левая рука — статичная стойка; ноги в позах = NaN (ванильный шаг из
    setAngles продолжает работать во время удара — герой не «плывёт»).
  - Углы в физиологичном диапазоне: рука pitch −160°..+60°, roll −110°..+110°,
    без полных оборотов (никаких проворотов на ±360°).
  - Каждый клип непрерывен: t=0 — финальная поза предыдущего удара.
  - Замах ~58% клипа (медленно), свинг с УСКОРЕНИЕМ (E_IN_CUBIC) до пика
    скорости в момент урона (~0.70 клипа), сопровождение с торможением.
  - Между ударами — плавные переходы (в Java первые 12% клипа смешиваются
    с предыдущей позой через prevAppliedPose).
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
E_IN_CUBIC = 5

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
NEUTRAL["rlYaw"] = NEUTRAL["rlPitch"] = NEUTRAL["rlRoll"] = float("nan")
NEUTRAL["llYaw"] = NEUTRAL["llPitch"] = NEUTRAL["llRoll"] = float("nan")

def arm(lh, pose):
    """проставить левую руку-стойку и ноги=NaN в позу.

    NaN для ног = не трогаем канал: ванильная анимация шага продолжает
    работать во время удара (герой не «плывёт» по земле, а бежит)."""
    pose["lYaw"], pose["lPitch"], pose["lRoll"] = L_GUARD
    pose["rlYaw"] = pose["rlPitch"] = pose["rlRoll"] = float("nan")
    pose["llYaw"] = pose["llPitch"] = pose["llRoll"] = float("nan")
    return pose

# ================= КЛИПЫ =================
# Каждая запись: (t, easing, Pose) — углы в градусах, конвертируются в радианы.
CLIPS = [
    # ВНИМАНИЕ: easing в кортеже (t, easing, поза) применяется к СЕГМЕНТУ,
    # начинающемуся с этого ключевого кадра (так устроен Clip.at в Java).
    # Свинг (разгон тяжёлого удара) = E_IN_CUBIC, сопровождение = E_OUT_CUBIC.
    # Удары максимально ШИРОКИЕ: клинок выписывает большие дуги (roll до ±125°,
    # pitch до −160°), замах ~50% клипа, свинг с ускорением до пика (урон 0.70).
    # --- УДАР 1: очень широкий горизонтальный слева направо ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-25, rRoll=10, bPitch=2))),
        (0.34, E_IN_OUT_CUBIC, arm(None, P(rPitch=-75, rRoll=-100, bYaw=24, bPitch=5))),   # замах далеко влево-вверх
        (0.52, E_IN_CUBIC, arm(None, P(rPitch=-110, rRoll=-55, bYaw=12, bPitch=3))),       # СВИНГ: разгон до пика (урон на 0.70)
        (0.70, E_OUT_CUBIC, arm(None, P(rPitch=-100, rRoll=-5, bYaw=-6))),                 # пик скорости, вылет
        (0.84, E_OUT_CUBIC, arm(None, P(rPitch=-105, rRoll=60, bYaw=-18))),                # вылет вправо
        (1.00, E_LINEAR, arm(None, P(rPitch=-95, rRoll=95, bYaw=-24))),                    # финал справа-вверх
    ],
    # --- УДАР 2: длинный апперкот справа снизу вверх влево ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-95, rRoll=95, bYaw=-24))),               # стык: конец удара 1
        (0.30, E_IN_OUT_CUBIC, arm(None, P(rPitch=-10, rRoll=80, bYaw=26, bPitch=8))),     # увод вниз-вправо
        (0.50, E_IN_CUBIC, arm(None, P(rPitch=32, rRoll=75, bYaw=32, bPitch=10))),         # СВИНГ: разгон восходящего (урон на 0.70)
        (0.70, E_OUT_CUBIC, arm(None, P(rPitch=-60, rRoll=12, bYaw=12, bPitch=4))),        # пик скорости, пролёт
        (0.84, E_OUT_CUBIC, arm(None, P(rPitch=-140, rRoll=-45, bYaw=-20))),               # вверх-влево
        (1.00, E_LINEAR, arm(None, P(rPitch=-160, rRoll=-70, bYaw=-28, bPitch=-8))),       # финал вверху-слева
    ],
    # --- УДАР 3: разворот через левое плечо на 360°, рубящий удар по диагонали ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-160, rRoll=-70, bYaw=-28, bPitch=-8))),  # стык: конец удара 2
        (0.28, E_IN_OUT_CUBIC, arm(None, P(rPitch=-155, rRoll=-30, bYaw=-8, bPitch=-4))),  # клинок поднят над головой
        (0.50, E_IN_CUBIC, arm(None, P(rPitch=-145, rRoll=-5, bYaw=8, bPitch=4))),         # РУБЯЩИЙ: разгон вниз (урон на 0.70)
        (0.70, E_OUT_CUBIC, arm(None, P(rPitch=-80, rRoll=35, bYaw=16, bPitch=10))),       # пик скорости, довод
        (0.84, E_OUT_CUBIC, arm(None, P(rPitch=-40, rRoll=75, bYaw=18, bPitch=12))),       # довод вниз-вправо
        (1.00, E_LINEAR, arm(None, P(rPitch=-35, rRoll=85, bYaw=12, bPitch=8))),           # сопровождение
    ],
    # --- УДАР 4: очень широкий горизонтальный справа налево ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-35, rRoll=85, bYaw=12, bPitch=8))),      # стык: конец удара 3
        (0.30, E_IN_OUT_CUBIC, arm(None, P(rPitch=-75, rRoll=105, bYaw=24, bPitch=4))),    # замах вправо-вверх
        (0.50, E_IN_CUBIC, arm(None, P(rPitch=-115, rRoll=55, bYaw=14, bPitch=2))),        # СВИНГ: разгон до пика (урон на 0.70)
        (0.70, E_OUT_CUBIC, arm(None, P(rPitch=-100, rRoll=-8, bYaw=-6))),                 # пик скорости, вылет
        (0.84, E_OUT_CUBIC, arm(None, P(rPitch=-105, rRoll=-65, bYaw=-16))),               # вылет влево
        (1.00, E_LINEAR, arm(None, P(rPitch=-95, rRoll=-95, bYaw=-22))),                   # финал влево-вверх
    ],
    # --- УДАР 5: гигантский замах, удар справа налево, клинок за спину, прокат ---
    [
        (0.00, E_IN_OUT_SINE, arm(None, P(rPitch=-95, rRoll=-95, bYaw=-22))),              # стык: конец удара 4
        (0.22, E_IN_OUT_CUBIC, arm(None, P(rPitch=-70, rRoll=110, bYaw=36, bPitch=5))),    # огромный замах вправо
        (0.38, E_IN_OUT_CUBIC, arm(None, P(rPitch=-155, rRoll=125, bYaw=45, bPitch=8))),   # пик замаха за правым плечом
        (0.56, E_IN_CUBIC, arm(None, P(rYaw=-25, rPitch=-100, rRoll=50, bYaw=14, bPitch=5))),   # СВИНГ: разгон справа налево (урон на 0.70)
        (0.70, E_OUT_CUBIC, arm(None, P(rYaw=-35, rPitch=-85, rRoll=-55, bYaw=-18, bPitch=-4))), # пик скорости, пролёт
        (0.84, E_OUT_CUBIC, arm(None, P(rYaw=-110, rPitch=65, rRoll=45, bYaw=-28, bPitch=-8))),  # увод клинка за спину влево
        (1.00, E_LINEAR, arm(None, P(rYaw=-115, rPitch=60, rRoll=50, bYaw=-28, bPitch=-8))),     # финал: клинок за спиной
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
    # позы заданы в градусах — в Java пишем радианы (как ModelPart);
    # NaN (ноги) -> Float.NaN: канал не трогаем при наложении позы
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
