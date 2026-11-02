package driftengine.world.map;

import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.world.Terrain;

import java.util.Random;

/**
 * 도시: 6×6 블록 바둑판 (블록 72m, 도로 16m). 가운데 남북 대로는 노란 중앙선, 나머지는 흰 점선.
 * 교차로 앞 횡단보도, 인도, 건물(층마다 창 띠), 가로등. 블록 2×2 는 드리프트 광장(주차장), 하나는 공원.
 * 바닥은 모두 높이 0 (물리는 평평한 지형), 건물·가로등에 물리 상자.
 */
public final class City {

    public static final int BLOCKS = 6;
    public static final float PITCH = 88f;
    public static final float HALF_STREET = 8f;
    public static final float SIDEWALK = 4f;
    /** 가장 바깥 도로 중심 */
    public static final float EDGE = BLOCKS * PITCH / 2f;
    /** 도시 바닥이 덮는 범위 (바깥 인도 포함) */
    public static final float COVER = EDGE + HALF_STREET + 6f;
    static final float Y = 0.02f;
    /** 드리프트 광장: 블록 2×2 (i 1~2, j 3~4) 와 그 사이 도로를 합친 160m 아스팔트 (도심 주차장 이벤트) */
    static final int PLAZA_I = 1, PLAZA_J = 3;
    static final int[] PARK = {4, 1};

    static boolean plaza(int i, int j) {
        return i >= PLAZA_I && i <= PLAZA_I + 1 && j >= PLAZA_J && j <= PLAZA_J + 1;
    }

    /** 광장 바깥 경계 (인도 포함) {x0, z0, x1, z1} */
    public static float[] plazaBounds() {
        return new float[]{line(PLAZA_I) + HALF_STREET, line(PLAZA_J) + HALF_STREET,
                line(PLAZA_I + 2) - HALF_STREET, line(PLAZA_J + 2) - HALF_STREET};
    }

    static final Vec3 ASPHALT = RoadMesh.ASPHALT;
    static final Vec3 WHITE = RoadMesh.WHITE;
    static final Vec3 YELLOW = RoadMesh.YELLOW;
    static final Vec3 WALK = new Vec3(0.40f, 0.39f, 0.37f);
    static final Vec3 CURB = new Vec3(0.55f, 0.54f, 0.52f);
    static final Vec3 LOT = new Vec3(0.30f, 0.29f, 0.28f);
    static final Vec3 LAWN = new Vec3(0.12f, 0.24f, 0.07f);

    private City() {
    }

    /** 도로 중심선 좌표 (0..BLOCKS) */
    public static float line(int i) {
        return -EDGE + i * PITCH;
    }

