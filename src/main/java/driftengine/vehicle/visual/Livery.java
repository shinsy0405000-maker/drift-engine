package driftengine.vehicle.visual;

import driftengine.math.Vec3;

import java.util.List;

/**
 * 스타일링 샵에서 바꾸는 겉모양 (차 한 대마다 저장). -1 / null = 순정 그대로.
 *
 * @param paint    차체 색 (PAINTS 번호)
 * @param finish   도장 마감 (FINISHES 번호: 솔리드, 메탈릭, 무광, 크롬)
 * @param rimFrom  휠 디자인을 가져올 차 id (그 차의 휠을 이 차 크기에 맞춰 늘임)
 * @param rimColor 휠 색 (RIM_COLORS 번호)
 * @param caliper  캘리퍼 색 (CALIPERS 번호)
 * @param tint     유리 틴트 단계 (0 기본 ~ 3 리무진)
 */
public record Livery(int paint, int finish, String rimFrom, int rimColor, int caliper, int tint) {

    /** 이름 + 색 (sRGB 0~1) */
    public record Swatch(String name, float r, float g, float b) {
        public Vec3 linear() {
            return new Vec3(CarStyle.srgbToLinear(r), CarStyle.srgbToLinear(g), CarStyle.srgbToLinear(b));
        }

        /** UI 용 0xRRGGBBAA */
        public int rgba() {
            return (Math.round(r * 255) << 24) | (Math.round(g * 255) << 16) | (Math.round(b * 255) << 8) | 0xFF;
        }
    }

    public static final List<Swatch> PAINTS = List.of(
            new Swatch("화이트", 0.93f, 0.93f, 0.92f), new Swatch("펄 화이트", 0.88f, 0.87f, 0.84f),
            new Swatch("실버", 0.66f, 0.68f, 0.70f), new Swatch("건메탈", 0.30f, 0.32f, 0.35f),
            new Swatch("블랙", 0.03f, 0.03f, 0.035f), new Swatch("레드", 0.78f, 0.06f, 0.05f),
            new Swatch("버건디", 0.42f, 0.04f, 0.08f), new Swatch("오렌지", 0.95f, 0.38f, 0.04f),
            new Swatch("옐로", 0.97f, 0.78f, 0.05f), new Swatch("라임", 0.55f, 0.85f, 0.10f),
            new Swatch("브리티시 그린", 0.05f, 0.25f, 0.13f), new Swatch("민트", 0.45f, 0.85f, 0.72f),
            new Swatch("스카이 블루", 0.38f, 0.66f, 0.92f), new Swatch("로열 블루", 0.07f, 0.20f, 0.65f),
            new Swatch("미드나잇", 0.04f, 0.07f, 0.20f), new Swatch("퍼플", 0.38f, 0.12f, 0.62f),
            new Swatch("핑크", 0.95f, 0.45f, 0.65f), new Swatch("나르도 그레이", 0.55f, 0.56f, 0.55f),
            new Swatch("샌드", 0.76f, 0.66f, 0.48f), new Swatch("브론즈", 0.55f, 0.36f, 0.18f));

    public static final List<String> FINISHES = List.of("솔리드", "메탈릭", "무광", "크롬");

    public static final List<Swatch> RIM_COLORS = List.of(
            new Swatch("실버", 0.72f, 0.72f, 0.74f), new Swatch("건메탈", 0.22f, 0.23f, 0.25f),
            new Swatch("블랙", 0.03f, 0.03f, 0.03f), new Swatch("화이트", 0.92f, 0.92f, 0.92f),
            new Swatch("골드", 0.80f, 0.62f, 0.25f), new Swatch("브론즈", 0.52f, 0.36f, 0.20f),
            new Swatch("레드", 0.75f, 0.06f, 0.05f), new Swatch("블루", 0.10f, 0.30f, 0.80f));

    public static final List<Swatch> CALIPERS = List.of(
            new Swatch("레드", 0.70f, 0.04f, 0.03f), new Swatch("옐로", 0.95f, 0.75f, 0.05f),
            new Swatch("골드", 0.85f, 0.60f, 0.08f), new Swatch("블루", 0.05f, 0.18f, 0.75f),
            new Swatch("그린", 0.10f, 0.55f, 0.20f), new Swatch("블랙", 0.03f, 0.03f, 0.03f));

    public static final List<String> TINTS = List.of("기본", "라이트", "다크", "리무진");
    /** 틴트 단계 → 유리 재질 값 (glass.frag: 0 = 기본 짙기 0.30, 1 = 0.85) */
    public static final float[] TINT_VALUE = {0f, 0.27f, 0.64f, 1f};

    public static final Livery STOCK = new Livery(-1, -1, null, -1, -1, 0);

    public Livery {
        if (paint < -1 || paint >= PAINTS.size() || finish < -1 || finish >= FINISHES.size()
                || rimColor < -1 || rimColor >= RIM_COLORS.size() || caliper < -1 || caliper >= CALIPERS.size()
                || tint < 0 || tint >= TINTS.size()) {
            throw new IllegalArgumentException("꾸미기 값 범위 오류: paint " + paint + ", finish " + finish + ", rimColor " + rimColor
                    + ", caliper " + caliper + ", tint " + tint);
        }
        if (rimFrom != null && rimFrom.isBlank()) {
            rimFrom = null;
        }
    }

    public Livery withPaint(int v) {
        return new Livery(v, finish, rimFrom, rimColor, caliper, tint);
    }

    public Livery withFinish(int v) {
        return new Livery(paint, v, rimFrom, rimColor, caliper, tint);
    }

    public Livery withRimFrom(String v) {
        return new Livery(paint, finish, v, rimColor, caliper, tint);
    }

    public Livery withRimColor(int v) {
        return new Livery(paint, finish, rimFrom, v, caliper, tint);
    }

    public Livery withCaliper(int v) {
        return new Livery(paint, finish, rimFrom, rimColor, v, tint);
    }

    public Livery withTint(int v) {
        return new Livery(paint, finish, rimFrom, rimColor, caliper, v);
    }

    /** 저장용 "paint,finish,rimFrom,rimColor,caliper,tint" (rimFrom 없으면 -) */
    public String text() {
        return paint + "," + finish + "," + (rimFrom == null ? "-" : rimFrom) + "," + rimColor + "," + caliper + "," + tint;
    }

    public static Livery parse(String text) {
        if (text == null || text.isBlank()) {
            return STOCK;
        }
        String[] p = text.trim().split(",");
        if (p.length != 6) {
            throw new IllegalArgumentException("꾸미기 값은 6개: " + text);
        }
        String rim = p[2].trim();
        return new Livery(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), rim.equals("-") ? null : rim,
                Integer.parseInt(p[3].trim()), Integer.parseInt(p[4].trim()), Integer.parseInt(p[5].trim()));
    }
}
