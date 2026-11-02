package driftengine.upscale;

import driftengine.core.Log;
import driftengine.vulkan.GpuInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Streamline(DLSS) 사용 세션. --dlss 를 줬을 때만 만들어진다.
 * <p>
 * 순서 제약 (Streamline 문서):
 * <ol>
 *   <li>tryStart: Vulkan 을 건드리기 전에 호출 (브리지 로드 → 서명 확인 → interposer 로드 → slInit)</li>
 *   <li>interposerPath() 를 VulkanLoader 에 넘겨 LWJGL/GLFW 가 interposer 를 Vulkan 로더로 쓰게 함</li>
 *   <li>checkSupport: GPU 를 고른 뒤 slIsFeatureSupported 로 공식 판정</li>
 *   <li>shutdownStreamline: Vulkan 디바이스/인스턴스 파괴 전에 slShutdown</li>
 * </ol>
 * 어느 단계에서든 실패하면 로그를 남기고 DLSS 없이(Native) 계속 진행한다.
 */
public final class DlssSession implements AutoCloseable {

    private final StreamlineBridge bridge;
    private final Path interposer;
    private boolean streamlineActive = true;

    private DlssSession(StreamlineBridge bridge, Path interposer) {
        this.bridge = bridge;
        this.interposer = interposer;
    }

    /**
     * Streamline 을 시작한다. 실패하면 이유를 로그에 남기고 null 을 돌려준다(→ Native 경로).
     *
     * @param projectId GUID 형식 프로젝트 ID. NVIDIA 가 발급한 applicationId 가 없을 때 NGX(DLSS 본체)를
     *                  초기화하려면 엔진 버전과 함께 반드시 필요하다 (sl.common/commonEntry.cpp, DLSS 가이드 5.2.1).
     *                  없으면 Streamline 은 켜지지만 디바이스 생성 시 NGX 가 꺼져 DLSS 가 "지원 안 됨"이 된다.
     */
    public static DlssSession tryStart(Path streamlineDir, String engineVersion, String projectId) {
        Path dir = streamlineDir.toAbsolutePath().normalize();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            Log.warn("DLSS 시작 안 함: Windows 가 아님");
            return null;
        }
        for (String file : DlssPrecheck.REQUIRED_FILES) {
            if (!Files.isRegularFile(dir.resolve(file))) {
                Log.warn("DLSS 시작 안 함: " + dir.resolve(file) + " 없음");
                return null;
            }
        }
        Path bridgeDll = dir.resolve(DlssPrecheck.BRIDGE_FILE);
        if (!Files.isRegularFile(bridgeDll)) {
            Log.warn("DLSS 시작 안 함: C++ 브리지 없음 " + bridgeDll + " (gradlew buildStreamlineBridge 로 빌드하세요)");
            return null;
        }

        StreamlineBridge bridge;
        try {
            bridge = StreamlineBridge.load(bridgeDll);
        } catch (RuntimeException e) {
            Log.warn("DLSS 시작 안 함: 브리지 로드 실패 - " + e.getMessage());
            return null;
        }

        Path interposer = dir.resolve("sl.interposer.dll");
        int code = bridge.loadInterposer(interposer, true); // 문서: 배포판은 반드시 서명 확인
        if (code != 0) {
            Log.warn("DLSS 시작 안 함: interposer 로드 실패 " + SlResult.describe(code) + " - " + bridge.lastError());
            bridge.close();
            return null;
        }

        Path logDir = Path.of("logs", "streamline").toAbsolutePath();
        try {
            Files.createDirectories(logDir);
        } catch (Exception e) {
            logDir = null; // 로그 폴더가 없어도 진행
        }
        code = bridge.init(dir, logDir, false, engineVersion, projectId);
        if (code != 0) {
            Log.warn("DLSS 시작 안 함: slInit 실패 " + SlResult.describe(code) + " - " + bridge.lastError());
            bridge.close();
            return null;
        }

