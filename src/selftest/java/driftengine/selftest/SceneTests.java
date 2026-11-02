package driftengine.selftest;

import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.Camera;
import driftengine.scene.DemoScene;
import driftengine.scene.SceneObject;
import driftengine.scene.mesh.MeshData;
import driftengine.scene.mesh.Primitives;
import driftengine.vulkan.DepthBuffer;
import driftengine.vulkan.VulkanDevice;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.expectThrows;
import static driftengine.selftest.TestRunner.test;
import static org.lwjgl.vulkan.VK10.*;

/** 3단계(3D 장면) 중 GPU 없이 확인할 수 있는 부분 */
final class SceneTests {

    private static final float EPS = 1e-3f;

    private SceneTests() {
    }

    static void run() {
        test("데모 장면: 모든 물체가 바닥(y=0) 위에 놓임", SceneTests::objectsOnGround);
        test("데모 장면: 가운데 정육면체가 시간에 따라 회전", SceneTests::spinnerRotates);
        test("물체 배치: 크기 → 회전 → 이동 순서", SceneTests::placeOrder);
        test("뒷면 제거 규칙: 카메라를 향한 면만 앞면(반시계)", SceneTests::frontFaceRule);
        test("메모리 타입 선택", SceneTests::memoryType);
        test("깊이 포맷 선택: 선호 순서와 대체", SceneTests::depthFormat);
    }

    private static void objectsOnGround() {
        DemoScene scene = new DemoScene();
        check(scene.objects().size() == 1 + 1 + DemoScene.RING_COUNT + 1, "물체 수 " + scene.objects().size());
        for (SceneObject o : scene.objects()) {
            check(Math.abs(o.worldMinY()) < EPS, o.name() + " 바닥 높이 " + o.worldMinY());
        }
    }

    private static void spinnerRotates() {
        DemoScene scene = new DemoScene();
        scene.update(0);
        Mat4 start = new Mat4(scene.spinner().model());
        scene.update(1.0);
        check(!scene.spinner().model().approxEquals(start, EPS), "1초 후 달라짐");
        scene.update(2 * Math.PI / DemoScene.SPIN_SPEED);
        check(scene.spinner().model().approxEquals(start, 1e-2f), "한 바퀴 후 원래대로");
        check(Math.abs(scene.spinner().worldMinY()) < EPS, "회전해도 바닥 위");
    }

    private static void placeOrder() {
        SceneObject o = new SceneObject("t", Primitives.cube(1, Vec3.UNIT_X))
                .place(new Vec3(10, 0, 0), (float) Math.toRadians(90), new Vec3(2, 1, 1));
        // 로컬 (0.5,0,0) → 크기 x2 → (1,0,0) → Y축 90도 → (0,0,-1) → 이동 → (10,0,-1)
        Vec3 p = o.model().transformPoint(new Vec3(0.5f, 0, 0));
        check(p.approxEquals(new Vec3(10, 0, -1), EPS), "결과 " + p);
    }

    /**
     * Vulkan 사양의 앞면 판정: 프레임버퍼 좌표에서 a = -½ Σ (x_i·y_(i+1) − x_(i+1)·y_i),
     * frontFace = COUNTER_CLOCKWISE 이면 a > 0 인 삼각형이 앞면.
     * 정육면체를 바라볼 때 카메라를 향한 면은 앞면(a>0), 반대쪽 면은 뒷면(a<0)이어야 한다.
     */
    private static void frontFaceRule() {
        int w = 1280;
        int h = 720;
        Camera cam = new Camera().position(new Vec3(3, 4, 6));
        // 원점을 바라보도록 요/피치 설정
        Vec3 dir = Vec3.ZERO.sub(cam.position()).normalize();
        cam.rotation((float) Math.atan2(-dir.x(), -dir.z()), (float) Math.asin(dir.y()));
        cam.update(w, h, 0, 0);

        MeshData cube = Primitives.cube(1, Vec3.UNIT_X);
        int[] idx = cube.indices();
        int front = 0;
        int back = 0;
        for (int t = 0; t < idx.length; t += 3) {
            float[][] fb = new float[3][];
            for (int k = 0; k < 3; k++) {
                Vec3 ndc = cam.viewProjection().transformPoint(cube.position(idx[t + k]));
                fb[k] = new float[]{(ndc.x() * 0.5f + 0.5f) * w, (ndc.y() * 0.5f + 0.5f) * h};
            }
            float sum = 0;
            for (int k = 0; k < 3; k++) {
                float[] a = fb[k];
                float[] b = fb[(k + 1) % 3];
                sum += a[0] * b[1] - b[0] * a[1];
            }
            float area = -0.5f * sum;
            Vec3 normal = cube.normal(idx[t]);
            boolean facesCamera = normal.dot(cam.position().sub(cube.position(idx[t]))) > 0;
            if (facesCamera) {
                check(area > 0, "카메라를 향한 삼각형 " + t / 3 + " 이 뒷면으로 판정됨 (a=" + area + ")");
                front++;
            } else {
                check(area < 0, "반대쪽 삼각형 " + t / 3 + " 이 앞면으로 판정됨 (a=" + area + ")");
                back++;
            }
        }
        check(front == 6 && back == 6, "앞면 " + front + ", 뒷면 " + back + " (각각 3면 = 삼각형 6개 예상)");
    }

    private static void memoryType() {
        int hostVisible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        int[] types = {VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, hostVisible, hostVisible | VK_MEMORY_PROPERTY_HOST_CACHED_BIT};
        check(VulkanDevice.selectMemoryType(0b111, hostVisible, types) == 1, "첫 번째로 맞는 타입");
        check(VulkanDevice.selectMemoryType(0b101, hostVisible, types) == 2, "허용되지 않은 타입은 건너뜀");
        check(VulkanDevice.selectMemoryType(0b001, hostVisible, types) == -1, "맞는 타입 없음");
        check(VulkanDevice.selectMemoryType(0b111, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, types) == 0, "GPU 전용");
    }

    private static void depthFormat() {
        check(DepthBuffer.chooseFormat(f -> true) == VK_FORMAT_D32_SFLOAT, "가장 선호: D32");
        check(DepthBuffer.chooseFormat(f -> f == VK_FORMAT_D16_UNORM) == VK_FORMAT_D16_UNORM, "대체: D16");
        expectThrows(RuntimeException.class, () -> DepthBuffer.chooseFormat(f -> false), "지원 포맷 없음");
    }
}
