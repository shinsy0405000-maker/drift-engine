package driftengine.scene;

import driftengine.math.Vec3;

/**
 * 차 뒤를 따라가는 카메라. 렌더 프레임마다 update 를 부른다.
 * <p>
 * 목표 위치 = 차 위치 - (차 앞 방향의 수평 성분) × distance + 위로 height.
 * 카메라는 목표 위치로 지수적으로 다가간다(급하게 꺾여도 화면이 튀지 않게). smoothing 이 클수록 빨리 따라간다.
 * 바라보는 곳은 차 위치 + 위로 lookHeight.
 */
public final class ChaseCamera {

    private float distance = 6.5f;
    private float height = 2.4f;
    private float lookHeight = 0.9f;
    private float smoothing = 8f; // 1/초
    private Vec3 current;

    /** 다음 update 에서 바로 목표 위치로 옮긴다 (처음, 또는 카메라 모드를 바꿨을 때) */
    public void snap() {
        current = null;
    }

    /**
     * @param target  따라갈 점 (차 위치)
     * @param forward 차 앞 방향 (길이 무관, 수평 성분만 씀)
     * @param dt      지난 렌더 프레임 이후 시간 (초)
     */
    public void update(Camera camera, Vec3 target, Vec3 forward, float dt) {
        Vec3 desired = desiredPosition(target, forward);
        if (current == null) {
            current = desired;
        } else {
            float k = 1 - (float) Math.exp(-smoothing * Math.max(0, dt));
            current = current.add(desired.sub(current).scale(k));
        }
        Vec3 look = target.add(new Vec3(0, lookHeight, 0));
        float[] yawPitch = yawPitchTowards(current, look);
        camera.position(current).rotation(yawPitch[0], yawPitch[1]);
    }

    Vec3 desiredPosition(Vec3 target, Vec3 forward) {
        Vec3 flat = new Vec3(forward.x(), 0, forward.z());
        if (flat.length() < 1e-4f) {
            flat = new Vec3(0, 0, -1); // 차가 똑바로 서 있는 등 앞 방향이 수평이 아니면 기본값
        }
        flat = flat.normalize();
        return target.sub(flat.scale(distance)).add(new Vec3(0, height, 0));
    }

    /**
     * from 에서 to 를 보는 Camera 의 (yaw, pitch). Camera.forward() 의 정의와 짝을 맞춘다:
     * forward = (-sin yaw · cos pitch, sin pitch, -cos yaw · cos pitch).
     */
    public static float[] yawPitchTowards(Vec3 from, Vec3 to) {
        Vec3 d = to.sub(from);
        if (d.length() < 1e-6f) {
            return new float[]{0, 0};
        }
        d = d.normalize();
        float yaw = (float) Math.atan2(-d.x(), -d.z());
        float pitch = (float) Math.asin(Math.max(-1, Math.min(1, d.y())));
        return new float[]{yaw, pitch};
    }

    public Vec3 currentPosition() {
        return current;
    }
}
