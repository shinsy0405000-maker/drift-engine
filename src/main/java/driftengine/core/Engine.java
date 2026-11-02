package driftengine.core;

import driftengine.input.ActionEdges;
import driftengine.vehicle.DriverInput;
import driftengine.input.GlfwInputState;
import driftengine.input.InputAction;
import driftengine.input.InputMap;
import driftengine.input.InputState;
import driftengine.math.HaltonJitter;
import driftengine.math.Vec3;
import driftengine.platform.Window;
import driftengine.render.FrameLoop;
import driftengine.render.Renderer;
import driftengine.render.SceneRenderer;
import driftengine.render.TriangleRenderer;
import driftengine.scene.Camera;
import driftengine.scene.ChaseCamera;
import driftengine.scene.DemoScene;
import driftengine.scene.Scene;
import driftengine.world.TerrainScene;
import driftengine.scene.FreeCameraController;
import driftengine.upscale.DlssMode;
import driftengine.upscale.DlssPrecheck;
import driftengine.upscale.DlssSession;
import driftengine.upscale.DlssUpscaler;
import driftengine.upscale.NativeUpscaler;
import driftengine.upscale.BilinearUpscaler;
import driftengine.upscale.UpscaleCamera;
import driftengine.upscale.Upscaler;
import driftengine.upscale.UpscalerSelector;
import driftengine.vulkan.GpuInfo;
import driftengine.vulkan.PhysicalDeviceSelector;
import driftengine.vulkan.VulkanDevice;
import driftengine.vulkan.VulkanInstance;
import driftengine.vulkan.VulkanLoader;
import driftengine.vulkan.VulkanSurface;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 엔진 수명 주기 관리.
 * <p>
 * 초기화 순서: (DLSS 세션) → (로더) → 창 → 인스턴스 → 서피스 → GPU → 디바이스 → 업스케일러 선택
 * → 프레임 루프(스왑체인, 오프스크린 색/깊이) → 장면 렌더러(또는 --triangle 이면 삼각형).
 * 해제는 ResourceStack 이 생성의 역순으로 처리한다.
 */
public final class Engine implements AutoCloseable {

    private final EngineConfig config;
    private final ResourceStack resources = new ResourceStack();
    private final Window window;
    private final VulkanDevice device;
    private final FrameLoop frameLoop;
    private final Renderer renderer; // 인터페이스 타입으로만 사용 → 다른 렌더러로 교체 가능
    private Upscaler upscaler;       // R 키로 바뀐다 (Native ↔ Bilinear, DLSS 사용 시 DLSS 모드 ↔ Native)
    private float renderScale;
    private DlssSession dlss;        // --dlss 로 Streamline 이 시작됐을 때만
    private boolean dlssUsable;      // DLSS 업스케일러를 고를 수 있는 상태인지 (지원 확인 + 출력 포맷)
    private final ActionEdges keyPresses = new ActionEdges();
    private final java.util.Set<InputAction> pendingEdges = java.util.EnumSet.noneOf(InputAction.class);
    private HaltonJitter jitter = new HaltonJitter(8);
    private boolean jitterEnabled;
    private boolean jitterAuto;      // 시간적 업스케일러 때문에 자동으로 켠 경우

    // 성능 측정
    private final FrameStats stats = new FrameStats();            // 업스케일러별 누적 (종료 시 요약)
    private final FrameStats.Accumulator titleWindow = new FrameStats.Accumulator(); // 창 제목용 0.25초 구간
    private Benchmark<Upscaler> benchmark;                         // --benchmark 일 때만

    // 게임 로직 (렌더링과 분리된 고정 틱)
    private final GameClock clock = new GameClock(60, 0.25);
    private final InputMap inputMap;
    private Scene scene;
    private final Camera camera = new Camera().position(new Vec3(0, 2.5f, 16));
    private double simTimeSeconds;
    private final FreeCameraController cameraController = new FreeCameraController();
    private final ChaseCamera chaseCamera = new ChaseCamera();
    private boolean chaseMode = true; // 따라갈 대상이 있으면 추적 카메라로 시작
    private final InputState input;

