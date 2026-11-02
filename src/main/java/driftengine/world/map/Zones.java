package driftengine.world.map;

import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.world.Terrain;

import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

/**
 * 지역 만들기: 용인 서킷, 태백 고갯길, 도시. (연습장은 TerrainScene 이 예전대로 만든다)
 * 모두 가상의 장소다. 서킷·고갯길 모양은 그런 느낌만 살렸다.
 */
public final class Zones {

    public static final String CIRCUIT_COURSE = "yongin-circuit";
    public static final String PASS_COURSE = "taebaek-pass";

    private Zones() {
    }

    public static ZoneMap build(Zone zone) {
        return switch (zone) {
            case CIRCUIT -> circuit();
            case PASS -> pass();
            case CITY -> City.build();
            case HUB -> throw new IllegalArgumentException("연습장은 TerrainScene 이 만든다");
        };
    }

    // ------------------------------------------------------------------ 용인 서킷

    /** 시계 방향 3.6km, 메인 스트레이트 700m, 헤어핀 1개, S자, 고속 스위퍼, 언덕 (±20m) */
    static final float[][] CIRCUIT_POINTS = {
            {-300, 300}, {0, 300}, {300, 300}, {400, 292}, {455, 262}, {470, 215}, {440, 180}, {370, 165}, {300, 140},
            {262, 90}, {255, 30}, {280, -30}, {340, -75}, {430, -100}, {510, -150}, {540, -235}, {505, -310}, {420, -345},
            {300, -345}, {180, -330}, {90, -300}, {20, -320}, {-40, -360}, {-120, -385}, {-250, -380}, {-370, -340},
            {-450, -270}, {-470, -170}, {-430, -70}, {-350, -20}, {-290, 40}, {-290, 120}, {-350, 170}, {-430, 200},
            {-475, 245}, {-460, 290}, {-400, 302}};
    public static final float CIRCUIT_HALF_WIDTH = 6.5f;
    /** 출발선: 메인 스트레이트 가운데 (x = 0) */
    static final float CIRCUIT_START_X = 0;

    static ZoneMap circuit() {
        RoadPath path = RoadPath.through(CIRCUIT_POINTS, true, 1f);
        float len = path.length();
        path.heights(s -> 14 * Math.sin(2 * Math.PI * s / len + 0.6) + 6 * Math.sin(4 * Math.PI * s / len + 2.1)
                + 3 * Math.sin(6 * Math.PI * s / len + 0.4));
        Road road = new Road("circuit", path, CIRCUIT_HALF_WIDTH, Road.Style.CIRCUIT, false, CIRCUIT_HALF_WIDTH + 13f, 22f);
        RoadNet net = new RoadNet(List.of(road));
        MapTerrain.Result t = MapTerrain.build(1024, 1.5f, net, (x, z) -> 4f, 0.4f, 12f, 1 / 230f, 0x5EED_0001L, 150f);
        ZoneMap m = new ZoneMap(Zone.CIRCUIT, t.terrain(), t.cover(), paint(t.cover(), false), hiddenUnderRoad(t), 64,
                220f, net);
        float start = startS(path, CIRCUIT_START_X, 300);
        for (var mesh : RoadMesh.build(road, t.terrain(), start)) {
            m.pieces.add(new ZoneMap.Piece(mesh, false));
        }
        Barriers.Result walls = Barriers.build(road, net, t.terrain());
        walls.meshes().forEach(x -> m.pieces.add(new ZoneMap.Piece(x, true)));
        m.boxes.addAll(walls.boxes());
        m.courses.put(CIRCUIT_COURSE, Course.on(CIRCUIT_COURSE, road, true, start, 0, 300, 6f));
        float[] sp = path.at(start - 60);
        m.spawn = new ZoneMap.Spawn(sp[0], sp[1], yawOf(sp[2], sp[3]));
        circuitScenery(m, path, start, t.terrain(), t.cover());
        horizon(m, 60f);
        boundary(m);
        return m;
    }

    /** 출발선 s: 경로 위에서 (x, z) 에 가장 가까운 점 */
    static float startS(RoadPath p, float x, float z) {
        float best = Float.MAX_VALUE;
        float s = 0;
        for (int i = 0; i < p.count(); i++) {
            float d = (float) Math.hypot(p.x(i) - x, p.z(i) - z);
            if (d < best) {
                best = d;
                s = p.s(i);
            }
        }
        return s;
    }

