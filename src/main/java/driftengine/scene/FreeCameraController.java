package driftengine.scene;

import driftengine.input.InputAction;
import driftengine.input.InputMap;
import driftengine.input.InputState;
import driftengine.math.Vec3;

/**
 * 자유 이동(플라이) 카메라 조작. 개발 중 장면을 둘러보는 용도이며, 게임용 차량 추적 카메라는 따로 만든다.
 * <p>
 * 고정 틱마다 update 를 호출한다 (GameClock). 속도 단위는 초당 미터, 회전은 초당 라디안.
 */
public final class FreeCameraController {

    private float moveSpeed = 5.0f;        // m/s
    private float sprintMultiplier = 4.0f; // Shift 를 누르면 이 배수
    private float turnSpeed = 1.8f;        // rad/s

    public FreeCameraController moveSpeed(float metersPerSecond) {
        if (metersPerSecond <= 0) {
            throw new IllegalArgumentException("이동 속도는 양수: " + metersPerSecond);
        }
        this.moveSpeed = metersPerSecond;
        return this;
    }

    public FreeCameraController sprintMultiplier(float multiplier) {
        if (multiplier < 1) {
            throw new IllegalArgumentException("가속 배수는 1 이상: " + multiplier);
        }
        this.sprintMultiplier = multiplier;
        return this;
    }

    public FreeCameraController turnSpeed(float radiansPerSecond) {
        if (radiansPerSecond <= 0) {
            throw new IllegalArgumentException("회전 속도는 양수: " + radiansPerSecond);
        }
        this.turnSpeed = radiansPerSecond;
        return this;
    }

    /**
     * @param dtSeconds 고정 틱 길이 (GameClock.fixedDeltaSeconds)
     * @return 이번 틱에 카메라가 움직였거나 회전했으면 true
     */
    public boolean update(Camera camera, InputState input, InputMap map, double dtSeconds) {
        float dt = (float) dtSeconds;

        // 1. 회전
        float yawInput = map.axis(InputAction.LOOK_RIGHT, InputAction.LOOK_LEFT, input);   // 왼쪽이 + (Camera 규칙)
        float pitchInput = map.axis(InputAction.LOOK_DOWN, InputAction.LOOK_UP, input);
        boolean rotated = yawInput != 0 || pitchInput != 0;
        if (rotated) {
            camera.rotation(camera.yaw() + yawInput * turnSpeed * dt,
                    camera.pitch() + pitchInput * turnSpeed * dt);
        }

        // 2. 이동 (카메라가 보는 방향 기준)
        float forward = map.axis(InputAction.MOVE_BACKWARD, InputAction.MOVE_FORWARD, input);
        float strafe = map.axis(InputAction.MOVE_LEFT, InputAction.MOVE_RIGHT, input);
        float vertical = map.axis(InputAction.MOVE_DOWN, InputAction.MOVE_UP, input);
        if (forward == 0 && strafe == 0 && vertical == 0) {
            return rotated;
        }

        Vec3 direction = camera.forward().scale(forward)
                .add(camera.right().scale(strafe))
                .add(Vec3.UNIT_Y.scale(vertical));
        float length = direction.length();
        if (length < 1e-6f) {
            return rotated; // 예: 정확히 위를 보며 앞+아래를 누른 경우 서로 상쇄
        }
        // 대각선으로 갈 때 더 빨라지지 않도록 길이를 1 로 맞춘다
        float speed = moveSpeed * (map.isActive(InputAction.SPRINT, input) ? sprintMultiplier : 1f);
        camera.position(camera.position().add(direction.scale(speed * dt / length)));
        return true;
    }
}
