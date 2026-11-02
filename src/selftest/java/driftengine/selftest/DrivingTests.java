package driftengine.selftest;

import driftengine.math.Vec3;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.CarSpecLoader;
import driftengine.vehicle.Differential;
import driftengine.vehicle.DriverInput;
import driftengine.vehicle.EngineSpec;
import driftengine.vehicle.Gearbox;
import driftengine.vehicle.GearboxSpec;
import driftengine.vehicle.Tire;
import driftengine.vehicle.TireSpec;
import driftengine.vehicle.Turbo;
import driftengine.vehicle.TurboSpec;

import java.nio.file.Path;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/**
 * 덩어리 A: 타이어, 터보, 엔진, 변속기, LSD, 그리고 순수 Java 강체 위에서 실제로 운전해 보는 시험.
 * 게임과 같은 Car 코드를 쓰고, 강체만 Jolt 대신 SimpleRigidBody 다 (평평한 땅).
 */
final class DrivingTests {

    private DrivingTests() {
    }

    static void run() {
        test("타이어: 최고점 = μ·하중, 미끄러지면 줄어듦, 힘 방향", DrivingTests::tire);
        test("타이어 결합 슬립: 뒷바퀴가 헛돌면 옆으로 버티는 힘이 준다", DrivingTests::combinedSlip);
        test("터보: 랙(서서히 참), 회전수 구간, 스로틀 떼면 빠짐 + 블로우오프 신호", DrivingTests::turbo);
        test("엔진 곡선: 터보 차 스톡 토크 그대로 복원, 최고 출력", DrivingTests::engineCurve);
        test("차 파일: 터보는 turbo.boost 가 있을 때만, 전기차는 터보 없음, 틀린 값은 오류", DrivingTests::loader);
        test("변속기: 자동 올림/내림, 휠스핀으로 오르내리지 않음, 후진", DrivingTests::gearbox);
        test("LSD: 한도 0 이면 그대로, 한도 안이면 두 바퀴 같은 속도, 한도 넘으면 그만큼만", DrivingTests::differential);
        test("운전(기본 차): 정지, 0→100, 최고 속도, 제동, 왼쪽 조향 = 왼쪽 회전", DrivingTests::basicDriving);
        test("운전(기본 차): 핸드브레이크 드리프트가 되고 스핀하지 않음, 고속 슬라럼 안정", DrivingTests::driftAndStability);
        test("운전(기본 차): 드리프트 중 반대 방향 키 → 반대쪽 드리프트로 넘어가고 스핀하지 않음", DrivingTests::transition);
        test("운전(cars 폴더 전체): 모든 차가 서 있고, 가속하고, 고속에서 돌지 않음", DrivingTests::allCars);
    }

    // ------------------------------------------------------------------ 부품

    private static void tire() {
        TireSpec t = TireSpec.street();
        float[] out = new float[2];
        Tire.force(t, 3000, 3000, 0, t.peakSlipAngle(), out);
        check(Math.abs(Math.abs(out[1]) - t.grip() * 3000) < 1, "옆 최고점 = μ·Fz: " + out[1]);
        check(out[1] < 0, "오른쪽으로 미끄러지면(+α) 왼쪽 힘(-): " + out[1]);
        Tire.force(t, 3000, 3000, t.peakSlipRatio(), 0, out);
        check(Math.abs(out[0] - t.grip() * 3000) < 1 && out[0] > 0, "앞 최고점: " + out[0]);
        Tire.force(t, 3000, 3000, 0, (float) Math.toRadians(80), out);
        float sliding = Math.abs(out[1]) / (t.grip() * 3000);
        check(sliding > 0.7f && sliding < 0.9f, "옆으로 완전히 미끄러질 때 남는 비율 " + sliding + " (계산 " + t.slidingRatio() + ")");
        Tire.force(t, 0, 3000, 0.1f, 0.1f, out);
        check(out[0] == 0 && out[1] == 0, "하중 0 이면 힘 0");
        Tire.force(t, 6000, 3000, 0, t.peakSlipAngle(), out);
        check(Math.abs(out[1]) < t.grip() * 6000 && Math.abs(out[1]) > t.grip() * 6000 * 0.8f, "하중 2배 → μ 약간 감소: " + out[1]);
    }

    private static void combinedSlip() {
        TireSpec t = TireSpec.street();
        float[] grip = new float[2];
        float[] spin = new float[2];
        Tire.force(t, 3000, 3000, 0, t.peakSlipAngle(), grip);
        Tire.force(t, 3000, 3000, 2.0f, t.peakSlipAngle(), spin);
        check(Math.abs(spin[1]) < 0.15f * Math.abs(grip[1]), "헛돌면 옆 힘 " + spin[1] + " (그립 " + grip[1] + ")");
        float total = (float) Math.hypot(spin[0], spin[1]);
        check(total <= t.grip() * 3000 + 1, "합친 힘은 μ·Fz 이하: " + total);
    }

