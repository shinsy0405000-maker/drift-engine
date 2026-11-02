package driftengine.vehicle;

/**
 * 엔진 상태: 회전수, 터보, 크랭크 토크.
 * <p>
 * 기어가 물려 있으면 엔진 회전수는 바퀴에서 거꾸로 계산한 회전수와 같다(클러치 붙음).
 * 바퀴 회전수가 너무 낮으면(출발) 클러치가 미끄러지며 엔진은 공회전 ~ 출발 회전수를 유지하고, 그 회전수의 토크를 보낸다.
 * 레드존을 넘으면 연료를 끊는다(토크 0 이하). 스로틀을 떼면 엔진 브레이크가 걸린다.
 */
public final class Engine {

    private final EngineSpec spec;
    private final Turbo turbo;
    private float rpm;
    private boolean clutchLocked;
    private float torque;
    private boolean limiter;

    public Engine(EngineSpec spec) {
        this.spec = spec;
        this.turbo = spec.turbo() != null ? new Turbo(spec.turbo()) : null;
        this.rpm = spec.idleRpm();
    }

    /**
     * @param wheelRpm 구동 바퀴 평균 회전에 기어비를 곱한 회전수 (기어가 물렸을 때 엔진이 돌아야 할 회전수)
     * @param inGear   기어가 물려 있고 변속 중이 아님
     * @return 크랭크 토크 (N·m). 음수 = 엔진 브레이크
     */
    public float update(float dt, float wheelRpm, float throttle, boolean inGear) {
        limiter = false;
        if (!inGear) {
            // 중립·변속 중: 엔진만 공회전 ~ 스로틀만큼 돈다, 바퀴에 힘 없음
            float free = spec.idleRpm() + throttle * (spec.redlineRpm() - spec.idleRpm()) * 0.95f;
            rpm += (free - rpm) * (1 - (float) Math.exp(-dt * 6));
            clutchLocked = false;
            torque = 0;
            updateTurbo(dt, throttle);
            return 0;
        }
        float clutchRpm = spec.idleRpm() + throttle * Math.max(0, spec.launchRpm() - spec.idleRpm());
        if (wheelRpm >= clutchRpm) {
            clutchLocked = true;
            rpm = wheelRpm;
        } else {
            clutchLocked = false;
            float target = Math.max(wheelRpm, clutchRpm);
            rpm += (target - rpm) * (1 - (float) Math.exp(-dt * 10));
        }
        updateTurbo(dt, throttle);
        float drive = spec.baseTorqueAt(rpm) * throttle * (turbo != null ? turbo.torqueMultiplier() : 1);
        float engineBrake = 0;
        if (clutchLocked && throttle < 0.05f) {
            engineBrake = spec.brakeTorque() * Math.min(1, rpm / spec.redlineRpm());
        }
        torque = drive - engineBrake;
        if (wheelRpm >= spec.redlineRpm()) {
            limiter = true;
            torque = Math.min(torque, -engineBrake);
        }
        if (!clutchLocked && throttle < 0.05f) {
            torque = 0; // 클러치가 떨어져 있으면 바퀴에 힘이 안 간다 (멈출 때 시동 꺼짐 없음)
        }
        return torque;
    }

    private void updateTurbo(float dt, float throttle) {
        if (turbo != null) {
            turbo.update(dt, rpm, spec.redlineRpm(), throttle);
        }
    }

    public float rpm() {
        return rpm;
    }

    public boolean clutchLocked() {
        return clutchLocked;
    }

    public boolean limiter() {
        return limiter;
    }

    public float torque() {
        return torque;
    }

    public float boost() {
        return turbo != null ? turbo.boost() : 0;
    }

    public Turbo turbo() {
        return turbo;
    }

    public EngineSpec spec() {
        return spec;
    }

    public void reset() {
        rpm = spec.idleRpm();
        torque = 0;
        clutchLocked = false;
        if (turbo != null) {
            turbo.reset();
        }
    }
}
