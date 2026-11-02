package driftengine.game;

import java.util.Locale;

/**
 * 튜닝샵에서 직접 맞추는 값 (부품을 산 뒤 열림).
 *
 * @param camberFront 앞 캠버 (도, 0 ~ -8). 캠버 3단계(풀 커스텀)일 때만 씀
 * @param camberRear  뒤 캠버 (도, 0 ~ -5)
 * @param offsetFront 앞 휠 오프셋 (mm, 0 ~ 40, 바깥으로). 휠 오프셋 부품이 있을 때만 씀
 * @param offsetRear  뒤 휠 오프셋 (mm)
 */
public record Setup(float camberFront, float camberRear, float offsetFront, float offsetRear) {

    public static final Setup DEFAULT = new Setup(-4.5f, -1.5f, 15f, 15f);
    public static final float CAMBER_FRONT_MIN = -8f;
    public static final float CAMBER_REAR_MIN = -5f;
    public static final float OFFSET_MAX = 40f;
    public static final float CAMBER_STEP = 0.5f;
    public static final float OFFSET_STEP = 5f;

    public Setup {
        camberFront = clamp(round(camberFront, CAMBER_STEP), CAMBER_FRONT_MIN, 0);
        camberRear = clamp(round(camberRear, CAMBER_STEP), CAMBER_REAR_MIN, 0);
        offsetFront = clamp(round(offsetFront, OFFSET_STEP), 0, OFFSET_MAX);
        offsetRear = clamp(round(offsetRear, OFFSET_STEP), 0, OFFSET_MAX);
    }

    public Setup withCamberFront(float v) {
        return new Setup(v, camberRear, offsetFront, offsetRear);
    }

    public Setup withCamberRear(float v) {
        return new Setup(camberFront, v, offsetFront, offsetRear);
    }

    public Setup withOffsetFront(float v) {
        return new Setup(camberFront, camberRear, v, offsetRear);
    }

    public Setup withOffsetRear(float v) {
        return new Setup(camberFront, camberRear, offsetFront, v);
    }

    public String text() {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.0f,%.0f", camberFront, camberRear, offsetFront, offsetRear);
    }

    public static Setup parse(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT;
        }
        String[] p = text.trim().split(",");
        if (p.length != 4) {
            throw new IllegalArgumentException("셋업 값은 4개 (앞 캠버, 뒤 캠버, 앞 오프셋, 뒤 오프셋): " + text);
        }
        return new Setup(Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()), Float.parseFloat(p[2].trim()),
                Float.parseFloat(p[3].trim()));
    }

    private static float round(float v, float step) {
        return Math.round(v / step) * step;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