    private static void turbo() {
        TurboSpec spec = new TurboSpec(0.5f, 2500, 4000, 0.6f, 0.85f);
        Turbo t = new Turbo(spec);
        float dt = 1f / 240;
        for (int i = 0; i < 24; i++) {
            t.update(dt, 6000, 7500, 1);
        }
        float early = t.boost();
        for (int i = 0; i < 480; i++) {
            t.update(dt, 6000, 7500, 1);
        }
        check(early < 0.25f, "0.1초 뒤 아직 덜 참: " + early);
        check(t.boost() > 0.45f && t.boost() <= 0.5f, "2초 뒤 최대의 90% 이상: " + t.boost());
        check(Math.abs(t.torqueMultiplier() - (1 + 0.85f * t.boost())) < 1e-5f, "토크 배율");
        t.update(dt, 6000, 7500, 0);
        for (int i = 0; i < 120; i++) {
            t.update(dt, 6000, 7500, 0);
        }
        check(t.boost() < 0.05f, "스로틀 떼고 0.5초: " + t.boost());
        check(t.blowOffCount() == 1, "블로우오프 1번: " + t.blowOffCount());
        Turbo low = new Turbo(spec);
        for (int i = 0; i < 480; i++) {
            low.update(dt, 2000, 7500, 1);
        }
        check(low.boost() < 1e-4f, "spoolStart 아래 회전수면 부스트 없음: " + low.boost());
    }

    private static void engineCurve() {
        CarSpec s = CarSpec.defaults();
        EngineSpec e = s.engine();
        check(e.turbo() != null && Math.abs(e.turbo().maxBoost() - 0.5f) < 1e-6f, "기본 차는 스톡 터보 0.5bar");
        check(Math.abs(e.fullLoadTorqueAt(5000) - 294) < 0.5f, "스톡 5000rpm 토크 294: " + e.fullLoadTorqueAt(5000));
        check(Math.abs(e.fullLoadTorqueAt(1000) - 150) < 0.5f, "부스트 전 1000rpm 150: " + e.fullLoadTorqueAt(1000));
        check(e.baseTorqueAt(5000) < 294 / 1.4f, "부스트 0 기준 곡선은 더 낮음: " + e.baseTorqueAt(5000));
        float kw = e.peakPower()[0];
        check(kw > 185 && kw < 200, "최고 출력 약 190kW: " + kw);
        EngineSpec tuned = e.withTurbo(e.turbo().withBoost(1.0f));
        check(tuned.peakPower()[0] > kw * 1.25f, "부스트 1.0bar 로 올리면 출력 증가: " + tuned.peakPower()[0]);
    }

    private static void loader() {
        CarSpec na = CarSpecLoader.parse("na", CarSpecLoader.read("name=NA\nengine.type=piston\nengine.cylinders=4\n"));
        check(na.engine().turbo() == null, "turbo.boost 가 없으면 자연흡기");
        CarSpec ev = CarSpecLoader.parse("ev", CarSpecLoader.read("engine.type=electric\nturbo.boost=1.0\nengine.torque=0:300, 10000:150\n"));
        check(ev.engine().turbo() == null && ev.electric() && ev.engine().idleRpm() == 0, "전기차: 터보·공회전 없음");
        TestRunner.expectThrows(IllegalArgumentException.class,
                () -> CarSpecLoader.parse("bad", CarSpecLoader.read("mass=abc\n")), "숫자가 아니면 오류");
        TestRunner.expectThrows(IllegalArgumentException.class,
                () -> CarSpecLoader.parse("bad", CarSpecLoader.read("engine.torque=1000:200, 900:210\n")), "회전수 거꾸로면 오류");
    }

    private static void gearbox() {
        GearboxSpec spec = new GearboxSpec(new float[]{3.5f, 2.0f, 1.4f, 1.0f}, 3.3f, 4.1f, 0.2f, 0.9f);
        Gearbox g = new Gearbox(spec);
        float dt = 1f / 240;
        check(Math.abs(g.totalRatio() - 3.5f * 4.1f) < 1e-4f, "1단 총 기어비");
        g.update(dt, 8000, 8000, 7600, 3000);
        check(g.gear() == 2 && g.shifting(), "레드존 근처 → 2단, 변속 중");
        for (int i = 0; i < 240; i++) {
            g.update(dt, 2000, 2000, 7600, 3000);
        }
        check(g.gear() == 1, "회전수가 떨어지면 내림: " + g.gear());
        g.update(dt, 7700, 2000, 7600, 3000); // 쿨다운 중
        check(g.gear() == 1, "변속 직후 쿨다운 동안은 다시 안 바꿈");
        for (int i = 0; i < 240; i++) {
            g.update(dt, 2000, 9000, 7600, 3000); // 바퀴 잠김 등으로 내림 판단 회전수가 높음
        }
        g.shiftTo(-1);
        check(g.totalRatio() < 0 && g.label().equals("R"), "후진 기어비 음수");
    }

