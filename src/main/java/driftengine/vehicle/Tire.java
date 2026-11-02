package driftengine.vehicle;

/**
 * 타이어 마찰력 (순수 함수).
 * <p>
 * 결합 슬립: 앞뒤 슬립률 κ 와 옆 슬립각 α 를 각자의 최고점으로 나눠 정규화한 뒤 합친 크기 s 로 마찰 크기를 정한다.
 * s = √((κ/κp)² + (α/αp)²),  크기 = μ·Fz·f(s),  f(s) = sin(C·atan(B·s)), B = tan(π/(2C)) → f(1) = 1 (최고점).
 * 힘의 방향은 정규화한 슬립 방향을 따른다. 그래서 뒷바퀴가 헛돌면(κ 큼) 옆으로 버티는 힘이 줄어 차가 미끄러진다.
 * 드리프트의 핵심 동작이다.
 * <p>
 * 부호: fx 는 바퀴 앞 방향(κ 가 + 면 앞으로 밀어줌), fy 는 바퀴 오른쪽 방향(옆으로 미끄러지는 반대쪽).
 */
public final class Tire {

    private Tire() {
    }

    /**
     * @param load       바퀴 하중 Fz (N)
     * @param refLoad    μ 기준 하중 (정지 시 바퀴 하중)
     * @param slipRatio  κ = (바퀴 둘레 속도 - 땅 속도) / 땅 속도
     * @param slipAngle  α = atan(옆 속도 / 앞 속도), rad. + 는 오른쪽으로 미끄러짐
     * @param out        out[0] = fx, out[1] = fy (N)
     */
    public static void force(TireSpec t, float load, float refLoad, float slipRatio, float slipAngle, float[] out) {
        if (!(load > 0)) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        float mu = t.grip() * Math.max(0.5f, 1 - t.loadSensitivity() * (load / refLoad - 1));
        float nx = slipRatio / t.peakSlipRatio();
        float ny = slipAngle / t.peakSlipAngle();
        float s = (float) Math.sqrt(nx * nx + ny * ny);
        float c = t.shape();
        float b = (float) Math.tan(Math.PI / (2 * c));
        // f(s)/s, s → 0 이면 기울기 B·C
        float perSlip = s < 1e-6f ? b * c : (float) Math.sin(c * Math.atan(b * s)) / s;
        float max = mu * load;
        out[0] = max * perSlip * nx;
        out[1] = -max * perSlip * ny;
    }

    /** 정규화 슬립 크기 s 에서의 마찰 비율 f(s) (그래프·테스트용) */
    public static float curve(TireSpec t, float s) {
        float c = t.shape();
        float b = (float) Math.tan(Math.PI / (2 * c));
        return (float) Math.sin(c * Math.atan(b * s));
    }
}
