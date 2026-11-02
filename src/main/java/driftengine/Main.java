package driftengine;

import driftengine.core.Engine;
import driftengine.core.EngineConfig;
import driftengine.core.EngineSettings;
import driftengine.core.Log;

/**
 * 진입점. 설정 파일과 실행 인자를 읽고 엔진을 실행한 뒤, 어떤 경우에도 리소스를 해제하고 종료 코드를 돌려준다.
 * 종료 코드: 0 정상, 1 실행 중 오류, 2 실행 인자 오류.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Log.openFile(java.nio.file.Path.of("logs", "drift-latest.log")); // 콘솔 한글이 깨져도 이 파일은 UTF-8
        EngineSettings settings = EngineSettings.load(EngineSettings.DEFAULT_PATH);
        for (String warning : settings.warnings()) {
            Log.warn("설정: " + warning);
        }

        EngineConfig config;
        try {
            config = EngineConfig.fromArgs(args, settings);
        } catch (IllegalArgumentException e) {
            Log.error("잘못된 실행 인자: " + e.getMessage());
            Log.info(EngineConfig.USAGE);
            System.exit(2);
            return;
        }

        int exitCode = 0;
        try (Engine engine = new Engine(config, settings)) {
            engine.run();
        } catch (Throwable t) {
            Log.error("치명적 오류로 종료합니다: " + t.getMessage(), t);
            exitCode = 1;
        }
        Log.closeFile();
        System.exit(exitCode);
    }
}