    private static void differential() {
        float[] w = {10, 20};
        float[] inertia = {1, 1};
        Differential.couple(w, inertia, 0, 0.01f);
        check(w[0] == 10 && w[1] == 20, "한도 0 = 일반 차동");
        Differential.couple(w, inertia, 1e6f, 0.01f);
        check(Math.abs(w[0] - w[1]) < 1e-3f && Math.abs(w[0] + w[1] - 30) < 1e-3f, "잠김: 같은 속도, 합 보존 " + w[0] + "," + w[1]);
        float[] w2 = {10, 20};
        float moved = Differential.couple(w2, inertia, 100, 0.01f);
        check(Math.abs(moved + 100) < 1e-3f && Math.abs(w2[0] - 11) < 1e-3f && Math.abs(w2[1] - 19) < 1e-3f,
                "한도만큼만 옮김: " + moved + " → " + w2[0] + "," + w2[1]);
    }

    // ------------------------------------------------------------------ 운전

    private static final DriverInput GAS = DriverInput.of(true, false, false, false, false);
    private static final DriverInput BRAKE = DriverInput.of(false, true, false, false, false);

    private static void basicDriving() {
        CarSpec spec = CarSpec.defaults();
        DriveSim rest = new DriveSim(spec);
        rest.run(DriverInput.NONE, 5);
        check(Math.abs(rest.heightAboveGround() - spec.restHeight()) < 0.01f, "정지 높이 " + rest.heightAboveGround());
        check(rest.body.linearVelocity().length() < 0.01f, "정지 상태에서 밀리지 않음: " + rest.body.linearVelocity().length());

        float[] r = accelerate(spec, 45);
        check(r[0] > 3 && r[0] < 10, "0→100km/h " + r[0] + "초 (3~10초 예상)");
        check(r[1] > 200, "최고 속도 " + r[1] + "km/h");

        DriveSim b = new DriveSim(spec);
        b.launchAt(100 / 3.6f);
        Vec3 p0 = b.body.position();
        double t0 = b.time;
        while (b.car.forwardSpeed() > 0.3f && b.time - t0 < 10) {
            b.tick(BRAKE);
        }
        float dist = b.body.position().sub(p0).length();
        check(dist > 25 && dist < 50, "100→0 제동 거리 " + dist + "m (일반 스포츠카 35~45m)");

        DriveSim c = new DriveSim(spec);
        c.launchAt(50 / 3.6f);
        c.run(DriverInput.of(false, false, true, false, false), 1.5);
        check(c.yawRate() > 0.2f, "왼쪽 조향 → 위에서 볼 때 반시계(+Y) 회전: " + c.yawRate());

        DriveSim rev = new DriveSim(spec);
        rev.run(DriverInput.NONE, 1);
        rev.run(BRAKE, 3);
        check(rev.car.gearbox().gear() == -1 && rev.car.forwardSpeed() < -3, "멈춘 뒤 브레이크 유지 → 후진: " + rev.car.forwardSpeed());
        check(!rest.nan && !b.nan && !c.nan && !rev.nan, "NaN 없음");
        check(rest.body.chassisContacts == 0 && b.body.chassisContacts == 0, "차체가 땅에 닿지 않음");
    }

