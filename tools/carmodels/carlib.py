"""
차 모델 생성 도구 (개발용, Blender 의 bpy 모듈로 실행). 게임은 이 결과 파일만 읽는다.

좌표: Blender 기준 X 오른쪽, Y 앞, Z 위, 땅 Z = 0.
내보낼 때 게임 좌표 (x, y, z) = (X, Z, -Y) 로 바꾼다 (게임은 -z 가 앞, 땅 위 높이 그대로).

만드는 순서 (차체):
  1. 단면 고리를 차 길이 방향으로 세워 이은 거친 망(케이지)을 만든다. 맨 앞·뒤 고리는 세로선으로 모은다.
  2. 서브디비전으로 매끈하게 (어깨선은 크리스로 날을 세움).
  3. 유리 면을 안쪽으로 들이고 둘레를 검은 테두리로.
  4. 두께를 주고(솔리디파이) 바퀴 자리를 원통으로 잘라낸다(불리언).
  5. 라이트·그릴·문틈 같은 무늬는 차체 표면에 쏘아 붙인 얇은 판(데칼)으로 만든다.
"""

import math
import struct
import gzip

import bpy
import bmesh
from mathutils import Vector, Matrix
from mathutils.bvhtree import BVHTree

# ---------------------------------------------------------------------------- 재질

# 이름 → (미리보기 색, 금속성, 거칠기, 발광). 게임에서는 이름으로 엔진 재질을 고른다.
MATERIALS = {
    "paint": ((0.6, 0.05, 0.04), 0.5, 0.3, 0.0),
    "glass": ((0.015, 0.02, 0.025), 0.0, 0.04, 0.0),
    "trim": ((0.012, 0.012, 0.013), 0.0, 0.25, 0.0),        # 광택 검정 (유리 테두리, 기둥)
    "black": ((0.02, 0.02, 0.022), 0.0, 0.7, 0.0),         # 무광 검정 (그릴, 바닥, 휠하우스)
    "chrome": ((0.9, 0.9, 0.92), 1.0, 0.08, 0.0),
    "head": ((0.42, 0.44, 0.47), 1.0, 0.12, 0.0),           # 헤드라이트 반사경 (투명 렌즈 너머 크롬)
    "drl": ((1.0, 0.98, 0.94), 0.0, 0.2, 4.0),              # 주간등 LED (발광)
    "tail": ((0.6, 0.01, 0.01), 0.0, 0.15, 1.5),           # 미등 (붉은 발광)
    "amber": ((0.9, 0.35, 0.02), 0.0, 0.2, 0.5),
    "carbon": ((0.03, 0.03, 0.033), 0.3, 0.25, 0.0),
    "rubber": ((0.025, 0.025, 0.025), 0.0, 0.85, 0.0),
    "rim": ((0.7, 0.7, 0.72), 1.0, 0.3, 0.0),
    "disc": ((0.35, 0.35, 0.36), 1.0, 0.45, 0.0),
    "caliper": ((0.7, 0.04, 0.03), 0.0, 0.35, 0.0),
    "caliper_gold": ((0.8, 0.55, 0.12), 0.6, 0.35, 0.0),
    "caliper_blue": ((0.05, 0.25, 0.7), 0.0, 0.35, 0.0),
    "caliper_black": ((0.03, 0.03, 0.03), 0.0, 0.45, 0.0),
    "plate": ((0.75, 0.76, 0.74), 0.0, 0.5, 0.0),
    "interior": ((0.045, 0.045, 0.05), 0.0, 0.8, 0.0),      # 실내 (천·플라스틱)
    "seat": ((0.03, 0.03, 0.032), 0.0, 0.9, 0.0),           # 시트 (알칸타라)
    "accent": ((0.55, 0.04, 0.03), 0.0, 0.6, 0.0),          # 시트 포인트 색
    "lens": ((0.9, 0.92, 0.95), 0.0, 0.02, 0.0),            # 라이트 투명 덮개
    "cage": ((0.12, 0.12, 0.13), 0.6, 0.4, 0.0),            # 롤케이지
    "screen": ((0.02, 0.03, 0.05), 0.0, 0.1, 0.6),          # 계기판 화면 (약한 발광)
}


