package driftengine.selftest;

import driftengine.game.CarClass;
import driftengine.game.ClassTable;
import driftengine.game.Club;
import driftengine.game.ClubEvent;
import driftengine.game.DriftScorer;
import driftengine.game.Garage;
import driftengine.game.GameSession;
import driftengine.game.Market;
import driftengine.game.Part;
import driftengine.game.PerformanceRating;
import driftengine.game.Profile;
import driftengine.game.Tuning;
import driftengine.game.TuningState;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.DriverInput;
import driftengine.vehicle.visual.BodyKit;
import driftengine.vehicle.visual.CarCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 덩어리 D: 클래스 점수, 튜닝, 가격, 딜러쉽, 클럽, 저장, 드리프트 점수 */
final class GameTests {

    private GameTests() {
    }

    static void run() {
        test("클래스: 순정 9대가 1 ~ CS+ 에 퍼짐, 서킷 차가 가장 높음", GameTests::stockClasses);
        test("튜닝: 단계를 올리면 점수가 오르고, 자연흡기는 터보 키트, 전기차는 터보 불가", GameTests::tuning);
        test("가격·딜러쉽: 클래스가 높을수록 비싸고, 딜러쉽은 드리프트 차 클래스 4 이하만", GameTests::market);
        test("차고: 사기·팔기·부품·바디킷, 돈 모자라면 거절", GameTests::garage);
        test("클럽: 별 기준, 새 별만큼만 상금, 보스가 서킷 차를 엶, 클래스 제한", GameTests::clubs);
        test("저장: 저장했다 불러오면 같음, 새 게임은 86R + 20,000 CR", GameTests::saveLoad);
        test("튜닝 끝까지 한 차도 서 있고, 고속에서 안 돌고, 드리프트가 스핀 없이 됨", GameTests::tunedDriving);
        test("드리프트 점수: 각도 계수, 배율, 2초 뒤 확정, 스핀하면 잃음", GameTests::scorerUnit);
        test("드리프트 점수: 실제 주행(핸드브레이크 드리프트)으로 점수가 쌓이고 확정됨", GameTests::scorerDriving);
    }

    private static List<CarCatalog.Entry> catalog() {
        return CarCatalog.load(Path.of("cars"));
    }

    private static CarCatalog.Entry find(List<CarCatalog.Entry> c, String id) {
        return c.stream().filter(e -> e.spec().id().equals(id)).findFirst().orElseThrow();
    }

    private static void stockClasses() {
        List<CarCatalog.Entry> cars = catalog();
        ClassTable t = ClassTable.DEFAULT;
        CarClass lowest = CarClass.CS_PLUS;
        CarClass highest = CarClass.C1;
        for (CarCatalog.Entry e : cars) {
            CarClass c = t.classOf(PerformanceRating.of(e.spec()));
            lowest = c.ordinal() < lowest.ordinal() ? c : lowest;
            highest = c.ordinal() > highest.ordinal() ? c : highest;
        }
        check(lowest == CarClass.C1 && highest == CarClass.CS_PLUS, "범위 " + lowest.label() + " ~ " + highest.label());
        int fujin = PerformanceRating.of(find(cars, "09-kaze-fujin").spec());
        int tora = PerformanceRating.of(find(cars, "03-tora-86r").spec());
        check(fujin > tora + 300, "Fujin-R " + fujin + " vs 86R " + tora);
        check(t.classOf(449) == CarClass.C1 && t.classOf(450) == CarClass.C2 && t.classOf(999) == CarClass.CS_PLUS, "경계");
        TestRunner.expectThrows(IllegalArgumentException.class, () -> new ClassTable(new int[]{0, 500, 450, 550, 650, 750, 850}),
                "경계가 오름차순이 아니면 오류");
    }

