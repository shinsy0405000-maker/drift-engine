package driftengine.vulkan;

import driftengine.core.EngineException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.LongBuffer;
import java.util.List;
import java.util.function.IntPredicate;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 깊이 버퍼: 픽셀마다 "카메라에서 얼마나 먼지"를 저장해, 뒤에 있는 물체가 앞 물체를 덮지 않게 한다.
 * 화면 크기와 같아야 하므로 스왑체인을 다시 만들 때 함께 다시 만든다.
 * <p>
 * 스텐실이 없는 깊이 전용 포맷만 쓴다 (레이아웃/aspect 처리가 단순해짐).
 * DLSS 가 이 깊이 버퍼를 셰이더로 읽으므로 SAMPLED 용도도 켠다(포맷 선택 때 그 기능까지 확인).
 */
public final class DepthBuffer extends DeviceResource {

    /** 선호 순서. D16_UNORM 은 Vulkan 사양상 깊이 첨부로 항상 지원된다. */
    static final List<Integer> CANDIDATES = List.of(
            VK_FORMAT_D32_SFLOAT, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D16_UNORM);

    private final int format;
    private final int width;
    private final int height;
    private long image = VK_NULL_HANDLE;
    private long memory = VK_NULL_HANDLE;
    private long view = VK_NULL_HANDLE;

    /** 깊이 이미지 용도: 깊이 테스트 + 셰이더 읽기(DLSS 입력) */
    public static final int USAGE = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;

    /** 이 GPU 에서 쓸 깊이 포맷을 고른다. */
    public static int chooseFormat(VulkanDevice device) {
        return chooseFormat(format -> device.supportsOptimalTiling(format,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT));
    }

    /** 계산 부분 (GPU 없이 테스트 가능) */
    public static int chooseFormat(IntPredicate supported) {
        for (int format : CANDIDATES) {
            if (supported.test(format)) {
                return format;
            }
        }
        throw new EngineException("지원되는 깊이 버퍼 포맷이 없습니다");
    }

    public DepthBuffer(VulkanDevice device, int format, int width, int height) {
        super(device);
        this.format = format;
        this.width = width;
        this.height = height;
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(USAGE)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().width(width).height(height).depth(1);
            LongBuffer pHandle = stack.mallocLong(1);
            check(vkCreateImage(vk(), info, null, pHandle), "vkCreateImage(depth)");
            image = pHandle.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(vk(), image, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(device.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(vk(), alloc, null, pHandle), "vkAllocateMemory(depth)");
            memory = pHandle.get(0);
            check(vkBindImageMemory(vk(), image, memory, 0), "vkBindImageMemory(depth)");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            check(vkCreateImageView(vk(), viewInfo, null, pHandle), "vkCreateImageView(depth)");
            view = pHandle.get(0);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    public long image() {
        return image;
    }

    public long view() {
        return view;
    }

    public GpuImage asGpuImage() {
        return new GpuImage(image, view, memory, format, width, height, USAGE, VK_IMAGE_ASPECT_DEPTH_BIT);
    }

    public int format() {
        return format;
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