def material(name):
    m = bpy.data.materials.get(name)
    if m is not None:
        return m
    col, metal, rough, emit = MATERIALS[name]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = m.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (*col, 1)
    bsdf.inputs["Metallic"].default_value = metal
    bsdf.inputs["Roughness"].default_value = rough
    if name in ("glass", "lens"):
        bsdf.inputs["Transmission Weight"].default_value = 1.0
        bsdf.inputs["Alpha"].default_value = 0.35 if name == "glass" else 0.15
    if name == "paint":
        bsdf.inputs["Coat Weight"].default_value = 1.0
        bsdf.inputs["Coat Roughness"].default_value = 0.03
    if emit > 0:
        bsdf.inputs["Emission Color"].default_value = (*col, 1)
        bsdf.inputs["Emission Strength"].default_value = emit
    return m


def set_paint(color, metallic):
    m = material("paint")
    bsdf = m.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (*color, 1)
    bsdf.inputs["Metallic"].default_value = 0.55 if metallic else 0.0


# ---------------------------------------------------------------------------- 수학 도우미

def lerp(a, b, t):
    return a + (b - a) * t


def clamp01(t):
    return max(0.0, min(1.0, t))


def smoothstep(a, b, x):
    if a == b:
        return 1.0 if x >= b else 0.0
    t = clamp01((x - a) / (b - a))
    return t * t * (3 - 2 * t)


class Curve:
    """(t, 값) 점들을 지나는 매끈한 곡선 (단조 3차 보간, 넘침 없음)."""

    def __init__(self, pts):
        self.pts = sorted(pts)
        xs = [p[0] for p in self.pts]
        ys = [p[1] for p in self.pts]
        n = len(xs)
        d = [(ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) for i in range(n - 1)]
        m = [0.0] * n
        m[0] = d[0]
        m[-1] = d[-1]
        for i in range(1, n - 1):
            m[i] = 0.0 if d[i - 1] * d[i] <= 0 else (d[i - 1] + d[i]) / 2
        for i in range(n - 1):
            if d[i] == 0:
                m[i] = m[i + 1] = 0.0
            else:
                a, b = m[i] / d[i], m[i + 1] / d[i]
                s = a * a + b * b
                if s > 9:
                    k = 3 / math.sqrt(s)
                    m[i] = k * a * d[i]
                    m[i + 1] = k * b * d[i]
        self.xs, self.ys, self.m = xs, ys, m

    def __call__(self, x):
        xs, ys, m = self.xs, self.ys, self.m
        if x <= xs[0]:
            return ys[0]
        if x >= xs[-1]:
            return ys[-1]
        i = 0
        while xs[i + 1] < x:
            i += 1
        h = xs[i + 1] - xs[i]
        t = (x - xs[i]) / h
        h00 = (1 + 2 * t) * (1 - t) ** 2
        h10 = t * (1 - t) ** 2
        h01 = t * t * (3 - 2 * t)
        h11 = t * t * (t - 1)
        return h00 * ys[i] + h10 * h * m[i] + h01 * ys[i + 1] + h11 * h * m[i + 1]


# ---------------------------------------------------------------------------- 물체 도우미

def new_object(name, bm, mats):
    """bmesh → 물체. mats = 재질 이름 목록 (면의 material_index 순서)."""
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for mname in mats:
        me.materials.append(material(mname))
    obj = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(obj)
    return obj


def apply_modifiers(obj):
    """모디파이어를 모두 적용한 새 메시로 바꾼다."""
    dg = bpy.context.evaluated_depsgraph_get()
    ev = obj.evaluated_get(dg)
    me = bpy.data.meshes.new_from_object(ev, preserve_all_data_layers=True, depsgraph=dg)
    old = obj.data
    obj.modifiers.clear()
    obj.data = me
    bpy.data.meshes.remove(old)
    return obj


