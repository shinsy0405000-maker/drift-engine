package driftengine.game;

import driftengine.vehicle.CarSpec;
import driftengine.vehicle.visual.BodyKit;
import driftengine.vehicle.visual.CarCatalog;
import driftengine.vehicle.visual.CarStyle;

import java.util.List;

/**
 * 차고 조작: 차 사기·팔기·고르기, 부품 올리기, 바디킷 바꾸기, 클럽 이벤트 보상.
 * 돈이 모자라거나 조건이 안 되면 바꾸지 않고 이유를 돌려준다.
 */
public final class Garage {

    public record Result(boolean ok, String message, long moneyChange) {
        static Result fail(String message) {
            return new Result(false, message, 0);
        }
    }

    private final Profile profile;
    private final List<CarCatalog.Entry> catalog;
    private final Market market;

    public Garage(Profile profile, List<CarCatalog.Entry> catalog, Market market) {
        this.profile = profile;
        this.catalog = catalog;
        this.market = market;
    }

    public Profile profile() {
        return profile;
    }

    public Market market() {
        return market;
    }

    public CarCatalog.Entry entry(String carId) {
        for (CarCatalog.Entry e : catalog) {
            if (e.spec().id().equals(carId)) {
                return e;
            }
        }
        throw new IllegalArgumentException("없는 차: " + carId);
    }

    /** 지금 차의 튜닝 반영 제원 */
    public CarSpec currentSpec() {
        Profile.OwnedCar c = profile.selectedCar();
        return Tuning.apply(entry(c.carId()).spec(), c.tuning());
    }

    /** 지금 차의 소리 (엔진 튜닝 단계만큼 배기가 열림) */
    public driftengine.audio.SoundProfile currentSound() {
        Profile.OwnedCar c = profile.selectedCar();
        return entry(c.carId()).sound().withTuneLevel(c.tuning().level(Part.ENGINE));
    }

    /** 지금 차의 겉모양 (바디킷 + 꾸미기 반영) */
    public CarStyle currentStyle() {
        Profile.OwnedCar c = profile.selectedCar();
        return styleOf(c.carId(), c.tuning().kit(), c.livery());
    }

    /** 이 차에 이 바디킷·꾸미기를 입힌 모양 (스타일링 샵 미리보기에도 씀) */
    public CarStyle styleOf(String carId, BodyKit kit, driftengine.vehicle.visual.Livery livery) {
        return entry(carId).style().withKit(kit).withLivery(livery);
    }

    public int currentRating() {
        return PerformanceRating.of(currentSpec());
    }

    public CarClass currentClass() {
        return market.classes().classOf(currentRating());
    }

    // ------------------------------------------------------------------ 차

    /** 살 수 있는 곳: 딜러쉽(드리프트 차, 클래스 4 이하) 또는 클럽에서 연 차 */
    public boolean purchasable(String carId) {
        CarCatalog.Entry e = entry(carId);
        return market.soldAtDealer(e.spec()) || profile.unlocked.contains(carId);
    }

    public Result buyCar(String carId) {
        CarCatalog.Entry e = entry(carId);
        if (!purchasable(carId)) {
            return Result.fail(e.spec().name() + " 은(는) 딜러쉽에서 팔지 않습니다 (클럽 보상으로 열림)");
        }
        boolean unlockedFree = profile.unlocked.contains(carId) && !market.soldAtDealer(e.spec());
        long price = unlockedFree ? 0 : market.carPrice(e.spec());
        if (profile.money < price) {
            return Result.fail("돈이 모자랍니다: " + price + " CR 필요, 가진 돈 " + profile.money + " CR");
        }
        profile.money -= price;
        profile.cars.add(new Profile.OwnedCar(carId, TuningState.stock(e.style().kit()), price));
        profile.selected = profile.cars.size() - 1;
        if (unlockedFree) {
            profile.unlocked.remove(carId); // 보상 차는 한 번만 받음
        }
        return new Result(true, e.spec().brand() + " " + e.spec().name() + " 구입" + (unlockedFree ? " (클럽 보상)" : ""), -price);
    }

