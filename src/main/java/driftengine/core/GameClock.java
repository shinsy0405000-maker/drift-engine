package driftengine.core;

/**
 * 고정 시간 간격(fixed timestep) 게임 시계.
 * <p>
 * 렌더링은 모니터 속도(예: 60, 144Hz)대로 돌지만, 게임 로직(나중에 물리 포함)은 항상 같은 간격으로
 * 계산해야 결과가 프레임 속도에 따라 달라지지 않는다. 이 클래스는 매 렌더 프레임마다
 * "이번에 로직을 몇 번 돌려야 하는지"를 알려준다.
 * <p>
 * 렌더가 오래 멈췄을 때(창 드래그, 디버거 정지) 밀린 틱을 한꺼번에 처리하다 더 느려지는
 * 악순환을 막기 위해, 한 프레임에 반영하는 시간은 maxFrameSeconds 로 제한한다.
 */
public final class GameClock {

    private final double fixedDeltaSeconds;
    private final double maxFrameSeconds;

    private long lastNanos;
    private boolean started;
    private double accumulator;
    private double frameDeltaSeconds;
    private long totalTicks;

    /**
     * @param ticksPerSecond  로직 갱신 빈도 (예: 60)
     * @param maxFrameSeconds 한 프레임에서 따라잡을 최대 시간 (예: 0.25초)
     */
    public GameClock(double ticksPerSecond, double maxFrameSeconds) {
        if (ticksPerSecond <= 0 || maxFrameSeconds <= 0) {
            throw new IllegalArgumentException("양수여야 합니다: " + ticksPerSecond + ", " + maxFrameSeconds);
        }
        this.fixedDeltaSeconds = 1.0 / ticksPerSecond;
        this.maxFrameSeconds = maxFrameSeconds;
    }

    /**
     * 현재 시각을 넣으면 이번 프레임에 실행할 로직 틱 수를 돌려준다.
     * 첫 호출은 기준 시각만 잡고 0 을 돌려준다.
     *
     * @param nowNanos System.nanoTime() 값
     */
    public int advance(long nowNanos) {
        if (!started) {
            started = true;
            lastNanos = nowNanos;
            frameDeltaSeconds = 0;
            return 0;
        }
        double elapsed = (nowNanos - lastNanos) / 1e9;
        lastNanos = nowNanos;
        if (elapsed < 0) {
            elapsed = 0; // 시계가 거꾸로 가는 경우 방어
        }
        frameDeltaSeconds = Math.min(elapsed, maxFrameSeconds);
        accumulator += frameDeltaSeconds;

        int ticks = 0;
        // 부동소수점 오차로 틱이 하나 빠지는 것을 막기 위해 아주 작은 여유를 둔다
        while (accumulator + 1e-9 >= fixedDeltaSeconds) {
            accumulator -= fixedDeltaSeconds;
            ticks++;
        }
        if (accumulator < 0) {
            accumulator = 0;
        }
        totalTicks += ticks;
        return ticks;
    }

    /** 로직 1틱의 길이 (초) */
    public double fixedDeltaSeconds() {
        return fixedDeltaSeconds;
    }

    /** 마지막 프레임의 실제 경과 시간 (제한 적용 후, 초) */
    public double frameDeltaSeconds() {
        return frameDeltaSeconds;
    }

    /**
     * 다음 틱까지 얼마나 왔는지 (0~1). 렌더링 시 이전 상태와 현재 상태를 이 비율로 섞으면
     * 로직 빈도보다 화면 주사율이 높아도 움직임이 부드럽다.
     */
    public double interpolationAlpha() {
        return accumulator / fixedDeltaSeconds;
    }

    public long totalTicks() {
        return totalTicks;
    }
}
