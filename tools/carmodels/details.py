"""차체 위 부품: 라이트, 그릴·흡기구, 문틈, 손잡이, 미러, 배기구, 번호판, 와이퍼. 차마다 design 값으로 모양을 바꾼다."""

import math
from mathutils import Vector, Matrix
import bmesh

from carlib import (decal, anchor_frame, quad_shape, disc_shape, ribbon_shape, cylinder, box, lathe, new_object,
                    shade, extrude_outline, lerp, join)


def surface_point(surf, origin, direction):
    loc, nrm = surf.hit(origin, direction)
    return loc, nrm


def front_anchor(surf, b, x, z, back=0.0):
    """앞에서 (x, z) 로 쏜 광선이 닿은 점과 법선"""
    loc, nrm = surf.hit((x, b.L / 2 + 1.0, z), (0, -1, 0))
    return loc, nrm


def rear_anchor(surf, b, x, z):
    loc, nrm = surf.hit((x, -b.L / 2 - 1.0, z), (0, 1, 0))
    return loc, nrm


def side_anchor(surf, b, y, z):
    loc, nrm = surf.hit((b.W, y, z), (-1, 0, 0))
    return loc, nrm


def blend_normal(nrm, axis, k):
    return (Vector(nrm).normalized() * (1 - k) + Vector(axis) * k).normalized()


# ---------------------------------------------------------------------------- 앞

def headlights(surf, b, d):
    """d['head'] = {type, x, z, w, h, slant, wrap}"""
    h = d.get("head", {})
    kind = h.get("type", 0)
    x = h.get("x", b.W / 2 * 0.62)
    z = h.get("z", b.noseH - 0.06)
    w = h.get("w", 0.42)
    hh = h.get("h", 0.11)
    slant = h.get("slant", 0.04)        # 바깥쪽이 올라감
    wrap = h.get("wrap", 0.45)          # 법선을 옆으로 얼마나 돌리나 (모서리를 감싸는 라이트)
    loc, nrm = front_anchor(surf, b, x, z)
    if loc is None:
        return []
    n = blend_normal(nrm, (0.5, 0.86, 0.1), wrap * 0.3)
    fr = anchor_frame(loc, n)
    parts = []
    if kind == 1:  # 둥근 덮개형 (타원)
        house = disc_shape(0, 0, w * 0.55, hh * 0.75)
        parts.append(decal(surf, "head_house", house, fr, "trim", 0.003, 24, 4, mirror=True, closed_u=True))
        parts.append(decal(surf, "head_cover", house, fr, "lens", 0.012, 24, 4, mirror=True, closed_u=True))
        parts.append(decal(surf, "head_lens", disc_shape(0, 0, w * 0.5, hh * 0.65), fr, "head", 0.005, 24, 4, mirror=True, closed_u=True))
        parts.append(decal(surf, "head_drl", disc_shape(-w * 0.12, 0, w * 0.13, hh * 0.32), fr, "drl", 0.007, 16, 2, mirror=True, closed_u=True))
        parts.append(decal(surf, "head_proj", disc_shape(w * 0.17, 0, w * 0.11, hh * 0.32), fr, "chrome", 0.007, 16, 2, mirror=True, closed_u=True))
    elif kind == 2:  # 픽셀 (작은 네모 여러 개)
        cols, rows = h.get("cols", 4), h.get("rows", 2)
        house = quad_shape((-w / 2, -hh / 2), (w / 2, -hh / 2), (w / 2, hh / 2), (-w / 2, hh / 2), 0.15)
        parts.append(decal(surf, "head_house", house, fr, "trim", 0.003, 10, 4, mirror=True))
        parts.append(decal(surf, "head_cover", house, fr, "lens", 0.011, 10, 4, mirror=True))
        cw, ch = w / cols, hh / rows
        for i in range(cols):
            for j in range(rows):
                a0, b0 = -w / 2 + cw * (i + 0.18), -hh / 2 + ch * (j + 0.18)
                a1, b1 = a0 + cw * 0.64, b0 + ch * 0.64
                parts.append(decal(surf, "pix", quad_shape((a0, b0), (a1, b0), (a1, b1), (a0, b1)), fr, "drl", 0.006, 2, 2, mirror=True))
    else:  # 가는 사다리꼴 (안쪽 낮고 바깥쪽 높음)
        c0, c1 = (-w / 2, -hh / 2), (w / 2, -hh / 2 + slant)
        c2, c3 = (w / 2 - 0.02, hh / 2 + slant), (-w / 2 + 0.03, hh / 2 - 0.01)
        house = quad_shape(c0, c1, c2, c3, 0.25)
        parts.append(decal(surf, "head_house", house, fr, "trim", 0.003, 12, 4, mirror=True))
        parts.append(decal(surf, "head_cover", house, fr, "lens", 0.012, 12, 4, mirror=True))
        sh = lambda p, k: (p[0] * k, p[1] * k + 0.002)
        parts.append(decal(surf, "head_lens", quad_shape(sh(c0, 0.9), sh(c1, 0.9), sh(c2, 0.86), sh(c3, 0.86), 0.25), fr, "head", 0.005, 12, 4, mirror=True))
        # 주간등 띠 (렌즈 아래쪽 가장자리)
        drl = [(c0[0] * 0.8, c0[1] * 0.55), (c1[0] * 0.85, c1[1] * 0.55 + slant * 0.4)]
        parts.append(decal(surf, "head_drl", ribbon_shape(drl, 0.012), fr, "drl", 0.007, 10, 1, mirror=True))
        for k in (-0.22, 0.12):
            cy = 0.006 + slant * (k + 0.5)
            r = min(0.032, hh * 0.32)
            parts.append(decal(surf, "head_ring", disc_shape(w * k, cy, r * 1.25), fr, "trim", 0.0065, 16, 2, mirror=True, closed_u=True))
            parts.append(decal(surf, "head_proj", disc_shape(w * k, cy, r), fr, "chrome", 0.0075, 16, 2, mirror=True, closed_u=True))
            parts.append(decal(surf, "head_bulb", disc_shape(w * k, cy, r * 0.55), fr, "lens", 0.0085, 12, 2, mirror=True, closed_u=True))
    if h.get("twin"):  # 두 줄 라이트 (위아래로 하나 더)
        fr2 = anchor_frame(loc + Vector((0, 0, -h["twin"])), n)
        c0, c1 = (-w / 2, -0.012), (w / 2, -0.012 + slant)
        c2, c3 = (w / 2, 0.012 + slant), (-w / 2, 0.012)
        parts.append(decal(surf, "head_twin", quad_shape(c0, c1, c2, c3), fr2, "drl", 0.006, 12, 1, mirror=True))
    return parts


