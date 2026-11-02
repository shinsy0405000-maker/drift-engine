package driftengine.input;

/**
 * 지금 어떤 키가 눌려 있는지 알려주는 창구.
 * <p>
 * 실제 게임에서는 GlfwInputState(창의 키보드 상태)를 쓰고,
 * 테스트에서는 원하는 키만 눌린 것처럼 꾸민 가짜 구현을 쓴다.
 */
@FunctionalInterface
public interface InputState {

    /** @param glfwKey GLFW 키 코드 (예: GLFW_KEY_W) */
    boolean isKeyDown(int glfwKey);
}
