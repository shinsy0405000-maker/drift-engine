package driftengine.vehicle.visual;

import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.MeshData;

/**
 * 바퀴 메시 (타이어 + 휠). 바퀴 중심이 원점, 차축 = X. outward = +1 이면 휠 바깥면이 +X (오른쪽 바퀴), -1 이면 -X.
 * 굴러가는 회전은 그리는 쪽에서 X 축 회전으로 준다.
 */
public final class WheelBuilder {

    private static final Vec3 RUBBER = new Vec3(0.022f, 0.022f, 0.024f);
    private static final Vec3 SIDEWALL = new Vec3(0.03f, 0.03f, 0.032f);
    private static final Vec3 DARK = new Vec3(0.05f, 0.05f, 0.055f);

    private WheelBuilder() {
    }

    public static MeshData build(float radius, float width, int rimStyle, Vec3 rimColor, float outward) {
        MeshBuilder b = new MeshBuilder("wheel");
        float rimR = radius * 0.68f;
        tire(b, radius, rimR, width, outward);
        rim(b, rimR, width, rimStyle, rimColor, outward);
        return b.build();
    }

    /** 타이어: 둥근 어깨 단면을 X 축으로 돌린 회전체 */
    private static void tire(MeshBuilder b, float r, float rimR, float w, float o) {
        float hw = w / 2;
        // (x, 반지름) 단면: 안쪽 림 → 사이드월 → 어깨 → 트레드 → 반대쪽
        float[][] prof = {
                {-hw + 0.01f, rimR}, {-hw - 0.004f, rimR + (r - rimR) * 0.45f}, {-hw + 0.006f, r - 0.018f},
                {-hw + 0.03f, r - 0.002f}, {0, r}, {hw - 0.03f, r - 0.002f}, {hw - 0.006f, r - 0.018f},
                {hw + 0.004f, rimR + (r - rimR) * 0.45f}, {hw - 0.01f, rimR}};
        int seg = 48;
        int rows = prof.length;
        int[][] idx = new int[seg + 1][rows];
        for (int i = 0; i <= seg; i++) {
            double a = 2 * Math.PI * i / seg;
            float ca = (float) Math.cos(a);
            float sa = (float) Math.sin(a);
            for (int j = 0; j < rows; j++) {
                float x = prof[j][0] * o;
                float rr = prof[j][1];
                // 단면 접선으로 법선 (단면 평면 안에서 접선에 수직, 바깥쪽)
                float[] prev = prof[Math.max(0, j - 1)];
                float[] next = prof[Math.min(rows - 1, j + 1)];
                float tx = (next[0] - prev[0]) * o;
                float tr = next[1] - prev[1];
                float nx = tr * o * o;
                float nr = -tx * o;
                // 바깥 방향 판정: 트레드는 반지름 쪽, 옆면은 x 바깥쪽
                Vec3 n = new Vec3(nx, nr * ca, nr * sa);
                Vec3 pos = new Vec3(x, rr * ca, rr * sa);
                Vec3 radial = new Vec3(x * 2, rr * ca, rr * sa);
                if (n.dot(radial) < 0) {
                    n = n.scale(-1);
                }
                boolean tread = j >= 3 && j <= 5;
                b.material(Material.RUBBER);
                idx[i][j] = b.vertex(pos, n.normalize(), tread ? RUBBER : SIDEWALL);
            }
        }
        for (int i = 0; i < seg; i++) {
            for (int j = 0; j + 1 < rows; j++) {
                Vec3 c = b.position(idx[i][j]).add(b.position(idx[i + 1][j + 1])).scale(0.5f);
                Vec3 out = new Vec3(c.x() * 3, c.y(), c.z());
                MeshTools.orientedQuad(b, new int[]{idx[i][j], idx[i + 1][j], idx[i + 1][j + 1], idx[i][j + 1]}, out);
            }
        }
    }

