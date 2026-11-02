package driftengine.selftest;

import driftengine.math.Quat;
import driftengine.math.Vec3;
import driftengine.scene.Camera;
import driftengine.scene.ChaseCamera;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.Suspension;

import static driftengine.selftest.TestRunner.check;
import static driftengine.selftest.TestRunner.test;

/** 5b 단계(차체, 서스펜션, 추적 카메라) 중 GPU·물리 엔진 없이 확인할 수 있는 부분 */
final class VehicleTests {

    private static final float EPS = 1e-4f;

    private VehicleTests() {
    }

    static void run() {
        test("서스펜션: 눌림 = 늘어난 길이 + 반지름 - 땅까지 거리", VehicleTests::compression);
        test("서스펜션 힘: 스프링 + 댐퍼, 공중이면 0, 당기는 힘 없음", VehicleTests::force);
        test("차 제원: 정지 눌림 = 파일 값(수평), 감쇠비, 정지 높이, 앞뒤 하중", VehicleTests::spec);
        test("1/4 차량 모의(240Hz): 계산한 눌림에 멈추고 크게 출렁이지 않음", VehicleTests::quarterCar);
        test("쿼터니언: 벡터 회전, 보간 양 끝", VehicleTests::quat);
        test("추적 카메라: 차 뒤 위쪽에 서서 차를 바라봄", VehicleTests::chase);
    }

    private static void compression() {
        check(Math.abs(Suspension.compression(0.58f, 0.35f, 0.33f) - 0.10f) < EPS, "10cm 눌림");
        check(Suspension.compression(0.70f, 0.35f, 0.33f) < 0, "땅이 멀면 음수(공중)");
    }

    private static void force() {
        check(Math.abs(Suspension.force(0.1f, 0, 30000, 2000) - 3000) < EPS, "스프링만");
        check(Math.abs(Suspension.force(0.1f, 0.5f, 30000, 2000) - 4000) < EPS, "눌리는 중: 댐퍼가 더함");
        check(Suspension.force(0, 0, 30000, 2000) == 0, "닿기만 하면 0");
        check(Suspension.force(-0.1f, 1f, 30000, 2000) == 0, "공중이면 0");
        check(Suspension.force(0.01f, -5f, 30000, 2000) == 0, "빨리 늘어나도 당기지 않음 (0 에서 자름)");
    }

    private static void spec() {
        CarSpec s = CarSpec.defaults();
        check(Math.abs(s.staticSag(0) - 0.06f) < 0.001f && Math.abs(s.staticSag(2) - 0.06f) < 0.001f,
                "앞뒤 눌림 = 파일의 sag (수평): " + s.staticSag(0) + " / " + s.staticSag(2));
        check(Math.abs(s.dampingRatio() - 0.40f) < 0.001f, "감쇠비 " + s.dampingRatio());
        float loadSum = (s.staticSag(0) * s.springRate(0) + s.staticSag(2) * s.springRate(2)) * 2;
        check(Math.abs(loadSum - s.mass() * CarSpec.GRAVITY) < 1f, "네 바퀴 힘 합 = 무게: " + loadSum);
        float expected = s.wheelRadius() + s.restLength() - s.staticSag(0) - s.mount(0).y();
        check(Math.abs(s.restHeight() - expected) < EPS, "정지 높이 " + s.restHeight());
        check(s.restHeight() - s.halfExtents().y() > 0.12f, "차체 밑면이 땅에서 12cm 이상 떠 있음");
        // 앞 축 하중 비율 = 뒤 축까지 거리 / 축간거리 (무게중심 z = 0)
        float share = s.rearAxleZ() / (s.rearAxleZ() - s.frontAxleZ());
        check(Math.abs(share - s.weightFront()) < 1e-4f, "앞 하중 비율 " + share);
    }

