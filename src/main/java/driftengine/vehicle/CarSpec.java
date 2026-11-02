package driftengine.vehicle;

import driftengine.math.Vec3;

import java.util.List;

/**
 * 차량 제원 전체. 단위: kg, m, N, N·m, rad. 좌표: 차체 로컬, +X 오른쪽, +Y 위, -Z 앞, 원점 = 충돌 상자 중심.
 * <p>
 * 파일(cars/*.properties)에서 읽는다: {@link CarSpecLoader}. 스프링·댐퍼는 파일의 "정지 눌림"과 "감쇠비"에서 계산해
 * 앞뒤 축 하중이 달라도 차가 수평으로 서게 한다. 무게중심은 z = 0 이고, 앞뒤 축 위치는 weightFront 로 정한다.
 *
 * @param mass          차 전체 질량
 * @param halfExtents   충돌 상자 크기의 절반
 * @param centerOfMassY 무게중심을 상자 중심보다 얼마나 아래(음수)로 둘지
 * @param wheelbase     앞뒤 축 거리
 * @param weightFront   앞 축이 받는 무게 비율 (0.5 = 50:50)
 * @param mountY        서스펜션 위쪽 고정점 높이 (상자 중심 기준)
 * @param restLength    서스펜션이 다 늘어났을 때 고정점 ~ 바퀴 중심 거리
 * @param springFront   앞 스프링 (바퀴 하나, N/m) — 나머지 뒤/댐퍼도 같은 방식
 * @param antiRollFront 앞 스태빌라이저 (좌우 눌림 차이 1m 당 N)
 * @param maxSteer      최대 조향각
 * @param steerRate     핸들을 돌리는 속도 (rad/s)
 * @param brakeTorque   풀 브레이크 때 네 바퀴 합 제동 토크
 * @param handbrakeTorque 핸드브레이크 뒷바퀴 하나당 제동 토크
 * @param dragArea      공기저항 Cd × 앞면적 (m²)
 * @param downforceArea 다운포스 Cl × 면적 (m²), 0 = 없음
 * @param frontTorqueShare 사륜일 때 앞 축으로 가는 토크 비율
 * @param lsdPreload    LSD 기본 잠김 토크 (N·m), 0 = 일반 차동
 * @param lsdLock       LSD 토크 비례 잠김 (0~1)
 * @param countersteerAssist 키보드용 카운터 보조 (0 = 없음, 1 = 앞바퀴를 항상 진행 방향으로)
 * @param assistMaxAngle 드리프트 각이 이보다 커지면 스로틀을 줄여 스핀을 막는다 (rad, 키보드 보조)
 * @param assistYaw   각이 한계를 넘을 때 차를 되돌리는 회전 보조 세기 (0 = 없음, 1 = 강함)
 * @param camberFront 앞바퀴 캠버 (도, 음수 = 위쪽이 안으로). 옆 그립·한계 각이 늘고 앞뒤 그립이 조금 준다
 * @param camberRear  뒷바퀴 캠버 (도)
 * @param nitroBoost  니트로를 쓰는 동안 엔진 토크에 더하는 비율 (0 = 니트로 없음)
 * @param nitroCapacity 니트로 한 통으로 뿜는 시간 (초)
 */
