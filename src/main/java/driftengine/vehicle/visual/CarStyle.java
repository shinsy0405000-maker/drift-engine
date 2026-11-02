package driftengine.vehicle.visual;

import driftengine.math.Vec3;
import driftengine.vehicle.CarSpec;

import java.util.Locale;
import java.util.Properties;

/**
 * 차 겉모양 수치 (차 파일의 style.* 키). 실제 차 디자인을 옮기지 않고, 차급 느낌(쿠페, 해치백, 프로토타입)을
 * 숫자로 정해 CarBodyBuilder 가 차체를 만든다.
 * <p>
 * 높이는 모두 땅에서 잰 값(m), 위치(…U)는 차 앞끝 0 ~ 뒤끝 1 비율.
 *
 * @param length       차 길이 (보통 물리 상자 길이와 같음)
 * @param width        차 폭 (펜더 포함)
 * @param noseH        앞끝 윗선 높이
 * @param hoodH        보닛 끝(앞유리 시작) 높이
 * @param roofH        지붕 높이
 * @param deckH        트렁크 높이 (뒷유리 끝)
 * @param tailH        뒤끝 윗선 높이
 * @param sillH        사이드실(문 아래) 높이
 * @param beltH        유리 아랫선 높이
 * @param cowlU        앞유리 시작 위치
 * @param roofStartU   지붕 시작 (앞유리 끝)
 * @param roofEndU     지붕 끝 (뒷유리 시작)
 * @param deckU        뒷유리 끝
 * @param cabinWidth   지붕 폭 / 차 폭 (0.6~0.85)
 * @param flare        펜더가 바퀴 위에서 불룩한 정도 (m)
 * @param color        도장 색 (선형 RGB)
 * @param metallicPaint 메탈릭 도장인지
 * @param headlight    0 가는 라이트, 1 둥근(팝업 느낌), 2 픽셀(사각 여러 개)
 * @param taillight    0 가로 바, 1 둥근 4개
 * @param bPillar      문 사이 기둥(4도어·해치백)
 * @param rimStyle     휠: 0 5스포크, 1 10스포크, 2 메시, 3 디스크(전기차), 4 6스포크
 * @param rimColor     휠 색
 * @param tireWidthF   앞 타이어 폭
 * @param tireWidthR   뒤 타이어 폭
 * @param kit          기본 바디킷
 * @param livery       스타일링 샵 꾸미기 (색·휠 색은 withLivery 가 위 값에 반영해 둠, 나머지는 CarModels 가 읽음)
 */
