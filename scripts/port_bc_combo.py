#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Портирование атак путешественника из готовых анимаций BetterCombat.

Источник: BetterCombat 2.4.0, assets/bettercombat/player_animations/*.json
(формат Emotecraft/PlayerAnimator, автор анимаций Daedelus). Это данные,
а не код: ключевые кадры (tick + yaw/pitch/roll в радианах + easing).

Проблема исходных клипов: свинг занимает только первые ~25-35% клипа
(ключевые кадры на tick 8->9->11->15), а с tick 16 до 20 идёт статичная
«удерживаемая» поза — при проигрывании она даёт заморозку в конце удара.
Плюс стыки независимых клипов имеют большие скачки позы (до 3.6 рад),
которые раньше «дёргали» модель назад в замах следующего удара.

Новая раскладка:
  удар 1 — нейтраль -> замах -> свинг -> сопровождение -> переход
  удары 2-4 — стойка замаха -> свинг (быстро, удар на ~30% клипа) ->
              сопровождение -> плавный переход к замаху следующего удара
              (переход живёт В ХВОСТЕ предыдущего удара, поэтому на стыке
              нет ни скачка, ни отскока в ванильную стойку)
  удар 5 — разворот над головой (spin) -> обрушение (slam) -> нейтраль

Статичные хвосты (tick 16-20) обрезаны, каждая фаза пересэмплируется
с точным воспроизведением исходных easing-кривых, переходные сегменты
получают EASE_IN_OUT_SINE в рантайме.

Использование: python3 scripts/port_bc_combo.py > /tmp/clips.java
"""
import json
import math
import os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "anim_sources")
BONES = ["rightArm", "leftArm", "torso", "head", "rightLeg", "leftLeg"]
CHANS = ["yaw", "pitch", "roll"]
POSE_ORDER = [(b, c) for b in BONES for c in CHANS]
NAN_CHANS = {("head", "yaw"), ("head", "pitch"), ("head", "roll")}

# Runtime easing ids (см. CombatController.java)
E_LINEAR = 0
E_SINE = 4

# Активные окна исходных клипов: tick замаха .. tick конца сопровождения
# (за пределами окна начинается статичная «удерживаемая» поза).
WINDOWS = [
    ("hit1_slash_r2l.json", 8, 15),
    ("hit2_slash_l2r.json", 8, 17),
    ("hit3_uppercut.json", 8, 15),
    ("hit4_stab.json", 8, 12),
]
SPIN = ("hit5_spin.json", 10, 18)   # разворот: build-up спина
SLAM = ("hit5_slam.json", 8, 12)    # обрушение: свинг вниз


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
            pose[(bone, ch)] = eval_curve(build_curves(e, bone, ch), tick)
    return pose


def neutral_pose():
    return {k: (None if k in NAN_CHANS else 0.0) for k in POSE_ORDER}


def blend(a, b, t):
    out = {}
    for k in POSE_ORDER:
        va, vb = a[k], b[k]
        if va is None or vb is None:
            out[k] = va if vb is None else vb
        else:
            out[k] = va + (vb - va) * t
    return out


def pose_dist(a, b):
    d = 0.0
    for k in POSE_ORDER:
        if a[k] is None or b[k] is None:
            continue
        d += (a[k] - b[k]) ** 2
    return math.sqrt(d)


def hit_swing(e, s0, s1, u_seam, u_impact, u_end, start_pose, next_windup,
              tail_w=0.30, head_nan=False, sample_src=None):
    """Клип удара: [вход] -> замах(s0) -> свинг(11) -> сопровождение(s1) -> переход в следующий замах.

    Ширина хвоста tail_w выбирается по размеру скачка между позами (см. build):
    маленький скачок — короткий резкий переход, большой — плавный разворот.
    Хвост — ОДИН сегмент EASE_IN_OUT_SINE: мягкий разгон после сопровождения
    и «усаживание» в замах следующего удара (без двойного разгона).
    """
    kf = []
    kf.append((0.0, E_SINE, dict(start_pose)))
    kf.append((u_seam, E_SINE, pose_at(e, s0)))
    swing_src = sample_src or (9.0, 10.0)
    for src in swing_src:
        u = u_seam + (u_impact - u_seam) * (src - s0) / (11 - s0)
        kf.append((u, E_LINEAR, pose_at(e, src)))
    kf.append((u_impact, E_LINEAR, pose_at(e, 11)))
    for i in range(1, 4):
        src = 11 + (s1 - 11) * i / 4.0
        u = u_impact + (u_end - u_impact) * i / 4.0
        kf.append((u, E_LINEAR, pose_at(e, src)))
    kf.append((u_end, E_LINEAR, pose_at(e, s1)))
    kf.append((u_end + tail_w, E_SINE, next_windup))
    kf.append((1.0, E_SINE, next_windup))
    if head_nan:
        for k in NAN_CHANS:
            for _, _, p in kf:
                p[k] = None
    return kf


def hit5_clip(start_pose, spin, slam, head_nan=True):
    """Удар 5: spin (разворот-замах) -> slam (обрушение) -> нейтраль."""
    spin_file, s0, s1 = spin
    slam_file, sl0, sl1 = slam
    se = load(spin_file)
    se2 = load(slam_file)
    kf = []
    kf.append((0.00, E_SINE, dict(start_pose)))          # конец удара 4 (замах спина)
    # разворот: плотная сетка (резкий поворот tick 13->14 разбит на сегменты)
    spin_src = (s0 + 2, s0 + 3, s0 + 4, s0 + 5, s0 + 6, s1)
    for i, src in enumerate(spin_src):
        u = 0.06 + 0.34 * i / (len(spin_src) - 1)
        kf.append((u, E_LINEAR, pose_at(se, src)))
    # переход spin -> замах slam: растянут, мягкий sine
    kf.append((0.58, E_SINE, pose_at(se2, sl0 + 1)))
    # свинг slam вниз: sl0+1 -> sl1 (удар на ~0.70 клипа)
    kf.append((0.68, E_LINEAR, pose_at(se2, sl0 + 2)))
    kf.append((0.76, E_LINEAR, pose_at(se2, sl1)))
    kf.append((0.88, E_SINE, blend(pose_at(se2, sl1), neutral_pose(), 0.5)))
    kf.append((1.00, E_SINE, neutral_pose()))
    if head_nan:
        for k in NAN_CHANS:
            for _, _, p in kf:
                p[k] = None
    return kf


def build():
    hits = []
    neutral = neutral_pose()
    next_windups = [pose_at(load(f), 8) for f, _, _ in WINDOWS]
    spin_windup = pose_at(load(SPIN[0]), SPIN[1])

    # ширина хвоста-перехода: 0.10 рад скачка -> 0.10 клипа, мин 0.14, макс 0.42
    def tail_w(gap):
        return min(0.42, max(0.14, gap * 0.10))

    # удар 1: плавный вход из нейтрали в замах, короткий хвост (скачок до удара 2 мал)
    e1 = load(WINDOWS[0][0])
    gap1 = pose_dist(pose_at(e1, WINDOWS[0][2]), next_windups[1])
    hit1 = hit_swing(e1, WINDOWS[0][1], WINDOWS[0][2],
                     0.16, 0.38, 0.60, neutral, next_windups[1], tail_w=tail_w(gap1))
    hits.append(("hit1_slash_r2l.json", hit1))

    # удары 2-3: старт из замаха (переход уже сделан хвостом предыдущего)
    for i in (1, 2):
        f, s0, s1 = WINDOWS[i]
        e = load(f)
        gap = pose_dist(pose_at(e, s1), next_windups[i + 1])
        clip = hit_swing(e, s0, s1, 0.04, 0.34, 0.58,
                         pose_at(e, 8), next_windups[i + 1], tail_w=tail_w(gap))
        hits.append((f, clip))

    # удар 4 (выпад-укол): короткое сопровождение, хвост до замаха спина
    f, s0, s1 = WINDOWS[3]
    e = load(f)
    gap = pose_dist(pose_at(e, s1), spin_windup)
    hit4 = hit_swing(e, s0, s1, 0.04, 0.32, 0.38,
                     pose_at(e, 8), spin_windup, tail_w=tail_w(gap))
    hits.append((f, hit4))

    # удар 5
    hit5 = hit5_clip(spin_windup, SPIN, SLAM)
    hits.append(("hit5_spin+slam", hit5))

    seams = []
    for i in range(1, len(hits)):
        d = pose_dist(hits[i - 1][1][-1][2], hits[i][1][0][2])
        seams.append((hits[i][0], d))
    return hits, seams


# ---------- анализ ----------

def fk_tip(pose, length=26.0):
    yaw = pose[("rightArm", "yaw")] or 0.0
    pitch = pose[("rightArm", "pitch")] or 0.0
    roll = pose[("rightArm", "roll")] or 0.0
    x, y, z = 0.0, -length, 0.0
    cp, sp = math.cos(pitch), math.sin(pitch)
    y, z = y * cp - z * sp, y * sp + z * cp
    cy, sy = math.cos(yaw), math.sin(yaw)
    x, z = x * cy + z * sy, -x * sy + z * cy
    cr, sr = math.cos(roll), math.sin(roll)
    x, y = x * cr - y * sr, x * sr + y * cr
    return (5.0 + x, 22.0 + y, z)


def runtime_at(kf, p):
    """Точная копия рантаймовой Clip.at(): easing левого кадра."""
    if p <= kf[0][0]:
        return kf[0][2]
    for i in range(len(kf) - 1):
        a = kf[i]
        b = kf[i + 1]
        if p <= b[0]:
            span = b[0] - a[0]
            u = 1.0 if span <= 0 else (p - a[0]) / span
            if a[1] == E_SINE:
                u = 0.5 * (1 - math.cos(math.pi * u))
            return blend(a[2], b[2], u)
    return kf[-1][2]


def impact_moments(hits, dur):
    """Пик скорости меча по сегментам ключевых кадров: самый быстрый сегмент
    в окне удара -> его середина = момент урона."""
    res = []
    windows = [(0.16, 0.60), (0.04, 0.58), (0.04, 0.58), (0.04, 0.45), (0.55, 0.85)]
    for (name, kf), d, (u0, u1) in zip(hits, dur, windows):
        best_u, best_v = u0, 0.0
        for i in range(len(kf) - 1):
            a = kf[i]
            b = kf[i + 1]
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
    """Профиль скорости позы на 20 FPS: сколько «замороженных» кадров и пики."""
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
    return "Float.NaN" if v is None else f"{v:.6f}f"


def emit(hits):
    lines = []
    lines.append("    /** Позы пяти ударов, сгенерированы из анимаций BetterCombat")
    lines.append("     *  (scripts/port_bc_combo.py, исходники в scripts/anim_sources).")
    lines.append("     *  Хвосты-«удержания» обрезаны, стыки живут в хвосте предыдущего удара,")
    lines.append("     *  переходные сегменты — EASE_IN_OUT_SINE (плавные развороты корпуса).")
    lines.append("     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r, корпус y/p/r,")
    lines.append("     *  голова y/p/r (NaN — не трогаем, голова за взглядом), прав. нога y/p/r,")
    lines.append("     *  лев. нога y/p/r. Углы в радианах, как у ModelPart (pitch -> yaw -> roll). */")
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
    DUR = [7, 7, 7, 8, 9]
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
