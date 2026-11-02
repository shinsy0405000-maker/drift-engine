package driftengine.core;

import java.nio.file.Path;

/**
 * 실행 설정. 외부 라이브러리 없이 "--key=value" 형식의 인자만 해석한다.
 *
 * @param width        창 너비(픽셀)
 * @param height       창 높이(픽셀)
 * @param title        창 제목 / Vulkan 애플리케이션 이름
 * @param validation   Vulkan 검증 레이어 요청 여부 (설치되어 있지 않으면 경고 후 비활성화)
 * @param maxFrames    0이면 창을 닫을 때까지 실행, 양수면 해당 프레임 수 후 자동 종료 (자동 테스트용)
 * @param dlss         DLSS 사용 요청 여부 (기본: 설정 파일 render.dlss = true). 조건이 안 맞으면 기본 렌더링으로 자동 전환된다.
 * @param triangle     true 면 3D 장면 대신 2단계의 삼각형만 그린다 (렌더링 문제 분리용)
 * @param vulkanLoader null 이면 시스템 Vulkan 로더(vulkan-1.dll), 아니면 지정한 DLL 을 로더로 사용
 *                     (Stage 4 에서 Streamline 의 sl.interposer.dll 경로를 넣어 시험할 용도)
 * @param vsync        수직동기 (false 면 최대 속도로 표시)
 * @param benchmark    true 면 업스케일러를 자동으로 바꿔 가며 측정한 뒤 요약을 남기고 종료
 * @param gpuLoad      픽셀당 인공 GPU 부하 반복 횟수 (성능 측정용, 0 이면 없음)
 * @param scene        terrain(지형 + 물리, 기본), garage(차고: 차 구경, 물리 없음) 또는 demo(정육면체 데모)
 */
public record EngineConfig(int width, int height, String title, boolean validation, long maxFrames,
                           boolean dlss, Path vulkanLoader, boolean triangle,
                           boolean vsync, boolean benchmark, int gpuLoad, String scene) {

    public static final java.util.Set<String> SCENES = java.util.Set.of("terrain", "demo", "garage");

    /** --gpu-load 상한 (너무 크면 GPU 가 응답하지 않는 것처럼 보일 수 있다) */
    public static final int MAX_GPU_LOAD = 20000;

    public static final String USAGE = """
            사용법: gradlew run --args="[옵션]"
              --width=1280              창 너비
              --height=720              창 높이
              --no-validation           Vulkan 검증 레이어 끄기
              --frames=N                N 프레임 후 자동 종료 (0 = 무제한)
              --dlss / --no-dlss        DLSS 켜기/끄기 (기본: 설정 파일 render.dlss, 처음엔 켜짐)
              --vulkan-loader=경로       Vulkan 로더 DLL 직접 지정 (실험용)
              --triangle                3D 장면 대신 삼각형만 그리기 (문제 분리용)
              --vsync / --no-vsync      수직동기 켜기/끄기 (기본: 설정 파일 render.vsync)
              --benchmark               업스케일러별 자동 성능 측정 후 종료 (수직동기·검증 레이어 끔)
              --gpu-load=N              픽셀당 인공 GPU 부하 N 회 (0~20000, 무거운 장면 흉내)
              --scene=terrain|garage|demo  장면 (기본 terrain: 지형 + 물리, garage: 차고, demo: 정육면체 데모)
            """;

    public EngineConfig {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("창 크기는 양수여야 합니다: " + width + "x" + height);
        }
        if (maxFrames < 0) {
            throw new IllegalArgumentException("--frames 는 0 이상이어야 합니다: " + maxFrames);
        }
        if (!SCENES.contains(scene)) {
            throw new IllegalArgumentException("--scene 은 " + SCENES + " 중 하나: " + scene);
        }
        if (gpuLoad < 0 || gpuLoad > MAX_GPU_LOAD) {
            throw new IllegalArgumentException("--gpu-load 는 0~" + MAX_GPU_LOAD + ": " + gpuLoad);
        }
    }

    /** 설정 파일 없이 기본값 + 실행 인자 */
    public static EngineConfig fromArgs(String[] args) {
        return fromArgs(args, EngineSettings.defaults());
    }

    /** 설정 파일 값을 기본으로 하고, 실행 인자가 있으면 덮어쓴다. */
    public static EngineConfig fromArgs(String[] args, EngineSettings settings) {
        int width = settings.windowWidth();
        int height = settings.windowHeight();
        boolean validation = settings.validation();
        long maxFrames = 0;
        boolean dlss = settings.dlss();
        Path vulkanLoader = null;
        boolean triangle = false;
        boolean vsync = settings.vsync();
        boolean benchmark = false;
        int gpuLoad = 0;
        String scene = "terrain";

        for (String arg : args) {
            if (arg.equals("--no-validation")) {
                validation = false;
            } else if (arg.equals("--triangle")) {
                triangle = true;
            } else if (arg.equals("--dlss")) {
                dlss = true;
            } else if (arg.equals("--no-dlss")) {
                dlss = false;
            } else if (arg.equals("--vsync")) {
                vsync = true;
            } else if (arg.equals("--no-vsync")) {
                vsync = false;
            } else if (arg.equals("--benchmark")) {
                benchmark = true;
            } else if (arg.startsWith("--scene=")) {
                scene = arg.substring("--scene=".length());
            } else if (arg.startsWith("--gpu-load=")) {
                gpuLoad = parseInt(arg, "--gpu-load=");
            } else if (arg.startsWith("--width=")) {
                width = parseInt(arg, "--width=");
            } else if (arg.startsWith("--height=")) {
                height = parseInt(arg, "--height=");
            } else if (arg.startsWith("--frames=")) {
                maxFrames = parseInt(arg, "--frames=");
            } else if (arg.startsWith("--vulkan-loader=")) {
                vulkanLoader = Path.of(arg.substring("--vulkan-loader=".length()));
            } else {
                throw new IllegalArgumentException("알 수 없는 인자: " + arg);
            }
        }
        if (benchmark) {
            // 측정값이 모니터 주사율이나 검증 레이어의 CPU 비용에 묶이지 않게 한다
            vsync = false;
            validation = false;
        }
        return new EngineConfig(width, height, "Drift Engine", validation, maxFrames, dlss, vulkanLoader, triangle,
                vsync, benchmark, gpuLoad, scene);
    }

    private static int parseInt(String arg, String prefix) {
        String value = arg.substring(prefix.length());
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(prefix + " 값이 정수가 아닙니다: " + value, e);
        }
    }
}