    static ZoneMap build() {
        int samples = 512;
        float spacing = 1.5f;
        float size = (samples - 1) * spacing;
        float origin = -size / 2;
        float[] h = new float[samples * samples];
        for (int iz = 0; iz < samples; iz++) {
            for (int ix = 0; ix < samples; ix++) {
                float x = origin + ix * spacing, z = origin + iz * spacing;
                float d = Math.max(Math.abs(x), Math.abs(z));
                float rise = RoadNet.smooth((d - (COVER + 8)) / 70f);
                h[iz * samples + ix] = rise * (14 + 30 * MapTerrain.fbm(x / 160f, z / 160f, 0xC17EL));
            }
        }
        Terrain t = new Terrain(samples, spacing, origin, origin, h);
        float hide = COVER - 1;
        java.util.function.IntPredicate hidden = i -> {
            int ix = i % samples, iz = i / samples;
            float x0 = origin + ix * spacing, z0 = origin + iz * spacing;
            return Math.abs(x0) < hide && Math.abs(z0) < hide && Math.abs(x0 + spacing) < hide && Math.abs(z0 + spacing) < hide;
        };
        Vec3 grass = new Vec3(0.12f, 0.23f, 0.07f), rock = new Vec3(0.31f, 0.29f, 0.27f);
        Terrain.Paint paint = (b, p, slope, i, out) -> {
            b.material(Material.GROUND);
            out[0] = Zones.lerp(grass, rock, Zones.clamp01((slope - 0.12f) * 5));
        };
        ZoneMap m = new ZoneMap(Zone.CITY, t, null, paint, hidden, 32, 160f, null);
        float low = COVER + 6;
        m.lowerFar = i -> Math.abs(origin + (i % samples) * spacing) < low && Math.abs(origin + (i / samples) * spacing) < low;
        Random r = new Random(0xC1717L);
        // 도로 (세로·가로 줄마다 한 메시)
        for (int i = 0; i <= BLOCKS; i++) {
            MeshBuilder ns = new MeshBuilder("street-ns-" + i);
            MeshBuilder ew = new MeshBuilder("street-ew-" + i);
            for (int j = 0; j < BLOCKS; j++) {
                float a = line(j) + HALF_STREET, c = line(j + 1) - HALF_STREET;
                // 광장 안 도로는 없앰 (광장 바닥이 덮음)
                if (!(plaza(i - 1, j) && plaza(i, j))) {
                    street(ns, line(i), a, c, true, i == BLOCKS / 2);
                }
                if (!(plaza(j, i - 1) && plaza(j, i))) {
                    street(ew, line(i), a, c, false, false);
                }
            }
            for (int j = 0; j <= BLOCKS; j++) {
                if (plaza(i - 1, j - 1) && plaza(i, j - 1) && plaza(i - 1, j) && plaza(i, j)) {
                    continue;
                }
                rect(ns, line(i) - HALF_STREET, line(j) - HALF_STREET, line(i) + HALF_STREET, line(j) + HALF_STREET, ASPHALT, Material.ROAD);
            }
            m.pieces.add(new ZoneMap.Piece(ns.build(), false));
            m.pieces.add(new ZoneMap.Piece(ew.build(), false));
        }
        // 바깥 인도 띠
        MeshBuilder ring = new MeshBuilder("city-ring");
        float o = EDGE + HALF_STREET;
        rect(ring, -COVER, o, COVER, COVER, WALK, Material.CONCRETE);
        rect(ring, -COVER, -COVER, COVER, -o, WALK, Material.CONCRETE);
        rect(ring, o, -o, COVER, o, WALK, Material.CONCRETE);
        rect(ring, -COVER, -o, -o, o, WALK, Material.CONCRETE);
        m.pieces.add(new ZoneMap.Piece(ring.build(), false));
        // 블록
        for (int j = 0; j < BLOCKS; j++) {
            for (int i = 0; i < BLOCKS; i++) {
                if (!plaza(i, j)) {
                    block(m, i, j, r);
                }
            }
        }
        plazaMesh(m);
        // 가로등: 교차로 네 모서리, 인도 안쪽 끝 (도로에서 드리프트하다 바로 걸리지 않게). 광장 안은 없음
        float[] pb = plazaBounds();
        MeshBuilder lamps = new MeshBuilder("city-lamps");
        for (int j = 0; j <= BLOCKS; j++) {
            for (int i = 0; i <= BLOCKS; i++) {
                for (int c = 0; c < 4; c++) {
                    float sx = (c & 1) == 0 ? -1 : 1, sz = (c & 2) == 0 ? -1 : 1;
                    float x = line(i) + sx * LAMP_OFFSET, z = line(j) + sz * LAMP_OFFSET;
                    if (Math.abs(x) > COVER - 1 || Math.abs(z) > COVER - 1) {
                        continue;
                    }
                    if (x > pb[0] + SIDEWALK && x < pb[2] - SIDEWALK && z > pb[1] + SIDEWALK && z < pb[3] - SIDEWALK) {
                        continue;
                    }
                    lamp(lamps, x, z, -sx, -sz);
                    m.boxes.add(new float[]{x, 3.5f, z, 0.15f, 3.5f, 0.15f, 0});
                }
            }
        }
        m.pieces.add(new ZoneMap.Piece(lamps.build(), true));
        float c = line(BLOCKS / 2);
        m.spawn = new ZoneMap.Spawn(c + 3.5f, c + PITCH / 2, 0);
        m.eventSpawn = new ZoneMap.Spawn((pb[0] + pb[2]) / 2, pb[3] - 25, 0);
        Zones.horizon(m, 120f);
        Zones.boundary(m);
        return m;
    }

    /** 바닥 사각형 (x0,z0)-(x1,z1), 높이 Y */
    static void rect(MeshBuilder b, float x0, float z0, float x1, float z1, Vec3 color, Material mat) {
        rect(b, x0, z0, x1, z1, Y, color, mat);
    }

    static void rect(MeshBuilder b, float x0, float z0, float x1, float z1, float y, Vec3 color, Material mat) {
        b.material(mat);
        Props.quad(b, new Vec3(x0, y, z0), new Vec3(x1, y, z0), new Vec3(x1, y, z1), new Vec3(x0, y, z1), Vec3.UNIT_Y, color);
    }