def honeycomb(surf, fr, w, h, cell, shape):
    """그릴 안 벌집 무늬 (작은 육각형 테두리들)"""
    parts = []
    dx = cell * 1.5
    dy = cell * math.sqrt(3) / 2
    rows = int(h / dy)
    for j in range(rows + 1):
        y = -h / 2 + j * dy
        off = (j % 2) * dx / 2
        cols = int(w / dx)
        for i in range(cols + 1):
            x = -w / 2 + i * dx + off
            if abs(x) > w / 2 - cell * 0.4 or abs(y) > h / 2 - cell * 0.3:
                continue
            if shape == "crest" and abs(x) > (w * 0.30 + (w * 0.20) * (y + h / 2) / h):
                continue
            ring = [(x + cell * 0.48 * math.cos(math.pi / 3 * k), y + cell * 0.48 * math.sin(math.pi / 3 * k)) for k in range(7)]
            parts.append(decal(surf, "hex", ribbon_shape(ring, cell * 0.10), fr, "trim", 0.006, 12, 1))
    return [join(parts, "honeycomb")] if parts else []


def front_intakes(surf, b, d):
    g = d.get("grille", {})
    parts = []
    z = g.get("z", b.bottom(0.02) + 0.10)
    w = g.get("w", b.W * 0.42)
    hgt = g.get("h", 0.12)
    loc, nrm = front_anchor(surf, b, 0.0, z)
    if loc is not None:
        fr = anchor_frame(loc, (0, 1, 0.05))
        shape = g.get("shape", "rect")
        if shape == "hex":  # 넓은 육각
            pts = [(-w / 2, 0), (-w / 2 + 0.06, -hgt / 2), (w / 2 - 0.06, -hgt / 2), (w / 2, 0), (w / 2 - 0.06, hgt / 2), (-w / 2 + 0.06, hgt / 2)]
            sh = quad_shape((-w / 2 + 0.03, -hgt / 2), (w / 2 - 0.03, -hgt / 2), (w / 2 - 0.03, hgt / 2), (-w / 2 + 0.03, hgt / 2), 0.35)
        elif shape == "crest":  # 방패형 큰 그릴 (위가 넓음)
            sh = quad_shape((-w * 0.30, -hgt / 2), (w * 0.30, -hgt / 2), (w / 2, hgt / 2), (-w / 2, hgt / 2), 0.15)
        elif shape == "slot":  # 전기차: 얇은 틈
            sh = quad_shape((-w / 2, -hgt / 2), (w / 2, -hgt / 2), (w / 2, hgt / 2), (-w / 2, hgt / 2), 0.6)
        else:
            sh = quad_shape((-w / 2, -hgt / 2), (w / 2, -hgt / 2), (w / 2, hgt / 2), (-w / 2, hgt / 2), 0.5)
        parts.append(decal(surf, "grille", sh, fr, "black", 0.003, 16, 6))
        if g.get("honeycomb", shape != "slot"):
            parts += honeycomb(surf, fr, w * 0.92, hgt * 0.8, g.get("cell", 0.032), shape)
        # 가로 살 (크롬 또는 검정 광택)
        if g.get("slats", 2) and shape != "slot":
            n = g.get("slats", 2)
            for k in range(n):
                bz = -hgt / 2 + hgt * (k + 1) / (n + 1)
                parts.append(decal(surf, "slat", ribbon_shape([(-w * 0.42, bz), (w * 0.42, bz)], 0.008), fr,
                                   g.get("slatMat", "trim"), 0.005, 12, 1))
    # 양쪽 흡기구
    side = d.get("sideIntake", {"w": 0.16, "h": 0.07})
    if side:
        sx = side.get("x", b.W / 2 * 0.70)
        sz = side.get("z", z)
        loc, nrm = front_anchor(surf, b, sx, sz)
        if loc is not None:
            fr = anchor_frame(loc, blend_normal(nrm, (0, 1, 0), 0.5))
            sw, shh = side["w"], side["h"]
            parts.append(decal(surf, "intake", quad_shape((-sw / 2, -shh / 2), (sw / 2, -shh / 2 + 0.01), (sw / 2, shh / 2), (-sw / 2, shh / 2 - 0.01), 0.4),
                               fr, "black", 0.003, 8, 4, mirror=True))
    # 범퍼 아래 검은 립 (순정 언더 스포일러)
    chin = []
    for k in range(13):
        a = -b.W * 0.40 + b.W * 0.80 * k / 12
        chin.append((a, 0))
    loc, nrm = front_anchor(surf, b, 0.0, b.bottom(0.02) + 0.035)
    if loc is not None:
        fr = anchor_frame(loc, (0, 1, -0.4))
        parts.append(decal(surf, "chin", ribbon_shape(chin, 0.05), fr, "black", 0.003, 24, 1))
    return parts


