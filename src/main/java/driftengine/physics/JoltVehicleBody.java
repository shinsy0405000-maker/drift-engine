package driftengine.physics;

import driftengine.math.Quat;
import driftengine.math.Vec3;
import driftengine.vehicle.CarSpec;
import driftengine.vehicle.VehicleBody;

/** Jolt 차체 몸체 → Car 가 쓰는 VehicleBody. 바퀴 광선은 정적 물체(지형)만 본다 */
public final class JoltVehicleBody implements VehicleBody {

    private final PhysicsWorld physics;
    private final int body;

    private JoltVehicleBody(PhysicsWorld physics, int body) {
        this.physics = physics;
        this.body = body;
    }

    /** 제원의 충돌 상자·무게중심·질량으로 차체 몸체를 만든다 */
    public static JoltVehicleBody create(PhysicsWorld physics, CarSpec spec, Vec3 position) {
        Vec3 h = spec.halfExtents();
        int id = physics.addVehicleBody(position.x(), position.y(), position.z(), h.x(), h.y(), h.z(),
                spec.centerOfMassY(), spec.mass(), 0.5f);
        return new JoltVehicleBody(physics, id);
    }

    public int id() {
        return body;
    }

    public void remove() {
        physics.removeBody(body);
    }

    @Override
    public Vec3 position() {
        float[] p = physics.position(body);
        return new Vec3(p[0], p[1], p[2]);
    }

    @Override
    public Quat rotation() {
        float[] q = physics.rotation(body);
        return new Quat(q[0], q[1], q[2], q[3]).normalize();
    }

    @Override
    public Vec3 centerOfMass() {
        float[] c = physics.centerOfMass(body);
        return new Vec3(c[0], c[1], c[2]);
    }

    @Override
    public Vec3 linearVelocity() {
        float[] v = physics.linearVelocity(body);
        return new Vec3(v[0], v[1], v[2]);
    }

    @Override
    public Vec3 angularVelocity() {
        float[] w = physics.angularVelocity(body);
        return new Vec3(w[0], w[1], w[2]);
    }

    @Override
    public void addForce(Vec3 force, Vec3 point) {
        physics.addForceAt(body, force.x(), force.y(), force.z(), point.x(), point.y(), point.z());
    }

    @Override
    public float raycastGround(Vec3 origin, Vec3 dir, float length) {
        return physics.raycastStatic(origin.x(), origin.y(), origin.z(), dir.x(), dir.y(), dir.z(), length);
    }

    @Override
    public void teleport(Vec3 position, Quat rotation) {
        physics.teleport(body, position.x(), position.y(), position.z(), rotation.x(), rotation.y(), rotation.z(), rotation.w());
    }
}
