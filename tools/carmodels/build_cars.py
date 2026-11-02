"""
9대 차 모델 만들기: Blender(bpy) 로 실행.
  python build_cars.py   (Blender 의 bpy 모듈이 설치된 파이썬) [차 id ...] [--preview] [--out 폴더]
결과: <out>/<차 id>.cmesh.gz (게임이 읽음), --preview 면 렌더 사진도.
"""
import sys, json, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bpy
from mathutils import Vector

import carlib, body, wheel, details, kits, interior
from designs import DESIGNS

HERE = os.path.dirname(os.path.abspath(__file__))


def build(car_id, dims, design, preview_dir=None, out_dir=None):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    carlib.set_paint(design.get("color", (0.6, 0.6, 0.6)), design.get("metallic", True))
    over = design.get("body", {})
    shell, b = body.build_body(dims, over)
    surf = carlib.Surface(shell)
    parts = [shell] + body.wheel_tubs(b)
    parts += details.front_band(surf, b, design)
    parts += details.headlights(surf, b, design)
    parts += details.fin(b, design)
    parts += details.front_intakes(surf, b, design)
    parts += details.wipers(surf, b, design)
    parts += details.taillights(surf, b, design)
    parts += details.exhausts(b, design)
    parts += details.side_details(surf, b, design)
    parts += details.mirrors(surf, b, design)
    parts += details.hood_vents(surf, b, design)
    parts += details.hood_lines(surf, b, design)
    if design.get("fuelCap", True):
        parts += details.fuel_cap(surf, b, design)
    parts += interior.build_interior(b, design)
    body_obj = carlib.join(parts, "body")
    glass_obj = carlib.split_materials(body_obj, ("glass", "lens"), "glass")
    kit_objs = kits.build_kits(surf, b, design)
    st = dims["style"]
    wf = wheel.build_wheel("wheel.front", b.R, st["tireWidthF"], st["rim"], design.get("rimInch"))
    wr = wheel.build_wheel("wheel.rear", b.R, st["tireWidthR"], st["rim"], design.get("rimInch"))
    cal = design.get("caliper", "caliper")
    cf = wheel.caliper(b.R, st["tireWidthF"], design.get("rimInch"), color=cal)
    cr = wheel.caliper(b.R, st["tireWidthR"], design.get("rimInch"), color=cal)
    result = {"body": body_obj}
    if glass_obj is not None:
        result["glass"] = glass_obj
    result.update(kit_objs)
    if out_dir:
        tris = carlib.export_parts(os.path.join(out_dir, car_id + ".cmesh.gz"), result,
                                   {"wheel.front": wf, "wheel.rear": wr, "caliper.front": cf, "caliper.rear": cr})
        print("%s: 삼각형 %d" % (car_id, tris))
    if preview_dir:
        # 미리보기: 바퀴 4개 배치 + 기본 바디킷
        shown = [body_obj] + ([glass_obj] if glass_obj is not None else [])
        kit = design.get("previewKit", design.get("kit", (0, 0, 0, 0, 0)))
        if os.environ.get("PREVIEW_KIT"):  # 예: PREVIEW_KIT=0,0,0,0,1 (바디킷 모습 확인용)
            kit = tuple(int(v) for v in os.environ["PREVIEW_KIT"].split(","))
        names = ["kit.front.%d" % kit[0], "kit.side.%d" % kit[1], "kit.rear.%d" % kit[2], "kit.spoiler.%d" % kit[3], "kit.wide.%d" % kit[4]]
        shown += [kit_objs[n] for n in names if n in kit_objs]
        for (src, y, x) in ((wf, b.yF, b.xF), (wr, b.yR, b.xR), (cf, b.yF, b.xF), (cr, b.yR, b.xR)):
            for s in (1, -1):
                c = src.copy()
                c.data = src.data
                bpy.context.scene.collection.objects.link(c)
                c.location = (s * x, y, b.R)
                if s < 0:
                    c.scale = (-1, 1, 1)
                shown.append(c)
        L = b.L
        views = [((L * 1.05, L * 1.2, 1.5), (0, 0.25, 0.55)), ((-L * 1.1, -L * 1.15, 1.6), (0, -0.2, 0.5)),
                 ((L * 1.75, 0, 0.75), (0, 0, 0.55)), ((0.6, L * 1.45, 0.9), (0, 0, 0.5))]
        carlib.preview(os.path.join(preview_dir, car_id + ".png"), shown, views, size=(800, 450), samples=20)


if __name__ == "__main__":
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
    preview = None
    out = None
    ids = []
    i = 0
    while i < len(args):
        if args[i] == "--preview":
            preview = args[i + 1]
            i += 2
        elif args[i] == "--out":
            out = args[i + 1]
            i += 2
        else:
            ids.append(args[i])
            i += 1
    dims = json.load(open(os.path.join(HERE, "dims.json")))
    for cid in ids or list(dims.keys()):
        build(cid, dims[cid], DESIGNS.get(cid, {}), preview, out)
