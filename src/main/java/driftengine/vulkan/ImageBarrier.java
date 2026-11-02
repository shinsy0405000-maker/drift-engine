package driftengine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;

/** 이미지 레이아웃 전환 + 실행/메모리 의존성 (synchronization2). */
public final class ImageBarrier {

    private ImageBarrier() {
    }

    public static void transition(MemoryStack stack, VkCommandBuffer cmd, long image, int aspect,
                                  int oldLayout, int newLayout,
                                  long srcStage, long srcAccess, long dstStage, long dstAccess) {
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcStageMask(srcStage)
                .srcAccessMask(srcAccess)
                .dstStageMask(dstStage)
                .dstAccessMask(dstAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(aspect)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(barrier);
        vkCmdPipelineBarrier2(cmd, dependency);
    }
}
