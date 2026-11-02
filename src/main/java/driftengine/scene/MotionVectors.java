package driftengine.scene;

import driftengine.math.Mat4;
import driftengine.math.Vec3;

/**
 * 모션 벡터 규칙을 CPU 에서 계산하는 기준 구현. scene.vert/scene.frag 가 GPU 에서 같은 식을 쓴다.
 * <p>
 * 규칙:
 * <ul>
 *   <li>값 = (이전 프레임의 화면 위치) − (현재 화면 위치)</li>
 *   <li>단위 = 화면 비율 (가로/세로 전체가 1). NDC 차이 × 0.5</li>
 *   <li>지터를 뺀 행렬로 계산한다 (지터는 매 프레임 흔드는 것이지 실제 움직임이 아니므로)</li>
 *   <li>Vulkan 화면 좌표: x 오른쪽 +, y 아래쪽 +</li>
 * </ul>
 * DLSS(Streamline) 에 넘길 때는 이 단위에 맞는 배율(mvecScale)을 설정해야 한다 → 4e 단계에서 문서로 확인.
 */
public final class MotionVectors {

    private MotionVectors() {
    }

    /**
     * @param local 물체 로컬 좌표의 점
     * @return {x, y} 화면 비율 단위 모션 벡터
     */
    public static float[] of(Vec3 local, Mat4 model, Mat4 previousModel, Mat4 viewProj, Mat4 previousViewProj) {
        Vec3 world = model.transformPoint(local);
        Vec3 previousWorld = previousModel.transformPoint(local);
        Vec3 current = viewProj.transformPoint(world);
        Vec3 previous = previousViewProj.transformPoint(previousWorld);
        return new float[]{(previous.x() - current.x()) * 0.5f, (previous.y() - current.y()) * 0.5f};
    }
}
