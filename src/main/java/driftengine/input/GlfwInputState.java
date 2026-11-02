package driftengine.input;

import driftengine.platform.Window;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

/**
 * 실제 키보드 상태 (GLFW). 창이 포커스를 갖고 있을 때만 눌림이 잡힌다.
 * 메인 스레드에서 pollEvents 이후에 호출해야 최신 상태가 반영된다.
 */
public final class GlfwInputState implements InputState {

    private final Window window;

    public GlfwInputState(Window window) {
        this.window = window;
    }

    @Override
    public boolean isKeyDown(int glfwKey) {
        return glfwGetKey(window.handle(), glfwKey) == GLFW_PRESS;
    }
}
