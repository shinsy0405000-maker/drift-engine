"""휠 + 타이어 + 브레이크 디스크. 축 = X, 바깥쪽 = +X (오른쪽 바퀴 기준, 왼쪽은 게임이 거울로)."""

import math
import bmesh
from mathutils import Vector, Matrix

from carlib import lathe, new_object, shade, join, extrude_outline, cylinder, material


def tire(R, w, rim_r):
    h = w / 2
    prof = [
        (rim_r + 0.004, -h + 0.016),
        (rim_r + 0.022, -h + 0.002),
        (lerp(rim_r, R, 0.55), -h - 0.006),
        (R - 0.022, -h + 0.002),
        (R - 0.006, -h + 0.016),
        (R, -h + 0.034),
        (R, h - 0.034),
        (R - 0.006, h - 0.016),
        (R - 0.022, h - 0.002),
        (lerp(rim_r, R, 0.55), h + 0.006),
        (rim_r + 0.022, h - 0.002),
        (rim_r + 0.004, h - 0.016),
    ]
    o = lathe("tire", prof, 64, mats=("rubber",))
    shade(o, 50)
    return o


def lerp(a, b, t):
    return a + (b - a) * t


def rim_barrel(rim_r, w, face_x):
    """림 통 + 바깥 테두리(립). face_x = 스포크 면 위치"""
    h = w / 2
    prof = [
        (rim_r - 0.035, -h + 0.03),
        (rim_r - 0.004, -h + 0.016),
        (rim_r + 0.006, -h + 0.012),
        (rim_r + 0.008, -h + 0.020),
        (rim_r - 0.006, -h + 0.03),
        (rim_r - 0.010, face_x - 0.02),
        (rim_r - 0.004, h - 0.012),
        (rim_r + 0.010, h - 0.006),
        (rim_r + 0.009, h + 0.002),
        (rim_r - 0.006, h + 0.002),
        (rim_r - 0.022, h - 0.010),
        (rim_r - 0.030, face_x + 0.004),
    ]
    o = lathe("barrel", prof, 64, mats=("rim",))
    shade(o, 40)
    return o


def spoke_outline(r0, r1, w0, w1, ang, curve=0.0):
    """허브(r0, 폭 w0) → 테두리(r1, 폭 w1) 사다리꼴, ang 방향. curve = 끝이 돌아가는 정도"""
    pts = []
    n = 6
    left, right = [], []
    for i in range(n + 1):
        t = i / n
        r = lerp(r0, r1, t)
        ww = lerp(w0, w1, t) / 2
        a = ang + curve * t * t
        c, s = math.cos(a), math.sin(a)
        # (반지름 방향, 옆 방향) → (y, z)
        left.append((c * r - s * ww, s * r + c * ww))
        right.append((c * r + s * ww, s * r - c * ww))
    return right + list(reversed(left))


def spokes(style, rim_r, face_x, depth):
    parts = []
    hub = 0.075
    edge = rim_r - 0.022
    if style == 3:  # 디스크 (전기차 공력 휠)
        prof = [(0.0, face_x + 0.01), (hub, face_x + 0.012), (edge * 0.7, face_x + 0.004), (edge, face_x - 0.006),
                (edge, face_x - 0.03), (0.0, face_x - 0.03)]
        d = lathe("aero", prof, 64, mats=("rim",))
        shade(d, 40)
        parts.append(d)
        # 홈 (어두운 판 5개)
        for k in range(5):
            a = k * 2 * math.pi / 5
            out = spoke_outline(edge * 0.55, edge * 0.92, 0.05, 0.075, a, 0.25)
            parts.append(extrude_outline("vent", [(-p[0], p[1]) for p in out], 0.006, "black", plane="YZ",
                                         origin=(face_x + 0.006, 0, 0)))
        return parts
    configs = {
        0: [(5, 0.062, 0.042, 0.0, 0.0)],                    # 5 스포크
        4: [(6, 0.050, 0.034, 0.0, 0.0)],                    # 6 스포크
        1: [(10, 0.030, 0.022, 0.0, 0.0)],                   # 10 스포크
        2: [(10, 0.020, 0.016, 0.0, 0.22), (10, 0.020, 0.016, math.pi / 10, -0.22)],  # 메시 (엇갈림)
    }
    for layer, (n, w0, w1, off, curve) in enumerate(configs.get(style, configs[0])):
        for k in range(n):
            a = off + k * 2 * math.pi / n
            out = spoke_outline(hub * 0.8, edge, w0, w1, a, curve)
            s = extrude_outline("spoke", [(-p[0], p[1]) for p in out], depth, "rim", plane="YZ",
                                origin=(face_x - depth / 2 - layer * 0.006, 0, 0))
            parts.append(s)
    # 허브 + 센터캡 + 너트
    parts.append(cylinder("hub", (face_x - 0.006, 0, 0), hub, 0.03, "rim", segments=32))
    parts.append(cylinder("cap", (face_x + 0.012, 0, 0), 0.032, 0.012, "black", segments=24))
    for k in range(5):
        a = k * 2 * math.pi / 5 + 0.3
        parts.append(cylinder("nut", (face_x + 0.012, math.cos(a) * 0.052, math.sin(a) * 0.052), 0.0095, 0.016,
                              "chrome", segments=8))
    return parts


def brake(rim_r, face_x):
    r = rim_r - 0.035
    disc = cylinder("disc", (face_x - 0.075, 0, 0), r, 0.026, "disc", segments=48)
    hat = cylinder("hat", (face_x - 0.05, 0, 0), 0.085, 0.04, "disc", segments=32)
    return [disc, hat]


def build_wheel(name, R, width, rim_style, rim_inch=None, dish=0.035):
    rim_r = (rim_inch * 0.0254 / 2) if rim_inch else R - max(0.075, width * 0.38)
    face_x = width / 2 - dish
    parts = [tire(R, width, rim_r), rim_barrel(rim_r, width, face_x)]
    parts += spokes(rim_style, rim_r, face_x, 0.026)
    parts += brake(rim_r, face_x)
    o = join(parts, name)
    return o


def caliper(R, width, rim_inch=None, dish=0.035, color="caliper"):
    """브레이크 캘리퍼: 디스크 뒤-위쪽을 감싸는 덩어리 (바퀴와 같이 조향, 돌지는 않음)"""
    rim_r = (rim_inch * 0.0254 / 2) if rim_inch else R - max(0.075, width * 0.38)
    face_x = width / 2 - dish
    r = rim_r - 0.035
    bm = bmesh.new()
    seg = 10
    a0, a1 = math.radians(100), math.radians(170)   # 뒤-위 (Blender: Y 앞, Z 위 → 뒤쪽 = -Y)
    rows = []
    for k in range(seg + 1):
        a = lerp(a0, a1, k / seg)
        c, s = math.cos(a), math.sin(a)
        ring = []
        for (rr, xx) in ((r - 0.05, face_x - 0.11), (r + 0.012, face_x - 0.11), (r + 0.012, face_x - 0.045), (r - 0.05, face_x - 0.045)):
            ring.append(bm.verts.new((xx, c * rr, s * rr)))
        rows.append(ring)
    for k in range(seg):
        for j in range(4):
            bm.faces.new([rows[k][j], rows[k][(j + 1) % 4], rows[k + 1][(j + 1) % 4], rows[k + 1][j]])
    bm.faces.new(rows[0])
    bm.faces.new(list(reversed(rows[-1])))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bmesh.ops.bevel(bm, geom=list(bm.edges), offset=0.006, segments=2, affect="EDGES", clamp_overlap=True)
    o = new_object("caliper", bm, [color])
    shade(o, 40)
    return o
