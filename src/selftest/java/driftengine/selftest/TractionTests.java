package driftengine.selftest;

import driftengine.vehicle.CarSpec;
import driftengine.vehicle.CarSpecLoader;
import driftengine.vehicle.DriverInput;
import driftengine.vehicle.TractionControl;

import java.nio.file.Path;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 트랙션 컨트롤: 제어 규칙 + 실제 출발(헛바퀴, 0→100) + 드리프트를 막지 않음 */
final class TractionTests {

    private static final DriverInput GAS = DriverInput.of(true, false, false, false, false);
    private static final DriverInput CYCLE = new DriverInput(false, false, false, false, false, false, false, false, false, false, true);

    private TractionTests() {
    }

    static void run() {
        test("트랙션 컨트롤 규칙: 끔은 그대로, 켬은 헛돌면 출력을 줄였다가 돌려줌, 스포츠는 드리프트 중엔 놓아 줌", TractionTests::rules);
        test("트랙션 컨트롤 출발: 켬이면 헛바퀴가 사라지고 0→100 이 끔보다 느리지 않음", TractionTests::launch);
        test("트랙션 컨트롤: X 키로 끔 → 스포츠 → 켬, 스포츠(기본)에서도 핸드브레이크 드리프트 됨", TractionTests::driftStillWorks);
    }

    private static void rules() {
        float dt = 1f / 240;
        TractionControl off = new TractionControl();
        off.setMode(TractionControl.Mode.OFF);
        check(off.update(dt, 5f, 0.1f, 0, true) == 1f && !off.active(), "끔: 출력 그대로");

        TractionControl full = new TractionControl();
        full.setMode(TractionControl.Mode.FULL);
        float cut = 1;
        for (int i = 0; i < 48; i++) { // 0.2초 동안 크게 헛돎
            cut = full.update(dt, 1.0f, 0.1f, 0, true);
        }
        check(cut < 0.5f && full.active(), "켬: 0.2초 헛돌면 출력 절반 아래 (" + cut + ")");
        for (int i = 0; i < 240; i++) { // 1초 동안 붙잡음
            cut = full.update(dt, 0f, 0.1f, 0, true);
        }
        check(cut > 0.99f && !full.active(), "붙잡으면 1초 안에 출력 회복 (" + cut + ")");
        float steady = 1;
        for (int i = 0; i < 240; i++) { // 한계 바로 아래 (0.12 < 0.125)
            steady = full.update(dt, 0.12f, 0.1f, 0, true);
        }
        check(steady == 1f || steady > 0.99f, "한계 안쪽 슬립에는 개입 안 함");

        TractionControl sport = new TractionControl();
        check(sport.mode() == TractionControl.Mode.SPORT, "기본은 스포츠");
        float c2 = 1;
        for (int i = 0; i < 48; i++) {
            c2 = sport.update(dt, 1.0f, 0.1f, (float) Math.toRadians(25), true);
        }
        check(c2 == 1f, "스포츠: 25° 드리프트 중 헛바퀴는 그대로 둠");
        for (int i = 0; i < 48; i++) {
            c2 = sport.update(dt, 1.0f, 0.1f, (float) Math.toRadians(3), true);
        }
        check(c2 < 0.9f, "스포츠: 직진 헛바퀴는 잡음 (" + c2 + ")");
    }

    private static void launch() {
        List<CarSpec> cars = CarSpecLoader.loadDirectory(Path.of("cars"));
        int checked = 0;
        for (CarSpec spec : cars) {
            if (!spec.id().equals("05-hanul-nvolt5") && !spec.id().equals("08-hanul-fc74") && !spec.id().equals("01-kaze-rt7")) {
                continue;
            }
            float[] off = launch(spec, TractionControl.Mode.OFF);
            float[] on = launch(spec, TractionControl.Mode.FULL);
            check(off[1] > 2f, spec.id() + ": 끔이면 크게 헛돎 (슬립률 " + off[1] + ")");
            check(on[1] < 1.5f, spec.id() + ": 켬이면 헛바퀴 작음 (슬립률 " + on[1] + ")");
            check(on[0] > 0 && (off[0] < 0 || on[0] <= off[0] + 0.05f),
                    spec.id() + ": 0→100 켬 " + on[0] + "초 ≤ 끔 " + off[0] + "초");
            checked++;
        }
        check(checked == 3, "시험한 차 3대");
    }

    /** {0→100 초 (못 가면 -1), 1.5초 뒤 최대 구동 바퀴 슬립률} */
    private static float[] launch(CarSpec spec, TractionControl.Mode mode) {
        DriveSim s = new DriveSim(spec);
        s.run(DriverInput.NONE, 1.0);
        s.car.traction().setMode(mode);
        double start = s.time;
        float t100 = -1;
        float maxSlip = 0;
        for (int i = 0; i < 60 * 8; i++) {
            s.tick(GAS);
            if (s.time - start > 1.5) {
                for (int w = 0; w < 4; w++) {
                    if (spec.driven(w)) {
                        maxSlip = Math.max(maxSlip, s.car.wheel(w).slipRatio());
                    }
                }
            }
            if (t100 < 0 && s.car.forwardSpeed() * 3.6f > 100) {
                t100 = (float) (s.time - start);
            }
        }
        check(!s.nan, spec.id() + " " + mode + ": NaN 없음");
        return new float[]{t100, maxSlip};
    }

    private static void driftStillWorks() {
        DriveSim c = new DriveSim(CarSpec.defaults());
        c.run(DriverInput.NONE, 0.2);
        TractionControl.Mode first = c.car.traction().mode();
        c.tick(CYCLE);
        TractionControl.Mode second = c.car.traction().mode();
        c.tick(CYCLE);
        TractionControl.Mode third = c.car.traction().mode();
        c.tick(CYCLE);
        check(first == TractionControl.Mode.SPORT && second == TractionControl.Mode.FULL
                && third == TractionControl.Mode.OFF && c.car.traction().mode() == TractionControl.Mode.SPORT,
                "순서 스포츠 → 켬 → 끔 → 스포츠");

        DriveSim s = new DriveSim(CarSpec.defaults());
        s.launchAt(70 / 3.6f);
        for (int i = 0; i < 60; i++) {
            s.tick(DriverInput.of(false, false, true, false, i < 24));
        }
        float maxAngle = 0;
        for (int i = 0; i < 240; i++) {
            s.tick(GAS);
            maxAngle = Math.max(maxAngle, (float) Math.toDegrees(s.car.driftAngle()));
        }
        check(s.car.traction().mode() == TractionControl.Mode.SPORT, "기본 스포츠");
        check(maxAngle > 25 && s.car.forwardSpeed() > 0, "스포츠에서 핸드브레이크 드리프트 " + maxAngle + "°");
    }
}