    /**
     * 도로 한 마디: 중심 at, 길이 방향 a..c. ns = 남북(길이가 z) 방향.
     * 양 끝 4m 는 횡단보도, 가운데는 차선 (바깥 흰 선, 중앙 노란 이중선 또는 흰 점선).
     */
    static void street(MeshBuilder b, float at, float a, float c, boolean ns, boolean avenue) {
        float cw = 4f;
        // 횡단보도: 줄무늬가 도로 길이 방향
        for (int e = 0; e < 2; e++) {
            float l0 = e == 0 ? a : c - cw, l1 = e == 0 ? a + cw : c;
            float w = -HALF_STREET;
            boolean white = false;
            while (w < HALF_STREET - 1e-3f) {
                float w1 = Math.min(HALF_STREET, w + (white ? 0.6f : (w == -HALF_STREET ? 0.5f : 0.6f)));
                seg(b, at, ns, w, w1, l0, l1, white ? WHITE : ASPHALT);
                w = w1;
                white = !white;
            }
        }
        float l0 = a + cw, l1 = c - cw;
        float[] cols;
        Vec3[] colors;
        if (avenue) {
            cols = new float[]{-8, -7.6f, -7.45f, -0.2f, -0.08f, 0.08f, 0.2f, 7.45f, 7.6f, 8};
            colors = new Vec3[]{ASPHALT, WHITE, ASPHALT, YELLOW, ASPHALT, YELLOW, ASPHALT, WHITE, ASPHALT};
        } else {
            cols = new float[]{-8, -7.6f, -7.45f, -0.08f, 0.08f, 7.45f, 7.6f, 8};
            colors = new Vec3[]{ASPHALT, WHITE, ASPHALT, null, ASPHALT, WHITE, ASPHALT};
        }
        for (int k = 0; k < colors.length; k++) {
            if (colors[k] != null) {
                seg(b, at, ns, cols[k], cols[k + 1], l0, l1, colors[k]);
            } else {
                // 흰 점선 3m / 빈칸 5m
                float s = l0;
                boolean on = false;
                while (s < l1 - 1e-3f) {
                    float s1 = Math.min(l1, s + (on ? 3 : 5));
                    seg(b, at, ns, cols[k], cols[k + 1], s, s1, on ? WHITE : ASPHALT);
                    s = s1;
                    on = !on;
                }
            }
        }
    }

    /** 도로 좌표(옆 w0..w1, 길이 l0..l1) 사각형 */
    private static void seg(MeshBuilder b, float at, boolean ns, float w0, float w1, float l0, float l1, Vec3 color) {
        if (ns) {
            rect(b, at + w0, l0, at + w1, l1, color, Material.ROAD);
        } else {
            rect(b, l0, at + w0, l1, at + w1, color, Material.ROAD);
        }
    }

    private static void block(ZoneMap m, int i, int j, Random r) {
        float x0 = line(i) + HALF_STREET, x1 = line(i + 1) - HALF_STREET;
        float z0 = line(j) + HALF_STREET, z1 = line(j + 1) - HALF_STREET;
        MeshBuilder b = new MeshBuilder("block-" + i + "-" + j);
        // 인도 (연석 띠 + 보도)
        float s = SIDEWALK;
        rect(b, x0, z0, x1, z0 + 0.25f, CURB, Material.CONCRETE);
        rect(b, x0, z1 - 0.25f, x1, z1, CURB, Material.CONCRETE);
        rect(b, x0, z0 + 0.25f, x0 + 0.25f, z1 - 0.25f, CURB, Material.CONCRETE);
        rect(b, x1 - 0.25f, z0 + 0.25f, x1, z1 - 0.25f, CURB, Material.CONCRETE);
        rect(b, x0 + 0.25f, z0 + 0.25f, x1 - 0.25f, z0 + s, WALK, Material.CONCRETE);
        rect(b, x0 + 0.25f, z1 - s, x1 - 0.25f, z1 - 0.25f, WALK, Material.CONCRETE);
        rect(b, x0 + 0.25f, z0 + s, x0 + s, z1 - s, WALK, Material.CONCRETE);
        rect(b, x1 - s, z0 + s, x1 - 0.25f, z1 - s, WALK, Material.CONCRETE);
        float ix0 = x0 + s, ix1 = x1 - s, iz0 = z0 + s, iz1 = z1 - s;
        if (i == PARK[0] && j == PARK[1]) {
            rect(b, ix0, iz0, ix1, iz1, LAWN, Material.GROUND);
            for (int k = 0; k < 14; k++) {
                float tx = ix0 + 5 + r.nextFloat() * (ix1 - ix0 - 10), tz = iz0 + 5 + r.nextFloat() * (iz1 - iz0 - 10);
                Props.broadleaf(b, tx, 0, tz, 0.8f + r.nextFloat() * 0.5f, new Vec3(0.07f, 0.2f, 0.05f), r.nextFloat() * 3);
                m.boxes.add(new float[]{tx, 2, tz, 0.3f, 2, 0.3f, 0});
            }
        } else {
            rect(b, ix0, iz0, ix1, iz1, LOT, Material.CONCRETE);
            buildings(m, b, ix0, iz0, ix1, iz1, i, j, r);
        }
        m.pieces.add(new ZoneMap.Piece(b.build(), true));
    }

