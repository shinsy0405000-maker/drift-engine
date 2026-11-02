package driftengine.selftest;

import driftengine.game.Club;
import driftengine.game.ClubEvent;
import driftengine.game.PerformanceRating;
import driftengine.scene.mesh.MeshData;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.world.LapTimer;
import driftengine.world.map.City;
import driftengine.world.map.Course;
import driftengine.world.map.Road;
import driftengine.world.map.RoadNet;
import driftengine.world.map.RoadPath;
import driftengine.world.map.Zone;
import driftengine.world.map.ZoneMap;
import driftengine.world.map.Zones;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 맵 덩어리 C: 도로 곡선, 지형 새기기, 지역(서킷·고갯길·도시), 코스 문, 컴퓨터 운전자 한 바퀴 */
final class MapTests {

    private MapTests() {
    }

    private static final Map<Zone, ZoneMap> MAPS = new EnumMap<>(Zone.class);

    static ZoneMap map(Zone z) {
        return MAPS.computeIfAbsent(z, Zones::build);
    }

    static void run() {
        test("도로 곡선: 원 둘레·굽음 부호(왼쪽 +)·간격 고름·호 길이 보간", MapTests::path);
        test("가까운 도로: 거리·옆(왼쪽 +)·높이", MapTests::nearest);
        test("코스 문: 앞으로 넘을 때만, 폭 밖은 무시 / 랩 기록: 순서대로만, 한 바퀴·편도", MapTests::gates);
        test("서킷: 3.4~3.8km, 굽음 반지름 25m 이상, 경사 6% 이하, 도로 위 지형 = 도로 높이, 가로 평평", () -> roadZone(Zone.CIRCUIT, 25, 0.06f));
        test("고갯길: 2.8~3.4km, 헤어핀 반지름 10m 이상, 경사 8% 이하, 정상 > 아래 200m", () -> roadZone(Zone.PASS, 10, 0.08f));
        test("도로 면 메시: 정점이 지형 바로 위(3cm), 지형은 도로 아래 칸만 숨김", MapTests::ribbon);
        test("벽·가드레일: 도로 포장 위에 걸친 상자 없음, 물리 몸체 한도 안", MapTests::barriers);
        test("도시: 도로 위에 건물·가로등 상자 없음, 출발점은 오른쪽 차로", MapTests::city);
        test("도시 광장: 160m 안에 상자·도로 없음, 가로등은 도로에서 3m 넘게, 이벤트 출발은 광장 안", MapTests::plaza);
        test("지형 조각: 듬성 조각 합 20만 삼각형 아래, 자세한 조각 하나 1만 아래, 고정 메시 합 25만 아래", MapTests::budgets);
        test("이벤트 코스 이름 → 지역, 모든 클럽 이벤트의 코스가 있음", MapTests::tracks);
        test("컴퓨터 운전자: 서킷을 드리프트 차·하이퍼카로 한 바퀴 (도로 밖 9m 안)", MapTests::lapBot);
        test("랩 목표: 클럽 클래스 이하 순정 차 가장 빠른 랩 ≤ 보스 목표 × 1.02, 첫 이벤트는 그보다 여유", MapTests::lapTargets);
        test("고갯길: 컴퓨터 운전자가 클래스 2 차로 결승선까지", MapTests::passBot);
        test("클래스 분포: 1~4 클래스에 각각 4대 이상 (딜러쉽 판매)", MapTests::classSpread);
    }

    private static void path() {
        float[][] pts = new float[24][];
        for (int i = 0; i < 24; i++) {
            double a = 2 * Math.PI * i / 24;
            // 위에서 볼 때 반시계: x = cos, z = −sin → 진행 왼쪽이 원 안
            pts[i] = new float[]{(float) Math.cos(a) * 50, (float) -Math.sin(a) * 50};
        }
        RoadPath p = RoadPath.through(pts, true, 1f);
        check(Math.abs(p.length() - 2 * Math.PI * 50) < 1.5f, "둘레 " + p.length());
        float kMean = 0;
        for (int i = 0; i < p.count(); i++) {
            kMean += p.curvature(i);
        }
        kMean /= p.count();
        check(Math.abs(kMean - 1 / 50f) < 0.002f, "굽음 평균 " + kMean + " (왼쪽 +)");
        float maxStep = 0, minStep = 9;
        for (int i = 0; i < p.count(); i++) {
            int j = p.next(i);
            float d = (float) Math.hypot(p.x(j) - p.x(i), p.z(j) - p.z(i));
            maxStep = Math.max(maxStep, d);
            minStep = Math.min(minStep, d);
        }
        check(maxStep < 1.1f && minStep > 0.9f, "간격 " + minStep + " ~ " + maxStep);
        float[] a = p.at(p.length() + 10);
        float[] b = p.at(10);
        check(Math.abs(a[0] - b[0]) < 1e-3f && Math.abs(a[1] - b[1]) < 1e-3f, "닫힌 길은 감김");
        check(Math.abs(Math.hypot(a[2], a[3]) - 1) < 1e-4f, "방향 단위 벡터");
    }