    private static void tuning() {
        List<CarCatalog.Entry> cars = catalog();
        CarSpec tora = find(cars, "03-tora-86r").spec();
        TuningState stock = TuningState.stock(BodyKit.STOCK);
        int base = PerformanceRating.of(Tuning.apply(tora, stock));
        check(base == PerformanceRating.of(tora), "순정 튜닝 = 순정 점수");
        int prev = base;
        for (Part p : Part.values()) {
            TuningState t = stock.with(p, p.maxLevel());
            CarSpec tuned = Tuning.apply(tora, t);
            int r = PerformanceRating.of(tuned);
            if (p == Part.ENGINE || p == Part.TURBO || p == Part.WEIGHT || p == Part.TIRES) {
                check(r > base, p.label() + " 최고 단계로 점수 상승: " + base + " → " + r);
            }
            prev = r;
        }
        CarSpec kit = Tuning.apply(tora, stock.with(Part.TURBO, 1));
        check(tora.engine().turbo() == null && kit.engine().turbo() != null
                && Math.abs(kit.engine().turbo().maxBoost() - 0.5f) < 1e-4f, "자연흡기 + 터보 키트 = 0.5bar");
        CarSpec rt7 = find(cars, "01-kaze-rt7").spec();
        CarSpec rtBoost = Tuning.apply(rt7, stock.with(Part.TURBO, 2));
        check(Math.abs(rtBoost.engine().turbo().maxBoost() - 1.1f) < 1e-4f, "순정 터보 0.5 + 2단계 = 1.1bar: " + rtBoost.engine().turbo().maxBoost());
        CarSpec ev = find(cars, "05-hanul-nvolt5").spec();
        check(!Tuning.available(ev, Part.TURBO), "전기차 터보 불가");
        CarSpec light = Tuning.apply(tora, stock.with(Part.WEIGHT, 3).with(Part.SUSPENSION, 2));
        check(Math.abs(light.restHeight() - tora.restHeight() + 0.03f) < 0.005f,
                "경량화해도 차 높이는 서스펜션 낮춤만큼만 바뀜: " + tora.restHeight() + " → " + light.restHeight());
        CarSpec wing = Tuning.apply(tora, stock.withKit(new BodyKit(0, 0, 0, 2, 0)));
        check(wing.downforceArea() == tora.downforceArea() && wing.mass() == tora.mass(), "바디킷은 겉모양만 (다운포스 그대로)");
        CarSpec aero = Tuning.apply(tora, stock.with(Part.AERO, 3));
        check(aero.downforceArea() > tora.downforceArea() + 0.5f && aero.dragArea() > tora.dragArea(), "에어로 3단계: 다운포스↑ 저항↑");
        CarSpec tank = Tuning.apply(tora, stock.with(Part.FUEL_TANK, 2));
        check(Math.abs(tora.mass() - tank.mass() - 25f) < 0.01f, "경량 연료통 2단계 = 25kg 가벼움: " + (tora.mass() - tank.mass()));
        int cats = 0;
        for (Part.Category c : Part.Category.values()) {
            check(!c.parts().isEmpty(), c.label() + " 분류에 부품 있음");
            cats++;
        }
        check(cats == 7, "튜닝 분류 7개 (엔진, 기어, 연료통, 다운포스, 경량, 하체, 타이어)");
        check(prev > 0, "모든 부품 적용 가능");
    }

    private static void tunedDriving() {
        for (CarCatalog.Entry e : catalog()) {
            int[] max = new int[Part.values().length];
            for (Part p : Part.values()) {
                max[p.ordinal()] = Tuning.available(e.spec(), p) ? p.maxLevel() : 0;
            }
            CarSpec spec = Tuning.apply(e.spec(), new TuningState(max, new BodyKit(2, 1, 1, 2, 1)));
            String n = e.spec().name() + " 풀튜닝";
            DriveSim rest = new DriveSim(spec);
            rest.run(DriverInput.NONE, 4);
            check(Math.abs(rest.heightAboveGround() - spec.restHeight()) < 0.015f && rest.body.linearVelocity().length() < 0.02f,
                    n + ": 정지 " + rest.heightAboveGround() + " vs " + spec.restHeight());
            DriveSim h = new DriveSim(spec);
            h.launchAt(150 / 3.6f);
            float worst = 0;
            for (int i = 0; i < 180; i++) {
                h.tick(DriverInput.of(true, false, (i / 45) % 2 == 0, (i / 45) % 2 == 1, false));
                worst = Math.max(worst, (float) Math.toDegrees(h.car.driftAngle()));
            }
            check(worst < 30 && h.car.forwardSpeed() > 30 && !h.nan, n + ": 고속 슬라럼 최대 " + worst + "°");
            if (e.spec().category().equals("drift")) {
                DriveSim d = new DriveSim(spec);
                d.launchAt(70 / 3.6f);
                for (int i = 0; i < 60; i++) {
                    d.tick(DriverInput.of(false, false, true, false, i < 24));
                }
                boolean spun = false;
                float peak = 0;
                for (int i = 0; i < 240; i++) {
                    d.tick(DriverInput.of(true, false, false, false, false));
                    spun |= d.car.forwardSpeed() < 0;
                    peak = Math.max(peak, (float) Math.toDegrees(d.car.driftAngle()));
                }
                check(!spun && peak > 20 && !d.nan, n + ": 핸드브레이크 드리프트 최대 " + peak + "° 스핀 " + spun);
            }
        }
    }

