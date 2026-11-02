package driftengine.input;

/**
 * 게임이 이해하는 "행동". 게임 코드는 키가 아니라 행동으로만 입력을 확인한다.
 * 그래서 키 배치를 바꾸거나 나중에 게임패드를 붙여도 게임 코드는 바뀌지 않는다.
 * <p>
 * 자유 카메라 행동(MOVE_*, LOOK_*)과 운전 행동이 같은 키를 쓴다 (W/A/S/D, 화살표).
 * 추적 카메라일 때는 운전, 자유 카메라일 때는 카메라 이동으로만 쓰인다.
 */
public enum InputAction {
    MOVE_FORWARD,
    MOVE_BACKWARD,
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_UP,
    MOVE_DOWN,
    LOOK_LEFT,
    LOOK_RIGHT,
    LOOK_UP,
    LOOK_DOWN,
    SPRINT,
    /** 렌더 배율 바꾸기 (100% → 75% → 50% → 100%). 업스케일 화질 비교용 */
    CYCLE_RENDER_SCALE,
    /** 화면에 색 대신 모션 벡터 보기 (DLSS 입력 확인용) */
    TOGGLE_MOTION_VIEW,
    /** 카메라 지터 켜기/끄기 (DLSS 입력 확인용, 업스케일러 없이 켜면 화면이 미세하게 떨림) */
    TOGGLE_JITTER,
    /** 추적 카메라 ↔ 자유 카메라 (따라갈 차가 있는 장면에서) */
    TOGGLE_CAMERA,
    /** 가속 (후진 기어에서는 브레이크) */
    THROTTLE,
    /** 브레이크 (멈춘 상태에서 계속 누르면 후진) */
    BRAKE,
    STEER_LEFT,
    STEER_RIGHT,
    HANDBRAKE,
    /** 수동 변속 (누르면 수동 모드로 바뀜) */
    SHIFT_UP,
    SHIFT_DOWN,
    /** 자동 ↔ 수동 변속 */
    TOGGLE_GEARBOX,
    /** 트랙션 컨트롤 단계 바꾸기 (끔 → 스포츠 → 켬) */
    TOGGLE_TRACTION,
    /** 니트로 (누르고 있는 동안) */
    NITRO,
    /** 차를 바로 세우기 */
    RESET_CAR,
    /** 다음 차로 바꾸기 */
    NEXT_CAR,
    /** 메뉴 열기/닫기 */
    MENU,
    /** 샵 입구 원 안에서 샵 들어가기 (Enter 만, 핸드브레이크 Space 와 겹치지 않게) */
    ENTER_SHOP,
    UI_UP,
    UI_DOWN,
    UI_LEFT,
    UI_RIGHT,
    UI_CONFIRM,
    UI_BACK
}
