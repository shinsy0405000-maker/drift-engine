package driftengine.selftest;

import driftengine.math.Vec3;
import driftengine.scene.mesh.MeshData;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.visual.BodyKit;
import driftengine.vehicle.visual.CarBodyBuilder;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarStyle;
import driftengine.vehicle.visual.WheelBuilder;

import java.nio.file.Path;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 차 모델 생성기: 메시가 올바른지 (면 방향, 크기, 바퀴와 겹침) — 화면 없이 검사할 수 있는 부분 */
final class VisualTests {

    private VisualTests() {
    }

    static void run() {
        test("차체(cars 폴더 전체): 면이 바깥을 향함, 크기가 제원 안, 땅 아래로 안 내려감", VisualTests::bodies);
        test("차체: 바퀴(타이어 원기둥) 안으로 차체가 들어가지 않음", VisualTests::wheelClearance);
        test("바디킷: 부품을 달면 삼각형이 늘고, 와이드 펜더는 폭이 넓어짐, 범위 밖 값은 오류", VisualTests::kits);
        test("바퀴: 면이 바깥을 향함, 지름·폭이 맞음, 왼쪽은 거울", VisualTests::wheels);
    }

    private static List<CarCatalog.Entry> cars() {
        return CarCatalog.load(Path.of("cars"));
    }

    /** 삼각형 면 법선과 정점 법선 평균이 같은 쪽인 비율 */
    static float outwardRatio(MeshData m) {
        int good = 0;
        int total = 0;
        int[] idx = indices(m);
        for (int t = 0; t < idx.length; t += 3) {
            Vec3 a = m.position(idx[t]);
            Vec3 b = m.position(idx[t + 1]);
            Vec3 c = m.position(idx[t + 2]);
            Vec3 fn = b.sub(a).cross(c.sub(a));
            if (fn.length() < 1e-10f) {
                continue;
            }
            Vec3 vn = m.normal(idx[t]).add(m.normal(idx[t + 1])).add(m.normal(idx[t + 2]));
            total++;
            if (fn.dot(vn) > 0) {
                good++;
            }
        }
        return total == 0 ? 0 : good / (float) total;
    }

    static int[] indices(MeshData m) {
        int[] out = new int[m.indexCount()];
        java.nio.IntBuffer buf = java.nio.IntBuffer.wrap(out);
        m.writeIndices(buf);
        return out;
    }

    private static void bodies() {
        List<CarCatalog.Entry> cars = cars();
        check(cars.size() >= 9, "차 " + cars.size() + "대");
        for (CarCatalog.Entry e : cars) {
            CarSpec spec = e.spec();
            CarStyle st = e.style();
            MeshData m = CarBodyBuilder.build(spec, st);
            float ratio = outwardRatio(m);
            // 바퀴 아치 안쪽(밀어 올린 점들 사이)은 접힌 삼각형이 조금 생긴다. 렌더러는 양면으로 그리므로 구멍은 안 생김
            check(ratio > 0.98f, spec.name() + ": 바깥 방향 면 비율 " + ratio);
            check(m.triangleCount() > 3000 && m.triangleCount() < 40000, spec.name() + ": 삼각형 " + m.triangleCount());
            Vec3 lo = m.boundsMin();
            Vec3 hi = m.boundsMax();
            float ground = -spec.restHeight();
            check(lo.y() > ground - 0.005f, spec.name() + ": 가장 낮은 점이 땅 위 (" + (lo.y() - ground) + "m)");
            check(hi.x() - lo.x() < st.width() + 0.3f, spec.name() + ": 폭 " + (hi.x() - lo.x()));
            check(hi.z() - lo.z() < st.length() + 0.15f && hi.z() - lo.z() > st.length() - 0.05f,
                    spec.name() + ": 길이 " + (hi.z() - lo.z()) + " vs " + st.length());
            check(hi.y() - ground > st.roofH() - 0.05f, spec.name() + ": 지붕 높이 " + (hi.y() - ground));
        }
    }

    private static void wheelClearance() {
        for (CarCatalog.Entry e : cars()) {
            CarSpec spec = e.spec();
            CarStyle st = e.style();
            MeshData m = CarBodyBuilder.build(spec, st);
            int inside = 0;
            for (int w = 0; w < 4; w++) {
                Vec3 c = spec.mount(w).add(new Vec3(0, -(spec.restLength() - spec.staticSag(w)), 0));
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
            check(inside == 0, spec.name() + ": 바퀴 안에 들어간 차체 점 " + inside + "개");
        }
    }

    private static void kits() {
        CarCatalog.Entry e = cars().get(0);
        CarStyle stock = e.style().withKit(BodyKit.STOCK);
        CarStyle full = e.style().withKit(new BodyKit(2, 1, 1, 2, 1));
        MeshData a = CarBodyBuilder.build(e.spec(), stock);
        MeshData b = CarBodyBuilder.build(e.spec(), full);
        check(b.triangleCount() > a.triangleCount() + 50, "부품 삼각형 " + a.triangleCount() + " → " + b.triangleCount());
        float wa = a.boundsMax().x();
        float wb = b.boundsMax().x();
        check(wb > wa, "와이드 펜더: " + wa + " → " + wb);
        check(outwardRatio(b) > 0.98f, "부품 면 방향 " + outwardRatio(b));
        TestRunner.expectThrows(IllegalArgumentException.class, () -> new BodyKit(3, 0, 0, 0, 0), "앞범퍼 3 은 없음");
        TestRunner.expectThrows(IllegalArgumentException.class, () -> BodyKit.parse("1,1"), "숫자 5개가 아니면 오류");
        check(BodyKit.parse(" 1, 0,1,2 ,1").equals(new BodyKit(1, 0, 1, 2, 1)), "문자열 읽기");
    }

    private static void wheels() {
        for (int style = 0; style <= 4; style++) {
            MeshData right = WheelBuilder.build(0.33f, 0.25f, style, new Vec3(0.7f, 0.7f, 0.7f), 1);
            MeshData left = WheelBuilder.build(0.33f, 0.25f, style, new Vec3(0.7f, 0.7f, 0.7f), -1);
            check(outwardRatio(right) > 0.99f && outwardRatio(left) > 0.99f,
                    "휠 " + style + ": 면 방향 " + outwardRatio(right) + " / " + outwardRatio(left));
            Vec3 hi = right.boundsMax();
            Vec3 lo = right.boundsMin();
            check(Math.abs(hi.y() - 0.33f) < 0.01f && Math.abs(lo.z() + 0.33f) < 0.01f, "휠 " + style + ": 반지름 " + hi.y());
            check(hi.x() - lo.x() < 0.27f && hi.x() - lo.x() > 0.22f, "휠 " + style + ": 폭 " + (hi.x() - lo.x()));
            check(Math.abs(right.boundsMax().x() + left.boundsMin().x()) < 1e-4f, "휠 " + style + ": 왼쪽은 오른쪽 거울");
        }
    }
}
