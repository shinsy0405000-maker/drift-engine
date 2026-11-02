package driftengine.selftest;

import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.Camera;
import driftengine.upscale.DlssMode;
import driftengine.upscale.DlssUpscaler;
import driftengine.upscale.SlResult;
import driftengine.upscale.StreamlineBridge;
import driftengine.upscale.UpscaleCamera;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 4e 단계(DLSS 실행) 중 GPU·DLL 없이 확인할 수 있는 부분 */
final class DlssTests {

    private static final float EPS = 1e-4f;

    private DlssTests() {
    }

    static void run() {
        test("C 구조체와 같은 배치: DslImage 48바이트, DslConstants 352바이트", DlssTests::layouts);
        test("상수 기록: 지터·플래그가 C 와 같은 위치에", DlssTests::writeConstants);
        test("행렬 규약: 열 우선 배열 = Streamline(행 벡터) 행렬", DlssTests::matrixConvention);
        test("정지한 카메라: clipToPrevClip = 단위 행렬", DlssTests::staticCamera);
        test("움직인 카메라: clipToPrevClip 이 현재 → 이전 화면 위치로 보냄", DlssTests::movingCamera);
        test("지터 단계 수: 8 × (출력/렌더)²", DlssTests::jitterPhases);
        test("DLSS 모드 순환: 품질 → 균형 → 성능 → 끄기", DlssTests::modeCycle);
        test("sl::Result 이름 (SDK 2.14.16, 컴파일러로 확인한 값)", DlssTests::resultNames);
    }

    private static void layouts() {
        check(StreamlineBridge.IMAGE_LAYOUT.byteSize() == 48, "DslImage " + StreamlineBridge.IMAGE_LAYOUT.byteSize());
        check(StreamlineBridge.CONSTANTS_LAYOUT.byteSize() == 352,
                "DslConstants " + StreamlineBridge.CONSTANTS_LAYOUT.byteSize());
        // drift_sl_bridge.cpp 의 offsetof static_assert 와 같은 숫자
        check(StreamlineBridge.offset(StreamlineBridge.CONSTANTS_LAYOUT, "jitterOffset") == 256, "jitterOffset");
        check(StreamlineBridge.offset(StreamlineBridge.CONSTANTS_LAYOUT, "cameraPos") == 272, "cameraPos");
        check(StreamlineBridge.offset(StreamlineBridge.CONSTANTS_LAYOUT, "cameraNear") == 320, "cameraNear");
        check(StreamlineBridge.offset(StreamlineBridge.CONSTANTS_LAYOUT, "depthInverted") == 336, "depthInverted");
        check(StreamlineBridge.offset(StreamlineBridge.CONSTANTS_LAYOUT, "reset") == 348, "reset");
        check(StreamlineBridge.offset(StreamlineBridge.IMAGE_LAYOUT, "layout") == 24, "layout");
        check(StreamlineBridge.offset(StreamlineBridge.IMAGE_LAYOUT, "aspectMask") == 44, "aspectMask");
    }

    private static Camera camera(Vec3 pos) {
        Camera cam = new Camera().position(pos);
        cam.beginFrame();
        cam.update(1280, 720, 0, 0);
        return cam;
    }

