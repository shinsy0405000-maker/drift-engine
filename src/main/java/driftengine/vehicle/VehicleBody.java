package driftengine.vehicle;

import driftengine.math.Quat;
import driftengine.math.Vec3;

/**
 * 차량 계산(Car)이 차체 강체에 요구하는 것. 게임에서는 Jolt 몸체(physics.JoltVehicleBody),
 * 자체 테스트에서는 순수 Java 강체를 쓴다 → 주행 물리를 GPU·Jolt 없이 시험할 수 있다.
 * <p>
 * addForce 로 넣은 힘은 다음 물리 스텝 한 번 동안만 작용한다.
 */
public interface VehicleBody {

    /** 몸체 원점(충돌 상자 중심) 위치 */
    Vec3 position();

    Quat rotation();

    Vec3 centerOfMass();

    Vec3 linearVelocity();

    /** 월드 좌표 각속도 (rad/s) */
    Vec3 angularVelocity();

    /** 월드 점 point 에 힘 force (N) */
    void addForce(Vec3 force, Vec3 point);

    /** origin 에서 dir(길이 1) 방향으로 length 까지 땅(정적 물체)을 찾는다. @return 거리, 없으면 NaN */
    float raycastGround(Vec3 origin, Vec3 dir, float length);

    /** 위치·방향을 바꾸고 속도를 0 으로 (차 되돌리기) */
    void teleport(Vec3 position, Quat rotation);
}
