"""실내: 시트, 대시보드, 계기판, 핸들, 센터 콘솔, 뒷좌석, 롤케이지 (투명 유리 너머로 보임)."""

import math
import bmesh
from mathutils import Vector, Matrix

from carlib import new_object, shade, join, cylinder, lerp


def rounded_box(name, center, size, mat, bevel=0.03, rot=None):
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    bmesh.ops.scale(bm, vec=Vector(size), verts=bm.verts)
    bmesh.ops.bevel(bm, geom=list(bm.edges), offset=min(bevel, min(size) * 0.45), segments=3,
                    affect="EDGES", profile=0.5, clamp_overlap=True)
    if rot is not None:
        bmesh.ops.rotate(bm, cent=(0, 0, 0), matrix=rot, verts=bm.verts)
    bmesh.ops.translate(bm, vec=Vector(center), verts=bm.verts)
    o = new_object(name, bm, [mat])
    shade(o, 50)
    return o


def tube(name, p0, p1, r, mat, seg=12):
    p0, p1 = Vector(p0), Vector(p1)
    d = p1 - p0
    o = cylinder(name, (0, 0, 0), r, d.length, mat, axis="Z", segments=seg)
    rot = d.normalized().to_track_quat("Z", "Y").to_matrix().to_4x4()
    o.matrix_world = Matrix.Translation((p0 + p1) / 2) @ rot
    return o


def seat(x, y, z, accent, bucket=True):
    """x, y = 엉덩이 위치 (y 는 앞 +), z = 쿠션 윗면"""
    parts = []
    w = 0.50
    tilt = Matrix.Rotation(math.radians(-16), 3, "X")  # 등받이 뒤로 기울임
    parts.append(rounded_box("seat_base", (x, y + 0.05, z - 0.05), (w, 0.50, 0.10), "seat", 0.035))
    back_c = Vector((x, y - 0.22, z + 0.30))
    parts.append(rounded_box("seat_back", back_c, (w, 0.11, 0.66), "seat", 0.04, tilt))
    if bucket:
        for s in (1, -1):
            # 옆 날개 (등받이 + 쿠션)
            parts.append(rounded_box("bolster", back_c + Vector((s * (w / 2 - 0.035), 0.05, -0.05)), (0.08, 0.16, 0.48), "seat", 0.03, tilt))
            parts.append(rounded_box("bolster_low", (x + s * (w / 2 - 0.035), y + 0.05, z), (0.08, 0.44, 0.10), "seat", 0.03))
            # 벨트 구멍 위 포인트 색 줄
            parts.append(rounded_box("stripe", back_c + Vector((s * 0.09, 0.058, 0.08)), (0.04, 0.01, 0.40), accent, 0.004, tilt))
    else:
        parts.append(rounded_box("headrest", back_c + Vector((0, -0.05, 0.42)), (0.26, 0.09, 0.17), "seat", 0.035, tilt))
    return parts