def shade(obj, sharp_angle_deg=40.0, smooth=True):
    """면을 매끈하게 하되 각이 큰 모서리는 날카롭게 (모서리별 sharp 표시)."""
    me = obj.data
    bm = bmesh.new()
    bm.from_mesh(me)
    lim = math.radians(sharp_angle_deg)
    for f in bm.faces:
        f.smooth = smooth
    for e in bm.edges:
        if len(e.link_faces) == 2:
            a, b = e.link_faces
            sharp = a.normal.angle(b.normal, 0) > lim or a.material_index != b.material_index
            e.smooth = not sharp
        else:
            e.smooth = False
    bm.to_mesh(me)
    bm.free()


def join(objs, name):
    objs = [o for o in objs if o is not None]
    if not objs:
        return None
    bm = bmesh.new()
    mats = []
    for o in objs:
        tmp = bmesh.new()
        tmp.from_mesh(o.data)
        tmp.transform(o.matrix_world)
        remap = []
        for m in o.data.materials:
            if m.name not in mats:
                mats.append(m.name)
            remap.append(mats.index(m.name))
        for f in tmp.faces:
            f.material_index = remap[f.material_index] if remap else 0
        me = bpy.data.meshes.new("tmp")
        tmp.to_mesh(me)
        tmp.free()
        bm.from_mesh(me)
        bpy.data.meshes.remove(me)
    for o in objs:
        bpy.data.objects.remove(o, do_unlink=True)
    return new_object(name, bm, mats)


# ---------------------------------------------------------------------------- 로프트 (단면 이어 붙이기)

def loft(name, rings, face_mat, crease_cols=(), crease=0.0, mats=("paint",), close_front=True, close_rear=True,
         ring_creases=None):
    """
    rings: 고리마다 오른쪽 반 단면 점 [(x, y, z) ...] (아래 가운데 → 위 가운데, 첫·끝 점 x = 0).
    왼쪽은 거울로 만든다. 맨 앞/뒤 고리는 x 를 0 으로 모아 세로선 하나로 닫는다.
    face_mat(i, j) → 재질 번호: i = 고리 사이 번호, j = 단면 구간 번호 (0 .. K-2, 오른쪽 기준, 왼쪽도 같은 j).
    crease_cols: 단면 점 번호 중 길이 방향 모서리에 크리스를 줄 것.
    """
    bm = bmesh.new()
    K = len(rings[0])
    crease_layer = bm.edges.layers.float.get("crease_edge") or bm.edges.layers.float.new("crease_edge")
    verts = []  # verts[i] = 고리 i 의 정점 목록 (오른쪽 0..K-1, 그다음 왼쪽 K-2 .. 1)
    n = len(rings)
    for i, ring in enumerate(rings):
        line = (i == 0 and close_front) or (i == n - 1 and close_rear)
        right = []
        for (x, y, z) in ring:
            right.append(bm.verts.new((0.0 if line else x, y, z)))
        if line:
            ring_v = right + right[K - 2:0:-1]  # 왼쪽 = 같은 점 (세로선)
        else:
            left = [bm.verts.new((-x, y, z)) for (x, y, z) in ring[K - 2:0:-1]]
            ring_v = right + left
        verts.append(ring_v)
    M = 2 * K - 2
    # 고리 안 위치 → (j, 오른쪽?) : 위치 p 와 p+1 사이 구간
    def seg_of(p):
        if p < K - 1:
            return p, True
        return M - 1 - p, False
    for i in range(n - 1):
        a, b = verts[i], verts[i + 1]
        for p in range(M):
            q = (p + 1) % M
            quad = [a[p], a[q], b[q], b[p]]
            uniq = []
            for v in quad:
                if v not in uniq:
                    uniq.append(v)
            if len(uniq) < 3:
                continue
            j, right = seg_of(p)
            try:
                f = bm.faces.new(uniq if right else list(reversed(uniq)))
            except ValueError:
                continue
            f.material_index = face_mat(i, j)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    # 크리스: 길이 방향 모서리 중 단면 점 번호가 crease_cols 인 것
    if crease > 0:
        cols = set()
        for c in crease_cols:
            cols.add(c)
            cols.add(M - c)
        for i in range(n - 1):
            for p in cols:
                e = bm.edges.get((verts[i][p % M], verts[i + 1][p % M]))
                if e is not None:
                    e[crease_layer] = crease
    # 고리 방향 크리스: {고리 번호: (값, 단면 점 시작, 끝)} 그 고리의 해당 구간 모서리
    for i, (val, j0, j1) in (ring_creases or {}).items():
        ring_v = verts[i]
        for p in range(M):
            j, _ = seg_of(p)
            if j0 <= j < j1:
                e = bm.edges.get((ring_v[p], ring_v[(p + 1) % M]))
                if e is not None:
                    e[crease_layer] = val
    return new_object(name, bm, list(mats))


