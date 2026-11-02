package driftengine.math;

/**
 * 회전 쿼터니언 (x, y, z, w). 물리 엔진이 돌려주는 물체 방향을 다룰 때 쓴다. 불변 값.
 */
public record Quat(float x, float y, float z, float w) {

    public static final Quat IDENTITY = new Quat(0, 0, 0, 1);

    /** 축(길이 1) 둘레로 radians 만큼 회전 */
    public static Quat axisAngle(Vec3 axis, float radians) {
        float s = (float) Math.sin(radians / 2);
        return new Quat(axis.x() * s, axis.y() * s, axis.z() * s, (float) Math.cos(radians / 2));
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z + w * w);
    }

    public Quat normalize() {
        float len = length();
        return len < 1e-12f ? IDENTITY : new Quat(x / len, y / len, z / len, w / len);
    }

    /** 벡터 v 를 이 회전으로 돌린다 (단위 쿼터니언 가정) */
    public Vec3 rotate(Vec3 v) {
        // v' = v + 2w(q × v) + 2 q × (q × v),  q = (x, y, z)
        Vec3 q = new Vec3(x, y, z);
        Vec3 t = q.cross(v).scale(2);
        return v.add(t.scale(w)).add(q.cross(t));
    }

    /**
     * a 와 b 사이를 t(0~1) 로 섞는다 (정규화 선형 보간). 가까운 쪽으로 돌도록 부호를 맞춘다.
     * 한 틱(1/60초) 사이처럼 회전 차이가 작을 때는 구면 보간(slerp)과 차이가 거의 없다.
     */
    public static Quat nlerp(Quat a, Quat b, float t) {
        float dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
        float sign = dot < 0 ? -1 : 1;
        return new Quat(
                a.x + (b.x * sign - a.x) * t,
                a.y + (b.y * sign - a.y) * t,
                a.z + (b.z * sign - a.z) * t,
                a.w + (b.w * sign - a.w) * t).normalize();
    }

    /** this × o : o 를 먼저 돌리고 그다음 this 로 돌리는 회전 */
    public Quat mul(Quat o) {
        return new Quat(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }

    /** 반대 회전 (단위 쿼터니언 가정) */
    public Quat conjugate() {
        return new Quat(-x, -y, -z, w);
    }

    /** 월드 각속도 omega (rad/s) 로 dt 초 돌린 방향 (작은 회전 근사 후 정규화) */
    public Quat integrate(Vec3 omega, float dt) {
        Quat spin = new Quat(omega.x() * dt * 0.5f, omega.y() * dt * 0.5f, omega.z() * dt * 0.5f, 0).mul(this);
        return new Quat(x + spin.x, y + spin.y, z + spin.z, w + spin.w).normalize();
    }

    /** 수평 방향(요)만 남긴 회전: 앞(-Z) 방향을 바닥에 눕혀 그 방향을 보게 한다. 차를 바로 세울 때 쓴다 */
    public Quat yawOnly() {
        Vec3 f = rotate(new Vec3(0, 0, -1));
        float yaw = (float) Math.atan2(-f.x(), -f.z());
        return axisAngle(Vec3.UNIT_Y, yaw);
    }
}
