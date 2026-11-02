package driftengine.world;

import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.MeshData;

import java.util.ArrayList;
import java.util.List;

/**
 * 맵 위 샵 두 곳 (튜닝샵, 스타일링샵). 지붕 없는 쇼룸: 둥근 무대 + 뒤 곡면 벽 + 간판 기둥 + 조명 기둥,
 * 앞에 빛나는 입구 원. 입구 원 안에 천천히 들어와 Enter → 샵 화면.
 * <p>
 * 좌표: 샵마다 "앞"(+z 로컬)이 출발점 쪽을 본다. 차는 무대 가운데에 앞을 보고 선다.
 */
public final class Shops {

    public enum Kind {
        TUNING("튜닝샵", "TUNING", new Vec3(1.0f, 0.55f, 0.08f), 0xFFB321FF),
        STYLING("스타일링샵", "STYLING", new Vec3(0.15f, 0.75f, 1.0f), 0x38D6FFFF);

        public final String label;
        public final String title;
        /** 간판·바닥 빛 색 (선형) */
        public final Vec3 glow;
        /** UI 색 (sRGB 0xRRGGBBAA) */
        public final int ui;

        Kind(String label, String title, Vec3 glow, int ui) {
            this.label = label;
            this.title = title;
            this.glow = glow;
            this.ui = ui;
        }
    }

    /**
     * @param center 무대 가운데 (땅 높이)
     * @param yaw    샵 앞 방향 (라디안, 0 = +z)
     */
    public record Shop(Kind kind, Vec3 center, float yaw) {

        public static final float PAD_RADIUS = 6.5f;
        public static final float RING_DISTANCE = 11f;
        public static final float RING_RADIUS = 3.6f;

        /** 로컬 (x, z) → 월드 */
        public Vec3 local(float x, float y, float z) {
            float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
            return new Vec3(center.x() + x * c + z * s, center.y() + y, center.z() - x * s + z * c);
        }

        /** 샵 앞 방향 (수평, 길이 1) */
        public Vec3 front() {
            return new Vec3((float) Math.sin(yaw), 0, (float) Math.cos(yaw));
        }

        public Vec3 entrance() {
            return local(0, 0, RING_DISTANCE);
        }

        /** 입구 원 안인가 (수평 거리) */
        public boolean atEntrance(Vec3 p) {
            Vec3 e = entrance();
            float dx = p.x() - e.x(), dz = p.z() - e.z();
            return dx * dx + dz * dz < RING_RADIUS * RING_RADIUS;
        }

        public float distance(Vec3 p) {
            float dx = p.x() - center.x(), dz = p.z() - center.z();
            return (float) Math.sqrt(dx * dx + dz * dz);
        }

        /** 충돌 상자 (월드 가운데 x, y, z, 반크기 x, y, z, 회전 yaw): 뒤 벽 조각들 + 간판 기둥 + 조명 기둥 */
        public List<float[]> colliders() {
            List<float[]> out = new ArrayList<>();
            int seg = 7;
            for (int i = 0; i < seg; i++) {
                float a = (float) Math.toRadians(205 + (130.0 * (i + 0.5) / seg));
                float r = 9.3f;
                Vec3 p = local((float) Math.sin(a) * r, 2.75f, (float) Math.cos(a) * r);
                float len = (float) (2 * r * Math.sin(Math.toRadians(130.0 / seg / 2))) * 0.55f;
                out.add(new float[]{p.x(), p.y(), p.z(), len, 2.75f, 0.25f, yaw + a});
            }
            Vec3 pylon = local(-8.2f, 4.5f, 3.5f);
            out.add(new float[]{pylon.x(), pylon.y(), pylon.z(), 0.7f, 4.5f, 0.7f, yaw});
            for (float sx : new float[]{-6.8f, 6.8f}) {
                Vec3 pole = local(sx, 3f, 6.5f);
                out.add(new float[]{pole.x(), pole.y(), pole.z(), 0.15f, 3f, 0.15f, yaw});
            }
            return out;
        }
    }

    private Shops() {
    }