public record CarStyle(float length, float width, float noseH, float hoodH, float roofH, float deckH, float tailH,
                       float sillH, float beltH, float cowlU, float roofStartU, float roofEndU, float deckU,
                       float cabinWidth, float flare, Vec3 color, boolean metallicPaint, int headlight, int taillight,
                       boolean bPillar, int rimStyle, Vec3 rimColor, float tireWidthF, float tireWidthR, BodyKit kit,
                       Livery livery) {

    public CarStyle {
        if (!(length > 1 && width > 0.5f && roofH > hoodH && hoodH > sillH && sillH > 0
                && cowlU > 0 && roofStartU > cowlU && roofEndU > roofStartU && deckU > roofEndU && deckU < 1
                && cabinWidth > 0.3f && cabinWidth <= 1 && rimStyle >= 0 && rimStyle <= 4)) {
            throw new IllegalArgumentException("차 모양 값 범위 오류 (높이 순서: 사이드실 < 보닛 < 지붕, 위치 순서: 앞유리 < 지붕 < 뒷유리 < 1)");
        }
    }

    /** 기본: 물리 상자 크기에 맞춘 쿠페 */
    public static CarStyle defaults(CarSpec spec) {
        Vec3 h = spec.halfExtents();
        return new CarStyle(h.z() * 2, h.x() * 2, 0.62f, 0.86f, 1.28f, 0.98f, 0.95f, 0.22f, 0.92f,
                0.36f, 0.52f, 0.70f, 0.86f, 0.74f, 0.03f, new Vec3(0.55f, 0.04f, 0.03f), true, 0, 0, false, 0,
                new Vec3(0.72f, 0.72f, 0.74f), 0.235f, 0.255f, BodyKit.STOCK, Livery.STOCK);
    }

    /** 차 파일의 style.* 값으로 기본값을 덮어쓴다 */
    public static CarStyle fromProperties(CarSpec spec, Properties p) {
        CarStyle d = defaults(spec);
        return new CarStyle(
                f(p, "style.length", d.length), f(p, "style.width", d.width),
                f(p, "style.noseH", d.noseH), f(p, "style.hoodH", d.hoodH), f(p, "style.roofH", d.roofH),
                f(p, "style.deckH", d.deckH), f(p, "style.tailH", d.tailH), f(p, "style.sillH", d.sillH),
                f(p, "style.beltH", d.beltH), f(p, "style.cowlU", d.cowlU), f(p, "style.roofStartU", d.roofStartU),
                f(p, "style.roofEndU", d.roofEndU), f(p, "style.deckU", d.deckU),
                f(p, "style.cabinWidth", d.cabinWidth), f(p, "style.flare", d.flare),
                color(p, "style.color", d.color), p.getProperty("style.paint", "metallic").trim().toLowerCase(Locale.ROOT).equals("metallic"),
                (int) f(p, "style.headlight", d.headlight), (int) f(p, "style.taillight", d.taillight),
                Boolean.parseBoolean(p.getProperty("style.bPillar", Boolean.toString(d.bPillar)).trim()),
                (int) f(p, "style.rim", d.rimStyle), color(p, "style.rimColor", d.rimColor),
                f(p, "style.tireWidthF", d.tireWidthF), f(p, "style.tireWidthR", d.tireWidthR),
                p.getProperty("style.kit") != null ? BodyKit.parse(p.getProperty("style.kit")) : d.kit, Livery.STOCK);
    }

    public CarStyle withKit(BodyKit k) {
        return new CarStyle(length, width, noseH, hoodH, roofH, deckH, tailH, sillH, beltH, cowlU, roofStartU, roofEndU,
                deckU, cabinWidth, flare, color, metallicPaint, headlight, taillight, bPillar, rimStyle, rimColor,
                tireWidthF, tireWidthR, k, livery);
    }

    /**
     * 순정 모양에 꾸미기를 입힌다 (색·마감·휠 색은 여기서 바로 바꾸고, 나머지는 livery 로 넘김).
     * 순정 style 에 불러야 한다 (이미 입힌 것에 또 부르면 순정 색을 잃음).
     */
    public CarStyle withLivery(Livery l) {
        Vec3 c = l.paint() >= 0 ? Livery.PAINTS.get(l.paint()).linear() : color;
        boolean metallic = l.finish() >= 0 ? l.finish() == 1 : metallicPaint;
        Vec3 rim = l.rimColor() >= 0 ? Livery.RIM_COLORS.get(l.rimColor()).linear() : rimColor;
        return new CarStyle(length, width, noseH, hoodH, roofH, deckH, tailH, sillH, beltH, cowlU, roofStartU, roofEndU,
                deckU, cabinWidth, flare, c, metallic, headlight, taillight, bPillar, rimStyle, rim,
                tireWidthF, tireWidthR, kit, l);
    }

    private static float f(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' 는 숫자여야 합니다: " + v);
        }
    }

    /** "r,g,b" (0~1, sRGB 로 적으면 선형으로 바꿔 둔다) */
    private static Vec3 color(Properties p, String key, Vec3 def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        String[] c = v.trim().split(",");
        if (c.length != 3) {
            throw new IllegalArgumentException("'" + key + "' 는 r,g,b (0~1): " + v);
        }
        return new Vec3(srgbToLinear(Float.parseFloat(c[0].trim())), srgbToLinear(Float.parseFloat(c[1].trim())),
                srgbToLinear(Float.parseFloat(c[2].trim())));
    }

    static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }
}