    /** 진행 방향 (tx, tz) 으로 차 앞(−z)을 돌리는 yaw */
    public static float yawOf(float tx, float tz) {
        // 차 앞 (0,0,−1) 을 Y 축으로 a 만큼 돌리면 (−sin a, 0, −cos a)
        return (float) Math.atan2(-tx, -tz);
    }

    private static void circuitScenery(ZoneMap m, RoadPath path, float start, Terrain t, float[] cover) {
        MeshBuilder b = new MeshBuilder("circuit-stands");
        // 메인 스트레이트 바깥(z > 300) 관중석 4동 + 지붕
        Vec3[] seats = {new Vec3(0.75f, 0.12f, 0.1f), new Vec3(0.1f, 0.3f, 0.7f), new Vec3(0.85f, 0.85f, 0.85f), new Vec3(0.9f, 0.55f, 0.05f)};
        for (int k = 0; k < 4; k++) {
            float cx = -210 + k * 140;
            float base = t.height(cx, 300) - 0.5f;
            for (int row = 0; row < 8; row++) {
                float z = 328 + row * 2.2f;
                Props.box(b, cx, base + 0.6f + row * 0.9f, z, 55, 0.6f + row * 0.45f, 1.1f, 0, seats[k].scale(row % 2 == 0 ? 1 : 0.8f), Material.DEFAULT);
            }
            Props.box(b, cx, base + 13f, 336, 58, 0.25f, 12, 0, new Vec3(0.85f, 0.86f, 0.88f), Material.ALUMINIUM);
            for (int c = -1; c <= 1; c += 2) {
                Props.box(b, cx + c * 56, base + 6.5f, 346, 0.4f, 6.5f, 0.4f, 0, new Vec3(0.4f, 0.4f, 0.42f), Material.ALUMINIUM);
            }
        }
        // 피트 건물 (안쪽, z < 300)
        float pitBase = t.height(0, 268) - 0.3f;
        Props.box(b, 0, pitBase + 4, 262, 180, 4, 7, 0, new Vec3(0.78f, 0.79f, 0.8f), Material.CONCRETE);
        for (int i = 0; i < 18; i++) {
            Props.box(b, -170 + i * 20, pitBase + 2.4f, 269.1f, 8, 2.2f, 0.05f, 0, new Vec3(0.12f, 0.12f, 0.13f), Material.WINDOW);
        }
        Props.box(b, 0, pitBase + 9.5f, 262, 60, 1.5f, 6, 0, new Vec3(0.1f, 0.1f, 0.12f), Material.WINDOW);
        m.boxes.add(new float[]{0, pitBase + 4, 262, 180, 4, 7, 0});
        // 출발선 문 (기둥 둘 + 가로 보 + 신호등)
        float[] s0 = path.at(start);
        float gy = t.height(s0[0], s0[1]);
        float yaw = (float) Math.atan2(-s0[3], s0[2]);
        float side = CIRCUIT_HALF_WIDTH + 6f;
        for (int c = -1; c <= 1; c += 2) {
            float px = s0[0] + s0[3] * side * c, pz = s0[1] - s0[2] * side * c;
            Props.box(b, px, gy + 4, pz, 0.5f, 4, 0.5f, yaw, new Vec3(0.2f, 0.2f, 0.22f), Material.ALUMINIUM);
            m.boxes.add(new float[]{px, gy + 4, pz, 0.5f, 4, 0.5f, yaw});
        }
        Props.box(b, s0[0], gy + 8.6f, s0[1], 0.7f, 0.9f, side + 0.5f, yaw, new Vec3(0.12f, 0.12f, 0.14f), Material.DEFAULT);
        for (int i = -4; i <= 4; i++) {
            float px = s0[0] + s0[3] * i * 1.2f, pz = s0[1] - s0[2] * i * 1.2f;
            Props.box(b, px + s0[2] * 0.72f, gy + 8.6f, pz + s0[3] * 0.72f, 0.06f, 0.35f, 0.35f, yaw,
                    new Vec3(0.9f, 0.1f, 0.05f), Material.TAIL_LIGHT);
        }
        m.pieces.add(new ZoneMap.Piece(b.build(), true));
        trees(m, t, cover, 22f, 0.45f, 28f, 0x7EE5L, false, 640);
    }

    // ------------------------------------------------------------------ 태백 고갯길

