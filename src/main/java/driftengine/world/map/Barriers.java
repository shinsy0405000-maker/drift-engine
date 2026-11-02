package driftengine.world.map;

import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.MeshData;
import driftengine.world.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * 도로 옆 막이: 산길 가드레일 (기둥 + W 빔), 서킷 콘크리트 벽. 보이는 메시 + 물리 상자.
 * 다른 길 위(헤어핀 반대편 등)에 걸리는 마디는 빼고 만든다.
 */
public final class Barriers {

    /** 물리 상자 하나: 가운데, 반 크기, yaw ({x, y, z, hx, hy, hz, yaw}) */
    public record Result(List<MeshData> meshes, List<float[]> boxes) {
    }

    static final float SEGMENT = 4f;
    static final Vec3 STEEL = new Vec3(0.55f, 0.56f, 0.58f);
    static final Vec3 CONCRETE = new Vec3(0.52f, 0.52f, 0.5f);

    private Barriers() {
    }

    public static Result build(Road road, RoadNet net, Terrain t) {
        List<MeshData> meshes = new ArrayList<>();
        List<float[]> boxes = new ArrayList<>();
        RoadPath p = road.path();
        float offset = road.rails() ? road.halfWidth() + 0.9f : road.wall();
        if (offset <= 0) {
            return new Result(meshes, boxes);
        }
        boolean rail = road.rails();
        int segs = (int) Math.floor(p.length() / SEGMENT);
        int perPiece = Math.max(1, (int) (RoadMesh.PIECE / SEGMENT));
        MeshBuilder b = null;
        int piece = 0;
        for (int i = 0; i < segs; i++) {
            if (i % perPiece == 0) {
                if (b != null && b.vertexCount() > 0) {
                    meshes.add(b.build());
                }
                b = new MeshBuilder("barrier-" + road.name() + "-" + piece++);
            }
            float s0 = i * SEGMENT, s1 = s0 + SEGMENT;
            if (!p.closed() && (s0 < 6 || s1 > p.length() - 6)) {
                continue; // 산길 출발·도착 광장
            }
            for (int side = -1; side <= 1; side += 2) {
                float[] a = p.at(s0), c = p.at(s1);
                float ax = a[0] + a[3] * offset * side, az = a[1] - a[2] * offset * side;
                float cx = c[0] + c[3] * offset * side, cz = c[1] - c[2] * offset * side;
                float mx = (ax + cx) / 2, mz = (az + cz) / 2;
                RoadNet.Hit h = net.nearest(mx, mz, offset + 2);
                if (h != null && h.distance() < offset - 0.6f) {
                    continue; // 다른 길 위
                }
                float dx = cx - ax, dz = cz - az;
                float len = (float) Math.hypot(dx, dz);
                if (len < 0.5f) {
                    continue;
                }
                float yaw = (float) Math.atan2(-dz, dx);
                float gm = t.height(mx, mz);
                if (rail) {
                    // 기둥 + 빔 (빔은 두 끝 높이를 따라 기울지 않고 가운데 높이)
                    Props.box(b, ax, t.height(ax, az) + 0.35f, az, 0.07f, 0.45f, 0.07f, yaw, STEEL.scale(0.8f), Material.GALVANIZED);
                    beam(b, ax, t.height(ax, az), az, cx, t.height(cx, cz), cz, side, a, c);
                    boxes.add(new float[]{mx, gm + 0.3f, mz, len / 2 + 0.1f, 0.65f, 0.15f, yaw});
                } else {
                    wall(b, ax, t.height(ax, az), az, cx, t.height(cx, cz), cz, ((i & 1) == 0));
                    boxes.add(new float[]{mx, gm + 0.2f, mz, len / 2 + 0.1f, 0.9f, 0.25f, yaw});
                }
            }
        }
        if (b != null && b.vertexCount() > 0) {
            meshes.add(b.build());
        }
        return new Result(meshes, boxes);
    }

