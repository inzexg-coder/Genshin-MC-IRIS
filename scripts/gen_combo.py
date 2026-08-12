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
  - УДАР МГНОВЕННЫЙ: t=0 — уже замах (клинок уведён), свинг стартует с
    первых кадров; пик скорости (момент урона) ~0.40 клипа — без долгой
    раскачки перед первым ударом.
  - Удары МАКСИМАЛЬНО ШИРОКИЕ: клинок выписывает огромные дуги (roll до
    ±155°, pitch −175°..+60°), корпус доворачивается (bYaw до ±50°) и
    наклоняется в удар (bPitch до +30°).
  - Левая рука ЖИВАЯ: противовес и «вторая волна» — поднимается в замах,
    хлещет в противоположную сторону в пике, сопровождает вылет.
  - Ноги ПЕРЕСТУПАЮТ: в каждом ударе явные шаги (pitch +/− до ±70°):
    выпад в удар, опора и перестановка между замахом и свингом.
  - Углы без полных оборотов (никаких проворотов на ±360° у рук).
  - Каждый клип непрерывен: t=0 — финальная поза предыдущего удара.
  - Свинг с УСКОРЕНИЕМ (E_IN_CUBIC) до пика скорости в момент урона
    (~0.40 клипа), сопровождение с торможением.
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

# Правки каналов Pose: правая рука y/p/r, левая рука y/p/r,
# корпус y/p/r, голова y/p/r, правая нога y/p/r, левая нога y/p/r.
# Углы задаются в градусах, конвертируются в радианы при генерации.

