package driftengine.selftest;

import driftengine.render.MeshCache;
import driftengine.scene.mesh.MeshData;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarModels;
import driftengine.vehicle.visual.CarStyle;
import driftengine.vehicle.visual.Livery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 메모리: 안 쓰는 GPU 메시 내리기, 차 모델 캐시 한도 (색 미리보기를 많이 넘겨도 계속 늘지 않음) */
final class MemoryTests {

    private MemoryTests() {
    }

    static void run() {
        test("GPU 메시 목록: 한 번만 올리고, 장면에서 빠진 메시는 슬롯 수만큼 지난 뒤 내림", MemoryTests::meshCache);
        test("차 모델 캐시: 색 미리보기 60번 → 바이트 한도 안, 최근 색은 같은 메시 재사용", MemoryTests::liveryPreview);
        test("차 모델 파일: 24대를 다 봐도 최근 몇 대만 메모리에, 다시 읽어도 같은 모양", MemoryTests::partFiles);
        test("스타일링 샵 흉내: 색을 계속 바꿔도 GPU 메시 바이트가 차 2대 분량을 넘지 않음", MemoryTests::shopGpu);
    }

    /** 가짜 GPU 메시: 해제 횟수 기록 */
    private static final class FakeGpu implements AutoCloseable {
        final long bytes;
        int closed;

        FakeGpu(MeshData m) {
            bytes = 4L * (m.vertices().length + m.indices().length);
        }

        @Override
        public void close() {
            closed++;
        }
    }

    private static MeshData tiny(String name) {
        return new MeshData(name, new float[14 * 3], new int[]{0, 1, 2});
    }

    private static void meshCache() {
        List<FakeGpu> made = new ArrayList<>();
        MeshCache<FakeGpu> c = new MeshCache<>(m -> {
            FakeGpu g = new FakeGpu(m);
            made.add(g);
            return g;
        }, g -> g.bytes, 3);
        MeshData a = tiny("a");
        MeshData b = tiny("b");
        FakeGpu ga = c.get(a, 1);
        check(c.get(a, 1) == ga && c.get(a, 2) == ga && made.size() == 1, "같은 메시는 한 번만 올림");
        c.get(b, 2);
        check(c.size() == 2 && c.totalBytes() == 2 * ga.bytes, "바이트 합계");
        // b 는 프레임 2 까지만 장면에 있었다
        for (long f = 3; f <= 4; f++) {
            c.mark(a, f);
            check(c.sweep(f) == 0, "프레임 " + f + ": 아직 GPU 가 쓸 수 있어 남김");
        }
        c.mark(a, 5);
        check(c.sweep(5) == 1 && !c.contains(b) && c.contains(a), "3 프레임 지나면 b 만 내림");
        check(made.get(1).closed == 1 && ga.closed == 0, "해제는 한 번, 쓰는 메시는 그대로");
        check(c.totalBytes() == ga.bytes && c.freedBytes() == ga.bytes, "내린 만큼 합계가 줄어듦");
        // 다시 장면에 들어오면 새로 올린다
        c.get(b, 6);
        check(made.size() == 3 && c.size() == 2, "다시 쓰면 새로 올림");
        c.clear();
        check(c.size() == 0 && c.totalBytes() == 0 && ga.closed == 1, "닫을 때 전부 해제");
    }

    private static List<CarCatalog.Entry> cars() {
        CarModels.setDirectory(Path.of("cars", "models"));
        return CarCatalog.load(Path.of("cars"));
    }

    private static CarStyle paint(CarStyle base, int i) {
        Livery l = base.livery().withPaint(i % Livery.PAINTS.size()).withFinish(i / Livery.PAINTS.size() % Livery.FINISHES.size());
        return base.withLivery(l);
    }

    private static void liveryPreview() {
        CarCatalog.Entry e = cars().get(0);
        CarSpec spec = e.spec();
        MeshData first = null;
        for (int i = 0; i < 60; i++) {
            MeshData m = CarModels.body(spec.id(), paint(e.style(), i), spec.restHeight());
            if (i == 0) {
                first = m;
            }
            check(CarModels.cachedMeshBytes() <= CarModels.MESH_BUDGET, "한도 " + CarModels.cachedMeshBytes());
        }
        check(first != null, "모델 있음");
        MeshData again = CarModels.body(spec.id(), paint(e.style(), 59), spec.restHeight());
        check(again == CarModels.body(spec.id(), paint(e.style(), 59), spec.restHeight()), "최근 색은 같은 메시 (GPU 다시 안 올림)");
        MeshData rebuilt = CarModels.body(spec.id(), paint(e.style(), 0), spec.restHeight());
        check(rebuilt.triangleCount() == first.triangleCount(), "밀려난 색을 다시 고르면 같은 모양으로 다시 만듦");
    }

    private static void partFiles() {
        List<CarCatalog.Entry> all = cars();
        int tris0 = -1;
        for (CarCatalog.Entry e : all) {
            MeshData m = CarModels.body(e.spec().id(), e.style(), e.spec().restHeight());
            if (tris0 < 0) {
                tris0 = m.triangleCount();
            }
            check(CarModels.cachedPartCars() <= CarModels.PART_CARS, "읽어 둔 차 " + CarModels.cachedPartCars());
        }
        // 첫 차는 밀려났다 → 파일에서 다시 읽어도 같은 모양
        CarModels.setDirectory(Path.of("cars", "models"));
        CarCatalog.Entry e0 = all.get(0);
        check(CarModels.body(e0.spec().id(), e0.style(), e0.spec().restHeight()).triangleCount() == tris0, "다시 읽은 모양");
    }

    /** 렌더러처럼: 매 프레임 장면의 메시 표시 → 오래된 것 내리기. 색은 5 프레임마다 바뀜 */
    private static void shopGpu() {
        CarCatalog.Entry e = cars().get(3);
        CarSpec spec = e.spec();
        long[] live = {0};
        MeshCache<AutoCloseable> c = new MeshCache<>(m -> {
            long b = 4L * (m.vertices().length + m.indices().length);
            live[0] += b;
            return () -> live[0] -= b;
        }, g -> 0, 3);
        long one = 0;
        long peak = 0;
        long frame = 0;
        for (int i = 0; i < 40; i++) {
            CarStyle st = paint(e.style(), i);
            List<MeshData> scene = new ArrayList<>();
            scene.add(CarModels.body(spec.id(), st, spec.restHeight()));
            scene.add(CarModels.glass(spec.id(), st, spec.restHeight()));
            for (int w = 0; w < 4; w++) {
                scene.add(CarModels.wheelOrBuilt(spec, st, w));
            }
            scene.removeIf(m -> m == null);
            for (int k = 0; k < 5; k++) {
                frame++;
                for (MeshData m : scene) {
                    c.mark(m, frame);
                }
                c.sweep(frame);
                for (MeshData m : scene) {
                    c.get(m, frame);
                }
                peak = Math.max(peak, live[0]);
            }
            if (i == 0) {
                one = live[0];
            }
        }
        check(one > 0 && peak <= 2 * one, "최대 " + peak / 1e6 + " MB, 차 한 대 " + one / 1e6 + " MB");
    }
}
