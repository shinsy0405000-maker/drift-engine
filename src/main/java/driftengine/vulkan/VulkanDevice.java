package driftengine.vulkan;

import driftengine.core.Log;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkQueue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * 논리 디바이스(VkDevice)와 graphics / present 큐.
 * <p>
 * Vulkan 1.3 의 dynamicRendering(렌더 패스 객체 불필요)과 synchronization2 를 켠다.
 * <p>
 * Streamline(sl.interposer) 경유일 때: interposer 가 vkCreateDevice 를 가로채 DLSS 에 필요한 확장과
 * 1.2 기능(timelineSemaphore, descriptorIndexing, bufferDeviceAddress)을 스스로 추가한다
 * (SDK 2.14.16 소스 sl.interposer/vulkan/wrapper.cpp 확인). 다만 그대로 두면 검증 오류 2건이 난다.
 * <ul>
 *   <li>DLSS 플러그인이 옛 확장 VK_EXT_buffer_device_address 를 요구하는데, 1.2 기능의 bufferDeviceAddress 와
 *       같이 켜면 규격 위반(VUID-VkDeviceCreateInfo-pNext-04748). interposer 는 VK_KHR_buffer_device_address 가
 *       목록에 있으면 EXT 쪽을 빼 주므로, 우리가 KHR 확장을 넣는다.</li>
 *   <li>Streamline 내부(sl.chi)가 기능 확인 없이 vkCreatePrivateDataSlot 을 부르므로 1.3 의 privateData 를 켠다.</li>
 * </ul>
 */
public final class VulkanDevice implements AutoCloseable {

    private final GpuInfo gpu;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    /** 메모리 타입 i 의 속성 플래그 (VkMemoryPropertyFlags) */
    private final int[] memoryTypeFlags;

    /** Streamline 경유일 때 추가하는 확장 (설명은 클래스 주석) */
    public static final String KHR_BUFFER_DEVICE_ADDRESS = "VK_KHR_buffer_device_address";

    public VulkanDevice(GpuInfo gpu) {
        this(gpu, false);
    }

