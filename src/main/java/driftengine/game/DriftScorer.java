package driftengine.game;

/**
 * 드리프트 점수.
 * <p>
 * 드리프트 중(각 15° 이상, 시속 30km 이상)에는 매 순간 점수가 쌓인다: 초당 (속도/10)^1.1 × 각도 계수 × 50 × 배율
 * (시속 60km, 50° 면 초당 약 360점).
 * 각도 계수는 15°에서 0, 45~70°에서 1, 그 위로는 조금 줄어든다 (너무 돌린 드리프트는 감점).
 * 끊기지 않고 이어 가면 2.5초마다 배율 +0.5 (최대 x5).
 * 드리프트가 끝나고 2초 동안 다시 시작하지 않으면 그동안 쌓은 점수가 확정(BANKED)된다.
 * 스핀(110° 넘음, 뒤로 감)이나 충돌이면 쌓던 점수를 잃는다.
 */
public final class DriftScorer {

    public static final float MIN_ANGLE = (float) Math.toRadians(15);
    public static final float SPIN_ANGLE = (float) Math.toRadians(110);
    public static final float MIN_SPEED_KMH = 30;
    public static final float BANK_DELAY = 2.0f;
    public static final float MULT_STEP_TIME = 2.5f;
    public static final float MAX_MULT = 5f;

    public enum Event { NONE, BANKED, SPIN, CRASH }

    private float chain;
    private float chainTime;
    private float multiplier = 1;
    private float grace;
    private long total;
    private long lastBanked;
    private Event lastEvent = Event.NONE;
    private boolean drifting;

    /**
     * @param angle       미끄러지는 각 (rad, 0 이상)
     * @param speedKmh    속력
     * @param forwardKmh  앞 방향 속도 (뒤로 가면 음수)
     * @return 이번에 일어난 일
     */
    public Event update(float dt, float angle, float speedKmh, float forwardKmh) {
        if (chain > 0 && (angle > SPIN_ANGLE || (forwardKmh < -5 && speedKmh > 10))) {
            return lose(Event.SPIN);
        }
        drifting = angle >= MIN_ANGLE && angle <= SPIN_ANGLE && speedKmh >= MIN_SPEED_KMH && forwardKmh > 0;
        if (drifting) {
            float rate = (float) Math.pow(speedKmh / 10, 1.1) * angleFactor(angle) * 50;
            chain += rate * multiplier * dt;
            chainTime += dt;
            multiplier = Math.min(MAX_MULT, 1 + 0.5f * (float) Math.floor(chainTime / MULT_STEP_TIME));
            grace = 0;
            return Event.NONE;
        }
        if (chain > 0) {
            grace += dt;
            if (grace >= BANK_DELAY) {
                lastBanked = Math.round(chain);
                total += lastBanked;
                reset();
                lastEvent = Event.BANKED;
                return Event.BANKED;
            }
        }
        return Event.NONE;
    }

    /** 충돌 (차 속도가 한 틱에 크게 바뀜 등): 쌓던 점수를 잃는다 */
    public Event crash() {
        return chain > 0 ? lose(Event.CRASH) : Event.NONE;
    }

    private Event lose(Event e) {
        reset();
        lastEvent = e;
        return e;
    }

    private void reset() {
        chain = 0;
        chainTime = 0;
        multiplier = 1;
        grace = 0;
    }

    /** 15° → 0, 45~70° → 1, 110° → 0.6 */
    public static float angleFactor(float angle) {
        float deg = (float) Math.toDegrees(angle);
        if (deg < 15) {
            return 0;
        }
        if (deg < 45) {
            float t = (deg - 15) / 30f;
            return t * t * (3 - 2 * t);
        }
        if (deg <= 70) {
            return 1;
        }
        return Math.max(0.6f, 1 - (deg - 70) / 40f * 0.4f);
    }

    /** 이벤트 시작 등: 모두 0 으로 */
    public void clear() {
        reset();
        total = 0;
        lastBanked = 0;
        lastEvent = Event.NONE;
    }

    public float chainPoints() {
        return chain;
    }

    public float multiplier() {
        return multiplier;
    }

    public long total() {
        return total;
    }

    /** 확정 점수 + 쌓는 중인 점수 (이벤트 끝날 때 쌓는 중인 것도 인정) */
    public long totalWithChain() {
        return total + Math.round(chain);
    }

    public long lastBanked() {
        return lastBanked;
    }

    public Event lastEvent() {
        return lastEvent;
    }

    /** 드리프트를 멈춘 뒤 점수가 확정되기까지 지난 비율 (0 = 드리프트 중, 1 = 확정) */
    public float bankProgress() {
        return chain > 0 ? Math.min(1, grace / BANK_DELAY) : 0;
    }

    /** 다음 배율까지 진행 비율 (0~1). 최고 배율이면 1 */
    public float multiplierProgress() {
        if (multiplier >= MAX_MULT) {
            return 1;
        }
        return (chainTime % MULT_STEP_TIME) / MULT_STEP_TIME;
    }

    public boolean drifting() {
        return drifting;
    }
}
