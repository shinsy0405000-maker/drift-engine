package driftengine.vehicle.visual;

import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;

/** 차 모델용 작은 도형 도구 (상자, 원기둥). 모두 MeshBuilder 에 바로 추가한다 */
final class MeshTools {

    private MeshTools() {
    }

    /** 단위 상자(-1~1)를 transform 으로 옮긴 상자. 면마다 정점을 따로 둬서 모서리가 날카롭다 */
    static void box(MeshBuilder b, Mat4 transform, Material m, Vec3 color) {
        Material old = b.material();
        b.material(m);
        Vec3[] n = {Vec3.UNIT_X, Vec3.UNIT_X.scale(-1), Vec3.UNIT_Y, Vec3.UNIT_Y.scale(-1), Vec3.UNIT_Z, Vec3.UNIT_Z.scale(-1)};
        for (Vec3 normal : n) {
            // 면의 두 접선
            Vec3 t1 = Math.abs(normal.y()) > 0.5f ? Vec3.UNIT_X : Vec3.UNIT_Y;
            Vec3 t2 = normal.cross(t1);
            Vec3 c = normal;
            Vec3[] corners = {c.sub(t1).sub(t2), c.add(t1).sub(t2), c.add(t1).add(t2), c.sub(t1).add(t2)};
            Vec3 wn = transform.transformDirection(normal).normalize();
            int[] idx = new int[4];
            for (int i = 0; i < 4; i++) {
                idx[i] = b.vertex(transform.transformPoint(corners[i]), wn, color);
            }
            orientedQuad(b, idx, wn);
        }
        b.material(old);
    }

    /** 위치 + 반 크기 + X축 기울기(rad) 상자 */
    static void box(MeshBuilder b, Vec3 center, Vec3 half, float pitch, Material m, Vec3 color) {
        Mat4 t = new Mat4().translation(center.x(), center.y(), center.z());
        Mat4 r = new Mat4().rotation((float) Math.sin(pitch / 2), 0, 0, (float) Math.cos(pitch / 2));
        Mat4 s = new Mat4().scaling(half.x(), half.y(), half.z());
        t.mul(t, r);
        t.mul(t, s);
        box(b, t, m, color);
    }

    /** z 방향 원기둥 (배기구 등), 앞뒤 뚜껑 포함 */
    static void cylinderZ(MeshBuilder b, Vec3 center, float radius, float length, int segments, Material m, Vec3 color) {
        Material old = b.material();
        b.material(m);
        float z0 = center.z() - length / 2;
        float z1 = center.z() + length / 2;
        for (int i = 0; i < segments; i++) {
            double a0 = 2 * Math.PI * i / segments;
            double a1 = 2 * Math.PI * (i + 1) / segments;
            Vec3 n0 = new Vec3((float) Math.cos(a0), (float) Math.sin(a0), 0);
            Vec3 n1 = new Vec3((float) Math.cos(a1), (float) Math.sin(a1), 0);
            Vec3 p0 = new Vec3(center.x() + n0.x() * radius, center.y() + n0.y() * radius, z0);
            Vec3 p1 = new Vec3(center.x() + n1.x() * radius, center.y() + n1.y() * radius, z0);
            int a = b.vertex(p0, n0, color);
            int c = b.vertex(new Vec3(p0.x(), p0.y(), z1), n0, color);
            int d = b.vertex(new Vec3(p1.x(), p1.y(), z1), n1, color);
            int e = b.vertex(p1, n1, color);
            orientedQuad(b, new int[]{a, c, d, e}, n0.add(n1).normalize());
        }
        b.material(old);
    }

    /** 사각형을 법선 쪽에서 볼 때 반시계가 되게 넣는다 (꼭짓점 순서가 어느 쪽이든) */
    static void orientedQuad(MeshBuilder b, int[] q, Vec3 outward) {
        Vec3 p0 = b.position(q[0]);
        Vec3 p1 = b.position(q[1]);
        Vec3 p2 = b.position(q[2]);
        Vec3 fn = p1.sub(p0).cross(p2.sub(p0));
        if (fn.length() < 1e-12f) {
            Vec3 p3 = b.position(q[3]);
            fn = p2.sub(p0).cross(p3.sub(p0));
        }
        if (fn.dot(outward) >= 0) {
            b.quad(q[0], q[1], q[2], q[3]);
        } else {
            b.quad(q[0], q[3], q[2], q[1]);
        }
    }

    static void orientedTriangle(MeshBuilder b, int i0, int i1, int i2, Vec3 outward) {
        Vec3 p0 = b.position(i0);
        Vec3 fn = b.position(i1).sub(p0).cross(b.position(i2).sub(p0));
        if (fn.dot(outward) >= 0) {
            b.triangle(i0, i1, i2);
        } else {
            b.triangle(i0, i2, i1);
        }
    }

    static float smoothstep(float a, float b, float x) {
        float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
