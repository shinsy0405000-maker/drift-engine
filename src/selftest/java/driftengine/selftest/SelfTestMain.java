package driftengine.selftest;

/**
 * 외부 테스트 라이브러리 없이 돌리는 자체 테스트. 실행: gradlew selfTest
 * <p>
 * GPU 가 필요 없는 순수 계산만 검사한다. 하나라도 실패하면 종료 코드 1 (Gradle 빌드 실패).
 */
public final class SelfTestMain {

    private SelfTestMain() {
    }

    public static void main(String[] args) {
        TestRunner.section("수학 / 카메라");
        MathCameraTests.run();
        TestRunner.section("게임 시계");
        ClockTests.run();
        TestRunner.section("입력 / 카메라 조작");
        InputTests.run();
        TestRunner.section("3D 모델 데이터");
        MeshTests.run();
        TestRunner.section("설정 파일");
        SettingsTests.run();
        TestRunner.section("3D 장면");
        SceneTests.run();
        TestRunner.section("오프스크린 렌더링 / 업스케일");
        UpscaleTests.run();
        TestRunner.section("모션 벡터 / 지터");
        MotionTests.run();
        TestRunner.section("DLSS 실행 준비");
        DlssTests.run();
        TestRunner.section("성능 측정");
        PerfTests.run();
        TestRunner.section("지형 / 물리 연결");
        WorldTests.run();
        TestRunner.section("차체 / 서스펜션");
        VehicleTests.run();
        TestRunner.section("주행 물리 (타이어 / 엔진 / 터보 / 변속 / LSD / 운전 시험)");
        DrivingTests.run();
        TractionTests.run();
        ShopTests.run();
        PartsTests.run();
        TestRunner.section("차 모델 (차체 생성기 / 바디킷 / 바퀴)");
        VisualTests.run();
        TestRunner.section("게임 시스템 (클래스 / 튜닝 / 딜러쉽 / 클럽 / 저장 / 드리프트 점수)");
        GameTests.run();
        TestRunner.section("모델링한 차 (cars/models)");
        ModelTests.run();
        MemoryTests.run();
        TestRunner.section("맵 (서킷 / 고갯길 / 도시 / 코스 / 컴퓨터 운전자)");
        MapTests.run();
        TestRunner.section("소리 (엔진 / 로터리 / 전기 / 터보 / 타이어)");
        AudioTests.run();
        TestRunner.section("그래픽 B-2 (그림자 / 블룸 / 스키드 자국 / 타이어 연기)");
        FxTests.run();

        if (TestRunner.summary() > 0) {
            System.exit(1);
        }
    }
}
