package driftengine.vulkan;

import org.lwjgl.vulkan.VkDevice;

import java.util.Objects;

/**
 * VkDevice 에 속한 GPU 자원(스왑체인, 셰이더, 파이프라인 등)의 공통 부모 클래스.
 * <p>
 * close() 를 final 로 막아 두 번 해제되는 것을 방지하고,
 * 실제 해제 작업은 하위 클래스가 destroy() 에서 구현한다 (템플릿 메서드 패턴).
 * <p>
 * 하위 클래스 생성자에서 일부만 만들고 실패할 수 있으므로,
 * destroy() 는 아직 만들어지지 않은 핸들(VK_NULL_HANDLE)을 건너뛰도록 작성해야 한다.
 */
public abstract class DeviceResource implements AutoCloseable {

    protected final VulkanDevice device;
    private boolean closed;

    protected DeviceResource(VulkanDevice device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    /** 하위 클래스에서 Vulkan 함수를 호출할 때 쓰는 VkDevice 핸들. */
    protected final VkDevice vk() {
        return device.handle();
    }

    public final boolean isClosed() {
        return closed;
    }

    @Override
    public final void close() {
        if (closed) {
            return;
        }
        closed = true;
        destroy();
    }

    /** 이 객체가 만든 Vulkan 핸들을 모두 파괴한다. VK_NULL_HANDLE 인 핸들은 건너뛴다. */
    protected abstract void destroy();
}
