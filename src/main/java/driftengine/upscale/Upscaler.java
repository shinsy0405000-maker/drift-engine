package driftengine.upscale;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * 업스케일러: 낮은 해상도로 렌더링한 화면을 출력 해상도로 키운다.
 * <p>
 * FrameLoop 는 이 인터페이스만 사용한다. 구현:
 * <ul>
 *   <li>NativeUpscaler: 배율 100%, 그대로 복사</li>
 *   <li>BilinearUpscaler: 배율 &lt; 100%, 선형 보간으로 늘림 (DLSS 와 화질 비교 기준)</li>
 *   <li>DlssUpscaler: Streamline 으로 DLSS 실행 (시간적 업스케일러)</li>
 * </ul>
 */
public interface Upscaler extends AutoCloseable {

    /** 실제 렌더링 해상도. 출력 해상도 이하. */
    record Resolution(int width, int height) {
    }

    String name();

    UpscalerStatus status();

    Resolution renderResolution(int outputWidth, int outputHeight);

    /** 업스케일 명령을 기록한다. 레이아웃 조건은 UpscaleInput 설명 참고. */
    void record(VkCommandBuffer cmd, UpscaleInput input);

    /**
     * true 면 여러 프레임을 모아 쓰는 시간적 업스케일러(DLSS): 색·모션 벡터·깊이를 셰이더로 읽으며,
     * 카메라 지터가 켜져 있어야 제 성능이 난다.
     */
    default boolean temporal() {
        return false;
    }

    /** 시간적 업스케일러가 권장하는 지터 단계 수 (렌더/출력 해상도 기준). 복사 방식은 8. */
    default int recommendedJitterPhases(int renderWidth, int outputWidth) {
        return 8;
    }

    @Override
    void close();
}