    private static void rim(MeshBuilder b, float rimR, float w, int style, Vec3 color, float o) {
        float hw = w / 2;
        float face = (hw - 0.025f) * o;   // 휠 바깥면 x
        Material metal = style == 3 ? new Material(0.9f, 0.35f, 0.6f, 0, Material.SURFACE_PLAIN) : Material.ALUMINIUM;
        // 림 안쪽 통 (스포크 사이로 보이는 어두운 면)
        int seg = 40;
        b.material(Material.PLASTIC_BLACK);
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg;
            double a1 = 2 * Math.PI * (i + 1) / seg;
            Vec3 n0 = new Vec3(0, (float) -Math.cos(a0), (float) -Math.sin(a0));
            Vec3 n1 = new Vec3(0, (float) -Math.cos(a1), (float) -Math.sin(a1));
            float r = rimR * 0.97f;
            int p0 = b.vertex(new Vec3(-hw * o, r * (float) Math.cos(a0), r * (float) Math.sin(a0)), n0, DARK);
            int p1 = b.vertex(new Vec3(face, r * (float) Math.cos(a0), r * (float) Math.sin(a0)), n0, DARK);
            int p2 = b.vertex(new Vec3(face, r * (float) Math.cos(a1), r * (float) Math.sin(a1)), n1, DARK);
            int p3 = b.vertex(new Vec3(-hw * o, r * (float) Math.cos(a1), r * (float) Math.sin(a1)), n1, DARK);
            MeshTools.orientedQuad(b, new int[]{p0, p1, p2, p3}, n0.add(n1));
            // 안쪽 원판 (브레이크 디스크 자리, 어둡게)
            Vec3 axis = new Vec3(o, 0, 0);
            int c0 = b.vertex(new Vec3(face - 0.07f * o, 0, 0), axis, DARK);
            int c1 = b.vertex(new Vec3(face - 0.07f * o, r * (float) Math.cos(a0), r * (float) Math.sin(a0)), axis, DARK);
            int c2 = b.vertex(new Vec3(face - 0.07f * o, r * (float) Math.cos(a1), r * (float) Math.sin(a1)), axis, DARK);
            MeshTools.orientedTriangle(b, c0, c1, c2, axis);
        }
        // 림 테두리 (바깥 고리)
        b.material(metal);
        Vec3 axis = new Vec3(o, 0, 0);
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg;
            double a1 = 2 * Math.PI * (i + 1) / seg;
            float r0 = rimR * 0.86f;
            float r1 = rimR * 1.0f;
            int p0 = b.vertex(new Vec3(face, r0 * (float) Math.cos(a0), r0 * (float) Math.sin(a0)), axis, color);
            int p1 = b.vertex(new Vec3(face + 0.008f * o, r1 * (float) Math.cos(a0), r1 * (float) Math.sin(a0)), axis, color);
            int p2 = b.vertex(new Vec3(face + 0.008f * o, r1 * (float) Math.cos(a1), r1 * (float) Math.sin(a1)), axis, color);
            int p3 = b.vertex(new Vec3(face, r0 * (float) Math.cos(a1), r0 * (float) Math.sin(a1)), axis, color);
            MeshTools.orientedQuad(b, new int[]{p0, p1, p2, p3}, axis);
        }
        // 가운데 허브
        MeshTools.box(b, new Vec3(face + 0.005f * o, 0, 0), new Vec3(0.015f, rimR * 0.18f, rimR * 0.18f), 0, metal, color);
        if (style == 3) {
            // 디스크 휠: 막힌 원판 + 작은 구멍 무늬(어두운 칸)
            for (int i = 0; i < seg; i++) {
                double a0 = 2 * Math.PI * i / seg;
                double a1 = 2 * Math.PI * (i + 1) / seg;
                float r = rimR * 0.87f;
                boolean slot = (i % 8) < 2;
                b.material(slot ? Material.PLASTIC_BLACK : metal);
                Vec3 col = slot ? DARK : color;
                int c0 = b.vertex(new Vec3(face + 0.004f * o, 0, 0), axis, col);
                int c1 = b.vertex(new Vec3(face, r * (float) Math.cos(a0), r * (float) Math.sin(a0)), axis, col);
                int c2 = b.vertex(new Vec3(face, r * (float) Math.cos(a1), r * (float) Math.sin(a1)), axis, col);
                MeshTools.orientedTriangle(b, c0, c1, c2, axis);
            }
            return;
        }
        int spokes = switch (style) {
            case 1 -> 10;
            case 2 -> 14;
            case 4 -> 6;
            default -> 5;
        };
        float spokeHalfW = switch (style) {
            case 1 -> 0.010f;
            case 2 -> 0.006f;
            case 4 -> 0.020f;
            default -> 0.024f;
        };
        for (int s = 0; s < spokes; s++) {
            float a = (float) (2 * Math.PI * s / spokes);
            float len = rimR * 0.78f;
            float mid = rimR * 0.12f + len / 2;
            Vec3 center = new Vec3(face - 0.004f * o, (float) Math.cos(a) * mid, (float) Math.sin(a) * mid);
            driftengine.math.Mat4 t = new driftengine.math.Mat4().translation(center.x(), center.y(), center.z());
            // X 축으로 a 만큼 회전: 스포크 길이 방향(+Y)이 (cos a, sin a) 를 향함
            float half = a / 2;
            driftengine.math.Mat4 r = new driftengine.math.Mat4().rotation((float) Math.sin(half), 0, 0, (float) Math.cos(half));
            driftengine.math.Mat4 sc = new driftengine.math.Mat4().scaling(0.012f, len / 2, spokeHalfW);
            t.mul(t, r);
            t.mul(t, sc);
            MeshTools.box(b, t, metal, color);
            if (style == 2) {
                // 메시: 엇갈린 두 번째 스포크
                float half2 = (a + (float) Math.PI / spokes) / 2;
                driftengine.math.Mat4 t2 = new driftengine.math.Mat4().translation(center.x(), 0, 0);
                driftengine.math.Mat4 r2 = new driftengine.math.Mat4().rotation((float) Math.sin(half2), 0, 0, (float) Math.cos(half2));
                driftengine.math.Mat4 off = new driftengine.math.Mat4().translation(0, mid, 0);
                t2.mul(t2, r2);
                t2.mul(t2, off);
                t2.mul(t2, sc);
                MeshTools.box(b, t2, metal, color);
            }
        }
    }
}
