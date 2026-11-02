package driftengine.vulkan;

import driftengine.platform.Window;
import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;

/**
 * 창과 Vulkan 을 연결하는 VkSurfaceKHR. 플랫폼별 코드(Win32 등)는 GLFW 가 처리한다.
 */
public final class VulkanSurface implements AutoCloseable {

    private final VulkanInstance instance;
    private final long surface;

    public VulkanSurface(VulkanInstance instance, Window window) {
        this.instance = instance;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance.handle(), window.handle(), null, pSurface),
                    "glfwCreateWindowSurface");
            this.surface = pSurface.get(0);
        }
    }

    public long handle() {
        return surface;
    }

    @Override
    public void close() {
        vkDestroySurfaceKHR(instance.handle(), surface, null);
    }
}
