package driftengine.vulkan;

import driftengine.core.Log;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 화면에 표시될 이미지들의 묶음(VkSwapchainKHR)과 각 이미지의 뷰.
 * <p>
 * 창 크기가 바뀌면 통째로 다시 만든다(FrameLoop 가 담당).
 * 이미지마다 "렌더링 끝남" 세마포어를 하나씩 둔다. present 가 이 세마포어를 기다리므로
 * 프레임 단위가 아니라 이미지 단위로 두어야 재사용 충돌이 생기지 않는다.
 */
public final class Swapchain extends DeviceResource {

    /** 그리기 대상 + 업스케일 복사 대상(TRANSFER_DST). 장면은 오프스크린에 그리고 여기로 복사한다. */
    static final int USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private long handle = VK_NULL_HANDLE;
    private int format;
    private int width;
    private int height;
    private long[] images = new long[0];
    private long[] imageViews = new long[0];
    private long[] renderFinished = new long[0];
    private int presentMode = VK_PRESENT_MODE_FIFO_KHR;

    /**
     * @param framebufferWidth  창의 실제 픽셀 너비 (0 이면 안 됨: 최소화 상태는 호출 전에 걸러야 한다)
     * @param oldSwapchain      재생성 시 이전 스왑체인 핸들, 처음이면 VK_NULL_HANDLE
     */
    public Swapchain(VulkanDevice device, VulkanSurface surface, int framebufferWidth, int framebufferHeight,
                     long oldSwapchain) {
        this(device, surface, framebufferWidth, framebufferHeight, oldSwapchain, true);
    }

    /** @param vsync false 면 수직동기 없이 (IMMEDIATE → MAILBOX → FIFO 순으로 지원되는 것) */
    public Swapchain(VulkanDevice device, VulkanSurface surface, int framebufferWidth, int framebufferHeight,
                     long oldSwapchain, boolean vsync) {
        super(device);
        try {
            create(surface.handle(), framebufferWidth, framebufferHeight, oldSwapchain, vsync);
        } catch (RuntimeException e) {
            destroy(); // 일부만 만들어졌어도 만든 것만 정리
            throw e;
        }
        Log.info("스왑체인 생성: " + width + "x" + height + ", 이미지 " + images.length + "장, 포맷 " + format
                + ", 표시 방식 " + presentModeName(presentMode));
    }

    /**
     * 표시 방식 선택 (GPU 없이 테스트 가능).
     * 수직동기: FIFO (사양상 항상 지원). 끔: IMMEDIATE(대기 없음, 찢김 가능) → MAILBOX(대기 없음, 찢김 없음) → FIFO.
     */
    public static int choosePresentMode(boolean vsync, java.util.Set<Integer> supported) {
        if (!vsync) {
            for (int mode : new int[]{VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR}) {
                if (supported.contains(mode)) {
                    return mode;
                }
            }
        }
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    public static String presentModeName(int mode) {
        return switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE (수직동기 끔)";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX (수직동기 끔, 찢김 없음)";
            case VK_PRESENT_MODE_FIFO_KHR -> "FIFO (수직동기)";
            default -> "모드 " + mode;
        };
    }

    public int presentMode() {
        return presentMode;
    }

