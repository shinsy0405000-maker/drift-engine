package driftengine.selftest;

import driftengine.input.ActionEdges;
import driftengine.input.InputAction;
import driftengine.input.InputMap;
import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.render.FrameUniforms;
import driftengine.scene.Camera;
import driftengine.scene.DemoScene;
import driftengine.scene.MotionVectors;
import driftengine.scene.SceneObject;
import driftengine.scene.mesh.Primitives;

import java.nio.FloatBuffer;
import java.util.Set;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;

/** 4d 단계(모션 벡터, 지터) 중 GPU 없이 확인할 수 있는 부분 */
final class MotionTests {

    private static final float EPS = 1e-5f;

    private MotionTests() {
    }

    static void run() {
        test("정지한 카메라 + 정지한 물체 → 모션 벡터 0", MotionTests::staticScene);
        test("카메라가 오른쪽으로 이동 → 물체는 화면에서 왼쪽으로 → 벡터 x > 0", MotionTests::cameraMovesRight);
        test("지터는 모션 벡터에 영향 없음", MotionTests::jitterIgnored);
        test("움직이는 물체만 모션 벡터가 생김", MotionTests::movingObject);
        test("물체: 프레임 시작 시 이전 행렬 보관", MotionTests::previousModel);
        test("카메라 유니폼 배치(std140): 행렬 4개 + 옵션 + 카메라·해·그림자", MotionTests::uniformLayout);
        test("토글 키: 누르는 순간 한 번만", MotionTests::edges);
    }

    private static Camera camera(Vec3 position) {
        Camera cam = new Camera().position(position);
        cam.beginFrame();
        cam.update(1280, 720, 0, 0);
        return cam;
    }

    private static void staticScene() {
        Camera cam = camera(new Vec3(0, 2, 10));
        cam.beginFrame();
        cam.update(1280, 720, 0, 0);
        Mat4 model = new Mat4().translation(1, 0, 0);
        float[] mv = MotionVectors.of(Vec3.ZERO, model, model, cam.viewProjection(), cam.previousViewProjection());
        check(Math.abs(mv[0]) < EPS && Math.abs(mv[1]) < EPS, "벡터 " + mv[0] + ", " + mv[1]);
    }

    private static void cameraMovesRight() {
        Camera cam = camera(new Vec3(0, 2, 10));
        cam.beginFrame();
        cam.position(new Vec3(0.5f, 2, 10));
        cam.update(1280, 720, 0, 0);
        Mat4 model = new Mat4();
        float[] mv = MotionVectors.of(Vec3.ZERO, model, model, cam.viewProjection(), cam.previousViewProjection());
        // 이전 위치(더 오른쪽) - 현재 위치 > 0
        check(mv[0] > 0, "x " + mv[0]);
        check(Math.abs(mv[1]) < 1e-3f, "y 는 거의 0: " + mv[1]);
    }

    private static void jitterIgnored() {
        Camera cam = camera(new Vec3(0, 2, 10));
        cam.beginFrame();
        cam.update(1280, 720, 0.4f, -0.3f);  // 지터만 있고 이동 없음
        Mat4 model = new Mat4();
        float[] mv = MotionVectors.of(new Vec3(1, 1, 0), model, model, cam.viewProjection(), cam.previousViewProjection());
        check(Math.abs(mv[0]) < EPS && Math.abs(mv[1]) < EPS, "벡터 " + mv[0] + ", " + mv[1]);
        check(!cam.jitteredViewProjection().approxEquals(cam.viewProjection(), 1e-6f), "그리기용 행렬에는 지터가 있음");
    }

