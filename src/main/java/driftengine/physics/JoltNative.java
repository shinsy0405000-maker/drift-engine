package driftengine.physics;

import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.JoltPhysicsObject;
import driftengine.core.EngineException;
import driftengine.core.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * jolt-jni 네이티브 라이브러리(joltjni.dll) 로드와 Jolt 전역 초기화. 프로그램에서 한 번만.
 * <p>
 * DLL 은 jolt-jni 의 "ReleaseSp" jar 안에 들어 있다 (windows/x86-64/com/github/stephengold/joltjni.dll, jar 확인).
 * 문서의 예제는 JSnapLoader 라이브러리로 꺼내지만, 의존성을 늘리지 않으려고 직접 꺼내서 System.load 한다.
 * 초기화 순서는 jolt-jni 문서(Add to an existing project)를 따른다:
 * 로드 → startCleaner → registerDefaultAllocator → 콜백 → newFactory → registerTypes.
 */
@SuppressWarnings("restricted") // 네이티브 로드는 의도된 것 (--enable-native-access 로 허용)
public final class JoltNative {

    private static final String WINDOWS_RESOURCE = "windows/x86-64/com/github/stephengold/joltjni.dll";
    private static final Path EXTRACT_DIR = Path.of("natives", "jolt");

    private static boolean initialized;

    private JoltNative() {
    }

    /** 이미 초기화됐으면 아무것도 하지 않는다. 실패하면 EngineException. */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        load();
        JoltPhysicsObject.startCleaner(); // 더 이상 쓰지 않는 Java 객체의 네이티브 메모리를 회수
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        if (!Jolt.newFactory()) {
            throw new EngineException("Jolt.newFactory() 실패");
        }
        Jolt.registerTypes();
        initialized = true;
        Log.info("Jolt Physics 초기화: " + Jolt.versionString() + " (" + Jolt.buildType() + ")");
    }

    private static void load() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows")) {
            // 다른 OS 는 아직 확인 안 함: 라이브러리 경로에 있으면 쓴다
            System.loadLibrary("joltjni");
            return;
        }
        Path target = EXTRACT_DIR.resolve("joltjni.dll").toAbsolutePath();
        try (InputStream in = JoltNative.class.getClassLoader().getResourceAsStream(WINDOWS_RESOURCE)) {
            if (in == null) {
                throw new EngineException("jolt-jni 네이티브가 클래스패스에 없습니다 (" + WINDOWS_RESOURCE
                        + "). build.gradle.kts 의 ReleaseSp 의존성 확인");
            }
            byte[] bytes = in.readAllBytes();
            // 같은 파일이 이미 있으면 다시 쓰지 않는다 (다른 실행이 로드 중일 수 있음)
            if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
                Files.createDirectories(target.getParent());
                Path temp = target.resolveSibling("joltjni.dll.tmp");
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new EngineException("joltjni.dll 을 꺼내지 못했습니다: " + target, e);
        }
        System.load(target.toString());
    }
}
