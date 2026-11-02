package driftengine.platform;

import driftengine.core.Log;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW 창. OpenGL 컨텍스트 없이(GLFW_NO_API) 생성해 Vulkan 서피스용으로만 사용한다.
 * <p>
 * GLFW 는 메인 스레드에서만 호출해야 한다. 이 클래스의 모든 메서드는 메인 스레드 전용이다.
 */
public final class Window implements AutoCloseable {

    /** 창의 실제 픽셀 크기. 고해상도(DPI) 화면에서는 창 크기와 다를 수 있다. */
    public record FramebufferSize(int width, int height) {
        public boolean isEmpty() {
            return width <= 0 || height <= 0;
        }
    }

    private final long handle;
    private volatile boolean framebufferResized;

    public Window(int width, int height, String title) {
        GLFWErrorCallback.create((error, description) ->
                Log.error("[GLFW " + error + "] " + GLFWErrorCallback.getDescription(description))).set();

        if (!glfwInit()) {
            freeErrorCallback();
            throw new IllegalStateException("GLFW 초기화 실패");
        }

        if (!glfwVulkanSupported()) {
            glfwTerminate();
            freeErrorCallback();
            throw new IllegalStateException(
                    "Vulkan 로더(vulkan-1.dll)를 찾을 수 없습니다. GPU 드라이버가 Vulkan 을 지원하는지 확인하세요.");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            glfwTerminate();
            freeErrorCallback();
            throw new IllegalStateException("GLFW 창 생성 실패");
        }

        glfwSetFramebufferSizeCallback(handle, (window, w, h) -> framebufferResized = true);
        glfwSetKeyCallback(handle, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(window, true);
            }
        });
        Log.info("창 생성: " + width + "x" + height);
    }

    public long handle() {
        return handle;
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public FramebufferSize framebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            return new FramebufferSize(w.get(0), h.get(0));
        }
    }

    /** 최소화되면 프레임버퍼 크기가 0 이 된다. 이때는 스왑체인을 만들 수 없으므로 그리지 않는다. */
    public boolean isMinimized() {
        return framebufferSize().isEmpty();
    }

    public void setTitle(String title) {
        glfwSetWindowTitle(handle, title);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    public void waitEvents(double timeoutSeconds) {
        glfwWaitEventsTimeout(timeoutSeconds);
    }

    /** 리사이즈 플래그를 읽고 초기화한다. 스왑체인 재생성 판단에 사용. */
    public boolean consumeResized() {
        boolean resized = framebufferResized;
        framebufferResized = false;
        return resized;
    }

    @Override
    public void close() {
        glfwFreeCallbacks(handle);
        glfwDestroyWindow(handle);
        glfwTerminate();
        freeErrorCallback();
    }

    private static void freeErrorCallback() {
        GLFWErrorCallback previous = glfwSetErrorCallback(null);
        if (previous != null) {
            previous.free();
        }
    }
}
