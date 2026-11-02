"""차체 (도로차): 단면 로프트 케이지 → 서브디비전 → 유리 → 두께 → 바퀴 아치."""

import math
import bmesh
import bpy
from mathutils import Vector, Matrix

from carlib import (Curve, lerp, smoothstep, clamp01, loft, apply_modifiers, shade, new_object, cylinder, material)

# 단면 점 번호 (오른쪽 반, 아래 가운데 → 위 가운데)
P_BOT, P_BOT_OUT, P_ROCK, P_SILL, P_DOOR, P_SHOULDER, P_BELT, P_WIN_TOP, P_ROOF_EDGE, P_ROOF_IN, P_TOP = range(11)
K = 11
# 재질 번호
M_PAINT, M_GLASS, M_TRIM, M_BLACK = range(4)
MATS = ("paint", "glass", "trim", "black", "interior", "black", "interior", "black")
INNER = 4  # 솔리디파이 안쪽 껍질 재질 번호 시작


class Body:
    """dims.json 한 대 + 디자인 값(over) → 단면 함수들"""

    def __init__(self, dims, over):
        st = dims["style"]
        self.o = over
        self.L = st["length"]
        self.W = st["width"]
        self.R = dims["wheelRadius"]
        m = dims["mounts"]
        self.yF = -m[0][2]                 # 앞바퀴 Y (Blender, 앞 +)
        self.yR = -m[2][2]
        self.xF = abs(m[0][0])
        self.xR = abs(m[2][0])
        self.tireF = st["tireWidthF"]
        self.tireR = st["tireWidthR"]
        self.tF = self.t_of(self.yF)
        self.tR = self.t_of(self.yR)
        g = lambda k, d=None: over.get(k, st.get(k, d))
        self.noseH, self.hoodH, self.roofH = g("noseH"), g("hoodH"), g("roofH")
        self.deckH, self.tailH, self.sillH, self.beltH = g("deckH"), g("tailH"), g("sillH"), g("beltH")
        self.cowl, self.roofStart, self.roofEnd, self.deck = g("cowlU"), g("roofStartU"), g("roofEndU"), g("deckU")
        self.cabin = g("cabinWidth")
        self.flare = g("flare")
        self.bPillar = g("bPillar", False)
        # 옆유리 뒤끝 (쿠페는 C필러 앞에서 끝남)
        self.sideEnd = over.get("sideEnd", self.roofEnd + (self.deck - self.roofEnd) * over.get("sideRear", 0.45))
        self.doorEnd = over.get("doorEnd", self.roofStart + (self.roofEnd - self.roofStart) * 0.62)
        self.glassStart = self.cowl + (self.roofStart - self.cowl) * 0.3

        # ---- 옆에서 본 윗선 (가운데)
        nose_rise = over.get("noseRise", 0.55)
        tops = [
            (0.0, self.noseH * over.get("noseTip", 0.86)),
            (0.025, self.noseH),
            (0.025 + 0.38 * (self.cowl - 0.025), self.noseH + nose_rise * (self.hoodH - self.noseH)),
            (self.cowl, self.hoodH),
            (self.cowl + 0.5 * (self.roofStart - self.cowl), self.hoodH + over.get("wsCurve", 0.6) * (self.roofH - self.hoodH)),
            (self.roofStart, self.roofH - 0.012),
            ((self.roofStart + self.roofEnd) / 2, self.roofH),
            (self.roofEnd, self.roofH - over.get("roofDrop", 0.03)),
        ]
        rw = over.get("rearWindowCurve", 0.45)
        tops.append((self.roofEnd + 0.5 * (self.deck - self.roofEnd), self.deckH + rw * (self.roofH - self.deckH)))
        tops.append((self.deck, self.deckH))
        tops.append((0.975, self.tailH))
        tops.append((1.0, self.tailH * over.get("tailTip", 0.92)))
        self.top = Curve(tops)
        # ---- 바닥선
        b = over.get("bottom", 0.13)
        self.bottom = Curve([(0.0, over.get("chin", 0.26)), (0.02, b + 0.03), (0.07, b), (0.93, b + 0.01),
                             (0.985, over.get("rearBottom", 0.22)), (1.0, over.get("rearTipBottom", 0.30))])
        # ---- 어깨선 (가장 넓은 높이)
        sh0 = over.get("shoulderFront", self.noseH * 0.80)
        sh1 = over.get("shoulderRear", min(self.tailH, self.beltH) * 0.80)
        self.shoulder = lambda t: lerp(sh0, sh1, t ** 0.85)
        # ---- 위에서 본 반폭 비율
        fw = over.get("frontWidth", [(0.0, 0.70), (0.004, 0.74), (0.012, 0.84), (0.03, 0.92), (0.06, 0.965), (0.12, 0.99), (0.2, 1.0)])
        rwid = over.get("rearWidth", [(0.84, 1.0), (0.9, 0.99), (0.95, 0.965), (0.975, 0.93), (0.99, 0.86), (0.997, 0.78), (1.0, 0.74)])
        self.plan = Curve(fw + [(0.5, 0.995)] + rwid)

    def t_of(self, y):
        return (self.L / 2 - y) / self.L

    def y_of(self, t):
        return self.L / 2 - t * self.L

    def fender_factor(self, t):
        """바퀴 위 펜더 구간 0 ~ 1 (축에서 1)"""
        win = (self.R + 0.06) * self.o.get("fenderSpan", 1.45) / self.L
        k = 0.0
        for ta in (self.tF, self.tR):
            d = abs(t - ta) / win
            if d < 1:
                k = max(k, math.cos(d * math.pi / 2) ** 2)
        return k

    def cabin_factor(self, t):
        return smoothstep(self.cowl, self.roofStart, t) * (1 - smoothstep(self.roofEnd, self.deck, t))

    def half_width(self, t):
        w = self.W / 2 * self.plan(t)
        # 펜더 불룩함: 바퀴 위
        win = self.R * 1.35 / self.L
        for ta in (self.tF, self.tR):
            d = abs(t - ta) / win
            if d < 1:
                w += self.flare * math.cos(d * math.pi / 2) ** 2
        # 문 쪽은 조금 들어감
        mid = (self.tF + self.tR) / 2
        span = (self.tR - self.tF) / 2
        d = abs(t - mid) / span
        if d < 1:
            w -= self.o.get("waist", 0.4) * self.flare * math.cos(d * math.pi / 2) ** 2
        return w - self.flare * 0.5

    def ring(self, t):
        y = self.y_of(t)
        w = self.half_width(t)
        top = self.top(t)
        zb = self.bottom(t)
        zs = max(zb + 0.05, self.o.get("sill", self.sillH))
        zsh = min(self.shoulder(t), top - 0.08)
        zs = min(zs, zsh - 0.08)
        c = self.cabin_factor(t)
        cw = self.W / 2 * self.cabin * (1 - 0.06 * smoothstep(self.roofStart, self.deck, t))
        belt = self.beltH + 0.035 * t
        # 보닛·트렁크 위 (c = 0)
        hood_edge_z = min(top - 0.03, zsh + (top - zsh) * self.o.get("hoodEdge", 0.62))
        hood = [(w * 0.935, hood_edge_z), (w * 0.80, top - 0.016), (w * 0.60, top - 0.007), (w * 0.31, top - 0.002)]
        # 펜더 봉우리: 바퀴 위에서는 어깨·펜더 윗선이 아치보다 충분히 높게 (보닛 가운데보다 높을 수도 있음)
        fk = self.fender_factor(t)
        if fk > 0:
            peak = 2 * self.R + 0.055 + self.o.get("fenderRise", 0.09)
            zsh = lerp(zsh, max(zsh, peak - 0.10), fk)
            fz = max(hood_edge_z, peak)
            hood[0] = (hood[0][0], lerp(hood[0][1], fz, fk))
            hood[1] = (hood[1][0], lerp(hood[1][1], max(hood[1][1], fz + 0.012), fk))
            # 보닛 쪽으로 부드럽게 이어지게 (봉우리 옆이 움푹 꺼지지 않게)
            hood[2] = (hood[2][0], lerp(hood[2][1], max(hood[2][1], fz - 0.01), fk * 0.75))
            hood[3] = (hood[3][0], lerp(hood[3][1], max(hood[3][1], fz - 0.05), fk * 0.4))
            zs = min(zs, zsh - 0.08)
        # 실내 (c = 1)
        wb = w * self.o.get("tumble", 0.87)
        cab = [(wb, belt), (cw + 0.02, top - self.o.get("railDrop", 0.075)), (cw - 0.04, top - 0.028), (cw * 0.5, top - 0.006)]
        up = [(lerp(h[0], k[0], c), lerp(h[1], k[1], c)) for h, k in zip(hood, cab)]
        pts = [
            (0.0, zb),
            (w * 0.80, zb),
            (w * 0.93, zb + 0.035),
            (w * self.o.get("rocker", 0.955), zs),
            (w * 0.99, zs + 0.5 * (zsh - zs)),
            (w, zsh),
        ] + up + [(0.0, top)]
        return [(x, y, z) for (x, z) in pts]

    def stations(self):
        keys = [self.cowl, self.glassStart, self.roofStart, self.doorEnd - 0.006, self.doorEnd + 0.006,
                self.roofEnd, self.sideEnd, self.deck]
        win = (self.R + 0.06) / self.L
        for ta in (self.tF, self.tR):
            keys += [ta - win * 1.05, ta - win * 0.5, ta, ta + win * 0.5, ta + win * 1.05]
        ends = [0.0, 0.004, 0.012, 0.022, 0.035, 0.055, 0.08, 0.11, 0.89, 0.92, 0.945, 0.965, 0.978, 0.988, 0.996, 1.0]
        protected = set(round(t, 4) for t in keys + ends)
        ts = sorted(set(round(t, 4) for t in ends + keys + [i / 30 for i in range(4, 27)] if 0 <= t <= 1))
        out = []
        for t in ts:
            if out and t - out[-1] < 0.01 and t not in protected:
                continue
            out.append(t)
        return out

    def face_material(self, ts):
        def f(i, j):
            t = (ts[i] + ts[i + 1]) / 2
            if j == P_BELT and self.glassStart <= t <= self.sideEnd:
                if abs(t - self.doorEnd) < 0.006:
                    return M_TRIM if self.o.get("bPillarBlack", True) else M_PAINT
                return M_GLASS
            if j in (P_ROOF_EDGE, P_ROOF_IN) and self.cowl <= t <= self.roofStart:
                return M_GLASS
            if j in (P_ROOF_EDGE, P_ROOF_IN) and self.roofEnd <= t <= self.deck and self.o.get("rearGlass", True):
                return M_GLASS
            if j == P_WIN_TOP and self.o.get("blackRoof") and self.roofStart <= t <= self.roofEnd:
                return M_TRIM
            if j in (P_ROOF_EDGE, P_ROOF_IN) and self.o.get("blackRoof") and self.roofStart <= t <= self.roofEnd:
                return M_TRIM
            return M_PAINT
        return f


