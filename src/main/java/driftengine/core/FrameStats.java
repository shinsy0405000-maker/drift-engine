package driftengine.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 업스케일러(또는 측정 단계)별 프레임 시간 통계. GPU 없이 테스트할 수 있는 순수 계산.
 * <p>
 * 프레임 시간 = CPU 에서 잰 한 프레임 간격(=1000/FPS). GPU 장면/업스케일 = 타임스탬프로 잰 GPU 작업 시간.
 * GPU 값은 타임스탬프를 못 읽은 프레임이면 NaN 으로 넘기고, 평균에서 빠진다.
 */
public final class FrameStats {

    /** 한 묶음의 누적값 */
    public static final class Accumulator {
        private long frames;
        private double frameMsSum;
        private long gpuSamples;
        private double sceneMsSum;
        private double upscaleMsSum;

        public void add(double frameMs, double gpuSceneMs, double gpuUpscaleMs) {
            frames++;
            frameMsSum += frameMs;
            if (!Double.isNaN(gpuSceneMs) && !Double.isNaN(gpuUpscaleMs)) {
                gpuSamples++;
                sceneMsSum += gpuSceneMs;
                upscaleMsSum += gpuUpscaleMs;
            }
        }

        public long frames() {
            return frames;
        }

        public double avgFrameMs() {
            return frames == 0 ? Double.NaN : frameMsSum / frames;
        }

        public double avgFps() {
            double ms = avgFrameMs();
            return Double.isNaN(ms) || ms <= 0 ? Double.NaN : 1000.0 / ms;
        }

        public double avgSceneMs() {
            return gpuSamples == 0 ? Double.NaN : sceneMsSum / gpuSamples;
        }

        public double avgUpscaleMs() {
            return gpuSamples == 0 ? Double.NaN : upscaleMsSum / gpuSamples;
        }

        public double avgGpuTotalMs() {
            return gpuSamples == 0 ? Double.NaN : (sceneMsSum + upscaleMsSum) / gpuSamples;
        }

        public void reset() {
            frames = 0;
            frameMsSum = 0;
            gpuSamples = 0;
            sceneMsSum = 0;
            upscaleMsSum = 0;
        }

        /** 예: "143.8 FPS (프레임 6.95 ms), GPU 장면 0.42 + 업스케일 0.61 = 1.03 ms" */
        public String describe() {
            String gpu = gpuSamples == 0 ? "GPU 시간 없음"
                    : String.format("GPU 장면 %.2f + 업스케일 %.2f = %.2f ms",
                    avgSceneMs(), avgUpscaleMs(), avgGpuTotalMs());
            return String.format("%.1f FPS (프레임 %.2f ms), %s", avgFps(), avgFrameMs(), gpu);
        }
    }

    private final Map<String, Accumulator> byLabel = new LinkedHashMap<>();

    public void add(String label, double frameMs, double gpuSceneMs, double gpuUpscaleMs) {
        byLabel.computeIfAbsent(label, k -> new Accumulator()).add(frameMs, gpuSceneMs, gpuUpscaleMs);
    }

    public Accumulator get(String label) {
        return byLabel.get(label);
    }

    public boolean isEmpty() {
        return byLabel.isEmpty();
    }

    /** 처음 기록된 순서대로 "이름: 요약 (N 프레임)" */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Accumulator> e : byLabel.entrySet()) {
            lines.add(e.getKey() + ": " + e.getValue().describe() + " [" + e.getValue().frames() + " 프레임]");
        }
        return lines;
    }
}
