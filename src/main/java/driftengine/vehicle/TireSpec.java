package driftengine.vehicle;

/**
 * 타이어 특성.
 *
 * @param grip            마찰 계수 μ (정지 하중 기준). 일반 타이어 1.0~1.15, 슬릭 1.4~1.7
 * @param peakSlipRatio   앞뒤 방향 최대 마찰이 나오는 슬립률 (0.08~0.12)
 * @param peakSlipAngle   옆 방향 최대 마찰이 나오는 슬립각 (rad, 보통 6~10도)
 * @param shape           곡선 모양 C (1.3~1.9). 클수록 최고점을 넘으면 마찰이 많이 떨어진다.
 *                        완전히 미끄러질 때 남는 마찰 = sin(C·π/2): 1.4 → 0.81, 1.6 → 0.59
 * @param loadSensitivity 하중이 늘수록 μ 가 줄어드는 정도 (0~0.3). 실제 타이어는 무거울수록 μ 가 약간 준다
 */
public record TireSpec(float grip, float peakSlipRatio, float peakSlipAngle, float shape, float loadSensitivity) {

    public TireSpec {
        if (!(grip > 0 && peakSlipRatio > 0 && peakSlipAngle > 0 && shape > 1 && shape < 2
                && loadSensitivity >= 0 && loadSensitivity < 1)) {
            throw new IllegalArgumentException(String.format(
                    "타이어 값 범위: grip>0, peakSlip>0, peakAngle>0, 1<shape<2, 0<=loadSensitivity<1 (받은 값 %s)", this));
        }
    }

    public static TireSpec street() {
        return new TireSpec(1.10f, 0.10f, (float) Math.toRadians(8), 1.45f, 0.10f);
    }

    /** 완전히 미끄러질 때 남는 마찰 비율 (최고점 = 1) */
    public float slidingRatio() {
        return (float) Math.sin(shape * Math.PI / 2);
    }
}