    private static void nearest() {
        RoadPath p = RoadPath.through(new float[][]{{0, 0}, {0, -100}, {0, -200}}, false, 1f).heights(s -> 10 + s * 0.05);
        RoadNet net = new RoadNet(List.of(new Road("t", p, 4, Road.Style.MOUNTAIN, false, 0, 10)));
        // 진행 방향 −z (차 앞), 왼쪽 = −x
        RoadNet.Hit h = net.nearest(-3, -50, 20);
        check(h != null && Math.abs(h.distance() - 3) < 0.01f, "거리 " + (h == null ? -1 : h.distance()));
        check(h != null && h.lateral() > 0, "왼쪽은 + (" + (h == null ? 0 : h.lateral()) + ")");
        check(h != null && Math.abs(h.s() - 50) < 0.6f && Math.abs(h.y() - 12.5f) < 0.05f, "s·높이");
        check(net.nearest(30, -50, 20) == null, "멀면 없음");
    }

    private static void gates() {
        Course.Gate g = new Course.Gate(0, 0, 0, -1, 5, 0);
        check(g.crossed(0, 1, 0, -1), "앞으로 넘음");
        check(!g.crossed(0, -1, 0, 1), "거꾸로는 아님");
        check(!g.crossed(8, 1, 8, -1), "폭 밖은 아님");
        // 원 코스 한 바퀴
        float[][] pts = new float[16][];
        for (int i = 0; i < 16; i++) {
            double a = 2 * Math.PI * i / 16;
            pts[i] = new float[]{(float) Math.cos(a) * 80, (float) -Math.sin(a) * 80};
        }
        RoadPath p = RoadPath.through(pts, true, 1f);
        Road road = new Road("c", p, 6, Road.Style.CIRCUIT, false, 0, 10);
        Course c = Course.on("c", road, true, 10, 0, 100, 2);
        LapTimer t = new LapTimer(c);
        float dt = 0.05f;
        int laps = 0;
        float last = -1;
        float s = 0;
        float[] prev = p.at(s);
        for (int k = 0; k < 4000 && laps < 2; k++) {
            s += 20 * dt;
            float[] now = p.at(s);
            if (t.update(dt, prev[0], prev[1], now[0], now[1])) {
                laps++;
                last = t.last();
            }
            prev = now;
        }
        float expect = p.length() / 20;
        check(laps == 2 && Math.abs(last - expect) < 0.2f, "한 바퀴 " + last + " s (예상 " + expect + ")");
        // 문 하나를 건너뛰면 (지름길) 바퀴가 안 끝남
        LapTimer cheat = new LapTimer(c);
        prev = p.at(0);
        boolean done = false;
        for (float u = 0.5f; u < p.length() * 1.5f; u += 0.5f) {
            float[] now = p.at(u);
            float px = prev[0], pz = prev[1];
            if (u > 150 && u < 260) { // 이 구간은 도로 밖으로 (문 폭 밖)
                now = new float[]{now[0] * 1.5f, now[1] * 1.5f, now[2], now[3]};
            }
            done |= cheat.update(0.025f, px, pz, now[0], now[1]);
            prev = now;
        }
        check(!done, "문을 건너뛰면 바퀴 인정 안 함");
        // 편도: 마지막 문에서 끝
        RoadPath line = RoadPath.through(new float[][]{{0, 0}, {0, -500}}, false, 1f);
        Course oneWay = Course.on("o", new Road("o", line, 4, Road.Style.MOUNTAIN, false, 0, 10), false, 20, 480, 100, 3);
        LapTimer o = new LapTimer(oneWay);
        boolean fin = false;
        for (float z = 0; z > -500 && !fin; z -= 1) {
            fin = o.update(0.05f, 0, z, 0, z - 1);
        }
        check(fin && Math.abs(o.last() - 460 * 0.05f) < 0.2f && o.finished(), "편도 기록 " + o.last());
    }