    public Result sellCar(int index) {
        if (profile.cars.size() <= 1) {
            return Result.fail("마지막 한 대는 팔 수 없습니다");
        }
        if (index < 0 || index >= profile.cars.size()) {
            return Result.fail("없는 차 번호: " + index);
        }
        Profile.OwnedCar c = profile.cars.remove(index);
        long back = Math.round(c.spent() * Market.RESALE);
        profile.money += back;
        if (profile.selected >= profile.cars.size() || profile.selected == index) {
            profile.selected = Math.max(0, Math.min(profile.selected, profile.cars.size() - 1));
        } else if (profile.selected > index) {
            profile.selected--;
        }
        return new Result(true, entry(c.carId()).spec().name() + " 판매 (+" + back + " CR)", back);
    }

    public void select(int index) {
        if (index >= 0 && index < profile.cars.size()) {
            profile.selected = index;
        }
    }

    // ------------------------------------------------------------------ 튜닝

    /** 지금 차의 부품을 한 단계 올린다 */
    public Result upgrade(Part part) {
        Profile.OwnedCar c = profile.selectedCar();
        CarSpec stock = entry(c.carId()).spec();
        if (!Tuning.available(stock, part)) {
            return Result.fail(part.label() + ": 이 차에는 달 수 없습니다");
        }
        int level = c.tuning().level(part);
        if (level >= part.maxLevel()) {
            return Result.fail(part.label() + ": 이미 최고 단계");
        }
        Part other = Tuning.conflict(part);
        if (other != null && c.tuning().level(other) > 0) {
            return Result.fail(part.label() + ": " + other.label() + " 와(과) 같이 달 수 없습니다");
        }
        int price = Tuning.price(part, level + 1, market.stockClass(stock));
        if (profile.money < price) {
            return Result.fail("돈이 모자랍니다: " + price + " CR 필요");
        }
        profile.money -= price;
        replaceSelected(c.withTuning(c.tuning().with(part, level + 1), price));
        return new Result(true, part.label() + " " + (level + 1) + "단계 (" + price + " CR)", -price);
    }

    /** 한 단계 내릴 때 돌려받는 돈 (그 단계 값의 절반, 차 팔 때와 같은 비율) */
    public int downgradeRefund(Part part) {
        Profile.OwnedCar c = profile.selectedCar();
        int level = c.tuning().level(part);
        if (level <= 0) {
            return 0;
        }
        return Math.round(Tuning.price(part, level, market.stockClass(entry(c.carId()).spec())) * Market.RESALE);
    }

    /** 지금 차의 부품을 한 단계 내린다 (그 단계 값의 절반을 돌려받음). 캠버·오프셋 맞춤 값은 남겨 두어 다시 사면 그대로 */
    public Result downgrade(Part part) {
        Profile.OwnedCar c = profile.selectedCar();
        int level = c.tuning().level(part);
        if (level <= 0) {
            return Result.fail(part.label() + ": 이미 순정");
        }
        int price = Tuning.price(part, level, market.stockClass(entry(c.carId()).spec()));
        int back = Math.round(price * Market.RESALE);
        profile.money += back;
        replaceSelected(c.withTuning(c.tuning().with(part, level - 1), -price));
        String to = level - 1 == 0 ? "순정" : (level - 1) + "단계";
        return new Result(true, part.label() + " → " + to + " (+" + back + " CR)", back);
    }