    /** 가로등 위치: 도로 중심에서 (인도 안쪽 끝) */
    static final float LAMP_OFFSET = HALF_STREET + SIDEWALK - 0.6f;

    /** 드리프트 광장: 바깥 인도 + 아스팔트, 가장자리 주차 칸, 가운데 큰 원 두 개 (선은 바닥보다 1cm 위) */
    private static void plazaMesh(ZoneMap m) {
        float[] pb = plazaBounds();
        float x0 = pb[0], z0 = pb[1], x1 = pb[2], z1 = pb[3], s = SIDEWALK;
        MeshBuilder b = new MeshBuilder("city-plaza");
        rect(b, x0, z0, x1, z0 + s, WALK, Material.CONCRETE);
        rect(b, x0, z1 - s, x1, z1, WALK, Material.CONCRETE);
        rect(b, x0, z0 + s, x0 + s, z1 - s, WALK, Material.CONCRETE);
        rect(b, x1 - s, z0 + s, x1, z1 - s, WALK, Material.CONCRETE);
        float ax0 = x0 + s, az0 = z0 + s, ax1 = x1 - s, az1 = z1 - s;
        rect(b, ax0, az0, ax1, az1, ASPHALT, Material.ROAD);
        float y = Y + 0.01f;
        for (float x = ax0 + 3; x < ax1 - 3; x += 2.6f) {
            rect(b, x, az0 + 1, x + 0.12f, az0 + 6, y, WHITE, Material.ROAD);
            rect(b, x, az1 - 6, x + 0.12f, az1 - 1, y, WHITE, Material.ROAD);
        }
        rect(b, ax0 + 3, az0 + 6, ax1 - 3, az0 + 6.12f, y, WHITE, Material.ROAD);
        rect(b, ax0 + 3, az1 - 6.12f, ax1 - 3, az1 - 6, y, WHITE, Material.ROAD);
        float cx = (ax0 + ax1) / 2, cz = (az0 + az1) / 2;
        ring(b, cx, cz - 30, 22, y);
        ring(b, cx, cz + 30, 22, y);
        m.pieces.add(new ZoneMap.Piece(b.build(), false));
    }

