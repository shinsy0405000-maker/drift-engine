package driftengine.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 동시에 처리 중인 프레임(frames in flight)마다 필요한 자원.
 * <p>
 * CPU 가 다음 프레임 명령을 기록하는 동안 GPU 는 이전 프레임을 그릴 수 있도록 2벌을 둔다.
 * 프레임마다: 커맨드 버퍼 1개, "이미지 획득" 세마포어 1개, "GPU 작업 끝남" 펜스 1개.
 */
public final class FrameResources extends DeviceResource {

    public static final int FRAMES_IN_FLIGHT = 2;

    private long commandPool = VK_NULL_HANDLE;
    private final VkCommandBuffer[] commandBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] imageAvailable = new long[FRAMES_IN_FLIGHT];
    private final long[] inFlight = new long[FRAMES_IN_FLIGHT];

    public FrameResources(VulkanDevice device) {
        super(device);
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT) // 버퍼를 프레임마다 개별 리셋
                    .queueFamilyIndex(device.gpu().graphicsFamily());
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(vk(), poolInfo, null, pPool), "vkCreateCommandPool");
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(FRAMES_IN_FLIGHT);
            PointerBuffer pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT);
            check(vkAllocateCommandBuffers(vk(), allocInfo, pBuffers), "vkAllocateCommandBuffers");

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            // 첫 프레임에서 기다리다 멈추지 않도록 펜스는 "신호된 상태"로 만든다.
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pHandle = stack.mallocLong(1);

            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                commandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), vk());
                check(vkCreateSemaphore(vk(), semaphoreInfo, null, pHandle), "vkCreateSemaphore");
                imageAvailable[i] = pHandle.get(0);
                check(vkCreateFence(vk(), fenceInfo, null, pHandle), "vkCreateFence");
                inFlight[i] = pHandle.get(0);
            }
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    public VkCommandBuffer commandBuffer(int frame) {
        return commandBuffers[frame];
    }

    public long imageAvailableSemaphore(int frame) {
        return imageAvailable[frame];
    }

    public long inFlightFence(int frame) {
        return inFlight[frame];
    }

    @Override
    protected void destroy() {
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            if (inFlight[i] != VK_NULL_HANDLE) {
                vkDestroyFence(vk(), inFlight[i], null);
            }
            if (imageAvailable[i] != VK_NULL_HANDLE) {
                vkDestroySemaphore(vk(), imageAvailable[i], null);
            }
        }
        if (commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(vk(), commandPool, null); // 풀에서 할당한 커맨드 버퍼도 함께 해제됨
        }
    }
}