    /** @param streamline sl.interposer.dll 을 Vulkan 로더로 쓰는 중이면 true */
    public VulkanDevice(GpuInfo gpu, boolean streamline) {
        this.gpu = gpu;
        this.memoryTypeFlags = queryMemoryTypes(gpu);
        try (MemoryStack stack = stackPush()) {
            Set<Integer> uniqueFamilies = new LinkedHashSet<>();
            uniqueFamilies.add(gpu.graphicsFamily());
            uniqueFamilies.add(gpu.presentFamily());

            VkDeviceQueueCreateInfo.Buffer queueInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size(), stack);
            int i = 0;
            for (int family : uniqueFamilies) {
                queueInfos.get(i++)
                        .sType$Default()
                        .queueFamilyIndex(family)
                        .pQueuePriorities(stack.floats(1.0f));
            }

            boolean privateData = streamline && supportsPrivateData(gpu, stack);
            VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                    .sType$Default()
                    .dynamicRendering(true)
                    .synchronization2(true)
                    // glslc(vulkan1.3) 는 discard 를 OpDemoteToHelperInvocation 으로 만든다 → 이 기능을 켜야 함 (1.3 필수 기능)
                    .shaderDemoteToHelperInvocation(true)
                    .privateData(privateData);
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType$Default()
                    .pNext(features13.address());

            List<String> extensionNames = deviceExtensions(streamline, streamline ? availableExtensions(gpu, stack) : Set.of());
            PointerBuffer extensions = stack.mallocPointer(extensionNames.size());
            for (String ext : extensionNames) {
                extensions.put(stack.UTF8(ext));
            }
            extensions.flip();

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(features2.address()) // features2 를 쓸 때 pEnabledFeatures 는 반드시 NULL
                    .pQueueCreateInfos(queueInfos)
                    .ppEnabledExtensionNames(extensions);

            PointerBuffer pDevice = stack.mallocPointer(1);
            check(vkCreateDevice(gpu.device(), createInfo, null, pDevice), "vkCreateDevice");
            this.device = new VkDevice(pDevice.get(0), gpu.device(), createInfo, VulkanInstance.REQUIRED_API_VERSION);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, gpu.graphicsFamily(), 0, pQueue);
            this.graphicsQueue = new VkQueue(pQueue.get(0), device);
            vkGetDeviceQueue(device, gpu.presentFamily(), 0, pQueue);
            this.presentQueue = new VkQueue(pQueue.get(0), device);
            Log.info("VkDevice 생성 (dynamicRendering, synchronization2" + (privateData ? ", privateData" : "")
                    + ", " + extensionNames + (streamline ? ", Streamline 이 DLSS 요구 사항 추가" : "") + ")");
        }
    }

    /**
     * 우리가 직접 켜는 디바이스 확장 목록 (GPU 없이 테스트 가능).
     * Streamline 경유이고 GPU 가 지원하면 VK_KHR_buffer_device_address 를 더한다.
     */
    public static List<String> deviceExtensions(boolean streamline, Set<String> available) {
        List<String> list = new ArrayList<>(PhysicalDeviceSelector.REQUIRED_DEVICE_EXTENSIONS);
        if (streamline && available.contains(KHR_BUFFER_DEVICE_ADDRESS)) {
            list.add(KHR_BUFFER_DEVICE_ADDRESS);
        }
        return list;
    }

    private static Set<String> availableExtensions(GpuInfo gpu, MemoryStack stack) {
        var count = stack.mallocInt(1);
        check(vkEnumerateDeviceExtensionProperties(gpu.device(), (String) null, count, null),
                "vkEnumerateDeviceExtensionProperties");
        Set<String> names = new HashSet<>();
        // NVIDIA 드라이버는 디바이스 확장이 200개를 넘어(약 60KB) 스택(64KB)이 모자라므로 힙을 쓴다.
        try (VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0))) {
            check(vkEnumerateDeviceExtensionProperties(gpu.device(), (String) null, count, props),
                    "vkEnumerateDeviceExtensionProperties");
            for (VkExtensionProperties p : props) {
                names.add(p.extensionNameString());
            }
        }
        return names;
    }

    private static boolean supportsPrivateData(GpuInfo gpu, MemoryStack stack) {
        VkPhysicalDeviceVulkan13Features supported13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
        VkPhysicalDeviceFeatures2 supported = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType$Default()
                .pNext(supported13.address());
        vkGetPhysicalDeviceFeatures2(gpu.device(), supported);
        return supported13.privateData();
    }

    public GpuInfo gpu() {
        return gpu;
    }

    public VkDevice handle() {
        return device;
    }

    public VkQueue graphicsQueue() {
        return graphicsQueue;
    }

    public VkQueue presentQueue() {
        return presentQueue;
    }

    private static int[] queryMemoryTypes(GpuInfo gpu) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(gpu.device(), props);
            int[] flags = new int[props.memoryTypeCount()];
            for (int i = 0; i < flags.length; i++) {
                flags[i] = props.memoryTypes(i).propertyFlags();
            }
            return flags;
        }
    }

    /**
     * 버퍼/이미지가 허용하는 메모리 타입(typeBits) 중 원하는 속성(required)을 모두 가진 첫 타입.
     *
     * @throws EngineException 맞는 타입이 없으면
     */
    public int findMemoryType(int typeBits, int required) {
        int index = selectMemoryType(typeBits, required, memoryTypeFlags);
        if (index < 0) {
            throw new driftengine.core.EngineException(String.format(
                    "맞는 GPU 메모리 타입이 없습니다 (허용 0x%X, 필요 속성 0x%X)", typeBits, required));
        }
        return index;
    }

    /** findMemoryType 의 계산 부분 (GPU 없이 테스트 가능). 없으면 -1. */
    public static int selectMemoryType(int typeBits, int required, int[] typeFlags) {
        for (int i = 0; i < typeFlags.length && i < 32; i++) {
            boolean allowed = (typeBits & (1 << i)) != 0;
            if (allowed && (typeFlags[i] & required) == required) {
                return i;
            }
        }
        return -1;
    }

    /** 이 GPU 가 해당 포맷을 optimal 타일링에서 features 용도로 지원하는지 */
    public boolean supportsOptimalTiling(int format, int features) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(gpu.device(), format, props);
            return (props.optimalTilingFeatures() & features) == features;
        }
    }

    public void waitIdle() {
        int result = vkDeviceWaitIdle(device);
        if (result != VK_SUCCESS) {
            Log.warn("vkDeviceWaitIdle: " + VulkanException.resultName(result));
        }
    }

    @Override
    public void close() {
        vkDestroyDevice(device, null);
    }
}
