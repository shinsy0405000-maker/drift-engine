package driftengine.upscale;

import driftengine.core.EngineException;
import driftengine.math.Vec3;
import driftengine.vulkan.GpuImage;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * drift_sl_bridge.dll 의 Java 바인딩. JDK 표준 FFM(java.lang.foreign)만 사용한다.
 * <p>
 * C 쪽 정의: native/streamline-bridge/include/drift_sl_bridge.h
 * 함수 하나당 메서드 하나로 대응하고, 의미 해석(지원 여부 판정 등)은 DlssSession 이 한다.
 */
@SuppressWarnings("restricted") // 네이티브 호출은 의도된 것 (--enable-native-access 로 허용)
public final class StreamlineBridge implements AutoCloseable {

    /** drift_sl_bridge.h 의 DSL_ABI_VERSION 과 같아야 한다. */
    static final int EXPECTED_ABI = 2;

    /** drift_sl_bridge.h 의 DslVkRequirements 와 같은 배치 (정수 8개 + 포인터 4개 = 64바이트). */
    static final StructLayout REQUIREMENTS_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("numInstanceExtensions"),
            JAVA_INT.withName("numDeviceExtensions"),
            JAVA_INT.withName("numFeatures12"),
            JAVA_INT.withName("numFeatures13"),
            JAVA_INT.withName("numGraphicsQueues"),
            JAVA_INT.withName("numComputeQueues"),
            JAVA_INT.withName("numOpticalFlowQueues"),
            JAVA_INT.withName("reserved"),
            ADDRESS.withName("instanceExtensions"),
            ADDRESS.withName("deviceExtensions"),
            ADDRESS.withName("features12"),
            ADDRESS.withName("features13"));

    /** drift_sl_bridge.h 의 DslImage 와 같은 배치 (64비트 3개 + 32비트 6개 = 48바이트). */
    public static final StructLayout IMAGE_LAYOUT = MemoryLayout.structLayout(
            JAVA_LONG.withName("image"),
            JAVA_LONG.withName("view"),
            JAVA_LONG.withName("memory"),
            JAVA_INT.withName("layout"),
            JAVA_INT.withName("format"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("usage"),
            JAVA_INT.withName("aspectMask"));

    /** drift_sl_bridge.h 의 DslConstants 와 같은 배치 (352바이트). */
    public static final StructLayout CONSTANTS_LAYOUT = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("cameraViewToClip"),
            MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("clipToCameraView"),
            MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("clipToPrevClip"),
            MemoryLayout.sequenceLayout(16, JAVA_FLOAT).withName("prevClipToClip"),
            MemoryLayout.sequenceLayout(2, JAVA_FLOAT).withName("jitterOffset"),
            MemoryLayout.sequenceLayout(2, JAVA_FLOAT).withName("mvecScale"),
            MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("cameraPos"),
            MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("cameraUp"),
            MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("cameraRight"),
            MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("cameraFwd"),
            JAVA_FLOAT.withName("cameraNear"),
            JAVA_FLOAT.withName("cameraFar"),
            JAVA_FLOAT.withName("cameraFov"),
            JAVA_FLOAT.withName("cameraAspectRatio"),
            JAVA_INT.withName("depthInverted"),
            JAVA_INT.withName("cameraMotionIncluded"),
            JAVA_INT.withName("motionVectorsJittered"),
            JAVA_INT.withName("reset"));

    /** DLSS 상수 중 카메라 외의 설정 */
    public record ConstantFlags(float mvecScaleX, float mvecScaleY, boolean depthInverted,
                                boolean cameraMotionIncluded, boolean motionVectorsJittered, boolean reset) {
    }

    /** DLSS 가 요구하는 Vulkan 설정 (slGetFeatureRequirements 결과). */
    public record VkRequirements(List<String> instanceExtensions, List<String> deviceExtensions,
                                 List<String> features12, List<String> features13,
                                 int graphicsQueues, int computeQueues, int opticalFlowQueues) {
    }

    private final Arena libraryArena;
    private final MethodHandle abiVersion;
    private final MethodHandle lastError;
    private final MethodHandle verifySignature;
    private final MethodHandle loadInterposer;
    private final MethodHandle init;
    private final MethodHandle isDlssSupported;
    private final MethodHandle getDlssRequirements;
    private final MethodHandle shutdown;
    private final MethodHandle dlssOptimalSettings;
    private final MethodHandle dlssSetOptions;
    private final MethodHandle beginFrame;
    private final MethodHandle setConstants;
    private final MethodHandle dlssEvaluate;
    private final MethodHandle dlssFree;

    private StreamlineBridge(Arena libraryArena, SymbolLookup lookup) {
        this.libraryArena = libraryArena;
        Linker linker = Linker.nativeLinker();
        abiVersion = bind(linker, lookup, "dsl_abi_version", FunctionDescriptor.of(JAVA_INT));
        lastError = bind(linker, lookup, "dsl_last_error", FunctionDescriptor.of(ADDRESS));
        verifySignature = bind(linker, lookup, "dsl_verify_signature", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        loadInterposer = bind(linker, lookup, "dsl_load_interposer", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        init = bind(linker, lookup, "dsl_init",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        isDlssSupported = bind(linker, lookup, "dsl_is_dlss_supported", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        getDlssRequirements = bind(linker, lookup, "dsl_get_dlss_requirements", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        shutdown = bind(linker, lookup, "dsl_shutdown", FunctionDescriptor.of(JAVA_INT));
        dlssOptimalSettings = bind(linker, lookup, "dsl_dlss_optimal_settings",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        dlssSetOptions = bind(linker, lookup, "dsl_dlss_set_options",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        beginFrame = bind(linker, lookup, "dsl_begin_frame", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        setConstants = bind(linker, lookup, "dsl_set_constants", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        dlssEvaluate = bind(linker, lookup, "dsl_dlss_evaluate",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        dlssFree = bind(linker, lookup, "dsl_dlss_free", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    }

    /** 브리지 DLL 을 로드한다. 함수가 하나라도 없거나 ABI 버전이 다르면 예외. */
    public static StreamlineBridge load(Path dll) {
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(dll.toAbsolutePath(), arena);
            StreamlineBridge bridge = new StreamlineBridge(arena, lookup);
            int abi = bridge.abiVersion();
            if (abi != EXPECTED_ABI) {
                throw new EngineException("브리지 ABI 버전 불일치: DLL=" + abi + ", Java=" + EXPECTED_ABI
                        + " (브리지를 다시 빌드하세요)");
            }
            return bridge;
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new EngineException("브리지 함수가 없습니다: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    // ------------------------------------------------------------------ C 함수 대응

    public int abiVersion() {
        try {
            return (int) abiVersion.invokeExact();
        } catch (Throwable t) {
            throw wrap("dsl_abi_version", t);
        }
    }

    public String lastError() {
        try {
            MemorySegment text = (MemorySegment) lastError.invokeExact();
            return text.equals(MemorySegment.NULL) ? "" : text.reinterpret(Integer.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw wrap("dsl_last_error", t);
        }
    }

    /** @return 1 신뢰됨, 0 신뢰 안 됨, 음수 오류 */
    public int verifySignature(Path file) {
        try (Arena call = Arena.ofConfined()) {
            return (int) verifySignature.invokeExact(wide(call, file.toAbsolutePath().toString()));
        } catch (Throwable t) {
            throw wrap("dsl_verify_signature", t);
        }
    }

    public int loadInterposer(Path interposer, boolean requireSignature) {
        try (Arena call = Arena.ofConfined()) {
            return (int) loadInterposer.invokeExact(wide(call, interposer.toAbsolutePath().toString()),
                    requireSignature ? 1 : 0);
        } catch (Throwable t) {
            throw wrap("dsl_load_interposer", t);
        }
    }

    public int init(Path pluginDir, Path logDir, boolean verbose, String engineVersion, String projectId) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment plugins = wide(call, pluginDir.toAbsolutePath().toString());
            MemorySegment logs = logDir == null ? MemorySegment.NULL : wide(call, logDir.toAbsolutePath().toString());
            MemorySegment version = call.allocateFrom(engineVersion);
            MemorySegment project = projectId == null ? MemorySegment.NULL : call.allocateFrom(projectId);
            return (int) init.invokeExact(plugins, logs, verbose ? 1 : 0, version, project);
        } catch (Throwable t) {
            throw wrap("dsl_init", t);
        }
    }

    /** @param vkPhysicalDevice VkPhysicalDevice 핸들 주소 (LWJGL 의 VkPhysicalDevice.address()) */
    public int isDlssSupported(long vkPhysicalDevice) {
        try {
            return (int) isDlssSupported.invokeExact(MemorySegment.ofAddress(vkPhysicalDevice));
        } catch (Throwable t) {
            throw wrap("dsl_is_dlss_supported", t);
        }
    }

    /** @return 결과 코드와 요구 사항. 코드가 0 이 아니면 requirements 는 null. */
    public RequirementsResult dlssRequirements() {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment out = call.allocate(REQUIREMENTS_LAYOUT);
            int code = (int) getDlssRequirements.invokeExact(out);
            if (code != 0) {
                return new RequirementsResult(code, null);
            }
            VkRequirements reqs = new VkRequirements(
                    strings(out, "instanceExtensions", "numInstanceExtensions"),
                    strings(out, "deviceExtensions", "numDeviceExtensions"),
                    strings(out, "features12", "numFeatures12"),
                    strings(out, "features13", "numFeatures13"),
                    intField(out, "numGraphicsQueues"),
                    intField(out, "numComputeQueues"),
                    intField(out, "numOpticalFlowQueues"));
            return new RequirementsResult(0, reqs);
        } catch (Throwable t) {
            throw wrap("dsl_get_dlss_requirements", t);
        }
    }

    public record RequirementsResult(int code, VkRequirements requirements) {
    }

    public int shutdown() {
        try {
            return (int) shutdown.invokeExact();
        } catch (Throwable t) {
            throw wrap("dsl_shutdown", t);
        }
    }

    // ------------------------------------------------------------------ DLSS 실행 (ABI 2)

    /** @return {코드, 렌더 너비, 렌더 높이} */
    public int[] dlssOptimalSettings(int mode, int outputWidth, int outputHeight) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment w = call.allocate(JAVA_INT);
            MemorySegment h = call.allocate(JAVA_INT);
            int code = (int) dlssOptimalSettings.invokeExact(mode, outputWidth, outputHeight, w, h);
            return new int[]{code, w.get(JAVA_INT, 0), h.get(JAVA_INT, 0)};
        } catch (Throwable t) {
            throw wrap("dsl_dlss_optimal_settings", t);
        }
    }

    public int dlssSetOptions(int viewport, int mode, int outputWidth, int outputHeight,
                              boolean colorHdr, boolean autoExposure) {
        try {
            return (int) dlssSetOptions.invokeExact(viewport, mode, outputWidth, outputHeight,
                    colorHdr ? 1 : 0, autoExposure ? 1 : 0);
        } catch (Throwable t) {
            throw wrap("dsl_dlss_set_options", t);
        }
    }

    public int beginFrame(int frameIndex) {
        try {
            return (int) beginFrame.invokeExact(frameIndex);
        } catch (Throwable t) {
            throw wrap("dsl_begin_frame", t);
        }
    }

    public int setConstants(int viewport, UpscaleCamera cam, ConstantFlags flags) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment c = call.allocate(CONSTANTS_LAYOUT);
            writeConstants(c, cam, flags);
            return (int) setConstants.invokeExact(viewport, c);
        } catch (Throwable t) {
            throw wrap("dsl_set_constants", t);
        }
    }

    /** 이미지마다 "지금 실제 레이아웃"을 함께 넘긴다. */
    public int dlssEvaluate(int viewport, long vkCommandBuffer,
                            GpuImage colorIn, int colorInLayout, GpuImage colorOut, int colorOutLayout,
                            GpuImage depth, int depthLayout, GpuImage motion, int motionLayout) {
        try (Arena call = Arena.ofConfined()) {
            return (int) dlssEvaluate.invokeExact(viewport, MemorySegment.ofAddress(vkCommandBuffer),
                    image(call, colorIn, colorInLayout), image(call, colorOut, colorOutLayout),
                    image(call, depth, depthLayout), image(call, motion, motionLayout));
        } catch (Throwable t) {
            throw wrap("dsl_dlss_evaluate", t);
        }
    }

    public int dlssFree(int viewport) {
        try {
            return (int) dlssFree.invokeExact(viewport);
        } catch (Throwable t) {
            throw wrap("dsl_dlss_free", t);
        }
    }

    /** DslConstants 채우기 (GPU·DLL 없이 테스트 가능하도록 분리) */
    public static void writeConstants(MemorySegment c, UpscaleCamera cam, ConstantFlags flags) {
        putFloats(c, "cameraViewToClip", cam.viewToClip());
        putFloats(c, "clipToCameraView", cam.clipToView());
        putFloats(c, "clipToPrevClip", cam.clipToPrevClip());
        putFloats(c, "prevClipToClip", cam.prevClipToClip());
        putFloats(c, "jitterOffset", new float[]{cam.jitterX(), cam.jitterY()});
        putFloats(c, "mvecScale", new float[]{flags.mvecScaleX(), flags.mvecScaleY()});
        putVec(c, "cameraPos", cam.position());
        putVec(c, "cameraUp", cam.up());
        putVec(c, "cameraRight", cam.right());
        putVec(c, "cameraFwd", cam.forward());
        c.set(JAVA_FLOAT, offset(CONSTANTS_LAYOUT, "cameraNear"), cam.near());
        c.set(JAVA_FLOAT, offset(CONSTANTS_LAYOUT, "cameraFar"), cam.far());
        c.set(JAVA_FLOAT, offset(CONSTANTS_LAYOUT, "cameraFov"), cam.fovY());
        c.set(JAVA_FLOAT, offset(CONSTANTS_LAYOUT, "cameraAspectRatio"), cam.aspect());
        c.set(JAVA_INT, offset(CONSTANTS_LAYOUT, "depthInverted"), flags.depthInverted() ? 1 : 0);
        c.set(JAVA_INT, offset(CONSTANTS_LAYOUT, "cameraMotionIncluded"), flags.cameraMotionIncluded() ? 1 : 0);
        c.set(JAVA_INT, offset(CONSTANTS_LAYOUT, "motionVectorsJittered"), flags.motionVectorsJittered() ? 1 : 0);
        c.set(JAVA_INT, offset(CONSTANTS_LAYOUT, "reset"), flags.reset() ? 1 : 0);
    }

    /** DslImage 채우기 */
    static MemorySegment image(Arena arena, GpuImage img, int layout) {
        MemorySegment s = arena.allocate(IMAGE_LAYOUT);
        s.set(JAVA_LONG, offset(IMAGE_LAYOUT, "image"), img.image());
        s.set(JAVA_LONG, offset(IMAGE_LAYOUT, "view"), img.view());
        s.set(JAVA_LONG, offset(IMAGE_LAYOUT, "memory"), img.memory());
        s.set(JAVA_INT, offset(IMAGE_LAYOUT, "layout"), layout);
        s.set(JAVA_INT, offset(IMAGE_LAYOUT, "format"), img.format());
        s.set(JAVA_INT, offset(IMAGE_LAYOUT, "width"), img.width());
        s.set(JAVA_INT, offset(IMAGE_LAYOUT, "height"), img.height());
        s.set(JAVA_INT, offset(IMAGE_LAYOUT, "usage"), img.usage());
        s.set(JAVA_INT, offset(IMAGE_LAYOUT, "aspectMask"), img.aspect());
        return s;
    }

    public static long offset(StructLayout layout, String name) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(name));
    }

    private static void putFloats(MemorySegment c, String name, float[] values) {
        long base = offset(CONSTANTS_LAYOUT, name);
        long count = CONSTANTS_LAYOUT.select(MemoryLayout.PathElement.groupElement(name)).byteSize() / JAVA_FLOAT.byteSize();
        if (values.length != count) {
            throw new IllegalArgumentException(name + " 은 float " + count + "개여야 함: " + values.length);
        }
        for (int i = 0; i < values.length; i++) {
            c.set(JAVA_FLOAT, base + i * JAVA_FLOAT.byteSize(), values[i]);
        }
    }

    private static void putVec(MemorySegment c, String name, Vec3 v) {
        putFloats(c, name, new float[]{v.x(), v.y(), v.z()});
    }

    /** 브리지 DLL 핸들을 놓는다. Streamline 종료(shutdown)는 따로 호출해야 한다. */
    @Override
    public void close() {
        libraryArena.close();
    }

    // ------------------------------------------------------------------ 도우미

    /** Windows wchar_t 문자열 (UTF-16LE, 널 종료) */
    private static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
    }

    private static int intField(MemorySegment struct, String name) {
        VarHandle handle = REQUIREMENTS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(name));
        return (int) handle.get(struct, 0L);
    }

    private static List<String> strings(MemorySegment struct, String arrayName, String countName) {
        int count = intField(struct, countName);
        VarHandle handle = REQUIREMENTS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(arrayName));
        MemorySegment array = (MemorySegment) handle.get(struct, 0L);
        List<String> result = new ArrayList<>(count);
        if (count <= 0 || array.equals(MemorySegment.NULL)) {
            return result;
        }
        MemorySegment pointers = array.reinterpret(count * ADDRESS.byteSize());
        for (int i = 0; i < count; i++) {
            MemorySegment text = pointers.getAtIndex(ADDRESS, i);
            result.add(text.equals(MemorySegment.NULL) ? "" : text.reinterpret(Integer.MAX_VALUE).getString(0));
        }
        return result;
    }

    private static EngineException wrap(String function, Throwable t) {
        if (t instanceof EngineException e) {
            return e;
        }
        return new EngineException("브리지 호출 실패: " + function, t);
    }
}
