package driftengine.upscale;

import driftengine.core.Log;
import driftengine.vulkan.GpuInfo;
import driftengine.vulkan.VulkanDevice;

/**
 * 사용할 업스케일러를 고른다. 원칙: DLSS 가 확실히 동작할 때만 쓰고, 아니면 배율에 맞는 기본 업스케일러.
 */
public final class UpscalerSelector {

    /** R 키로 순환하는 렌더 배율 */
    static final float[] SCALE_STEPS = {1.0f, 0.75f, 0.5f};

    private UpscalerSelector() {
    }

    /**
     * 시작 시 호출: DLSS 상태를 점검·기록하고, 배율에 맞는 업스케일러를 돌려준다.
     *
     * @param dlssSession --dlss 로 Streamline 이 시작된 경우의 세션, 아니면 null
     * @param renderScale 설정의 렌더 배율 (0.25~1.0)
     * @param device      DLSS 출력 이미지를 만들 디바이스 (DLSS 는 디바이스 생성 후에만 고를 수 있다)
     * @param dlssMode    DLSS 를 쓸 때의 품질 모드
     */
    public static Upscaler select(boolean dlssRequested, GpuInfo gpu, DlssSession dlssSession, float renderScale,
                                  VulkanDevice device, DlssMode dlssMode) {
        UpscalerStatus dlss = DlssPrecheck.run(gpu, DlssPrecheck.DEFAULT_DIR);
        if (dlssSession != null) {
            int code = dlssSession.checkSupport(gpu);
            dlss = DlssPrecheck.withOfficialResult(dlss, code, dlssSession.lastError());
        }

        Log.info("DLSS 점검: " + dlss.describe());
        for (String check : dlss.checks()) {
            Log.info("    " + check);
        }

        if (dlssRequested) {
            if (dlss.support() == UpscalerStatus.Support.SUPPORTED && dlssSession != null) {
                if (DlssUpscaler.outputFormatSupported(device)) {
                    Upscaler chosen = new DlssUpscaler(dlssSession, device, dlssMode);
                    Log.info("업스케일러 선택: " + chosen.status().describe() + " (실제 동작은 첫 slEvaluateFeature 결과로 판정)");
                    return chosen;
                }
                Log.warn("DLSS 지원 확인됨. 하지만 출력 이미지 포맷(R16G16B16A16_SFLOAT storage)이 안 돼 기본 업스케일러로 진행");
            } else {
                Log.warn("DLSS 가 요청됐지만 사용할 수 없습니다 (" + dlss.support() + ") → 기본 업스케일러로 진행");
            }
        }

        Upscaler chosen = forScale(renderScale);
        Log.info("업스케일러 선택: " + chosen.status().describe());
        return chosen;
    }

    /** 배율 100% 면 Native, 그보다 작으면 Bilinear */
    public static Upscaler forScale(float renderScale) {
        return renderScale >= 0.999f ? new NativeUpscaler() : new BilinearUpscaler(renderScale);
    }

    /** 다음 배율 (100 → 75 → 50 → 100%). 목록에 없는 값이면 100% 로. */
    public static float nextScale(float current) {
        for (int i = 0; i < SCALE_STEPS.length; i++) {
            if (Math.abs(SCALE_STEPS[i] - current) < 1e-3f) {
                return SCALE_STEPS[(i + 1) % SCALE_STEPS.length];
            }
        }
        return SCALE_STEPS[0];
    }
}