# ---------------------------------------------------------------------------- 표면에 붙이는 판 (데칼)

class Surface:
    """차체 표면에 광선을 쏘는 도구 (적용이 끝난 물체로 만든다)."""

    def __init__(self, obj):
        self.bvh = BVHTree.FromObject(obj, bpy.context.evaluated_depsgraph_get())

    def hit(self, origin, direction, far=6.0):
        d = Vector(direction).normalized()
        loc, normal, _, _ = self.bvh.ray_cast(Vector(origin) - d * far * 0.5, d, far)
        return loc, normal


# 투영 틀: (a, b) 평면 좌표 → 광선 시작점·방향
def frame_front(y_far=4.0):
    return lambda a, b: ((a, y_far, b), (0, -1, 0))


def frame_rear(y_far=-4.0):
    return lambda a, b: ((a, y_far, b), (0, 1, 0))


def frame_side(x_far=3.0):
    """오른쪽 옆면: a = Y(앞쪽 +), b = Z"""
    return lambda a, b: ((x_far, a, b), (-1, 0, 0))


def frame_top(z_far=3.0):
    """위: a = X, b = Y"""
    return lambda a, b: ((a, b, z_far), (0, 0, -1))


def frame_dir(direction, far=3.0):
    """임의 방향 (a, b) = 방향에 수직인 평면 좌표 (위쪽은 +Z 쪽)"""
    d = Vector(direction).normalized()
    up = Vector((0, 0, 1)) if abs(d.z) < 0.9 else Vector((0, 1, 0))
    u = d.cross(up).normalized()
    v = u.cross(d).normalized()

    def f(a, b):
        o = u * a + v * b - d * far
        return (tuple(o), tuple(d))
    return f


def quad_shape(c0, c1, c2, c3, roundness=0.0):
    """네 모서리 (a, b) 사각형 (아래왼, 아래오, 위오, 위왼). roundness 0 = 각짐, 1 = 타원."""
    def f(s, t):  # s, t in [0, 1]
        u, v = s * 2 - 1, t * 2 - 1
        if roundness > 0:
            uu = u * math.sqrt(max(0.0, 1 - v * v / 2))
            vv = v * math.sqrt(max(0.0, 1 - u * u / 2))
            u, v = lerp(u, uu * 1.0, roundness), lerp(v, vv * 1.0, roundness)
        s2, t2 = (u + 1) / 2, (v + 1) / 2
        a = (1 - s2) * (1 - t2) * c0[0] + s2 * (1 - t2) * c1[0] + s2 * t2 * c2[0] + (1 - s2) * t2 * c3[0]
        b = (1 - s2) * (1 - t2) * c0[1] + s2 * (1 - t2) * c1[1] + s2 * t2 * c2[1] + (1 - s2) * t2 * c3[1]
        return a, b
    return f


def disc_shape(ca, cb, ra, rb=None):
    rb = ra if rb is None else rb

    def f(s, t):
        ang = s * 2 * math.pi
        r = t
        return ca + math.cos(ang) * ra * r, cb + math.sin(ang) * rb * r
    return f


