package driftengine.game;

import driftengine.vehicle.CarSpec;
import driftengine.vehicle.DriveLayout;

/**
 * 성능 점수 (100 ~ 999): 가속(출력/무게), 코너(타이어 그립 + 다운포스), 최고 속도(출력 vs 공기저항)를 합친다.
 * 튜닝하면 제원이 바뀌므로 점수가 오르고, 점수 구간이 클래스가 된다.
 * <p>
 * 가속 0.5 + 코너 0.35 + 최고 속도 0.15 (드리프트·서킷 둘 다 출력과 그립이 가장 중요).
 * 각 항목은 0~1000 으로 정규화: 가속 = (kW/t ÷ 400)^0.6, 코너 = ((유효 그립 - 0.8) ÷ 1.2)^0.8,
 * 최고 속도 = (km/h - 150) ÷ 250. 사륜은 출발 접지가 좋아 가속에 5% 가산.
 */
public final class PerformanceRating {

    public record Breakdown(int total, int acceleration, int cornering, int topSpeed, float powerToWeight,
                            float effectiveGrip, float topSpeedKmh) {
    }

    private PerformanceRating() {
    }

    public static int of(CarSpec spec) {
        return breakdown(spec).total();
    }

    public static Breakdown breakdown(CarSpec spec) {
        float kw = spec.peakPowerKw();
        float ptw = kw / (spec.mass() / 1000f);
        float traction = spec.layout() == DriveLayout.AWD ? 1.05f : 1f;
        float accel = 1000 * (float) Math.pow(clamp01(ptw * traction / 400f), 0.6);

        float grip = (spec.frontTire().grip() + spec.rearTire().grip()) / 2;
        float v = 150 / 3.6f;
        float downforce = 0.5f * CarSpec.AIR_DENSITY * spec.downforceArea() * v * v;
        float effGrip = grip * (1 + downforce / (spec.mass() * CarSpec.GRAVITY));
        float corner = 1000 * (float) Math.pow(clamp01((effGrip - 0.8f) / 1.2f), 0.8);

        float top = topSpeedKmh(spec);
        float speed = 1000 * clamp01((top - 150) / 250f);

        int total = Math.round(Math.max(100, Math.min(999, 0.5f * accel + 0.35f * corner + 0.15f * speed)));
        return new Breakdown(total, Math.round(accel), Math.round(corner), Math.round(speed), ptw, effGrip, top);
    }

    /** 최고 속도: 공기저항 한계와 기어(레드존) 한계 중 낮은 값 */
    public static float topSpeedKmh(CarSpec spec) {
        float power = spec.peakPowerKw() * 1000 * spec.gearbox().efficiency();
        float drag = 0.5f * CarSpec.AIR_DENSITY * Math.max(0.2f, spec.dragArea());
        float vDrag = (float) Math.cbrt(power / drag);
        float[] r = spec.gearbox().ratios();
        float lowestRatio = r[r.length - 1] * spec.gearbox().finalDrive();
        float vGear = spec.engine().redlineRpm() * (float) (2 * Math.PI / 60) / lowestRatio * spec.wheelRadius();
        return Math.min(vDrag, vGear) * 3.6f;
    }

    private static float clamp01(float x) {
        return Math.max(0, Math.min(1, x));
    }
}
