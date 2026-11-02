package driftengine.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.lwjgl.glfw.GLFW.*;

/**
 * 행동 ↔ 키 연결표. 행동 하나에 키 여러 개를 연결할 수 있다 (예: 앞으로 = W, UP).
 * <p>
 * 설정 파일 형식(Properties): {@code MOVE_FORWARD=W} 처럼 행동 이름 = 키 이름(쉼표로 여러 개).
 * 키 이름은 KEY_NAMES 표를 따른다.
 */
public final class InputMap {

    /** 설정 파일에서 쓰는 키 이름 → GLFW 키 코드 */
    private static final Map<String, Integer> KEY_NAMES = buildKeyNames();

    private final Map<InputAction, List<Integer>> bindings = new EnumMap<>(InputAction.class);

    /**
     * 기본 배치. 자유 카메라: WASD 이동, Q/E 아래/위, 화살표 시점, 왼쪽 Shift 빠르게.
     * 운전: W/↑ 가속, S/↓ 브레이크·후진, A/D/←/→ 조향, Space 핸드브레이크, E/Q 변속, G 자동/수동, X 트랙션 컨트롤, T 차 세우기, N 다음 차.
     * 메뉴: Tab 열기/닫기, 화살표·WASD 이동, Enter·Space 고르기, Backspace 뒤로.
     * 공통: R 렌더 배율, M 모션 벡터 보기, J 지터, C 카메라 전환.
     */
    public static InputMap defaults() {
        InputMap map = new InputMap();
        map.bind(InputAction.MOVE_FORWARD, GLFW_KEY_W);
        map.bind(InputAction.MOVE_BACKWARD, GLFW_KEY_S);
        map.bind(InputAction.MOVE_LEFT, GLFW_KEY_A);
        map.bind(InputAction.MOVE_RIGHT, GLFW_KEY_D);
        map.bind(InputAction.MOVE_UP, GLFW_KEY_E);
        map.bind(InputAction.MOVE_DOWN, GLFW_KEY_Q);
        map.bind(InputAction.LOOK_LEFT, GLFW_KEY_LEFT);
        map.bind(InputAction.LOOK_RIGHT, GLFW_KEY_RIGHT);
        map.bind(InputAction.LOOK_UP, GLFW_KEY_UP);
        map.bind(InputAction.LOOK_DOWN, GLFW_KEY_DOWN);
        map.bind(InputAction.SPRINT, GLFW_KEY_LEFT_SHIFT);
        map.bind(InputAction.CYCLE_RENDER_SCALE, GLFW_KEY_R);
        map.bind(InputAction.TOGGLE_MOTION_VIEW, GLFW_KEY_M);
        map.bind(InputAction.TOGGLE_JITTER, GLFW_KEY_J);
        map.bind(InputAction.TOGGLE_CAMERA, GLFW_KEY_C);
        map.bind(InputAction.THROTTLE, GLFW_KEY_W).bind(InputAction.THROTTLE, GLFW_KEY_UP);
        map.bind(InputAction.BRAKE, GLFW_KEY_S).bind(InputAction.BRAKE, GLFW_KEY_DOWN);
        map.bind(InputAction.STEER_LEFT, GLFW_KEY_A).bind(InputAction.STEER_LEFT, GLFW_KEY_LEFT);
        map.bind(InputAction.STEER_RIGHT, GLFW_KEY_D).bind(InputAction.STEER_RIGHT, GLFW_KEY_RIGHT);
        map.bind(InputAction.HANDBRAKE, GLFW_KEY_SPACE);
        map.bind(InputAction.SHIFT_UP, GLFW_KEY_E);
        map.bind(InputAction.SHIFT_DOWN, GLFW_KEY_Q);
        map.bind(InputAction.TOGGLE_GEARBOX, GLFW_KEY_G);
        map.bind(InputAction.RESET_CAR, GLFW_KEY_T);
        map.bind(InputAction.TOGGLE_TRACTION, GLFW_KEY_X);
        map.bind(InputAction.NITRO, GLFW_KEY_LEFT_SHIFT);
        map.bind(InputAction.NEXT_CAR, GLFW_KEY_N);
        map.bind(InputAction.MENU, GLFW_KEY_TAB);
        map.bind(InputAction.ENTER_SHOP, GLFW_KEY_ENTER);
        map.bind(InputAction.UI_UP, GLFW_KEY_UP).bind(InputAction.UI_UP, GLFW_KEY_W);
        map.bind(InputAction.UI_DOWN, GLFW_KEY_DOWN).bind(InputAction.UI_DOWN, GLFW_KEY_S);
        map.bind(InputAction.UI_LEFT, GLFW_KEY_LEFT).bind(InputAction.UI_LEFT, GLFW_KEY_A);
        map.bind(InputAction.UI_RIGHT, GLFW_KEY_RIGHT).bind(InputAction.UI_RIGHT, GLFW_KEY_D);
        map.bind(InputAction.UI_CONFIRM, GLFW_KEY_ENTER).bind(InputAction.UI_CONFIRM, GLFW_KEY_SPACE);
        map.bind(InputAction.UI_BACK, GLFW_KEY_BACKSPACE);
        return map;
    }

