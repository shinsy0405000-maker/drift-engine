package driftengine.selftest;

import driftengine.core.EngineConfig;
import driftengine.core.EngineSettings;
import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.mesh.MeshData;
import driftengine.world.Terrain;

import java.util.Random;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.expectThrows;
import static driftengine.selftest.TestRunner.test;

/** 5a 단계(지형, 물리 연결) 중 GPU·물리 엔진 없이 확인할 수 있는 부분 */
final class WorldTests {

    private static final float EPS = 1e-4f;

    private WorldTests() {
    }

    static void run() {
        test("지형: 격자점 높이 = 배열 값, 바깥은 가장자리로 고정", WorldTests::gridHeights);
        test("지형: 칸 안 높이 = 메시 삼각형 평면 위 (Jolt 와 같은 대각선)", WorldTests::interpolationMatchesMesh);
        test("지형 메시: 정점·삼각형 수, 모든 면이 위를 향함", WorldTests::meshShape);
        test("지형 생성: 같은 seed 면 같은 지형, 가운데는 평평", WorldTests::generation);
        test("지형: 잘못된 크기 거부", WorldTests::invalid);
        test("지형 조각: 합치면 통짜 메시와 삼각형 수·높이가 같고 이음매가 맞음", WorldTests::chunks);
        test("쿼터니언 회전 = Y 축 회전 행렬 (90도)", WorldTests::quaternion);
        test("실행 인자 --scene", WorldTests::sceneArg);
    }

    private static Terrain sample() {
        float[] h = new float[16];
        Random r = new Random(3);
        for (int i = 0; i < h.length; i++) {
            h[i] = r.nextFloat() * 5;
        }
        return new Terrain(4, 2f, -3f, -3f, h);
    }

    private static void gridHeights() {
        Terrain t = sample();
        for (int iz = 0; iz < 4; iz++) {
            for (int ix = 0; ix < 4; ix++) {
                Vec3 p = t.position(ix, iz);
                check(Math.abs(t.height(p.x(), p.z()) - t.heightAtGrid(ix, iz)) < EPS, "격자점 " + ix + "," + iz);
            }
        }
        check(Math.abs(t.height(-100, -100) - t.heightAtGrid(0, 0)) < EPS, "바깥 모서리");
        check(Math.abs(t.height(100, 100) - t.heightAtGrid(3, 3)) < EPS, "반대쪽 모서리");
    }

    /** 무작위 점의 height() 가, 그 점을 포함하는 메시 삼각형의 평면 높이와 같아야 한다. */
    private static void interpolationMatchesMesh() {
        Terrain t = sample();
        MeshData mesh = t.toMesh();
        Random r = new Random(11);
        int checked = 0;
        for (int n = 0; n < 500; n++) {
            float x = -3f + r.nextFloat() * t.size();
            float z = -3f + r.nextFloat() * t.size();
            boolean found = false;
            for (int i = 0; i < mesh.indexCount() && !found; i += 3) {
                Vec3 a = mesh.position(mesh.indices()[i]);
                Vec3 b = mesh.position(mesh.indices()[i + 1]);
                Vec3 c = mesh.position(mesh.indices()[i + 2]);
                float[] w = barycentricXZ(x, z, a, b, c);
                if (w != null) {
                    float hy = w[0] * a.y() + w[1] * b.y() + w[2] * c.y();
                    check(Math.abs(hy - t.height(x, z)) < 1e-3f, "점 (" + x + ", " + z + "): " + hy + " vs " + t.height(x, z));
                    found = true;
                    checked++;
                }
            }
            check(found, "점이 어떤 삼각형에도 없음: " + x + ", " + z);
        }
        check(checked == 500, "검사 수 " + checked);
    }

    private static float[] barycentricXZ(float x, float z, Vec3 a, Vec3 b, Vec3 c) {
        float d = (b.z() - c.z()) * (a.x() - c.x()) + (c.x() - b.x()) * (a.z() - c.z());
        float w0 = ((b.z() - c.z()) * (x - c.x()) + (c.x() - b.x()) * (z - c.z())) / d;
        float w1 = ((c.z() - a.z()) * (x - c.x()) + (a.x() - c.x()) * (z - c.z())) / d;
        float w2 = 1 - w0 - w1;
        float tol = -1e-5f;
        return (w0 >= tol && w1 >= tol && w2 >= tol) ? new float[]{w0, w1, w2} : null;
    }