def ribbon_shape(points, width):
    """꺾은선 따라 폭 width 띠 (a, b 평면)"""
    pts = [Vector((p[0], p[1])) for p in points]
    lens = [0.0]
    for i in range(1, len(pts)):
        lens.append(lens[-1] + (pts[i] - pts[i - 1]).length)
    total = lens[-1]

    def at(s):
        d = s * total
        i = 0
        while i < len(lens) - 2 and lens[i + 1] < d:
            i += 1
        seg = max(1e-9, lens[i + 1] - lens[i])
        u = (d - lens[i]) / seg
        p = pts[i].lerp(pts[i + 1], u)
        tan = (pts[i + 1] - pts[i]).normalized()
        return p, Vector((-tan.y, tan.x))

    def f(s, t):
        p, nrm = at(s)
        q = p + nrm * (t - 0.5) * width
        return q.x, q.y
    return f


def decal(surface, name, shape, frame, mat, offset=0.003, nu=12, nv=6, mirror=False, closed_u=False):
    """shape(s, t) → (a, b), frame(a, b) → 광선. 표면에 닿은 점 + 법선·offset 으로 판을 만든다."""
    bm = bmesh.new()
    sides = [1, -1] if mirror else [1]
    for side in sides:
        grid = []
        ok = True
        for i in range(nu + (0 if closed_u else 1)):
            row = []
            for j in range(nv + 1):
                a, b = shape(i / nu, j / nv)
                o, d = frame(a, b)
                o = Vector(o)
                d = Vector(d)
                if side < 0:
                    o.x, d.x = -o.x, -d.x
                loc, nrm = surface.hit(o, d)
                if loc is None:
                    ok = False
                    row.append(None)
                    continue
                if nrm.dot(d) > 0:
                    nrm = -nrm
                row.append(bm.verts.new(loc + nrm * offset))
            grid.append(row)
        if closed_u:
            grid.append(grid[0])
        for i in range(len(grid) - 1):
            for j in range(nv):
                q = [grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]]
                if any(v is None for v in q):
                    continue
                uniq = []
                for v in q:
                    if v not in uniq:
                        uniq.append(v)
                if len(uniq) < 3:
                    continue
                try:
                    bm.faces.new(uniq if side > 0 else list(reversed(uniq)))
                except ValueError:
                    pass
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    loose = [v for v in bm.verts if not v.link_faces]
    bmesh.ops.delete(bm, geom=loose, context="VERTS")
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    # 판이 차체 바깥을 향하게: 평균 법선이 광선 반대쪽
    obj = new_object(name, bm, [mat])
    shade(obj, 60)
    return obj


# ---------------------------------------------------------------------------- 기본 도형

def lathe(name, profile, segments, mat_of=None, mats=("rubber",), axis="X", cap=False):
    """profile: [(r, a)] (반지름, 축 방향 위치) 를 축 둘레로 돌린 면. mat_of(k) → k 번째 띠 재질 번호."""
    bm = bmesh.new()
    rings = []
    for k in range(segments):
        ang = 2 * math.pi * k / segments
        c, s = math.cos(ang), math.sin(ang)
        ring = []
        for (r, a) in profile:
            if axis == "X":
                ring.append(bm.verts.new((a, r * c, r * s)))
            else:
                ring.append(bm.verts.new((r * c, r * s, a)))
        rings.append(ring)
    for k in range(segments):
        r0, r1 = rings[k], rings[(k + 1) % segments]
        for i in range(len(profile) - 1):
            q = [r0[i], r1[i], r1[i + 1], r0[i + 1]]
            uniq = []
            for v in q:
                if v not in uniq:
                    uniq.append(v)
            if len(uniq) < 3:
                continue
            try:
                f = bm.faces.new(uniq)
                f.material_index = mat_of(i) if mat_of else 0
            except ValueError:
                pass
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_object(name, bm, list(mats))


def box(name, center, size, mat, rot=None):
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    bmesh.ops.scale(bm, vec=Vector(size), verts=bm.verts)
    if rot is not None:
        bmesh.ops.rotate(bm, cent=(0, 0, 0), matrix=rot, verts=bm.verts)
    bmesh.ops.translate(bm, vec=Vector(center), verts=bm.verts)
    o = new_object(name, bm, [mat])
    shade(o, 30)
    return o