    /** 정상(북서) → 아래(남서). 4 구간 지그재그, 헤어핀 3개, 3.1km, 경사 약 7.5% */
    static final float[][] PASS_POINTS = {
            {-420, 480}, {-360, 478}, {-290, 470}, {-210, 452}, {-130, 430}, {-60, 440}, {20, 425}, {100, 402}, {180, 415},
            {250, 400}, {300, 386}, {343, 376}, {366, 357}, {357, 334}, {325, 326},
            {270, 328}, {200, 318}, {130, 298}, {60, 310}, {-20, 292}, {-100, 272}, {-180, 284}, {-250, 266}, {-300, 250},
            {-343, 240}, {-366, 220}, {-355, 198}, {-320, 191},
            {-260, 192}, {-190, 178}, {-120, 158}, {-50, 170}, {30, 150}, {110, 130}, {190, 142}, {260, 124}, {305, 110},
            {345, 100}, {366, 80}, {357, 58}, {325, 51},
            {270, 52}, {200, 40}, {120, 20}, {40, 32}, {-40, 12}, {-120, -10}, {-200, 4}, {-280, -14}, {-340, -30},
            {-400, -40}};
    public static final float PASS_HALF_WIDTH = 4.2f;
    static final float PASS_TOP = 255f;
    static final float PASS_GRADE = 0.075f;
    /** 정상·아래 광장 길이 (평평) */
    static final float PASS_PLAZA = 45f;

    static ZoneMap pass() {
        RoadPath path = RoadPath.through(PASS_POINTS, false, 1f);
        float len = path.length();
        path.heights(s -> {
            float u = (float) s - PASS_PLAZA;
            float run = len - 2 * PASS_PLAZA;
            // 광장 끝에서 경사로 부드럽게 (20m)
            float d = softClamp(u, 0, run, 20);
            return PASS_TOP - PASS_GRADE * d;
        });
        Road road = new Road("pass", path, PASS_HALF_WIDTH, Road.Style.MOUNTAIN, true, 0, 14f);
        RoadNet net = new RoadNet(List.of(road));
        MapTerrain.Result t = MapTerrain.build(1024, 1.5f, net,
                (x, z) -> softFloor(45 + 0.45f * z, 5f, 40f), 1.2f, 42f, 1 / 250f, 0x7AEBA4L, 90f);
        ZoneMap m = new ZoneMap(Zone.PASS, t.terrain(), t.cover(), paint(t.cover(), true), hiddenUnderRoad(t), 64,
                220f, net);
        for (var mesh : RoadMesh.build(road, t.terrain(), -1)) {
            m.pieces.add(new ZoneMap.Piece(mesh, false));
        }
        Barriers.Result rails = Barriers.build(road, net, t.terrain());
        rails.meshes().forEach(x -> m.pieces.add(new ZoneMap.Piece(x, true)));
        m.boxes.addAll(rails.boxes());
        m.courses.put(PASS_COURSE, Course.on(PASS_COURSE, road, false, PASS_PLAZA - 5, len - PASS_PLAZA + 5, 250, 6f));
        float[] sp = path.at(12);
        m.spawn = new ZoneMap.Spawn(sp[0], sp[1], yawOf(sp[2], sp[3]));
        // 정상 표지 + 아래 휴게소 지붕
        MeshBuilder b = new MeshBuilder("pass-signs");
        float[] top = path.at(4);
        float ty = t.terrain().height(top[0], top[1] + 9);
        Props.box(b, top[0] + 2, ty + 2.5f, top[1] + 9, 3.2f, 1.1f, 0.15f, 0, new Vec3(0.1f, 0.35f, 0.15f), Material.DEFAULT);
        Props.box(b, top[0] - 0.5f, ty + 1, top[1] + 9, 0.12f, 1.4f, 0.12f, 0, new Vec3(0.5f, 0.5f, 0.5f), Material.GALVANIZED);
        Props.box(b, top[0] + 4.5f, ty + 1, top[1] + 9, 0.12f, 1.4f, 0.12f, 0, new Vec3(0.5f, 0.5f, 0.5f), Material.GALVANIZED);
        float[] end = path.at(len - 10);
        float ey = t.terrain().height(end[0], end[1] - 12);
        Props.box(b, end[0], ey + 3.2f, end[1] - 13, 9, 0.2f, 4, 0, new Vec3(0.55f, 0.18f, 0.12f), Material.DEFAULT);
        for (int c = -1; c <= 1; c += 2) {
            for (int d = -1; d <= 1; d += 2) {
                Props.box(b, end[0] + c * 8, ey + 1.6f, end[1] - 13 + d * 3.4f, 0.15f, 1.6f, 0.15f, 0, new Vec3(0.3f, 0.22f, 0.15f), Material.DEFAULT);
            }
        }
        m.pieces.add(new ZoneMap.Piece(b.build(), true));
        trees(m, t.terrain(), t.cover(), 16f, 0.62f, 9f, 0x7A3BL, true, 700);
        horizon(m, 260f);
        boundary(m);
        return m;
    }

