package driftengine.selftest;

import driftengine.input.InputAction;
import driftengine.input.InputMap;
import driftengine.input.InputState;
import driftengine.math.Vec3;
import driftengine.scene.Camera;
import driftengine.scene.FreeCameraController;

import java.util.Properties;
import java.util.Set;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.expectThrows;
import static driftengine.selftest.TestRunner.test;
import static org.lwjgl.glfw.GLFW.*;

/** 입력 매핑과 자유 이동 카메라 테스트. 키보드 대신 "이 키들이 눌렸다"는 가짜 상태를 쓴다. */
final class InputTests {

    private static final float EPS = 1e-4f;
    private static final double DT = 1.0 / 60.0;

    private InputTests() {
    }

    /** 지정한 키만 눌린 가짜 키보드 */
    private static InputState pressed(Integer... keys) {
        Set<Integer> down = Set.of(keys);
        return down::contains;
    }

    static void run() {
        test("기본 배치: W = 앞으로", InputTests::defaultBinding);
        test("반대 방향 동시 입력은 0", InputTests::axisCancel);
        test("설정 파일로 키 바꾸기", InputTests::fromProperties);
        test("설정 오류는 위치를 알려줌", InputTests::badProperties);
        test("W 1초 → 앞으로 5m", InputTests::moveForward);
        test("대각선 이동도 속도 같음", InputTests::diagonalSpeed);
        test("Shift → 4배 속도", InputTests::sprint);
        test("← 1초 → 왼쪽으로 1.8 rad 회전", InputTests::turnLeft);
        test("입력 없으면 움직이지 않음", InputTests::idle);
    }

    private static void defaultBinding() {
        InputMap map = InputMap.defaults();
        check(map.isActive(InputAction.MOVE_FORWARD, pressed(GLFW_KEY_W)), "W 눌림");
        check(!map.isActive(InputAction.MOVE_FORWARD, pressed(GLFW_KEY_S)), "S 는 앞이 아님");
    }

    private static void axisCancel() {
        InputMap map = InputMap.defaults();
        check(map.axis(InputAction.MOVE_BACKWARD, InputAction.MOVE_FORWARD, pressed(GLFW_KEY_W, GLFW_KEY_S)) == 0, "W+S = 0");
        check(map.axis(InputAction.MOVE_BACKWARD, InputAction.MOVE_FORWARD, pressed(GLFW_KEY_S)) == -1, "S = -1");
    }

    private static void fromProperties() {
        Properties p = new Properties();
        p.setProperty("move_forward", "I, up");   // 대소문자, 공백 허용
        p.setProperty("SPRINT", "");             // 빈 값 → 끄기
        InputMap map = InputMap.fromProperties(p);
        check(map.isActive(InputAction.MOVE_FORWARD, pressed(GLFW_KEY_I)), "I");
        check(map.isActive(InputAction.MOVE_FORWARD, pressed(GLFW_KEY_UP)), "↑");
        check(!map.isActive(InputAction.MOVE_FORWARD, pressed(GLFW_KEY_W)), "W 는 해제됨");
        check(!map.isActive(InputAction.SPRINT, pressed(GLFW_KEY_LEFT_SHIFT)), "Shift 해제");
        check(map.isActive(InputAction.MOVE_LEFT, pressed(GLFW_KEY_A)), "설정에 없는 행동은 기본값 유지");
    }

    private static void badProperties() {
        Properties badAction = new Properties();
        badAction.setProperty("JUMP_HIGH", "SPACE");
        expectThrows(IllegalArgumentException.class, () -> InputMap.fromProperties(badAction), "모르는 행동");
        Properties badKey = new Properties();
        badKey.setProperty("MOVE_FORWARD", "NOPE");
        expectThrows(IllegalArgumentException.class, () -> InputMap.fromProperties(badKey), "모르는 키");
    }

    private static Camera runFor(InputState input, double seconds) {
        Camera cam = new Camera().position(Vec3.ZERO);
        FreeCameraController ctl = new FreeCameraController();
        InputMap map = InputMap.defaults();
        int ticks = (int) Math.round(seconds / DT);
        for (int i = 0; i < ticks; i++) {
            ctl.update(cam, input, map, DT);
        }
        return cam;
    }

    private static void moveForward() {
        Vec3 p = runFor(pressed(GLFW_KEY_W), 1.0).position();
        check(p.approxEquals(new Vec3(0, 0, -5), 1e-3f), "위치 " + p);
    }

    private static void diagonalSpeed() {
        Vec3 p = runFor(pressed(GLFW_KEY_W, GLFW_KEY_D), 1.0).position();
        check(Math.abs(p.length() - 5) < 1e-3f, "이동 거리 " + p.length());
        check(p.x() > 0 && p.z() < 0, "앞-오른쪽 방향 " + p);
    }

    private static void sprint() {
        Vec3 p = runFor(pressed(GLFW_KEY_W, GLFW_KEY_LEFT_SHIFT), 1.0).position();
        check(Math.abs(p.length() - 20) < 1e-2f, "이동 거리 " + p.length());
    }

    private static void turnLeft() {
        Camera cam = runFor(pressed(GLFW_KEY_LEFT), 1.0);
        check(Math.abs(cam.yaw() - 1.8f) < 1e-3f, "yaw " + cam.yaw());
        check(cam.position().approxEquals(Vec3.ZERO, EPS), "회전만 하고 이동 없음");
    }

    private static void idle() {
        Camera cam = new Camera().position(new Vec3(1, 2, 3));
        boolean moved = new FreeCameraController().update(cam, pressed(), InputMap.defaults(), DT);
        check(!moved, "false 반환");
        check(cam.position().approxEquals(new Vec3(1, 2, 3), EPS), "위치 그대로");
    }
}