    /** W 빔: 두 기둥 사이 0.45 ~ 0.75m 높이 판, 길쪽으로 살짝 */
    private static void beam(MeshBuilder b, float ax, float ay, float az, float cx, float cy, float cz, int side,
                             float[] ta, float[] tc) {
        b.material(Material.GALVANIZED);
        // 길 쪽 법선 (도로 중심으로)
        Vec3 na = new Vec3(-ta[3] * side, 0, ta[2] * side);
        Vec3 nc = new Vec3(-tc[3] * side, 0, tc[2] * side);
        float in = 0.12f;
        Vec3 a0 = new Vec3(ax + na.x() * in, ay + 0.45f, az + na.z() * in);
        Vec3 a1 = new Vec3(ax + na.x() * in, ay + 0.78f, az + na.z() * in);
        Vec3 c0 = new Vec3(cx + nc.x() * in, cy + 0.45f, cz + nc.z() * in);
        Vec3 c1 = new Vec3(cx + nc.x() * in, cy + 0.78f, cz + nc.z() * in);
        int p0 = b.vertex(a0, na, STEEL), p1 = b.vertex(a1, na, STEEL), p2 = b.vertex(c1, nc, STEEL), p3 = b.vertex(c0, nc, STEEL);
        Props.tri(b, p0, p1, p2);
        Props.tri(b, p0, p2, p3);
        // 가운데 홈 (어두운 줄)
        Vec3 dark = STEEL.scale(0.55f);
        Vec3 m0 = new Vec3(ax + na.x() * (in - 0.03f), ay + 0.6f, az + na.z() * (in - 0.03f));
        Vec3 m1 = new Vec3(ax + na.x() * (in - 0.03f), ay + 0.63f, az + na.z() * (in - 0.03f));
        Vec3 n0 = new Vec3(cx + nc.x() * (in - 0.03f), cy + 0.6f, cz + nc.z() * (in - 0.03f));
        Vec3 n1 = new Vec3(cx + nc.x() * (in - 0.03f), cy + 0.63f, cz + nc.z() * (in - 0.03f));
        Props.quad(b, m0, m1, n1, n0, na, dark);
    }

    /** 콘크리트 벽 (높이 1.0m, 두께 0.4m), 마디마다 위 띠를 빨강/흰 번갈아 */
    private static void wall(MeshBuilder b, float ax, float ay, float az, float cx, float cy, float cz, boolean red) {
        b.material(Material.CONCRETE);
        float dx = cx - ax, dz = cz - az;
        float len = (float) Math.hypot(dx, dz);
        Vec3 n = new Vec3(-dz / len, 0, dx / len);
        float h = 0.2f;
        Vec3 off = n.scale(h);
        float top = 1.0f;
        Vec3 a = new Vec3(ax, ay - 0.3f, az), c = new Vec3(cx, cy - 0.3f, cz);
        Vec3 at = new Vec3(ax, ay + top, az), ct = new Vec3(cx, cy + top, cz);
        Props.quad(b, a.add(off), c.add(off), ct.add(off), at.add(off), n, CONCRETE);
        Props.quad(b, a.sub(off), c.sub(off), ct.sub(off), at.sub(off), n.scale(-1), CONCRETE);
        Props.quad(b, at.add(off), ct.add(off), ct.sub(off), at.sub(off), Vec3.UNIT_Y, CONCRETE.scale(1.1f));
        Vec3 stripe = red ? RoadMesh.RED : RoadMesh.WHITE;
        Vec3 up = new Vec3(0, 0.25f, 0);
        Vec3 o2 = n.scale(h + 0.005f);
        Props.quad(b, at.sub(up).add(o2), ct.sub(up).add(o2), ct.add(o2), at.add(o2), n, stripe);
        Props.quad(b, at.sub(up).sub(o2), ct.sub(up).sub(o2), ct.sub(o2), at.sub(o2), n.scale(-1), stripe);
    }
}
