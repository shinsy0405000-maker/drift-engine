package driftengine.math;

/**
 * 프레임마다 다른 서브픽셀 지터 오프셋을 만든다 (Halton 수열, 밑 2와 3).
 * <p>
 * DLSS 같은 시간적 업스케일러는 매 프레임 카메라를 픽셀보다 작게 흔들어 여러 프레임의 샘플을 모은다.
 * Halton 수열은 무작위보다 고르게 퍼진 위치를 준다.
 * <p>
 * 반환값은 픽셀 단위 [-0.5, 0.5). 몇 프레임마다 반복할지(phaseCount)는 업스케일 비율에 맞춰
 * 정해야 하며, 정확한 권장값은 DLSS 프로그래밍 가이드를 확인한 뒤 설정한다(현재 기본 8).
 */
public final class HaltonJitter {

    private final int phaseCount;
    private int index;

    public HaltonJitter(int phaseCount) {
        if (phaseCount < 1) {
            throw new IllegalArgumentException("phaseCount 는 1 이상: " + phaseCount);
        }
        this.phaseCount = phaseCount;
    }

    /** 밑 base 의 Halton 수열 index 번째 값 (index ≥ 1), 범위 (0, 1). */
    public static float halton(int index, int base) {
        float result = 0;
        float fraction = 1;
        int i = index;
        while (i > 0) {
            fraction /= base;
            result += fraction * (i % base);
            i /= base;
        }
        return result;
    }

    /** 이번 프레임의 지터 (픽셀 단위, x/y 각각 [-0.5, 0.5)) 를 돌려주고 다음 단계로 넘어간다. */
    public float[] next() {
        int n = index % phaseCount + 1; // 0 번째(=0)는 건너뛴다
        index++;
        return new float[]{halton(n, 2) - 0.5f, halton(n, 3) - 0.5f};
    }

    public int phaseCount() {
        return phaseCount;
    }

    public void reset() {
        index = 0;
    }
}
