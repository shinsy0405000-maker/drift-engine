"""바디킷 부품 (차고에서 단계별로 바꿔 끼움): 앞 립·카나드, 사이드 스커트, 디퓨저, 덕테일, GT 윙, 오버펜더."""

import math
import bmesh
from mathutils import Vector, Matrix

from carlib import new_object, shade, box, join, lerp, extrude_outline, cylinder
from body import arch_radius


def sweep(name, rails, mat, closed=False):
    """rails[k] = 단면 점 목록 (같은 개수). 단면끼리 이어 붙인 면 (끝은 막음)."""
    bm = bmesh.new()
    rows = [[bm.verts.new(p) for p in sec] for sec in rails]
    n = len(rails[0])
    for i in range(len(rows) - 1):
        for j in range(n if closed else n - 1):
            q = [rows[i][j], rows[i][(j + 1) % n], rows[i + 1][(j + 1) % n], rows[i + 1][j]]
            try:
                bm.faces.new(q)
            except ValueError:
                pass
    for r in (rows[0], rows[-1]):
        if n >= 3:
            try:
                bm.faces.new(r)
            except ValueError:
                pass
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    o = new_object(name, bm, [mat])
    shade(o, 35)
    return o


def bumper_line(surf, b, z, count=21, span=0.47, front=True):
    """앞(또는 뒤) 범퍼 높이 z 에서 x 를 바꿔 가며 표면 점과 수평 법선"""
    pts = []
    for k in range(count):
        x = -b.W * span + 2 * b.W * span * k / (count - 1)
        if front:
            loc, nrm = surf.hit((x, b.L / 2 + 1, z), (0, -1, 0))
        else:
            loc, nrm = surf.hit((x, -b.L / 2 - 1, z), (0, 1, 0))
        if loc is None:
            continue
        hn = Vector((nrm.x, nrm.y, 0))
        if hn.length < 1e-3:
            hn = Vector((0, 1 if front else -1, 0))
        pts.append((loc, hn.normalized()))
    return pts


def front_lip(surf, b, d, aero):
    z = b.bottom(0.03) + 0.012
    line = bumper_line(surf, b, z + 0.02)
    out = 0.05 if not aero else 0.075
    rails = []
    for (p, n) in line:
        base = Vector((p.x, p.y, z))
        inner = base - n * 0.12
        outer = base + n * out
        th = 0.016
        rails.append([tuple(inner + Vector((0, 0, th))), tuple(outer + Vector((0, 0, th * 0.5))),
                      tuple(outer - Vector((0, 0, th * 0.5))), tuple(inner - Vector((0, 0, th * 0.2)))])
    parts = [sweep("lip", rails, "carbon", closed=True)]
    if aero:
        # 카나드: 범퍼 모서리에 비스듬한 판 두 장씩
        for (p, n) in (line[1], line[-2]):
            s = 1 if p.x > 0 else -1
            for k, dz in enumerate((0.10, 0.17)):
                c = Vector((p.x, p.y, z + dz)) + n * 0.02
                rot = Matrix.Rotation(s * math.radians(-12 + 6 * k), 3, "Z") @ Matrix.Rotation(math.radians(-8), 3, "Y")
                parts.append(box("canard", c, (0.14, 0.09, 0.008), "carbon", rot))
        # 더 큰 스플리터 받침
        for x in (-0.35, 0.35):
            parts.append(box("splitter_rod", (x, b.L / 2 - 0.2, z + 0.06), (0.012, 0.012, 0.12), "black"))
    return join(parts, "kit.front.%d" % (2 if aero else 1))


def side_skirt(surf, b, d):
    parts = []
    y0 = b.yF - arch_radius(b) - 0.02
    y1 = b.yR + arch_radius(b) + 0.02
    zs = b.bottom(0.5)
    for s in (1, -1):
        rails = []
        for k in range(16):
            y = lerp(y0, y1, k / 15)
            loc, nrm = surf.hit((3.0, y, zs + 0.05), (-1, 0, 0))
            x = loc.x if loc is not None else b.half_width(b.t_of(y))
            rails.append([(s * (x - 0.03), y, zs + 0.10), (s * (x + 0.035), y, zs + 0.07),
                          (s * (x + 0.045), y, zs - 0.01), (s * (x - 0.05), y, zs - 0.02)])
        parts.append(sweep("skirt", rails, "black", closed=True))
    return join(parts, "kit.side.1")