    private static void driftAndStability() {
        CarSpec spec = CarSpec.defaults();
        DriveSim s = new DriveSim(spec);
        s.launchAt(70 / 3.6f);
        for (int i = 0; i < 60; i++) {
            s.tick(DriverInput.of(false, false, true, false, i < 24));
        }
        float maxAngle = 0;
        boolean spun = false;
        double driftSeconds = 0;
        for (int i = 0; i < 240; i++) {
            s.tick(GAS);
            float a = (float) Math.toDegrees(s.car.driftAngle());
            maxAngle = Math.max(maxAngle, a);
            spun |= s.car.forwardSpeed() < 0;
            if (a > 15) {
                driftSeconds += DriveSim.TICK;
            }
        }
        check(maxAngle > 25, "핸드브레이크로 드리프트 시작: 최대 " + maxAngle + "°");
        check(!spun && maxAngle < 80, "보조로 스핀하지 않음 (최대 " + maxAngle + "°, 뒤로 감 " + spun + ")");
        check(driftSeconds > 1.5, "드리프트 유지 " + driftSeconds + "초");

        DriveSim h = new DriveSim(spec);
        h.launchAt(150 / 3.6f);
        float worst = 0;
        for (int i = 0; i < 240; i++) {
            h.tick(DriverInput.of(true, false, (i / 45) % 2 == 0, (i / 45) % 2 == 1, false));
            worst = Math.max(worst, (float) Math.toDegrees(h.car.driftAngle()));
        }
        check(worst < 20 && h.car.forwardSpeed() > 30, "시속 150 좌우 끝까지 꺾기: 최대 미끄러짐 " + worst + "°");
        check(!s.nan && !h.nan, "NaN 없음");
    }

    private static void transition() {
        DriveSim s = new DriveSim(CarSpec.defaults());
        s.launchAt(70 / 3.6f);
        for (int i = 0; i < 60; i++) {
            s.tick(DriverInput.of(false, false, true, false, i < 24)); // 왼쪽 + 핸드브레이크로 시작
        }
        s.run(GAS, 1.5);
        float before = signedSlip(s);
        check(before < -0.35f, "왼쪽 드리프트 유지 중 (β " + Math.toDegrees(before) + "°)");
        float after = 0;
        float worst = 0;
        boolean spun = false;
        for (int i = 0; i < 180; i++) {
            s.tick(DriverInput.of(true, false, false, true, false)); // 가속 유지 + 오른쪽
            after = signedSlip(s);
            worst = Math.max(worst, Math.abs(after));
            spun |= s.car.forwardSpeed() < 0;
        }
        check(after > 0.35f, "3초 안에 오른쪽 드리프트로 넘어감 (β " + Math.toDegrees(after) + "°)");
        check(!spun && worst < Math.toRadians(80), "넘어간 뒤 스핀 없음 (최대 " + Math.toDegrees(worst) + "°)");
    }

    /** β 부호 포함 (- = 왼쪽 드리프트: 차 머리가 진행 방향보다 왼쪽) */
    private static float signedSlip(DriveSim s) {
        Vec3 v = s.body.linearVelocity();
        Vec3 right = s.body.rotation().rotate(Vec3.UNIT_X);
        return (float) Math.atan2(-v.dot(right), s.car.forwardSpeed());
    }

    private static void allCars() {
        List<CarSpec> cars = CarSpecLoader.loadDirectory(Path.of("cars"));
        check(cars.size() >= 9, "cars 폴더 차 " + cars.size() + "대 (9대 예상, 프로젝트 폴더에서 실행해야 함)");
        for (CarSpec spec : cars) {
            DriveSim rest = new DriveSim(spec);
            rest.run(DriverInput.NONE, 4);
            check(Math.abs(rest.heightAboveGround() - spec.restHeight()) < 0.015f && rest.body.linearVelocity().length() < 0.02f,
                    spec.name() + ": 정지 " + rest.heightAboveGround() + " vs " + spec.restHeight());
            float[] r = accelerate(spec, 25);
            check(r[0] > 1.5f && r[0] < 12, spec.name() + ": 0→100 " + r[0] + "초");
            DriveSim h = new DriveSim(spec);
            h.launchAt(150 / 3.6f);
            float worst = 0;
            for (int i = 0; i < 180; i++) {
                h.tick(DriverInput.of(true, false, (i / 45) % 2 == 0, (i / 45) % 2 == 1, false));
                worst = Math.max(worst, (float) Math.toDegrees(h.car.driftAngle()));
            }
            check(worst < 25 && h.car.forwardSpeed() > 30, spec.name() + ": 고속 슬라럼 최대 " + worst + "°");
            check(!rest.nan && !h.nan && rest.body.chassisContacts == 0 && h.body.chassisContacts == 0,
                    spec.name() + ": NaN / 차체 닿음 없음");
        }
    }

    /** @return {0→100 초 (못 가면 99), 최고 속도 km/h} */
    private static float[] accelerate(CarSpec spec, double seconds) {
        DriveSim s = new DriveSim(spec);
        s.run(DriverInput.NONE, 1);
        double t0 = s.time;
        float t100 = 99;
        float top = 0;
        while (s.time - t0 < seconds) {
            s.tick(GAS);
            float kmh = s.car.forwardSpeed() * 3.6f;
            top = Math.max(top, kmh);
            if (t100 == 99 && kmh >= 100) {
                t100 = (float) (s.time - t0);
            }
        }
        return new float[]{t100, top};
    }
}
