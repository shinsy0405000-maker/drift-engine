package driftengine.vehicle;

/**
 * 변속기 상태. 기어: -1 = 후진, 0 = 중립, 1.. = 전진.
 * <p>
 * 자동 모드: 엔진이 레드존 가까이(upShiftRpm) 가면 올리고, 낮은 기어로 내려도 넘치지 않을 만큼 회전수가 떨어지면 내린다.
 * 변속하는 동안(shiftTime) 동력이 끊긴다. 변속 직후 잠깐은 다시 변속하지 않는다(기어 왔다 갔다 방지).
 */
public final class Gearbox {

    static final float AUTO_COOLDOWN = 0.6f;

    private final GearboxSpec spec;
    private int gear = 1;
    private float shiftTimer;
    private float cooldown;
    private boolean automatic = true;
    private int shiftCount;

    public Gearbox(GearboxSpec spec) {
        this.spec = spec;
    }

    /** 엔진 회전 → 바퀴 회전 총 기어비 (부호 포함, 중립 0) */
    public float totalRatio() {
        return ratioOf(gear);
    }

    float ratioOf(int g) {
        if (g > 0) {
            return spec.ratios()[g - 1] * spec.finalDrive();
        }
        if (g < 0) {
            return -spec.reverse() * spec.finalDrive();
        }
        return 0;
    }

    public boolean shifting() {
        return shiftTimer > 0;
    }

    /** 기어가 물려 있고 변속 중이 아님 */
    public boolean engaged() {
        return gear != 0 && !shifting();
    }

    public void shiftTo(int g) {
        int target = Math.max(-1, Math.min(spec.gearCount(), g));
        if (target == gear) {
            return;
        }
        gear = target;
        shiftTimer = spec.shiftTime();
        cooldown = AUTO_COOLDOWN;
        shiftCount++;
    }

    /**
     * 자동 변속 판단 (매 스텝). 후진·중립에서는 아무것도 안 한다.
     *
     * 바퀴가 헛돌거나(휠스핀) 잠기면(핸드브레이크) 바퀴 회전수가 실제 속도와 다르다. 그래서 올림은 둘 중 낮은 값,
     * 내림은 둘 중 높은 값으로 판단한다 → 휠스핀으로 올렸다가 바로 내리거나, 핸드브레이크로 1단까지 떨어지지 않는다.
     *
     * @param upCheckRpm     올림 판단용 회전수 (바퀴 기준과 땅 속도 기준 중 낮은 값)
     * @param downCheckRpm   내림 판단용 회전수 (둘 중 높은 값)
     * @param upShiftRpm     이 회전수 이상이면 올림
     * @param downShiftRpm   이 회전수 이하이고 한 단 내려도 upShiftRpm 아래면 내림
     */
    public void update(float dt, float upCheckRpm, float downCheckRpm, float upShiftRpm, float downShiftRpm) {
        shiftTimer = Math.max(0, shiftTimer - dt);
        cooldown = Math.max(0, cooldown - dt);
        if (!automatic || gear <= 0 || shifting() || cooldown > 0) {
            return;
        }
        if (upCheckRpm >= upShiftRpm && gear < spec.gearCount()) {
            shiftTo(gear + 1);
        } else if (gear > 1 && downCheckRpm <= downShiftRpm) {
            float lowerRpm = downCheckRpm * ratioOf(gear - 1) / ratioOf(gear);
            if (lowerRpm < upShiftRpm * 0.92f) {
                shiftTo(gear - 1);
            }
        }
    }

    public int gear() {
        return gear;
    }

    public boolean automatic() {
        return automatic;
    }

    public void setAutomatic(boolean automatic) {
        this.automatic = automatic;
    }

    public int shiftCount() {
        return shiftCount;
    }

    public GearboxSpec spec() {
        return spec;
    }

    public void reset() {
        gear = 1;
        shiftTimer = 0;
        cooldown = 0;
    }

    /** 표시용: R, N, 1, 2 ... */
    public String label() {
        return gear < 0 ? "R" : gear == 0 ? "N" : Integer.toString(gear);
    }
}
