package driftengine.core;

/**
 * 엔진에서 발생하는 모든 예외의 부모 클래스.
 * <p>
 * 하위 클래스(VulkanException, 이후 DlssException 등)로 원인을 구분하면,
 * 호출하는 쪽에서 필요한 종류만 골라 잡을 수 있다.
 */
public class EngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
