package driftengine.scene;

import java.util.List;

/**
 * 렌더러가 그리는 장면. 구현: DemoScene(정육면체 데모), world.TerrainScene(지형 + 물리).
 * <p>
 * 호출 순서 (한 렌더 프레임): beginFrame() → 고정 틱마다 update(...) 0번 이상 → 그리기(objects()).
 * 물체 목록은 처음 만든 뒤 바뀌지 않는다 (렌더러가 시작할 때 메시를 GPU 에 올린다).
 */
public interface Scene extends AutoCloseable {

    /** 렌더 프레임 시작: 모든 물체의 지금 행렬을 "이전 프레임"으로 보관 (모션 벡터 기준) */
    void beginFrame();

    /**
     * 고정 틱 하나만큼 진행한다.
     *
     * @param simTimeSeconds 이 틱이 끝난 시점의 누적 게임 시간
     * @param dtSeconds      틱 길이
     */
    void update(double simTimeSeconds, double dtSeconds);

    List<SceneObject> objects();

    /**
     * 렌더 직전: 마지막 두 틱 상태를 alpha(0~1) 로 섞어 물체 위치를 정한다 (물리 60Hz, 화면 144Hz 일 때 끊김 방지).
     * 기본: 아무것도 안 함 (틱마다 직접 배치하는 장면)
     */
    default void interpolate(float alpha) {
    }

    /** 운전 입력 (고정 틱마다 update 직전에 한 번). 기본: 무시 */
    default void drive(driftengine.vehicle.DriverInput input) {
    }

    /** 메뉴 키 입력 (렌더 프레임마다, "막 눌림"만) */
    default void uiInput(driftengine.ui.UiInput input) {
    }

    /** 메뉴가 열려 있으면 운전 입력을 막는다 */
    default boolean menuOpen() {
        return false;
    }

    /** 화면 위 계기판·메뉴 그리기 (렌더 프레임마다) */
    default void drawOverlay(driftengine.ui.UiCanvas canvas) {
    }

    /** 창 제목에 붙일 짧은 상태 (속도, 기어 등). 없으면 빈 문자열 */
    default String statusText() {
        return "";
    }

    /** 추적 카메라가 따라갈 대상. 없으면 null (자유 카메라만) */
    default FollowTarget followTarget() {
        return null;
    }

    /** 추적 카메라용: 위치와 앞 방향 */
    record FollowTarget(driftengine.math.Vec3 position, driftengine.math.Vec3 forward) {
    }

    /**
     * 장면이 카메라를 직접 정하고 싶을 때 (샵 안 궤도 카메라 등). true 면 추적 카메라를 쓰지 않는다.
     * 렌더 프레임마다, 보간 뒤에 부른다.
     */
    default boolean cameraOverride(Camera camera, float dt) {
        return false;
    }

    /** 이번 프레임 카메라 (화면 위 표시를 월드 위치에 붙일 때). 행렬 계산 뒤, 그리기 전에 부른다 */
    default void observeCamera(Camera camera) {
    }

    /** 이번 프레임 반투명 효과 (스키드 자국, 타이어 연기). 없으면 null */
    default Effects effects() {
        return null;
    }

    /** 빛과 대기 (기본: 늦은 오후). 렌더 직전에 한 번 읽는다 */
    /** 지금 장면에는 없지만 곧 다시 쓸 메시 (렌더러가 GPU 에서 내리지 않음). 예: 멀어진 지형 조각의 자세한 메시 */
    default void retainMeshes(java.util.function.Consumer<driftengine.scene.mesh.MeshData> keep) {
    }

    default Environment environment() {
        return Environment.afternoon(0);
    }

    /** 카메라 시작 위치로 알맞은 곳 */
    default driftengine.math.Vec3 cameraStart() {
        return new driftengine.math.Vec3(0, 2.5f, 16);
    }

    /** 물리 엔진 등 장면이 가진 자원 해제 (기본: 없음) */
    @Override
    default void close() {
    }
}
