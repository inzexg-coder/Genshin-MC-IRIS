#!/usr/bin/env python3
"""ASCII-рендер поз комбо из CombatController.java.

Воспроизводит геометрию ванильной PlayerEntityModel (1.21.10, yarn) и порядок
поворотов ModelPart.rotate: Matrix3f.rotationZYX(roll, yaw, pitch) — для
вектора-столбца сначала поворот по X (pitch), затем Y (yaw), затем Z (roll):
    R = Rz(roll) * Ry(yaw) * Rx(pitch)

Точка части: v' = R * v (поворот вокруг пивота), затем + origin.
Использование:
    python3 scripts/anim_preview.py            # все 5 ударов, вид сбоку/спереди
    python3 scripts/anim_preview.py --hit 3    # только удар 3
    python3 scripts/anim_preview.py --p 0.5    # один кадр всех ударов
"""
import argparse
import math
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAVA = REPO / "mod/src/main/java/net/teyvat/client/CombatController.java"

# ---------- геометрия ванильной PlayerEntityModel (1.21.10) ----------
# часть: (origin x,y,z, cuboid x,y,z,w,h,d, символ)
GEOMETRY = [
    # (name, origin, cuboid, char)
    ("head",     (0.0, 0.0, 0.0), (-4.0, -8.0, -4.0, 8.0, 8.0, 8.0), "H"),
    ("body",     (0.0, 0.0, 0.0), (-4.0, 0.0, -2.0, 8.0, 12.0, 4.0), "B"),
    ("rightArm", (-5.0, 2.0, 0.0), (-3.0, -2.0, -2.0, 4.0, 12.0, 4.0), "R"),
    ("leftArm",  (5.0, 2.0, 0.0), (-1.0, -2.0, -2.0, 4.0, 12.0, 4.0), "L"),
    ("rightLeg", (-1.9, 12.0, 0.0), (-2.0, 0.0, -2.0, 4.0, 12.0, 4.0), "r"),
    ("leftLeg",  (1.9, 12.0, 0.0), (-2.0, 0.0, -2.0, 4.0, 12.0, 4.0), "l"),
]

CHANNELS = ["rYaw", "rPitch", "rRoll", "lYaw", "lPitch", "lRoll",
            "bYaw", "bPitch", "bRoll", "hYaw", "hPitch", "hRoll",
            "rlYaw", "rlPitch", "rlRoll", "llYaw", "llPitch", "llRoll"]

# соответствие каналов Pose частям модели (yaw, pitch, roll)
PART_CHANNELS = {
    "head":     ("hYaw", "hPitch", "hRoll"),
    "body":     ("bYaw", "bPitch", "bRoll"),
    "rightArm": ("rYaw", "rPitch", "rRoll"),
    "leftArm":  ("lYaw", "lPitch", "lRoll"),
    "rightLeg": ("rlYaw", "rlPitch", "rlRoll"),
    "leftLeg":  ("llYaw", "llPitch", "llRoll"),
}


def rot_z(yaw):
    c, s = math.cos(yaw), math.sin(yaw)
    return ((c, -s, 0.0), (s, c, 0.0), (0.0, 0.0, 1.0))


def rot_y(roll):
    # roll — это поворот вокруг Z в MC, но чтобы не путаться, храним
    # готовые матрицы напрямую
    raise NotImplementedError


def rotation_zyx(roll, yaw, pitch):
    """Matrix3f.rotationZYX(roll, yaw, pitch) (JOML 1.10.8, проверено по байткоду):
    R = Rz(roll) * Ry(yaw) * Rx(pitch) — arm pitch -90° = рука горизонтально вперёд."""
    cz, sz = math.cos(roll), math.sin(roll)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cx, sx = math.cos(pitch), math.sin(pitch)
    return (
        (cz * cy, sz * cy, -sy),
        (-sz * cx + cz * sy * sx, cz * cx + sz * sy * sx, cy * sx),
        (sz * sx + cz * sy * cx, -cz * sx + sz * sy * cx, cy * cx),
    )


def mat_mul_vec(m, v):
    return (m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2])


def parse_poses(src):
    poses = {}
    for m in re.finditer(r'private static final Pose (\w+) = new Pose\(([^)]*)\);', src):
        vals = [float(x.rstrip("f")) for x in m.group(2).split(",")]
        poses[m.group(1)] = dict(zip(CHANNELS, vals))
    return poses


def parse_clips(src, poses):
    clips = []
    # ищем блоки new Clip(new Keyframe[] { ... })
    for m in re.finditer(r'new Clip\(new Keyframe\[\] \{(.*?)\}\),?\n', src, re.S):
        frames = []
        for f in re.finditer(r'new Keyframe\(([\d.eE+f-]+), (\d+), (\w+)\)', m.group(1)):
            t, ease, name = float(f.group(1).rstrip("f")), int(f.group(2)), f.group(3)
            frames.append((t, ease, poses[name]))
        clips.append(frames)
    return clips