    private static java.util.Set<Integer> supportedPresentModes(MemoryStack stack, VkPhysicalDevice physicalDevice, long surface) {
        IntBuffer count = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, null),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        IntBuffer modes = stack.mallocInt(count.get(0));
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, modes),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        java.util.Set<Integer> result = new java.util.HashSet<>();
        for (int i = 0; i < count.get(0); i++) {
            result.add(modes.get(i));
        }
        return result;
    }

    private void create(long surface, int fbWidth, int fbHeight, long oldSwapchain, boolean vsync) {
        VkPhysicalDevice physicalDevice = device.gpu().device();
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, caps),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            int[] surfaceFormat = chooseSurfaceFormat(stack, physicalDevice, surface);
            presentMode = choosePresentMode(vsync, supportedPresentModes(stack, physicalDevice, surface));
            this.format = surfaceFormat[0];

            // currentExtent 가 0xFFFFFFFF 이면 "창 크기에 맞춰 직접 정하라"는 뜻이다.
            if (caps.currentExtent().width() != -1) {
                width = caps.currentExtent().width();
                height = caps.currentExtent().height();
            } else {
                width = clamp(fbWidth, caps.minImageExtent().width(), caps.maxImageExtent().width());
                height = clamp(fbHeight, caps.minImageExtent().height(), caps.maxImageExtent().height());
            }

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                imageCount = caps.maxImageCount();
            }

            if ((caps.supportedUsageFlags() & USAGE) != USAGE) {
                throw new driftengine.core.EngineException(String.format(
                        "스왑체인이 필요한 용도를 지원하지 않습니다 (필요 0x%X, 지원 0x%X)", USAGE, caps.supportedUsageFlags()));
            }

            int compositeAlpha = (caps.supportedCompositeAlpha() & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) != 0
                    ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                    : Integer.lowestOneBit(caps.supportedCompositeAlpha());

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(format)
                    .imageColorSpace(surfaceFormat[1])
                    .imageArrayLayers(1)
                    .imageUsage(USAGE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(compositeAlpha)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);
            createInfo.imageExtent().width(width).height(height);

            GpuInfo gpu = device.gpu();
            if (gpu.graphicsFamily() != gpu.presentFamily()) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(gpu.graphicsFamily(), gpu.presentFamily()));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            LongBuffer pSwapchain = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(vk(), createInfo, null, pSwapchain), "vkCreateSwapchainKHR");
            handle = pSwapchain.get(0);

            IntBuffer count = stack.mallocInt(1);
            check(vkGetSwapchainImagesKHR(vk(), handle, count, null), "vkGetSwapchainImagesKHR");
            LongBuffer pImages = stack.mallocLong(count.get(0));
            check(vkGetSwapchainImagesKHR(vk(), handle, count, pImages), "vkGetSwapchainImagesKHR");

            int n = count.get(0);
            images = new long[n];
            imageViews = new long[n];
            renderFinished = new long[n];
            for (int i = 0; i < n; i++) {
                images[i] = pImages.get(i);
                imageViews[i] = createImageView(stack, images[i]);
                renderFinished[i] = createSemaphore(stack);
            }
        }
    }

    private long createImageView(MemoryStack stack, long image) {
        VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        info.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
        LongBuffer pView = stack.mallocLong(1);
        check(vkCreateImageView(vk(), info, null, pView), "vkCreateImageView");
        return pView.get(0);
    }

    private long createSemaphore(MemoryStack stack) {
        VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        LongBuffer pSemaphore = stack.mallocLong(1);
        check(vkCreateSemaphore(vk(), info, null, pSemaphore), "vkCreateSemaphore");
        return pSemaphore.get(0);
    }

    /** sRGB 포맷을 우선한다. 셰이더가 선형 색을 출력하면 GPU가 감마 보정을 해준다. */
    private static int[] chooseSurfaceFormat(MemoryStack stack, VkPhysicalDevice physicalDevice, long surface) {
        IntBuffer count = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, formats),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");

        for (int preferred : new int[]{VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_R8G8B8A8_SRGB}) {
            for (VkSurfaceFormatKHR f : formats) {
                if (f.format() == preferred && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    return new int[]{f.format(), f.colorSpace()};
                }
            }
        }
        VkSurfaceFormatKHR first = formats.get(0);
        Log.warn("sRGB 서피스 포맷 없음 → 첫 번째 포맷 사용: " + first.format());
        return new int[]{first.format(), first.colorSpace()};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public long handle() {
        return handle;
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

    public int imageCount() {
        return images.length;
    }

    public long image(int index) {
        return images[index];
    }

    public long imageView(int index) {
        return imageViews[index];
    }

    public long renderFinishedSemaphore(int index) {
        return renderFinished[index];
    }

    @Override
    protected void destroy() {
        for (long semaphore : renderFinished) {
            if (semaphore != VK_NULL_HANDLE) {
                vkDestroySemaphore(vk(), semaphore, null);
            }
        }
        for (long view : imageViews) {
            if (view != VK_NULL_HANDLE) {
                vkDestroyImageView(vk(), view, null);
            }
        }
        // images 는 스왑체인 소유라 직접 파괴하지 않는다.
        if (handle != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(vk(), handle, null);
        }
    }
}
