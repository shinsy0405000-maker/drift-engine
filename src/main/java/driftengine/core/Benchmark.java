package driftengine.core;

import java.util.List;
import java.util.function.Supplier;

/**
 * 자동 성능 측정 (--benchmark). 단계마다 업스케일러를 바꾸고, 준비 시간 뒤 일정 시간만 기록한다.
 * 시간은 호출하는 쪽이 넘겨준다 → GPU 없이 테스트할 수 있다.
 *
 * @param <T> 단계마다 만들 대상 (엔진에서는 Upscaler)
 */
public final class Benchmark<T> {

    /** 단계 하나: 이름 + 만드는 방법 */
    public record Phase<T>(String label, Supplier<T> factory) {
    }

    /** tick 결과 */
    public enum Action {
        /** 아무것도 안 함 */
        NONE,
        /** current() 단계로 바꿔야 함 */
        SWITCH,
        /** 모든 단계 끝 */
        FINISHED
    }

    private final List<Phase<T>> phases;
    private final long warmupNanos;
    private final long measureNanos;
    private int index = -1;
    private long phaseStart;

    public Benchmark(List<Phase<T>> phases, double warmupSeconds, double measureSeconds) {
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("측정 단계가 없습니다");
        }
        this.phases = List.copyOf(phases);
        this.warmupNanos = (long) (warmupSeconds * 1e9);
        this.measureNanos = (long) (measureSeconds * 1e9);
    }

    /** 매 프레임 호출. 첫 호출은 항상 SWITCH(첫 단계). */
    public Action tick(long nowNanos) {
        if (index == -1) {
            index = 0;
            phaseStart = nowNanos;
            return Action.SWITCH;
        }
        if (index >= phases.size()) {
            return Action.FINISHED;
        }
        if (nowNanos - phaseStart >= warmupNanos + measureNanos) {
            index++;
            phaseStart = nowNanos;
            return index >= phases.size() ? Action.FINISHED : Action.SWITCH;
        }
        return Action.NONE;
    }

    /** 지금 단계가 준비 시간을 지나 기록 중인지 */
    public boolean measuring(long nowNanos) {
        return index >= 0 && index < phases.size() && nowNanos - phaseStart >= warmupNanos;
    }

    public Phase<T> current() {
        return phases.get(index);
    }

    public int phaseNumber() {
        return index + 1;
    }

    public int phaseCount() {
        return phases.size();
    }

    /** 전체 예상 시간 (초) */
    public double totalSeconds() {
        return phases.size() * (warmupNanos + measureNanos) / 1e9;
    }
}
