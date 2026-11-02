package driftengine.world.map;

import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;

/**
 * 맵 소품 도형 (상자, 원기둥, 원뿔)을 MeshBuilder 에 바로 쌓는다. 여러 개를 한 메시로 묶어 그리기 호출을 줄인다.
 */
public final class Props {

    private Props() {
    }

    /** 삼각형: 정점 a 의 법선 쪽에서 볼 때 반시계가 되도록 순서를 맞춰 넣는다 (뒷면은 셰이더가 법선을 뒤집음) */
    public static void tri(MeshBuilder b, int a, int c, int d) {
        float[] v = b.peekVertices();
        int fa = a * 14, fc = c * 14, fd = d * 14;
        float ux = v[fc] - v[fa], uy = v[fc + 1] - v[fa + 1], uz = v[fc + 2] - v[fa + 2];
        float wx = v[fd] - v[fa], wy = v[fd + 1] - v[fa + 1], wz = v[fd + 2] - v[fa + 2];
        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        if (nx * v[fa + 3] + ny * v[fa + 4] + nz * v[fa + 5] >= 0) {
            b.triangle(a, c, d);
        } else {
            b.triangle(a, d, c);
        }
    }

    /** 사각형 p0→p1→p2→p3 (같은 법선·색), 방향은 자동 */
    public static void quad(MeshBuilder b, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 n, Vec3 color) {
        int a = b.vertex(p0, n, color);
        int c = b.vertex(p1, n, color);
        int d = b.vertex(p2, n, color);
        int e = b.vertex(p3, n, color);
        tri(b, a, c, d);
        tri(b, a, d, e);
    }

    /**
     * 상자. (cx, cy, cz) = 바닥 가운데가 아니라 상자 가운데, yaw = Y 축 회전 (라디안, 로컬 x → (cos, 0, −sin)).
     */
    public static void box(MeshBuilder b, float cx, float cy, float cz, float hx, float hy, float hz, float yaw,
                           Vec3 color, Material m) {
        b.material(m);
        float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
        Vec3 ax = new Vec3(c, 0, -s);
        Vec3 az = new Vec3(s, 0, c);
        Vec3 ay = Vec3.UNIT_Y;
        Vec3 o = new Vec3(cx, cy, cz);
        Vec3[] dirs = {ax, ax.scale(-1), ay, ay.scale(-1), az, az.scale(-1)};
        float[] half = {hx, hx, hy, hy, hz, hz};
        for (int f = 0; f < 6; f++) {
            Vec3 n = dirs[f];
            // 면의 두 접선
            Vec3 u = f < 2 ? az : (f < 4 ? ax : ax);
            Vec3 v = f < 2 ? ay : (f < 4 ? az : ay);
            float hu = f < 2 ? hz : hx;
            float hv = f < 2 ? hy : (f < 4 ? hz : hy);
            Vec3 center = o.add(n.scale(half[f]));
            Vec3 du = u.scale(hu), dv = v.scale(hv);
            quad(b, center.sub(du).sub(dv), center.add(du).sub(dv), center.add(du).add(dv), center.sub(du).add(dv), n, color);
        }
    }

    /** 세로 원기둥 (바닥 y0 → 위 y1), seg 각 */
    public static void cylinder(MeshBuilder b, float x, float y0, float z, float r, float y1, int seg, Vec3 color, Material m) {
        b.material(m);
        int first = -1;
        for (int i = 0; i <= seg; i++) {
            double a = 2 * Math.PI * i / seg;
            float cx = (float) Math.cos(a), sz = (float) Math.sin(a);
            Vec3 n = new Vec3(cx, 0, sz);
            int lo = b.vertex(new Vec3(x + cx * r, y0, z + sz * r), n, color);
            int hi = b.vertex(new Vec3(x + cx * r, y1, z + sz * r), n, color);
            if (first >= 0) {
                tri(b, first, lo, hi);
                tri(b, first, hi, first + 1);
            }
            first = lo;
        }
    }

    /** 원뿔 (나무 잎): 바닥 반지름 r, 바닥 y0, 꼭대기 y1 */
    public static void cone(MeshBuilder b, float x, float y0, float z, float r, float y1, int seg, Vec3 color, Material m) {
        b.material(m);
        Vec3 tip = new Vec3(x, y1, z);
        float h = y1 - y0;
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg, am = (a0 + a1) / 2;
            Vec3 n = new Vec3((float) Math.cos(am) * h, r, (float) Math.sin(am) * h).normalize();
            int p0 = b.vertex(new Vec3(x + (float) Math.cos(a0) * r, y0, z + (float) Math.sin(a0) * r), n, color);
            int p1 = b.vertex(new Vec3(x + (float) Math.cos(a1) * r, y0, z + (float) Math.sin(a1) * r), n, color);
            int t = b.vertex(tip, n, color.scale(1.15f));
            tri(b, p0, t, p1);
        }
    }

    /** 침엽수 한 그루 (줄기 + 잎 원뿔 2단). size 1 = 약 9m */
    public static void pine(MeshBuilder b, float x, float y, float z, float size, Vec3 leaf) {
        Vec3 trunk = new Vec3(0.18f, 0.12f, 0.08f);
        cylinder(b, x, y - 0.3f, z, 0.22f * size, y + 2.2f * size, 5, trunk, Material.GROUND);
        cone(b, x, y + 1.6f * size, z, 2.2f * size, y + 6.5f * size, 7, leaf, Material.FOLIAGE);
        cone(b, x, y + 4.3f * size, z, 1.5f * size, y + 9.0f * size, 7, leaf.scale(1.1f), Material.FOLIAGE);
    }

    /** 활엽수 (줄기 + 상자 두 개를 비튼 덩어리) */
    public static void broadleaf(MeshBuilder b, float x, float y, float z, float size, Vec3 leaf, float yaw) {
        Vec3 trunk = new Vec3(0.2f, 0.14f, 0.09f);
        cylinder(b, x, y - 0.3f, z, 0.25f * size, y + 2.8f * size, 5, trunk, Material.GROUND);
        box(b, x, y + 4.6f * size, z, 2.3f * size, 1.9f * size, 2.3f * size, yaw, leaf, Material.FOLIAGE);
        box(b, x, y + 5.4f * size, z, 1.7f * size, 1.6f * size, 1.7f * size, yaw + 0.7f, leaf.scale(1.12f), Material.FOLIAGE);
    }
}
