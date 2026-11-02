package driftengine.math;

/**
 * 3차원 벡터 (불변). 좌표계: 오른손 좌표계, +Y 위, 카메라는 기본적으로 -Z 를 바라본다.
 * <p>
 * 매 연산마다 새 객체를 만든다. 지금 단계(카메라, 설정값)에는 충분하고,
 * 나중에 물리처럼 초당 수만 번 계산하는 곳은 별도의 가변 타입을 쓴다.
 */
public record Vec3(float x, float y, float z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 UNIT_X = new Vec3(1, 0, 0);
    public static final Vec3 UNIT_Y = new Vec3(0, 1, 0);
    public static final Vec3 UNIT_Z = new Vec3(0, 0, 1);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(float s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public float dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public float length() {
        return (float) Math.sqrt(dot(this));
    }

    /** 길이 1 로 만든다. 길이가 0 이면 예외 (조용히 NaN 을 퍼뜨리지 않기 위해). */
    public Vec3 normalize() {
        float len = length();
        if (len < 1e-8f) {
            throw new ArithmeticException("길이가 0 인 벡터는 정규화할 수 없습니다");
        }
        return scale(1.0f / len);
    }

    public boolean approxEquals(Vec3 o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps && Math.abs(z - o.z) <= eps;
    }
}
