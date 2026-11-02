package driftengine.render;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * 한 프레임의 그리기 명령을 기록하는 객체.
 * <p>
 * FrameLoop 는 이 인터페이스만 알고, 무엇을 그리는지는 모른다.
 * 지금은 TriangleRenderer 하나지만, 이후 장면 렌더러로 교체해도 FrameLoop 는 바뀌지 않는다.
 */
public interface Renderer extends AutoCloseable {

    /** 커맨드 버퍼에 그리기 명령을 기록한다. 렌더링 시작/종료(vkCmdBeginRendering/EndRendering)도 여기서 한다. */
    void record(VkCommandBuffer commandBuffer, RenderTarget target);

    /** 스왑체인 재생성으로 출력 포맷이 바뀌었을 때 호출된다. 포맷에 묶인 파이프라인을 다시 만든다. */
    void onTargetFormatChanged(int newFormat);

    /** 해의 화면 위치 [u, v, 앞쪽이면 1] (렌즈 플레어용, 모르면 null) */
    default float[] sunScreen() {
        return null;
    }

    @Override
    void close();
}
