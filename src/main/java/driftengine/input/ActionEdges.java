package driftengine.input;

import java.util.EnumSet;
import java.util.Set;

/**
 * "누르는 순간"만 감지한다. 키를 누르고 있으면 계속 true 가 되는 isActive 와 달리,
 * 토글 기능(배율 전환, 디버그 보기 켜기/끄기)은 한 번 누를 때 한 번만 실행돼야 한다.
 * <p>
 * 매 렌더 프레임 update 를 한 번 부른 뒤 pressed 로 확인한다.
 */
public final class ActionEdges {

    private final Set<InputAction> down = EnumSet.noneOf(InputAction.class);
    private final Set<InputAction> pressedNow = EnumSet.noneOf(InputAction.class);

    /** 이번 프레임의 키 상태를 반영한다. */
    public void update(InputMap map, InputState state) {
        pressedNow.clear();
        for (InputAction action : InputAction.values()) {
            boolean active = map.isActive(action, state);
            if (active && !down.contains(action)) {
                pressedNow.add(action);
            }
            if (active) {
                down.add(action);
            } else {
                down.remove(action);
            }
        }
    }

    /** 이번 프레임에 막 눌렸으면 true (누르고 있는 동안 다음 프레임부터는 false) */
    public boolean pressed(InputAction action) {
        return pressedNow.contains(action);
    }
}
