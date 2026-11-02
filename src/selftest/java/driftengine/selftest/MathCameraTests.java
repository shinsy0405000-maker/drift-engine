package driftengine.selftest;

import driftengine.math.HaltonJitter;
import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.Camera;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 수학(Vec3, Mat4, Halton)과 카메라 테스트 */
final class MathCameraTests {

    private static final float EPS = 1e-4f;

    private MathCameraTests() {
    }

    static void run() {
        test("단위 행렬 곱셈", MathCameraTests::identityMultiply);
        test("행렬 곱 순서 (평행이동 후 회전)", MathCameraTests::multiplyOrder);
        test("역행렬", MathCameraTests::inverse);
        test("특이 행렬은 역행렬 없음", MathCameraTests::singular);
        test("lookAt: 앞에 있는 점은 뷰 공간 -Z", MathCameraTests::lookAtForward);
        test("Vulkan 투영: near→깊이 0, far→깊이 1", MathCameraTests::perspectiveDepth);
        test("Vulkan 투영: 위쪽 점은 NDC y < 0", MathCameraTests::perspectiveYDown);
        test("Vulkan 투영: 오른쪽 점은 NDC x > 0", MathCameraTests::perspectiveXRight);
        test("지터: 정확히 지정한 픽셀만큼 이동", MathCameraTests::jitterShiftsPixels);
        test("행 우선 변환 = 전치", MathCameraTests::rowMajor);
        test("Halton 수열 값", MathCameraTests::haltonValues);
        test("Halton 지터 범위와 반복", MathCameraTests::haltonJitterRange);
        test("카메라 방향 벡터", MathCameraTests::cameraDirections);
        test("카메라: 첫 프레임 이전 행렬 = 현재", MathCameraTests::cameraFirstFrame);
        test("카메라: 이동 후 이전 행렬 유지", MathCameraTests::cameraPrevious);
        test("카메라: 피치 제한", MathCameraTests::cameraPitchClamp);
        test("벡터 외적 (오른손 좌표계)", MathCameraTests::crossProduct);
    }

    // ------------------------------------------------------------------ 테스트

    private static void identityMultiply() {
        Mat4 t = new Mat4().translation(1, 2, 3);
        check(new Mat4().mul(new Mat4(), t).approxEquals(t, EPS), "I×T = T");
        check(new Mat4().mul(t, new Mat4()).approxEquals(t, EPS), "T×I = T");
    }

    private static void multiplyOrder() {
        // M = R × T : 먼저 T(평행이동), 그 다음 R(회전)이 적용된다
        Mat4 m = new Mat4().mul(new Mat4().rotationY((float) Math.toRadians(90)), new Mat4().translation(1, 0, 0));
        Vec3 p = m.transformPoint(Vec3.ZERO);
        // (1,0,0) 을 Y축으로 +90도 → (0,0,-1)
        check(p.approxEquals(new Vec3(0, 0, -1), EPS), "결과 " + p);
    }

    private static void inverse() {
        Mat4 m = new Mat4().mul(new Mat4().translation(3, -2, 5), new Mat4().rotationY(0.7f));
        m.mul(m, new Mat4().scaling(2, 3, 4));
        Mat4 inv = new Mat4();
        check(inv.invert(m), "역행렬 존재");
        check(new Mat4().mul(m, inv).approxEquals(new Mat4(), EPS), "M×M⁻¹ = I");
        Mat4 proj = new Mat4().perspectiveVulkan(1.0f, 16f / 9f, 0.1f, 100f);
        check(inv.invert(proj), "투영 역행렬 존재");
        check(new Mat4().mul(proj, inv).approxEquals(new Mat4(), 1e-3f), "P×P⁻¹ = I");
    }

    private static void singular() {
        Mat4 zero = new Mat4().scaling(1, 0, 1);
        Mat4 out = new Mat4().translation(9, 9, 9);
        check(!out.invert(zero), "false 반환");
        check(out.approxEquals(new Mat4().translation(9, 9, 9), EPS), "실패 시 기존 값 유지");
    }

    private static void lookAtForward() {
        Mat4 view = new Mat4().lookAt(new Vec3(0, 0, 5), Vec3.ZERO, Vec3.UNIT_Y);
        Vec3 origin = view.transformPoint(Vec3.ZERO);
        check(origin.approxEquals(new Vec3(0, 0, -5), EPS), "원점 → " + origin);
        Vec3 right = view.transformPoint(new Vec3(1, 0, 0));
        check(right.x() > 0, "월드 +X 는 화면 오른쪽: " + right);
    }

    private static void perspectiveDepth() {
        Mat4 p = new Mat4().perspectiveVulkan((float) Math.toRadians(60), 1.5f, 0.5f, 200f);
        check(Math.abs(p.transformPoint(new Vec3(0, 0, -0.5f)).z()) < EPS, "near 깊이 0");
        check(Math.abs(p.transformPoint(new Vec3(0, 0, -200f)).z() - 1) < EPS, "far 깊이 1");
        float mid = p.transformPoint(new Vec3(0, 0, -10f)).z();
        check(mid > 0 && mid < 1, "중간 깊이 0~1: " + mid);
    }

    private static void perspectiveYDown() {
        Mat4 p = new Mat4().perspectiveVulkan(1.0f, 1f, 0.1f, 100f);
        check(p.transformPoint(new Vec3(0, 1, -5)).y() < 0, "월드 위쪽 → NDC y 음수 (화면 위)");
    }

