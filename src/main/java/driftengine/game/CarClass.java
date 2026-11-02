package driftengine.game;

/**
 * 차 클래스 (낮음 → 높음): 1, 2, 3, 4, 5, CS, CS+. 성능 점수(PerformanceRating)의 구간으로 정한다.
 * 구간 경계는 ClassTable 이 설정 파일에서 읽는다 (기본값은 이 프로젝트가 정한 값).
 */
public enum CarClass {
    C1("1"), C2("2"), C3("3"), C4("4"), C5("5"), CS("CS"), CS_PLUS("CS+");

    private final String label;

    CarClass(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean atMost(CarClass other) {
        return ordinal() <= other.ordinal();
    }

    public static CarClass fromLabel(String text) {
        for (CarClass c : values()) {
            if (c.label.equalsIgnoreCase(text.trim())) {
                return c;
            }
        }
        throw new IllegalArgumentException("클래스는 1,2,3,4,5,CS,CS+ 중 하나: " + text);
    }
}