def front_band(surf, b, d):
    """라이트 높이를 가로지르는 검은 띠 (FC-74 같은 디자인)"""
    fb = d.get("frontBand")
    if not fb:
        return []
    z = fb.get("z", b.noseH - 0.06)
    loc, nrm = front_anchor(surf, b, 0.0, z)
    if loc is None:
        return []
    fr = anchor_frame(loc, (0, 1, 0))
    w = fb.get("w", b.W * 0.46)
    pts = [(-w + 2 * w * k / 16, 0) for k in range(17)]
    return [decal(surf, "band", ribbon_shape(pts, fb.get("h", 0.11)), fr, "trim", 0.0025, 32, 2)]


def fin(b, d):
    """지붕 뒤 → 꼬리 상어 지느러미 (프로토타입)"""
    f = d.get("fin")
    if not f:
        return []
    t0, t1 = f.get("t0", b.roofEnd - 0.02), f.get("t1", 0.995)
    pts = [(b.y_of(t0), b.top(t0) - 0.02), (b.y_of(t1), b.top(t1) + f.get("h", 0.22)), (b.y_of(t1) - 0.02, b.top(t1) - 0.02)]
    return [extrude_outline("fin", pts, 0.012, "paint", plane="YZ", origin=(0, 0, 0))]


def wipers(surf, b, d):
    parts = []
    t = b.cowl + 0.012
    y = b.y_of(t)
    for x0 in (-0.05, 0.42):
        pts = [(x0, y + 0.01), (x0 - 0.45, y - 0.02)]
        fr = lambda a, bb: ((a, bb, 3.0), (0, 0, -1))
        parts.append(decal(surf, "wiper", ribbon_shape(pts, 0.012), fr, "black", 0.010, 6, 1))
    return parts


