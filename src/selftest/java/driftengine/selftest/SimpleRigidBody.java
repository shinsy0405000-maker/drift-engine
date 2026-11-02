package driftengine.selftest;

import driftengine.math.Quat;
import driftengine.math.Vec3;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.VehicleBody;

/**
 * 테스트용 순수 Java 강체 (Jolt 대신). 평평한 땅(y = 0) 위에서 차 계산(Car)을 시험한다.
 * 반암시적 오일러 적분, 상자 관성, 게임 차체 몸체와 같은 감쇠(선 0, 각 0.02).
 * 차체 상자 모서리가 땅에 닿으면 벌칙 힘으로 밀어내고 횟수를 센다 (정상 주행이면 0 이어야 함).
 */
final class SimpleRigidBody implements VehicleBody {

    static final Vec3 GRAVITY = new Vec3(0, -9.81f, 0);

    private final float mass;
    private final Vec3 inertia;      // 로컬 주축 관성
    private final Vec3 comLocal;     // 몸체 원점 기준 무게중심
    private final Vec3 half;
    private Vec3 com;
    private Quat rot = Quat.IDENTITY;
    private Vec3 v = Vec3.ZERO;
    private Vec3 w = Vec3.ZERO;
    private Vec3 force = Vec3.ZERO;
    private Vec3 torque = Vec3.ZERO;
    int chassisContacts;

    SimpleRigidBody(CarSpec spec, Vec3 position) {
        this.mass = spec.mass();
        this.half = spec.halfExtents();
        float dx = half.x() * 2, dy = half.y() * 2, dz = half.z() * 2;
        this.inertia = new Vec3(mass / 12 * (dy * dy + dz * dz), mass / 12 * (dx * dx + dz * dz), mass / 12 * (dx * dx + dy * dy));
        this.comLocal = new Vec3(0, spec.centerOfMassY(), 0);
        this.com = position.add(comLocal);
    }

    void setVelocity(Vec3 velocity) {
        v = velocity;
    }

    void integrate(float dt) {
        groundContact();
        v = v.add(force.scale(1 / mass).add(GRAVITY).scale(dt));
        // 각속도: 로컬에서 dω = I⁻¹(τ - ω × Iω)
        Quat inv = rot.conjugate();
        Vec3 wl = inv.rotate(w);
        Vec3 tl = inv.rotate(torque);
        Vec3 iw = new Vec3(inertia.x() * wl.x(), inertia.y() * wl.y(), inertia.z() * wl.z());
        Vec3 rhs = tl.sub(wl.cross(iw));
        wl = wl.add(new Vec3(rhs.x() / inertia.x(), rhs.y() / inertia.y(), rhs.z() / inertia.z()).scale(dt));
        w = rot.rotate(wl);
        w = w.scale(Math.max(0, 1 - 0.02f * dt)); // 게임 차체와 같은 감쇠: 선 0, 각 0.02
        com = com.add(v.scale(dt));
        rot = rot.integrate(w, dt);
        force = Vec3.ZERO;
        torque = Vec3.ZERO;
    }

    private void groundContact() {
        boolean touched = false;
        Vec3 pos = position();
        for (int i = 0; i < 8; i++) {
            Vec3 corner = pos.add(rot.rotate(new Vec3(
                    (i & 1) == 0 ? -half.x() : half.x(),
                    (i & 2) == 0 ? -half.y() : half.y(),
                    (i & 4) == 0 ? -half.z() : half.z())));
            if (corner.y() < 0) {
                touched = true;
                Vec3 pv = v.add(w.cross(corner.sub(com)));
                float f = Math.max(0, 2e5f * -corner.y() - 5e3f * pv.y());
                addForce(new Vec3(-pv.x() * 2e3f, f, -pv.z() * 2e3f), corner);
            }
        }
        if (touched) {
            chassisContacts++;
        }
    }

    @Override
    public Vec3 position() {
        return com.sub(rot.rotate(comLocal));
    }

    @Override
    public Quat rotation() {
        return rot;
    }

    @Override
    public Vec3 centerOfMass() {
        return com;
    }

    @Override
    public Vec3 linearVelocity() {
        return v;
    }

    @Override
    public Vec3 angularVelocity() {
        return w;
    }

    @Override
    public void addForce(Vec3 f, Vec3 point) {
        force = force.add(f);
        torque = torque.add(point.sub(com).cross(f));
    }

    @Override
    public float raycastGround(Vec3 origin, Vec3 dir, float length) {
        if (dir.y() >= -1e-6f || origin.y() < 0) {
            return Float.NaN;
        }
        float t = origin.y() / -dir.y();
        return t <= length ? t : Float.NaN;
    }

    @Override
    public void teleport(Vec3 position, Quat rotation) {
        rot = rotation;
        com = position.add(rotation.rotate(comLocal));
        v = Vec3.ZERO;
        w = Vec3.ZERO;
    }
}
