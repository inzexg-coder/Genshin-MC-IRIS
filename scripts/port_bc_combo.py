#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Портирование атак путешественника из готовых анимаций BetterCombat.

Источник: BetterCombat 2.4.0, assets/bettercombat/player_animations/*.json
(формат Emotecraft/PlayerAnimator, автор анимаций Daedelus). Это данные,
а не код: ключевые кадры (tick + yaw/pitch/roll в радианах + easing).

Склейка 5 ударов комбо (как у Итэра/Люмин в Genshin):
  удар 1 — горизонтальный разрез справа налево  (one_handed_slash_horizontal_right)
  удар 2 — обратный восходящий слева направо    (one_handed_slash_horizontal_left)
  удар 3 — восходящий разрез                    (one_handed_uppercut_right)
  удар 4 — выпад-укол с разворотом корпуса       (one_handed_stab)
  удар 5 — разворот над головой + обрушение      (two_handed_spin -> one_handed_slam)

Каждый клип пересэмплируется на равномерной сетке с точным воспроизведением
исходных easing-кривых. Стыки между ударами сглаживаются рамповой коррекцией:
начало клипа N начинается ровно с последней позы клипа N-1 и за ~20% плавно
возвращается к собственной позе. Конец 5-го удара плавно уходит в нейтральную
стойку (голова — NaN, «за взглядом»).

Использование: python3 scripts/port_bc_combo.py > /tmp/clips.java
"""
import json
import math
import os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "anim_sources")
BONES = ["rightArm", "leftArm", "torso", "head", "rightLeg", "leftLeg"]
CHANS = ["yaw", "pitch", "roll"]
POSE_ORDER = [(b, c) for b in ("rightArm", "leftArm", "torso", "head", "rightLeg", "leftLeg") for c in CHANS]
NAN_CHANS = {("head", "yaw"), ("head", "pitch"), ("head", "roll")}
K = 13  # сэмплов на клип (кроме 5-го: 7 + 8)


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


def sample_clip(e, n, u0=0.0, u1=1.0):
    b, en = e["beginTick"], e["endTick"]
    out = []
    for i in range(n):
        u = u0 + (u1 - u0) * (i / (n - 1))
        tick = b + (en - b) * u
        pose = {}
        for bone in BONES:
            for ch in CHANS:
                pose[(bone, ch)] = eval_curve(build_curves(e, bone, ch), tick)
        out.append((u, pose))
    return out


def neutral_pose():
    return {k: (None if k in NAN_CHANS else 0.0) for k in POSE_ORDER}


def blend_start(samples, prev_end, L=0.25):
    """Первые L клипа — плавный переход из последней позы предыдущего удара."""
    total = samples[-1][0] - samples[0][0]
    for u, pose in samples:
        loc = (u - samples[0][0]) / total if total > 0 else 1.0
        w = 0.5 * (1 - math.cos(math.pi * min(1.0, loc / L))) if loc < L else 1.0
        if w == 1.0:
            continue
        for k in pose:
            b = prev_end.get(k)
            if pose[k] is not None and b is not None:
                pose[k] = b + (pose[k] - b) * w


def blend_end(samples, target, L=0.28):
    """Последние L клипа плавно уходят в целевую позу (нейтраль)."""
    total = samples[-1][0] - samples[0][0]
    for u, pose in samples:
        loc = (u - samples[0][0]) / total if total > 0 else 1.0
        w = 0.5 * (1 - math.cos(math.pi * min(1.0, (loc - (1 - L)) / L))) if loc > 1 - L else 0.0
        if w == 0.0:
            continue
        for k in pose:
            t = target.get(k)
            if pose[k] is not None and t is not None:
                pose[k] = pose[k] + (t - pose[k]) * w


def seam_gap(prev_last, cur_first):
    g = {}
    for k in cur_first:
        a = prev_last.get(k)
        b = cur_first[k]
        g[k] = None if (a is None or b is None) else a - b
    return g


def chain(prev_last, samples, L=0.25):
    blend_start(samples, prev_last, L)
    return samples[-1][1]


def max_gap(g):
    vals = [abs(v) for v in g.values() if v is not None]
    return max(vals) if vals else 0.0


def build():
    hits = []
    raw_gaps = []
    neutral = neutral_pose()
    # удар 1: старт из нейтральной стойки (бленд первые 15%), дальше — исходный клип
    hit1 = sample_clip(load("hit1_slash_r2l.json"), K)
    hit1 = [(0.0, dict(neutral))] + [(0.15 + 0.85 * u, pose) for u, pose in hit1]
    blend_start(hit1, neutral, 0.15)
    hits.append(("hit1_slash_r2l.json", hit1))
    hits.append(("hit2_slash_l2r.json", sample_clip(load("hit2_slash_l2r.json"), K)))
    hits.append(("hit3_uppercut.json", sample_clip(load("hit3_uppercut.json"), K)))
    hits.append(("hit4_stab.json", sample_clip(load("hit4_stab.json"), K)))
    # удар 5: разворот (spin 0..0.25) -> обрушение (slam 0.12..1.0)
    spin = sample_clip(load("hit5_spin.json"), 7, 0.0, 0.25)
    slam = sample_clip(load("hit5_slam.json"), 8, 0.12, 1.0)
    for i in range(7):
        spin[i] = (0.55 * i / 6, spin[i][1])
    for i in range(8):
        slam[i] = (0.55 + 0.45 * i / 7, slam[i][1])
    hit5 = spin + slam
    hits.append(("hit5_spin+slam", hit5))

    for i in range(1, len(hits)):
        g = seam_gap(hits[i - 1][1][-1][1], hits[i][1][0][1])
        raw_gaps.append((hits[i][0], max_gap(g)))
    # склейка N2..N5 (начало = последняя поза предыдущего удара)
    prev_last = hits[0][1][-1][1]
    for name, samples in hits[1:]:
        prev_last = chain(prev_last, samples)
    # стык внутри 5-го удара (spin -> slam)
    slam_samples = [s for s in hit5 if s[0] >= 0.55]
    blend_start(slam_samples, spin[-1][1], 0.30)
    # финал: плавный возврат в нейтраль
    blend_end(hits[-1][1], neutral, 0.28)
    return hits, raw_gaps


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


def impact_moments(hits):
    res = []
    fine = [
        (hits[0][0], sample_clip(load("hit1_slash_r2l.json"), 101)),
        (hits[1][0], sample_clip(load("hit2_slash_l2r.json"), 101)),
        (hits[2][0], sample_clip(load("hit3_uppercut.json"), 101)),
        (hits[3][0], sample_clip(load("hit4_stab.json"), 101)),
        (hits[4][0], sample_clip(load("hit5_spin.json"), 51, 0.0, 0.25) + sample_clip(load("hit5_slam.json"), 51, 0.12, 1.0)),
    ]
    for name, samples in fine:
        best_u, best_v = samples[0][0], 0.0
        prev = None
        for u, pose in samples:
            tip = fk_tip(pose)
            if prev is not None:
                v = math.dist(tip, prev)
                if v > best_v:
                    best_v, best_u = v, u
            prev = tip
        res.append((name, best_u, best_v))
    return res


def slam_peak():
    samples = sample_clip(load("hit5_slam.json"), 101, 0.12, 1.0)
    best_u, best_v = 0.0, 0.0
    prev = None
    for u, pose in samples:
        tip = fk_tip(pose)
        if prev is not None:
            v = math.dist(tip, prev)
            if v > best_v:
                best_v, best_u = v, u
        prev = tip
    return best_u


def fmt(v):
    return "Float.NaN" if v is None else f"{v:.6f}f"


def emit(hits):
    lines = []
    lines.append("    /** Позы пяти ударов, сгенерированы из анимаций BetterCombat")
    lines.append("     *  (scripts/port_bc_combo.py, исходники в scripts/anim_sources).")
    lines.append("     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r, корпус y/p/r,")
    lines.append("     *  голова y/p/r (NaN — не трогаем, голова за взглядом), прав. нога y/p/r,")
    lines.append("     *  лев. нога y/p/r. Углы в радианах, как у ModelPart (pitch -> yaw -> roll). */")
    names = ["hit1", "hit2", "hit3", "hit4", "hit5"]
    for hi, (name, samples) in enumerate(hits):
        lines.append(f"    // Удар {hi + 1}: {name}")
        for si, (u, pose) in enumerate(samples):
            vals = ", ".join(fmt(pose[k]) for k in POSE_ORDER)
            lines.append(f"    private static final Pose {names[hi]}_{si:02d} = new Pose({vals});")
    lines.append("")
    lines.append("    /** Клипы пяти ударов: начало N = конец N-1 (склейка в конвертере). */")
    lines.append("    private static final Clip[] CLIPS = {")
    for hi, (name, samples) in enumerate(hits):
        lines.append(f"        new Clip(new Keyframe[] {{ // удар {hi + 1}: {name}")
        for si, (u, pose) in enumerate(samples):
            lines.append(f"                new Keyframe({u:.3f}f, E_LINEAR, {names[hi]}_{si:02d}),")
        lines.append("        }),")
    lines.append("    };")
    return "\n".join(lines)


def main():
    hits, raw_gaps = build()
    print("// Impact (пик скорости меча, доля клипа):")
    for name, u, v in impact_moments(hits):
        print(f"//   {name}: u={u:.3f} (speed={v:.1f})")
    sp = slam_peak()
    print(f"//   hit5 slam-фаза: пик на v={sp:.3f} (u={0.55 + 0.45 * sp:.3f})")
    print("// Стыки ДО коррекции (макс. скачок канала, рад):")
    for name, m in raw_gaps:
        print(f"//   -> {name}: max gap = {m:.3f}")
    print("// Последняя поза (должна быть нейтраль + NaN-голова):")
    last = hits[-1][1][-1][1]
    print("//   " + ", ".join(fmt(last[k]) for k in POSE_ORDER))
    print()
    print(emit(hits))


if __name__ == "__main__":
    main()