# ---------------------------------------------------------------------------- 뒤

def taillights(surf, b, d):
    tl = d.get("tail", {})
    kind = tl.get("type", 0)
    z = tl.get("z", b.tailH - 0.09)
    parts = []
    if kind == 2:  # 픽셀 띠
        w = tl.get("w", 0.40)
        h = tl.get("h", 0.06)
        x = tl.get("x", b.W / 2 * 0.68)
        cols, rows = tl.get("cols", 5), tl.get("rows", 2)
        loc, nrm = rear_anchor(surf, b, x, z)
        if loc is not None:
            fr = anchor_frame(loc, blend_normal(nrm, (0, -1, 0), 0.5))
            parts.append(decal(surf, "tail_house", quad_shape((-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)), fr, "trim", 0.003, 10, 3, mirror=True))
            cw, ch = w / cols, h / rows
            for i in range(cols):
                for j in range(rows):
                    a0, b0 = -w / 2 + cw * (i + 0.15), -h / 2 + ch * (j + 0.15)
                    parts.append(decal(surf, "tpix", quad_shape((a0, b0), (a0 + cw * 0.7, b0), (a0 + cw * 0.7, b0 + ch * 0.7), (a0, b0 + ch * 0.7)), fr, "tail", 0.006, 2, 2, mirror=True))
        if tl.get("full"):
            loc, nrm = rear_anchor(surf, b, 0.0, z)
            if loc is not None:
                fr = anchor_frame(loc, (0, -1, 0))
                xs = x - w / 2 - 0.01
                n = max(2, int(xs * 2 / 0.05))
                parts.append(decal(surf, "tail_mid_house", quad_shape((-xs, -h * 0.3), (xs, -h * 0.3), (xs, h * 0.3), (-xs, h * 0.3)), fr, "trim", 0.003, 16, 1))
                for i in range(n):
                    a0 = -xs + 2 * xs * (i + 0.2) / n
                    parts.append(decal(surf, "tpix_mid", quad_shape((a0, -h * 0.18), (a0 + 1.2 * xs / n, -h * 0.18), (a0 + 1.2 * xs / n, h * 0.18), (a0, h * 0.18)), fr, "tail", 0.005, 1, 1))
    elif kind == 1:  # 둥근 등 2개씩
        r = tl.get("r", 0.055)
        for k, x in enumerate(tl.get("xs", [b.W / 2 * 0.58, b.W / 2 * 0.80])):
            loc, nrm = rear_anchor(surf, b, x, z)
            if loc is None:
                continue
            fr = anchor_frame(loc, nrm)
            parts.append(decal(surf, "tail_ring", disc_shape(0, 0, r * 1.18), fr, "trim", 0.003, 20, 3, mirror=True, closed_u=True))
            parts.append(decal(surf, "tail", disc_shape(0, 0, r), fr, "tail", 0.005, 20, 3, mirror=True, closed_u=True))
            parts.append(decal(surf, "tail_in", disc_shape(0, 0, r * 0.45), fr, "chrome" if k else "tail", 0.007, 12, 2, mirror=True, closed_u=True))
        if tl.get("bar", True):  # 가운데 검은 가니시
            loc, nrm = rear_anchor(surf, b, 0.0, z)
            if loc is not None:
                fr = anchor_frame(loc, (0, -1, 0))
                xs = tl.get("xs", [b.W / 2 * 0.58])[0] - r * 1.3
                parts.append(decal(surf, "garnish", quad_shape((-xs, -r * 0.8), (xs, -r * 0.8), (xs, r * 0.8), (-xs, r * 0.8), 0.2), fr, "trim", 0.003, 12, 2))
    else:  # 가로 띠 (가운데로 이어질 수 있음)
        w = tl.get("w", 0.40)
        h = tl.get("h", 0.07)
        x = tl.get("x", b.W / 2 * 0.70)
        loc, nrm = rear_anchor(surf, b, x, z)
        if loc is not None:
            n = blend_normal(nrm, (0.5, -0.86, 0), tl.get("wrap", 0.3) * 0.4)
            fr = anchor_frame(loc, n)
            c0, c1, c2, c3 = (-w / 2, -h / 2), (w / 2, -h / 2 + 0.01), (w / 2, h / 2), (-w / 2, h / 2 - tl.get("taper", 0.02))
            parts.append(decal(surf, "tail_house", quad_shape(c0, c1, c2, c3, 0.2), fr, "trim", 0.003, 12, 3, mirror=True))
            sh = lambda p, k: (p[0] * k, p[1] * k)
            parts.append(decal(surf, "tail", quad_shape(sh(c0, 0.92), sh(c1, 0.92), sh(c2, 0.8), sh(c3, 0.8), 0.2), fr, "tail", 0.005, 12, 3, mirror=True))
            parts.append(decal(surf, "tail_line", ribbon_shape([(-w * 0.42, h * 0.2), (w * 0.44, h * 0.2 + 0.006)], 0.008), fr, "drl", 0.007, 8, 1, mirror=True))
        if tl.get("full"):  # 가운데까지 잇는 띠
            loc, nrm = rear_anchor(surf, b, 0.0, z + h * 0.15)
            if loc is not None:
                fr = anchor_frame(loc, (0, -1, 0))
                xs = x - w / 2 + 0.01
                parts.append(decal(surf, "tail_mid", quad_shape((-xs, -0.012), (xs, -0.012), (xs, 0.012), (-xs, 0.012)), fr, "tail", 0.005, 16, 1))
    # 번호판 자리 + 아래 검은 범퍼 + 반사판
    pz = tl.get("plateZ", b.bottom(0.98) + 0.17)
    loc, nrm = rear_anchor(surf, b, 0.0, pz)
    if loc is not None:
        fr = anchor_frame(loc, (0, -1, 0))
        parts.append(decal(surf, "plate_rim", quad_shape((-0.18, -0.065), (0.18, -0.065), (0.18, 0.065), (-0.18, 0.065), 0.15), fr, "trim", 0.003, 6, 2))
        parts.append(decal(surf, "plate", quad_shape((-0.165, -0.055), (0.165, -0.055), (0.165, 0.055), (-0.165, 0.055), 0.1), fr, "plate", 0.005, 6, 2))
    # 뒤 범퍼 아래 검은 부분 (디퓨저 모양) + 반사판
    lowH = tl.get("lowH", 0.13)
    loc, nrm = rear_anchor(surf, b, 0.0, b.bottom(0.99) + lowH / 2 + 0.01)
    if loc is not None:
        fr = anchor_frame(loc, (0, -1, -0.2))
        wl = b.W * 0.44
        parts.append(decal(surf, "rear_low", quad_shape((-wl, -lowH / 2), (wl, -lowH / 2), (wl * 0.94, lowH / 2), (-wl * 0.94, lowH / 2), 0.15),
                           fr, "black", 0.003, 24, 3))
        for k in range(5):
            x = -wl * 0.5 + wl * 0.25 * k
            parts.append(decal(surf, "rear_fin", ribbon_shape([(x, -lowH / 2), (x, lowH * 0.2)], 0.012), fr, "trim", 0.005, 4, 1))
        for sx in (1, -1):
            x = sx * wl * 0.82
            parts.append(decal(surf, "reflector", quad_shape((x - 0.05, lowH * 0.18), (x + 0.05, lowH * 0.18), (x + 0.05, lowH * 0.38), (x - 0.05, lowH * 0.38)), fr, "tail", 0.005, 3, 1))
    # 범퍼 윗선 (틈)
    zb = tl.get("bumperLine", pz + 0.12)
    loc, nrm = rear_anchor(surf, b, 0.0, zb)
    if loc is not None:
        fr = anchor_frame(loc, (0, -1, 0))
        pts = [(-b.W * 0.47 + b.W * 0.94 * k / 16, 0) for k in range(17)]
        parts.append(decal(surf, "bumper_gap", ribbon_shape(pts, 0.005), fr, "black", 0.0015, 32, 1))
    return parts


