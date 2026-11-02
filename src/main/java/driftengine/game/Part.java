package driftengine.game;

/**
 * 튜닝 부품 종류. 레벨 0 = 순정, 1.. = 단계. 가격은 단계별 기본가 × 차 클래스 배율 (Tuning.price).
 * 순서는 저장 파일(부품 단계 목록)과 맞물려 있으므로 새 부품은 맨 뒤에만 붙인다.
 */
public enum Part {
    ENGINE("흡배기", Category.ENGINE, 0, new int[]{2500, 6000, 11000}),
    TURBO("터보", Category.ENGINE, 5, new int[]{6000, 10000, 18000}),
    WEIGHT("경량화", Category.WEIGHT, 0, new int[]{2000, 5000, 11000}),
    TIRES("타이어 컴파운드", Category.TIRES, 0, new int[]{1500, 4000, 9000}),
    SUSPENSION("서스펜션", Category.CHASSIS, 0, new int[]{3000, 7000}),
    BRAKES("브레이크", Category.CHASSIS, 1, new int[]{2000, 5000}),
    LSD("LSD", Category.GEAR, 1, new int[]{2500, 6000}),
    GEARBOX("변속기", Category.GEAR, 0, new int[]{8000}),
    STEERING("조향각 키트", Category.CHASSIS, 2, new int[]{2000, 5000}),
    FUEL_TANK("경량 연료통", Category.FUEL, 0, new int[]{1500, 4500}),
    AERO("에어로 다운포스", Category.AERO, 0, new int[]{3000, 7000, 13000}),
    ECU("ECU", Category.ENGINE, 1, new int[]{3000, 7500}),
    CAMSHAFT("하이캠", Category.ENGINE, 2, new int[]{3500, 8000, 15000}),
    CYLINDER("실린더 (보어업)", Category.ENGINE, 3, new int[]{6000, 14000}),
    IGNITION("점화 (플러그 · 코일)", Category.ENGINE, 4, new int[]{1200, 3000}),
    SUPERCHARGER("차저 (슈퍼차저)", Category.ENGINE, 6, new int[]{8000, 13000, 21000}),
    NITRO("니트로", Category.ENGINE, 7, new int[]{5000, 9000, 15000}),
    CAMBER("캠버", Category.CHASSIS, 3, new int[]{1500, 4000, 9000}),
    OFFSET("휠 오프셋 (스페이서)", Category.CHASSIS, 4, new int[]{2500});

    /** 튜닝샵 분류 (화면 왼쪽 탭 순서) */
    public enum Category {
        ENGINE("엔진", "출력과 부스트"),
        GEAR("기어", "변속과 구동"),
        FUEL("연료통", "무게 줄이기"),
        AERO("다운포스", "고속 접지력"),
        WEIGHT("경량", "차체 무게"),
        CHASSIS("하체", "서스펜션 · 브레이크 · 조향"),
        TIRES("타이어", "접지력");

        private final String label;
        private final String hint;

        Category(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        public String label() {
            return label;
        }

        public String hint() {
            return hint;
        }

        /** 이 분류의 부품 (화면 순서) */
        public java.util.List<Part> parts() {
            java.util.List<Part> out = new java.util.ArrayList<>();
            for (Part p : Part.values()) {
                if (p.category == this) {
                    out.add(p);
                }
            }
            out.sort(java.util.Comparator.comparingInt(p -> p.order));
            return out;
        }
    }

    private final String label;
    private final Category category;
    /** 분류 안 화면 순서 */
    private final int order;
    private final int[] basePrices;

    Part(String label, Category category, int order, int[] basePrices) {
        this.label = label;
        this.category = category;
        this.order = order;
        this.basePrices = basePrices;
    }

    public String label() {
        return label;
    }

    public Category category() {
        return category;
    }

    public int maxLevel() {
        return basePrices.length;
    }

    /** level 단계(1부터)의 기본 가격 */
    public int basePrice(int level) {
        return basePrices[level - 1];
    }
}