def build_body(dims, over, name="body"):
    b = Body(dims, over)
    ts = b.stations()
    rings = [b.ring(t) for t in ts]
    # 앞면 윗모서리(보닛 앞끝), 트렁크 끝 모서리에 날
    rc = {}
    for i, t in enumerate(ts):
        if abs(t - over.get("noseEdgeT", 0.022)) < 1e-4:
            rc[i] = (over.get("noseCrease", 0.7), P_SHOULDER, P_TOP)
        if abs(t - over.get("tailEdgeT", 0.978)) < 1e-4:
            rc[i] = (over.get("tailCrease", 0.8), P_SHOULDER, P_TOP)
    obj = loft(name, rings, b.face_material(ts), crease_cols=(P_SHOULDER, P_BELT, P_WIN_TOP, P_ROCK),
               crease=over.get("crease", 0.55), mats=MATS, ring_creases=rc)
    sub = obj.modifiers.new("sub", "SUBSURF")
    sub.levels = sub.render_levels = over.get("subdiv", 3)
    sub.boundary_smooth = "PRESERVE_CORNERS"
    apply_modifiers(obj)
    inset_glass(obj)
    sol = obj.modifiers.new("sol", "SOLIDIFY")
    sol.thickness = 0.022
    sol.offset = -1
    sol.use_even_offset = False       # True 면 모인 코끝에서 가시가 튀어나옴
    sol.use_quality_normals = True
    sol.material_offset = INNER
    apply_modifiers(obj)
    cut_arches(obj, b)
    drop_inner_shell(obj, b)
    shade(obj, 38)
    return obj, b