public record CarSpec(String id, String name, String brand, String origin, String category,
                      float mass, Vec3 halfExtents, float centerOfMassY,
                      float wheelbase, float trackFront, float trackRear, float weightFront, float mountY,
                      float restLength, float wheelRadius, float wheelInertia,
                      float springFront, float springRear, float damperFront, float damperRear,
                      float antiRollFront, float antiRollRear,
                      TireSpec frontTire, TireSpec rearTire,
                      float maxSteer, float steerRate,
                      float brakeTorque, float brakeFrontBias, float handbrakeTorque, boolean abs,
                      float dragArea, float downforceArea, float downforceFront,
                      EngineSpec engine, GearboxSpec gearbox,
                      DriveLayout layout, float frontTorqueShare, float lsdPreload, float lsdLock,
                      float countersteerAssist, float assistMaxAngle, float assistYaw,
                      float camberFront, float camberRear, float nitroBoost, float nitroCapacity) {

    public static final float GRAVITY = 9.81f;
    public static final float AIR_DENSITY = 1.225f;

    public CarSpec {
        if (!(mass > 0 && wheelbase > 0 && weightFront > 0.2f && weightFront < 0.8f && restLength > 0
                && wheelRadius > 0 && wheelInertia > 0 && springFront > 0 && springRear > 0
                && damperFront >= 0 && damperRear >= 0 && maxSteer > 0 && steerRate > 0
                && brakeTorque >= 0 && brakeFrontBias >= 0 && brakeFrontBias <= 1 && handbrakeTorque >= 0
                && dragArea >= 0 && downforceArea >= 0 && frontTorqueShare >= 0 && frontTorqueShare <= 1
                && lsdPreload >= 0 && lsdLock >= 0 && countersteerAssist >= 0 && countersteerAssist <= 1 && assistMaxAngle > 0 && assistYaw >= 0
                && camberFront <= 0 && camberFront >= -12 && camberRear <= 0 && camberRear >= -12
                && nitroBoost >= 0 && nitroCapacity >= 0)) {
            throw new IllegalArgumentException("차량 '" + id + "' 값 범위 오류");
        }
    }

    /** 기본 차 (내장 파일, cars 폴더가 없어도 동작) */
    public static CarSpec defaults() {
        return CarSpecLoader.builtIn();
    }

    /** 앞 축 z (음수 = 앞) */
    public float frontAxleZ() {
        return -wheelbase * (1 - weightFront);
    }

    public float rearAxleZ() {
        return wheelbase * weightFront;
    }

    /** 바퀴 고정점 (0 앞왼, 1 앞오, 2 뒤왼, 3 뒤오) */
    public Vec3 mount(int wheel) {
        boolean front = wheel < 2;
        float half = (front ? trackFront : trackRear) / 2;
        return new Vec3(wheel % 2 == 0 ? -half : half, mountY, front ? frontAxleZ() : rearAxleZ());
    }

    public List<Vec3> mounts() {
        return List.of(mount(0), mount(1), mount(2), mount(3));
    }

    public static boolean isFront(int wheel) {
        return wheel < 2;
    }

    /** 정지 시 바퀴 하나 하중 (N) */
    public float staticLoad(int wheel) {
        return mass * GRAVITY * (isFront(wheel) ? weightFront : 1 - weightFront) / 2;
    }

    public float springRate(int wheel) {
        return isFront(wheel) ? springFront : springRear;
    }

    public float damperRate(int wheel) {
        return isFront(wheel) ? damperFront : damperRear;
    }

    public float antiRoll(int wheel) {
        return isFront(wheel) ? antiRollFront : antiRollRear;
    }

    public TireSpec tire(int wheel) {
        return isFront(wheel) ? frontTire : rearTire;
    }

    /** 바퀴에 엔진 토크가 가는지 */
    public boolean driven(int wheel) {
        return isFront(wheel) ? layout.drivesFront() : layout.drivesRear();
    }

    /** 구동 축의 토크 비율 */
    public float axleTorqueShare(boolean front) {
        return switch (layout) {
            case RWD -> front ? 0 : 1;
            case FWD -> front ? 1 : 0;
            case AWD -> front ? frontTorqueShare : 1 - frontTorqueShare;
        };
    }

    public int drivenWheelCount() {
        return layout == DriveLayout.AWD ? 4 : 2;
    }

    /** 평평한 땅에 정지했을 때 바퀴 하나가 눌리는 길이 */
    public float staticSag(int wheel) {
        return staticLoad(wheel) / springRate(wheel);
    }

    /** 평평한 땅에 정지했을 때 상자 중심의 지면 위 높이 (앞뒤 평균) */
    public float restHeight() {
        float sag = (staticSag(0) + staticSag(2)) / 2;
        return wheelRadius + restLength - sag - mountY;
    }

    /** 감쇠비 (앞바퀴 기준) */
    public float dampingRatio() {
        float m = staticLoad(0) / GRAVITY;
        return (float) (damperFront / (2 * Math.sqrt(springFront * m)));
    }

    public boolean electric() {
        return engine.type() == EngineSpec.EngineType.ELECTRIC;
    }

    public boolean turbocharged() {
        return engine.turbo() != null;
    }

    /** 최고 출력 (kW) */
    public float peakPowerKw() {
        return engine.peakPower()[0];
    }

    /** 짧은 소개 줄 (로그용) */
    public String summary() {
        float[] p = engine.peakPower();
        return String.format("%s %s [%s, %s, %s] %.0fkg, %.0fkW(%.0fPS)@%.0frpm, %.0fN·m, %s%s, %d단",
                brand, name, origin, category, layout, mass, p[0], p[0] * 1.3596f, p[1], engine.peakTorque(),
                engine.type() == EngineSpec.EngineType.ROTARY ? engine.cylinders() + "로터" :
                        engine.type() == EngineSpec.EngineType.ELECTRIC ? "전기 모터" : engine.cylinders() + "기통",
                turbocharged() ? String.format(" 터보 %.1fbar", engine.turbo().maxBoost()) : "",
                gearbox.gearCount());
    }

    /** 엔진만 바꾼 제원 (튜닝용) */
    public CarSpec withEngine(EngineSpec e) {
        return toBuilder().engine(e).build();
    }

    public CarSpec withAssist(float assist) {
        return toBuilder().countersteerAssist(assist).build();
    }

    /** 일부 값만 바꾼 복사본을 만들 때 (튜닝): spec.toBuilder().mass(...).build() */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.id = id;
        b.name = name;
        b.brand = brand;
        b.origin = origin;
        b.category = category;
        b.mass = mass;
        b.halfExtents = halfExtents;
        b.centerOfMassY = centerOfMassY;
        b.wheelbase = wheelbase;
        b.trackFront = trackFront;
        b.trackRear = trackRear;
        b.weightFront = weightFront;
        b.mountY = mountY;
        b.restLength = restLength;
        b.wheelRadius = wheelRadius;
        b.wheelInertia = wheelInertia;
        b.springFront = springFront;
        b.springRear = springRear;
        b.damperFront = damperFront;
        b.damperRear = damperRear;
        b.antiRollFront = antiRollFront;
        b.antiRollRear = antiRollRear;
        b.frontTire = frontTire;
        b.rearTire = rearTire;
        b.maxSteer = maxSteer;
        b.steerRate = steerRate;
        b.brakeTorque = brakeTorque;
        b.brakeFrontBias = brakeFrontBias;
        b.handbrakeTorque = handbrakeTorque;
        b.abs = abs;
        b.dragArea = dragArea;
        b.downforceArea = downforceArea;
        b.downforceFront = downforceFront;
        b.engine = engine;
        b.gearbox = gearbox;
        b.layout = layout;
        b.frontTorqueShare = frontTorqueShare;
        b.lsdPreload = lsdPreload;
        b.lsdLock = lsdLock;
        b.countersteerAssist = countersteerAssist;
        b.assistMaxAngle = assistMaxAngle;
        b.assistYaw = assistYaw;
        b.camberFront = camberFront;
        b.camberRear = camberRear;
        b.nitroBoost = nitroBoost;
        b.nitroCapacity = nitroCapacity;
        return b;
    }

    /** CarSpec 의 모든 값을 바꿀 수 있는 복사본. build() 에서 다시 값 범위를 검사한다 */
    public static final class Builder {
        String id;
        String name;
        String brand;
        String origin;
        String category;
        float mass;
        Vec3 halfExtents;
        float centerOfMassY;
        float wheelbase;
        float trackFront;
        float trackRear;
        float weightFront;
        float mountY;
        float restLength;
        float wheelRadius;
        float wheelInertia;
        float springFront;
        float springRear;
        float damperFront;
        float damperRear;
        float antiRollFront;
        float antiRollRear;
        TireSpec frontTire;
        TireSpec rearTire;
        float maxSteer;
        float steerRate;
        float brakeTorque;
        float brakeFrontBias;
        float handbrakeTorque;
        boolean abs;
        float dragArea;
        float downforceArea;
        float downforceFront;
        EngineSpec engine;
        GearboxSpec gearbox;
        DriveLayout layout;
        float frontTorqueShare;
        float lsdPreload;
        float lsdLock;
        float countersteerAssist;
        float assistMaxAngle;
        float assistYaw;
        float camberFront;
        float camberRear;
        float nitroBoost;
        float nitroCapacity;

        public Builder camber(float front, float rear) {
            camberFront = front;
            camberRear = rear;
            return this;
        }

        public Builder nitro(float boost, float capacitySeconds) {
            nitroBoost = boost;
            nitroCapacity = capacitySeconds;
            return this;
        }

        public Builder id(String v) {
            id = v;
            return this;
        }

        public String id() {
            return id;
        }

        public Builder name(String v) {
            name = v;
            return this;
        }

        public String name() {
            return name;
        }

        public Builder brand(String v) {
            brand = v;
            return this;
        }

        public String brand() {
            return brand;
        }

        public Builder origin(String v) {
            origin = v;
            return this;
        }

        public String origin() {
            return origin;
        }

        public Builder category(String v) {
            category = v;
            return this;
        }

        public String category() {
            return category;
        }

        public Builder mass(float v) {
            mass = v;
            return this;
        }

        public float mass() {
            return mass;
        }

        public Builder halfExtents(Vec3 v) {
            halfExtents = v;
            return this;
        }

        public Vec3 halfExtents() {
            return halfExtents;
        }

        public Builder centerOfMassY(float v) {
            centerOfMassY = v;
            return this;
        }

        public float centerOfMassY() {
            return centerOfMassY;
        }

        public Builder wheelbase(float v) {
            wheelbase = v;
            return this;
        }

        public float wheelbase() {
            return wheelbase;
        }

        public Builder trackFront(float v) {
            trackFront = v;
            return this;
        }

        public float trackFront() {
            return trackFront;
        }

        public Builder trackRear(float v) {
            trackRear = v;
            return this;
        }

        public float trackRear() {
            return trackRear;
        }

        public Builder weightFront(float v) {
            weightFront = v;
            return this;
        }

        public float weightFront() {
            return weightFront;
        }

        public Builder mountY(float v) {
            mountY = v;
            return this;
        }

        public float mountY() {
            return mountY;
        }

        public Builder restLength(float v) {
            restLength = v;
            return this;
        }

        public float restLength() {
            return restLength;
        }

        public Builder wheelRadius(float v) {
            wheelRadius = v;
            return this;
        }

        public float wheelRadius() {
            return wheelRadius;
        }

        public Builder wheelInertia(float v) {
            wheelInertia = v;
            return this;
        }

        public float wheelInertia() {
            return wheelInertia;
        }

        public Builder springFront(float v) {
            springFront = v;
            return this;
        }

        public float springFront() {
            return springFront;
        }

        public Builder springRear(float v) {
            springRear = v;
            return this;
        }

        public float springRear() {
            return springRear;
        }

        public Builder damperFront(float v) {
            damperFront = v;
            return this;
        }

        public float damperFront() {
            return damperFront;
        }

        public Builder damperRear(float v) {
            damperRear = v;
            return this;
        }

        public float damperRear() {
            return damperRear;
        }

        public Builder antiRollFront(float v) {
            antiRollFront = v;
            return this;
        }

        public float antiRollFront() {
            return antiRollFront;
        }

        public Builder antiRollRear(float v) {
            antiRollRear = v;
            return this;
        }

        public float antiRollRear() {
            return antiRollRear;
        }

        public Builder frontTire(TireSpec v) {
            frontTire = v;
            return this;
        }

        public TireSpec frontTire() {
            return frontTire;
        }

        public Builder rearTire(TireSpec v) {
            rearTire = v;
            return this;
        }

        public TireSpec rearTire() {
            return rearTire;
        }

        public Builder maxSteer(float v) {
            maxSteer = v;
            return this;
        }

        public float maxSteer() {
            return maxSteer;
        }

        public Builder steerRate(float v) {
            steerRate = v;
            return this;
        }

        public float steerRate() {
            return steerRate;
        }

        public Builder brakeTorque(float v) {
            brakeTorque = v;
            return this;
        }

        public float brakeTorque() {
            return brakeTorque;
        }

        public Builder brakeFrontBias(float v) {
            brakeFrontBias = v;
            return this;
        }

        public float brakeFrontBias() {
            return brakeFrontBias;
        }

        public Builder handbrakeTorque(float v) {
            handbrakeTorque = v;
            return this;
        }

        public float handbrakeTorque() {
            return handbrakeTorque;
        }

        public Builder abs(boolean v) {
            abs = v;
            return this;
        }

        public boolean abs() {
            return abs;
        }

        public Builder dragArea(float v) {
            dragArea = v;
            return this;
        }

        public float dragArea() {
            return dragArea;
        }

        public Builder downforceArea(float v) {
            downforceArea = v;
            return this;
        }

        public float downforceArea() {
            return downforceArea;
        }

        public Builder downforceFront(float v) {
            downforceFront = v;
            return this;
        }

        public float downforceFront() {
            return downforceFront;
        }

        public Builder engine(EngineSpec v) {
            engine = v;
            return this;
        }

        public EngineSpec engine() {
            return engine;
        }

        public Builder gearbox(GearboxSpec v) {
            gearbox = v;
            return this;
        }

        public GearboxSpec gearbox() {
            return gearbox;
        }

        public Builder layout(DriveLayout v) {
            layout = v;
            return this;
        }

        public DriveLayout layout() {
            return layout;
        }

        public Builder frontTorqueShare(float v) {
            frontTorqueShare = v;
            return this;
        }

        public float frontTorqueShare() {
            return frontTorqueShare;
        }

        public Builder lsdPreload(float v) {
            lsdPreload = v;
            return this;
        }

        public float lsdPreload() {
            return lsdPreload;
        }

        public Builder lsdLock(float v) {
            lsdLock = v;
            return this;
        }

        public float lsdLock() {
            return lsdLock;
        }

        public Builder countersteerAssist(float v) {
            countersteerAssist = v;
            return this;
        }

        public float countersteerAssist() {
            return countersteerAssist;
        }

        public Builder assistMaxAngle(float v) {
            assistMaxAngle = v;
            return this;
        }

        public float assistMaxAngle() {
            return assistMaxAngle;
        }

        public Builder assistYaw(float v) {
            assistYaw = v;
            return this;
        }

        public float assistYaw() {
            return assistYaw;
        }

        public CarSpec build() {
            return new CarSpec(id, name, brand, origin, category, mass, halfExtents, centerOfMassY, wheelbase, trackFront, trackRear, weightFront, mountY, restLength, wheelRadius, wheelInertia, springFront, springRear, damperFront, damperRear, antiRollFront, antiRollRear, frontTire, rearTire, maxSteer, steerRate, brakeTorque, brakeFrontBias, handbrakeTorque, abs, dragArea, downforceArea, downforceFront, engine, gearbox, layout, frontTorqueShare, lsdPreload, lsdLock, countersteerAssist, assistMaxAngle, assistYaw,
                    camberFront, camberRear, nitroBoost, nitroCapacity);
        }
    }
}
