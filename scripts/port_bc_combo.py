#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Комбо путешественника из готовых анимаций BetterCombat (автор Daedelus),
подогнанное под описание обычных атак Итэра/Люмин из Genshin.

Источник: BetterCombat 26.2, assets/bettercombat/player_animations/*.json
(формат Emotecraft/PlayerAnimator, ключевые кадры tick + yaw/pitch/roll + easing).

Раскладка готовых клипов под описание комбо:
  удар 1 — горизонтальный слева направо -> one_handed_slash_horizontal_left;
  удар 2 — длинный апперкот справа снизу вверх влево -> one_handed_uppercut_right
           (финал довёрнут влево: клинок уходит вверх-влево);
  удар 3 — разворот через левое плечо на 360° с рубящим ударом -> two_handed_spin
           (поза клинка из спина; корпус/голова/руки проворачиваются ровно на
           +2π, клинок описывает полный круг и опускается по диагонали);
  удар 4 — горизонтальный справа налево -> one_handed_slash_horizontal_right;
  удар 5 — широкий замах и удар справа налево, меч уходит за спину ->
           one_handed_slash_switch_blade_left (финальная поза «клинок за
           спиной» удерживается, затем выход в нейтраль = прокат).

Что скрипт чинит сверх исходников:
  * голова ВСЕГДА смотрит по направлению удара: каналы головы прописаны
    отдельной кривой для каждого удара (yaw/pitch за клинком), без NaN;
  * части тела у ванильной модели независимы от корпуса (head/arms/legs —
    дети root, а не body), поэтому разворот на 360° крутит корпус, голову
    И руки вместе, иначе клинок «замирал» бы в мире, пока корпус крутится;
  * углы Эйлера разворачиваются в непрерывную кривую (unwrap), чтобы части
    тела не проворачивались на лишние ±360° на стыках;
  * хвосты-«удержания» исходников обрезаны, переходы живут в хвосте удара
    (EASE_IN_OUT_SINE), стык N -> N+1 точный (разница позы 0).

Темп: удары 1-2 и 4 — 0.8-0.9 с, разворот — 1.4 с, финал с мечом за спиной —
1.6 с (полный круг ~5.6 с). Замах медленный, свинг — быстрый рывок,
сопровождение — плавное дожимание: так удары ощущаются сильными.

Использование: python3 scripts/port_bc_combo.py > /tmp/clips.java
"""
import json
import math
import os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "anim_sources")
BONES = ["rightArm", "leftArm", "torso", "head", "rightLeg", "leftLeg"]
CHANS = ["yaw", "pitch", "roll"]
POSE_ORDER = [(b, c) for b in BONES for c in CHANS]

# Runtime easing ids (см. CombatController.java)
E_LINEAR = 0
E_SINE = 4

TAU = 2.0 * math.pi

# Активные окна исходных клипов. Порядок — под описание комбо.
HIT1 = ("hit2_slash_l2r.json", 8, 17)          # горизонтальный слева направо
HIT2 = ("hit3_uppercut.json", 8, 15)           # апперкот справа снизу вверх влево
SPIN = ("hit5_spin.json", 10, 16)              # позы клинка для разворота на 360°
HIT4 = ("hit1_slash_r2l.json", 8, 15)          # горизонтальный справа налево
HIT5 = ("hit5_wide_r2l_back.json", 8, 20)      # широкий замах -> за спину


def easing_fn(name):
    if name == "EASEINQUAD":
        return lambda t: t * t
    if name == "EASEOUTQUAD":
        return lambda t: 1 - (1 - t) * (1 - t)
    if name == "EASEINOUTQUAD":
        return lambda t: 2 * t * t if t < 0.5 else 1 - 2 * (1 - t) * (1 - t)
    return lambda t: t


def build_curves(e, bone, ch):
    kfs = []
    for m in e["moves"]:
        v = m.get(bone, {})
        if ch in v:
            kfs.append((m["tick"], v[ch], easing_fn(m.get("easing", "linear"))))
    kfs.sort()
    return kfs


def eval_curve(kfs, tick):
    if not kfs:
        return None
    if tick <= kfs[0][0]:
        return kfs[0][1]
    if tick >= kfs[-1][0]:
        return kfs[-1][1]
    for i in range(len(kfs) - 1):
        t0, v0, e0 = kfs[i]
        t1, v1, _ = kfs[i + 1]
        if tick <= t1:
            if t1 == t0:
                return v1
            u = (tick - t0) / (t1 - t0)
            return v0 + (v1 - v0) * e0(u)
    return kfs[-1][1]


def load(fname):
    with open(os.path.join(SRC, fname), encoding="utf-8") as f:
        return json.load(f)["emote"]


def pose_at(e, tick):
    pose = {}
    for bone in BONES:
        for ch in CHANS:
            v = eval_curve(build_curves(e, bone, ch), tick)
            pose[(bone, ch)] = 0.0 if v is None else v
    return pose


def neutral_pose():
    return {k: 0.0 for k in POSE_ORDER}


def blend(a, b, t):
    return {k: a[k] + (b[k] - a[k]) * t for k in POSE_ORDER}


def set_channel(pose, bone, ch, v):
    pose[(bone, ch)] = v


# ---------- голова за клинком ----------

def head_kf(keyframes):
    """Кривая головы: список (u, yaw, pitch). Возвращает функцию u -> (yaw, pitch)."""
    kf = sorted(keyframes)

    def at(u):
        if u <= kf[0][0]:
            return kf[0][1], kf[0][2]
        if u >= kf[-1][0]:
            return kf[-1][1], kf[-1][2]
        for i in range(len(kf) - 1):
            a, b = kf[i], kf[i + 1]
            if u <= b[0]:
                t = (u - a[0]) / (b[0] - a[0])
                return a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t
        return kf[-1][1], kf[-1][2]

    return at


# Кривые головы по ударам (yaw + = влево, pitch + = вниз, как у ModelPart).
HEADS = {
    # 1: слева направо — смотрим на клинок от замаха слева до сопровождения справа
    "hit1": head_kf([
        (0.00, 0.00, 0.00),
        (0.18, 0.90, 0.02),
        (0.34, 0.45, 0.00),
        (0.50, -0.20, 0.02),
        (0.62, -0.50, 0.12),
        (0.72, -0.55, 0.20),
        (1.00, -0.55, 0.28),
    ]),
    # 2: апперкот справа снизу вверх влево
    "hit2": head_kf([
        (0.00, -0.55, 0.28),
        (0.20, -0.45, 0.30),
        (0.32, -0.10, 0.10),
        (0.44, 0.20, -0.05),
        (0.56, 0.50, -0.20),
        (0.68, 0.70, -0.30),
        (1.00, 0.80, -0.30),
    ]),
    # 3: разворот — голова ведёт клинок, который закреплён на ~0.44 рад правее корпуса
    "hit3": head_kf([
        (0.00, 0.80, -0.30),
        (0.24, 2.37, -0.20),
        (0.55, 4.29, -0.12),
        (0.86, 6.97, -0.10),
        (1.00, 7.08, -0.10),
    ]),
    # 4: справа налево — голова возвращается из разворота, затем ведёт клинок
    "hit4": head_kf([
        (0.00, 7.08, -0.10),
        (0.28, -0.50, 0.08),
        (0.37, -0.10, 0.05),
        (0.46, 0.50, 0.00),
        (0.58, 0.80, 0.03),
        (0.68, 0.90, 0.05),
        (1.00, -0.55, 0.10),
    ]),
    # 5: широкий замах -> удар -> клинок за спиной, голова провожает его назад
    "hit5": head_kf([
        (0.00, -0.55, 0.10),
        (0.28, -0.95, 0.18),
        (0.39, -0.30, 0.05),
        (0.50, 0.35, -0.02),
        (0.60, 1.30, -0.10),
        (0.68, 1.70, -0.15),
        (0.80, 1.90, -0.18),
        (1.00, 0.00, 0.00),
    ]),
}


def apply_head(kf, head):
    for u, _, pose in kf:
        y, p = head(u)
        set_channel(pose, "head", "yaw", y)
        set_channel(pose, "head", "pitch", p)
        set_channel(pose, "head", "roll", 0.0)


# ---------- построение клипов ----------

def pose_dist(a, b):
    return math.sqrt(sum((a[k] - b[k]) ** 2 for k in POSE_ORDER))


def unwrap_clips(hits):
    """Разворачивает углы всех клипов в непрерывную кривую: значение кадра
    сдвигается на ±2π, чтобы отличаться от предыдущего не более чем на π."""

    prev = {}
    out = []
    for name, kf in hits:
        new_kf = []
        for u, ease, pose in kf:
            np_ = dict(pose)
            for k, v in np_.items():
                if k in prev:
                    while v - prev[k] > math.pi:
                        v -= TAU
                    while v - prev[k] < -math.pi:
                        v += TAU
                prev[k] = v
                np_[k] = v
            new_kf.append((u, ease, np_))
        out.append((name, new_kf))
    return out


def hit_swing(e, s_windup, swing_srcs, s_impact, follow_srcs, s_follow,
              u_seam, u_impact, u_end, start_pose, next_windup,
              tail_w=0.24, arm_yaw_override=None, windup_deepen=None):
    """Клип удара: [вход] -> замах (медленный) -> свинг (быстрый рывок) ->
    сопровождение -> переход в следующий замах (EASE_IN_OUT_SINE).

    arm_yaw_override: {tick_источника: yaw} — точечные довороты правой руки
    (например, финал апперкота уходит вверх-влево, как в описании).
    windup_deepen: {канал: сдвиг} — пик замаха чуть утрируется (клинок ниже,
    рука выше), чтобы фаза замаха двигалась, а не висела статично.
    """
    def src_pose(src):
        p = pose_at(e, src)
        if arm_yaw_override and src in arm_yaw_override:
            set_channel(p, "rightArm", "yaw", arm_yaw_override[src])
        if windup_deepen and src == s_windup:
            for (bone, ch), d in windup_deepen.items():
                set_channel(p, bone, ch, p[(bone, ch)] + d)
        return p

    kf = []
    kf.append((0.0, E_SINE, dict(start_pose)))
    kf.append((u_seam, E_SINE, src_pose(s_windup)))
    for i, src in enumerate(swing_srcs):
        u = u_seam + (u_impact - u_seam) * (i + 1) / (len(swing_srcs) + 1)
        kf.append((u, E_LINEAR, src_pose(src)))
    kf.append((u_impact, E_LINEAR, src_pose(s_impact)))
    for i, src in enumerate(follow_srcs):
        u = u_impact + (u_end - u_impact) * (i + 1) / (len(follow_srcs) + 1)
        kf.append((u, E_LINEAR, src_pose(src)))
    kf.append((u_end, E_LINEAR, src_pose(s_follow)))
    kf.append((u_end + tail_w, E_SINE, dict(next_windup)))
    kf.append((1.0, E_SINE, dict(next_windup)))
    return kf


def spin_clip(windup_pose, spin, spin_end_pose, head):
    """Удар 3: разворот через левое плечо на 360°. Корпус, голова и обе руки
    проворачиваются ровно на +2π (части модели независимы от корпуса), позы
    клинка — из two_handed_spin: клинок поднят и во время разворота опускается
    (рубящий удар по диагонали), а к концу уходит вправо-вверх к замаху удара 4."""
    spin_file, s0, s1 = spin
    se = load(spin_file)
    kf = []
    kf.append((0.00, E_SINE, dict(windup_pose)))
    srcs = list(range(s0 + 1, s1 + 1))          # t11..t16
    for i, src in enumerate(srcs):
        u = 0.14 + 0.72 * i / (len(srcs) - 1)   # 0.14 .. 0.86
        p = pose_at(se, src)
        set_channel(p, "torso", "yaw", 1.239 + TAU * i / (len(srcs) - 1))
        set_channel(p, "rightArm", "yaw", 0.80 + TAU * i / (len(srcs) - 1))
        set_channel(p, "leftArm", "yaw", p[("leftArm", "yaw")] + TAU * i / (len(srcs) - 1))
        kf.append((u, E_LINEAR, p))
    kf.append((0.86, E_SINE, dict(spin_end_pose)))
    kf.append((1.00, E_SINE, dict(spin_end_pose)))
    apply_head(kf, head)
    return kf


def build():
    neutral = neutral_pose()
    e1 = load(HIT1[0])
    e2 = load(HIT2[0])
    e4 = load(HIT4[0])
    e5 = load(HIT5[0])
    se = load(SPIN[0])

    # --- позы-переходы (общие для конца N и начала N+1) ---
    hit2_windup = pose_at(e2, 8)                       # вход удара 2
    spin_windup = pose_at(se, SPIN[1])                 # замах разворота
    set_channel(spin_windup, "torso", "yaw", 1.239)
    set_channel(spin_windup, "rightArm", "yaw", 0.80)
    set_channel(spin_windup, "leftArm", "yaw", spin_windup[("leftArm", "yaw")])
    # конец разворота = начало удара 4: поза t16 спина с доворотом корпуса/рук
    spin_end = pose_at(se, 16)
    set_channel(spin_end, "torso", "yaw", 1.239 + TAU)
    set_channel(spin_end, "rightArm", "yaw", 0.80 + TAU)
    set_channel(spin_end, "leftArm", "yaw", spin_end[("leftArm", "yaw")] + TAU)
    hit4_windup = pose_at(e4, 8)                       # замах удара 4 (R->L)
    hit5_windup = pose_at(e5, 8)                       # широкий замах удара 5

    hits = []

    # удар 1: горизонтальный слева направо, вход из нейтрали
    hit1 = hit_swing(e1, 8, [9, 10], 11, [13, 15], 17,
                     0.30, 0.50, 0.72, neutral, hit2_windup, tail_w=0.22)
    apply_head(hit1, HEADS["hit1"])
    hits.append(("hit2_slash_l2r (L->R)", hit1))

    # удар 2: апперкот справа снизу вверх влево (финал довёрнут влево)
    hit2 = hit_swing(e2, 8, [9, 10], 11, [12, 13], 15,
                     0.20, 0.44, 0.68, hit2_windup, spin_windup, tail_w=0.22,
                     arm_yaw_override={13: 0.55, 15: -0.35},
                     windup_deepen={("rightArm", "pitch"): 0.35,
                                    ("torso", "pitch"): 0.10})
    apply_head(hit2, HEADS["hit2"])
    hits.append(("hit3_uppercut (R low -> L up)", hit2))

    # удар 3: разворот через левое плечо на 360°
    hit3 = spin_clip(spin_windup, SPIN, spin_end, HEADS["hit3"])
    hits.append(("hit5_spin (360deg left)", hit3))

    # удар 4: горизонтальный справа налево
    hit4 = hit_swing(e4, 8, [9, 10], 11, [13], 15,
                     0.28, 0.46, 0.68, spin_end, hit5_windup, tail_w=0.22)
    apply_head(hit4, HEADS["hit4"])
    hits.append(("hit1_slash_r2l (R->L)", hit4))

    # удар 5: широкий замах, удар справа налево, клинок за спину, прокат в нейтраль
    hit5 = hit_swing(e5, 8, [9, 10, 11], 12, [14, 16], 18,
                     0.28, 0.50, 0.68, hit5_windup, neutral, tail_w=0.16,
                     windup_deepen={("rightArm", "pitch"): -0.55,
                                    ("rightArm", "yaw"): 0.25,
                                    ("torso", "pitch"): -0.12})
    # финальная поза «клинок за спиной» (t18) держится, затем выход в нейтраль:
    # заменяем нейтральный хвост hit_swing на hold позы и мягкий прокат в стойку
    behind_back = pose_at(e5, 18)
    hit5[-2:] = [
        (0.72, E_SINE, dict(behind_back)),
        (0.84, E_SINE, dict(behind_back)),
        (1.00, E_SINE, dict(neutral)),
    ]
    apply_head(hit5, HEADS["hit5"])
    hits.append(("hit5_switch_blade (wide R->L + behind back)", hit5))

    # непрерывная кривая: без проворотов частей тела на ±360° на стыках
    hits = unwrap_clips(hits)

    seams = []
    for i in range(1, len(hits)):
        d = pose_dist(hits[i - 1][1][-1][2], hits[i][1][0][2])
        seams.append((hits[i][0], d))
    return hits, seams


# ---------- анализ ----------

def fk_tip(pose, length=26.0):
    yaw = pose[("rightArm", "yaw")]
    pitch = pose[("rightArm", "pitch")]
    roll = pose[("rightArm", "roll")]
    x, y, z = 0.0, -length, 0.0
    cp, sp = math.cos(pitch), math.sin(pitch)
    y, z = y * cp - z * sp, y * sp + z * cp
    cy, sy = math.cos(yaw), math.sin(yaw)
    x, z = x * cy + z * sy, -x * sy + z * cy
    cr, sr = math.cos(roll), math.sin(roll)
    x, y = x * cr - y * sr, x * sr + y * cr
    return (5.0 + x, 22.0 + y, z)


def runtime_at(kf, p):
    if p <= kf[0][0]:
        return kf[0][2]
    for i in range(len(kf) - 1):
        a, b = kf[i], kf[i + 1]
        if p <= b[0]:
            span = b[0] - a[0]
            u = 1.0 if span <= 0 else (p - a[0]) / span
            if a[1] == E_SINE:
                u = 0.5 * (1 - math.cos(math.pi * u))
            return blend(a[2], b[2], u)
    return kf[-1][2]


def impact_moments(hits, dur):
    res = []
    windows = [(0.30, 0.55), (0.30, 0.55), (0.20, 0.80), (0.30, 0.55), (0.32, 0.56)]
    for (name, kf), d, (u0, u1) in zip(hits, dur, windows):
        best_u, best_v = u0, 0.0
        for i in range(len(kf) - 1):
            a, b = kf[i], kf[i + 1]
            if b[0] < u0 or a[0] > u1:
                continue
            span = b[0] - a[0]
            if span <= 0:
                continue
            v = math.dist(fk_tip(a[2]), fk_tip(b[2])) / span
            if v > best_v:
                best_v = v
                best_u = (a[0] + b[0]) / 2.0
        res.append((name, best_u, best_v, round(best_u * d)))
    return res


def motion_profile(hits, dur):
    out = []
    prev = None
    for (name, kf), d in zip(hits, dur):
        speeds = []
        for f in range(d * 20 + 1):
            p = f / (d * 20)
            pose = runtime_at(kf, p)
            if prev is not None:
                speeds.append(pose_dist(pose, prev))
            prev = pose
        frozen = sum(1 for s in speeds if s < 0.03)
        out.append((name, d, sum(speeds) / len(speeds), max(speeds), frozen))
    return out


# ---------- генерация Java ----------

def fmt(v):
    return f"{v:.6f}f"


def emit(hits):
    lines = []
    lines.append("    /** Позы пяти ударов, сгенерированы из готовых анимаций BetterCombat")
    lines.append("     *  (scripts/port_bc_combo.py, исходники в scripts/anim_sources, автор")
    lines.append("     *  анимаций Daedelus): удар 1 — горизонтальный слева направо,")
    lines.append("     *  удар 2 — длинный апперкот справа снизу вверх влево, удар 3 —")
    lines.append("     *  разворот через левое плечо на 360° с рубящим ударом, удар 4 —")
    lines.append("     *  горизонтальный справа налево, удар 5 — широкий замах и удар")
    lines.append("     *  справа налево с уводом клинка за спину.")
    lines.append("     *  Голова всегда смотрит по направлению удара (каналы головы")
    lines.append("     *  прописаны кривой за клинком). Разворот крутит корпус, голову")
    lines.append("     *  и руки ровно на +2π — части ванильной модели независимы от")
    lines.append("     *  корпуса. Углы развёрнуты в непрерывную кривую (без проворотов")
    lines.append("     *  на ±360°), стыки живут в хвосте удара (EASE_IN_OUT_SINE).")
    lines.append("     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r, корпус y/p/r,")
    lines.append("     *  голова y/p/r, прав. нога y/p/r, лев. нога y/p/r. Углы в радианах,")
    lines.append("     *  как у ModelPart (pitch -> yaw -> roll). */")
    names = ["hit1", "hit2", "hit3", "hit4", "hit5"]
    for hi, (name, kf) in enumerate(hits):
        lines.append(f"    // Удар {hi + 1}: {name}")
        for si, (u, ease, pose) in enumerate(kf):
            vals = ", ".join(fmt(pose[k]) for k in POSE_ORDER)
            lines.append(f"    private static final Pose {names[hi]}_{si:02d} = new Pose({vals});")
    lines.append("")
    lines.append("    /** Клипы пяти ударов: конец N = начало N+1 (переход в хвосте удара N). */")
    lines.append("    private static final Clip[] CLIPS = {")
    for hi, (name, kf) in enumerate(hits):
        lines.append(f"        new Clip(new Keyframe[] {{ // удар {hi + 1}: {name}")
        for si, (u, ease, pose) in enumerate(kf):
            lines.append(f"                new Keyframe({u:.3f}f, {ease}, {names[hi]}_{si:02d}),")
        lines.append("        }),")
    lines.append("    };")
    return "\n".join(lines)


def main():
    hits, seams = build()
    DUR = [16, 18, 28, 18, 32]
    print("// Стыки (макс. разница позы между концом N и началом N+1, рад):")
    for name, d in seams:
        print(f"//   -> {name}: {d:.3f}")
    print("// Удар/тик урона (пик скорости меча, доля клипа):")
    for name, u, v, tick in impact_moments(hits, DUR):
        print(f"//   {name}: u={u:.3f} speed={v:.1f} -> DAMAGE_TICK={tick}")
    print("// Профиль движения на 20 FPS (avg/max скорость позы, замороженных кадров <0.03):")
    for name, d, avg, mx, frozen in motion_profile(hits, DUR):
        print(f"//   {name}: dur={d} avg={avg:.3f} max={mx:.2f} frozen={frozen}/{d * 20}")
    print()
    print(emit(hits))


if __name__ == "__main__":
    main()