    private static void roadZone(Zone z, float minRadius, float maxGrade) {
        ZoneMap m = map(z);
        Road road = m.roads.roads().get(0);
        RoadPath p = road.path();
        float lo = z == Zone.CIRCUIT ? 3400 : 2800, hi = z == Zone.CIRCUIT ? 3800 : 3400;
        check(p.length() > lo && p.length() < hi, "길이 " + p.length());
        check(p.minRadius() >= minRadius, "가장 작은 반지름 " + p.minRadius());
        check(p.maxGrade() <= maxGrade, "가장 급한 경사 " + p.maxGrade());
        float dev = 0, cross = 0;
        for (int i = 0; i < p.count(); i++) {
            float h = m.terrain.height(p.x(i), p.z(i));
            dev = Math.max(dev, Math.abs(h - p.y(i)));
            float lx = p.tz(i), lz = -p.tx(i), hw = road.halfWidth();
            float hl = m.terrain.height(p.x(i) + lx * hw, p.z(i) + lz * hw);
            float hr = m.terrain.height(p.x(i) - lx * hw, p.z(i) - lz * hw);
            cross = Math.max(cross, Math.max(Math.abs(hl - h), Math.abs(hr - h)));
        }
        check(dev < 0.1f, "도로 가운데 지형 높이 차 " + dev);
        check(cross < 0.15f, "도로 가로 기울기 (가장자리 높이 차) " + cross);
        if (z == Zone.PASS) {
            check(p.y(0) - p.y(p.count() - 1) > 200, "정상 " + p.y(0) + " → 아래 " + p.y(p.count() - 1));
        }
        float sh = m.terrain.height(m.spawn.x(), m.spawn.z());
        RoadNet.Hit h = m.roads.nearest(m.spawn.x(), m.spawn.z(), 30);
        check(h != null && h.distance() < 1 && Math.abs(sh - h.y()) < 0.1f, "출발점이 도로 위");
    }

    private static void ribbon() {
        for (Zone z : new Zone[]{Zone.CIRCUIT, Zone.PASS}) {
            ZoneMap m = map(z);
            float worst = 0;
            int checked = 0;
            for (ZoneMap.Piece piece : m.pieces) {
                MeshData mesh = piece.mesh();
                if (!mesh.name().startsWith("road-")) {
                    continue;
                }
                for (int v = 0; v < mesh.vertexCount(); v += 7) {
                    var pos = mesh.position(v);
                    float d = pos.y() - m.terrain.height(pos.x(), pos.z());
                    worst = Math.max(worst, Math.abs(d - 0.03f));
                    checked++;
                }
            }
            check(checked > 1000 && worst < 0.005f, z + ": 도로 면 높이 오차 " + worst + " (" + checked + " 정점)");
            // 숨긴 칸은 모두 포장 안쪽
            int n = m.terrain.samples();
            int hidden = 0, bad = 0;
            for (int i = 0; i < n * n; i += 3) {
                if (m.hidden.test(i)) {
                    hidden++;
                    if (m.cover[i] > 0) {
                        bad++;
                    }
                }
            }
            check(hidden > 1000 && bad == 0, z + ": 숨긴 칸 " + hidden + ", 포장 밖 " + bad);
        }
    }

    private static void barriers() {
        for (Zone z : new Zone[]{Zone.CIRCUIT, Zone.PASS}) {
            ZoneMap m = map(z);
            int bad = 0;
            for (float[] b : m.boxes) {
                if (b[3] > 100 || b[5] > 100) {
                    continue; // 지도 가장자리 벽
                }
                RoadNet.Hit h = m.roads.nearest(b[0], b[2], 30);
                float thick = Math.min(b[3], b[5]);
                if (h != null && h.distance() - thick < h.road().halfWidth() + 0.3f) {
                    bad++;
                }
            }
            check(bad == 0, z + ": 포장에 걸친 상자 " + bad);
            check(m.boxes.size() < 3500, z + ": 상자 " + m.boxes.size() + " (물리 최대 4096)");
        }
    }

