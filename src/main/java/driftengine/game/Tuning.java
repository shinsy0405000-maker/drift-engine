package driftengine.game;

import driftengine.vehicle.CarSpec;
import driftengine.vehicle.EngineSpec;
import driftengine.vehicle.GearboxSpec;
import driftengine.vehicle.TireSpec;
import driftengine.vehicle.TurboSpec;

/**
 * 튜닝 적용: 순정 제원 + 튜닝 상태 → 실제로 달리는 제원. 성능 점수(클래스)도 이 제원으로 다시 계산한다.
 * <p>
 * 터보: 순정 터보 차는 단계마다 부스트 +0.3bar, 자연흡기 차는 1단계에서 터보 키트(0.5bar)를 달고 그 뒤 +0.3bar.
 * 3단계는 큰 터보(최대 부스트가 높은 대신 늦게 참). 전기차는 터보를 달 수 없다.
 * 바디킷은 겉모양만 바꾼다 (공기역학은 다운포스 튜닝). 연료통은 무게만 줄인다.
 */
public final class Tuning {

    /** 흡배기 토크 배율 */
    private static final float[] INTAKE = {1.0f, 1.05f, 1.10f, 1.15f};
    private static final float[] ECU_TORQUE = {1.0f, 1.04f, 1.08f};
    private static final float[] ECU_RPM = {0, 250, 500};
    /** 하이캠: 높은 회전에서 더하는 토크 (낮은 회전은 조금 손해) */
    private static final float[] CAM_GAIN = {0, 0.06f, 0.12f, 0.18f};
    private static final float CAM_RPM = 150;
    private static final float[] CYLINDER = {1.0f, 1.06f, 1.12f};
    private static final float[] IGNITION = {1.0f, 1.02f, 1.04f};
    /** 슈퍼차저 부스트 (bar): 아이들부터 바로 참 */
    private static final float[] CHARGER_BAR = {0, 0.35f, 0.55f, 0.75f};
    private static final float[] NITRO_BOOST = {0, 0.20f, 0.30f, 0.40f};
    private static final float[] NITRO_SECONDS = {0, 4, 6, 8};
    /** 캠버 1·2단계 고정값 (앞, 뒤 도). 3단계는 Setup 값 */
    private static final float[][] CAMBER = {{0, 0}, {-1.5f, -1.0f}, {-3.0f, -1.5f}};
    private static final float[] WEIGHT = {1.0f, 0.97f, 0.94f, 0.90f};
    private static final float[] TIRE_GRIP = {0f, 0.05f, 0.12f, 0.22f};
    private static final float[] SUSP_SPRING = {1.0f, 1.25f, 1.6f};
    private static final float[] SUSP_DAMPER = {1.0f, 1.2f, 1.45f};
    private static final float[] SUSP_LOWER = {0f, 0.015f, 0.03f};
    private static final float[] BRAKES = {1.0f, 1.15f, 1.3f};
    private static final float[][] LSD = {{0, 0}, {250, 0.7f}, {350, 0.85f}};
    private static final float[] STEER_DEG = {0, 5, 12};
    /** 경량 연료통: 줄어드는 무게 (kg) */
    private static final float[] FUEL_KG = {0, 12, 25};
    /** 에어로: 다운포스 Cl·A 추가 / 공기저항 Cd·A 추가 (m²) */
    private static final float[] AERO_CLA = {0, 0.15f, 0.35f, 0.60f};
    private static final float[] AERO_CDA = {0, 0.01f, 0.03f, 0.06f};

    private Tuning() {
    }

    /** 이 차에 이 부품을 달 수 있는지 (전기차는 터보·차저·캠·실린더·점화 불가, 순정 터보 차는 차저 불가) */
    public static boolean available(CarSpec stock, Part part) {
        boolean electric = stock.engine().type() == EngineSpec.EngineType.ELECTRIC;
        return switch (part) {
            case TURBO, CAMSHAFT, CYLINDER, IGNITION -> !electric;
            case SUPERCHARGER -> !electric && stock.engine().turbo() == null;
            default -> true;
        };
    }