        Log.info("Streamline 초기화 완료 (Vulkan, DLSS 로드 요청). 로더: " + interposer);
        return new DlssSession(bridge, interposer);
    }

    /** VulkanLoader 에 넘길 sl.interposer.dll 절대 경로 */
    public Path interposerPath() {
        return interposer;
    }

    /**
     * 공식 판정: slIsFeatureSupported(kFeatureDLSS).
     *
     * @return 0 이면 지원, 그 외는 SlResult 코드
     */
    public int checkSupport(GpuInfo gpu) {
        int code = bridge.isDlssSupported(gpu.device().address());
        if (code == 0) {
            Log.info("slIsFeatureSupported(DLSS): 지원됨");
            logRequirements();
        } else {
            Log.warn("slIsFeatureSupported(DLSS): " + SlResult.describe(code) + " - " + bridge.lastError());
        }
        return code;
    }

    public String lastError() {
        return bridge.lastError();
    }

    private void logRequirements() {
        StreamlineBridge.RequirementsResult result = bridge.dlssRequirements();
        if (result.code() != 0) {
            Log.warn("slGetFeatureRequirements(DLSS) 실패: " + SlResult.describe(result.code()));
            return;
        }
        StreamlineBridge.VkRequirements r = result.requirements();
        Log.info("DLSS Vulkan 요구 사항 (다음 단계에서 인스턴스/디바이스 생성에 반영 예정):");
        Log.info("    인스턴스 확장 " + r.instanceExtensions());
        Log.info("    디바이스 확장 " + r.deviceExtensions());
        Log.info("    1.2 기능 " + r.features12() + ", 1.3 기능 " + r.features13());
        Log.info("    추가 큐: graphics " + r.graphicsQueues() + ", compute " + r.computeQueues()
                + ", optical flow " + r.opticalFlowQueues());
    }

    /** slShutdown. Vulkan 디바이스/인스턴스 파괴 전에 호출해야 한다. 여러 번 불러도 안전. */
    public void shutdownStreamline() {
        if (!streamlineActive) {
            return;
        }
        streamlineActive = false;
        int code = bridge.shutdown();
        if (code != 0) {
            Log.warn("slShutdown 실패: " + SlResult.describe(code) + " - " + bridge.lastError());
        } else {
            Log.info("Streamline 종료");
        }
    }

    // ------------------------------------------------------------------ DLSS 실행 (Stage 4e)

    /** 이 엔진은 화면 하나만 쓰므로 Streamline 뷰포트 id 는 0 하나 */
    public static final int VIEWPORT = 0;

    /** @return {코드, 렌더 너비, 렌더 높이} (slDLSSGetOptimalSettings) */
    public int[] optimalSettings(DlssMode mode, int outputWidth, int outputHeight) {
        return bridge.dlssOptimalSettings(mode.slValue(), outputWidth, outputHeight);
    }

    /**
     * slDLSSSetOptions. 색은 선형(톤매핑 전) 값이므로 HDR 로 표시하고, 노출 텍스처가 없으니 자동 노출을 쓴다
     * (DLSS 문서: 노출 텍스처를 주지 않으면 자동 노출 모드).
     */
    public int setOptions(DlssMode mode, int outputWidth, int outputHeight) {
        return bridge.dlssSetOptions(VIEWPORT, mode.slValue(), outputWidth, outputHeight, true, true);
    }

    public int beginFrame(int frameIndex) {
        return bridge.beginFrame(frameIndex);
    }

    public int setConstants(UpscaleCamera camera, StreamlineBridge.ConstantFlags flags) {
        return bridge.setConstants(VIEWPORT, camera, flags);
    }

    public StreamlineBridge bridge() {
        return bridge;
    }

    /** slFreeResources. GPU 가 DLSS 명령을 다 끝낸 뒤 호출해야 한다. */
    public int freeResources() {
        if (!streamlineActive) {
            return 0;
        }
        return bridge.dlssFree(VIEWPORT);
    }

    public boolean active() {
        return streamlineActive;
    }

    @Override
    public void close() {
        shutdownStreamline();
        bridge.close();
    }
}