    /** 흰 원 (폭 0.15m) */
    private static void ring(MeshBuilder b, float cx, float cz, float r, float y) {
        b.material(Material.ROAD);
        int seg = 72;
        for (int k = 0; k < seg; k++) {
            double a0 = 2 * Math.PI * k / seg, a1 = 2 * Math.PI * (k + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            Props.quad(b, new Vec3(cx + c0 * (r - 0.08f), y, cz + s0 * (r - 0.08f)), new Vec3(cx + c1 * (r - 0.08f), y, cz + s1 * (r - 0.08f)),
                    new Vec3(cx + c1 * (r + 0.08f), y, cz + s1 * (r + 0.08f)), new Vec3(cx + c0 * (r + 0.08f), y, cz + s0 * (r + 0.08f)),
                    Vec3.UNIT_Y, WHITE);
        }
    }

    static final Vec3[] FACADE = {
            new Vec3(0.62f, 0.6f, 0.56f), new Vec3(0.45f, 0.43f, 0.42f), new Vec3(0.55f, 0.36f, 0.28f),
            new Vec3(0.75f, 0.74f, 0.7f), new Vec3(0.32f, 0.34f, 0.37f), new Vec3(0.66f, 0.58f, 0.46f)};
    static final Vec3 GLASS = new Vec3(0.09f, 0.12f, 0.15f);

    /** 블록 안 건물: 1, 2, 4 동으로 나누고 가운데일수록 높게 */
    private static void buildings(ZoneMap m, MeshBuilder b, float x0, float z0, float x1, float z1, int i, int j, Random r) {
        int split = r.nextInt(3);
        float cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        float centre = 1 - Math.min(1, (float) Math.hypot(cx, cz) / (EDGE * 1.1f));
        float[][] lots = switch (split) {
            case 0 -> new float[][]{{x0, z0, x1, z1}};
            case 1 -> r.nextBoolean() ? new float[][]{{x0, z0, cx, z1}, {cx, z0, x1, z1}} : new float[][]{{x0, z0, x1, cz}, {x0, cz, x1, z1}};
            default -> new float[][]{{x0, z0, cx, cz}, {cx, z0, x1, cz}, {x0, cz, cx, z1}, {cx, cz, x1, z1}};
        };
        for (float[] l : lots) {
            float inset = 2 + r.nextFloat() * 3;
            float bx0 = l[0] + inset, bz0 = l[1] + inset, bx1 = l[2] - inset, bz1 = l[3] - inset;
            int floors = 4 + (int) (r.nextFloat() * (6 + 26 * centre * centre)) + (lots.length == 1 ? 6 : 0);
            Vec3 wall = FACADE[r.nextInt(FACADE.length)];
            tower(b, bx0, bz0, bx1, bz1, floors, wall);
            float top = floors * 3.5f;
            m.boxes.add(new float[]{(bx0 + bx1) / 2, top / 2, (bz0 + bz1) / 2, (bx1 - bx0) / 2, top / 2, (bz1 - bz0) / 2, 0});
        }
    }

    /** 층마다 벽 띠 1.0m + 창 띠 2.5m, 지붕 + 옥상 설비 */
    static void tower(MeshBuilder b, float x0, float z0, float x1, float z1, int floors, Vec3 wall) {
        float y = 0;
        for (int f = 0; f < floors; f++) {
            band(b, x0, z0, x1, z1, y, y + 1.0f, wall, Material.CONCRETE);
            band(b, x0, z0, x1, z1, y + 1.0f, y + 3.5f, f == 0 ? wall.scale(0.6f) : GLASS, f == 0 ? Material.CONCRETE : Material.WINDOW);
            y += 3.5f;
        }
        band(b, x0, z0, x1, z1, y, y + 0.8f, wall, Material.CONCRETE);
        rect(b, x0, z0, x1, z1, y + 0.8f, wall.scale(0.7f), Material.CONCRETE);
        float w = Math.min(x1 - x0, z1 - z0);
        Props.box(b, (x0 + x1) / 2, y + 2.3f, (z0 + z1) / 2, w * 0.18f, 1.5f, w * 0.14f, 0, new Vec3(0.5f, 0.5f, 0.5f), Material.ALUMINIUM);
    }

    /** 네 벽면 띠 (y0..y1) */
    private static void band(MeshBuilder b, float x0, float z0, float x1, float z1, float y0, float y1, Vec3 c, Material m) {
        b.material(m);
        Props.quad(b, new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0), new Vec3(0, 0, -1), c);
        Props.quad(b, new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1), new Vec3(0, 0, 1), c);
        Props.quad(b, new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), new Vec3(x0, y1, z0), new Vec3(-1, 0, 0), c);
        Props.quad(b, new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x1, y1, z0), new Vec3(1, 0, 0), c);
    }

    /** 가로등 7m: 기둥 + 길 쪽 팔 + 등 */
    private static void lamp(MeshBuilder b, float x, float z, float dx, float dz) {
        Vec3 pole = new Vec3(0.25f, 0.26f, 0.28f);
        Props.cylinder(b, x, 0, z, 0.1f, 7, 6, pole, Material.ALUMINIUM);
        float ax = x + dx * 1.0f, az = z + dz * 1.0f;
        Props.box(b, (x + ax) / 2, 6.9f, (z + az) / 2, Math.abs(ax - x) / 2 + 0.06f, 0.05f, Math.abs(az - z) / 2 + 0.06f, 0, pole, Material.ALUMINIUM);
        Props.box(b, ax, 6.8f, az, 0.25f, 0.08f, 0.25f, 0, new Vec3(0.9f, 0.88f, 0.8f), Material.LAMP);
    }
}
