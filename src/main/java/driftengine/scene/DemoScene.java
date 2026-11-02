package driftengine.scene;

import driftengine.math.Vec3;
import driftengine.scene.mesh.MeshData;
import driftengine.scene.mesh.Primitives;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 기술 검증용 장면: 바둑판 바닥, 가운데서 도는 정육면체, 원형으로 놓인 상자들, 멀리 있는 기둥.
 * <p>
 * 확인할 수 있는 것: 깊이 버퍼(앞뒤 가림), 원근감, 카메라 이동, 움직이는 물체(나중에 모션 벡터 검증용).
 * 게임 콘텐츠가 아니라 테스트 장면이다.
 */
public final class DemoScene implements Scene {

    public static final float GROUND_SIZE = 200f;
    public static final int GROUND_CELLS = 100;
    public static final int RING_COUNT = 12;
    public static final float RING_RADIUS = 10f;
    /** 가운데 정육면체의 회전 속도 (초당 라디안) */
    public static final float SPIN_SPEED = 0.8f;

    private final List<SceneObject> objects = new ArrayList<>();
    private final SceneObject spinner;

    public DemoScene() {
        MeshData ground = Primitives.checkerGround(GROUND_SIZE, GROUND_CELLS,
                new Vec3(0.10f, 0.10f, 0.11f), new Vec3(0.30f, 0.30f, 0.32f));
        objects.add(new SceneObject("ground", ground));

        // 같은 크기 1m 정육면체를 색만 다르게 몇 개 만들어 공유한다
        MeshData red = Primitives.cube(1, new Vec3(0.80f, 0.10f, 0.08f));
        MeshData orange = Primitives.cube(1, new Vec3(0.90f, 0.45f, 0.05f));
        MeshData blue = Primitives.cube(1, new Vec3(0.10f, 0.30f, 0.85f));
        MeshData white = Primitives.cube(1, new Vec3(0.75f, 0.75f, 0.75f));

        spinner = new SceneObject("spinner", red).place(new Vec3(0, 1, 0), 0, new Vec3(2, 2, 2));
        objects.add(spinner);

        for (int i = 0; i < RING_COUNT; i++) {
            double angle = 2 * Math.PI * i / RING_COUNT;
            float size = 1 + (i % 3) * 0.5f; // 1, 1.5, 2 m
            Vec3 pos = new Vec3((float) (Math.cos(angle) * RING_RADIUS), size / 2, (float) (Math.sin(angle) * RING_RADIUS));
            objects.add(new SceneObject("ring" + i, i % 2 == 0 ? orange : blue)
                    .place(pos, (float) angle, new Vec3(size, size, size)));
        }

        // 원근감 확인용 기둥 (높이 12m)
        objects.add(new SceneObject("pillar", white).place(new Vec3(0, 6, -40), 0, new Vec3(2, 12, 2)));
    }

    /** 렌더 프레임을 시작할 때 호출: 모든 물체의 현재 행렬을 "이전 프레임"으로 보관한다 (모션 벡터용). */
    @Override
    public void beginFrame() {
        for (SceneObject object : objects) {
            object.beginFrame();
        }
    }

    /** 고정 틱마다 호출 (simTimeSeconds = 누적 게임 시간) */
    public void update(double simTimeSeconds) {
        spinner.place(new Vec3(0, 1, 0), (float) (simTimeSeconds * SPIN_SPEED), new Vec3(2, 2, 2));
    }

    @Override
    public void update(double simTimeSeconds, double dtSeconds) {
        update(simTimeSeconds);
    }

    @Override
    public List<SceneObject> objects() {
        return Collections.unmodifiableList(objects);
    }

    public SceneObject spinner() {
        return spinner;
    }
}
