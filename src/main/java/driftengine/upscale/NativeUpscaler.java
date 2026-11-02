package driftengine.upscale;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/**
 * 업스케일 없음. 출력 해상도 그대로 렌더링하고 1:1 로 복사한다. 어떤 GPU 에서도 항상 동작하는 기본값.
 */
public final class NativeUpscaler implements Upscaler {

    @Override
    public String name() {
        return "Native";
    }

    @Override
    public UpscalerStatus status() {
        return new UpscalerStatus(name() + "(업스케일 없음)", UpscalerStatus.Support.SUPPORTED, true, List.of("항상 사용 가능"));
    }

    @Override
    public Resolution renderResolution(int outputWidth, int outputHeight) {
        return new Resolution(outputWidth, outputHeight);
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