    private static void meshShape() {
        Terrain t = sample();
        MeshData mesh = t.toMesh();
        check(mesh.vertexCount() == 16, "정점 16개: " + mesh.vertexCount());
        check(mesh.triangleCount() == 3 * 3 * 2, "삼각형 18개: " + mesh.triangleCount());
        for (int i = 0; i < mesh.indexCount(); i += 3) {
            Vec3 a = mesh.position(mesh.indices()[i]);
            Vec3 b = mesh.position(mesh.indices()[i + 1]);
            Vec3 c = mesh.position(mesh.indices()[i + 2]);
            Vec3 n = b.sub(a).cross(c.sub(a));
            check(n.y() > 0, "삼각형 " + i / 3 + " 이 아래를 향함 (뒷면 제거로 안 보이게 됨)");
        }
    }

    private static void generation() {
        Terrain a = Terrain.generate(64, 1f, 42, 10f, 8f);
        Terrain b = Terrain.generate(64, 1f, 42, 10f, 8f);
        Terrain c = Terrain.generate(64, 1f, 43, 10f, 8f);
        boolean same = true;
        boolean differs = false;
        for (int iz = 0; iz < 64; iz++) {
            for (int ix = 0; ix < 64; ix++) {
                same &= a.heightAtGrid(ix, iz) == b.heightAtGrid(ix, iz);
                differs |= a.heightAtGrid(ix, iz) != c.heightAtGrid(ix, iz);
            }
        }
        check(same, "같은 seed → 같은 지형");
        check(differs, "다른 seed → 다른 지형");
        check(Math.abs(a.height(0, 0)) < EPS && Math.abs(a.height(5, -5)) < EPS, "가운데 평지 높이 0");
        float max = 0;
        for (int iz = 0; iz < 64; iz++) {
            for (int ix = 0; ix < 64; ix++) {
                max = Math.max(max, a.heightAtGrid(ix, iz));
            }
        }
        check(max > 0.5f && max <= 8f + EPS, "언덕 높이 범위: " + max);
    }

    private static void chunks() {
        float[] h = new float[37 * 37];
        Random r = new Random(5);
        for (int i = 0; i < h.length; i++) {
            h[i] = r.nextFloat() * 4;
        }
        Terrain t = new Terrain(37, 1.5f, -27f, -27f, h);
        MeshData full = t.toMesh(10);
        java.util.List<MeshData> parts = t.toMeshChunks(10, 8);
        check(parts.size() == 25, "36칸 / 8 → 5×5 조각: " + parts.size());
        int tris = 0;
        for (MeshData m : parts) {
            tris += m.triangleCount();
            for (int i = 0; i < m.vertexCount(); i++) {
                Vec3 p = m.position(i);
                check(Math.abs(t.height(p.x(), p.z()) - p.y()) < EPS, m.name() + " 정점 높이");
            }
        }
        check(tris == full.triangleCount(), "삼각형 수 " + tris + " = " + full.triangleCount());
        // 이웃 조각의 맞닿은 정점은 같은 위치·법선
        MeshData a = parts.get(0);
        MeshData b = parts.get(1);
        int shared = 0;
        for (int i = 0; i < a.vertexCount(); i++) {
            for (int j = 0; j < b.vertexCount(); j++) {
                if (a.position(i).sub(b.position(j)).length() < 1e-5f) {
                    shared++;
                    check(a.normal(i).sub(b.normal(j)).length() < 1e-5f, "이음매 법선");
                }
            }
        }
        check(shared == 9, "맞닿은 정점 9개: " + shared);
    }

    private static void invalid() {
        expectThrows(IllegalArgumentException.class, () -> new Terrain(3, 1, 0, 0, new float[9]), "격자 3");
        expectThrows(IllegalArgumentException.class, () -> new Terrain(4, 1, 0, 0, new float[15]), "개수 불일치");
        expectThrows(IllegalArgumentException.class, () -> new Terrain(4, 0, 0, 0, new float[16]), "간격 0");
    }

    private static void quaternion() {
        float s = (float) Math.sin(Math.PI / 4);
        float c = (float) Math.cos(Math.PI / 4);
        Mat4 q = new Mat4().rotation(0, s, 0, c);
        Mat4 y = new Mat4().rotationY((float) (Math.PI / 2));
        check(q.approxEquals(y, 1e-5f), "Y 90도: " + q + " vs " + y);
        check(new Mat4().rotation(0, 0, 0, 2).approxEquals(new Mat4(), 1e-6f), "길이 2 단위 쿼터니언도 정규화");
    }

    private static void sceneArg() {
        EngineSettings d = EngineSettings.defaults();
        check(EngineConfig.fromArgs(new String[0], d).scene().equals("terrain"), "기본 terrain");
        check(EngineConfig.fromArgs(new String[]{"--scene=demo"}, d).scene().equals("demo"), "demo");
        expectThrows(IllegalArgumentException.class,
                () -> EngineConfig.fromArgs(new String[]{"--scene=city"}, d), "모르는 장면");
    }
}