def mix_pose(a, b, t):
    out = {}
    for k in CHANNELS:
        va, vb = a[k], b[k]
        if math.isnan(va):
            out[k] = vb
        elif math.isnan(vb):
            out[k] = va
        else:
            out[k] = va + (vb - va) * t
    return out


def ease(kind, t):
    t = max(0.0, min(1.0, t))
    if kind == 1:  # E_IN_OUT_CUBIC
        return 4 * t * t * t if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2
    if kind == 2:  # E_OUT_CUBIC
        return 1 - (1 - t) ** 3
    if kind == 3:  # E_OUT_BACK
        c1, c3 = 1.1, 2.1
        return 1 + c3 * (t - 1) ** 3 + c1 * (t - 1) ** 2
    if kind == 4:  # E_IN_OUT_SINE
        return -(math.cos(math.pi * t) - 1) / 2
    return t  # E_LINEAR


def clip_at(frames, p):
    if p <= frames[0][0]:
        return frames[0][2]
    for i in range(len(frames) - 1):
        ta, ea, pa = frames[i]
        tb, eb, pb = frames[i + 1]
        if p <= tb:
            span = tb - ta
            u = 1.0 if span <= 0 else (p - ta) / span
            return mix_pose(pa, pb, ease(ea, u))
    return frames[-1][2]


def part_pose(pose, name):
    yaw, pitch, roll = (pose[c] for c in PART_CHANNELS[name])
    if math.isnan(yaw):
        yaw = 0.0
    if math.isnan(pitch):
        pitch = 0.0
    if math.isnan(roll):
        roll = 0.0
    return roll, yaw, pitch


def transform_points(pose, root_yaw=0.0):
    """Возвращает словарь часть -> список мировых точек (voxels) и часть -> hand."""
    out = {}
    root_m = rotation_zyx(0.0, root_yaw, 0.0)
    for name, origin, cub, _ in GEOMETRY:
        roll, yaw, pitch = part_pose(pose, name)
        m = rotation_zyx(roll, yaw, pitch)
        ox, oy, oz = origin
        cxo, cyo, czo, w, h, d = cub
        pts = []
        step = 0.5
        xs = [cxo + i * step for i in range(0, int(w / step) + 1)]
        ys = [cyo + i * step for i in range(0, int(h / step) + 1)]
        zs = [czo + i * step for i in range(0, int(d / step) + 1)]
        for x in xs:
            for y in ys:
                for z in zs:
                    v = mat_mul_vec(m, (x, y, z))
                    v = (v[0] + ox, v[1] + oy, v[2] + oz)
                    v = mat_mul_vec(root_m, v)
                    pts.append(v)
        out[name] = pts
    return out


def hand_point(pose, root_yaw, arm="rightArm"):
    roll, yaw, pitch = part_pose(pose, arm)
    m = rotation_zyx(roll, yaw, pitch)
    name, origin, cub, _ = next(g for g in GEOMETRY if g[0] == arm)
    ox, oy, oz = origin
    # кисть — нижний конец куба руки
    tip = mat_mul_vec(m, (0.0, 10.0, 0.0))
    tip = mat_mul_vec(rotation_zyx(0.0, root_yaw, 0.0),
                      (tip[0] + ox, tip[1] + oy, tip[2] + oz))
    return tip


def blade_points(pose, root_yaw, arm="rightArm", length=10.0, reverse=False):
    """Клинок: от кисти вдоль направления руки (локальное +y)."""
    roll, yaw, pitch = part_pose(pose, arm)
    m = rotation_zyx(roll, yaw, pitch)
    name, origin, cub, _ = next(g for g in GEOMETRY if g[0] == arm)
    ox, oy, oz = origin
    hand = hand_point(pose, root_yaw, arm)
    # направление локальной +y (из плеча в кисть) — направление клинка
    d = mat_mul_vec(m, (0.0, 1.0, 0.0))
    d = mat_mul_vec(rotation_zyx(0.0, root_yaw, 0.0), d)
    sign = -1.0 if reverse else 1.0
    tip = (hand[0] + d[0] * length * sign,
           hand[1] + d[1] * length * sign,
           hand[2] + d[2] * length * sign)
    return hand, tip


# ---------- рендер ----------

def project_front(p):
    return p[0], p[1], p[2]


def project_side(p):
    # камера справа от игрока: экран-x = -z (фронт игрока слева), экран-y = y
    return -p[2], p[1], -p[0]


def project_top(p):
    # вид сверху: экран-x = x, экран-y = -z (перед игрока вверх)
    return p[0], -p[2], p[1]