    /** v 가 floor 아래로 내려가면 부드럽게 floor 로 (골짜기 바닥) */
    static float softFloor(float v, float floor, float w) {
        float d = (v - floor) / w;
        return floor + w * (d > 20 ? d : (float) Math.log1p(Math.exp(d)));
    }

    /** a..b 로 자르되 끝 w 거리 동안 부드럽게 (경사가 갑자기 꺾이지 않게) */
    static float softClamp(float u, float a, float b, float w) {
        if (u <= a - w) {
            return a;
        }
        if (u >= b + w) {
            return b;
        }
        if (u < a + w) {
            float t = (u - (a - w)) / (2 * w);
            return a + w * 2 * t * t / 2;
        }
        if (u > b - w) {
            float t = ((b + w) - u) / (2 * w);
            return b - w * 2 * t * t / 2;
        }
        return u;
    }

    // ------------------------------------------------------------------ 공통

    /** 칸의 네 모서리가 모두 포장 안쪽(끝에서 0.3m 안)이면 지형을 그리지 않는다 (도로 면이 덮음) */
    static IntPredicate hiddenUnderRoad(MapTerrain.Result t) {
        float[] c = t.cover();
        int n = t.terrain().samples();
        return i -> {
            int ix = i % n, iz = i / n;
            if (ix + 1 >= n || iz + 1 >= n) {
                return false;
            }
            return c[i] < -0.3f && c[i + 1] < -0.3f && c[i + n] < -0.3f && c[i + n + 1] < -0.3f;
        };
    }

    /** 지형 색: 도로 옆 자갈 → 풀 → (가파르면) 바위. 산은 짙은 숲 바닥 */
    static Terrain.Paint paint(float[] cover, boolean mountain) {
        Vec3 grass = mountain ? new Vec3(0.09f, 0.16f, 0.055f) : new Vec3(0.12f, 0.23f, 0.07f);
        Vec3 rock = new Vec3(0.31f, 0.29f, 0.27f);
        Vec3 gravel = new Vec3(0.24f, 0.22f, 0.19f);
        Vec3 asphalt = new Vec3(0.06f, 0.06f, 0.065f);
        return (b, p, slope, i, out) -> {
            float c = cover[i];
            if (c < 0) {
                b.material(Material.ROAD);
                out[0] = asphalt;
                return;
            }
            b.material(Material.GROUND);
            Vec3 g = lerp(grass, rock, clamp01((slope - (mountain ? 0.16f : 0.1f)) * 5f));
            out[0] = c < 0.8f ? gravel : lerp(gravel, g, clamp01((c - 0.8f) / 4f));
        };
    }

    /** 나무: 격자(간격 cell)에 흔들어 심되 도로에서 minCover 안, 가파른 곳, 지도 가장자리 밖은 뺀다 */
    static void trees(ZoneMap m, Terrain t, float[] cover, float cell, float chance, float minCover, long seed,
                      boolean pines, float extent) {
        Random r = new Random(seed);
        int tiles = (int) Math.ceil(2 * extent / 160f);
        MeshBuilder[] groups = new MeshBuilder[tiles * tiles];
        int n = t.samples();
        for (float z = -extent; z < extent; z += cell) {
            for (float x = -extent; x < extent; x += cell) {
                float px = x + (r.nextFloat() - 0.5f) * cell * 0.8f;
                float pz = z + (r.nextFloat() - 0.5f) * cell * 0.8f;
                if (r.nextFloat() > chance) {
                    continue;
                }
                int ix = Math.round((px - t.originX()) / t.spacing());
                int iz = Math.round((pz - t.originZ()) / t.spacing());
                if (ix < 2 || iz < 2 || ix >= n - 2 || iz >= n - 2 || cover[iz * n + ix] < minCover) {
                    continue;
                }
                if (t.normalAtGrid(ix, iz).y() < 0.8f) {
                    continue;
                }
                int g = Math.min(tiles - 1, (int) ((pz + extent) / 160f)) * tiles + Math.min(tiles - 1, (int) ((px + extent) / 160f));
                if (groups[g] == null) {
                    groups[g] = new MeshBuilder("trees-" + m.zone.name().toLowerCase() + "-" + g);
                }
                float size = 0.75f + r.nextFloat() * 0.6f;
                float y = t.height(px, pz);
                Vec3 leaf = new Vec3(0.05f + r.nextFloat() * 0.03f, 0.13f + r.nextFloat() * 0.06f, 0.04f);
                if (pines || r.nextFloat() < 0.35f) {
                    Props.pine(groups[g], px, y, pz, size, leaf);
                } else {
                    Props.broadleaf(groups[g], px, y, pz, size, leaf.add(new Vec3(0.03f, 0.04f, 0)), r.nextFloat() * 3);
                }
            }
        }
        for (MeshBuilder b : groups) {
            if (b != null) {
                m.pieces.add(new ZoneMap.Piece(b.build(), true));
            }
        }
    }

