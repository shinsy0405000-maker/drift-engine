package driftengine.scene;

import driftengine.math.Mat4;
import driftengine.math.Vec3;

/**
 * 1인칭/추적 카메라의 기본형. 위치 + 요(yaw) + 피치(pitch).
 * <p>
 * DLSS 를 염두에 두고 행렬을 세 종류로 나눠 관리한다.
 * <ul>
 *   <li>projection(): 지터 없음 → DLSS 상수(카메라 행렬)용. Streamline 문서: 행렬에 지터를 넣지 말 것</li>
 *   <li>jitteredProjection(): 지터 포함 → 실제 렌더링용</li>
 *   <li>previousViewProjection(): 이전 프레임 값 → 모션 벡터 계산용</li>
 * </ul>
 * 사용 순서: 매 프레임 beginFrame() → 위치/방향 변경 → update(가로, 세로, 지터).
 */
public final class Camera {

    private Vec3 position = new Vec3(0, 0, 3);
    private float yaw;   // 라디안. 0 이면 -Z 를 바라봄, + 는 왼쪽으로 회전
    private float pitch; // 라디안. + 는 위를 봄
    private float fovY = (float) Math.toRadians(60);
    private float near = 0.1f;
    private float far = 1000f;

    private final Mat4 view = new Mat4();
    private final Mat4 projection = new Mat4();
    private final Mat4 jitteredProjection = new Mat4();
    private final Mat4 viewProjection = new Mat4();
    private final Mat4 jitteredViewProjection = new Mat4();
    private final Mat4 previousViewProjection = new Mat4();
    private float jitterX;
    private float jitterY;
    private float aspect = 16f / 9f;
    private boolean hasPrevious;

    /** 피치를 ±89도로 제한한다 (정확히 위/아래를 보면 lookAt 의 up 벡터와 겹쳐 계산이 깨진다). */
    private static final float MAX_PITCH = (float) Math.toRadians(89);

    public Vec3 position() {
        return position;
    }

    public Camera position(Vec3 p) {
        this.position = p;
        return this;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public Camera rotation(float yawRadians, float pitchRadians) {
        this.yaw = yawRadians;
        this.pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitchRadians));
        return this;
    }

    public Camera lens(float fovYRadians, float nearPlane, float farPlane) {
        if (fovYRadians <= 0 || fovYRadians >= Math.PI) {
            throw new IllegalArgumentException("시야각은 0~180도 사이: " + Math.toDegrees(fovYRadians));
        }
        if (nearPlane <= 0 || farPlane <= nearPlane) {
            throw new IllegalArgumentException("near/far 가 잘못됨: " + nearPlane + ", " + farPlane);
        }
        this.fovY = fovYRadians;
        this.near = nearPlane;
        this.far = farPlane;
        return this;
    }

    /** 바라보는 방향 (길이 1) */
    public Vec3 forward() {
        float cp = (float) Math.cos(pitch);
        return new Vec3(
                (float) -Math.sin(yaw) * cp,
                (float) Math.sin(pitch),
                (float) -Math.cos(yaw) * cp);
    }

    /** 오른쪽 방향 (수평, 길이 1) */
    public Vec3 right() {
        return new Vec3((float) Math.cos(yaw), 0, (float) -Math.sin(yaw));
    }

    /** 카메라 위쪽 방향 (right × forward, 길이 1) */
    public Vec3 up() {
        return right().cross(forward()).normalize();
    }

    public float fovY() {
        return fovY;
    }

    public float near() {
        return near;
    }

    public float far() {
        return far;
    }

    /** 마지막 update 의 가로/세로 비율 */
    public float aspect() {
        return aspect;
    }

    /** 이번 프레임을 시작하기 전에 호출: 직전 프레임의 viewProjection 을 보관한다. */
    public void beginFrame() {
        if (hasPrevious) {
            previousViewProjection.set(viewProjection);
        }
    }

    /**
     * 행렬을 다시 계산한다.
     *
     * @param renderWidth  렌더링 해상도 너비 (업스케일 전)
     * @param renderHeight 렌더링 해상도 높이
     * @param jitterPixelsX 지터 (픽셀 단위, 보통 [-0.5, 0.5)). 지터를 안 쓰면 0
     * @param jitterPixelsY 지터 (픽셀 단위, +는 화면 아래쪽)
     */
    public void update(int renderWidth, int renderHeight, float jitterPixelsX, float jitterPixelsY) {
        if (renderWidth <= 0 || renderHeight <= 0) {
            throw new IllegalArgumentException("렌더 해상도는 양수: " + renderWidth + "x" + renderHeight);
        }
        view.lookAt(position, position.add(forward()), Vec3.UNIT_Y);
        aspect = (float) renderWidth / renderHeight;
        projection.perspectiveVulkan(fovY, aspect, near, far);

        // 픽셀 → NDC: NDC 는 -1..1 (폭 2) 이므로 1픽셀 = 2 / 해상도
        jitterX = jitterPixelsX;
        jitterY = jitterPixelsY;
        jitteredProjection.set(projection).applyNdcOffset(2f * jitterPixelsX / renderWidth, 2f * jitterPixelsY / renderHeight);

        viewProjection.mul(projection, view);
        jitteredViewProjection.mul(jitteredProjection, view);

        if (!hasPrevious) {
            previousViewProjection.set(viewProjection); // 첫 프레임: 움직임 없음
            hasPrevious = true;
        }
    }

    /** 장면이 순간이동했을 때(리스폰 등) 호출: 이전 프레임 정보를 버린다(모션 벡터가 튀지 않게). */
    public void resetHistory() {
        hasPrevious = false;
    }

    public Mat4 view() {
        return view;
    }

    public Mat4 projection() {
        return projection;
    }

    public Mat4 jitteredProjection() {
        return jitteredProjection;
    }

    public Mat4 viewProjection() {
        return viewProjection;
    }

    public Mat4 jitteredViewProjection() {
        return jitteredViewProjection;
    }

    public Mat4 previousViewProjection() {
        return previousViewProjection;
    }

    public float jitterX() {
        return jitterX;
    }

    public float jitterY() {
        return jitterY;
    }
}