    /** 바퀴 하나가 받치는 질량(m/4)을 같은 힘 계산으로 240Hz 적분해서, 떨어뜨린 뒤 멈추는 높이를 본다 */
    private static void quarterCar() {
        CarSpec s = CarSpec.defaults();
        float m = s.staticLoad(0) / CarSpec.GRAVITY;
        float dt = 1f / 240;
        float rest = s.restLength() + s.wheelRadius();
        float dist = rest + 1.0f; // 땅까지 거리 (1m 위에서 시작)
        float vDown = 0;           // 아래로 움직이는 속도
        int bounces = 0;
        float lastV = 0;
        for (int i = 0; i < 240 * 4; i++) {
            float x = Suspension.compression(dist, s.restLength(), s.wheelRadius());
            float f = Suspension.force(x, vDown, s.springRate(0), s.damperRate(0));
            float a = CarSpec.GRAVITY - f / m; // 아래 방향 가속도
            vDown += a * dt;
            dist -= vDown * dt;
            if (x > 0 && Math.abs(vDown) > 0.02f) {
                if (lastV != 0 && Math.signum(vDown) != Math.signum(lastV)) {
                    bounces++;
                }
                lastV = vDown;
            }
        }
        float sag = rest - dist;
        float expected = m * CarSpec.GRAVITY / s.springRate(0);
        check(Math.abs(sag - expected) < 0.002f, "멈춘 눌림 " + sag + " vs " + expected);
        check(Math.abs(vDown) < 0.01f, "4초 뒤 거의 정지: " + vDown);
        // 감쇠비 0.4: 반 주기마다 속도가 약 1/4 로 → 1m 낙하(착지 4.4m/s)면 2cm/s 아래로 줄 때까지 4번 정도
        check(bounces <= 5, "출렁임 " + bounces + "번 (1m 낙하, 감쇠비 0.4 면 4번 정도 예상)");
    }

    private static void quat() {
        Quat yaw90 = Quat.axisAngle(Vec3.UNIT_Y, (float) (Math.PI / 2));
        Vec3 r = yaw90.rotate(new Vec3(0, 0, -1));
        check(r.approxEquals(new Vec3(-1, 0, 0), 1e-5f), "앞(-Z) 을 왼쪽으로 90도 → -X: " + r);
        check(Quat.nlerp(Quat.IDENTITY, yaw90, 0).rotate(Vec3.UNIT_X).approxEquals(Vec3.UNIT_X, 1e-5f), "t=0");
        check(Quat.nlerp(Quat.IDENTITY, yaw90, 1).rotate(Vec3.UNIT_X).approxEquals(yaw90.rotate(Vec3.UNIT_X), 1e-5f), "t=1");
        Quat negated = new Quat(-yaw90.x(), -yaw90.y(), -yaw90.z(), -yaw90.w()); // 같은 회전, 부호만 반대
        check(Quat.nlerp(yaw90, negated, 0.5f).rotate(Vec3.UNIT_X).approxEquals(yaw90.rotate(Vec3.UNIT_X), 1e-5f),
                "부호가 반대인 같은 회전 사이는 그대로 (먼 길로 돌지 않음)");
    }

    private static void chase() {
        Camera cam = new Camera();
        ChaseCamera chase = new ChaseCamera();
        Vec3 car = new Vec3(10, 0, -20);
        chase.update(cam, car, new Vec3(0, 0, -1), 0.016f);
        Vec3 p = cam.position();
        check(p.z() > car.z() + 5 && p.y() > car.y() + 1.5f, "차 뒤(+Z) 위쪽: " + p);
        Vec3 toCar = car.add(new Vec3(0, 0.9f, 0)).sub(p).normalize();
        check(cam.forward().approxEquals(toCar, 1e-4f), "차를 바라봄: " + cam.forward() + " vs " + toCar);
        float[] yp = ChaseCamera.yawPitchTowards(Vec3.ZERO, new Vec3(-1, 0, 0));
        cam.rotation(yp[0], yp[1]);
        check(cam.forward().approxEquals(new Vec3(-1, 0, 0), 1e-5f), "왼쪽 보기: " + cam.forward());
    }
}