    private static void market() {
        List<CarCatalog.Entry> cars = catalog();
        Market m = new Market(ClassTable.DEFAULT);
        int last = 0;
        CarClass lastClass = CarClass.C1;
        for (CarCatalog.Entry e : m.dealerList(cars)) {
            CarClass c = m.stockClass(e.spec());
            int price = m.carPrice(e.spec());
            check(c.atMost(CarClass.C4) && e.spec().category().equals("drift"), e.spec().name() + " 딜러쉽 조건");
            check(c.ordinal() >= lastClass.ordinal() && (c != lastClass || price >= last), "정렬: " + e.spec().name());
            if (c.ordinal() > lastClass.ordinal()) {
                check(price > last, "클래스가 오르면 가격도: " + e.spec().name() + " " + price + " vs " + last);
            }
            last = price;
            lastClass = c;
        }
        check(!m.soldAtDealer(find(cars, "07-aurum-gtm").spec()) && !m.soldAtDealer(find(cars, "08-hanul-fc74").spec()),
                "서킷 차는 딜러쉽에 없음");
        check(m.dealerList(cars).size() >= 5, "딜러쉽 " + m.dealerList(cars).size() + "대");
        check(Tuning.price(Part.ENGINE, 1, CarClass.C4) > Tuning.price(Part.ENGINE, 1, CarClass.C1), "부품 값도 클래스 배율");
    }

    private static void garage() {
        List<CarCatalog.Entry> cars = catalog();
        Profile p = Profile.newGame(BodyKit.STOCK);
        Garage g = new Garage(p, cars, new Market(ClassTable.DEFAULT));
        check(p.money() == 20_000 && p.cars().size() == 1, "새 게임");
        Garage.Result r = g.upgrade(Part.TURBO);
        check(r.ok() && p.money() < 20_000 && p.selectedCar().tuning().level(Part.TURBO) == 1, "터보 키트 구입: " + r.message());
        check(g.currentSpec().engine().turbo() != null, "지금 차에 터보 반영");
        check(!g.buyCar("07-aurum-gtm").ok(), "GT-M 은 딜러쉽에서 안 팜");
        Garage.Result expensive = g.buyCar("06-hanul-nvolt6");
        check(!expensive.ok() && p.cars().size() == 1, "돈 모자람: " + expensive.message());
        long before = p.money();
        Garage.Result kit = g.setKit(3, 2);
        check(kit.ok() && p.money() == before - 5000, "GT 윙 5,000 CR");
        check(g.setKit(3, 0).ok() && p.money() == before - 5000, "떼는 건 무료");
        // 돈을 넣어 두 번째 차 사기 → 팔기
        p.profileMoneyForTest(100_000);
        Garage.Result buy = g.buyCar("01-kaze-rt7");
        check(buy.ok() && p.cars().size() == 2 && p.selectedIndex() == 1, "RT-7 구입: " + buy.message());
        long afterBuy = p.money();
        Garage.Result sell = g.sellCar(1);
        check(sell.ok() && p.money() == afterBuy - buy.moneyChange() / 2, "절반 값에 판매: " + sell.message());
        check(!g.sellCar(0).ok(), "마지막 차는 못 팜");
        for (int i = 0; i < 10; i++) {
            g.upgrade(Part.GEARBOX);
        }
        check(p.selectedCar().tuning().level(Part.GEARBOX) == 1, "최고 단계 넘지 않음");
    }