    /** 연습장 가장자리 두 곳 (출발점 앞 왼쪽 / 오른쪽, 출발점을 바라봄) */
    public static List<Shop> layout(java.util.function.BiFunction<Float, Float, Float> ground) {
        List<Shop> out = new ArrayList<>();
        float[][] spots = {{-52, -82}, {52, -82}};
        Kind[] kinds = {Kind.TUNING, Kind.STYLING};
        for (int i = 0; i < 2; i++) {
            float x = spots[i][0], z = spots[i][1];
            // 앞이 출발점(0, -14) 쪽을 보게
            float yaw = (float) Math.atan2(0 - x, -14 - z);
            out.add(new Shop(kinds[i], new Vec3(x, ground.apply(x, z), z), yaw));
        }
        return out;
    }

    // ------------------------------------------------------------------ 메시

    private static final Material FLOOR = new Material(0.0f, 0.22f, 0.9f, 0, Material.SURFACE_PLAIN);
    private static final Material WALL = new Material(0.0f, 0.55f, 0.2f, 0, Material.SURFACE_PLAIN);
    private static final Material METAL = new Material(1.0f, 0.3f, 0.2f, 0, Material.SURFACE_PLAIN);
    private static final Material NEON = new Material(0.0f, 0.3f, 0.3f, 5.0f, Material.SURFACE_PLAIN);
    private static final Material NEON_SOFT = new Material(0.0f, 0.3f, 0.3f, 1.6f, Material.SURFACE_PLAIN);

    public static MeshData mesh(Shop shop) {
        MeshBuilder b = new MeshBuilder("shop-" + shop.kind().name().toLowerCase());
        Vec3 glow = shop.kind().glow;
        Vec3 dark = new Vec3(0.035f, 0.038f, 0.045f);
        Vec3 wall = new Vec3(0.09f, 0.095f, 0.11f);
        Vec3 steel = new Vec3(0.55f, 0.56f, 0.58f);

        // 무대: 살짝 높은 원판 + 빛나는 테두리 + 안쪽 가는 고리
        disc(b, shop, 0, 0, Shop.PAD_RADIUS - 0.2f, 0.03f, FLOOR, dark, 64);
        ring(b, shop, 0, 0, Shop.PAD_RADIUS - 0.2f, Shop.PAD_RADIUS, 0.035f, NEON, glow, 96);
        ring(b, shop, 0, 0, 3.0f, 3.06f, 0.035f, NEON_SOFT, glow, 72);
        // 입구 원 + 들어오는 쪽 화살표 3개
        ring(b, shop, 0, Shop.RING_DISTANCE, Shop.RING_RADIUS - 0.18f, Shop.RING_RADIUS, 0.03f, NEON, glow, 72);
        for (int i = 0; i < 3; i++) {
            chevron(b, shop, Shop.RING_DISTANCE + Shop.RING_RADIUS + 1.2f + i * 1.3f, glow);
        }

        // 뒤 곡면 벽 (안쪽 면 + 위 빛 띠 + 아래 빛 띠)
        int seg = 28;
        float r0 = 9.0f, r1 = 9.6f, h = 5.5f;
        for (int i = 0; i < seg; i++) {
            float a0 = (float) Math.toRadians(205 + 130.0 * i / seg);
            float a1 = (float) Math.toRadians(205 + 130.0 * (i + 1) / seg);
            curvedPanel(b, shop, r0, r1, a0, a1, 0, h, WALL, wall);
            curvedPanel(b, shop, r0 - 0.05f, r0, a0, a1, h - 0.55f, h - 0.35f, NEON, glow);
            curvedPanel(b, shop, r0 - 0.05f, r0, a0, a1, 0.25f, 0.33f, NEON_SOFT, glow);
        }

        // 간판 기둥: 어두운 기둥 + 앞면 빛 줄 + 위 간판 판
        box(b, shop, -8.2f, 4.5f, 3.5f, 0.7f, 4.5f, 0.7f, WALL, wall);
        box(b, shop, -8.2f, 4.5f, 4.23f, 0.12f, 4.2f, 0.03f, NEON, glow);
        box(b, shop, -8.2f, 9.6f, 3.5f, 2.6f, 0.7f, 0.35f, WALL, dark);
        box(b, shop, -8.2f, 9.6f, 3.87f, 2.4f, 0.12f, 0.02f, NEON, glow);
        box(b, shop, -8.2f, 9.95f, 3.87f, 2.4f, 0.05f, 0.02f, NEON_SOFT, glow);

        // 조명 기둥 2개 (머리가 무대를 봄)
        for (float sx : new float[]{-6.8f, 6.8f}) {
            box(b, shop, sx, 3f, 6.5f, 0.12f, 3f, 0.12f, METAL, steel);
            box(b, shop, sx * 0.93f, 6.1f, 6.2f, 0.45f, 0.18f, 0.3f, METAL, dark);
            box(b, shop, sx * 0.93f, 5.98f, 6.0f, 0.38f, 0.05f, 0.05f, NEON, new Vec3(1f, 0.95f, 0.85f));
        }
        return b.build();
    }