    private static void perspectiveXRight() {
        Mat4 p = new Mat4().perspectiveVulkan(1.0f, 1f, 0.1f, 100f);
        check(p.transformPoint(new Vec3(1, 0, -5)).x() > 0, "월드 오른쪽 → NDC x 양수");
    }

    private static void jitterShiftsPixels() {
        int w = 1280;
        int h = 720;
        Camera cam = new Camera().position(new Vec3(1, 2, 10)).rotation(0.3f, -0.1f);
        Vec3 target = new Vec3(0.5f, 1.5f, 0);

        cam.update(w, h, 0, 0);
        float[] base = toPixels(cam.jitteredViewProjection().transformPoint(target), w, h);
        cam.update(w, h, 0.25f, -0.375f);
        float[] moved = toPixels(cam.jitteredViewProjection().transformPoint(target), w, h);

        check(Math.abs(moved[0] - base[0] - 0.25f) < 1e-2f, "x 이동 " + (moved[0] - base[0]));
        check(Math.abs(moved[1] - base[1] + 0.375f) < 1e-2f, "y 이동 " + (moved[1] - base[1]));
        check(cam.viewProjection().approxEquals(
                new Mat4().mul(cam.projection(), cam.view()), EPS), "지터 없는 행렬은 그대로");
    }

    private static void rowMajor() {
        Mat4 m = new Mat4().translation(7, 8, 9);
        float[] r = m.toRowMajorArray();
        // 행 우선에서 평행이동은 각 행의 마지막 원소 (인덱스 3, 7, 11)
        check(r[3] == 7 && r[7] == 8 && r[11] == 9, "평행이동 위치");
        check(r[15] == 1, "마지막 원소");
    }

    private static void haltonValues() {
        check(near(HaltonJitter.halton(1, 2), 0.5f), "H2(1)");
        check(near(HaltonJitter.halton(2, 2), 0.25f), "H2(2)");
        check(near(HaltonJitter.halton(3, 2), 0.75f), "H2(3)");
        check(near(HaltonJitter.halton(1, 3), 1f / 3), "H3(1)");
        check(near(HaltonJitter.halton(2, 3), 2f / 3), "H3(2)");
        check(near(HaltonJitter.halton(3, 3), 1f / 9), "H3(3)");
    }

    private static void haltonJitterRange() {
        HaltonJitter j = new HaltonJitter(8);
        float[] first = j.next();
        for (int i = 1; i < 8; i++) {
            float[] v = j.next();
            check(v[0] >= -0.5f && v[0] < 0.5f && v[1] >= -0.5f && v[1] < 0.5f, "범위");
        }
        float[] again = j.next();
        check(first[0] == again[0] && first[1] == again[1], "8 프레임 후 반복");
    }

    private static void cameraDirections() {
        Camera cam = new Camera();
        check(cam.forward().approxEquals(new Vec3(0, 0, -1), EPS), "기본 앞 = -Z");
        check(cam.right().approxEquals(new Vec3(1, 0, 0), EPS), "기본 오른쪽 = +X");
        cam.rotation((float) Math.toRadians(90), 0);
        check(cam.forward().approxEquals(new Vec3(-1, 0, 0), EPS), "요 +90도 → 왼쪽(-X)을 봄");
        check(Math.abs(cam.forward().dot(cam.right())) < EPS, "앞과 오른쪽은 수직");
    }

    private static void cameraFirstFrame() {
        Camera cam = new Camera();
        cam.beginFrame();
        cam.update(800, 600, 0, 0);
        check(cam.previousViewProjection().approxEquals(cam.viewProjection(), EPS), "이전 = 현재");
    }

    private static void cameraPrevious() {
        Camera cam = new Camera();
        cam.beginFrame();
        cam.update(800, 600, 0.1f, 0.1f);
        Mat4 frame1 = new Mat4(cam.viewProjection());

        cam.beginFrame();
        cam.position(new Vec3(5, 0, 3));
        cam.update(800, 600, -0.2f, 0.3f);
        check(cam.previousViewProjection().approxEquals(frame1, EPS), "이전 = 1프레임 값 (지터 제외)");
        check(!cam.viewProjection().approxEquals(frame1, EPS), "현재는 달라짐");

        cam.resetHistory();
        cam.beginFrame();
        cam.update(800, 600, 0, 0);
        check(cam.previousViewProjection().approxEquals(cam.viewProjection(), EPS), "리셋 후 이전 = 현재");
    }

    private static void cameraPitchClamp() {
        Camera cam = new Camera().rotation(0, (float) Math.toRadians(120));
        check(cam.pitch() <= (float) Math.toRadians(89) + EPS, "89도로 제한: " + Math.toDegrees(cam.pitch()));
        cam.update(100, 100, 0, 0); // 제한 덕분에 예외 없이 계산돼야 함
    }

    private static void crossProduct() {
        check(Vec3.UNIT_X.cross(Vec3.UNIT_Y).approxEquals(Vec3.UNIT_Z, EPS), "X × Y = Z");
    }

    // ------------------------------------------------------------------ 도우미

    private static float[] toPixels(Vec3 ndc, int w, int h) {
        return new float[]{(ndc.x() * 0.5f + 0.5f) * w, (ndc.y() * 0.5f + 0.5f) * h};
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-6f;
    }
}
