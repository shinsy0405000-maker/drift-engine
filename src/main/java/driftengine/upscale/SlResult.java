package driftengine.upscale;

/**
 * 브리지 반환 코드를 사람이 읽을 수 있게 바꾼다.
 * <p>
 * 음수: 브리지 자체 오류 (drift_sl_bridge.h 의 DSL_ERR_*).
 * 양수: sl::Result 값. 이름은 SDK 2.14.16 sl_result.h 의 선언 순서(명시 값 없음 → 0부터 증가)이며,
 * 컴파일러로 몇 개 값(31 eErrorFeatureMissing, 32 eErrorFeatureNotSupported, 34 eErrorFeatureFailedToLoad)을 확인했다.
 * SDK 버전이 바뀌면 다시 맞춰야 한다.
 */
public final class SlResult {

    private static final String[] SL_NAMES = {
            "eOk", "eErrorIO", "eErrorDriverOutOfDate", "eErrorOSOutOfDate",
            "eErrorOSDisabledHWS", "eErrorDeviceNotCreated", "eErrorNoSupportedAdapterFound", "eErrorAdapterNotSupported",
            "eErrorNoPlugins", "eErrorVulkanAPI", "eErrorDXGIAPI", "eErrorD3DAPI",
            "eErrorNRDAPI", "eErrorNVAPI", "eErrorReflexAPI", "eErrorNGXFailed",
            "eErrorJSONParsing", "eErrorMissingProxy", "eErrorMissingResourceState", "eErrorInvalidIntegration",
            "eErrorMissingInputParameter", "eErrorNotInitialized", "eErrorComputeFailed", "eErrorInitNotCalled",
            "eErrorExceptionHandler", "eErrorInvalidParameter", "eErrorMissingConstants", "eErrorDuplicatedConstants",
            "eErrorMissingOrInvalidAPI", "eErrorCommonConstantsMissing", "eErrorUnsupportedInterface", "eErrorFeatureMissing",
            "eErrorFeatureNotSupported", "eErrorFeatureMissingHooks", "eErrorFeatureFailedToLoad", "eErrorFeatureWrongPriority",
            "eErrorFeatureMissingDependency", "eErrorFeatureManagerInvalidState", "eErrorInvalidState", "eWarnOutOfVRAM"
    };

    private SlResult() {
    }

    public static String describe(int code) {
        return switch (code) {
            case -1 -> "DSL_ERR_INVALID_ARGUMENT";
            case -2 -> "DSL_ERR_NOT_LOADED";
            case -3 -> "DSL_ERR_NOT_INITIALIZED";
            case -4 -> "DSL_ERR_ALREADY";
            case -5 -> "DSL_ERR_SIGNATURE (서명 검증 실패)";
            case -6 -> "DSL_ERR_LOAD_FAILED";
            case -7 -> "DSL_ERR_EXCEPTION";
            default -> code >= 0 && code < SL_NAMES.length
                    ? "sl::Result::" + SL_NAMES[code] + " (" + code + ")"
                    : "sl::Result(" + code + ")";
        };
    }
}
