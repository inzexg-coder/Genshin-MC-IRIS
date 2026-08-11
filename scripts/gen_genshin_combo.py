#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генерация комбо путешественника по описанию боевки из Genshin.

Пять обычных атак путешественника (Итэр/Люмин) с мечом, как в игре:
  1. широкий горизонтальный удар слева направо;
  2. диагональный удар снизу справа вверх налево;
  3. удар с разворотом по кругу: клинок описывает круг справа сверху
     влево вниз (корпус делает большой поворот);
  4. горизонтальный удар справа налево;
  5. очень широкий удар справа налево, замах за спину и прокат вперёд.

Позы задаются направлением клинка (в локальной системе модели:
+X = вправо от персонажа, +Y = вверх, -Z = вперёд) и «закруткой» руки.
Углы руки считаются обратной кинематикой под рантайм модели Minecraft
(ModelPart: pitch -> yaw -> roll, JOML rotationZYX).

Использование: python3 scripts/gen_genshin_combo.py > /tmp/clips.java
"""
import math

# ---------- модель каналов ----------
# Порядок каналов Pose в CombatController.java:
#   правая рука y/p/r, левая рука y/p/r, корпус y/p/r,
#   голова y/p/r (NaN — не трогаем), правая нога y/p/r, левая нога y/p/r.
CHANS = ["ry", "rp", "rr", "ly", "lp", "lr",
         "by", "bp", "br", "hy", "hp", "hr",
         "rly", "rlp", "rlr", "lly", "llp", "llr"]

# Кривые рантайма CombatController.java
E_LINEAR = 0
E_IN_OUT_CUBIC = 1
E_OUT_CUBIC = 2
E_OUT_BACK = 3
E_IN_OUT_SINE = 4


def rot_z(g):
    c, s = math.cos(g), math.sin(g)
    return ((c, -s, 0), (s, c, 0), (0, 0, 1))


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return ((c, 0, s), (0, 1, 0), (-s, 0, c))


def rot_x(b):
    c, s = math.cos(b), math.sin(b)
    return ((1, 0, 0), (0, c, -s), (0, s, c))


def matmul(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3))
                 for i in range(3))


def apply(m, v):
    return tuple(sum(m[i][j] * v[j] for j in range(3)) for i in range(3))


def arm_angles(dx, dy, dz, gamma):
    """(yaw, pitch, roll) правой руки под направление клинка d и закрутку gamma.

    Рантайм: Rz(roll) * Ry(yaw) * Rx(pitch) * (0,-1,0) == d.
    """
    n = math.hypot(dx, dy, dz)
    dx, dy, dz = dx / n, dy / n, dz / n
    c, s = math.cos(gamma), math.sin(gamma)
    vx = dx * c + dy * s          # Rz(-gamma) * d
    vy = -dx * s + dy * c
    vz = dz
    r = math.hypot(vx, vz)
    beta = math.atan2(r, -vy)
    alpha = math.atan2(-vx, -vz)
    return (alpha, beta, gamma)


def pose(arm, larm, body, head, legs):
    """Собирает позу: arm=(dx,dy,dz,twist), остальные — тройки углов."""
    ay, ap, ar = arm_angles(*arm)
    out = {}
    out["ry"], out["rp"], out["rr"] = ay, ap, ar
    out["ly"], out["lp"], out["lr"] = larm
    out["by"], out["bp"], out["br"] = body
    if head is None:
        out["hy"] = out["hp"] = out["hr"] = None
    else:
        out["hy"], out["hp"], out["hr"] = head
    out["rly"], out["rlp"], out["rlr"], out["lly"], out["llp"], out["llr"] = legs
    return out


def neutral():
    return pose((0, -1, 0, 0), (0, 0, 0), (0, 0, 0), None, (0, 0, 0, 0, 0, 0))


# ---------- хореография ----------
# Каждый удар: список (u, easing, поза). Последний кадр удара N = первый
# кадр удара N+1 (бесшовные стыки), финал удара 5 = нейтраль.

def hit1():
    t2 = pose((0.55, -0.45, 0.55, 0.25), (0, -0.30, -0.10), (-0.15, 0.03, 0),
              (-0.10, -0.05, 0), (0, 0.02, 0, 0, 0.12, 0))
    return [
        (0.00, E_IN_OUT_CUBIC, neutral()),
        (0.14, E_IN_OUT_CUBIC, pose((-0.60, 0.25, 0.65, 0.30), (0, -0.15, -0.15),
                                    (0.30, 0, 0), (0.22, -0.03, 0),
                                    (0, 0.05, 0, 0, 0.05, 0))),
        (0.30, E_LINEAR, pose((-0.40, 0.02, -0.60, 0.45), (0, -0.10, -0.15),
                              (0.12, 0, 0), (0.10, 0, 0),
                              (0, 0.05, 0, 0, 0.05, 0))),
        (0.46, E_LINEAR, pose((0.85, -0.10, -0.40, 0.55), (0, 0, -0.10),
                              (-0.30, 0, 0.15), (-0.22, 0, 0),
                              (0, 0.05, 0, 0, 0.05, 0))),
        (0.60, E_OUT_CUBIC, pose((0.80, 0.15, 0.55, 0.45), (0, 0.05, -0.05),
                                 (-0.40, 0, 0.10), (-0.28, 0.05, 0),
                                 (0, 0.05, 0, 0, 0.05, 0))),
        (0.78, E_IN_OUT_SINE, pose((0.62, -0.05, 0.60, 0.35), (0, -0.10, -0.05),
                                   (-0.30, 0, 0.05), (-0.20, 0, 0),
                                   (0, 0.02, 0, 0, 0.10, 0))),
        (1.00, E_IN_OUT_SINE, t2),
    ]


def hit2():
    t3 = pose((0.68, 0.55, 0.38, 0.50), (-0.20, 0.15, 0.30), (-0.55, 0, 0),
              (-0.40, 0.15, 0), (0, 0, 0, 0, 0, 0))
    return [
        (0.00, E_IN_OUT_CUBIC, pose((0.55, -0.45, 0.55, 0.25), (0, -0.30, -0.10),
                                    (-0.15, 0.03, 0), (-0.10, -0.05, 0),
                                    (0, 0.02, 0, 0, 0.12, 0))),
        (0.14, E_IN_OUT_CUBIC, pose((0.62, -0.55, 0.45, 0.20), (0, -0.25, -0.10),
                                    (-0.25, 0.05, 0), (-0.18, -0.12, 0),
                                    (0, -0.05, 0, 0, 0.12, 0))),
        (0.32, E_LINEAR, pose((0.55, -0.25, -0.55, 0.40), (0, -0.15, -0.10),
                              (-0.05, 0, 0), (-0.05, -0.05, 0),
                              (0, -0.05, 0, 0, 0.12, 0))),
        (0.48, E_LINEAR, pose((-0.45, 0.62, -0.40, 0.50), (0, 0.05, -0.05),
                              (0.38, 0, 0), (0.28, -0.12, 0),
                              (0, -0.05, 0, 0, 0.12, 0))),
        (0.62, E_OUT_CUBIC, pose((-0.58, 0.55, 0.35, 0.40), (0, 0.10, 0),
                                 (0.48, 0, 0), (0.35, -0.10, 0),
                                 (0, -0.05, 0, 0, 0.12, 0))),
        (1.00, E_IN_OUT_SINE, t3),
    ]


def hit3():
    t4 = pose((0.70, 0.25, 0.60, 0.40), (-0.05, -0.05, 0), (1.00, 0, 0),
              (0.72, 0.05, 0), (0, 0, 0, 0, 0, 0))
    return [
        (0.00, E_IN_OUT_CUBIC, pose((0.68, 0.55, 0.38, 0.50), (-0.20, 0.15, 0.30),
                                    (-0.55, 0, 0), (-0.40, 0.15, 0),
                                    (0, 0, 0, 0, 0, 0))),
        (0.15, E_IN_OUT_CUBIC, pose((0.30, 0.62, -0.60, 0.50), (-0.15, 0.30, 0.20),
                                    (-0.25, 0, 0), (-0.20, 0.15, 0),
                                    (0, 0, 0, 0, 0, 0))),
        (0.30, E_LINEAR, pose((0.05, 0.30, -0.95, 0.60), (-0.10, 0.40, 0.15),
                              (0.55, 0, 0), (0.42, 0.08, 0),
                              (0, 0, 0, 0, 0, 0))),
        (0.45, E_LINEAR, pose((-0.55, 0.10, -0.80, 0.60), (0.10, 0.35, 0.10),
                              (1.50, 0, 0), (1.15, 0, 0),
                              (0, 0, 0, 0, 0, 0))),
        (0.60, E_LINEAR, pose((-0.65, -0.10, 0.65, 0.50), (0.15, 0.15, 0.05),
                              (2.45, 0, 0), (1.90, 0, 0),
                              (0, 0, 0, 0, 0, 0))),
        (0.76, E_OUT_CUBIC, pose((-0.55, -0.55, -0.40, 0.35), (0.10, 0, 0),
                                 (3.30, 0.10, 0), (2.55, 0.10, 0),
                                 (0.10, 0.15, 0, -0.10, -0.10, 0))),
        (0.90, E_IN_OUT_SINE, pose((0.30, 0.20, 0.70, 0.30), (0, 0, 0.05),
                                   (2.40, 0.05, 0), (1.90, 0.05, 0),
                                   (0, 0, 0, 0, 0, 0))),
        (1.00, E_IN_OUT_SINE, t4),
    ]


def hit4():
    t5 = pose((0.85, 0.35, 0.45, 0.50), (0, -0.10, -0.05), (0.45, 0, 0),
              (0.35, 0.08, 0), (0, 0.05, 0, 0, 0.05, 0))
    return [
        (0.00, E_IN_OUT_CUBIC, pose((0.70, 0.25, 0.60, 0.40), (-0.05, -0.05, 0),
                                    (1.00, 0, 0), (0.72, 0.05, 0),
                                    (0, 0, 0, 0, 0, 0))),
        (0.16, E_IN_OUT_CUBIC, pose((0.72, 0.28, 0.55, 0.35), (0, -0.15, -0.10),
                                    (0.45, 0, 0), (0.33, 0.05, 0),
                                    (0, 0.05, 0, 0, 0.05, 0))),
        (0.34, E_LINEAR, pose((0.78, -0.05, -0.45, 0.50), (0, -0.05, -0.10),
                              (0.10, 0, 0), (0.08, 0, 0),
                              (0, 0.05, 0, 0, 0.05, 0))),
        (0.50, E_LINEAR, pose((-0.75, -0.10, -0.45, 0.55), (0, 0.05, -0.05),
                              (-0.30, 0, -0.15), (-0.22, 0, 0),
                              (0, 0.05, 0, 0, 0.05, 0))),
        (0.64, E_OUT_CUBIC, pose((-0.80, 0.15, 0.55, 0.45), (0, 0.10, 0),
                                 (-0.45, 0, -0.10), (-0.30, 0.05, 0),
                                 (0, 0.05, 0, 0, 0.05, 0))),
        (1.00, E_IN_OUT_SINE, t5),
    ]


def hit5():
    lunge = pose((-0.60, 0.32, 0.68, 0.45), (0.20, -0.10, -0.05),
                 (0.90, 0.22, 0), (0.60, 0.15, 0),
                 (0, -0.50, -0.06, 0, 0.62, 0))
    return [
        (0.00, E_IN_OUT_CUBIC, pose((0.85, 0.35, 0.45, 0.50), (0, -0.10, -0.05),
                                    (0.45, 0, 0), (0.35, 0.08, 0),
                                    (0, 0.05, 0, 0, 0.05, 0))),
        (0.12, E_IN_OUT_CUBIC, pose((0.88, 0.30, 0.40, 0.55), (0, -0.20, -0.10),
                                    (-0.35, 0, 0), (-0.25, 0.10, 0),
                                    (0, 0.05, 0, 0, 0.05, 0))),
        (0.26, E_LINEAR, pose((0.80, -0.05, -0.50, 0.60), (0, -0.10, -0.10),
                              (-0.05, 0, 0), (-0.05, 0, 0),
                              (0, 0.05, 0, 0, 0.05, 0))),
        (0.42, E_LINEAR, pose((-0.35, -0.10, -0.85, 0.60), (0, 0.05, -0.05),
                              (0.35, 0, 0.10), (0.25, 0, 0),
                              (0, 0.05, 0, 0, 0.05, 0))),
        (0.56, E_OUT_CUBIC, pose((-0.80, 0.05, 0.55, 0.50), (0.10, 0.10, 0),
                                 (0.55, 0, 0.05), (0.40, 0, 0),
                                 (0, 0.05, 0, 0, 0.05, 0))),
        (0.70, E_OUT_CUBIC, pose((-0.75, 0.25, 0.62, 0.45), (0.15, 0.10, 0),
                                 (0.75, 0.05, 0), (0.55, 0.05, 0),
                                 (0, 0.05, 0, 0, 0.05, 0))),
        (0.86, E_IN_OUT_SINE, lunge),
        (1.00, E_IN_OUT_SINE, neutral()),
    ]


HITS = [hit1, hit2, hit3, hit4, hit5]
NAMES = ["hit1_wide_l2r", "hit2_diag_r2l_up", "hit3_spin_circle",
         "hit4_slash_r2l", "hit5_wide_r2l_lunge"]
DUR = [7, 7, 9, 7, 10]


# ---------- проверка ----------

def forward_pose(p):
    """Прямая кинематика: направление клинка из углов руки."""
    return apply(matmul(rot_z(p["rr"]), matmul(rot_y(p["ry"]), rot_x(p["rp"]))),
                 (0, -1, 0))


def pose_dist(a, b):
    return math.sqrt(sum((a[k] - b[k]) ** 2 for k in CHANS
                         if a[k] is not None and b[k] is not None))


def report():
    print("// Хореография (направление клинка правой руки, локальное пространство:")
    print("//   +X = вправо, +Y = вверх, -Z = вперёд; корпус: +yaw = поворот влево)")
    prev_end = None
    for fn, name, dur in zip(HITS, NAMES, DUR):
        clip = fn()
        print(f"// --- {name} (dur={dur})")
        for i, (u, ease, p) in enumerate(clip):
            d = forward_pose(p)
            seam = ""
            if prev_end is not None and i == 0:
                seam = f" seam={pose_dist(prev_end, p):.3f}"
            hy = "NaN" if p["hy"] is None else f"{p['hy']:+.2f}"
            print(f"//   u={u:.2f} blade=({d[0]:+.2f},{d[1]:+.2f},{d[2]:+.2f})"
                  f" bodyYaw={p['by']:+.2f} headYaw={hy}{seam}")
        prev_end = clip[-1][2]


# ---------- генерация Java ----------

def fmt(v):
    return "Float.NaN" if v is None else f"{v:.6f}f"


def emit():
    lines = []
    lines.append("    /** Позы пяти ударов, сгенерированы по описанию боевки")
    lines.append("     *  путешественника из Genshin (scripts/gen_genshin_combo.py):")
    lines.append("     *  удар 1 — широкий горизонтальный слева направо,")
    lines.append("     *  удар 2 — диагональ снизу справа вверх налево,")
    lines.append("     *  удар 3 — разворот по кругу (справа сверху влево вниз),")
    lines.append("     *  удар 4 — горизонтальный справа налево,")
    lines.append("     *  удар 5 — очень широкий справа налево, замах за спину")
    lines.append("     *  и прокат вперёд.")
    lines.append("     *  Стыки живут в хвосте предыдущего удара (EASE_IN_OUT_SINE),")
    lines.append("     *  финал 5-го уходит в нейтральную стойку.")
    lines.append("     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r,")
    lines.append("     *  корпус y/p/r, голова y/p/r (NaN — не трогаем),")
    lines.append("     *  прав. нога y/p/r, лев. нога y/p/r. Углы в радианах,")
    lines.append("     *  как у ModelPart (pitch -> yaw -> roll). */")
    for i, fn in enumerate(HITS):
        lines.append(f"    // Удар {i + 1}: {NAMES[i]}")
        for j, (u, ease, p) in enumerate(fn()):
            vals = ", ".join(fmt(p[k]) for k in CHANS)
            lines.append(f"    private static final Pose hit{i + 1}_{j:02d} = new Pose({vals});")
    lines.append("")
    lines.append("    /** Клипы пяти ударов: конец N = начало N+1 (переход в хвосте удара N). */")
    lines.append("    private static final Clip[] CLIPS = {")
    for i, fn in enumerate(HITS):
        lines.append(f"        new Clip(new Keyframe[] {{ // удар {i + 1}: {NAMES[i]}")
        for j, (u, ease, p) in enumerate(fn()):
            lines.append(f"                new Keyframe({u:.3f}f, {ease}, hit{i + 1}_{j:02d}),")
        lines.append("        }),")
    lines.append("    };")
    return "\n".join(lines)


if __name__ == "__main__":
    import sys
    if "--report" in sys.argv:
        report()
        print()
    print(emit())
