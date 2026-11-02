package driftengine.selftest;

import driftengine.core.Benchmark;
import driftengine.core.EngineConfig;
import driftengine.core.EngineSettings;
import driftengine.core.FrameStats;
import driftengine.render.FrameUniforms;
import driftengine.scene.Camera;
import driftengine.vulkan.GpuTimer;
import driftengine.vulkan.Swapchain;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Set;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.expectThrows;
import static driftengine.selftest.TestRunner.test;
import static org.lwjgl.vulkan.KHRSurface.*;

/** 성능 측정 기능 중 GPU 없이 확인할 수 있는 부분 */
final class PerfTests {

    private PerfTests() {
    }

    static void run() {
        test("표시 방식: 수직동기 켜면 FIFO, 끄면 IMMEDIATE → MAILBOX → FIFO", PerfTests::presentMode);
        test("GPU 타임스탬프 → ms (주기 배율, 비트 넘침 보정)", PerfTests::timestamps);
        test("프레임 통계: 평균, GPU 값 없는 프레임 제외", PerfTests::stats);
        test("자동 측정 단계: 준비 → 기록 → 다음 단계 → 끝", PerfTests::benchmark);
        test("실행 인자: --no-vsync, --gpu-load, --benchmark 는 수직동기·검증 끔", PerfTests::args);
        test("인공 GPU 부하 값이 유니폼 options.z 에", PerfTests::uniformLoad);
    }

    private static void presentMode() {
        Set<Integer> all = Set.of(VK_PRESENT_MODE_FIFO_KHR, VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR);
        check(Swapchain.choosePresentMode(true, all) == VK_PRESENT_MODE_FIFO_KHR, "수직동기 → FIFO");
        check(Swapchain.choosePresentMode(false, all) == VK_PRESENT_MODE_IMMEDIATE_KHR, "끔 → IMMEDIATE");
        check(Swapchain.choosePresentMode(false, Set.of(VK_PRESENT_MODE_FIFO_KHR, VK_PRESENT_MODE_MAILBOX_KHR))
                == VK_PRESENT_MODE_MAILBOX_KHR, "IMMEDIATE 없으면 MAILBOX");
        check(Swapchain.choosePresentMode(false, Set.of(VK_PRESENT_MODE_FIFO_KHR)) == VK_PRESENT_MODE_FIFO_KHR,
                "둘 다 없으면 FIFO");
    }

    private static void timestamps() {
        double[] ms = GpuTimer.intervalsMs(new long[]{1000, 3000, 7000}, -1L, 1.0);
        check(Math.abs(ms[0] - 0.002) < 1e-12 && Math.abs(ms[1] - 0.004) < 1e-12, "1ns 주기: " + ms[0] + ", " + ms[1]);
        double[] scaled = GpuTimer.intervalsMs(new long[]{0, 1_000_000, 1_500_000}, -1L, 2.0);
        check(Math.abs(scaled[0] - 2.0) < 1e-9 && Math.abs(scaled[1] - 1.0) < 1e-9, "2ns 주기");
        long mask = (1L << 36) - 1;
        double[] wrap = GpuTimer.intervalsMs(new long[]{mask - 9, 10, 30}, mask, 1.0);
        check(Math.abs(wrap[0] - 20e-6) < 1e-12 && Math.abs(wrap[1] - 20e-6) < 1e-12, "넘침 보정: " + wrap[0]);
    }

    private static void stats() {
        FrameStats s = new FrameStats();
        s.add("A", 10, 1, 2);
        s.add("A", 20, Double.NaN, Double.NaN);
        s.add("A", 30, 3, 4);
        s.add("B", 5, 0.5, 0.5);
        FrameStats.Accumulator a = s.get("A");
        check(a.frames() == 3 && Math.abs(a.avgFrameMs() - 20) < 1e-9, "프레임 평균 20ms");
        check(Math.abs(a.avgFps() - 50) < 1e-9, "50 FPS");
        check(Math.abs(a.avgSceneMs() - 2) < 1e-9 && Math.abs(a.avgUpscaleMs() - 3) < 1e-9, "GPU 평균(NaN 제외)");
        check(Math.abs(a.avgGpuTotalMs() - 5) < 1e-9, "GPU 합계");
        List<String> lines = s.summaryLines();
        check(lines.size() == 2 && lines.get(0).startsWith("A:") && lines.get(1).startsWith("B:"), "기록 순서: " + lines);
        a.reset();
        check(a.frames() == 0 && Double.isNaN(a.avgFrameMs()), "초기화");
    }

    private static void benchmark() {
        Benchmark<String> b = new Benchmark<>(List.of(
                new Benchmark.Phase<>("one", () -> "1"), new Benchmark.Phase<>("two", () -> "2")), 1.0, 2.0);
        long s = 1_000_000_000L;
        check(b.tick(0) == Benchmark.Action.SWITCH && b.current().label().equals("one"), "첫 단계");
        check(!b.measuring(s / 2), "준비 중에는 기록 안 함");
        check(b.tick(s / 2) == Benchmark.Action.NONE, "준비 중");
        check(b.measuring(s + 1), "1초 뒤부터 기록");
        check(b.tick(3 * s) == Benchmark.Action.SWITCH && b.current().label().equals("two"), "3초 뒤 다음 단계");
        check(!b.measuring(3 * s + 1), "새 단계는 다시 준비부터");
        check(b.tick(6 * s) == Benchmark.Action.FINISHED, "끝");
        check(Math.abs(b.totalSeconds() - 6.0) < 1e-9, "예상 시간 6초");
    }

    private static void args() {
        EngineSettings d = EngineSettings.defaults();
        check(d.vsync(), "기본 수직동기 켜짐");
        EngineConfig normal = EngineConfig.fromArgs(new String[0], d);
        check(normal.vsync() && !normal.benchmark() && normal.gpuLoad() == 0, "기본값");
        check(!EngineConfig.fromArgs(new String[]{"--no-vsync"}, d).vsync(), "--no-vsync");
        check(!EngineConfig.fromArgs(new String[0], EngineSettings.parse("render.vsync=false\n")).vsync(), "설정 파일");
        EngineConfig bench = EngineConfig.fromArgs(new String[]{"--benchmark", "--gpu-load=300"}, d);
        check(bench.benchmark() && !bench.vsync() && !bench.validation() && bench.gpuLoad() == 300, "벤치마크");
        expectThrows(IllegalArgumentException.class,
                () -> EngineConfig.fromArgs(new String[]{"--gpu-load=999999"}, d), "부하 상한");
        expectThrows(IllegalArgumentException.class,
                () -> EngineConfig.fromArgs(new String[]{"--gpu-load=-1"}, d), "음수 부하");
    }

    private static void uniformLoad() {
        Camera cam = new Camera();
        cam.beginFrame();
        cam.update(800, 600, 0, 0);
        FloatBuffer buf = FloatBuffer.allocate(FrameUniforms.FLOAT_COUNT);
        FrameUniforms.pack(cam, false, 250, driftengine.scene.Environment.afternoon(0), buf);
        check(buf.get(66) == 250f, "options.z = " + buf.get(66));
        FloatBuffer zero = FloatBuffer.allocate(FrameUniforms.FLOAT_COUNT);
        FrameUniforms.pack(cam, false, zero);
        check(zero.get(66) == 0f, "기본 0");
    }
}