    private static void writeConstants() {
        Camera cam = new Camera().position(new Vec3(1, 2, 3));
        cam.beginFrame();
        cam.update(1280, 720, 0.25f, -0.125f);
        UpscaleCamera uc = UpscaleCamera.from(cam);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = arena.allocate(StreamlineBridge.CONSTANTS_LAYOUT);
            StreamlineBridge.writeConstants(c, uc, new StreamlineBridge.ConstantFlags(1f, 1f, false, true, false, true));
            check(c.get(JAVA_FLOAT, 256) == 0.25f && c.get(JAVA_FLOAT, 260) == -0.125f, "지터");
            check(c.get(JAVA_FLOAT, 264) == 1f && c.get(JAVA_FLOAT, 268) == 1f, "mvecScale");
            check(c.get(JAVA_FLOAT, 272) == 1f && c.get(JAVA_FLOAT, 276) == 2f && c.get(JAVA_FLOAT, 280) == 3f, "위치");
            check(c.get(JAVA_FLOAT, 0) == uc.viewToClip()[0], "투영 첫 원소");
            check(c.get(JAVA_INT, 336) == 0 && c.get(JAVA_INT, 340) == 1 && c.get(JAVA_INT, 344) == 0
                    && c.get(JAVA_INT, 348) == 1, "플래그");
        }
    }

    /** Streamline 방식(행 벡터 × 행 우선 행렬)으로 곱한 결과가 이 엔진의 M × v 와 같아야 한다. */
    private static void matrixConvention() {
        Mat4 m = new Mat4().perspectiveVulkan(1.0f, 1.6f, 0.1f, 100f);
        m.mul(m, new Mat4().translation(0.3f, -2f, 5f));
        float[] s = m.toColumnMajorArray(); // Streamline 의 row[r] = s[r*4 .. r*4+3]
        float[] v = {0.5f, -1.5f, -4f, 1f};
        float[] ours = m.transform(v[0], v[1], v[2], v[3]);
        for (int col = 0; col < 4; col++) {
            float sum = 0;
            for (int row = 0; row < 4; row++) {
                sum += v[row] * s[row * 4 + col];
            }
            check(Math.abs(sum - ours[col]) < EPS, "성분 " + col + ": " + sum + " vs " + ours[col]);
        }
    }

    private static void staticCamera() {
        Camera cam = camera(new Vec3(0, 2, 10));
        cam.beginFrame();
        cam.update(1280, 720, 0, 0);
        UpscaleCamera uc = UpscaleCamera.from(cam);
        float[] id = new Mat4().toColumnMajorArray();
        for (int i = 0; i < 16; i++) {
            check(Math.abs(uc.clipToPrevClip()[i] - id[i]) < EPS, "원소 " + i + ": " + uc.clipToPrevClip()[i]);
        }
    }

    private static void movingCamera() {
        Camera cam = camera(new Vec3(0, 2, 10));
        Mat4 prevVp = new Mat4(cam.viewProjection());
        cam.beginFrame();
        cam.position(new Vec3(0.7f, 2.2f, 9.5f));
        cam.update(1280, 720, 0, 0);
        UpscaleCamera uc = UpscaleCamera.from(cam);

        Vec3 world = new Vec3(1, 1, 0);
        float[] curr = cam.viewProjection().transform(world.x(), world.y(), world.z(), 1);
        float[] prevExpected = prevVp.transform(world.x(), world.y(), world.z(), 1);
        // clipToPrevClip 를 엔진 규약(M × v)으로 적용: 열 우선 배열 a 에서 M(r,c) = a[c*4+r]
        float[] a = uc.clipToPrevClip();
        float[] prev = new float[4];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                prev[r] += a[c * 4 + r] * curr[c];
            }
        }
        for (int i = 0; i < 2; i++) {
            float got = prev[i] / prev[3];
            float want = prevExpected[i] / prevExpected[3];
            check(Math.abs(got - want) < 1e-3f, "NDC " + i + ": " + got + " vs " + want);
        }
    }

    private static void jitterPhases() {
        check(DlssUpscaler.jitterPhases(1280, 1280) == 8, "DLAA 8");
        check(DlssUpscaler.jitterPhases(853, 1280) == 18, "품질(1.5배) 18: " + DlssUpscaler.jitterPhases(853, 1280));
        check(DlssUpscaler.jitterPhases(640, 1280) == 32, "성능(2배) 32");
        check(DlssUpscaler.jitterPhases(427, 1280) == 72, "울트라 성능(3배) 72: " + DlssUpscaler.jitterPhases(427, 1280));
    }

    private static void modeCycle() {
        check(DlssMode.QUALITY.next() == DlssMode.BALANCED, "품질 → 균형");
        check(DlssMode.BALANCED.next() == DlssMode.PERFORMANCE, "균형 → 성능");
        check(DlssMode.PERFORMANCE.next() == null, "성능 → 끄기(Native)");
        check(DlssMode.QUALITY.slValue() == 3 && DlssMode.PERFORMANCE.slValue() == 1 && DlssMode.DLAA.slValue() == 6,
                "sl::DLSSMode 값");
    }

    private static void resultNames() {
        check(SlResult.describe(0).contains("eOk"), "0");
        check(SlResult.describe(31).contains("eErrorFeatureMissing"), "31");
        check(SlResult.describe(32).contains("eErrorFeatureNotSupported"), "32");
        check(SlResult.describe(34).contains("eErrorFeatureFailedToLoad"), "34");
        check(SlResult.describe(-5).contains("DSL_ERR_SIGNATURE"), "-5");
    }
}