def cylinder(name, center, radius, length, mat, axis="X", segments=24, cap=True):
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=cap, cap_tris=False, segments=segments, radius1=radius, radius2=radius, depth=length)
    if axis == "X":
        bmesh.ops.rotate(bm, cent=(0, 0, 0), matrix=Matrix.Rotation(math.pi / 2, 3, "Y"), verts=bm.verts)
    elif axis == "Y":
        bmesh.ops.rotate(bm, cent=(0, 0, 0), matrix=Matrix.Rotation(math.pi / 2, 3, "X"), verts=bm.verts)
    bmesh.ops.translate(bm, vec=Vector(center), verts=bm.verts)
    o = new_object(name, bm, [mat])
    shade(o, 35)
    return o


def extrude_outline(name, outline, depth, mat, plane="XZ", origin=(0, 0, 0), bevel=0.0):
    """2D 외곽선 (반시계) 을 depth 만큼 밀어낸 판 (plane = 외곽선이 놓인 평면, 나머지 축으로 밂)."""
    bm = bmesh.new()
    vs = []
    for (a, b) in outline:
        if plane == "XZ":
            vs.append(bm.verts.new((a, 0, b)))
        elif plane == "YZ":
            vs.append(bm.verts.new((0, a, b)))
        else:
            vs.append(bm.verts.new((a, b, 0)))
    f = bm.faces.new(vs)
    ax = {"XZ": (0, 1, 0), "YZ": (1, 0, 0), "XY": (0, 0, 1)}[plane]
    r = bmesh.ops.extrude_face_region(bm, geom=[f])
    moved = [g for g in r["geom"] if isinstance(g, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, vec=Vector(ax) * depth, verts=moved)
    bmesh.ops.translate(bm, vec=Vector(ax) * (-depth / 2), verts=bm.verts)
    bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    if bevel > 0:
        bmesh.ops.bevel(bm, geom=list(bm.edges), offset=bevel, segments=1, affect="EDGES", clamp_overlap=True)
    bmesh.ops.translate(bm, vec=Vector(origin), verts=bm.verts)
    o = new_object(name, bm, [mat])
    shade(o, 30)
    return o


# ---------------------------------------------------------------------------- 내보내기

def export_parts(path, parts, wheel_parts):
    """
    parts: {이름: 물체}, 게임 좌표로 바꿔 gzip 이진 파일로 쓴다.
    형식 (빅엔디안): 'CMSH' int 버전(1) int 부품 수,
      부품마다 utf(이름) int 묶음 수, 묶음마다 utf(재질) int 정점 수 float[정점×6] (위치, 법선) int 인덱스 수 int[...]
    """
    out = bytearray()
    out += b"CMSH"
    out += struct.pack(">ii", 1, len(parts) + len(wheel_parts))
    total_tris = 0
    for name, obj in list(parts.items()) + list(wheel_parts.items()):
        groups = mesh_groups(obj)
        enc = name.encode("utf-8")
        out += struct.pack(">H", len(enc)) + enc
        out += struct.pack(">i", len(groups))
        for mname, (verts, idx) in groups.items():
            e = mname.encode("utf-8")
            out += struct.pack(">H", len(e)) + e
            out += struct.pack(">i", len(verts) // 6)
            out += struct.pack(">%df" % len(verts), *verts)
            out += struct.pack(">i", len(idx))
            out += struct.pack(">%di" % len(idx), *idx)
            total_tris += len(idx) // 3
    with gzip.open(path, "wb", compresslevel=9) as f:
        f.write(bytes(out))
    return total_tris


def mesh_groups(obj):
    """물체 → {재질: (정점 float 목록, 인덱스)} 게임 좌표, 모서리 법선(날카로운 모서리 반영)"""
    me = obj.data
    me.calc_loop_triangles()
    normals = me.corner_normals
    mw = obj.matrix_world
    nm = mw.to_3x3().inverted().transposed()
    groups = {}
    keys = {}
    for tri in me.loop_triangles:
        mname = me.materials[tri.material_index].name if me.materials else "paint"
        verts, idx = groups.setdefault(mname, ([], []))
        kmap = keys.setdefault(mname, {})
        for li in tri.loops:
            vi = me.loops[li].vertex_index
            p = mw @ me.vertices[vi].co
            n = (nm @ normals[li].vector).normalized()
            g = (round(p.x, 5), round(p.z, 5), round(-p.y, 5), round(n.x, 3), round(n.z, 3), round(-n.y, 3))
            k = kmap.get(g)
            if k is None:
                k = len(verts) // 6
                kmap[g] = k
                verts.extend(g)
            idx.append(k)
    return groups


# ---------------------------------------------------------------------------- 미리보기

def preview(path, objs_visible, views, size=(960, 540), samples=24):
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.render.resolution_x, scene.render.resolution_y = size
    scene.render.film_transparent = False
    world = bpy.data.worlds.get("W") or bpy.data.worlds.new("W")
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    bg.inputs[0].default_value = (0.62, 0.70, 0.82, 1)
    bg.inputs[1].default_value = 0.9
    scene.world = world
    if "sun" not in bpy.data.objects:
        sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
        sun.data.energy = 4.0
        sun.data.angle = math.radians(3)
        sun.rotation_euler = (math.radians(50), math.radians(10), math.radians(140))
        scene.collection.objects.link(sun)
        floor = bpy.data.objects.new("floor", bpy.data.meshes.new("floor"))
        bm = bmesh.new()
        bmesh.ops.create_grid(bm, x_segments=1, y_segments=1, size=30)
        bm.to_mesh(floor.data)
        bm.free()
        fm = bpy.data.materials.new("floor")
        fm.use_nodes = True
        fm.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.32, 0.32, 0.33, 1)
        fm.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.8
        floor.data.materials.append(fm)
        scene.collection.objects.link(floor)
    cam = bpy.data.objects.get("cam")
    if cam is None:
        cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
        scene.collection.objects.link(cam)
    scene.camera = cam
    cam.data.lens = 50
    for o in scene.objects:
        if o.type == "MESH" and o.name != "floor":
            o.hide_render = o not in objs_visible
    files = []
    for k, (loc, target) in enumerate(views):
        cam.location = loc
        d = Vector(target) - Vector(loc)
        cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
        p = path.replace(".png", "-%d.png" % k)
        scene.render.filepath = p
        bpy.ops.render.render(write_still=True)
        files.append(p)
    return files


def anchor_frame(anchor, normal, far=1.0):
    """차체 위 한 점 기준 틀: (a, b) = 그 점에서 옆(u)·위(v) 로 m 단위 거리. 광선은 바깥 → 안쪽 (-normal)."""
    n = Vector(normal).normalized()
    u = n.cross(Vector((0, 0, 1)))
    if u.length < 1e-4:
        u = Vector((1, 0, 0))
    u.normalize()
    if u.x < -0.3:
        u = -u
    v = u.cross(n).normalized()
    if v.z < 0:
        v = -v
    p = Vector(anchor)

    def f(a, b):
        o = p + u * a + v * b + n * far
        return (tuple(o), tuple(-n))
    return f


def split_materials(obj, names, new_name):
    """obj 에서 재질 이름이 names 인 면을 떼어 새 물체로 (예: 투명 유리). 없으면 None"""
    me = obj.data
    idx = {i for i, m in enumerate(me.materials) if m is not None and m.name in names}
    if not idx:
        return None
    bm = bmesh.new()
    bm.from_mesh(me)
    take = [f for f in bm.faces if f.material_index in idx]
    if not take:
        bm.free()
        return None
    other = bmesh.new()
    other.from_mesh(me)
    keep_other = [f for f in other.faces if f.material_index not in idx]
    bmesh.ops.delete(other, geom=keep_other, context="FACES")
    me2 = bpy.data.meshes.new(new_name)
    other.to_mesh(me2)
    other.free()
    for m in me.materials:
        me2.materials.append(m)
    o2 = bpy.data.objects.new(new_name, me2)
    o2.matrix_world = obj.matrix_world.copy()
    bpy.context.scene.collection.objects.link(o2)
    bmesh.ops.delete(bm, geom=take, context="FACES")
    bm.to_mesh(me)
    bm.free()
    return o2
