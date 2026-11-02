package driftengine.vehicle;

/**
 * 터보 상태: 부스트가 회전수와 스로틀을 따라 서서히 차오르고(터보 랙), 스로틀을 떼면 빠르게 빠진다(블로우오프).
 * <p>
 * 목표 부스트 = 최대 × 스로틀 × (회전수 구간 비율). 차오르는 속도는 배기 흐름(회전수)에 비례한다.
 * 블로우오프: 부스트가 찬 상태에서 스로틀을 떼는 순간을 센다 (소리 단계에서 "치익" 재생 신호).
 */
public final class Turbo {

    private final TurboSpec spec;
    private float boost;
    private boolean armed;
    private float armedBoost;
    private int blowOffCount;

    public Turbo(TurboSpec spec) {
        this.spec = spec;
    }

    public void update(float dt, float rpm, float redlineRpm, float throttle) {
        float band = clamp01((rpm - spec.spoolStartRpm()) / (spec.fullBoostRpm() - spec.spoolStartRpm()));
        float target = spec.maxBoost() * band * clamp01(throttle);
        float rate;
        if (target > boost) {
            float flow = 0.25f + 0.75f * clamp01(rpm / redlineRpm);
            rate = flow / spec.spoolTime();
        } else {
            rate = 1 / 0.12f; // 블로우오프 밸브 / 웨이스트게이트로 빠르게 빠짐
        }
        boost += (target - boost) * (1 - (float) Math.exp(-rate * dt));
        // 스로틀은 서서히 줄어들므로 "밟고 있었다"를 기억해 두었다가 떼는 순간 센다
        if (throttle > 0.5f) {
            armed = true;
            armedBoost = boost;
        } else if (armed && throttle < 0.2f) {
            armed = false;
            if (armedBoost > 0.3f * spec.maxBoost()) {
                blowOffCount++;
            }
        }
    }

    public float boost() {
        return boost;
    }

    public float torqueMultiplier() {
        return 1 + spec.torquePerBar() * Math.max(0, boost);
    }

    /** 최대 부스트일 때 토크 배율 (스톡 토크 곡선 → 기본 곡선 환산용) */
    public static float fullMultiplier(TurboSpec spec) {
        return 1 + spec.torquePerBar() * spec.maxBoost();
    }

    public int blowOffCount() {
        return blowOffCount;
    }

    public TurboSpec spec() {
        return spec;
    }

    public void reset() {
        boost = 0;
        armed = false;
        armedBoost = 0;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