    private static void city() {
        ZoneMap m = map(Zone.CITY);
        int bad = 0;
        for (int i = 0; i <= City.BLOCKS; i++) {
            for (float t = -City.EDGE; t <= City.EDGE; t += 2) {
                for (float off = -City.HALF_STREET + 0.5f; off <= City.HALF_STREET - 0.5f; off += 1.5f) {
                    float a = City.line(i) + off;
                    if (inside(m.boxes, a, t) || inside(m.boxes, t, a)) {
                        bad++;
                    }
                }
            }
        }
        check(bad == 0, "도로 위 상자 " + bad);
        check(m.boxes.size() > 100, "건물·가로등 상자 " + m.boxes.size());
        check(Math.abs(m.spawn.x() - (City.line(City.BLOCKS / 2) + 3.5f)) < 0.01f && m.spawn.yaw() == 0, "출발: 북쪽(−z)으로 오른쪽 차로");
        check(Math.abs(m.terrain.height(m.spawn.x(), m.spawn.z())) < 0.01f, "도시 바닥 높이 0");
    }

    private static boolean inside(List<float[]> boxes, float x, float z) {
        for (float[] b : boxes) {
            if (b[3] > 100 || b[5] > 100) {
                continue;
            }
            if (Math.abs(x - b[0]) < b[3] && Math.abs(z - b[2]) < b[5] && b[1] - b[4] < 1.5f) {
                return true;
            }
        }
        return false;
    }

    private static void budgets() {
        for (Zone z : new Zone[]{Zone.CIRCUIT, Zone.PASS, Zone.CITY}) {
            ZoneMap m = map(z);
            long coarse = 0;
            int worst = 0;
            for (int i = 0; i < m.chunks.size(); i++) {
                coarse += m.coarse(i).triangleCount();
                if (i % 17 == 0) {
                    worst = Math.max(worst, m.detailed(i).triangleCount());
                }
            }
            long pieces = 0;
            for (ZoneMap.Piece p : m.pieces) {
                pieces += p.mesh().triangleCount();
            }
            check(coarse < 200_000, z + ": 듬성 조각 합 " + coarse);
            check(worst < 10_000, z + ": 자세한 조각 " + worst);
            check(pieces < 250_000, z + ": 고정 메시 " + pieces);
        }
    }

    private static void tracks() {
        check(Zone.ofTrack("yongin-circuit") == Zone.CIRCUIT && Zone.ofTrack("taebaek-pass") == Zone.PASS
                && Zone.ofTrack("city-streets") == Zone.CITY && Zone.ofTrack("drift-pad") == Zone.HUB, "이름 → 지역");
        for (Club club : Club.defaults()) {
            for (ClubEvent e : club.events()) {
                Zone z = Zone.ofTrack(e.track());
                check(z.track.equals(e.track()), e.id() + ": 코스 " + e.track());
                if (z == Zone.CIRCUIT || z == Zone.PASS) {
                    check(map(z).course(e.track()) != null, e.id() + ": 코스 문 있음");
                }
                if (e.type() == ClubEvent.Type.LAP_TIME) {
                    check(z == Zone.CIRCUIT, e.id() + ": 랩타임은 서킷");
                }
            }
        }
    }

    private static Map<String, Float> laps;

    private static Map<String, Float> laps() {
        if (laps == null) {
            laps = new java.util.HashMap<>();
            Course c = map(Zone.CIRCUIT).course(Zones.CIRCUIT_COURSE);
            for (CarCatalog.Entry e : CarCatalog.load(Path.of("cars"))) {
                laps.put(e.spec().id(), LapBot.run(e.spec(), c, 25).lap());
            }
        }
        return laps;
    }

    private static void lapBot() {
        Course c = map(Zone.CIRCUIT).course(Zones.CIRCUIT_COURSE);
        for (String id : new String[]{"04-hanul-coupe38", "21-aurum-magmax"}) {
            CarCatalog.Entry e = CarCatalog.load(Path.of("cars")).stream().filter(x -> x.spec().id().equals(id)).findFirst().orElseThrow();
            LapBot.Result r = LapBot.run(e.spec(), c, 25);
            check(r.lap() > 60 && r.lap() < 160 && !r.nan(), id + ": 랩 " + r.lap());
            check(r.maxOff() < 9, id + ": 도로에서 가장 멀리 " + r.maxOff() + " m");
        }
    }

