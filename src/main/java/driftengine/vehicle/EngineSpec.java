package driftengine.vehicle;

import java.util.Arrays;

/**
 * 엔진(또는 전기 모터) 제원.
 *
 * @param type        피스톤 / 로터리 / 전기
 * @param cylinders   실린더 수 (로터리는 로터 수, 전기는 모터 수). 배기음 합성에 쓴다
 * @param idleRpm     공회전 (전기 = 0)
 * @param launchRpm   출발할 때 클러치가 미끄러지며 유지하는 회전수 (풀 스로틀 기준, 전기 = 0)
 * @param redlineRpm  레드존 (여기서 연료 차단)
 * @param rpmPoints   토크 곡선 회전수 (오름차순)
 * @param baseTorque  토크 곡선 (N·m), 부스트 0 기준. 터보 차는 스톡 곡선을 최대 부스트 배율로 나눈 값
 * @param inertia     크랭크·플라이휠 관성 (kg·m²)
 * @param brakeTorque 스로틀을 뗐을 때 엔진 브레이크 (레드존 기준 N·m, 전기는 회생 제동)
 * @param turbo       터보 (없으면 null = 자연흡기 / 전기)
 */
public record EngineSpec(EngineType type, int cylinders, float idleRpm, float launchRpm, float redlineRpm,
                         float[] rpmPoints, float[] baseTorque, float inertia, float brakeTorque, TurboSpec turbo) {

    public enum EngineType { PISTON, ROTARY, ELECTRIC }

    public EngineSpec {
        if (rpmPoints.length < 2 || rpmPoints.length != baseTorque.length) {
            throw new IllegalArgumentException("토크 곡선은 2점 이상, 회전수와 토크 개수가 같아야 합니다");
        }
        for (int i = 1; i < rpmPoints.length; i++) {
            if (!(rpmPoints[i] > rpmPoints[i - 1])) {
                throw new IllegalArgumentException("토크 곡선 회전수는 오름차순: " + Arrays.toString(rpmPoints));
            }
        }
        if (!(redlineRpm > idleRpm && inertia > 0 && brakeTorque >= 0 && cylinders > 0)) {
            throw new IllegalArgumentException("엔진 값 범위 오류 (레드존 > 공회전, 관성 > 0)");
        }
        if (type == EngineType.ELECTRIC && turbo != null) {
            throw new IllegalArgumentException("전기 모터에는 터보를 달 수 없습니다");
        }
        rpmPoints = rpmPoints.clone();
        baseTorque = baseTorque.clone();
    }

    /** 부스트 0 기준 토크 (곡선 사이는 직선 보간, 범위 밖은 끝값) */
    public float baseTorqueAt(float rpm) {
        if (rpm <= rpmPoints[0]) {
            return baseTorque[0];
        }
        for (int i = 1; i < rpmPoints.length; i++) {
            if (rpm <= rpmPoints[i]) {
                float t = (rpm - rpmPoints[i - 1]) / (rpmPoints[i] - rpmPoints[i - 1]);
                return baseTorque[i - 1] + (baseTorque[i] - baseTorque[i - 1]) * t;
            }
        }
        return baseTorque[baseTorque.length - 1];
    }

    /** 풀 스로틀로 충분히 오래 버텼을 때(부스트가 다 찬 상태) 토크 */
    public float fullLoadTorqueAt(float rpm) {
        float m = 1;
        if (turbo != null) {
            float band = Math.max(0, Math.min(1, (rpm - turbo.spoolStartRpm()) / (turbo.fullBoostRpm() - turbo.spoolStartRpm())));
            m = 1 + turbo.torquePerBar() * turbo.maxBoost() * band;
        }
        return baseTorqueAt(rpm) * m;
    }

    /** 최고 출력 (kW) 과 그 회전수: {kW, rpm} */
    public float[] peakPower() {
        float best = 0;
        float bestRpm = 0;
        for (float rpm = Math.max(500, idleRpm); rpm <= redlineRpm; rpm += 50) {
            float kw = fullLoadTorqueAt(rpm) * rpm * (float) (2 * Math.PI / 60) / 1000;
            if (kw > best) {
                best = kw;
                bestRpm = rpm;
            }
        }
        return new float[]{best, bestRpm};
    }

    public float peakTorque() {
        float best = 0;
        for (float rpm = Math.max(500, idleRpm); rpm <= redlineRpm; rpm += 50) {
            best = Math.max(best, fullLoadTorqueAt(rpm));
        }
        return best;
    }

    /** 터보를 바꾼 엔진 (튜닝용). 기본 곡선은 그대로 */
    public EngineSpec withTurbo(TurboSpec newTurbo) {
        return new EngineSpec(type, cylinders, idleRpm, launchRpm, redlineRpm, rpmPoints, baseTorque, inertia, brakeTorque, newTurbo);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EngineSpec e && type == e.type && cylinders == e.cylinders && idleRpm == e.idleRpm
                && launchRpm == e.launchRpm && redlineRpm == e.redlineRpm && Arrays.equals(rpmPoints, e.rpmPoints)
                && Arrays.equals(baseTorque, e.baseTorque) && inertia == e.inertia && brakeTorque == e.brakeTorque
                && java.util.Objects.equals(turbo, e.turbo);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(type, cylinders, idleRpm, redlineRpm, Arrays.hashCode(rpmPoints), Arrays.hashCode(baseTorque), turbo);
    }

    @Override
    public String toString() {
        return String.format("EngineSpec[%s x%d, %.0f~%.0frpm, turbo=%s]", type, cylinders, idleRpm, redlineRpm, turbo);
    }
}