def exhausts(b, d):
    ex = d.get("exhaust", {})
    if ex is None or ex.get("none"):
        return []
    parts = []
    r = ex.get("r", 0.045)
    z = ex.get("z", b.bottom(0.99) + 0.06)
    y = -b.L / 2 + ex.get("inset", 0.02)
    xs = ex.get("xs", [b.W / 2 * 0.62])
    for x in xs:
        for s in (1, -1) if ex.get("both", True) else (1,):
            parts.append(cylinder("tip", (s * x, y, z), r, 0.16, ex.get("mat", "chrome"), axis="Y", segments=24, cap=False))
            parts.append(cylinder("tip_in", (s * x, y + 0.03, z), r * 0.82, 0.02, "black", axis="Y", segments=20))
    return parts


# ---------------------------------------------------------------------------- 옆

def side_details(surf, b, d):
    parts = []
    sd = d.get("side", {})
    zb = b.beltH - 0.015
    zs = b.o.get("sill", b.sillH) + 0.03
    # 문틈 (앞: A필러 밑, 뒤: 문 끝)
    t_front = sd.get("doorFront", b.glassStart + 0.015)
    t_back = sd.get("doorBack", b.doorEnd + 0.004)
    lines = [t_front, t_back]
    if sd.get("door2"):  # 4도어: 뒷문 끝
        t2 = sd["door2"] if not isinstance(sd["door2"], bool) else b.doorEnd + (b.sideEnd - b.doorEnd) * 0.9
        lines.append(t2)
    for t in lines:
        y = b.y_of(t)
        pts = [(y - 0.01, zb), (y, zs + 0.25), (y - 0.005, zs)]
        parts.append(decal(surf, "gap", ribbon_shape(pts, 0.005), frame_side_fn(b), "black", 0.0015, 8, 1, mirror=True))
    # 손잡이
    if sd.get("handles", True):
        t = t_back - 0.035
        y = b.y_of(t)
        z = zb - 0.075
        parts.append(decal(surf, "handle", quad_shape((y - 0.07, z - 0.012), (y + 0.07, z - 0.012), (y + 0.07, z + 0.012), (y - 0.07, z + 0.012), 0.6),
                           frame_side_fn(b), sd.get("handleMat", "trim"), 0.004, 6, 2, mirror=True))
    # 사이드실 검은 띠
    pts = [(b.y_of(b.tF + 0.07), zs - 0.04), (b.y_of(b.tR - 0.07), zs - 0.04)]
    parts.append(decal(surf, "sill_black", ribbon_shape(pts, 0.05), frame_side_fn(b), "black", 0.002, 16, 1, mirror=True))
    # 펜더 벤트
    if sd.get("vent"):
        v = sd["vent"]
        t = b.tF + (b.R + 0.08) / b.L
        y = b.y_of(t)
        z = v.get("z", b.shoulder(t) - 0.12)
        w, h = v.get("w", 0.16), v.get("h", 0.05)
        parts.append(decal(surf, "vent", quad_shape((y + w / 2, z - h / 2), (y - w / 2, z - h / 2 + 0.02), (y - w / 2 + 0.02, z + h / 2), (y + w / 2, z + h / 2 - 0.01), 0.3),
                           frame_side_fn(b), "black", 0.003, 8, 3, mirror=True))
    return parts


