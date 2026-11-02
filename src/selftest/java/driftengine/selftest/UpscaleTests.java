package driftengine.selftest;

import driftengine.core.EngineSettings;
import driftengine.input.InputAction;
import driftengine.input.InputMap;
import driftengine.upscale.BilinearUpscaler;
import driftengine.upscale.NativeUpscaler;
import driftengine.upscale.Upscaler;
import driftengine.upscale.UpscalerSelector;
import driftengine.vulkan.RenderTargets;
import driftengine.vulkan.VulkanDevice;

import java.util.Set;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.expectThrows;
import static driftengine.selftest.TestRunner.test;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

/** 4c 단계(오프스크린 렌더링 + 업스케일) 중 GPU 없이 확인할 수 있는 부분 */
final class UpscaleTests {

    private UpscaleTests() {
    }

    static void run() {
        test("Native: 렌더 해상도 = 출력 해상도", UpscaleTests::nativeResolution);
        test("디바이스 확장: Streamline 경유일 때만 KHR_buffer_device_address 추가", UpscaleTests::streamlineDeviceExtensions);
        test("Bilinear: 배율대로 렌더 해상도 계산", UpscaleTests::bilinearResolution);
        test("Bilinear: 잘못된 배율 거부", UpscaleTests::invalidScale);
        test("배율 선택: 100% → Native, 그 아래 → Bilinear", UpscaleTests::forScale);
        test("R 키 순환: 100 → 75 → 50 → 100%", UpscaleTests::cycle);
        test("오프스크린 색 포맷: HDR 우선, 안 되면 스왑체인 포맷", UpscaleTests::colorFormat);
        test("설정 render.scale 읽기와 범위 검사", UpscaleTests::settings);
        test("기본 키: R = 배율 전환", UpscaleTests::defaultKey);
    }

    private static void nativeResolution() {
        Upscaler.Resolution r = new NativeUpscaler().renderResolution(1920, 1080);
        check(r.width() == 1920 && r.height() == 1080, "결과 " + r);
    }

    private static void bilinearResolution() {
        Upscaler.Resolution r75 = new BilinearUpscaler(0.75f).renderResolution(1280, 720);
        check(r75.width() == 960 && r75.height() == 540, "75% " + r75);
        Upscaler.Resolution r50 = new BilinearUpscaler(0.5f).renderResolution(1280, 720);
        check(r50.width() == 640 && r50.height() == 360, "50% " + r50);
        Upscaler.Resolution tiny = new BilinearUpscaler(0.25f).renderResolution(1, 1);
        check(tiny.width() == 1 && tiny.height() == 1, "최소 1픽셀 " + tiny);
    }

    private static void invalidScale() {
        expectThrows(IllegalArgumentException.class, () -> new BilinearUpscaler(0.1f), "너무 작음");
        expectThrows(IllegalArgumentException.class, () -> new BilinearUpscaler(1.5f), "1 초과");
        expectThrows(IllegalArgumentException.class, () -> new BilinearUpscaler(Float.NaN), "NaN");
    }

    private static void forScale() {
        check(UpscalerSelector.forScale(1.0f) instanceof NativeUpscaler, "100%");
        check(UpscalerSelector.forScale(0.75f) instanceof BilinearUpscaler, "75%");
    }

    private static void cycle() {
        check(UpscalerSelector.nextScale(1.0f) == 0.75f, "100 → 75");
        check(UpscalerSelector.nextScale(0.75f) == 0.5f, "75 → 50");
        check(UpscalerSelector.nextScale(0.5f) == 1.0f, "50 → 100");
        check(UpscalerSelector.nextScale(0.6f) == 1.0f, "목록에 없는 값 → 100");
    }

    private static void colorFormat() {
        check(RenderTargets.chooseColorFormat(f -> true, VK_FORMAT_B8G8R8A8_SRGB) == VK_FORMAT_R16G16B16A16_SFLOAT, "HDR");
        check(RenderTargets.chooseColorFormat(f -> f == VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB)
                == VK_FORMAT_B8G8R8A8_SRGB, "대체");
        expectThrows(RuntimeException.class,
                () -> RenderTargets.chooseColorFormat(f -> false, VK_FORMAT_B8G8R8A8_SRGB), "지원 포맷 없음");
    }

    private static void settings() {
        check(EngineSettings.defaults().renderScale() == 1.0f, "기본 1.0");
        check(EngineSettings.parse("render.scale=0.5\n").renderScale() == 0.5f, "0.5");
        EngineSettings bad = EngineSettings.parse("render.scale=2\n");
        check(bad.renderScale() == 1.0f && bad.warnings().size() == 1, "범위 밖 → 기본값 + 경고");
        check(EngineSettings.defaults().toText().contains("render.scale=1"), "설정 파일에 기록");
    }

    private static void defaultKey() {
        check(InputMap.defaults().isActive(InputAction.CYCLE_RENDER_SCALE, Set.of(GLFW_KEY_R)::contains), "R");
    }

    private static void streamlineDeviceExtensions() {
        Set<String> available = Set.of("VK_KHR_swapchain", VulkanDevice.KHR_BUFFER_DEVICE_ADDRESS);
        check(!VulkanDevice.deviceExtensions(false, available).contains(VulkanDevice.KHR_BUFFER_DEVICE_ADDRESS),
                "기본 경로에는 추가하지 않음");
        check(VulkanDevice.deviceExtensions(true, available).contains(VulkanDevice.KHR_BUFFER_DEVICE_ADDRESS),
                "Streamline 경유 + 지원 → 추가");
        check(!VulkanDevice.deviceExtensions(true, Set.of("VK_KHR_swapchain")).contains(VulkanDevice.KHR_BUFFER_DEVICE_ADDRESS),
                "GPU 가 지원하지 않으면 추가하지 않음");
        check(VulkanDevice.deviceExtensions(true, available).contains("VK_KHR_swapchain"), "스왑체인 확장은 항상 있음");
    }
}
