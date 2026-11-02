package driftengine.upscale;

import driftengine.vulkan.GpuInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DLSS 사전 점검. Java 에서 확인할 수 있는 조건만 검사한다.
 * <p>
 * 여기서 모든 항목을 통과해도 결과는 최대 NOT_VERIFIED 다.
 * "지원됨(SUPPORTED)"은 Streamline 의 slIsFeatureSupported 결과로만 판정한다(C++ 브리지 필요, Stage 4).
 * <p>
 * 조건 출처: NVIDIA Streamline 문서 (Windows 10 20H1+, NVIDIA 드라이버 512.15+, 배포 DLL 목록).
 */
public final class DlssPrecheck {

    /** Streamline 문서의 최소 드라이버 버전 */
    static final int MIN_DRIVER_MAJOR = 512;
    static final int MIN_DRIVER_MINOR = 15;

    /** Streamline 문서의 배포 필수 DLL (DLSS 사용 시) */
    static final List<String> REQUIRED_FILES = List.of(
            "sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "nvngx_dlss.dll");

    /** 우리가 만들 C++ 브리지 (아직 없음) */
    static final String BRIDGE_FILE = "drift_sl_bridge.dll";

    /** Streamline DLL 을 둘 폴더 (작업 디렉터리 기준) */
    public static final Path DEFAULT_DIR = Path.of("natives", "streamline");

    private DlssPrecheck() {
    }

    public static UpscalerStatus run(GpuInfo gpu, Path streamlineDir) {
        List<String> checks = new ArrayList<>();
        boolean hardFail = false;

        // 1. 운영체제
        String os = System.getProperty("os.name", "");
        if (os.toLowerCase(Locale.ROOT).startsWith("windows")) {
            checks.add("[통과] 운영체제: " + os);
        } else {
            checks.add("[실패] 운영체제: " + os + " (Streamline 은 Windows 전용)");
            hardFail = true;
        }

        // 2. GPU 제조사
        if (gpu.isNvidia()) {
            checks.add("[통과] NVIDIA GPU: " + gpu.name());
        } else {
            checks.add("[실패] NVIDIA GPU 아님: " + gpu.name());
            hardFail = true;
        }

        // 3. RTX 여부 (이름으로만 추정 → 참고용, 판정에 쓰지 않음)
        if (gpu.isNvidia()) {
            boolean rtxName = gpu.name().toUpperCase(Locale.ROOT).contains("RTX");
            checks.add((rtxName ? "[참고] " : "[주의] ") + "이름에 RTX " + (rtxName ? "포함" : "없음")
                    + " (DLSS 는 RTX GPU 필요, 정확한 판정은 Streamline API 로)");
        }

        // 4. 드라이버 버전
        if (gpu.isNvidia()) {
            boolean driverOk = gpu.nvidiaDriverMajor() > MIN_DRIVER_MAJOR
                    || (gpu.nvidiaDriverMajor() == MIN_DRIVER_MAJOR && gpu.nvidiaDriverMinor() >= MIN_DRIVER_MINOR);
            checks.add((driverOk ? "[통과] " : "[실패] ") + "드라이버 " + gpu.driverVersionString()
                    + " (필요: " + MIN_DRIVER_MAJOR + "." + MIN_DRIVER_MINOR + " 이상)");
            hardFail |= !driverOk;
        }

        // 5. Streamline DLL 존재 여부 (없다고 '미지원'은 아니므로 hardFail 로 치지 않음)
        Path dir = streamlineDir.toAbsolutePath().normalize();
        List<String> missing = new ArrayList<>();
        for (String file : REQUIRED_FILES) {
            if (!Files.isRegularFile(dir.resolve(file))) {
                missing.add(file);
            }
        }
        checks.add(missing.isEmpty()
                ? "[통과] Streamline DLL 모두 있음: " + dir
                : "[없음] Streamline DLL " + missing + " (위치: " + dir + ", gradlew setupStreamline 으로 준비)");

        // 6. C++ 브리지
        boolean bridge = Files.isRegularFile(dir.resolve(BRIDGE_FILE));
        checks.add(bridge ? "[통과] C++ 브리지 있음" : "[없음] C++ 브리지 " + BRIDGE_FILE + " (gradlew buildStreamlineBridge 로 빌드)");

        // 7. 공식 판정
        checks.add("[미실행] slIsFeatureSupported(kFeatureDLSS) - --dlss 로 실행하면 확인");

        UpscalerStatus.Support support = hardFail
                ? UpscalerStatus.Support.UNSUPPORTED
                : UpscalerStatus.Support.NOT_VERIFIED;
        return new UpscalerStatus("DLSS", support, false, checks);
    }

    /**
     * 사전 점검 결과에 공식 판정(slIsFeatureSupported)을 반영한다. 공식 판정이 최종 근거다.
     *
     * @param code   0 이면 지원, 그 외는 SlResult 코드
     * @param detail 브리지가 알려준 상세 메시지
     */
    public static UpscalerStatus withOfficialResult(UpscalerStatus precheck, int code, String detail) {
        List<String> checks = new ArrayList<>(precheck.checks());
        checks.removeIf(c -> c.startsWith("[미실행] slIsFeatureSupported"));
        checks.add(code == 0
                ? "[공식] slIsFeatureSupported(DLSS): 지원됨"
                : "[공식] slIsFeatureSupported(DLSS): " + SlResult.describe(code)
                  + (detail == null || detail.isEmpty() ? "" : " - " + detail));
        UpscalerStatus.Support support = code == 0
                ? UpscalerStatus.Support.SUPPORTED
                : UpscalerStatus.Support.UNSUPPORTED;
        return new UpscalerStatus(precheck.name(), support, false, checks);
    }
}