    /** 같이 달 수 없는 부품 (터보 ↔ 차저). 없으면 null */
    public static Part conflict(Part part) {
        return switch (part) {
            case TURBO -> Part.SUPERCHARGER;
            case SUPERCHARGER -> Part.TURBO;
            default -> null;
        };
    }

    /** 이 튜닝의 실제 캠버 (앞, 뒤 도) */
    public static float[] camber(TuningState t) {
        int lv = t.level(Part.CAMBER);
        if (lv >= 3) {
            return new float[]{t.setup().camberFront(), t.setup().camberRear()};
        }
        return CAMBER[lv].clone();
    }

    /** 이 튜닝의 휠 오프셋 (앞, 뒤 mm) */
    public static float[] offset(TuningState t) {
        return t.level(Part.OFFSET) >= 1 ? new float[]{t.setup().offsetFront(), t.setup().offsetRear()} : new float[]{0, 0};
    }

    /** level 단계 가격 (차의 순정 클래스가 높을수록 비쌈) */
    public static int price(Part part, int level, CarClass stockClass) {
        return Math.round(part.basePrice(level) * (1 + 0.35f * stockClass.ordinal()) / 100f) * 100;
    }

    /** 바디킷 한 칸 바꾸는 가격 (단계 × 2500, 클래스 배율 없음) */
    public static int kitPrice(int level) {
        return 2500 * level;
    }

