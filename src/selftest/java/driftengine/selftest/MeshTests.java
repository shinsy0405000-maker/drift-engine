package driftengine.selftest;

import driftengine.math.Vec3;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.MeshData;
import driftengine.scene.mesh.Primitives;
import driftengine.scene.mesh.VertexFormat;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.expectThrows;
import static driftengine.selftest.TestRunner.test;

/** 메시 생성 테스트. 특히 "삼각형 감기 방향이 법선과 맞는지"를 모든 삼각형에 대해 확인한다. */
final class MeshTests {

    private static final float EPS = 1e-5f;
    private static final Vec3 RED = new Vec3(1, 0, 0);
    private static final Vec3 GRAY = new Vec3(0.3f, 0.3f, 0.3f);
    private static final Vec3 WHITE = new Vec3(0.8f, 0.8f, 0.8f);

    private MeshTests() {
    }

    static void run() {
        test("정점 형식: 56바이트, 오프셋 0/12/24/36/52", MeshTests::vertexFormat);
        test("정육면체: 정점 24, 삼각형 12, 크기", MeshTests::cubeCounts);
        test("정육면체: 모든 삼각형이 바깥을 향함(반시계)", MeshTests::cubeWinding);
        test("정육면체: 법선은 길이 1, 면 위에 있음", MeshTests::cubeNormals);
        test("바닥: 칸 수만큼 정점/삼각형, 크기, 높이 0", MeshTests::groundCounts);
        test("바닥: 모든 삼각형이 위를 향함", MeshTests::groundWinding);
        test("바닥: 바둑판 무늬 색 교차", MeshTests::groundChecker);
        test("빌더: 배열이 자동으로 늘어남", MeshTests::builderGrows);
        test("잘못된 인덱스/크기 거부", MeshTests::invalid);
        test("만든 뒤 원본 배열을 바꿔도 메시는 그대로", MeshTests::defensiveCopy);
    }

    private static void vertexFormat() {
        check(VertexFormat.STRIDE_BYTES == 56, "stride " + VertexFormat.STRIDE_BYTES);
        check(VertexFormat.POSITION_OFFSET_BYTES == 0 && VertexFormat.NORMAL_OFFSET_BYTES == 12
                && VertexFormat.COLOR_OFFSET_BYTES == 24 && VertexFormat.MATERIAL_OFFSET_BYTES == 36
                && VertexFormat.SURFACE_OFFSET_BYTES == 52, "오프셋");
    }

    private static void cubeCounts() {
        MeshData cube = Primitives.cube(2, RED);
        check(cube.vertexCount() == 24, "정점 " + cube.vertexCount());
        check(cube.triangleCount() == 12, "삼각형 " + cube.triangleCount());
        check(cube.boundsMin().approxEquals(new Vec3(-1, -1, -1), EPS), "최소 " + cube.boundsMin());
        check(cube.boundsMax().approxEquals(new Vec3(1, 1, 1), EPS), "최대 " + cube.boundsMax());
        check(cube.vertexBytes() == 24L * VertexFormat.STRIDE_BYTES, "정점 바이트");
    }

    /** 삼각형 (a,b,c)의 (b-a)×(c-a) 가 정점 법선과 같은 방향이면 바깥에서 볼 때 반시계 */
    private static int wrongWindingCount(MeshData mesh) {
        int wrong = 0;
        int[] idx = mesh.indices();
        for (int t = 0; t < idx.length; t += 3) {
            Vec3 a = mesh.position(idx[t]);
            Vec3 b = mesh.position(idx[t + 1]);
            Vec3 c = mesh.position(idx[t + 2]);
            Vec3 faceNormal = b.sub(a).cross(c.sub(a));
            if (faceNormal.dot(mesh.normal(idx[t])) <= 0) {
                wrong++;
            }
        }
        return wrong;
    }