def draw(poses, root_yaw, view="front", show_blade=True):
    """Авто-подбор окна: сетка с масштабом 1 клетка = 1/16 блока."""
    if view == "front":
        proj = project_front
    elif view == "side":
        proj = project_side
    else:
        proj = project_top

    # соберём все точки, чтобы подобрать окно
    allpts = []
    for pts in poses.values():
        allpts.extend(pts)
    xs = [p[0] for p in allpts]
    ys = [p[1] for p in allpts]
    zs = [p[2] for p in allpts]
    margin = 2
    xmin, xmax = int(math.floor(min(xs))) - margin, int(math.ceil(max(xs))) + margin
    ymin, ymax = int(math.floor(min(ys))) - margin, int(math.ceil(max(ys))) + margin
    width = xmax - xmin + 1
    height = ymax - ymin + 1
    grid = [[" "] * width for _ in range(height)]
    depth = [[-1e9] * width for _ in range(height)]

    def put(p, ch):
        sx, sy, z = proj(p)
        col = int(round(sx - xmin))
        row = int(round(sy - ymin))
        if 0 <= col < width and 0 <= row < height:
            if z > depth[row][col]:
                depth[row][col] = z
                grid[row][col] = ch

    def rasterize(pts, ch):
        for p in pts:
            put(p, ch)

    for name, _, _, ch in GEOMETRY:
        if name in poses:
            rasterize(poses[name], ch)

    if show_blade:
        for arm, bch in (("rightArm", "/"), ("leftArm", "\\")):
            pts = poses.get(arm)
            if pts is not None and len(pts) >= 2:
                hand, tip = pts[-2], pts[-1]
                n = max(2, int(abs(tip[0] - hand[0]) + abs(tip[1] - hand[1]) + abs(tip[2] - hand[2])) * 2)
                for i in range(n + 1):
                    t = i / n
                    q = (hand[0] + (tip[0] - hand[0]) * t,
                         hand[1] + (tip[1] - hand[1]) * t,
                         hand[2] + (tip[2] - hand[2]) * t)
                    put(q, bch)
    return grid, (xmin, ymin)


def render_view(pose, root_yaw, view, width=64, height=34):
    parts = transform_points(pose, root_yaw)
    blades = {}
    for arm in ("rightArm", "leftArm"):
        hand, tip = blade_points(pose, root_yaw, arm)
        blades[arm] = (hand, tip)
    return draw(parts, root_yaw, view, width, height)


def render_view(pose, root_yaw, view, width=64, height=34):
    parts = transform_points(pose, root_yaw)
    blades = {}
    for arm in ("rightArm", "leftArm"):
        hand, tip = blade_points(pose, root_yaw, arm)
        blades[arm] = (hand, tip)
    return draw(parts, root_yaw, view, width, height)


def show(grid, label=""):
    print(label)
    print("+" + "-" * len(grid[0]) + "+")
    for row in grid:
        print("|" + "".join(row) + "|")
    print("+" + "-" * len(grid[0]) + "+")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hit", type=int, default=None, help="номер удара 1..5")
    ap.add_argument("--p", type=float, default=None, help="один кадр (0..1)")
    ap.add_argument("--frames", type=int, default=7, help="кадров на удар")
    ap.add_argument("--view", default="side", choices=["side", "front", "top"])
    args = ap.parse_args()

    src = JAVA.read_text()
    poses = parse_poses(src)
    clips = parse_clips(src, poses)
    names = ["hit1", "hit2", "hit3", "hit4", "hit5"]
    print(f"поз: {len(poses)}, клипов: {len(clips)}")
    if len(clips) != 5:
        print("Ожидалось 5 клипов — проверь парсер!", file=sys.stderr)
        return 1

    def do(hit):
        frames = clips[hit - 1]
        ps = [args.p] if args.p is not None else [i / (args.frames - 1) for i in range(args.frames)]
        for p in ps:
            pose = clip_at(frames, p)
            root_yaw = 0.0
            if hit == 3:
                u = max(0.0, min(1.0, (p - 0.14) / 0.62))
                t = ease(1, u)
                root_yaw = 2 * math.pi * t
            parts = transform_points(pose, root_yaw)
            blades = {}
            for arm in ("rightArm", "leftArm"):
                blades[arm] = blade_points(pose, root_yaw, arm)
            # подмешиваем blade в poses для отрисовки
            pdraw = dict(parts)
            pdraw["rightArm"] = parts["rightArm"] + [blades["rightArm"][0], blades["rightArm"][1]]
            pdraw["leftArm"] = parts["leftArm"] + [blades["leftArm"][0], blades["leftArm"][1]]
            g, _ = draw(pdraw, root_yaw, args.view)
            show(g, f"--- удар {hit} p={p:.3f} root_yaw={math.degrees(root_yaw):.0f}° ({args.view}) ---")

    if args.hit:
        do(args.hit)
    else:
        for h in range(1, 6):
            do(h)
    return 0


if __name__ == "__main__":
    sys.exit(main())
