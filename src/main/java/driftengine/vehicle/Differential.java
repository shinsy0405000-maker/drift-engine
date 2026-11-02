package driftengine.vehicle;

/**
 * 차동장치 / LSD (순수 함수).
 * <p>
 * 엔진 토크는 좌우 바퀴에 반씩 간다(일반 차동). LSD 는 두 바퀴 회전 차이를 줄이는 쪽으로 토크를 옮긴다.
 * 옮길 수 있는 토크 한도 = preload + lock × |축에 들어온 토크|. 한도 안이면 두 바퀴를 같은 속도로 맞춘다(잠김).
 * 드리프트에서 안쪽 바퀴만 헛도는 것을 막아 두 뒷바퀴가 같이 미끄러지게 한다.
 * <p>
 * 바퀴 회전 속도를 직접 고친다 (토크 × dt / 관성). 한 스텝 안에서 회전 차이를 넘어서 뒤집지 않는다(안정).
 */
public final class Differential {

    private Differential() {
    }

    /**
     * @param omega    [왼, 오] 바퀴 회전 속도 (rad/s), 고쳐서 돌려준다
     * @param inertia  [왼, 오] 실효 관성 (kg·m²)
     * @param limit    옮길 수 있는 토크 한도 (N·m), 0 이면 일반 차동
     * @return 실제로 옮긴 토크 (왼 → 오, N·m)
     */
    public static float couple(float[] omega, float[] inertia, float limit, float dt) {
        if (limit <= 0 || dt <= 0) {
            return 0;
        }
        float diff = omega[0] - omega[1];
        float reduced = inertia[0] * inertia[1] / (inertia[0] + inertia[1]);
        float needed = diff * reduced / dt; // 이번 스텝에 회전 차이를 0 으로 만드는 토크
        float transfer = Math.max(-limit, Math.min(limit, needed));
        omega[0] -= transfer * dt / inertia[0];
        omega[1] += transfer * dt / inertia[1];
        return transfer;
    }

    /** LSD 한도 = preload + lock × |축 토크| */
    public static float limit(float preload, float lock, float axleTorque) {
        return preload + lock * Math.abs(axleTorque);
    }
}
