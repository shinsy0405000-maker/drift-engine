package driftengine.selftest;

import driftengine.core.GameClock;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.expectThrows;
import static driftengine.selftest.TestRunner.test;

/** GameClock 테스트. 실제 시간 대신 나노초 값을 직접 넣어 결과를 정확히 확인한다. */
final class ClockTests {

    private static final long SECOND = 1_000_000_000L;

    private ClockTests() {
    }

    static void run() {
        test("첫 호출은 0틱 (기준 시각만 설정)", ClockTests::firstCall);
        test("60Hz 에서 1/60초 → 1틱", ClockTests::oneTick);
        test("반 틱 경과 → 0틱, 보간 비율 0.5", ClockTests::halfTick);
        test("144Hz 화면에서도 1초에 60틱", ClockTests::highRefresh);
        test("5초 멈춤 → 0.25초만 따라잡음 (15틱)", ClockTests::hitchClamp);
        test("시간이 거꾸로 가도 음수 틱 없음", ClockTests::backwards);
        test("잘못된 설정 거부", ClockTests::invalid);
    }

    private static void firstCall() {
        GameClock c = new GameClock(60, 0.25);
        check(c.advance(123 * SECOND) == 0, "첫 호출 0");
        check(c.totalTicks() == 0, "누적 0");
    }

    private static void oneTick() {
        GameClock c = new GameClock(60, 0.25);
        c.advance(0);
        check(c.advance(SECOND / 60) == 1, "1틱");
    }

    private static void halfTick() {
        GameClock c = new GameClock(60, 0.25);
        c.advance(0);
        check(c.advance(SECOND / 120) == 0, "0틱");
        check(Math.abs(c.interpolationAlpha() - 0.5) < 1e-6, "alpha " + c.interpolationAlpha());
        check(c.advance(SECOND / 60) == 1, "누적되어 1틱");
    }

    private static void highRefresh() {
        GameClock c = new GameClock(60, 0.25);
        long t = 0;
        c.advance(t);
        int total = 0;
        for (int i = 1; i <= 144; i++) {
            total += c.advance(t + i * SECOND / 144);
        }
        check(total == 60, "1초 동안 " + total + "틱");
    }

    private static void hitchClamp() {
        GameClock c = new GameClock(60, 0.25);
        c.advance(0);
        int ticks = c.advance(5 * SECOND);
        check(ticks == 15, "틱 " + ticks);
        check(Math.abs(c.frameDeltaSeconds() - 0.25) < 1e-9, "프레임 시간 제한 " + c.frameDeltaSeconds());
    }

    private static void backwards() {
        GameClock c = new GameClock(60, 0.25);
        c.advance(10 * SECOND);
        check(c.advance(9 * SECOND) == 0, "0틱");
        check(c.interpolationAlpha() >= 0, "보간 비율 음수 아님");
    }

    private static void invalid() {
        expectThrows(IllegalArgumentException.class, () -> new GameClock(0, 0.25), "0Hz");
        expectThrows(IllegalArgumentException.class, () -> new GameClock(60, -1), "음수 제한");
    }
}
