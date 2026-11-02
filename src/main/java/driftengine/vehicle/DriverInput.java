package driftengine.vehicle;

/**
 * 운전 입력 한 틱 분량 (키보드: 누름/안 누름). 아날로그처럼 서서히 올리는 건 Car 가 한다.
 * shiftUp 등은 "이번 틱에 막 눌림" 신호.
 */
public record DriverInput(boolean throttle, boolean brake, boolean left, boolean right, boolean handbrake,
                          boolean shiftUp, boolean shiftDown, boolean toggleGearbox, boolean resetCar, boolean nextCar,
                          boolean cycleTraction, boolean nitro) {

    public static final DriverInput NONE = new DriverInput(false, false, false, false, false, false, false, false, false, false, false, false);

    /** 니트로 없이 */
    public DriverInput(boolean throttle, boolean brake, boolean left, boolean right, boolean handbrake, boolean shiftUp,
                       boolean shiftDown, boolean toggleGearbox, boolean resetCar, boolean nextCar, boolean cycleTraction) {
        this(throttle, brake, left, right, handbrake, shiftUp, shiftDown, toggleGearbox, resetCar, nextCar, cycleTraction, false);
    }

    public static DriverInput of(boolean throttle, boolean brake, boolean left, boolean right, boolean handbrake) {
        return new DriverInput(throttle, brake, left, right, handbrake, false, false, false, false, false, false, false);
    }

    /** 가속 + 니트로 (시험용) */
    public DriverInput withNitro(boolean on) {
        return new DriverInput(throttle, brake, left, right, handbrake, shiftUp, shiftDown, toggleGearbox, resetCar, nextCar,
                cycleTraction, on);
    }
}