    /**
     * 기본 배치 위에 설정 파일 내용을 덮어쓴다. 설정에 있는 행동만 바뀐다.
     *
     * @throws IllegalArgumentException 모르는 행동 이름이나 키 이름이 있으면 (어디가 틀렸는지 메시지에 포함)
     */
    public static InputMap fromProperties(Properties props) {
        InputMap map = defaults();
        for (String name : props.stringPropertyNames()) {
            InputAction action;
            try {
                action = InputAction.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("알 수 없는 행동: " + name);
            }
            List<Integer> keys = new ArrayList<>();
            for (String keyName : props.getProperty(name).split(",")) {
                String k = keyName.trim().toUpperCase(Locale.ROOT);
                if (k.isEmpty()) {
                    continue;
                }
                Integer code = KEY_NAMES.get(k);
                if (code == null) {
                    throw new IllegalArgumentException("알 수 없는 키 '" + keyName.trim() + "' (행동 " + name + ")");
                }
                keys.add(code);
            }
            map.bindings.put(action, keys); // 빈 목록이면 그 행동을 끈다
        }
        return map;
    }

    public InputMap bind(InputAction action, int glfwKey) {
        bindings.computeIfAbsent(action, a -> new ArrayList<>()).add(glfwKey);
        return this;
    }

    public List<Integer> keysFor(InputAction action) {
        return Collections.unmodifiableList(bindings.getOrDefault(action, List.of()));
    }

    /** 연결된 키 중 하나라도 눌려 있으면 true */
    public boolean isActive(InputAction action, InputState state) {
        for (int key : bindings.getOrDefault(action, List.of())) {
            if (state.isKeyDown(key)) {
                return true;
            }
        }
        return false;
    }

    /** 반대 방향 두 행동을 -1, 0, +1 로 합친다 (둘 다 누르면 0). */
    public float axis(InputAction negative, InputAction positive, InputState state) {
        return (isActive(positive, state) ? 1f : 0f) - (isActive(negative, state) ? 1f : 0f);
    }

    /** GLFW 키 코드 → 설정 파일용 이름. 표에 없는 키는 "KEY_코드" */
    public static String keyName(int glfwKey) {
        for (Map.Entry<String, Integer> e : KEY_NAMES.entrySet()) {
            if (e.getValue() == glfwKey) {
                return e.getKey();
            }
        }
        return "KEY_" + glfwKey;
    }

    /** 설정 파일 형식 한 줄의 값 부분 (예: "W,UP") */
    public String bindingText(InputAction action) {
        StringBuilder sb = new StringBuilder();
        for (int key : keysFor(action)) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(keyName(key));
        }
        return sb.toString();
    }

    private static Map<String, Integer> buildKeyNames() {
        Map<String, Integer> names = new HashMap<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            names.put(String.valueOf(c), GLFW_KEY_A + (c - 'A'));
        }
        for (char c = '0'; c <= '9'; c++) {
            names.put(String.valueOf(c), GLFW_KEY_0 + (c - '0'));
        }
        names.put("SPACE", GLFW_KEY_SPACE);
        names.put("LEFT_SHIFT", GLFW_KEY_LEFT_SHIFT);
        names.put("RIGHT_SHIFT", GLFW_KEY_RIGHT_SHIFT);
        names.put("LEFT_CONTROL", GLFW_KEY_LEFT_CONTROL);
        names.put("RIGHT_CONTROL", GLFW_KEY_RIGHT_CONTROL);
        names.put("LEFT_ALT", GLFW_KEY_LEFT_ALT);
        names.put("TAB", GLFW_KEY_TAB);
        names.put("BACKSPACE", GLFW_KEY_BACKSPACE);
        names.put("ESCAPE", GLFW_KEY_ESCAPE);
        names.put("ENTER", GLFW_KEY_ENTER);
        names.put("UP", GLFW_KEY_UP);
        names.put("DOWN", GLFW_KEY_DOWN);
        names.put("LEFT", GLFW_KEY_LEFT);
        names.put("RIGHT", GLFW_KEY_RIGHT);
        return Map.copyOf(names);
    }
}