# ================= КЛИПЫ =================
# Каждая запись: (t, easing, Pose). easing применяется к СЕГМЕНТУ,
# начинающемуся с этого ключевого кадра (так устроен Clip.at в Java).
# Тайминг: t=0 — УЖЕ ЗАМАХ (клинок отведён — свинг стартует с первого
# кадра), разгон (E_IN_CUBIC) до пика ~0.40 (момент урона), затем
# торможение и финал. Позы максимально широкие (проверено anim_preview):
# roll до ±155°, pitch −178°..+60°, корпус доворачивается (bYaw до ±52°)
# и наклоняется в удар (bPitch до +30°), левая рука — живой противовес,
# ноги переступают (pitch до ±75°) с выпадом в момент урона.
# --- УДАР 1: очень широкий горизонтальный слева направо ---
CLIPS = [
    [
        (0.00, E_IN_OUT_SINE, P(rPitch=-70, rRoll=-115, lYaw=-10, lPitch=-80, lRoll=35, bYaw=28, bPitch=8, llPitch=50, rlPitch=-20)),      # замах: клинок отведён влево, левая нога вперёд
        (0.14, E_IN_OUT_CUBIC, P(rPitch=-100, rRoll=-80, lYaw=-12, lPitch=-55, lRoll=8, bYaw=14, bPitch=16, llPitch=25, rlPitch=-5)),    # разгон, корпус доворачивается
        (0.34, E_IN_CUBIC, P(rPitch=-105, rRoll=-15, lYaw=22, lPitch=-10, lRoll=-30, bYaw=-14, bPitch=28, llPitch=-20, rlPitch=65)),     # ПИК: клинок режет дугу, наклон, правая нога выпад
        (0.56, E_OUT_CUBIC, P(rPitch=-115, rRoll=55, lYaw=25, lPitch=-45, lRoll=-55, bYaw=-32, bPitch=18, llPitch=20, rlPitch=40)),      # вылет вправо, левая рука хлещет назад
        (0.78, E_OUT_CUBIC, P(rPitch=-130, rRoll=115, lYaw=5, lPitch=-30, lRoll=-15, bYaw=-42, bPitch=6, llPitch=40, rlPitch=10)),       # довод вправо-вверх
        (1.00, E_LINEAR, P(rPitch=-155, rRoll=140, lYaw=-5, lPitch=-25, lRoll=15, bYaw=-32)),                                            # финал = старт удара 2
    ],
    # --- УДАР 2: длинный апперкот справа снизу вверх влево ---
    [
        (0.00, E_IN_OUT_SINE, P(rPitch=-155, rRoll=140, lYaw=-5, lPitch=-25, lRoll=15, bYaw=-32)),                                       # стык: конец удара 1
        (0.14, E_IN_OUT_CUBIC, P(rPitch=-15, rRoll=145, lYaw=15, lPitch=-95, lRoll=25, bYaw=35, bPitch=15, llPitch=50, rlPitch=-20)),    # увод клинка вниз-вправо, присед
        (0.34, E_IN_CUBIC, P(rPitch=40, rRoll=115, lYaw=-10, lPitch=-65, lRoll=-5, bYaw=12, bPitch=5, llPitch=15, rlPitch=45)),          # ПИК: восходящий удар снизу вверх
        (0.56, E_OUT_CUBIC, P(rPitch=-100, rRoll=45, lYaw=-20, lPitch=-25, lRoll=-35, bYaw=-22, bPitch=-12, llPitch=-10, rlPitch=35)),   # пролёт вверх-влево, отклон корпуса
        (0.78, E_OUT_CUBIC, P(rPitch=-165, rRoll=-60, lYaw=-15, lPitch=10, lRoll=-45, bYaw=-40, bPitch=-16)),                            # уход вверх-влево
        (1.00, E_LINEAR, P(rPitch=-172, rRoll=-90, lYaw=-10, lPitch=0, lRoll=-20, bYaw=-35, bPitch=-8)),                                 # финал: клинок над головой = старт удара 3
    ],
    # --- УДАР 3: разворот через левое плечо на 360°, рубящий удар по диагонали ---
    [
        (0.00, E_IN_OUT_SINE, P(rPitch=-172, rRoll=-90, lYaw=-10, lPitch=0, lRoll=-20, bYaw=-35, bPitch=-8)),                            # стык: конец удара 2
        (0.14, E_IN_OUT_CUBIC, P(rPitch=-178, rRoll=-30, lYaw=-5, lPitch=-175, lRoll=-10, bYaw=0, bPitch=4, llPitch=35, rlPitch=-35)),   # обе руки вверх, раскрытый шаг
        (0.34, E_IN_CUBIC, P(rPitch=-120, rRoll=45, lYaw=-15, lPitch=-125, lRoll=-45, bYaw=22, bPitch=18, llPitch=-35, rlPitch=50)),     # ПИК: рубящий вниз-вправо
        (0.56, E_OUT_CUBIC, P(rPitch=-65, rRoll=100, lYaw=-20, lPitch=-55, lRoll=-60, bYaw=40, bPitch=10, llPitch=10, rlPitch=30)),      # довод вниз-вправо
        (0.78, E_OUT_CUBIC, P(rPitch=-50, rRoll=145, lYaw=-10, lPitch=-35, lRoll=-30, bYaw=30, bPitch=4, llPitch=35)),                   # клинок справа, доворот
        (1.00, E_LINEAR, P(rPitch=-60, rRoll=150, lPitch=-20, lRoll=10, bYaw=20)),                                                       # финал = старт удара 4
    ],
    # --- УДАР 4: очень широкий горизонтальный справа налево ---
    [
        (0.00, E_IN_OUT_SINE, P(rPitch=-60, rRoll=150, lPitch=-20, lRoll=10, bYaw=20)),                                                  # стык: конец удара 3
        (0.14, E_IN_OUT_CUBIC, P(rPitch=-80, rRoll=155, lYaw=12, lPitch=-85, lRoll=-35, bYaw=38, bPitch=12, llPitch=-25, rlPitch=55)),   # замах вправо-вверх
        (0.34, E_IN_CUBIC, P(rPitch=-110, rRoll=50, lYaw=-22, lPitch=-5, lRoll=30, bYaw=10, bPitch=30, llPitch=65, rlPitch=-20)),        # ПИК: пролёт справа налево, левая нога выпад
        (0.56, E_OUT_CUBIC, P(rPitch=-120, rRoll=-50, lYaw=-25, lPitch=-45, lRoll=50, bYaw=-25, bPitch=20, llPitch=40, rlPitch=15)),     # вылет влево
        (0.78, E_OUT_CUBIC, P(rPitch=-135, rRoll=-110, lYaw=-10, lPitch=-25, lRoll=20, bYaw=-38, bPitch=6, llPitch=10, rlPitch=40)),     # довод влево-вверх
        (1.00, E_LINEAR, P(rPitch=-145, rRoll=-130, lYaw=-5, lPitch=-30, lRoll=-15, bYaw=-32)),                                          # финал = старт удара 5
    ],
    # --- УДАР 5: гигантский замах, удар справа налево, клинок за спину, прокат ---
    [
        (0.00, E_IN_OUT_SINE, P(rPitch=-145, rRoll=-130, lYaw=-5, lPitch=-30, lRoll=-15, bYaw=-32)),                                     # стык: конец удара 4
        (0.12, E_IN_OUT_CUBIC, P(rPitch=-70, rRoll=110, lYaw=25, lPitch=-135, lRoll=50, bYaw=42, bPitch=15, llPitch=55, rlPitch=-30)),   # огромный замах вправо
        (0.26, E_IN_OUT_CUBIC, P(rYaw=-35, rPitch=-168, rRoll=150, lYaw=30, lPitch=-120, lRoll=25, bYaw=52, bPitch=12, llPitch=70, rlPitch=-20)),  # пик замаха за правым плечом
        (0.42, E_IN_CUBIC, P(rYaw=-30, rPitch=-115, rRoll=35, lYaw=-25, lPitch=-10, lRoll=35, bYaw=12, bPitch=30, llPitch=25, rlPitch=75)),        # ПИК: низкий свинг, прокат правой ногой
        (0.60, E_OUT_CUBIC, P(rYaw=-40, rPitch=-105, rRoll=-60, lYaw=-30, lPitch=-55, lRoll=55, bYaw=-28, bPitch=18, llPitch=-15, rlPitch=45)),    # пролёт влево-вниз
        (0.78, E_OUT_CUBIC, P(rYaw=-115, rPitch=55, rRoll=50, lYaw=-10, lPitch=-10, lRoll=-20, bYaw=-35, bPitch=-10)),                             # увод клинка за спину
        (1.00, E_LINEAR, P(rYaw=-120, rPitch=60, rRoll=55, lYaw=-5, lPitch=-15, lRoll=-25, bYaw=-32, bPitch=-8)),                                  # финал: клинок за спиной
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
    # начало: первый Pose удара 1 (или любой Pose-константы); отступ берём
    # с начала строки, чтобы повторные --write не накапливали пробелы
    start = src.rfind("\n", 0, src.index("private static final Pose hit1_00")) + 1
    # конец: "};" после private static final Clip[] CLIPS
    end_marker = "    };"
    end = src.index(end_marker, src.index("private static final Clip[] CLIPS"))
    end += len(end_marker)
    new_block = gen_java(clip_data, names)
    # ровно две пустые строки после CLIPS — повторные запуски не накапливают
    # пустые строки и отступы (файл остаётся стабильным)
    tail = src[end:].lstrip("\n")
    return src[:start] + new_block + "\n\n" + tail


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
