package driftengine.render;

import java.util.Locale;

/**
 * 그래픽 효과 켜고 끄기 (시스템 속성, 예: -Ddrift.ao=off). 효과 하나가 문제를 일으키면 그것만 끄고 실행할 수 있게.
 *
 * @param shadows       해 그림자 (단계 4개)
 * @param shadowSize    그림자 맵 한 단계 해상도
 * @param ao            화면 공간 AO
 * @param flakes        메탈릭 도장 반짝이
 * @param glass         투명 유리 (끄면 유리를 그리지 않음)
 * @param heightFog     높이 안개 세기 (0 = 끔)
 * @param motionBlur    모션 블러 세기 (0 = 끔)
 * @param flare         렌즈 플레어 세기 (0 = 끔)
 * @param grain         필름 그레인 세기
 * @param chromatic     색수차 세기
 * @param bloom         블룸 세기
 * @param agx           톤매핑 AgX (false = ACES)
 * @param saturation    채도
 */
public record RenderSettings(boolean shadows, int shadowSize, boolean ao, boolean flakes, boolean glass, float heightFog,
                             float motionBlur, float flare, float grain, float chromatic, float bloom, boolean agx,
                             float saturation) {

    public static final RenderSettings ULTRA = new RenderSettings(true, 4096, true, true, true, 0.0025f,
            0.8f, 1.0f, 0.02f, 0.7f, 0.07f, false, 1.12f);

    /** 시스템 속성으로 덮어쓴 설정 */
    public static RenderSettings fromSystem() {
        RenderSettings d = ULTRA;
        return new RenderSettings(
                on("drift.shadows", d.shadows),
                (int) num("drift.shadowSize", d.shadowSize),
                on("drift.ao", d.ao),
                on("drift.flakes", d.flakes),
                on("drift.glass", d.glass),
                num("drift.fog", d.heightFog),
                num("drift.mblur", d.motionBlur),
                num("drift.flare", d.flare),
                num("drift.grain", d.grain),
                num("drift.ca", d.chromatic),
                num("drift.bloom", d.bloom),
                "agx".equalsIgnoreCase(System.getProperty("drift.tonemap", d.agx ? "agx" : "aces")),
                num("drift.saturation", d.saturation));
    }

    private static boolean on(String key, boolean def) {
        String v = System.getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("off") || v.equals("false") || v.equals("0"));
    }

    private static float num(String key, float def) {
        String v = System.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "그림자 %s(%d), AO %s, 도장입자 %s, 유리 %s, 높이안개 %.4f, 모션블러 %.2f, 플레어 %.2f, 그레인 %.3f, 색수차 %.2f, 블룸 %.3f, 톤매핑 %s",
                shadows ? "켬" : "끔", shadowSize, ao ? "켬" : "끔", flakes ? "켬" : "끔", glass ? "켬" : "끔", heightFog,
                motionBlur, flare, grain, chromatic, bloom, agx ? "AgX" : "ACES");
    }
}
