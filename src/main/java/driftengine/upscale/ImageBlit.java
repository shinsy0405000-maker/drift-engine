package driftengine.upscale;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU 고정 기능 복사(vkCmdBlitImage)로 이미지를 늘리거나 그대로 옮긴다. 선형(bilinear) 필터 사용.
 * 업스케일러들이 공유한다.
 */
final class ImageBlit {

    private ImageBlit() {
    }

    static void record(VkCommandBuffer cmd, UpscaleInput in) {
        record(cmd, in.sourceImage(), in.sourceWidth(), in.sourceHeight(),
                in.destinationImage(), in.destinationWidth(), in.destinationHeight(), VK_FILTER_LINEAR);
    }

    /** source = TRANSFER_SRC_OPTIMAL, destination = TRANSFER_DST_OPTIMAL 이어야 한다. */
    static void record(VkCommandBuffer cmd, long source, int sourceWidth, int sourceHeight,
                       long destination, int destinationWidth, int destinationHeight, int filter) {
        try (MemoryStack stack = stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            VkImageBlit blit = region.get(0);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.srcOffsets(0).set(0, 0, 0);
            blit.srcOffsets(1).set(sourceWidth, sourceHeight, 1);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.dstOffsets(0).set(0, 0, 0);
            blit.dstOffsets(1).set(destinationWidth, destinationHeight, 1);

            vkCmdBlitImage(cmd,
                    source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region, filter);
        }
    }
}
