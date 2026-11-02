package driftengine.game;

import java.util.Properties;

/**
 * 성능 점수 → 클래스 구간. 경계값 = 그 클래스가 시작하는 점수.
 * 기본: 1 (~449), 2 (450~), 3 (500~), 4 (550~), 5 (650~), CS (750~), CS+ (850~).
 * game.properties 의 class.2=450 같은 줄로 바꿀 수 있다.
 */
public record ClassTable(int[] starts) {

    public static final ClassTable DEFAULT = new ClassTable(new int[]{0, 450, 500, 550, 650, 750, 850});

    public ClassTable {
        if (starts.length != CarClass.values().length) {
            throw new IllegalArgumentException("클래스 경계는 " + CarClass.values().length + "개");
        }
        for (int i = 1; i < starts.length; i++) {
            if (starts[i] <= starts[i - 1]) {
                throw new IllegalArgumentException("클래스 경계는 오름차순이어야 합니다");
            }
        }
        starts = starts.clone();
    }

    public CarClass classOf(int rating) {
        CarClass result = CarClass.C1;
        for (CarClass c : CarClass.values()) {
            if (rating >= starts[c.ordinal()]) {
                result = c;
            }
        }
        return result;
    }

    public int start(CarClass c) {
        return starts[c.ordinal()];
    }

    /** 다음 클래스 시작 점수 (CS+ 는 1000) */
    public int end(CarClass c) {
        return c.ordinal() + 1 < starts.length ? starts[c.ordinal() + 1] : 1000;
    }

    public static ClassTable from(Properties p) {
        int[] s = DEFAULT.starts.clone();
        for (CarClass c : CarClass.values()) {
            String v = p.getProperty("class." + c.label());
            if (v != null) {
                s[c.ordinal()] = Integer.parseInt(v.trim());
            }
        }
        return new ClassTable(s);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClassTable t && java.util.Arrays.equals(starts, t.starts);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(starts);
    }

    @Override
    public String toString() {
        return "ClassTable" + java.util.Arrays.toString(starts);
    }
}
