package driftengine.vulkan;

import driftengine.core.Log;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * 사용할 GPU 를 고른다. 요구 사항을 만족하지 못하는 GPU 는 거절 사유를 로그로 남긴다.
 * <p>
 * 요구 사항: Vulkan 1.3, VK_KHR_swapchain, graphics + present 큐,
 * dynamicRendering / synchronization2 기능, 서피스 포맷과 present 모드가 1개 이상.
 * 점수: 외장 GPU 우선, graphics 와 present 가 같은 큐면 가산점.
 */
public final class PhysicalDeviceSelector {

    static final List<String> REQUIRED_DEVICE_EXTENSIONS = List.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME);

    private PhysicalDeviceSelector() {
    }

    public static GpuInfo select(VulkanInstance instance, VulkanSurface surface) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance.handle(), count, null), "vkEnumeratePhysicalDevices");
            if (count.get(0) == 0) {
                throw new IllegalStateException("Vulkan 을 지원하는 GPU 가 없습니다.");
            }
            PointerBuffer handles = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance.handle(), count, handles), "vkEnumeratePhysicalDevices");

            GpuInfo best = null;
            int bestScore = -1;
            for (int i = 0; i < handles.capacity(); i++) {
                VkPhysicalDevice device = new VkPhysicalDevice(handles.get(i), instance.handle());
                Candidate candidate = evaluate(device, surface.handle());
                if (candidate.rejection != null) {
                    Log.info("  GPU[" + i + "] 제외: " + candidate.name + " - " + candidate.rejection);
                    continue;
                }
                Log.info("  GPU[" + i + "] 후보: " + candidate.info.describe() + " 점수=" + candidate.score);
                if (candidate.score > bestScore) {
                    best = candidate.info;
                    bestScore = candidate.score;
                }
            }
            if (best == null) {
                throw new IllegalStateException("요구 사항(Vulkan 1.3, swapchain, dynamic rendering)을 만족하는 GPU 가 없습니다.");
            }
            return best;
        }
    }

    private record Candidate(String name, GpuInfo info, int score, String rejection) {
        static Candidate reject(String name, String reason) {
            return new Candidate(name, null, -1, reason);
        }
    }

    private static Candidate evaluate(VkPhysicalDevice device, long surface) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device, props);
            String name = props.deviceNameString();

            if (Integer.compareUnsigned(props.apiVersion(), VulkanInstance.REQUIRED_API_VERSION) < 0) {
                return Candidate.reject(name, "Vulkan " + VulkanInstance.versionString(props.apiVersion()) + " (1.3 필요)");
            }

            Set<String> missing = missingExtensions(device);
            if (!missing.isEmpty()) {
                return Candidate.reject(name, "확장 없음 " + missing);
            }

            VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType$Default()
                    .pNext(features13.address());
            vkGetPhysicalDeviceFeatures2(device, features2);
            if (!features13.dynamicRendering() || !features13.synchronization2() || !features13.shaderDemoteToHelperInvocation()) {
                return Candidate.reject(name, "dynamicRendering/synchronization2/shaderDemoteToHelperInvocation 미지원");
            }

            int[] families = findQueueFamilies(stack, device, surface);
            if (families == null) {
                return Candidate.reject(name, "graphics 또는 present 큐 없음");
            }

            IntBuffer n = stack.mallocInt(1);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, n, null), "vkGetPhysicalDeviceSurfaceFormatsKHR");
            int formatCount = n.get(0);
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, n, null), "vkGetPhysicalDeviceSurfacePresentModesKHR");
            int presentModeCount = n.get(0);
            if (formatCount == 0 || presentModeCount == 0) {
                return Candidate.reject(name, "서피스 포맷/present 모드 없음");
            }

            int score = switch (props.deviceType()) {
                case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 1000;
                case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 100;
                case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 50;
                default -> 1; // CPU 렌더러(lavapipe 등)는 최후의 수단
            };
            if (families[0] == families[1]) {
                score += 10;
            }

            GpuInfo info = new GpuInfo(device, name, props.vendorID(), props.deviceType(),
                    props.apiVersion(), props.driverVersion(), families[0], families[1]);
            return new Candidate(name, info, score, null);
        }
    }

    /** @return {graphics, present} 또는 null. 두 기능을 모두 가진 패밀리를 우선한다. */
    private static int[] findQueueFamilies(MemoryStack stack, VkPhysicalDevice device, long surface) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);

        IntBuffer presentSupport = stack.mallocInt(1);
        int graphics = -1;
        int present = -1;
        for (int i = 0; i < families.capacity(); i++) {
            boolean hasGraphics = (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
            check(vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport),
                    "vkGetPhysicalDeviceSurfaceSupportKHR");
            boolean hasPresent = presentSupport.get(0) == VK_TRUE;

            if (hasGraphics && hasPresent) {
                return new int[]{i, i};
            }
            if (hasGraphics && graphics < 0) {
                graphics = i;
            }
            if (hasPresent && present < 0) {
                present = i;
            }
        }
        return (graphics >= 0 && present >= 0) ? new int[]{graphics, present} : null;
    }

    private static Set<String> missingExtensions(VkPhysicalDevice device) {
        Set<String> missing = new HashSet<>(REQUIRED_DEVICE_EXTENSIONS);
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumerateDeviceExtensionProperties(device, (String) null, count, null),
                    "vkEnumerateDeviceExtensionProperties");
            // NVIDIA 드라이버는 디바이스 확장이 200개를 넘어 스택 대신 힙을 쓴다.
            try (VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0))) {
                check(vkEnumerateDeviceExtensionProperties(device, (String) null, count, props),
                        "vkEnumerateDeviceExtensionProperties");
                for (VkExtensionProperties p : props) {
                    missing.remove(p.extensionNameString());
                }
            }
        }
        return missing;
    }
}