    private static void cubeWinding() {
        MeshData cube = Primitives.cube(1, RED);
        check(wrongWindingCount(cube) == 0, "뒤집힌 삼각형 " + wrongWindingCount(cube) + "개");
        // 바깥을 향하는지: 각 면 법선이 중심(원점)에서 멀어지는 방향
        for (int i = 0; i < cube.vertexCount(); i++) {
            check(cube.position(i).dot(cube.normal(i)) > 0, "정점 " + i + " 법선이 안쪽을 향함");
        }
    }

    private static void cubeNormals() {
        MeshData cube = Primitives.cube(3, RED);
        for (int i = 0; i < cube.vertexCount(); i++) {
            Vec3 n = cube.normal(i);
            check(Math.abs(n.length() - 1) < EPS, "정점 " + i + " 법선 길이 " + n.length());
            // 면 위의 점은 법선 방향 좌표가 반 변(1.5)
            check(Math.abs(cube.position(i).dot(n) - 1.5f) < EPS, "정점 " + i + " 이 면 위에 없음");
        }
    }

    private static void groundCounts() {
        MeshData g = Primitives.checkerGround(100, 10, GRAY, WHITE);
        check(g.vertexCount() == 10 * 10 * 4, "정점 " + g.vertexCount());
        check(g.triangleCount() == 10 * 10 * 2, "삼각형 " + g.triangleCount());
        check(g.boundsMin().approxEquals(new Vec3(-50, 0, -50), 1e-3f), "최소 " + g.boundsMin());
        check(g.boundsMax().approxEquals(new Vec3(50, 0, 50), 1e-3f), "최대 " + g.boundsMax());
    }

    private static void groundWinding() {
        MeshData g = Primitives.checkerGround(10, 4, GRAY, WHITE);
        check(wrongWindingCount(g) == 0, "뒤집힌 삼각형 " + wrongWindingCount(g) + "개");
    }

    private static void groundChecker() {
        MeshData g = Primitives.checkerGround(2, 2, GRAY, WHITE);
        // 칸 순서: (0,0) (0,1) (1,0) (1,1), 칸마다 정점 4개
        check(g.color(0).approxEquals(GRAY, EPS), "칸(0,0)");
        check(g.color(4).approxEquals(WHITE, EPS), "칸(0,1)");
        check(g.color(8).approxEquals(WHITE, EPS), "칸(1,0)");
        check(g.color(12).approxEquals(GRAY, EPS), "칸(1,1)");
    }

    private static void builderGrows() {
        MeshBuilder b = new MeshBuilder("many");
        for (int i = 0; i < 1000; i++) {
            int a = b.vertex(new Vec3(i, 0, 0), Vec3.UNIT_Y, RED);
            int c = b.vertex(new Vec3(i, 0, 1), Vec3.UNIT_Y, RED);
            int d = b.vertex(new Vec3(i + 1, 0, 0), Vec3.UNIT_Y, RED);
            b.triangle(a, c, d);
        }
        MeshData m = b.build();
        check(m.vertexCount() == 3000 && m.triangleCount() == 1000, "정점 " + m.vertexCount());
    }

    private static void invalid() {
        expectThrows(IllegalArgumentException.class,
                () -> new MeshData("bad", new float[VertexFormat.FLOATS_PER_VERTEX], new int[]{0, 0, 1}), "범위 밖 인덱스");
        expectThrows(IllegalArgumentException.class,
                () -> new MeshData("bad", new float[5], new int[0]), "정점 배열 길이");
        expectThrows(IllegalArgumentException.class, () -> Primitives.cube(0, RED), "크기 0");
        expectThrows(IllegalArgumentException.class,
                () -> Primitives.checkerGround(10, Primitives.MAX_GRID_CELLS + 1, GRAY, WHITE), "칸 수 초과");
    }

    private static void defensiveCopy() {
        float[] v = new float[VertexFormat.FLOATS_PER_VERTEX * 3];
        int[] idx = {0, 1, 2};
        MeshData m = new MeshData("copy", v, idx);
        v[0] = 99;
        idx[0] = 2;
        check(m.position(0).x() == 0, "정점 복사됨");
        check(m.indices()[0] == 0, "인덱스 복사됨");
    }
}