    /** Streamline 초기화 때 넘기는 엔진 버전 (applicationId 가 없으면 필요) */
    private static final String ENGINE_VERSION = "0.2.0";
    /** 카메라 앞뒤 자르는 거리 (m). 지역이 1.5km 라 먼 산까지 보이게, 가까운 쪽은 0.2m (도로 면 3cm 띄움이 먼 곳에서 깜빡이지 않게) */
    static final float NEAR = 0.2f;
    static final float FAR = 2600f;
    /**
     * NGX(DLSS) 초기화용 프로젝트 ID. 자체 엔진(EngineType CUSTOM)은 GUID 형식이어야 한다 (DLSS 가이드 5.2.1).
     * 이 프로젝트용으로 한 번 무작위 생성한 값이며 바꾸지 않는다.
     */
    private static final String PROJECT_ID = "c1208b33-12b5-44e8-97c4-03f8b00fbfd9";

    public Engine(EngineConfig config) {
        this(config, EngineSettings.defaults());
    }

    public Engine(EngineConfig config, EngineSettings settings) {
        this.config = config;
        this.inputMap = settings.inputMap();
        camera.lens((float) Math.toRadians(settings.fovDegrees()), NEAR, FAR);
        cameraController.moveSpeed(settings.moveSpeed());
        this.renderScale = settings.renderScale();
        try {
            Log.info("Drift Engine 시작 (Java " + Runtime.version() + ")");

            // DLSS 를 요청했으면 Vulkan 을 건드리기 전에 Streamline 을 시작한다 (실패 시 null → Native)
            dlss = config.dlss()
                    ? DlssSession.tryStart(DlssPrecheck.DEFAULT_DIR, ENGINE_VERSION, PROJECT_ID)
                    : null;
            if (dlss != null) {
                resources.push(dlss); // 브리지 DLL 은 가장 마지막에 해제
            }

            // Vulkan 로더 선택은 창(GLFW)보다 먼저 해야 한다. DLSS 세션이 있으면 sl.interposer.dll 을 쓴다.
            Path loader = dlss != null ? dlss.interposerPath() : config.vulkanLoader();
            resources.push(VulkanLoader.configure(loader));
            window = resources.push(new Window(config.width(), config.height(), config.title()));
            input = new GlfwInputState(window);
            VulkanInstance instance = resources.push(new VulkanInstance(config.title(), config.validation()));
            VulkanSurface surface = resources.push(new VulkanSurface(instance, window));

            GpuInfo gpu = PhysicalDeviceSelector.select(instance, surface);
            Log.info("선택된 GPU: " + gpu.describe());
            device = resources.push(new VulkanDevice(gpu, dlss != null));
            if (dlss != null) {
                // slShutdown 은 Vulkan 디바이스/인스턴스 파괴 "전"이어야 하므로, 디바이스 다음에 등록한다
                // (ResourceStack 은 역순으로 닫으므로 디바이스보다 먼저 실행된다).
                resources.push((AutoCloseable) dlss::shutdownStreamline);
            }
            upscaler = UpscalerSelector.select(config.dlss(), gpu, dlss, renderScale, device, settings.dlssMode());
            dlssUsable = upscaler instanceof DlssUpscaler;
            // 업스케일러는 실행 중 바뀌므로 "그때의 업스케일러"를 닫도록 등록한다.
            // slShutdown·디바이스보다 나중에 등록 → 그보다 먼저 닫힌다 (DLSS 자원 해제 → slShutdown → 디바이스 파괴)
            resources.push((AutoCloseable) () -> upscaler.close());
            frameLoop = resources.push(new FrameLoop(device, surface, window, upscaler, config.vsync()));
            scene = resources.push(createScene(config.scene()));
            camera.position(scene.cameraStart());
            renderer = resources.push(config.triangle()
                    ? new TriangleRenderer(device, frameLoop.colorFormat())
                    : new SceneRenderer(device, frameLoop.colorFormat(), frameLoop.depthFormat(), scene, camera));
            if (renderer instanceof SceneRenderer sr && config.gpuLoad() > 0) {
                sr.setGpuLoad(config.gpuLoad());
                Log.info("인공 GPU 부하: 픽셀당 " + config.gpuLoad() + "회 (성능 측정용)");
            }
            applyJitterFor(upscaler);
            if (config.benchmark()) {
                benchmark = new Benchmark<>(benchmarkPhases(), 2.0, 5.0);
                Log.info(String.format("자동 성능 측정 시작: %d 단계, 약 %.0f 초. 측정 중에는 카메라 조작과 R/M/J 키가 꺼진다. 창 크기는 바꾸지 말 것",
                        benchmark.phaseCount(), benchmark.totalSeconds()));
            }
            Log.info("초기화 완료");
        } catch (Throwable t) {
            resources.closeAfterFailure(t);
            throw t;
        }
    }