    private static void lapTargets() {
        driftengine.game.Market market = new driftengine.game.Market(driftengine.game.ClassTable.DEFAULT);
        List<CarCatalog.Entry> cars = CarCatalog.load(Path.of("cars"));
        Map<String, Float> l = laps();
        for (Club club : Club.defaults()) {
            if (club.kind() != Club.Kind.RACING) {
                continue;
            }
            float best = Float.MAX_VALUE;
            for (CarCatalog.Entry e : cars) {
                Float t = l.get(e.spec().id());
                if (t != null && t > 0 && market.stockClass(e.spec()).atMost(club.maxClass())) {
                    best = Math.min(best, t);
                }
            }
            ClubEvent boss = club.events().get(club.events().size() - 1);
            check(best <= boss.target() * 1.02f, club.id() + ": 가장 빠른 순정 " + best + " s vs 보스 목표 " + boss.target());
            check(club.events().get(0).target() >= boss.target(), club.id() + ": 첫 이벤트가 보스보다 쉬움");
        }
    }

    private static void passBot() {
        Course c = map(Zone.PASS).course(Zones.PASS_COURSE);
        CarCatalog.Entry e = CarCatalog.load(Path.of("cars")).stream().filter(x -> x.spec().id().equals("02-hayate-sr15")).findFirst().orElseThrow();
        LapBot.Result r = LapBot.run(e.spec(), c, 20);
        check(r.lap() > 60 && r.lap() < 200, "다운힐 " + r.lap() + " s, 도로에서 가장 멀리 " + r.maxOff());
    }

    private static void classSpread() {
        driftengine.game.Market market = new driftengine.game.Market(driftengine.game.ClassTable.DEFAULT);
        List<CarCatalog.Entry> cars = CarCatalog.load(Path.of("cars"));
        int[] count = new int[driftengine.game.CarClass.values().length];
        for (CarCatalog.Entry e : cars) {
            count[market.stockClass(e.spec()).ordinal()]++;
        }
        for (int c = 0; c < 4; c++) {
            check(count[c] >= 4, "클래스 " + (c + 1) + ": " + count[c] + "대");
        }
        check(count[0] <= 5, "클래스 1 몰림 해소: " + count[0] + "대");
        check(PerformanceRating.of(cars.get(0).spec()) > 0, "성능 점수");
    }

    private static void plaza() {
        ZoneMap m = map(Zone.CITY);
        float[] pb = City.plazaBounds();
        check(pb[2] - pb[0] > 150 && pb[3] - pb[1] > 150, "광장 크기 " + (pb[2] - pb[0]));
        int inside = 0;
        for (float x = pb[0] + 5; x < pb[2] - 5; x += 2) {
            for (float z = pb[1] + 5; z < pb[3] - 5; z += 2) {
                if (inside(m.boxes, x, z)) {
                    inside++;
                }
            }
        }
        check(inside == 0, "광장 안 상자 " + inside);
        for (ZoneMap.Piece p : m.pieces) {
            if (!p.mesh().name().startsWith("street")) {
                continue;
            }
            for (int v = 0; v < p.mesh().vertexCount(); v++) {
                var q = p.mesh().position(v);
                if (q.x() > pb[0] + 4.5f && q.x() < pb[2] - 4.5f && q.z() > pb[1] + 4.5f && q.z() < pb[3] - 4.5f) {
                    check(false, "광장 안 도로 정점 " + q);
                    return;
                }
            }
        }
        int close = 0;
        for (float[] b : m.boxes) {
            if (b[3] > 0.2f) {
                continue; // 가로등만 (반폭 0.15)
            }
            for (int i = 0; i <= City.BLOCKS; i++) {
                if (Math.abs(b[0] - City.line(i)) < City.HALF_STREET + 3 || Math.abs(b[2] - City.line(i)) < City.HALF_STREET + 3) {
                    // 교차로 모서리 대각선이 아닌, 도로 바로 옆이면 문제
                    boolean nearX = Math.abs(b[0] - City.line(i)) < City.HALF_STREET + 3;
                    boolean nearZ = Math.abs(b[2] - City.line(i)) < City.HALF_STREET + 3;
                    if (nearX && Math.abs(b[0] - City.line(i)) < City.HALF_STREET + 2.9f || nearZ && Math.abs(b[2] - City.line(i)) < City.HALF_STREET + 2.9f) {
                        close++;
                    }
                }
            }
        }
        check(close == 0, "도로에서 3m 안 가로등 " + close);
        ZoneMap.Spawn e = m.eventSpawn;
        check(e != null && e.x() > pb[0] + 10 && e.x() < pb[2] - 10 && e.z() > pb[1] + 10 && e.z() < pb[3] - 10, "이벤트 출발 광장 안");
    }
}