    private static void clubs() {
        List<CarCatalog.Entry> cars = catalog();
        List<Club> clubs = Club.defaults();
        check(clubs.stream().anyMatch(c -> c.kind() == Club.Kind.RACING) && clubs.stream().anyMatch(c -> c.kind() == Club.Kind.DRIFT), "드리프트·레이싱 클럽");
        for (Club c : clubs) {
            if (c.kind() == Club.Kind.RACING) {
                for (ClubEvent e : c.events()) {
                    check(e.type() == ClubEvent.Type.LAP_TIME && e.track().contains("circuit"), c.name() + ": 서킷 전용");
                }
            }
        }
        ClubEvent drift = clubs.get(0).events().get(0);
        check(drift.stars(drift.target() - 1) == 0 && drift.stars(drift.target()) == 1 && drift.stars(drift.target() * 1.31f) == 2
                && drift.stars(drift.target() * 2) == 3, "드리프트 별");
        ClubEvent lap = clubs.get(3).events().get(0);
        check(lap.stars(lap.target() + 1) == 0 && lap.stars(lap.target()) == 1 && lap.stars(lap.target() * 0.93f) == 3, "랩타임 별");

        Profile p = Profile.newGame(BodyKit.STOCK);
        Garage g = new Garage(p, cars, new Market(ClassTable.DEFAULT));
        long m0 = p.money();
        Garage.Result r1 = g.finishEvent(drift, drift.target());
        check(r1.ok() && p.money() == m0 + drift.reward(), "1성 상금");
        Garage.Result r2 = g.finishEvent(drift, drift.target());
        check(p.money() == m0 + drift.reward(), "같은 별은 다시 안 줌: " + r2.message());
        g.finishEvent(drift, drift.target() * 2);
        check(p.money() == m0 + drift.rewardFor(3) && p.stars(drift.id()) == 3, "3성: 차액만");
        ClubEvent boss = clubs.get(3).events().get(2);
        g.finishEvent(boss, boss.target() - 1);
        check(p.unlocked().contains("08-hanul-fc74"), "서킷 보스 → FC-74 열림");
        check(g.purchasable("08-hanul-fc74") && g.buyCar("08-hanul-fc74").ok(), "열린 차 받기");
        // 클래스 제한: 86R(1) 는 골목 클럽(2 이하) 가능, 지금 FC-74(4)는 불가
        check(!g.canEnter(clubs.get(0)).ok(), "FC-74 로 골목 클럽 불가");
        g.select(0);
        check(g.canEnter(clubs.get(0)).ok(), "86R 로 골목 클럽 가능");
    }

    private static void saveLoad() {
        try {
            Path dir = Files.createTempDirectory("drift-save");
            Path file = dir.resolve("profile.properties");
            List<CarCatalog.Entry> cars = catalog();
            GameSession s = new GameSession(cars, file, null);
            Profile p = s.profile();
            check(p.money() == Profile.START_MONEY && p.selectedCar().carId().equals(Profile.STARTER_CAR), "새 게임");
            s.garage().upgrade(Part.TIRES);
            s.garage().setKit(0, 1);
            s.garage().finishEvent(s.clubs().get(0).events().get(0), 20_000);
            long beforeStyle = p.money();
            driftengine.vehicle.visual.Livery look = driftengine.vehicle.visual.Livery.STOCK.withPaint(5).withFinish(2)
                    .withRimFrom("01-kaze-rt7").withCaliper(1).withTint(2);
            check(s.garage().setLivery(look).ok() && p.money() == beforeStyle - driftengine.game.Styling.price(
                    driftengine.vehicle.visual.Livery.STOCK, look), "꾸미기 값 냄");
            check(!s.garage().setLivery(look).ok(), "같은 꾸미기는 거절");
            s.save();
            Profile q = Profile.load(file);
            check(q.money() == p.money() && q.cars().equals(p.cars()) && q.stars("alley-1") == p.stars("alley-1")
                    && q.best("alley-1").equals(p.best("alley-1")), "불러오기 = 저장");
            GameSession again = new GameSession(cars, file, null);
            check(again.profile().money() == p.money(), "다시 열어도 이어짐");
            check(again.garage().currentStyle().livery().equals(look)
                    && again.garage().currentStyle().color().equals(driftengine.vehicle.visual.Livery.PAINTS.get(5).linear()),
                    "꾸미기도 저장되고 색에 반영");
            // 옛 저장 파일 (부품 9개, 꾸미기 없음)도 읽힘
            Path old = dir.resolve("old.properties");
            Files.writeString(old, "money=100\ncar.0.id=03-tora-86r\ncar.0.parts=1,0,0,0,0,0,0,0,0\ncar.0.kit=0,0,0,0,0\n");
            Profile o = Profile.load(old);
            check(o.selectedCar().tuning().level(Part.ENGINE) == 1 && o.selectedCar().tuning().level(Part.AERO) == 0
                    && o.selectedCar().livery().equals(driftengine.vehicle.visual.Livery.STOCK), "옛 저장 파일: 새 부품 0단계, 순정 꾸미기");
        } catch (java.io.IOException e) {
            check(false, "입출력 오류: " + e);
        }
    }

