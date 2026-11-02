package driftengine.selftest;

import driftengine.game.GameSession;
import driftengine.game.Part;
import driftengine.game.Setup;
import driftengine.game.Tuning;
import driftengine.game.TuningState;
import driftengine.math.Vec3;
import driftengine.ui.UiInput;
import driftengine.vehicle.Car;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.DriverInput;
import driftengine.vehicle.EngineSpec;
import driftengine.vehicle.visual.BodyKit;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarStyle;
import driftengine.world.ShopUi;
import driftengine.world.Shops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 엔진 세부 부품(하이캠·니트로·차저·ECU·실린더·점화), 캠버, 휠 오프셋 */
final class PartsTests {

    private PartsTests() {
    }

    static void run() {
        test("엔진 부품: 흡배기·ECU·하이캠·실린더·점화가 출력을 올리고, 하이캠은 고회전형, 점화는 회전 관성↓", PartsTests::engineParts);
        test("차저: 낮은 회전부터 바로 부스트, 터보와 같이 못 담, 전기차·순정 터보 차는 불가", PartsTests::charger);
        test("니트로: 누르면 더 빨리 가속, 통이 비면 끝, 안 쓰면 다시 참 (드리프트 중엔 빨리)", PartsTests::nitro);
        test("캠버: 단계별 값, 3단계는 직접 맞춤, 바퀴가 위로 안쪽으로 기울고, 드리프트는 그대로 됨", PartsTests::camber);
        test("휠 오프셋: 트랙이 넓어지고 바퀴가 바깥으로, 값 범위·저장", PartsTests::offset);
        test("튜닝샵 하체: 캠버 3단계·오프셋을 사면 맞춤 줄이 생기고 ←→ 로 값이 바뀜", PartsTests::shopAdjust);
        test("부품 내리기: 한 단계씩, 그 단계 값 절반 돌려받음, 순정 아래는 안 됨, 터보를 내리면 차저 가능", PartsTests::downgrade);
    }

    private static CarSpec car(String id) {
        for (CarSpec s : driftengine.vehicle.CarSpecLoader.loadDirectory(Path.of("cars"))) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new IllegalStateException(id);
    }

    private static TuningState stock() {
        return TuningState.stock(BodyKit.STOCK);
    }

    private static void engineParts() {
        CarSpec tora = car("03-tora-86r");
        float base = tora.peakPowerKw();
        for (Part p : new Part[]{Part.ENGINE, Part.ECU, Part.CAMSHAFT, Part.CYLINDER, Part.IGNITION}) {
            CarSpec t = Tuning.apply(tora, stock().with(p, p.maxLevel()));
            check(t.peakPowerKw() > base * 1.015f, p.label() + " 최고 단계로 출력 ↑ " + base + " → " + t.peakPowerKw());
        }
        CarSpec cam = Tuning.apply(tora, stock().with(Part.CAMSHAFT, 3));
        EngineSpec e0 = tora.engine();
        EngineSpec e1 = cam.engine();
        check(e1.redlineRpm() > e0.redlineRpm(), "하이캠: 회전 한계 ↑");
        check(e1.baseTorqueAt(e0.idleRpm() + 300) < e0.baseTorqueAt(e0.idleRpm() + 300)
                && e1.baseTorqueAt(e0.redlineRpm() * 0.9f) > e0.baseTorqueAt(e0.redlineRpm() * 0.9f) * 1.1f, "하이캠: 저회전 ↓ 고회전 ↑");
        CarSpec ign = Tuning.apply(tora, stock().with(Part.IGNITION, 2));
        check(ign.engine().inertia() < e0.inertia(), "점화: 회전 관성 ↓");
        CarSpec ecu = Tuning.apply(tora, stock().with(Part.ECU, 2));
        check(Math.abs(ecu.engine().redlineRpm() - e0.redlineRpm() - 500) < 1, "ECU 2단계: 한계 +500rpm");
        CarSpec all = Tuning.apply(tora, stock().with(Part.ENGINE, 3).with(Part.ECU, 2).with(Part.CAMSHAFT, 3)
                .with(Part.CYLINDER, 2).with(Part.IGNITION, 2));
        float gain = all.peakPowerKw() / base;
        check(gain > 1.35f && gain < 1.9f, "자연흡기 부품 전부: 출력 x" + gain + " (지나치지 않게)");
        CarSpec ev = car("05-hanul-nvolt5");
        for (Part p : new Part[]{Part.CAMSHAFT, Part.CYLINDER, Part.IGNITION, Part.TURBO, Part.SUPERCHARGER}) {
            check(!Tuning.available(ev, p), "전기차 " + p.label() + " 불가");
        }
        check(Tuning.available(ev, Part.ECU) && Tuning.available(ev, Part.NITRO), "전기차 ECU·니트로 가능");
    }