    /**
     * 먼 땅: 지도 밖 3km 까지 성긴 격자 (그림자·물리 없음). 가장자리 높이를 이어받아 밖으로 갈수록 산이 솟는다.
     * 높은 곳(고갯길 정상)에서 봐도 땅이 끊긴 낭떠러지가 보이지 않게.
     */
    static void horizon(ZoneMap m, float rise) {
        Terrain t = m.terrain;
        float half = t.size() / 2 - 1;
        float cell = 150f;
        int n = 40;
        float ext = n * cell / 2;
        MeshBuilder b = new MeshBuilder("horizon-" + m.zone.name().toLowerCase());
        b.material(Material.GROUND);
        int[] idx = new int[(n + 1) * (n + 1)];
        Vec3 grass = new Vec3(0.1f, 0.19f, 0.06f), rock = new Vec3(0.3f, 0.28f, 0.26f);
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                float x = -ext + i * cell, z = -ext + j * cell;
                float cx = Math.max(-half, Math.min(half, x)), cz = Math.max(-half, Math.min(half, z));
                float out = (float) Math.hypot(x - cx, z - cz);
                float y = t.height(cx, cz) + rise * RoadNet.smooth(out / 900f) * (0.4f + MapTerrain.fbm(x / 700f, z / 700f, 0xFA2L))
                        - (out < 1 ? 2f : 0); // 지도 안쪽 꼭짓점은 지형 아래로 숨김
                Vec3 c = Zones.lerp(grass, rock, clamp01(out / 1500f));
                idx[j * (n + 1) + i] = b.vertex(new Vec3(x, y, z), Vec3.UNIT_Y, c);
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float x0 = -ext + i * cell, z0 = -ext + j * cell;
                if (x0 >= -half && x0 + cell <= half && z0 >= -half && z0 + cell <= half) {
                    continue; // 지도 안 (진짜 지형이 덮음)
                }
                int a = idx[j * (n + 1) + i], c = idx[j * (n + 1) + i + 1], d = idx[(j + 1) * (n + 1) + i + 1], e = idx[(j + 1) * (n + 1) + i];
                Props.tri(b, a, e, d);
                Props.tri(b, a, d, c);
            }
        }
        // 법선: 면 법선 평균 대신 위쪽 그대로 (멀어서 차이 작음)
        m.pieces.add(new ZoneMap.Piece(b.build(), false));
    }

    /** 지도 가장자리 벽 (보이지 않음, 밖으로 떨어지지 않게) */
    static void boundary(ZoneMap m) {
        Terrain t = m.terrain;
        float half = t.size() / 2 - 2;
        float top = 0;
        for (float h : t.heights()) {
            top = Math.max(top, h);
        }
        float cy = top / 2, hy = top / 2 + 60;
        m.boxes.add(new float[]{0, cy, half, half, hy, 1, 0});
        m.boxes.add(new float[]{0, cy, -half, half, hy, 1, 0});
        m.boxes.add(new float[]{half, cy, 0, 1, hy, half, 0});
        m.boxes.add(new float[]{-half, cy, 0, 1, hy, half, 0});
    }

    static Vec3 lerp(Vec3 a, Vec3 b, float t) {
        return a.add(b.sub(a).scale(t));
    }

    static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