def build_interior(b, d):
    it = d.get("interior", {})
    parts = []
    rhd = d.get("rhd", False)
    driver = 1 if rhd else -1                    # 운전석 쪽 (x 부호)
    belt = b.beltH
    sill = b.o.get("sill", b.sillH)
    floor = sill + 0.02
    t_seat = b.roofStart + (b.roofEnd - b.roofStart) * it.get("seatT", 0.55)
    y_seat = b.y_of(t_seat)
    half_in = b.half_width(t_seat) * b.o.get("tumble", 0.87) - 0.12
    seat_x = min(0.40, half_in * 0.52)
    seat_z = floor + it.get("seatH", 0.20)
    accent = it.get("accent", "accent")
    bucket = it.get("bucket", True)
    for s in (1, -1):
        parts += seat(s * seat_x, y_seat, seat_z, accent, bucket)
    # 바닥 (검은 카펫)
    y_dash = b.y_of(b.glassStart + 0.01)
    parts.append(rounded_box("floor", (0, (y_dash + y_seat - 0.5) / 2, floor), (half_in * 2, abs(y_dash - y_seat) + 0.6, 0.02), "interior", 0.005))
    # 대시보드: 앞유리 아래를 가로지름
    # 앞끝은 앞유리 안쪽이 대시 윗면보다 충분히 높아지는 곳부터 (후드 위로 뚫고 나가지 않게)
    dash_top = belt + 0.01
    t_front = None
    t = b.cowl + 0.005
    while t < b.roofStart:
        if b.top(t) - 0.09 >= dash_top:
            t_front = t
            break
        t += 0.002
    if t_front is None or t_front > b.glassStart + 0.04:
        t_front = min(t_front or 1.0, b.glassStart + 0.04)
        dash_top = min(dash_top, b.top(t_front) - 0.09)
    y0 = b.y_of(t_front)
    dash_h = 0.22 - (belt + 0.01 - dash_top)
    dash_depth = max(0.30, abs(y0 - y_dash) + 0.12)
    dash_c = Vector((0, y0 - dash_depth / 2, dash_top - dash_h / 2))
    dash_half = min(half_in + 0.04, b.half_width(b.cowl + 0.02) * 0.80 - 0.08)  # 앞유리 밑(좁은 곳)을 뚫고 나가지 않게
    parts.append(rounded_box("dash", dash_c, (dash_half * 2, dash_depth, dash_h), "interior", 0.05))
    # 계기판 덮개 + 화면
    dx = driver * seat_x
    rear_y = dash_c.y - dash_depth / 2
    parts.append(rounded_box("binnacle", (dx, rear_y + 0.10, belt + 0.02), (0.34, 0.20, 0.08), "interior", 0.03))
    parts.append(rounded_box("cluster", (dx, rear_y + 0.012, belt - 0.03), (0.30, 0.01, 0.10), "screen", 0.004))
    parts.append(rounded_box("center_screen", (0, rear_y + 0.02, belt - 0.02), (0.24, 0.012, 0.13), "screen", 0.004,
                             Matrix.Rotation(math.radians(-10), 3, "X")))
    # 핸들: 운전자 쪽, 기울어진 고리 + 스포크 3개
    wheel_c = Vector((dx, rear_y - 0.16, belt - 0.07))
    tilt = Matrix.Rotation(math.radians(64), 3, "X")
    bm = bmesh.new()
    seg, ring = 36, 10
    R, r = 0.175, 0.016
    rows = []
    for i in range(seg):
        a = 2 * math.pi * i / seg
        row = []
        for j in range(ring):
            c = 2 * math.pi * j / ring
            p = Vector(((R + r * math.cos(c)) * math.cos(a), (R + r * math.cos(c)) * math.sin(a), r * math.sin(c)))
            row.append(bm.verts.new(tilt @ p + wheel_c))
        rows.append(row)
    for i in range(seg):
        for j in range(ring):
            bm.faces.new([rows[i][j], rows[(i + 1) % seg][j], rows[(i + 1) % seg][(j + 1) % ring], rows[i][(j + 1) % ring]])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    sw = new_object("steering", bm, ["seat"])
    shade(sw, 60)
    parts.append(sw)
    for ang in (0, 180, 270):
        a = math.radians(ang)
        p1 = wheel_c + tilt @ Vector((math.cos(a) * R, math.sin(a) * R, 0))
        parts.append(tube("spoke", wheel_c, p1, 0.012, "interior", 8))
    parts.append(cylinder("hub", (0, 0, 0), 0.05, 0.05, "interior", axis="Z", segments=20))
    parts[-1].matrix_world = Matrix.Translation(wheel_c) @ tilt.to_4x4()
    parts.append(tube("column", wheel_c + tilt @ Vector((0, 0, -0.02)), wheel_c + tilt @ Vector((0, 0, -0.25)), 0.03, "interior"))
    # 센터 콘솔
    parts.append(rounded_box("console", (0, (rear_y + y_seat) / 2, floor + 0.13), (0.22, abs(rear_y - y_seat) + 0.1, 0.24), "interior", 0.04))
    parts.append(rounded_box("shifter", (0, rear_y - 0.30, floor + 0.32), (0.04, 0.04, 0.12), "chrome", 0.015))
    # 뒷좌석 (4도어) 또는 짐칸 덮개
    if d.get("side", {}).get("door2"):
        yb = b.y_of(min(b.sideEnd - 0.04, t_seat + 0.17))
        parts.append(rounded_box("rear_bench", (0, yb, seat_z - 0.02), (half_in * 2 - 0.06, 0.48, 0.12), "seat", 0.04))
        parts.append(rounded_box("rear_back", (0, yb - 0.25, seat_z + 0.30), (half_in * 2 - 0.06, 0.12, 0.6), "seat", 0.05,
                                 Matrix.Rotation(math.radians(-18), 3, "X")))
    else:
        yb = b.y_of(min(b.deck - 0.02, t_seat + 0.12))
        parts.append(rounded_box("parcel", (0, yb, belt - 0.12), (half_in * 2, 0.5, 0.03), "interior", 0.01))
    # 롤케이지 (레이스 차)
    if it.get("cage"):
        r = 0.022
        top = b.roofH - 0.10
        ya = b.y_of(b.roofStart + 0.01)
        yh = y_seat - 0.36
        for s in (1, -1):
            xo = s * (half_in - 0.03)
            xt = s * (b.W / 2 * b.cabin - 0.10)
            parts.append(tube("cage", (xo, yh, floor), (xt, yh, top), r, "cage"))           # 주 후프 다리
            parts.append(tube("cage", (xo, ya + 0.15, floor + 0.1), (xt, ya, top - 0.02), r, "cage"))  # A 필러
            parts.append(tube("cage", (xt, ya, top - 0.02), (xt, yh, top), r, "cage"))       # 지붕 레일
            parts.append(tube("cage", (xt, yh, top), (s * 0.25, yh - 0.6, floor + 0.15), r, "cage"))  # 뒤 버팀
            parts.append(tube("cage", (xo, ya + 0.15, floor + 0.35), (xo, yh, floor + 0.35), r, "cage"))  # 문 바
        parts.append(tube("cage", (-(b.W / 2 * b.cabin - 0.10), yh, top), (b.W / 2 * b.cabin - 0.10, yh, top), r, "cage"))
        parts.append(tube("cage", (-(half_in - 0.03), yh, floor), ((b.W / 2 * b.cabin - 0.10), yh, top), r, "cage"))
    clear_wheels(parts, b)
    return parts


def clear_wheels(parts, b):
    """실내 부품이 바퀴 자리(타이어 원통)를 파고들지 않게: 바퀴 앞뒤 범위에서 옆으로 튀어나온 점을 안쪽으로 민다"""
    for o in parts:
        mw = o.matrix_world
        inv = mw.inverted()
        moved = False
        for v in o.data.vertices:
            p = mw @ v.co
            for (ya, xa, tire) in ((b.yF, b.xF, b.tireF), (b.yR, b.xR, b.tireR)):
                if abs(p.y - ya) < b.R + 0.04 and p.z < 2 * b.R + 0.05:
                    lim = xa - tire / 2 - 0.04
                    if abs(p.x) > lim:
                        p.x = math.copysign(lim, p.x)
                        moved = True
            if moved:
                v.co = inv @ p
        if moved:
            o.data.update()