def diffuser(surf, b, d):
    parts = []
    z0 = b.bottom(0.96) - 0.005
    yb = -b.L / 2 + 0.015
    w = b.W * 0.36
    # 비스듬한 판 (뒤가 올라감)
    rails = []
    for k in range(9):
        x = -w + 2 * w * k / 8
        rails.append([(x, yb + 0.42, z0 - 0.02), (x, yb, z0 + 0.06), (x, yb, z0 + 0.075), (x, yb + 0.42, z0 - 0.005)])
    parts.append(sweep("diff", rails, "carbon", closed=True))
    for k in range(5):
        x = -w * 0.8 + 1.6 * w * k / 4
        fin = [(0.0, 0.0), (0.42, 0.0), (0.42, -0.02), (0.0, -0.10)]
        parts.append(extrude_outline("fin", [(-a, bb) for (a, bb) in fin], 0.008, "carbon", plane="YZ",
                                     origin=(x, yb + 0.42, z0 + 0.06)))
    return join(parts, "kit.rear.1")


def deck_point(surf, b, x, t):
    loc, nrm = surf.hit((x, b.y_of(t), 3.0), (0, 0, -1))
    return loc


def ducktail(surf, b, d):
    t = d.get("ducktailT", min(0.975, b.deck + (1 - b.deck) * 0.8))
    rails = []
    span = 0.43 * b.W
    for k in range(17):
        x = -span + 2 * span * k / 16
        p = deck_point(surf, b, x, t)
        q = deck_point(surf, b, x, t - 0.05)
        if p is None or q is None:
            continue
        rise = 0.05 * (1 - (abs(x) / span) ** 4)
        back = Vector((0, -0.05, 0))
        rails.append([tuple(q + Vector((0, 0, -0.004))), tuple(p + back + Vector((0, 0, rise))),
                      tuple(p + back + Vector((0, 0, rise - 0.012))), tuple(p + Vector((0, 0.02, -0.01)))])
    return join([sweep("ducktail", rails, "paint", closed=True)], "kit.spoiler.1")


def airfoil(chord, thick, n=10):
    up, lo = [], []
    for i in range(n + 1):
        x = i / n
        yt = 5 * thick * (0.2969 * math.sqrt(x) - 0.126 * x - 0.3516 * x * x + 0.2843 * x ** 3 - 0.1036 * x ** 4)
        camber = 0.04 * (2 * 0.4 * x - x * x) / 0.16 if x < 0.4 else 0.04 * ((1 - 0.8) + 0.8 * x - x * x) / 0.36
        up.append((x * chord, (camber + yt) * chord))
        lo.append((x * chord, (camber - yt) * chord))
    return up + list(reversed(lo[1:-1]))


def gt_wing(surf, b, d):
    w = d.get("wing", {})
    t = w.get("t", min(0.96, b.deck + (1 - b.deck) * 0.55))
    height = w.get("height", 0.26)
    span = w.get("span", 0.46 * b.W)
    chord = w.get("chord", 0.28)
    base = deck_point(surf, b, 0.0, t)
    if base is None:
        base = Vector((0, b.y_of(t), b.deckH))
    zt = base.z + height
    yc = base.y
    # 날개: 받음각 -10도 (뒤가 올라감 → 아래로 누름)
    prof = airfoil(chord, 0.12)
    ang = math.radians(w.get("angle", 10))
    sec = []
    for (cx, cy) in prof:
        yy = yc + chord * 0.4 - cx * math.cos(ang) - cy * math.sin(ang) * 0
        zz = zt + cy + cx * math.sin(ang)
        sec.append((yy, zz))
    rails = [[(x, yy, zz) for (yy, zz) in sec] for x in (-span, -span * 0.5, 0.0, span * 0.5, span)]
    parts = [sweep("wing", rails, "carbon", closed=True)]
    # 끝판
    for s in (1, -1):
        plate = [(yc + chord * 0.55, zt - 0.10), (yc - chord * 0.75, zt - 0.06), (yc - chord * 0.75, zt + 0.10),
                 (yc + chord * 0.35, zt + 0.07)]
        parts.append(extrude_outline("endplate", plate, 0.008, "carbon", plane="YZ", origin=(s * (span + 0.004), 0, 0)))
        # 받침대
        sx = s * w.get("standX", span * 0.55)
        p = deck_point(surf, b, sx, t)
        zb = p.z if p is not None else base.z
        sh = zt - zb
        parts.append(box("stand", (sx, yc - 0.02, zb + sh / 2), (0.012, 0.13, sh), "black"))
    return join(parts, "kit.spoiler.2")