def frame_side_fn(b):
    return lambda a, bb: ((3.0, a, bb), (-1, 0, 0))


def mirrors(surf, b, d):
    m = d.get("mirror", {})
    t = m.get("t", b.glassStart + 0.012)
    y = b.y_of(t)
    z = b.beltH + m.get("dz", 0.05)
    loc, nrm = surf.hit((3.0, y, b.beltH - 0.02), (-1, 0, 0))
    if loc is None:
        return []
    parts = []
    hx = loc.x + m.get("out", 0.09)   # 하우징 가운데 x
    for s in (1, -1):
        # 팔: 차체 표면에서 하우징까지
        arm = box("mirror_arm", (s * (loc.x + (hx - loc.x) / 2 - 0.01), y - 0.02, z - 0.03), (hx - loc.x + 0.02, 0.05, 0.022), "trim")
        # 하우징 (납작한 타원체)
        bm = bmesh.new()
        bmesh.ops.create_uvsphere(bm, u_segments=20, v_segments=12, radius=1.0)
        bmesh.ops.scale(bm, vec=(0.095, 0.06, 0.05), verts=bm.verts)
        # 뒤쪽(거울 면)을 평평하게
        for v in bm.verts:
            if v.co.y < -0.015:
                v.co.y = -0.015 - (v.co.y + 0.015) * 0.15
        bmesh.ops.translate(bm, vec=(s * hx, y - 0.03, z), verts=bm.verts)
        h = new_object("mirror", bm, [m.get("mat", "paint")])
        shade(h, 60)
        glass = box("mirror_glass", (s * hx, y - 0.046, z), (0.16, 0.004, 0.075), "chrome")
        parts += [arm, h, glass]
    return parts


