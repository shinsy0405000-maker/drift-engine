package driftengine.selftest;

import driftengine.math.Vec3;
import driftengine.scene.mesh.MeshData;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.visual.BodyKit;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarModels;
import driftengine.vehicle.visual.CarStyle;

import java.nio.file.Path;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 모델링한 차 파일 (cars/models/*.cmesh.gz): 읽기, 크기, 바퀴 자리, 바디킷, 거울 바퀴 */
final class ModelTests {

    private ModelTests() {
    }

    static void run() {
        test("차 모델 전체: 파일을 읽고, 크기가 차 파일 값과 맞고, 땅 위에 있음", ModelTests::sizes);
        test("차 모델: 바퀴 자리(정지 위치 타이어 안)에 차체가 없음", ModelTests::clearance);
        test("차 모델 바퀴: 반지름·폭이 제원과 같고, 왼쪽은 거울", ModelTests::wheels);
        test("차 모델 바디킷: 단계를 올리면 부품이 붙음, 파일이 없으면 옛 생성기", ModelTests::kits);
        test("차 모델 유리·캘리퍼: 모든 차에 있음, 유리는 차체 안쪽, 캘리퍼는 휠 안, 왼쪽은 거울", ModelTests::glassAndCalipers);
        test("차 모델 재질 이름: Blender 도구(carlib.py)와 엔진이 같은 목록", ModelTests::materialNames);
        test("차 모델 면 방향: 삼각형 감김과 법선이 같은 쪽 (양면 렌더링에서 어둡게 안 보임)", ModelTests::winding);
    }

    /** 삼각형 감김(반시계)으로 구한 면 법선과 정점 법선 평균이 같은 쪽인 비율 */
    static float agreement(MeshData m) {
        int ok = 0;
        int n = 0;
        int[] idx = m.indices();
        for (int i = 0; i < idx.length; i += 3) {
            Vec3 a = m.position(idx[i]);
            Vec3 b = m.position(idx[i + 1]);
            Vec3 c = m.position(idx[i + 2]);
            Vec3 fn = b.sub(a).cross(c.sub(a));
            if (fn.length() < 1e-9f) {
                continue;
            }
            Vec3 vn = m.normal(idx[i]).add(m.normal(idx[i + 1])).add(m.normal(idx[i + 2]));
            n++;
            if (fn.dot(vn) > 0) {
                ok++;
            }
        }
        return n == 0 ? 0 : ok / (float) n;
    }

    private static void winding() {
        for (CarCatalog.Entry e : cars()) {
            CarSpec spec = e.spec();
            MeshData body = CarModels.body(spec.id(), fullKit(e.style()), spec.restHeight());
            if (body == null) {
                continue;
            }
            float r = agreement(body);
            check(r > 0.97f, spec.id() + ": 차체 " + r);
            for (float o : new float[]{1, -1}) {
                float w = agreement(CarModels.wheel(spec.id(), e.style(), true, o));
                check(w > 0.97f, spec.id() + ": 바퀴(" + o + ") " + w);
            }
        }
    }

    private static List<CarCatalog.Entry> cars() {
        return CarCatalog.load(Path.of("cars"));
    }

    private static CarStyle fullKit(CarStyle st) {
        return st.withKit(new BodyKit(BodyKit.MAX[0], BodyKit.MAX[1], BodyKit.MAX[2], BodyKit.MAX[3], BodyKit.MAX[4]));
    }

    private static void sizes() {
        for (CarCatalog.Entry e : cars()) {
            CarSpec spec = e.spec();
            CarStyle st = e.style();
            check(CarModels.available(spec.id()), spec.id() + ": 모델 파일 있음");
            MeshData m = CarModels.body(spec.id(), st.withKit(BodyKit.STOCK), spec.restHeight());
            if (m == null) {
                continue;
            }
            Vec3 lo = m.boundsMin();
            Vec3 hi = m.boundsMax();
            float ground = -spec.restHeight();
            float len = hi.z() - lo.z();
            float wid = hi.x() - lo.x();
            check(Math.abs(len - st.length()) < 0.12f, spec.id() + ": 길이 " + len + " vs " + st.length());
            check(wid > st.width() - 0.06f && wid < st.width() + 0.45f, spec.id() + ": 폭(미러 포함) " + wid + " vs " + st.width());
            check(lo.y() > ground + 0.02f, spec.id() + ": 바닥이 땅 위 (" + (lo.y() - ground) + "m)");
            check(hi.y() - ground > 0.9f && hi.y() - ground < 1.75f, spec.id() + ": 높이 " + (hi.y() - ground));
            check(m.triangleCount() > 10000 && m.triangleCount() < 300000, spec.id() + ": 삼각형 " + m.triangleCount());
        }
    }

    private static void glassAndCalipers() {
        for (CarCatalog.Entry e : cars()) {
            CarSpec spec = e.spec();
            CarStyle st = e.style();
            if (!CarModels.available(spec.id())) {
                continue;
            }
            MeshData body = CarModels.body(spec.id(), st.withKit(BodyKit.STOCK), spec.restHeight());
            MeshData glass = CarModels.glass(spec.id(), st, spec.restHeight());
            check(glass != null && glass.triangleCount() > 100, spec.id() + ": 유리 부품");
            Vec3 lo = glass.boundsMin();
            Vec3 hi = glass.boundsMax();
            Vec3 blo = body.boundsMin();
            Vec3 bhi = body.boundsMax();
            check(lo.x() >= blo.x() - 0.01f && hi.x() <= bhi.x() + 0.01f && lo.z() >= blo.z() - 0.01f && hi.z() <= bhi.z() + 0.01f
                    && hi.y() <= bhi.y() + 0.01f, spec.id() + ": 유리가 차체 상자 안");
            for (boolean front : new boolean[]{true, false}) {
                MeshData r = CarModels.caliper(spec.id(), st, front, 1);
                MeshData l = CarModels.caliper(spec.id(), st, front, -1);
                check(r != null && l != null, spec.id() + ": 캘리퍼 " + (front ? "앞" : "뒤"));
                float reach = Math.max(Math.max(Math.abs(r.boundsMax().y()), Math.abs(r.boundsMin().y())),
                        Math.max(Math.abs(r.boundsMax().z()), Math.abs(r.boundsMin().z())));
                check(reach < spec.wheelRadius() - 0.05f, spec.id() + ": 캘리퍼가 타이어 안쪽 (" + reach + "m)");
                check(r.boundsMax().y() > 0 && r.boundsMin().z() > -0.02f, spec.id() + ": 캘리퍼는 뒤-위쪽");
                check(Math.abs(r.boundsMax().x() + l.boundsMin().x()) < 1e-4f, spec.id() + ": 왼쪽 캘리퍼는 거울");
                check(r.boundsMax().x() < (front ? st.tireWidthF() : st.tireWidthR()) / 2, spec.id() + ": 캘리퍼가 휠 바깥면 안쪽");
            }
        }
    }

    private static void materialNames() {
        Path py = Path.of("tools", "carmodels", "carlib.py");
        if (!java.nio.file.Files.exists(py)) {
            return; // 저장소 밖에서 실행
        }
        java.util.Set<String> names = new java.util.TreeSet<>();
        boolean in = false;
        try {
            for (String line : java.nio.file.Files.readAllLines(py)) {
                if (line.startsWith("MATERIALS = {")) {
                    in = true;
                } else if (in && line.startsWith("}")) {
                    break;
                } else if (in) {
                    java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^\\s*\"([a-z_]+)\":").matcher(line);
                    if (mm.find()) {
                        names.add(mm.group(1));
                    }
                }
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        check(names.size() > 15, "carlib.py 재질 " + names.size() + "개");
        check(names.equals(new java.util.TreeSet<>(CarModels.MATERIALS)),
                "같은 목록: carlib " + names + " / 엔진 " + new java.util.TreeSet<>(CarModels.MATERIALS));
    }

    private static void clearance() {
        for (CarCatalog.Entry e : cars()) {
            CarSpec spec = e.spec();
            CarStyle st = e.style();
            MeshData m = CarModels.body(spec.id(), fullKit(st), spec.restHeight());
            if (m == null) {
                continue;
            }
            int inside = 0;
            for (int w = 0; w < 4; w++) {
                Vec3 c = spec.mount(w).add(new Vec3(0, -(spec.restLength() - spec.staticSag(w)), 0));
                // 모델의 바퀴 중심(땅 + 반지름)과 물리 정지 위치가 같아야 한다
                float modelY = spec.wheelRadius() - spec.restHeight();
                check(Math.abs(c.y() - modelY) < 0.01f, spec.id() + ": 바퀴 높이 물리 " + c.y() + " vs 모델 " + modelY);
                float hw = (CarSpec.isFront(w) ? st.tireWidthF() : st.tireWidthR()) / 2;
                float r = spec.wheelRadius() - 0.01f;
                for (int i = 0; i < m.vertexCount(); i++) {
                    Vec3 p = m.position(i);
                    float dy = p.y() - c.y();
                    float dz = p.z() - c.z();
                    if (Math.abs(p.x() - c.x()) < hw - 0.01f && dy * dy + dz * dz < r * r) {
                        inside++;
                    }
                }
            }
            check(inside == 0, spec.id() + ": 타이어 안 차체 점 " + inside);
        }
    }

    private static void wheels() {
        for (CarCatalog.Entry e : cars()) {
            CarSpec spec = e.spec();
            CarStyle st = e.style();
            for (int w : new int[]{1, 3}) {
                MeshData right = CarModels.wheel(spec.id(), st, CarSpec.isFront(w), 1);
                MeshData left = CarModels.wheel(spec.id(), st, CarSpec.isFront(w), -1);
                if (right == null || left == null) {
                    check(false, spec.id() + ": 바퀴 없음");
                    continue;
                }
                Vec3 lo = right.boundsMin();
                Vec3 hi = right.boundsMax();
                float radius = (hi.y() - lo.y()) / 2;
                float width = hi.x() - lo.x();
                float tire = CarSpec.isFront(w) ? st.tireWidthF() : st.tireWidthR();
                check(Math.abs(radius - spec.wheelRadius()) < 0.01f, spec.id() + ": 바퀴 반지름 " + radius);
                check(width > tire * 0.95f && width < tire * 1.15f, spec.id() + ": 바퀴 폭 " + width + " vs " + tire);
                check(Math.abs(left.boundsMin().x() + hi.x()) < 1e-4f && Math.abs(left.boundsMax().x() + lo.x()) < 1e-4f,
                        spec.id() + ": 왼쪽 바퀴 = 거울");
                // 바깥면(+X)에 휠 면이 있음: 바깥 반쪽 정점이 더 많음 (스포크)
                int out = 0;
                for (int i = 0; i < right.vertexCount(); i++) {
                    out += right.position(i).x() > 0 ? 1 : -1;
                }
                check(out > 0, spec.id() + ": 휠 면이 바깥쪽");
            }
        }
    }

    private static void kits() {
        CarCatalog.Entry e = cars().get(1);
        CarSpec spec = e.spec();
        MeshData stock = CarModels.body(spec.id(), e.style().withKit(BodyKit.STOCK), spec.restHeight());
        MeshData full = CarModels.body(spec.id(), fullKit(e.style()), spec.restHeight());
        check(stock != null && full != null && full.triangleCount() > stock.triangleCount() + 1000,
                "바디킷 다 붙이면 삼각형 증가: " + (stock == null ? 0 : stock.triangleCount()) + " → " + (full == null ? 0 : full.triangleCount()));
        int prev = stock == null ? 0 : stock.triangleCount();
        for (int s = 1; s <= 2; s++) {
            MeshData m = CarModels.body(spec.id(), e.style().withKit(new BodyKit(0, 0, 0, s, 0)), spec.restHeight());
            check(m != null && m.triangleCount() > stock.triangleCount(), "스포일러 " + s + " 단계 붙음");
        }
        try {
            CarModels.setDirectory(Path.of("no-such-model-dir"));
            check(!CarModels.available(spec.id()), "파일이 없으면 모델 없음");
            MeshData fallback = CarModels.bodyOrBuilt(spec, e.style(), spec.restHeight());
            check(fallback.triangleCount() > 1000, "옛 생성기로 대신 (" + fallback.triangleCount() + ")");
        } finally {
            CarModels.setDirectory(Path.of("cars", "models"));
        }
        check(prev > 0, "순정");
    }
}