    /** 바디킷 한 칸 바꾸기 (slot: 0 앞, 1 옆, 2 뒤, 3 스포일러, 4 와이드). 내리는 건 무료 */
    public Result setKit(int slot, int level) {
        Profile.OwnedCar c = profile.selectedCar();
        BodyKit k = c.tuning().kit();
        int[] v = {k.front(), k.side(), k.rear(), k.spoiler(), k.widebody()};
        if (slot < 0 || slot >= v.length || level < 0 || level > BodyKit.MAX[slot]) {
            return Result.fail("바디킷 범위 밖");
        }
        int price = level > v[slot] ? Tuning.kitPrice(level) : 0;
        if (profile.money < price) {
            return Result.fail("돈이 모자랍니다: " + price + " CR 필요");
        }
        v[slot] = level;
        profile.money -= price;
        replaceSelected(c.withTuning(c.tuning().withKit(new BodyKit(v[0], v[1], v[2], v[3], v[4])), price));
        return new Result(true, "바디킷 변경 (" + price + " CR)", -price);
    }

    /**
     * 꾸미기 바꾸기 (스타일링 샵). 바뀐 항목마다 값을 매기고, 순정으로 되돌리는 건 무료.
     */
    public Result setLivery(driftengine.vehicle.visual.Livery next) {
        Profile.OwnedCar c = profile.selectedCar();
        int price = Styling.price(c.livery(), next);
        if (price == 0 && next.equals(c.livery())) {
            return Result.fail("바뀐 것이 없습니다");
        }
        if (profile.money < price) {
            return Result.fail("돈이 모자랍니다: " + price + " CR 필요");
        }
        profile.money -= price;
        replaceSelected(c.withLivery(next, price));
        return new Result(true, "스타일 적용 (" + String.format("%,d", price) + " CR)", -price);
    }

    /** 캠버·오프셋 직접 맞추기 (부품이 있어야 열림, 값 바꾸기는 무료) */
    public Result setSetup(Setup s) {
        Profile.OwnedCar c = profile.selectedCar();
        replaceSelected(c.withTuning(c.tuning().withSetup(s), 0));
        return new Result(true, "셋업 저장", 0);
    }

    private void replaceSelected(Profile.OwnedCar c) {
        profile.cars.set(profile.selected, c);
    }

    // ------------------------------------------------------------------ 클럽

    /** 지금 차로 이 클럽에 들어갈 수 있는지 */
    public Result canEnter(Club club) {
        CarClass c = currentClass();
        if (!c.atMost(club.maxClass())) {
            return Result.fail(club.name() + ": 클래스 " + club.maxClass().label() + " 이하만 (지금 차 " + c.label() + ")");
        }
        return new Result(true, "참가 가능", 0);
    }

    /** 이벤트 결과 반영: 새로 얻은 별만큼 상금, 보스면 차 열기. 최고 기록 갱신 */
    public Result finishEvent(ClubEvent event, float result) {
        int stars = event.stars(result);
        int before = profile.stars(event.id());
        Float prevBest = profile.best(event.id());
        boolean better = prevBest == null || (event.type() == ClubEvent.Type.DRIFT_SCORE ? result > prevBest : result < prevBest);
        if (better && result > 0) {
            profile.best.put(event.id(), result);
        }
        if (stars <= before) {
            return new Result(stars > 0, stars == 0 ? "실패 (목표 " + event.target() + ")" : "별 " + stars + "개 (새 보상 없음)", 0);
        }
        long gain = event.rewardFor(stars) - event.rewardFor(before);
        profile.money += gain;
        profile.stars.put(event.id(), stars);
        String msg = "별 " + stars + "개! +" + gain + " CR";
        if (event.boss() && event.unlockCar() != null && before == 0) {
            profile.unlocked.add(event.unlockCar());
            msg += ", " + entry(event.unlockCar()).spec().name() + " 열림 (차고에서 무료로 받기)";
        }
        return new Result(true, msg, gain);
    }

    /** 자유 주행 드리프트 보상: 쌓은 점수 200점당 1 CR */
    public long rewardFreeDrift(long points) {
        long gain = points / 200;
        profile.money += gain;
        return gain;
    }
}