def inset_glass(obj):
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    glass = [f for f in bm.faces if f.material_index == M_GLASS]
    if glass:
        r = bmesh.ops.inset_region(bm, faces=glass, thickness=0.014, depth=-0.005, use_even_offset=False)
        for f in r["faces"]:
            f.material_index = M_TRIM
    bm.to_mesh(obj.data)
    bm.free()


def arch_radius(b):
    return b.R + 0.055


def cut_arches(obj, b):
    """바퀴 자리를 원통으로 잘라낸다 (원통은 차 가운데까지 들어가지 않음)."""
    cutters = []
    for (y, x, tire) in ((b.yF, b.xF, b.tireF), (b.yR, b.xR, b.tireR)):
        for side in (1, -1):
            inner = x - tire / 2 - 0.10
            length = 2.0
            cx = side * (inner + length / 2)
            c = cylinder("cut", (cx, y, b.R), arch_radius(b), length, "black", axis="X", segments=48)
            cutters.append(c)
    from carlib import join
    cut = join(cutters, "cutters")
    mod = obj.modifiers.new("arch", "BOOLEAN")
    mod.operation = "DIFFERENCE"
    mod.object = cut
    # MANIFOLD: 빠르고 촘촘한 망에서도 안정적 (EXACT 는 자기 교차가 있는 두께 망에서 일부만 잘림)
    mod.solver = "MANIFOLD"
    apply_modifiers(obj)
    bpy.data.objects.remove(cut, do_unlink=True)


