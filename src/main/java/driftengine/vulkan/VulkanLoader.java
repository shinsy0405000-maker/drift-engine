package driftengine.vulkan;

import driftengine.core.EngineException;
import driftengine.core.Log;
import org.lwjgl.system.Configuration;
import org.lwjgl.vulkan.VK;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFWVulkan.glfwInitVulkanLoader;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * 어떤 Vulkan 로더 DLL 을 쓸지 정한다. 반드시 창(GLFW)과 VkInstance 를 만들기 전에 호출해야 한다.
 * <p>
 * 기본(경로 없음): 지금까지처럼 LWJGL 과 GLFW 가 각자 시스템의 vulkan-1.dll 을 로드한다.
 * <p>
 * 경로 지정: DLSS(Streamline) 통합 준비용. Streamline 문서는 vulkan-1.dll 대신 sl.interposer.dll 을
 * 로드하라고 요구한다. 이때 LWJGL 뿐 아니라 GLFW(창 서피스 생성)도 같은 로더를 써야 하므로
 * glfwInitVulkanLoader 로 vkGetInstanceProcAddr 를 GLFW 에 넘겨준다.
 * 이 경로가 실제로 Streamline 과 잘 동작하는지는 아직 검증하지 않았다(Stage 4).
 */
public final class VulkanLoader implements AutoCloseable {

    private final Path customLibrary;

    private VulkanLoader(Path customLibrary) {
        this.customLibrary = customLibrary;
    }

    /** @param library null 이면 시스템 기본 로더 */
    public static VulkanLoader configure(Path library) {
        if (library == null) {
            return new VulkanLoader(null);
        }
        Path absolute = library.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new EngineException("Vulkan 로더 파일이 없습니다: " + absolute);
        }

        // VK 클래스가 기본 로더를 자동으로 로드하지 않도록 막고, 지정한 DLL 로 직접 초기화한다.
        Configuration.VULKAN_EXPLICIT_INIT.set(true);
        VK.create(absolute.toString()); // Streamline 문서: 상대 경로 금지 → 항상 절대 경로

        long getInstanceProcAddr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
        if (getInstanceProcAddr == NULL) {
            VK.destroy();
            throw new EngineException("vkGetInstanceProcAddr 를 찾을 수 없습니다: " + absolute);
        }
        // GLFW 도 같은 로더를 쓰게 한다. glfwInit 전에 호출해야 효과가 있다.
        glfwInitVulkanLoader(getInstanceProcAddr);

        Log.info("Vulkan 로더 직접 지정: " + absolute);
        return new VulkanLoader(absolute);
    }

    public boolean isCustom() {
        return customLibrary != null;
    }

    @Override
    public void close() {
        if (customLibrary != null) {
            VK.destroy();
        }
    }
}
