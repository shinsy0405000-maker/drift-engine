package driftengine.selftest;

import java.util.ArrayList;
import java.util.List;

/**
 * 아주 작은 테스트 도구. JUnit 대신 쓴다 (의존성 추가 없음).
 * test(이름, 본문) 안에서 check(조건, 메시지)로 검사하고, 마지막에 summary 로 결과를 낸다.
 */
final class TestRunner {

    private static final List<String> failures = new ArrayList<>();
    private static int passed;
    private static String current = "";

    private TestRunner() {
    }

    static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    static void test(String name, Runnable body) {
        current = name;
        int before = failures.size();
        try {
            body.run();
        } catch (RuntimeException e) {
            failures.add(name + " - 예외: " + e);
        }
        boolean ok = failures.size() == before;
        if (ok) {
            passed++;
        }
        System.out.println((ok ? "[통과] " : "[실패] ") + name);
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            failures.add(current + " - " + message);
        }
    }

    /** 예외가 나야 통과 */
    static void expectThrows(Class<? extends Throwable> type, Runnable body, String message) {
        try {
            body.run();
        } catch (Throwable t) {
            check(type.isInstance(t), message + " (다른 예외: " + t + ")");
            return;
        }
        check(false, message + " (예외가 나지 않음)");
    }

    /** @return 실패 개수 */
    static int summary() {
        System.out.println();
        System.out.println("통과 " + passed + "개, 실패 " + failures.size() + "개");
        failures.forEach(f -> System.out.println("  실패: " + f));
        return failures.size();
    }
}
