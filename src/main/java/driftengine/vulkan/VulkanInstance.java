package driftengine.vulkan;

import driftengine.core.Log;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * VkInstance + (선택) 검증 레이어와 디버그 메신저.
 * <p>
 * 검증 레이어는 Vulkan SDK(LunarG)가 설치된 개발 PC에만 있다. 없으면 경고 후 계속 진행한다.
 * 이렇게 해야 SDK 없는 PC(일반 사용자)에서도 실행된다.
 */
public final class VulkanInstance implements AutoCloseable {

    static final int REQUIRED_API_VERSION = VK_API_VERSION_1_3;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    private static final AtomicInteger VALIDATION_ERRORS = new AtomicInteger();

    private final VkInstance instance;
    private final long debugMessenger;
    private final VkDebugUtilsMessengerCallbackEXT debugCallback;
    private final boolean validationEnabled;

    public VulkanInstance(String applicationName, boolean requestValidation) {
        int loaderVersion = VK.getInstanceVersionSupported();
        Log.info("Vulkan 로더 버전: " + versionString(loaderVersion));
        if (Integer.compareUnsigned(loaderVersion, REQUIRED_API_VERSION) < 0) {
            throw new IllegalStateException("Vulkan 1.3 이상이 필요합니다. 현재 로더: " + versionString(loaderVersion)
                    + " → GPU 드라이버를 최신으로 업데이트하세요.");
        }

        VkDebugUtilsMessengerCallbackEXT callback = null;
        VkInstance created = null;
        try (MemoryStack stack = stackPush()) {
            boolean layerAvailable = requestValidation && isLayerAvailable(stack, VALIDATION_LAYER);
            if (requestValidation && !layerAvailable) {
                Log.warn("검증 레이어 " + VALIDATION_LAYER + " 없음 → 비활성화 (개발 PC 라면 Vulkan SDK 설치 권장)");
            }
            Set<String> available = availableInstanceExtensions();
            boolean useDebugUtils = layerAvailable && available.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);

            PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) {
                throw new IllegalStateException("GLFW 가 Vulkan 서피스 확장을 제공하지 못합니다.");
            }

            PointerBuffer extensions = stack.mallocPointer(glfwExtensions.remaining() + (useDebugUtils ? 1 : 0));
            for (int i = glfwExtensions.position(); i < glfwExtensions.limit(); i++) {
                String name = glfwExtensions.getStringUTF8(i);
                Log.info("  인스턴스 확장: " + name);
                extensions.put(glfwExtensions.get(i));
            }
            if (useDebugUtils) {
                extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            }
            extensions.flip();

            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8(applicationName))
                    .applicationVersion(VK_MAKE_API_VERSION(0, 0, 1, 0))
                    .pEngineName(stack.UTF8("DriftEngine"))
                    .engineVersion(VK_MAKE_API_VERSION(0, 0, 1, 0))
                    .apiVersion(REQUIRED_API_VERSION);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(extensions);

            VkDebugUtilsMessengerCreateInfoEXT messengerInfo = null;
            if (layerAvailable) {
                createInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
            }
            if (useDebugUtils) {
                callback = VkDebugUtilsMessengerCallbackEXT.create(VulkanInstance::onDebugMessage);
                messengerInfo = messengerCreateInfo(stack, callback);
                // pNext 체인: vkCreateInstance / vkDestroyInstance 자체의 문제도 보고받는다.
                createInfo.pNext(messengerInfo);
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
            created = new VkInstance(pInstance.get(0), createInfo);

            long messenger = VK_NULL_HANDLE;
            if (useDebugUtils) {
                LongBuffer pMessenger = stack.mallocLong(1);
                check(vkCreateDebugUtilsMessengerEXT(created, messengerInfo, null, pMessenger),
                        "vkCreateDebugUtilsMessengerEXT");
                messenger = pMessenger.get(0);
            }

            this.instance = created;
            this.debugMessenger = messenger;
            this.debugCallback = callback;
            this.validationEnabled = layerAvailable;
        } catch (RuntimeException e) {
            if (created != null) {
                vkDestroyInstance(created, null);
            }
            if (callback != null) {
                callback.free();
            }
            throw e;
        }
        Log.info("VkInstance 생성 (검증 레이어: " + (validationEnabled ? "켜짐" : "꺼짐") + ")");
    }

    public VkInstance handle() {
        return instance;
    }

    public boolean validationEnabled() {
        return validationEnabled;
    }

    @Override
    public void close() {
        if (debugMessenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
        vkDestroyInstance(instance, null);
        if (debugCallback != null) {
            debugCallback.free();
        }
    }

    // ------------------------------------------------------------------

    private static VkDebugUtilsMessengerCreateInfoEXT messengerCreateInfo(MemoryStack stack,
                                                                         VkDebugUtilsMessengerCallbackEXT callback) {
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(callback);
    }

    private static int onDebugMessage(int severity, int types, long pCallbackData, long pUserData) {
        String message = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData).pMessageString();
        boolean error = (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0;
        boolean validation = (types & VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT) != 0;

        if (error && validation) {
            // 우리 코드의 Vulkan 사용 오류. 종료 시 개수를 보고한다.
            VALIDATION_ERRORS.incrementAndGet();
            Log.error("[Vulkan 검증] " + message);
        } else if (!validation) {
            // 로더/드라이버의 일반 메시지(예: 지워진 Steam 레이어 JSON). 우리 코드 문제가 아니므로 WARN.
            Log.warn("[Vulkan 로더] " + message);
        } else {
            Log.warn("[Vulkan 검증] " + message);
        }
        return VK_FALSE; // 호출을 중단하지 않는다 (사양상 항상 VK_FALSE).
    }

    /** 지금까지 발생한 검증 레이어 오류 수. 0 이어야 정상. */
    public static int validationErrorCount() {
        return VALIDATION_ERRORS.get();
    }

    private static boolean isLayerAvailable(MemoryStack stack, String layerName) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateInstanceLayerProperties(count, null), "vkEnumerateInstanceLayerProperties");
        if (count.get(0) == 0) {
            return false;
        }
        try (VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0))) {
            check(vkEnumerateInstanceLayerProperties(count, layers), "vkEnumerateInstanceLayerProperties");
            for (VkLayerProperties layer : layers) {
                if (layer.layerNameString().equals(layerName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> availableInstanceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumerateInstanceExtensionProperties((String) null, count, null),
                    "vkEnumerateInstanceExtensionProperties");
            Set<String> names = new HashSet<>();
            // 확장 목록은 수백 개일 수 있어 스택(64KB) 대신 힙에 할당한다.
            try (VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0))) {
                check(vkEnumerateInstanceExtensionProperties((String) null, count, props),
                        "vkEnumerateInstanceExtensionProperties");
                for (VkExtensionProperties p : props) {
                    names.add(p.extensionNameString());
                }
            }
            return names;
        }
    }

    static String versionString(int version) {
        return VK_API_VERSION_MAJOR(version) + "." + VK_API_VERSION_MINOR(version) + "." + VK_API_VERSION_PATCH(version);
    }
}
