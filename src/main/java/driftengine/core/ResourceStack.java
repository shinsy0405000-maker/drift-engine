package driftengine.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 생성 순서의 역순으로 리소스를 해제하는 스택.
 * <p>
 * Vulkan 객체는 의존 관계가 엄격하다(Device → Surface → Instance → Window 순으로 파괴해야 함).
 * 생성 도중 예외가 나도 이미 만든 것만 정확히 역순으로 해제할 수 있게 해준다.
 * 해제 중 발생한 예외는 모두 수집해서 첫 번째 예외에 suppressed 로 붙인다.
 */
public final class ResourceStack implements AutoCloseable {

    private final Deque<AutoCloseable> stack = new ArrayDeque<>();

    public <T extends AutoCloseable> T push(T resource) {
        stack.push(resource);
        return resource;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        while (!stack.isEmpty()) {
            AutoCloseable resource = stack.pop();
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new RuntimeException("리소스 해제 실패: " + resource.getClass().getSimpleName(), e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** 생성 실패 경로에서 사용: 원래 예외를 유지하고 해제 오류는 suppressed 로 붙인다. */
    public void closeAfterFailure(Throwable original) {
        try {
            close();
        } catch (RuntimeException e) {
            original.addSuppressed(e);
        }
    }
}