    public void run() {
        long frames = 0;
        long start = System.nanoTime();
        long fpsWindowStart = start;
        long lastFrameEnd = start;

        while (!window.shouldClose()) {
            window.pollEvents();
            if (window.isMinimized()) {
                window.waitEvents(0.1); // 최소화 중에는 CPU 를 쉬게 한다
                continue;
            }

            // 이번 프레임 시작: 물체/카메라의 현재 상태를 "이전 프레임"으로 보관 (모션 벡터 기준)
            scene.beginFrame();
            camera.beginFrame();

            // 고정 틱 로직: 밀린 만큼 여러 번, 아무것도 안 밀렸으면 0번 실행
            int ticks = clock.advance(System.nanoTime());
            for (int i = 0; i < ticks; i++) {
                if (benchmark == null && !usingChaseCamera()) { // 측정 중에는 같은 장면을 보도록 카메라 고정
                    cameraController.update(camera, input, inputMap, clock.fixedDeltaSeconds());
                }
                simTimeSeconds += clock.fixedDeltaSeconds();
                scene.drive(driverInput());
                scene.update(simTimeSeconds, clock.fixedDeltaSeconds());
            }

            if (benchmark != null) {
                Benchmark.Action action = benchmark.tick(System.nanoTime());
                if (action == Benchmark.Action.FINISHED) {
                    Log.info("자동 성능 측정 끝");
                    break;
                }
                if (action == Benchmark.Action.SWITCH) {
                    Log.info("측정 " + benchmark.phaseNumber() + "/" + benchmark.phaseCount() + ": "
                            + benchmark.current().label());
                    switchUpscaler(benchmark.current().factory().get());
                    lastFrameEnd = System.nanoTime(); // 전환에 걸린 시간은 프레임 시간에서 뺀다
                }
            }
            handleToggleKeys();

            // 물리 틱 사이 보간 → 추적 카메라 (보간된 차 위치 기준)
            scene.interpolate((float) clock.interpolationAlpha());
            if (usingChaseCamera()) {
                if (scene.cameraOverride(camera, (float) clock.frameDeltaSeconds())) {
                    chaseCamera.snap(); // 장면 카메라가 끝나면 추적 카메라가 차 뒤에서 바로 시작
                } else {
                    Scene.FollowTarget target = scene.followTarget();
                    chaseCamera.update(camera, target.position(), target.forward(), (float) clock.frameDeltaSeconds());
                }
            }

            // 이번 프레임의 카메라 행렬. 지터는 렌더 해상도 기준 픽셀 단위.
            float[] j = jitterEnabled ? jitter.next() : new float[]{0f, 0f};
            camera.update(frameLoop.renderWidth(), frameLoop.renderHeight(), j[0], j[1]);
            UpscaleCamera upscaleCamera = upscaler.temporal() ? UpscaleCamera.from(camera) : null;
            scene.observeCamera(camera);

            boolean drawn = frameLoop.drawFrame(renderer, upscaleCamera, scene::drawOverlay);
            long now = System.nanoTime();
            if (drawn) {
                frames++;
                double frameMs = (now - lastFrameEnd) / 1e6;
                double sceneMs = frameLoop.hasGpuSample() ? frameLoop.gpuSceneMs() : Double.NaN;
                double upscaleMs = frameLoop.hasGpuSample() ? frameLoop.gpuUpscaleMs() : Double.NaN;
                titleWindow.add(frameMs, sceneMs, upscaleMs);
                if (benchmark == null) {
                    stats.add(upscaler.name(), frameMs, sceneMs, upscaleMs);
                } else if (benchmark.measuring(now)) {
                    // 해상도를 이름에 붙인다: 측정 중 창 크기가 바뀌면 다른 줄로 나뉘어 섞이지 않는다
                    stats.add(benchmark.current().label() + " [" + frameLoop.resolutionText() + "]",
                            frameMs, sceneMs, upscaleMs);
                }
            }
            lastFrameEnd = now;

            if (now - fpsWindowStart >= 250_000_000L) { // 창 제목 4번/초 (속도 표시용)
                Vec3 p = camera.position();
                String gpu = Double.isNaN(titleWindow.avgGpuTotalMs()) ? ""
                        : String.format("  |  GPU 장면 %.2f + 업스케일 %.2f ms", titleWindow.avgSceneMs(),
                        titleWindow.avgUpscaleMs());
                String status = scene.statusText();
                window.setTitle(String.format("%s  |  %.0f FPS (%.2f ms)%s  |  %s %s%s%s  |  %s",
                        config.title(), titleWindow.avgFps(), titleWindow.avgFrameMs(), gpu,
                        upscaler.name(), frameLoop.resolutionText(),
                        jitterEnabled ? "  |  지터" : "",
                        renderer instanceof SceneRenderer sr && sr.showMotion() ? "  |  모션 벡터 보기" : "",
                        status.isEmpty() ? String.format("카메라 (%.1f, %.1f, %.1f)", p.x(), p.y(), p.z()) : status));
                titleWindow.reset();
                fpsWindowStart = now;
            }

            if (config.maxFrames() > 0 && frames >= config.maxFrames()) {
                Log.info("--frames=" + config.maxFrames() + " 도달, 자동 종료");
                break;
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        Log.info(String.format("루프 종료: %d 프레임, %.2f 초, 평균 %.1f FPS, 로직 %d 틱",
                frames, seconds, seconds > 0 ? frames / seconds : 0, clock.totalTicks()));
        Log.info("마지막 카메라 위치: " + camera.position());
        logPerformanceSummary();
    }

    private void logPerformanceSummary() {
        if (stats.isEmpty()) {
            return;
        }
        Log.info("성능 요약 (" + frameLoop.presentModeText() + ", 출력 " + frameLoop.outputWidth() + " 너비"
                + (config.validation() ? ", 검증 레이어 켜짐: CPU 시간이 늘어남" : "")
                + (config.gpuLoad() > 0 ? ", 인공 GPU 부하 " + config.gpuLoad() : "") + "):");
        for (String line : stats.summaryLines()) {
            Log.info("    " + line);
        }
        if (config.vsync()) {
            Log.info("    수직동기가 켜져 있어 FPS 는 모니터 주사율에서 막힌다. 비교는 GPU 시간으로 보거나 --no-vsync / --benchmark 사용");
        }
    }

    /** 자동 측정 단계: Native, DLSS 모드들(가능할 때), 같은 렌더 해상도의 선형 확대(비교 기준) */
    private List<Benchmark.Phase<Upscaler>> benchmarkPhases() {
        List<Benchmark.Phase<Upscaler>> phases = new ArrayList<>();
        phases.add(new Benchmark.Phase<>("Native 100%", NativeUpscaler::new));
        if (dlssUsable) {
            for (DlssMode mode : new DlssMode[]{DlssMode.QUALITY, DlssMode.BALANCED, DlssMode.PERFORMANCE}) {
                phases.add(new Benchmark.Phase<>("DLSS " + mode.label(), () -> new DlssUpscaler(dlss, device, mode)));
            }
            phases.add(new Benchmark.Phase<>("Bilinear 50% (DLSS 성능과 같은 렌더 해상도)",
                    () -> new BilinearUpscaler(0.5f)));
        } else {
            phases.add(new Benchmark.Phase<>("Bilinear 75%", () -> new BilinearUpscaler(0.75f)));
            phases.add(new Benchmark.Phase<>("Bilinear 50%", () -> new BilinearUpscaler(0.5f)));
        }
        return phases;
    }

    /**
     * 이번 틱 운전 입력. 추적 카메라일 때만 운전한다 (자유 카메라에서는 같은 키가 카메라를 움직이므로).
     * "막 눌림" 신호(변속 등)는 렌더 프레임에서 모아 두었다가 첫 틱에 한 번만 넘긴다.
     */
    private DriverInput driverInput() {
        if (!usingChaseCamera() || scene.menuOpen()) {
            pendingEdges.clear();
            return DriverInput.NONE;
        }
        DriverInput in = new DriverInput(
                inputMap.isActive(InputAction.THROTTLE, input),
                inputMap.isActive(InputAction.BRAKE, input),
                inputMap.isActive(InputAction.STEER_LEFT, input),
                inputMap.isActive(InputAction.STEER_RIGHT, input),
                inputMap.isActive(InputAction.HANDBRAKE, input),
                pendingEdges.contains(InputAction.SHIFT_UP),
                pendingEdges.contains(InputAction.SHIFT_DOWN),
                pendingEdges.contains(InputAction.TOGGLE_GEARBOX),
                pendingEdges.contains(InputAction.RESET_CAR),
                pendingEdges.contains(InputAction.NEXT_CAR),
                pendingEdges.contains(InputAction.TOGGLE_TRACTION),
                inputMap.isActive(InputAction.NITRO, input));
        pendingEdges.clear();
        return in;
    }

    private boolean usingChaseCamera() {
        return chaseMode && benchmark == null && scene.followTarget() != null;
    }

    /** --scene: terrain(기본, 지형 + 물리) 또는 demo(정육면체 데모). 물리 엔진을 못 쓰면 데모로 대체 */
    private static Scene createScene(String name) {
        if (name.equals("demo")) {
            return new DemoScene();
        }
        if (name.equals("garage")) {
            return new driftengine.world.GarageScene();
        }
        try {
            return new TerrainScene();
        } catch (RuntimeException | LinkageError e) {
            Log.error("지형 장면(물리) 시작 실패 → 데모 장면으로 진행: " + e, e);
            return new DemoScene();
        }
    }

    /** 업스케일러 교체: GPU 대기 → 오프스크린 재생성 → 이전 것 닫기 → 지터 맞추기 */
    private void switchUpscaler(Upscaler next) {
        frameLoop.setUpscaler(next);
        upscaler.close();
        upscaler = next;
        Log.info("업스케일러: " + upscaler.name() + " (" + frameLoop.resolutionText() + ")");
        applyJitterFor(upscaler);
    }

    /** 토글 키는 누르는 순간 한 번만 동작한다 (R 배율, M 모션 벡터 보기, J 지터) */
    private void handleToggleKeys() {
        keyPresses.update(inputMap, input);
        if (benchmark != null) {
            return; // 자동 측정 중에는 R / M / J 모두 끔 (측정 조건이 바뀌지 않게)
        }
        if (keyPresses.pressed(InputAction.CYCLE_RENDER_SCALE)) {
            Upscaler next;
            if (dlssUsable) {
                // DLSS 품질 → 균형 → 성능 → Native → DLSS 품질
                DlssMode mode = upscaler instanceof DlssUpscaler d ? d.mode().next() : DlssMode.QUALITY;
                next = mode != null ? new DlssUpscaler(dlss, device, mode) : new NativeUpscaler();
            } else {
                renderScale = UpscalerSelector.nextScale(renderScale);
                next = UpscalerSelector.forScale(renderScale);
            }
            switchUpscaler(next);
        }
        if (keyPresses.pressed(InputAction.TOGGLE_MOTION_VIEW) && renderer instanceof SceneRenderer sr) {
            sr.setShowMotion(!sr.showMotion());
            Log.info("모션 벡터 보기: " + (sr.showMotion() ? "켜짐" : "꺼짐"));
        }
        if (keyPresses.pressed(InputAction.TOGGLE_CAMERA) && scene.followTarget() != null) {
            chaseMode = !chaseMode;
            chaseCamera.snap();
            Log.info("카메라: " + (chaseMode ? "추적 (차 뒤, 운전 키 사용)" : "자유 (WASD/QE/화살표, 운전 멈춤)"));
        }
        for (InputAction a : new InputAction[]{InputAction.SHIFT_UP, InputAction.SHIFT_DOWN, InputAction.TOGGLE_GEARBOX,
                InputAction.RESET_CAR, InputAction.NEXT_CAR, InputAction.TOGGLE_TRACTION}) {
            if (keyPresses.pressed(a)) {
                pendingEdges.add(a);
            }
        }
        scene.uiInput(new driftengine.ui.UiInput(keyPresses.pressed(InputAction.MENU),
                keyPresses.pressed(InputAction.UI_UP), keyPresses.pressed(InputAction.UI_DOWN),
                keyPresses.pressed(InputAction.UI_LEFT), keyPresses.pressed(InputAction.UI_RIGHT),
                keyPresses.pressed(InputAction.UI_CONFIRM), keyPresses.pressed(InputAction.UI_BACK),
                keyPresses.pressed(InputAction.ENTER_SHOP)));
        if (keyPresses.pressed(InputAction.TOGGLE_JITTER)) {
            jitterEnabled = !jitterEnabled;
            jitterAuto = false;
            jitter.reset();
            Log.info("카메라 지터: " + (jitterEnabled ? "켜짐 (Halton, " + jitter.phaseCount() + "단계)" : "꺼짐"));
        }
    }

    /** 시간적 업스케일러(DLSS)는 지터가 필요하다: 켜고 단계 수를 맞춘다. 복사 방식으로 돌아가면 끈다. */
    private void applyJitterFor(Upscaler current) {
        if (current.temporal()) {
            int phases = current.recommendedJitterPhases(frameLoop.renderWidth(), frameLoop.outputWidth());
            jitter = new HaltonJitter(phases);
            jitterEnabled = true;
            jitterAuto = true;
            Log.info("카메라 지터: 켜짐 (Halton, " + phases + "단계, " + current.name() + " 권장값)");
        } else if (jitterAuto) {
            jitter = new HaltonJitter(8);
            jitterEnabled = false;
            jitterAuto = false;
            Log.info("카메라 지터: 꺼짐 (복사 방식 업스케일러)");
        }
    }

    @Override
    public void close() {
        if (device != null) {
            device.waitIdle(); // GPU 작업이 끝난 뒤에만 파괴한다.
        }
        resources.close();
        int errors = VulkanInstance.validationErrorCount();
        if (errors == 0) {
            Log.info("모든 리소스 해제 완료 (검증 오류 0건)");
        } else {
            Log.warn("모든 리소스 해제 완료, 하지만 검증 오류 " + errors + "건 발생 → 위 로그 확인 필요");
        }
    }
}