    private static void scorerUnit() {
        check(DriftScorer.angleFactor((float) Math.toRadians(10)) == 0 && DriftScorer.angleFactor((float) Math.toRadians(50)) == 1
                && DriftScorer.angleFactor((float) Math.toRadians(100)) < 1, "각도 계수");
        DriftScorer s = new DriftScorer();
        float dt = 1f / 60;
        for (int i = 0; i < 60 * 6; i++) {
            s.update(dt, (float) Math.toRadians(50), 60, 40);
        }
        check(s.multiplier() == 2.0f, "6초 이어 가면 배율 x2: " + s.multiplier());
        float chain = s.chainPoints();
        check(chain > 1000, "쌓인 점수 " + chain);
        DriftScorer.Event e = DriftScorer.Event.NONE;
        for (int i = 0; i < 60 * 3 && e == DriftScorer.Event.NONE; i++) {
            e = s.update(dt, 0, 60, 60);
        }
        check(e == DriftScorer.Event.BANKED && s.total() == Math.round(chain), "2초 뒤 확정: " + s.total());
        for (int i = 0; i < 60; i++) {
            s.update(dt, (float) Math.toRadians(50), 60, 40);
        }
        check(s.update(dt, (float) Math.toRadians(150), 40, -20) == DriftScorer.Event.SPIN && s.chainPoints() == 0, "스핀 → 잃음");
        check(s.total() == Math.round(chain), "확정 점수는 그대로");
        for (int i = 0; i < 30; i++) {
            s.update(dt, (float) Math.toRadians(50), 60, 40);
        }
        check(s.crash() == DriftScorer.Event.CRASH && s.chainPoints() == 0, "충돌 → 잃음");
    }

    private static void scorerDriving() {
        DriveSim sim = new DriveSim(CarSpec.defaults());
        sim.launchAt(70 / 3.6f);
        DriftScorer s = new DriftScorer();
        float dt = DriveSim.TICK;
        for (int i = 0; i < 60; i++) {
            sim.tick(DriverInput.of(false, false, true, false, i < 24));
            s.update(dt, sim.car.driftAngle(), sim.car.speedKmh(), sim.car.forwardSpeed() * 3.6f);
        }
        for (int i = 0; i < 180; i++) {
            sim.tick(DriverInput.of(true, false, false, false, false));
            s.update(dt, sim.car.driftAngle(), sim.car.speedKmh(), sim.car.forwardSpeed() * 3.6f);
        }
        float chain = s.chainPoints();
        check(chain > 500, "3초 드리프트 점수 " + chain);
        DriftScorer.Event e = DriftScorer.Event.NONE;
        for (int i = 0; i < 60 * 6 && e != DriftScorer.Event.BANKED; i++) {
            sim.tick(DriverInput.NONE);
            e = s.update(dt, sim.car.driftAngle(), sim.car.speedKmh(), sim.car.forwardSpeed() * 3.6f);
        }
        check(e == DriftScorer.Event.BANKED && s.total() > 500, "멈춘 뒤 확정: " + s.total());
    }
}