    private static void charger() {
        CarSpec tora = car("03-tora-86r");
        CarSpec sc = Tuning.apply(tora, stock().with(Part.SUPERCHARGER, 2));
        var t = sc.engine().turbo();
        check(t != null && Math.abs(t.maxBoost() - 0.55f) < 1e-4f && t.spoolStartRpm() <= tora.engine().idleRpm() + 1
                && t.spoolTime() < 0.1f, "차저: 0.55bar, 아이들부터, 바로 참 " + t);
        CarSpec turbo = Tuning.apply(tora, stock().with(Part.TURBO, 1));
        float lowRpm = tora.engine().redlineRpm() * 0.3f;
        check(sc.engine().fullLoadTorqueAt(lowRpm) > turbo.engine().fullLoadTorqueAt(lowRpm), "낮은 회전: 차저 > 터보");
        check(!Tuning.available(car("01-kaze-rt7"), Part.SUPERCHARGER), "순정 터보 차(RT-7)는 차저 불가");
        GameSession s = session();
        check(s.garage().upgrade(Part.TURBO).ok(), "터보 1단계 삼");
        check(!s.garage().upgrade(Part.SUPERCHARGER).ok(), "터보가 있으면 차저 거절");
    }

    private static GameSession session() {
        try {
            List<CarCatalog.Entry> cars = CarCatalog.load(Path.of("cars"));
            GameSession s = new GameSession(cars, Files.createTempDirectory("parts").resolve("p.properties"), null);
            s.profile().profileMoneyForTest(1_000_000);
            return s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void nitro() {
        CarSpec tora = car("03-tora-86r");
        CarSpec n = Tuning.apply(tora, stock().with(Part.NITRO, 2));
        check(n.nitroBoost() > 0.25f && n.nitroCapacity() == 6f, "니트로 2단계: +30%, 6초");
        float vPlain = launch(n, false, 4f);
        float vNitro = launch(n, true, 4f);
        check(vNitro > vPlain * 1.06f, "시속 60 에서 4초: 니트로 " + vNitro * 3.6f + " km/h > 그냥 " + vPlain * 3.6f + " km/h");
        DriveSim sim = new DriveSim(n);
        sim.run(DriverInput.NONE, 0.5);
        sim.run(DriverInput.of(true, false, false, false, false).withNitro(true), 7.0);
        check(sim.car.nitroLevel() < 0.01f && !sim.car.nitroActive(), "6초 넘게 누르면 통이 빔");
        sim.run(DriverInput.of(false, true, false, false, false), 5.0);
        float refill = sim.car.nitroLevel();
        check(refill > 0.08f && refill < 0.2f, "그냥 서 있으면 천천히 참 (5초 " + refill + ")");
        CarSpec plain = tora;
        DriveSim none = new DriveSim(plain);
        none.run(DriverInput.of(true, false, false, false, false).withNitro(true), 2.0);
        check(!none.car.nitroActive(), "니트로 없는 차는 눌러도 없음");
    }

    private static float launch(CarSpec spec, boolean nitro, float seconds) {
        // 시속 60 에서 4초 가속 (출발 직후는 헛바퀴라 니트로 효과가 묻힘)
        DriveSim s = new DriveSim(spec);
        s.launchAt(60 / 3.6f);
        s.run(DriverInput.of(true, false, false, false, false).withNitro(nitro), seconds);
        return s.car.forwardSpeed();
    }

    private static void camber() {
        CarSpec tora = car("03-tora-86r");
        check(Tuning.apply(tora, stock()).camberFront() == 0, "순정 0°");
        CarSpec c1 = Tuning.apply(tora, stock().with(Part.CAMBER, 1));
        CarSpec c2 = Tuning.apply(tora, stock().with(Part.CAMBER, 2));
        check(c1.camberFront() == -1.5f && c2.camberFront() == -3f && c2.camberRear() == -1.5f, "1·2단계 고정값");
        TuningState custom = stock().with(Part.CAMBER, 3).withSetup(new Setup(-7f, -2.5f, 0, 0));
        CarSpec c3 = Tuning.apply(tora, custom);
        check(c3.camberFront() == -7f && c3.camberRear() == -2.5f, "3단계 = 직접 맞춘 값");
        check(new Setup(-20f, -9f, 0, 0).camberFront() == Setup.CAMBER_FRONT_MIN && new Setup(-1.27f, 0, 0, 0).camberFront() == -1.5f,
                "범위·0.5° 단위로 맞춤");
        float[] g4 = Car.camberGrip(4f);
        float[] g8 = Car.camberGrip(8f);
        check(g4[1] > 1.04f && g4[0] < 1f && g8[1] < g4[1], "옆 그립: 4° 가 가장 좋고 8° 는 덜함, 앞뒤 그립은 줄어듦");
        // 바퀴 기울기: 오른쪽 앞바퀴(1) 의 위쪽(+y)이 −x 로
        DriveSim s = new DriveSim(c3);
        s.run(DriverInput.NONE, 0.3);
        Vec3 topRight = s.car.wheelLocalRotation(1).rotate(new Vec3(0, 1, 0));
        Vec3 topLeft = s.car.wheelLocalRotation(0).rotate(new Vec3(0, 1, 0));
        check(topRight.x() < -0.1f && topLeft.x() > 0.1f, "위쪽이 안으로 (오른쪽 " + topRight.x() + ", 왼쪽 " + topLeft.x() + ")");
        // 드리프트 (기본 차에 큰 캠버)
        CarSpec d = CarSpec.defaults().toBuilder().camber(-6f, -1.5f).build();
        DriveSim ds = new DriveSim(d);
        ds.launchAt(70 / 3.6f);
        for (int i = 0; i < 60; i++) {
            ds.tick(DriverInput.of(false, false, true, false, i < 24));
        }
        float maxAngle = 0;
        for (int i = 0; i < 240; i++) {
            ds.tick(DriverInput.of(true, false, false, false, false));
            maxAngle = Math.max(maxAngle, (float) Math.toDegrees(ds.car.driftAngle()));
        }
        check(maxAngle > 25 && maxAngle < 85 && ds.car.forwardSpeed() > 0 && !ds.nan, "캠버 −6°: 드리프트 됨, 스핀 안 함 (" + maxAngle + "°)");
    }

    private static void offset() {
        CarSpec tora = car("03-tora-86r");
        TuningState t = stock().with(Part.OFFSET, 1).withSetup(new Setup(0, 0, 25, 40));
        CarSpec o = Tuning.apply(tora, t);
        check(Math.abs(o.trackFront() - tora.trackFront() - 0.05f) < 1e-5f && Math.abs(o.trackRear() - tora.trackRear() - 0.08f) < 1e-5f,
                "트랙 +2×오프셋");
        check(Math.abs(o.mount(1).x() - tora.mount(1).x() - 0.025f) < 1e-5f, "오른쪽 앞바퀴가 25mm 바깥으로");
        check(Tuning.apply(tora, stock().withSetup(new Setup(0, 0, 40, 40))).trackFront() == tora.trackFront(), "부품 없으면 값이 있어도 그대로");
        check(new Setup(0, 0, 73, -5).offsetFront() == Setup.OFFSET_MAX && new Setup(0, 0, 73, -5).offsetRear() == 0, "범위 0~40mm");
        Setup st = new Setup(-6.5f, -2f, 20, 35);
        check(Setup.parse(st.text()).equals(st) && Setup.parse(null).equals(Setup.DEFAULT), "저장 글자 왕복");
    }

    private static void shopAdjust() {
        GameSession s = session();
        int[] changed = {0};
        ShopUi ui = new ShopUi(s, Shops.Kind.TUNING, new ShopUi.Host() {
            @Override
            public void carChanged() {
                changed[0]++;
            }

            @Override
            public void preview(CarStyle style) {
            }

            @Override
            public void exit() {
            }

            @Override
            public void toast(String message) {
            }
        });
        UiInput down = new UiInput(false, false, true, false, false, false, false);
        UiInput left = new UiInput(false, false, false, true, false, false, false);
        UiInput right = new UiInput(false, false, false, false, true, false, false);
        UiInput ok = new UiInput(false, false, false, false, false, true, false);
        for (int i = 0; i < 5; i++) {
            ui.input(down); // 엔진 → 하체
        }
        ui.input(ok);
        for (int i = 0; i < 3; i++) {
            ui.input(down); // 서스펜션 → 브레이크 → 조향 → 캠버
        }
        ui.input(ok);
        ui.input(ok);
        ui.input(ok);
        check(s.profile().selectedCar().tuning().level(Part.CAMBER) == 3, "캠버 3단계까지 구입");
        ui.input(down); // 앞 캠버 줄
        float before = s.profile().selectedCar().tuning().setup().camberFront();
        ui.input(left);
        ui.input(left);
        float after = s.profile().selectedCar().tuning().setup().camberFront();
        check(after == before - 1f && changed[0] >= 5, "←← = 1° 더 눕힘 (" + before + " → " + after + ")");
        check(s.garage().currentSpec().camberFront() == after, "제원에 반영");
        ui.input(down);
        ui.input(down); // 뒤 캠버 → 오프셋
        ui.input(ok);
        ui.input(down); // 앞 오프셋
        ui.input(right);
        ui.input(right);
        check(s.profile().selectedCar().tuning().level(Part.OFFSET) == 1
                && s.profile().selectedCar().tuning().setup().offsetFront() == Setup.DEFAULT.offsetFront() + 10, "오프셋 구입 후 +10mm");
    }

    private static void downgrade() {
        GameSession s = session();
        driftengine.game.Garage g = s.garage();
        long start = s.profile().money();
        check(!g.downgrade(Part.ENGINE).ok(), "순정은 못 내림");
        check(g.upgrade(Part.ENGINE).ok() && g.upgrade(Part.ENGINE).ok(), "흡배기 2단계");
        long afterBuy = s.profile().money();
        long spentBefore = s.profile().selectedCar().spent();
        int refund = g.downgradeRefund(Part.ENGINE);
        driftengine.game.Garage.Result r = g.downgrade(Part.ENGINE);
        check(r.ok() && s.profile().selectedCar().tuning().level(Part.ENGINE) == 1, "2 → 1 단계");
        check(s.profile().money() == afterBuy + refund && refund > 0 && refund < start - afterBuy, "돌려받은 돈 " + refund);
        check(s.profile().selectedCar().spent() == spentBefore - refund * 2L, "차에 쓴 돈에서 빠짐 (팔 때 중복 없음)");
        check(g.downgrade(Part.ENGINE).ok() && s.profile().selectedCar().tuning().level(Part.ENGINE) == 0, "1 → 순정");
        // 터보 ↔ 차저
        CarSpec stock = g.entry(s.profile().selectedCar().carId()).spec();
        if (Tuning.available(stock, Part.TURBO) && Tuning.available(stock, Part.SUPERCHARGER)) {
            check(g.upgrade(Part.TURBO).ok() && !g.upgrade(Part.SUPERCHARGER).ok(), "터보가 있으면 차저 안 됨");
            check(g.downgrade(Part.TURBO).ok() && g.upgrade(Part.SUPERCHARGER).ok(), "터보를 내리면 차저 가능");
        }
    }
}
