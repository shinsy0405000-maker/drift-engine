package driftengine.vehicle;

/**
 * 광선 서스펜션 계산 (순수 함수, GPU·물리 엔진 없이 테스트 가능).
 * <p>
 * 고정점에서 차체 아래 방향으로 광선을 쏴서 땅까지 거리 d 를 잰다.
 * 바퀴가 땅에 닿아 있으면 눌린 길이 x = (restLength + wheelRadius) - d.
 * 힘 = 스프링(k · x) + 댐퍼(c · 눌리는 속도). 땅이 차를 끌어당길 수는 없으므로 0 아래로는 자른다.
 */
public final class Suspension {

    private Suspension() {
    }

    /** @return 눌린 길이 (m). 바퀴가 공중이면 0 이하 */
    public static float compression(float hitDistance, float restLength, float wheelRadius) {
        return restLength + wheelRadius - hitDistance;
    }

    /**
     * @param compression         눌린 길이 (m), 0 이하면 공중 → 힘 0
     * @param compressionVelocity 눌리는 속도 (m/s, + 는 더 눌리는 중)
     * @return 차체를 위로 미는 힘 (N, 0 이상)
     */
    public static float force(float compression, float compressionVelocity, float springRate, float damperRate) {
        if (compression <= 0) {
            return 0;
        }
        return Math.max(0, springRate * compression + damperRate * compressionVelocity);
    }
}
