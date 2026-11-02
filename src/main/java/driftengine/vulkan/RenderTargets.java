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
 * 렌더 해상도로 그리는 화면 밖(오프스크린) 이미지 묶음: 색 + 모션 벡터 + 깊이.
 * <p>
 * 장면은 여기에 그리고, 업스케일러가 색 이미지를 출력 해상도의 스왑체인 이미지로 옮긴다.
 * <ul>
 *   <li>색: 16비트 실수(HDR) 우선. DLSS 도 HDR 색 입력을 기본으로 한다</li>
 *   <li>모션 벡터: R16G16 실수. 픽셀마다 "이전 프레임에서의 위치 - 현재 위치"(화면 비율 단위)</li>
 *   <li>깊이: DepthBuffer</li>
 * </ul>
 * 해상도나 배율이 바뀌면 통째로 다시 만든다.
 */
public final class RenderTargets extends DeviceResource {

    /** 색 이미지에 필요한 기능: 그리기 + 복사 원본 + 선형 필터 */
    static final int REQUIRED_COLOR_FEATURES = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
            | VK_FORMAT_FEATURE_BLIT_SRC_BIT
            | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;

    /** 모션 벡터 포맷 (Vulkan 사양상 색 첨부로 항상 지원되는 포맷) */
    public static final int MOTION_FORMAT = VK_FORMAT_R16G16_SFLOAT;

    private final int colorFormat;
    private final int width;
    private final int height;
    /** 색 이미지 용도: 그리기 + 업스케일 복사 원본 + 셰이더 읽기(DLSS 입력) */
    public static final int COLOR_USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_SAMPLED_BIT;
    /** 모션 벡터 용도: 그리기 + 셰이더 읽기(DLSS 입력) */
    public static final int MOTION_USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;

    private long[] color;   // {image, memory, view}
    private long[] motion;  // {image, memory, view}
    private DepthBuffer depth;

    /** 색 포맷 선택: HDR(R16G16B16A16_SFLOAT) 우선, 안 되면 fallback(보통 스왑체인 포맷). */
    public static int chooseColorFormat(VulkanDevice device, int fallback) {
        return chooseColorFormat(f -> device.supportsOptimalTiling(f, REQUIRED_COLOR_FEATURES), fallback);
    }

    /** 계산 부분 (GPU 없이 테스트 가능) */
    public static int chooseColorFormat(IntPredicate supported, int fallback) {
        for (int format : List.of(VK_FORMAT_R16G16B16A16_SFLOAT, fallback)) {
            if (supported.test(format)) {
                return format;
            }
        }
        throw new EngineException("오프스크린 색 이미지로 쓸 포맷이 없습니다");
    }

    public RenderTargets(VulkanDevice device, int colorFormat, int depthFormat, int width, int height) {
        super(device);
        if (width <= 0 || height <= 0) {
            throw new EngineException("렌더 해상도는 양수여야 합니다: " + width + "x" + height);
        }
        this.colorFormat = colorFormat;
        this.width = width;
        this.height = height;
        try {
            color = createImage(colorFormat, COLOR_USAGE, "color");
            motion = createImage(MOTION_FORMAT, MOTION_USAGE, "motion");
            depth = new DepthBuffer(device, depthFormat, width, height);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    /** @return {image, memory, view} */
    private long[] createImage(int format, int usage, String label) {
        long[] handles = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
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
            handles[0] = pHandle.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(vk(), handles[0], req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(device.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(vk(), alloc, null, pHandle), "vkAllocateMemory(" + label + ")");
            handles[1] = pHandle.get(0);
            check(vkBindImageMemory(vk(), handles[0], handles[1], 0), "vkBindImageMemory(" + label + ")");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(handles[0])
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            check(vkCreateImageView(vk(), viewInfo, null, pHandle), "vkCreateImageView(" + label + ")");
            handles[2] = pHandle.get(0);
            return handles;
        } catch (RuntimeException e) {
            destroyImage(handles);
            throw e;
        }
    }

    private void destroyImage(long[] handles) {
        if (handles == null) {
            return;
        }
        if (handles[2] != VK_NULL_HANDLE) {
            vkDestroyImageView(vk(), handles[2], null);
        }
        if (handles[0] != VK_NULL_HANDLE) {
            vkDestroyImage(vk(), handles[0], null);
        }
        if (handles[1] != VK_NULL_HANDLE) {
            vkFreeMemory(vk(), handles[1], null);
        }
    }

    public long colorImage() {
        return color[0];
    }

    public long colorView() {
        return color[2];
    }

    public int colorFormat() {
        return colorFormat;
    }

    public long motionImage() {
        return motion[0];
    }

    public long motionView() {
        return motion[2];
    }

    public GpuImage colorGpuImage() {
        return new GpuImage(color[0], color[2], color[1], colorFormat, width, height, COLOR_USAGE, VK_IMAGE_ASPECT_COLOR_BIT);
    }

    public GpuImage motionGpuImage() {
        return new GpuImage(motion[0], motion[2], motion[1], MOTION_FORMAT, width, height, MOTION_USAGE,
                VK_IMAGE_ASPECT_COLOR_BIT);
    }

    public DepthBuffer depth() {
        return depth;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    @Override
    protected void destroy() {
        if (depth != null) {
            depth.close();
        }
        destroyImage(motion);
        destroyImage(color);
    }
}
