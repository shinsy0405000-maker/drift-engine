package driftengine.scene.mesh;

import driftengine.math.Vec3;

/**
 * 기본 도형 생성기. 3D 장면 검증(카메라, 깊이 버퍼, 이후 DLSS 모션 벡터)에 쓴다.
 * 단위: 미터. 좌표계: +Y 위.
 */
public final class Primitives {

    /** 격자 한 변의 최대 칸 수 (정점 = 칸² × 4). 실수로 거대한 메시를 만드는 것을 막는다. */
    public static final int MAX_GRID_CELLS = 1024;

    private Primitives() {
    }

    /**
     * 중심이 원점인 정육면체. 면마다 정점을 따로 둬서 모서리가 날카롭게 보인다(정점 24개, 삼각형 12개).
     *
     * @param size  한 변의 길이
     * @param color 색 (선형 RGB 0~1)
     */
    public static MeshData cube(float size, Vec3 color) {
        if (size <= 0) {
            throw new IllegalArgumentException("크기는 양수: " + size);
        }
        float h = size / 2;
        MeshBuilder b = new MeshBuilder("cube");
        // 각 면: 바깥에서 볼 때 반시계 방향으로 모서리 4개
        b.face(new Vec3(-h, -h, h), new Vec3(h, -h, h), new Vec3(h, h, h), new Vec3(-h, h, h), Vec3.UNIT_Z, color);              // +Z 앞
        b.face(new Vec3(h, -h, -h), new Vec3(-h, -h, -h), new Vec3(-h, h, -h), new Vec3(h, h, -h), Vec3.UNIT_Z.scale(-1), color); // -Z 뒤
        b.face(new Vec3(h, -h, h), new Vec3(h, -h, -h), new Vec3(h, h, -h), new Vec3(h, h, h), Vec3.UNIT_X, color);              // +X 오른쪽
        b.face(new Vec3(-h, -h, -h), new Vec3(-h, -h, h), new Vec3(-h, h, h), new Vec3(-h, h, -h), Vec3.UNIT_X.scale(-1), color); // -X 왼쪽
        b.face(new Vec3(-h, h, h), new Vec3(h, h, h), new Vec3(h, h, -h), new Vec3(-h, h, -h), Vec3.UNIT_Y, color);              // +Y 위
        b.face(new Vec3(-h, -h, -h), new Vec3(h, -h, -h), new Vec3(h, -h, h), new Vec3(-h, -h, h), Vec3.UNIT_Y.scale(-1), color); // -Y 아래
        return b.build();
    }

    /**
     * y = 0 에 놓인 바둑판 무늬 바닥. 움직임을 눈으로 확인하기 좋다.
     *
     * @param sizeMeters 한 변 길이 (중심이 원점)
     * @param cells      한 변의 칸 수
     */
    public static MeshData checkerGround(float sizeMeters, int cells, Vec3 colorA, Vec3 colorB) {
        if (sizeMeters <= 0) {
            throw new IllegalArgumentException("크기는 양수: " + sizeMeters);
        }
        if (cells < 1 || cells > MAX_GRID_CELLS) {
            throw new IllegalArgumentException("칸 수는 1~" + MAX_GRID_CELLS + ": " + cells);
        }
        float cell = sizeMeters / cells;
        float start = -sizeMeters / 2;
        MeshBuilder b = new MeshBuilder("checkerGround");
        for (int row = 0; row < cells; row++) {
            for (int col = 0; col < cells; col++) {
                float x0 = start + col * cell;
                float z0 = start + row * cell;
                float x1 = x0 + cell;
                float z1 = z0 + cell;
                Vec3 color = ((row + col) % 2 == 0) ? colorA : colorB;
                // 위(+Y)에서 볼 때 반시계: (x0,z1) → (x1,z1) → (x1,z0) → (x0,z0)
                b.face(new Vec3(x0, 0, z1), new Vec3(x1, 0, z1), new Vec3(x1, 0, z0), new Vec3(x0, 0, z0),
                        Vec3.UNIT_Y, color);
            }
        }
        return b.build();
    }
}
