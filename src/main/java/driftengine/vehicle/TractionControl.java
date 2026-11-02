package driftengine.vehicle;

/**
 * 트랙션 컨트롤: 구동 바퀴가 헛돌면 엔진 출력을 줄인다 (실제 차처럼 점화·스로틀 컷).
 * <p>
 * 바퀴 슬립률(κ = (ωr − v) / v)이 한계를 넘으면 출력 배율을 빠르게 내리고, 돌아오면 천천히 올린다.
 * 출력만 줄이고 브레이크는 쓰지 않는다. 핸드브레이크(클러치 끊김) 중에는 쉰다.
 */
public final class TractionControl {

    /** 끔 / 스포츠(직진 헛바퀴만 잡고, 차가 옆으로 돌면 놓아 줌 = 드리프트 가능) / 켬(항상 잡음) */
    public enum Mode {
        OFF("끔"),
        SPORT("스포츠"),
        FULL("켬");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** 스포츠: 이 각도(차체 미끄러짐)를 넘으면 드리프트 중으로 보고 개입하지 않는다 */
    static final float SPORT_RELEASE_ANGLE = (float) Math.toRadians(12);
    /** 한계 = 타이어 최대 마찰 슬립률 × 배수 */
    static final float FULL_FACTOR = 1.25f;
    static final float SPORT_FACTOR = 2.5f;
    static final float MIN_CUT = 0.05f;
    static final float CUT_RATE = 6f;      // 1/s × 넘친 비율
    static final float RECOVER_RATE = 4f;  // 1/s × 남은 여유

    private Mode mode = Mode.SPORT;
    private float cut = 1f;
    private boolean active;

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.OFF) {
            cut = 1f;
            active = false;
        }
    }

    /** 지금 엔진 출력에 곱하는 값 (1 = 그대로) */
    public float cut() {
        return cut;
    }

    /** 이번 틱에 출력을 줄이고 있나 (계기판 표시등) */
    public boolean active() {
        return active;
    }

    /** 이 모드의 슬립률 한계. 개입하지 않으면 +무한 */
    static float limit(Mode mode, float peakSlipRatio, float driftAngle) {
        return switch (mode) {
            case OFF -> Float.POSITIVE_INFINITY;
            case SPORT -> driftAngle > SPORT_RELEASE_ANGLE ? Float.POSITIVE_INFINITY : peakSlipRatio * SPORT_FACTOR;
            case FULL -> peakSlipRatio * FULL_FACTOR;
        };
    }

    /**
     * 틱마다 한 번.
     *
     * @param spin          구동 바퀴 중 가장 큰 헛도는 슬립률 (가속 방향 +, 후진이면 부호를 뒤집어 준다)
     * @param peakSlipRatio 구동 바퀴 타이어의 최대 마찰 슬립률
     * @param driftAngle    차체 미끄러짐 각 (rad, 절댓값)
     * @param driving       출력이 바퀴로 가는 중인가 (가속 페달 + 기어 물림)
     * @return 엔진 출력 배율
     */
    public float update(float dt, float spin, float peakSlipRatio, float driftAngle, boolean driving) {
        if (mode == Mode.OFF) {
            cut = 1f;
            active = false;
            return cut;
        }
        float limit = limit(mode, peakSlipRatio, driftAngle);
        if (!driving) {
            cut = Math.min(1f, cut + dt * RECOVER_RATE);
        } else if (spin > limit) {
            // 넘친 비율만큼 줄인다 (적분 제어: 슬립률을 한계 근처에 붙잡아 둠)
            float over = Math.min(4f, (spin - limit) / limit);
            cut = Math.max(MIN_CUT, cut - dt * CUT_RATE * over);
        } else {
            // 한계에서 멀수록 빨리 돌려준다 (한계 바로 아래면 천천히 → 출렁임 없음)
            float room = 1f - spin / limit;
            cut = Math.min(1f, cut + dt * RECOVER_RATE * room);
        }
        active = cut < 0.97f;
        return cut;
    }
}