def overfenders(surf, b, d):
    """와이드 펜더: 아치를 따라 끊김 없이 이어진 플레어.
    단면 = 차체에 붙는 윗부분 → 바깥으로 불룩 → 타이어 바깥에 걸친 립 → 안쪽으로 접혀 차체에 닿는 아랫면.
    차체가 바퀴보다 안쪽으로 들어간 높이(펜더 위쪽)에서도 끊지 않고, 그만큼 넓은 판으로 메운다."""
    parts = []
    ar = arch_radius(b)
    for (y, x, tire) in ((b.yF, b.xF, b.tireF), (b.yR, b.xR, b.tireR)):
        x_tire = x + tire / 2
        # 립 위치: 아치 전체에서 차체 바깥면 + 4.5cm 중 가장 바깥 (한 줄로 곧게, 타이어보다 바깥)
        lip_x = x_tire + 0.035
        samples = []
        for k in range(25):
            a = math.radians(lerp(-10, 190, k / 24))
            ca, sa = math.cos(a), math.sin(a)
            yy = y + ca * (ar + 0.02)
            zz = b.R + sa * (ar + 0.02)
            if zz < b.bottom(b.t_of(yy)) + 0.015:
                samples.append(None)
                continue
            loc, _ = surf.hit((3.0, yy, zz), (-1, 0, 0))
            body_lip = loc.x if loc is not None and loc.x > x - 0.2 else None
            # 차체에 붙는 높이: 아치에서 8~19cm 바깥, 차체가 없으면(보닛 위 등) 안쪽으로 줄여 가며 찾음
            attach = None
            for dr in (0.19, 0.16, 0.13, 0.10, 0.08):
                ya = y + ca * (ar + dr)
                za = b.R + sa * (ar + dr)
                la, _ = surf.hit((3.0, ya, za), (-1, 0, 0))
                # 바퀴 바로 바깥 펜더 면이어야 함 (더 안쪽이면 보닛·지붕 위를 맞힌 것 → 너무 높음)
                if la is not None and la.x > x_tire - 0.09:
                    attach = (dr, la.x)
                    break
            samples.append((ca, sa, body_lip, attach))
            if body_lip is not None:
                lip_x = max(lip_x, body_lip + 0.045)
        rails_all = []
        for smp in samples:
            if smp is None:
                rails_all.append(None)
                continue
            ca, sa, body_lip, attach = smp
            dr_a, bx_a = attach if attach is not None else (0.06, (body_lip if body_lip is not None else lip_x - 0.06))
            bx_l = body_lip if body_lip is not None else bx_a
            bx_a = min(bx_a, lip_x - 0.01)
            bx_l = min(bx_l, lip_x - 0.02)
            rails_all.append((ca, sa, [
                (bx_a - 0.006, dr_a),                                   # 차체에 붙는 윗끝 (살짝 파묻음)
                (lerp(bx_a, lip_x, 0.55) + 0.006, dr_a * 0.62 + 0.02),  # 불룩한 면
                (lip_x + 0.010, 0.055),
                (lip_x, 0.012),                                         # 립 끝
                (lip_x - 0.028, 0.002),                                 # 접혀 들어감
                (bx_l - 0.008, 0.0),                                    # 아랫면이 차체에 닿음
            ]))
        for side in (1, -1):
            runs, cur = [], []
            for r in rails_all + [None]:
                if r is None:
                    if len(cur) > 2:
                        runs.append(cur)
                    cur = []
                    continue
                ca, sa, prof = r
                cur.append([(side * px, y + ca * (ar + dr), b.R + sa * (ar + dr)) for (px, dr) in prof])
            for run in runs:
                parts.append(sweep("flare", run, "paint"))
                # 리벳: 불룩한 면 위
                for k in range(2, len(run) - 2, 3):
                    p = Vector(run[k][1])
                    parts.append(cylinder("rivet", tuple(p + Vector((side * 0.004, 0, 0))), 0.007, 0.01, "chrome", segments=8))
    return join(parts, "kit.wide.1")


def build_kits(surf, b, d):
    out = {
        "kit.front.1": front_lip(surf, b, d, False),
        "kit.front.2": front_lip(surf, b, d, True),
        "kit.side.1": side_skirt(surf, b, d),
        "kit.rear.1": diffuser(surf, b, d),
        "kit.spoiler.1": ducktail(surf, b, d),
        "kit.spoiler.2": gt_wing(surf, b, d),
        "kit.wide.1": overfenders(surf, b, d),
    }
    return {k: v for k, v in out.items() if v is not None}