def drop_inner_shell(obj, b):
    """안쪽 껍질: 아치 둘레는 검게(펜더 안쪽), 실내 구간은 실내 재질(문 안쪽·천장·바닥, 투명 유리로 보임),
    나머지와 유리의 안쪽 복사본은 지운다."""
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    centers = [Vector((s * x, y, b.R)) for (y, x) in ((b.yF, b.xF), (b.yR, b.xR)) for s in (1, -1)]
    lim = arch_radius(b) + 0.12
    y0, y1 = b.y_of(b.cowl - 0.03), b.y_of(min(0.995, b.deck + 0.03))
    dead = []
    for f in bm.faces:
        if f.material_index < INNER:
            continue
        if f.material_index == INNER + M_GLASS:
            dead.append(f)
            continue
        c = f.calc_center_median()
        near = any(abs(c.x - w.x) < 0.5 and math.hypot(c.y - w.y, c.z - w.z) < lim for w in centers)
        if near:
            f.material_index = M_BLACK
        elif y1 <= c.y <= y0:
            f.material_index = INNER  # 실내
        else:
            dead.append(f)
    bmesh.ops.delete(bm, geom=dead, context="FACES")
    bm.to_mesh(obj.data)
    bm.free()


def wheel_tubs(b):
    """아치 안쪽 검은 휠하우스 (원통 윗부분만, 차 바닥 위)."""
    parts = []
    for (y, x, tire) in ((b.yF, b.xF, b.tireF), (b.yR, b.xR, b.tireR)):
        r = arch_radius(b) + 0.004
        x0 = x - tire / 2 - 0.09
        x1 = x + tire / 2 + 0.006
        zcut = b.bottom(b.t_of(y)) + 0.01
        for side in (1, -1):
            bm = bmesh.new()
            seg = 40
            rows = []
            for k in range(seg + 1):
                a = -math.pi / 2 + math.pi * 2 * k / seg
                z = b.R + r * math.sin(a)
                yy = y + r * math.cos(a)
                if z < zcut:
                    rows.append(None)
                    continue
                rows.append((bm.verts.new((side * x0, yy, z)), bm.verts.new((side * x1, yy, z))))
            for k in range(seg):
                if rows[k] is None or rows[k + 1] is None:
                    continue
                bm.faces.new([rows[k][0], rows[k + 1][0], rows[k + 1][1], rows[k][1]])
            # 안쪽 벽
            ring = [r_[0] for r_ in rows if r_ is not None]
            if len(ring) > 2:
                try:
                    bm.faces.new(ring)
                except ValueError:
                    pass
            parts.append(new_object("tub", bm, ["black"]))
    return parts