def fuel_cap(surf, b, d):
    t = b.tR - (b.R + 0.14) / b.L
    y = b.y_of(t)
    z = b.shoulder(t) - 0.05
    ring = [(y + 0.07 * math.cos(a * math.pi / 12), z + 0.07 * math.sin(a * math.pi / 12)) for a in range(25)]
    return [decal(surf, "fuel", ribbon_shape(ring, 0.004), frame_side_fn(b), "black", 0.0012, 24, 1)]


def hood_vents(surf, b, d):
    hv = d.get("hoodVent")
    if not hv:
        return []
    parts = []
    t = hv.get("t", (b.tF + b.cowl) / 2)
    y = b.y_of(t)
    for x in hv.get("xs", [0.32]):
        w, h = hv.get("w", 0.22), hv.get("h", 0.28)
        sh = quad_shape((x - w / 2, y - h / 2), (x + w / 2, y - h / 2), (x + w / 2 - 0.03, y + h / 2), (x - w / 2 + 0.03, y + h / 2), 0.2)
        fr = lambda a, bb: ((a, bb, 3.0), (0, 0, -1))
        parts.append(decal(surf, "hood_vent", sh, fr, "black", 0.003, 6, 6, mirror=hv.get("mirror", True)))
        for k in range(4):
            yy = y - h / 2 + h * (k + 0.5) / 4
            parts.append(decal(surf, "vent_slat", ribbon_shape([(x - w * 0.4, yy), (x + w * 0.4, yy)], 0.012), fr, "paint", 0.006, 6, 1, mirror=hv.get("mirror", True)))
    return parts


def hood_lines(surf, b, d):
    """보닛·트렁크 틈"""
    parts = []
    fr = lambda a, bb: ((a, bb, 3.0), (0, 0, -1))
    hx = d.get("hoodLineX", 0.78)
    t0 = d.get("hoodLineFront", 0.03)
    pts = []
    for k in range(9):
        t = lerp(t0, b.cowl - 0.01, k / 8)
        pts.append((b.half_width(t) * hx, b.y_of(t)))
    parts.append(decal(surf, "hood_gap", ribbon_shape(pts, 0.005), fr, "black", 0.0015, 16, 1, mirror=True))
    if d.get("trunkLine", True) and b.deck < 0.97:
        pts = []
        for k in range(7):
            t = lerp(b.deck + 0.005, 0.985, k / 6)
            pts.append((b.half_width(t) * 0.80, b.y_of(t)))
        parts.append(decal(surf, "trunk_gap", ribbon_shape(pts, 0.005), fr, "black", 0.0015, 12, 1, mirror=True))
    return parts
