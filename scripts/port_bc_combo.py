#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Комбо путешественника из готовых анимаций BetterCombat (автор Daedelus),
подогнанное под описание обычных атак Итэра/Люмин из Genshin.

Источник: BetterCombat 2.4.0, assets/bettercombat/player_animations/*.json
(формат Emotecraft/PlayerAnimator, ключевые кадры tick + yaw/pitch/roll + easing).

Раскладка готовых клипов под описание комбо:
  удар 1 — широкий слева направо -> one_handed_slash_horizontal_left;
  удар 2 — диагональ снизу справа вверх налево -> one_handed_uppercut_right;
  удар 3 — разворот по кругу (справа сверху влево вниз) -> two_handed_spin;
  удар 4 — справа налево -> one_handed_slash_horizontal_right;
  удар 5 — очень широкий справа налево, замах за спину и прокат ->
           one_handed_slam (замах над головой -> широкий свинг вниз с
           прогибом корпуса; прокат даёт рывок вперёд на тике урона).

Проблемы исходников, которые скрипт чинит:
  * свинг занимает первые ~25-35% клипа, дальше статичная «удерживаемая»
    поза (заморозка в конце удара) — хвосты обрезаны;
  * стыки независимых клипов дают скачки позы до ~3.6 рад — переходы
    живут в хвосте предыдущего удара (EASE_IN_OUT_SINE);
  * углы Эйлера «обёрнуты» через ±π (у спина torso.yaw: +2.34 / -2.36) —
    unwrap делает кривую непрерывной, чтобы части тела не проворачивались
    на 360° лишний раз.

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
# Порядок — под описание комбо: L->R, апперкот, разворот, R->L, обрушение.
WINDOWS = [
    ("hit2_slash_l2r.json", 8, 17),   # широкий слева направо
    ("hit3_uppercut.json", 8, 15),    # диагональ снизу справа вверх налево
    ("hit1_slash_r2l.json", 8, 15),   # справа налево
]
SPIN = ("hit5_spin.json", 10, 16)   # разворот по кругу: полный круг корпуса
SLAM = ("hit5_slam.json", 8, 12)    # очень широкий замах за спину -> обрушение


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

def unwrap_clips(hits):
    """Разворачивает углы всех клипов в непрерывную кривую: значение кадра
    сдвигается на ±2π, чтобы отличаться от предыдущего не более чем на π.
    Без этого скачки через ±π (например, torso.yaw спина: +2.34 после -2.36)
    проворачивали бы части тела на лишние ~360°."""

    prev = {}
    out = []
    for name, kf in hits:
        new_kf = []
        for u, ease, pose in kf:
            np_ = dict(pose)
            for k, v in np_.items():
                if v is None:
                    continue
                if k in prev:
                    while v - prev[k] > math.pi:
                        v -= 2 * math.pi
                    while v - prev[k] < -math.pi:
                        v += 2 * math.pi
                prev[k] = v
                np_[k] = v
            new_kf.append((u, ease, np_))
        out.append((name, new_kf))
    return out


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


def spin_clip(start_pose, spin, next_windup):
    """Удар 3: разворот по кругу — плотная сетка спина (t10..t16, полный круг
    корпуса), затем плавный переход в замах удара 4 (EASE_IN_OUT_SINE)."""
    spin_file, s0, s1 = spin
    se = load(spin_file)
    kf = []
    kf.append((0.00, E_SINE, dict(start_pose)))          # замах спина
    srcs = list(range(s0 + 1, s1 + 1))                   # t11..t16: круг
    for i, src in enumerate(srcs):
        u = 0.06 + 0.64 * i / (len(srcs) - 1)
        kf.append((u, E_LINEAR, pose_at(se, src)))
    kf.append((0.82, E_SINE, dict(next_windup)))         # в замах удара 4
    kf.append((1.00, E_SINE, dict(next_windup)))
    return kf


def slam_clip(start_pose, slam):
    """Удар 5: очень широкий — замах (рука поднята за спину/над головой) ->
    свинг вниз с прогибом корпуса -> плавный выход в нейтраль (прокат вперёд
    делает рывок на тике урона)."""
    slam_file, s0, s1 = slam
    se = load(slam_file)
    kf = []
    kf.append((0.00, E_SINE, dict(start_pose)))          # замах за спину
    for i, src in enumerate(range(s0, s1 + 1)):          # t8..t12: свинг вниз
        u = 0.08 + 0.48 * i / max(1, s1 - s0)
        kf.append((u, E_LINEAR, pose_at(se, src)))
    kf.append((0.70, E_SINE, blend(pose_at(se, s1), neutral_pose(), 0.25)))
    kf.append((0.86, E_SINE, blend(pose_at(se, s1), neutral_pose(), 0.70)))
    kf.append((1.00, E_SINE, neutral_pose()))
    return kf


def build():
    hits = []
    neutral = neutral_pose()
    e1, e2, e4 = (load(f) for f, _, _ in WINDOWS)
    spin_windup = pose_at(load(SPIN[0]), SPIN[1])
    slam_windup = pose_at(load(SLAM[0]), SLAM[1])
    r2l_windup = pose_at(e4, 8)

    # ширина хвоста-перехода: 0.10 рад скачка -> 0.10 клипа, мин 0.14, макс 0.42
    def tail_w(gap):
        return min(0.42, max(0.14, gap * 0.10))

    # удар 1: широкий слева направо, вход из нейтрали
    f, s0, s1 = WINDOWS[0]
    gap1 = pose_dist(pose_at(e1, s1), pose_at(e2, 8))
    hit1 = hit_swing(e1, s0, s1, 0.16, 0.38, 0.60, neutral, pose_at(e2, 8),
                     tail_w=tail_w(gap1))
    hits.append(("hit2_slash_l2r (L->R)", hit1))

    # удар 2: диагональ снизу справа вверх налево (апперкот справа)
    f, s0, s1 = WINDOWS[1]
    gap2 = pose_dist(pose_at(e2, s1), spin_windup)
    hit2 = hit_swing(e2, s0, s1, 0.04, 0.34, 0.58, pose_at(e2, 8), spin_windup,
                     tail_w=tail_w(gap2))
    hits.append(("hit3_uppercut (R low -> L up)", hit2))

    # удар 3: разворот по кругу (spin)
    hit3 = spin_clip(spin_windup, SPIN, r2l_windup)
    hits.append(("hit5_spin (circle turn)", hit3))

    # удар 4: справа налево
    f, s0, s1 = WINDOWS[2]
    gap4 = pose_dist(pose_at(e4, s1), slam_windup)
    hit4 = hit_swing(e4, s0, s1, 0.04, 0.34, 0.58, pose_at(e4, 8), slam_windup,
                     tail_w=tail_w(gap4))
    hits.append(("hit1_slash_r2l (R->L)", hit4))

    # удар 5: очень широкий замах за спину -> обрушение с прокатом
    hit5 = slam_clip(slam_windup, SLAM)
    hits.append(("hit5_slam (wide slam + lunge)", hit5))

    # непрерывная кривая: без проворотов частей тела на ±360°
    hits = unwrap_clips(hits)

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
    windows = [(0.10, 0.60), (0.04, 0.55), (0.06, 0.78), (0.04, 0.55), (0.08, 0.60)]
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
    lines.append("    /** Позы пяти ударов, сгенерированы из готовых анимаций BetterCombat")
    lines.append("     *  (scripts/port_bc_combo.py, исходники в scripts/anim_sources, автор")
    lines.append("     *  анимаций Daedelus): удар 1 — слева направо (slash horizontal left),")
    lines.append("     *  удар 2 — апперкот справа (uppercut right), удар 3 — разворот")
    lines.append("     *  по кругу (spin), удар 4 — справа налево (slash horizontal right),")
    lines.append("     *  удар 5 — широкий замах за спину и обрушение (slam).")
    lines.append("     *  Хвосты-«удержания» обрезаны, стыки живут в хвосте предыдущего удара,")
    lines.append("     *  переходные сегменты — EASE_IN_OUT_SINE (плавные развороты корпуса),")
    lines.append("     *  углы развёрнуты в непрерывную кривую (без проворотов на ±360°).")
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
    DUR = [12, 12, 16, 12, 18]
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
