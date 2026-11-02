package driftengine.upscale;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/**
 * 가장 단순한 업스케일: 낮은 해상도 이미지를 선형 보간으로 늘린다. 화면이 흐려진다.
 * DLSS 를 붙였을 때 화질을 비교할 기준선이 된다.
 */
public final class BilinearUpscaler implements Upscaler {

    public static final float MIN_SCALE = 0.25f;

    private final float scale;

    /** @param scale 렌더 배율 (0.25 ~ 1.0). 0.5 면 가로세로 절반 해상도로 렌더링 */
    public BilinearUpscaler(float scale) {
        if (!(scale >= MIN_SCALE && scale <= 1.0f)) {
            throw new IllegalArgumentException("렌더 배율은 " + MIN_SCALE + "~1.0: " + scale);
        }
        this.scale = scale;
    }

    public float scale() {
        return scale;
    }

    @Override
    public String name() {
        return "Bilinear " + Math.round(scale * 100) + "%";
    }

    @Override
    public UpscalerStatus status() {
        return new UpscalerStatus(name(), UpscalerStatus.Support.SUPPORTED, true, List.of("GPU 고정 기능 복사(blit)"));
    }

    @Override
    public Resolution renderResolution(int outputWidth, int outputHeight) {
        return new Resolution(scaled(outputWidth, scale), scaled(outputHeight, scale));
    }

    /** 반올림, 최소 1픽셀 */
    static int scaled(int size, float scale) {
        return Math.max(1, Math.round(size * scale));
    }

    @Override
    public void record(VkCommandBuffer cmd, UpscaleInput input) {
        ImageBlit.record(cmd, input);
    }

    @Override
    public void close() {
        // 해제할 자원 없음
    }
}
