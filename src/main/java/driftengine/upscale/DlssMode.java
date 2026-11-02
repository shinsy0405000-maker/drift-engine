package driftengine.upscale;

/**
 * DLSS 품질 모드. slValue 는 sl::DLSSMode 의 값 (sl_dlss.h, SDK 2.14.16).
 * fallbackScale 은 slDLSSGetOptimalSettings 가 실패했을 때만 쓰는 렌더 배율 (DLSS 프로그래밍 가이드의 배율표).
 */
public enum DlssMode {
    QUALITY(3, "품질", 1 / 1.5f),
    BALANCED(2, "균형", 1 / 1.724f),
    PERFORMANCE(1, "성능", 1 / 2.0f),
    ULTRA_PERFORMANCE(4, "울트라 성능", 1 / 3.0f),
    DLAA(6, "DLAA", 1.0f);

    private final int slValue;
    private final String label;
    private final float fallbackScale;

    DlssMode(int slValue, String label, float fallbackScale) {
        this.slValue = slValue;
        this.label = label;
        this.fallbackScale = fallbackScale;
    }

    public int slValue() {
        return slValue;
    }

    public String label() {
        return label;
    }

    public float fallbackScale() {
        return fallbackScale;
    }

    /** R 키 순환: 품질 → 균형 → 성능 → (null = DLSS 끄고 Native) */
    public DlssMode next() {
        return switch (this) {
            case QUALITY -> BALANCED;
            case BALANCED -> PERFORMANCE;
            case PERFORMANCE, ULTRA_PERFORMANCE, DLAA -> null;
        };
    }

    /** 설정 파일 값 (quality, balanced, performance, ultra_performance, dlaa). 모르는 값이면 null. */
    public static DlssMode parse(String text) {
        if (text == null) {
            return null;
        }
        for (DlssMode m : values()) {
            if (m.name().equalsIgnoreCase(text.trim())) {
                return m;
            }
        }
        return null;
    }
}
