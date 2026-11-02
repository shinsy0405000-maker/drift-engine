package driftengine.upscale;

import java.util.List;

/**
 * 업스케일러 상태 보고. "지원 여부"와 "실제 동작 여부"를 분리해서 기록한다.
 *
 * @param name      업스케일러 이름
 * @param support   지원 여부 판정 단계
 * @param active    지금 실제로 업스케일을 수행하고 있는지
 * @param checks    판정 근거 (통과/실패한 항목들)
 */
public record UpscalerStatus(String name, Support support, boolean active, List<String> checks) {

    public enum Support {
        /** 확인된 조건 중 하나 이상이 맞지 않음 → 사용 불가 */
        UNSUPPORTED,
        /** 확인 가능한 조건은 통과했지만, 공식 API(slIsFeatureSupported)로는 아직 확인하지 못함 */
        NOT_VERIFIED,
        /** 공식 API 로 지원이 확인됨 */
        SUPPORTED
    }

    public UpscalerStatus {
        checks = List.copyOf(checks); // 외부에서 목록을 바꾸지 못하게 복사
    }

    public String describe() {
        String supportText = switch (support) {
            case UNSUPPORTED -> "지원 안 됨";
            case NOT_VERIFIED -> "지원 미확인";
            case SUPPORTED -> "지원 확인";
        };
        return name + " [" + supportText + ", 실제 동작: " + (active ? "예" : "아니오") + "]";
    }
}