    public static CarSpec apply(CarSpec stock, TuningState t) {
        CarSpec.Builder b = stock.toBuilder();

        // 엔진: 흡배기 · ECU · 하이캠 · 실린더 · 점화 → 토크 곡선, 회전 한계, 관성
        EngineSpec e = stock.engine();
        boolean electric = e.type() == EngineSpec.EngineType.ELECTRIC;
        float redline = e.redlineRpm();
        float inertia = e.inertia();
        float scale = INTAKE[t.level(Part.ENGINE)] * ECU_TORQUE[t.level(Part.ECU)];
        float camGain = 0;
        if (!electric) {
            redline += ECU_RPM[t.level(Part.ECU)] + CAM_RPM * t.level(Part.CAMSHAFT);
            scale *= CYLINDER[t.level(Part.CYLINDER)] * IGNITION[t.level(Part.IGNITION)];
            inertia *= (1 + 0.05f * t.level(Part.CYLINDER)) * (1 - 0.07f * t.level(Part.IGNITION));
            camGain = CAM_GAIN[t.level(Part.CAMSHAFT)];
        }
        float[] rpms = e.rpmPoints();
        float[] base = e.baseTorque().clone();
        for (int i = 0; i < base.length; i++) {
            float x = rpms[i] / redline;
            float cam = 1 + camGain * smooth(0.45f, 0.85f, x) - 0.35f * camGain * (1 - smooth(0.2f, 0.45f, x));
            base[i] *= scale * cam;
        }
        TurboSpec turbo = e.turbo();
        int turboLevel = t.level(Part.TURBO);
        if (turboLevel > 0 && e.type() != EngineSpec.EngineType.ELECTRIC) {
            if (turbo == null) {
                turbo = new TurboSpec(0.5f + 0.3f * (turboLevel - 1), redline * 0.33f, redline * 0.58f, 0.6f, 0.85f);
            } else {
                turbo = turbo.withBoost(turbo.maxBoost() + 0.3f * turboLevel);
            }
            if (turboLevel == 3) {
                // 큰 터보: 늦게, 천천히 찬다
                turbo = new TurboSpec(turbo.maxBoost(), turbo.spoolStartRpm() * 1.2f, turbo.fullBoostRpm() * 1.15f,
                        turbo.spoolTime() * 1.35f, turbo.torquePerBar());
            }
        }
        int charger = t.level(Part.SUPERCHARGER);
        if (charger > 0 && !electric && turbo == null) {
            // 슈퍼차저: 크랭크로 돌려서 낮은 회전부터 바로 부스트 (지연 거의 없음)
            turbo = new TurboSpec(CHARGER_BAR[charger], e.idleRpm(), redline * 0.35f, 0.08f, 0.85f);
        }
        b.engine(new EngineSpec(e.type(), e.cylinders(), e.idleRpm(), e.launchRpm(), redline, rpms,
                base, inertia, e.brakeTorque(), turbo));
        b.nitro(NITRO_BOOST[t.level(Part.NITRO)], NITRO_SECONDS[t.level(Part.NITRO)]);

        // 캠버 (타이어 힘은 Car 가 캠버로 고침) + 휠 오프셋 (트랙이 넓어짐)
        float[] cam = camber(t);
        b.camber(cam[0], cam[1]);
        float[] off = offset(t);
        b.trackFront(stock.trackFront() + 2 * off[0] / 1000f).trackRear(stock.trackRear() + 2 * off[1] / 1000f);

        // 무게: 스프링·댐퍼는 줄어든 무게에 맞춰 같은 비율로 (차 높이 유지)
        float w = WEIGHT[t.level(Part.WEIGHT)] * (1 - FUEL_KG[t.level(Part.FUEL_TANK)] / stock.mass());
        b.mass(stock.mass() * w);
        float spring = SUSP_SPRING[t.level(Part.SUSPENSION)];
        float damper = SUSP_DAMPER[t.level(Part.SUSPENSION)];
        b.springFront(stock.springFront() * w * spring).springRear(stock.springRear() * w * spring);
        b.damperFront(stock.damperFront() * w * damper * (float) Math.sqrt(spring)).damperRear(stock.damperRear() * w * damper * (float) Math.sqrt(spring));
        b.antiRollFront(stock.antiRollFront() * (1 + 0.2f * t.level(Part.SUSPENSION)))
                .antiRollRear(stock.antiRollRear() * (1 + 0.2f * t.level(Part.SUSPENSION)));
        // 차 높이: 스프링이 단단해지면 덜 눌리므로(sag 감소) 그만큼 더 줄여서, 결과적으로 정확히 SUSP_LOWER 만큼 낮아지게
        float sag0 = stock.staticSag(0);
        float sag1 = sag0 / spring;
        b.restLength(stock.restLength() - sag0 + sag1 - SUSP_LOWER[t.level(Part.SUSPENSION)]);

        // 타이어
        float g = TIRE_GRIP[t.level(Part.TIRES)];
        b.frontTire(tire(stock.frontTire(), g, t.level(Part.TIRES))).rearTire(tire(stock.rearTire(), g, t.level(Part.TIRES)));

        b.brakeTorque(stock.brakeTorque() * BRAKES[t.level(Part.BRAKES)]);
        int lsd = t.level(Part.LSD);
        if (lsd > 0) {
            b.lsdPreload(Math.max(stock.lsdPreload(), LSD[lsd][0])).lsdLock(Math.max(stock.lsdLock(), LSD[lsd][1]));
        }
        if (t.level(Part.GEARBOX) > 0) {
            GearboxSpec gb = stock.gearbox();
            b.gearbox(new GearboxSpec(gb.ratios(), gb.reverse(), gb.finalDrive() * 1.05f, gb.shiftTime() * 0.5f, gb.efficiency()));
        }
        b.maxSteer(stock.maxSteer() + (float) Math.toRadians(STEER_DEG[t.level(Part.STEERING)]));

        // 에어로 (다운포스 튜닝)
        int aero = t.level(Part.AERO);
        b.downforceArea(stock.downforceArea() + AERO_CLA[aero]).dragArea(stock.dragArea() + AERO_CDA[aero]);
        return b.build();
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static TireSpec tire(TireSpec t, float extraGrip, int level) {
        float shape = level >= 3 ? Math.max(t.shape(), 1.62f) : t.shape();
        float peakAngle = level >= 3 ? t.peakSlipAngle() * 0.9f : t.peakSlipAngle();
        return new TireSpec(t.grip() + extraGrip, t.peakSlipRatio(), peakAngle, shape, t.loadSensitivity());
    }
}