    private static void movingObject() {
        DemoScene scene = new DemoScene();
        Camera cam = camera(new Vec3(0, 2.5f, 16));
        scene.update(0);
        scene.beginFrame();
        cam.beginFrame();
        scene.update(0.25);  // 가운데 정육면체만 회전
        cam.update(1280, 720, 0, 0);

        SceneObject spinner = scene.spinner();
        float[] mvSpin = MotionVectors.of(new Vec3(0.5f, 0.5f, 0.5f), spinner.model(), spinner.previousModel(),
                cam.viewProjection(), cam.previousViewProjection());
        check(Math.hypot(mvSpin[0], mvSpin[1]) > 1e-4, "도는 정육면체 모서리는 움직임: " + mvSpin[0] + ", " + mvSpin[1]);

        SceneObject pillar = scene.objects().get(scene.objects().size() - 1);
        float[] mvPillar = MotionVectors.of(new Vec3(0.5f, 0.5f, 0.5f), pillar.model(), pillar.previousModel(),
                cam.viewProjection(), cam.previousViewProjection());
        check(Math.abs(mvPillar[0]) < EPS && Math.abs(mvPillar[1]) < EPS, "기둥은 정지: " + mvPillar[0]);
    }

    private static void previousModel() {
        SceneObject o = new SceneObject("t", Primitives.cube(1, Vec3.UNIT_X)).place(Vec3.ZERO, 0, new Vec3(1, 1, 1));
        check(o.previousModel().approxEquals(o.model(), EPS), "처음 배치: 이전 = 현재");
        o.beginFrame();
        o.place(new Vec3(3, 0, 0), 0, new Vec3(1, 1, 1));
        check(!o.previousModel().approxEquals(o.model(), EPS), "이동 후: 이전은 옛 위치");
        check(o.previousModel().transformPoint(Vec3.ZERO).approxEquals(Vec3.ZERO, EPS), "이전 위치 원점");
    }

    private static void uniformLayout() {
        Camera cam = camera(new Vec3(1, 2, 3));
        cam.beginFrame();
        cam.update(800, 600, 0.25f, 0.25f);
        FloatBuffer buf = FloatBuffer.allocate(FrameUniforms.FLOAT_COUNT);
        FrameUniforms.pack(cam, true, buf);
        check(buf.position() == FrameUniforms.FLOAT_COUNT, "float 개수 " + buf.position());
        check(FrameUniforms.SIZE_BYTES == 720, "크기 " + FrameUniforms.SIZE_BYTES);
        // 열 우선: 두 번째 행렬(바이트 64 = float 16)의 (0,0) 원소
        check(buf.get(16) == cam.viewProjection().get(0, 0), "viewProj 위치");
        check(buf.get(32 + 13) == cam.previousViewProjection().get(1, 3), "prevViewProj 위치");
        // 바이트 192 = float 48: 역행렬. (지터 행렬 × 역행렬 = 단위)
        driftengine.math.Mat4 inv = new driftengine.math.Mat4();
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                inv.set(r, c, buf.get(48 + c * 4 + r));
            }
        }
        driftengine.math.Mat4 id = new driftengine.math.Mat4().mul(cam.jitteredViewProjection(), inv);
        check(Math.abs(id.get(0, 0) - 1) < 1e-3f && Math.abs(id.get(1, 3)) < 1e-3f && Math.abs(id.get(3, 3) - 1) < 1e-3f, "역행렬");
        check(buf.get(64) == 1f && buf.get(65) == FrameUniforms.MOTION_VIEW_GAIN, "옵션");
        check(buf.get(68) == 1f && buf.get(69) == 2f && buf.get(70) == 3f, "카메라 위치 (바이트 272)");
        check(Math.abs(buf.get(72) * buf.get(72) + buf.get(73) * buf.get(73) + buf.get(74) * buf.get(74) - 1) < 1e-4f, "해 방향 길이 1");
    }

    private static void edges() {
        ActionEdges edges = new ActionEdges();
        InputMap map = InputMap.defaults();
        edges.update(map, Set.of(GLFW_KEY_M)::contains);
        check(edges.pressed(InputAction.TOGGLE_MOTION_VIEW), "첫 프레임: 눌림");
        edges.update(map, Set.of(GLFW_KEY_M)::contains);
        check(!edges.pressed(InputAction.TOGGLE_MOTION_VIEW), "계속 누르고 있음: 다시 안 됨");
        edges.update(map, Set.<Integer>of()::contains);
        edges.update(map, Set.of(GLFW_KEY_M)::contains);
        check(edges.pressed(InputAction.TOGGLE_MOTION_VIEW), "뗐다가 다시 누름: 눌림");
    }
}
