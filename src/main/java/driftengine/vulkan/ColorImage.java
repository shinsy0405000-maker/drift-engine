package driftengine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 색 이미지 하나 (이미지 + GPU 메모리 + 뷰). 예: DLSS 결과를 받는 출력 해상도 이미지.
 */
public final class ColorImage extends DeviceResource {

    private final int format;
    private final int width;
    private final int height;
    private final int usage;
    private long image = VK_NULL_HANDLE;
    private long memory = VK_NULL_HANDLE;
    private long view = VK_NULL_HANDLE;

    public ColorImage(VulkanDevice device, int format, int width, int height, int usage, String label) {
        super(device);
        this.format = format;
        this.width = width;
        this.height = height;
        this.usage = usage;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().width(width).height(height).depth(1);
            LongBuffer pHandle = stack.mallocLong(1);
            check(vkCreateImage(vk(), info, null, pHandle), "vkCreateImage(" + label + ")");
            image = pHandle.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(vk(), image, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(device.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(vk(), alloc, null, pHandle), "vkAllocateMemory(" + label + ")");
            memory = pHandle.get(0);
            check(vkBindImageMemory(vk(), image, memory, 0), "vkBindImageMemory(" + label + ")");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            check(vkCreateImageView(vk(), viewInfo, null, pHandle), "vkCreateImageView(" + label + ")");
            view = pHandle.get(0);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    public GpuImage asGpuImage() {
        return new GpuImage(image, view, memory, format, width, height, usage, VK_IMAGE_ASPECT_COLOR_BIT);
    }

    public long image() {
        return image;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    @Override
    protected void destroy() {
        if (view != VK_NULL_HANDLE) {
            vkDestroyImageView(vk(), view, null);
        }
        if (image != VK_NULL_HANDLE) {
            vkDestroyImage(vk(), image, null);
        }
        if (memory != VK_NULL_HANDLE) {
            vkFreeMemory(vk(), memory, null);
        }
    }
}