    private static void disc(MeshBuilder b, Shop s, float cx, float cz, float r, float y, Material m, Vec3 color, int seg) {
        b.material(m);
        Vec3 up = new Vec3(0, 1, 0);
        int center = b.vertex(s.local(cx, y, cz), up, color);
        int first = -1, prev = -1;
        for (int i = 0; i <= seg; i++) {
            double a = 2 * Math.PI * i / seg;
            int v = b.vertex(s.local(cx + (float) Math.sin(a) * r, y, cz + (float) Math.cos(a) * r), up, color);
            if (prev >= 0) {
                Vec3 g = b.position(prev).sub(b.position(center)).cross(b.position(v).sub(b.position(center)));
                if (g.y() >= 0) {
                    b.triangle(center, prev, v);
                } else {
                    b.triangle(center, v, prev);
                }
            } else {
                first = v;
            }
            prev = v;
        }
        // 옆면 (땅까지)
        b.material(m);
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            Vec3 n = s.local((float) Math.sin((a0 + a1) / 2), 0, (float) Math.cos((a0 + a1) / 2)).sub(s.local(0, 0, 0));
            Vec3 p0 = s.local(cx + (float) Math.sin(a0) * r, y, cz + (float) Math.cos(a0) * r);
            Vec3 p1 = s.local(cx + (float) Math.sin(a1) * r, y, cz + (float) Math.cos(a1) * r);
            quadFacing(b, p0.add(new Vec3(0, -y - 0.05f, 0)), p1.add(new Vec3(0, -y - 0.05f, 0)), p1, p0, n, color);
        }
        if (first < 0) {
            throw new IllegalStateException();
        }
    }

    private static void ring(MeshBuilder b, Shop s, float cx, float cz, float r0, float r1, float y, Material m, Vec3 color, int seg) {
        b.material(m);
        Vec3 up = new Vec3(0, 1, 0);
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            Vec3 i0 = s.local(cx + (float) Math.sin(a0) * r0, y, cz + (float) Math.cos(a0) * r0);
            Vec3 o0 = s.local(cx + (float) Math.sin(a0) * r1, y, cz + (float) Math.cos(a0) * r1);
            Vec3 i1 = s.local(cx + (float) Math.sin(a1) * r0, y, cz + (float) Math.cos(a1) * r0);
            Vec3 o1 = s.local(cx + (float) Math.sin(a1) * r1, y, cz + (float) Math.cos(a1) * r1);
            upFace(b, i0, o0, o1, i1, up, color);
        }
    }

    /** 위를 보는 사각형 (감김 방향을 법선에 맞춤) */
    private static void upFace(MeshBuilder b, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 up, Vec3 color) {
        Vec3 n = p1.sub(p0).cross(p2.sub(p0));
        if (n.y() < 0) {
            b.face(p3, p2, p1, p0, up, color);
        } else {
            b.face(p0, p1, p2, p3, up, color);
        }
    }

    /** 바닥 화살표 (입구 쪽을 가리킴) */
    private static void chevron(MeshBuilder b, Shop s, float z, Vec3 color) {
        b.material(NEON_SOFT);
        Vec3 up = new Vec3(0, 1, 0);
        float w = 1.4f, t = 0.35f, d = 0.7f, y = 0.025f;
        // 왼쪽 팔, 오른쪽 팔 (꼭짓점이 무대 쪽 = -z)
        upFace(b, s.local(-w, y, z), s.local(-w + t, y, z), s.local(t / 2, y, z - d), s.local(-t / 2, y, z - d), up, color);
        upFace(b, s.local(w - t, y, z), s.local(w, y, z), s.local(t / 2, y, z - d), s.local(-t / 2, y, z - d), up, color);
    }

    private static void curvedPanel(MeshBuilder b, Shop s, float r0, float r1, float a0, float a1, float y0, float y1,
                                    Material m, Vec3 color) {
        b.material(m);
        Vec3 c = s.local(0, 0, 0);
        Vec3[] in = {s.local((float) Math.sin(a0) * r0, 0, (float) Math.cos(a0) * r0), s.local((float) Math.sin(a1) * r0, 0, (float) Math.cos(a1) * r0)};
        Vec3[] out = {s.local((float) Math.sin(a0) * r1, 0, (float) Math.cos(a0) * r1), s.local((float) Math.sin(a1) * r1, 0, (float) Math.cos(a1) * r1)};
        Vec3 lo = new Vec3(0, y0, 0), hi = new Vec3(0, y1, 0);
        Vec3 mid = in[0].add(in[1]).scale(0.5f);
        Vec3 inward = new Vec3(c.x() - mid.x(), 0, c.z() - mid.z()).normalize();
        quadFacing(b, in[0].add(lo), in[1].add(lo), in[1].add(hi), in[0].add(hi), inward, color);
        quadFacing(b, out[0].add(lo), out[1].add(lo), out[1].add(hi), out[0].add(hi), inward.scale(-1), color);
        quadFacing(b, in[0].add(hi), in[1].add(hi), out[1].add(hi), out[0].add(hi), new Vec3(0, 1, 0), color);
    }

    /** 사각형을 법선 n 쪽에서 볼 때 반시계가 되게 넣는다 */
    private static void quadFacing(MeshBuilder b, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 n, Vec3 color) {
        Vec3 g = p1.sub(p0).cross(p2.sub(p0));
        if (g.dot(n) < 0) {
            b.face(p3, p2, p1, p0, n, color);
        } else {
            b.face(p0, p1, p2, p3, n, color);
        }
    }

    /** 로컬 축 상자 (가운데, 반크기) */
    private static void box(MeshBuilder b, Shop s, float x, float y, float z, float hx, float hy, float hz, Material m, Vec3 color) {
        b.material(m);
        Vec3 o = s.local(0, 0, 0);
        float[][] axes = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (float[] a : axes) {
            Vec3 n = s.local(a[0], a[1], a[2]).sub(o);
            Vec3 c = new Vec3(x + a[0] * hx, y + a[1] * hy, z + a[2] * hz);
            // 면 위의 두 축
            float[] u = a[1] != 0 ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float[] v = a[2] != 0 ? new float[]{1, 0, 0} : (a[0] != 0 ? new float[]{0, 0, 1} : new float[]{0, 0, 1});
            float hu = u[0] != 0 ? hx : hy;
            float hv = v[0] != 0 ? hx : (v[2] != 0 ? hz : hy);
            if (a[1] != 0) {
                hv = hz;
            }
            Vec3[] p = new Vec3[4];
            float[][] sgn = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int k = 0; k < 4; k++) {
                float lx = c.x() + (u[0] * hu * sgn[k][0]) + (v[0] * hv * sgn[k][1]);
                float ly = c.y() + (u[1] * hu * sgn[k][0]) + (v[1] * hv * sgn[k][1]);
                float lz = c.z() + (u[2] * hu * sgn[k][0]) + (v[2] * hv * sgn[k][1]);
                p[k] = s.local(lx, ly, lz);
            }
            quadFacing(b, p[0], p[1], p[2], p[3], n, color);
        }
    }
}
