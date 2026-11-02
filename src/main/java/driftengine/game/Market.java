package driftengine.game;

import driftengine.vehicle.CarSpec;
import driftengine.vehicle.visual.CarCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 가격과 딜러쉽.
 * <p>
 * 차 가격 = 클래스 기본가 × (1 + 클래스 안에서 위치 × 0.6). 클래스가 높을수록 비싸고, 같은 클래스 안에서도 점수가 높을수록 비싸다.
 * 딜러쉽은 드리프트 차 중 순정 클래스 4 이하만 판다. 서킷 차와 그 위 클래스 차는 클럽 보상으로 얻는다.
 */
public final class Market {

    /** 클래스별 기본가 (CR): 1, 2, 3, 4, 5, CS, CS+ */
    static final int[] CLASS_BASE = {12_000, 25_000, 45_000, 75_000, 120_000, 180_000, 260_000};
    public static final CarClass DEALER_MAX_CLASS = CarClass.C4;
    /** 차를 팔 때 받는 비율 (차값 + 산 부품값) */
    public static final float RESALE = 0.5f;

    private final ClassTable classes;

    public Market(ClassTable classes) {
        this.classes = classes;
    }

    public ClassTable classes() {
        return classes;
    }

    public CarClass stockClass(CarSpec spec) {
        return classes.classOf(PerformanceRating.of(spec));
    }

    public int carPrice(CarSpec spec) {
        int rating = PerformanceRating.of(spec);
        CarClass c = classes.classOf(rating);
        float span = Math.max(1, classes.end(c) - classes.start(c));
        float pos = Math.max(0, Math.min(1, (rating - classes.start(c)) / span));
        return Math.round(CLASS_BASE[c.ordinal()] * (1 + 0.6f * pos) / 500f) * 500;
    }

    /** 딜러쉽에서 파는 차인지: 드리프트 차 + 순정 클래스 4 이하 */
    public boolean soldAtDealer(CarSpec spec) {
        return spec.category().equals("drift") && stockClass(spec).atMost(DEALER_MAX_CLASS);
    }

    /** 딜러쉽 목록: 클래스 순, 같은 클래스는 가격 순 */
    public List<CarCatalog.Entry> dealerList(List<CarCatalog.Entry> catalog) {
        List<CarCatalog.Entry> out = new ArrayList<>();
        for (CarCatalog.Entry e : catalog) {
            if (soldAtDealer(e.spec())) {
                out.add(e);
            }
        }
        out.sort((a, b) -> {
            int c = stockClass(a.spec()).compareTo(stockClass(b.spec()));
            return c != 0 ? c : Integer.compare(carPrice(a.spec()), carPrice(b.spec()));
        });
        return out;
    }
}
