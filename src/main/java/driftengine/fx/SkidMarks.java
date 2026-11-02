package driftengine.fx;

import driftengine.scene.Effects;

/**
 * 스키드 자국: 미끄러지는 바퀴가 지나간 자리에 남는 검은 띠.
 * <p>
 * 바퀴마다 마지막 점과 띠 양쪽 끝을 기억해 두었다가, 0.25m 이상 움직이면 조각 하나를 이어 붙인다
 * (앞 조각의 끝 = 다음 조각의 시작 → 틈 없는 띠). 조각은 고리 버퍼에 쌓이고, 꽉 차면 가장 오래된 것부터
 * 덮어쓴다. 오래된 것은 서서히 옅어진다.
 */
public final class SkidMarks {

    /** 땅 높이 (월드 x, z → y) */
    @FunctionalInterface
    public interface Ground {
        float height(float x, float z);
    }

    public static final int CAPACITY = 5000;
    static final float SEGMENT = 0.25f;
    static final float LIFT = 0.025f;   // 땅에서 띄우는 높이 (깊이 겹침 방지)
    static final float BREAK = 3f;      // 이보다 멀리 뛰면(차 되돌리기) 띠를 끊음

    private final float[] ring = new float[CAPACITY * Effects.SKID_FLOATS];
    private int head;   // 다음에 쓸 자리
    private int size;
    private final Wheel[] wheels = {new Wheel(), new Wheel(), new Wheel(), new Wheel()};
    private final float[] corners = new float[12];

    private static final class Wheel {
        boolean active;
        float x;
        float z;
        float v;            // 띠 길이 방향 누적 거리 (무늬용)
        boolean hasEdge;
        final float[] edge = new float[6]; // 마지막 왼쪽 끝 xyz, 오른쪽 끝 xyz
    }

    /** 강도 0 ~ 1: 미끄러지는 속도·하중으로 */
    public static float intensity(float slideSpeed, float loadRatio) {
        return clamp01((slideSpeed - 2.5f) / 8f) * clamp01(loadRatio * 1.2f);
    }

    /**
     * 바퀴 하나의 이번 틱 상태.
     *
     * @param contact   땅에 닿음
     * @param x         닿은 점 x
     * @param z         닿은 점 z
     * @param intensity 0 이면 띠가 끝남
     * @param width     타이어 폭 (m)
     */
    public void update(int wheel, boolean contact, float x, float z, float intensity, float width, Ground ground) {
        Wheel w = wheels[wheel];
        if (!contact || intensity <= 0.02f) {
            w.active = false;
            w.hasEdge = false;
            return;
        }
        if (!w.active) {
            w.active = true;
            w.hasEdge = false;
            w.x = x;
            w.z = z;
            return;
        }
        float dx = x - w.x;
        float dz = z - w.z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        if (d > BREAK) {
            w.x = x;
            w.z = z;
            w.hasEdge = false;
            return;
        }
        if (d < SEGMENT) {
            return;
        }
        // 진행 방향에 수직인 옆 방향 (수평)
        float sx = -dz / d * width * 0.5f;
        float sz = dx / d * width * 0.5f;
        if (!w.hasEdge) {
            edge(w.edge, 0, w.x + sx, w.z + sz, ground);
            edge(w.edge, 3, w.x - sx, w.z - sz, ground);
        }
        // 시작 = 앞 조각의 끝
        System.arraycopy(w.edge, 0, corners, 0, 3);   // 왼A
        System.arraycopy(w.edge, 3, corners, 3, 3);   // 오른A
        edge(w.edge, 0, x + sx, z + sz, ground);      // 왼B
        edge(w.edge, 3, x - sx, z - sz, ground);      // 오른B
        System.arraycopy(w.edge, 3, corners, 6, 3);   // 오른B
        System.arraycopy(w.edge, 0, corners, 9, 3);   // 왼B
        w.hasEdge = true;
        float v0 = w.v;
        w.v += d;
        push(corners, 0.15f + 0.6f * clamp01(intensity), v0, w.v);
        w.x = x;
        w.z = z;
    }

    private static void edge(float[] dst, int o, float x, float z, Ground ground) {
        dst[o] = x;
        dst[o + 1] = ground.height(x, z) + LIFT;
        dst[o + 2] = z;
    }

    private void push(float[] c, float alpha, float v0, float v1) {
        int o = head * Effects.SKID_FLOATS;
        System.arraycopy(c, 0, ring, o, 12);
        ring[o + 12] = alpha;
        ring[o + 13] = v0;
        ring[o + 14] = v1;
        head = (head + 1) % CAPACITY;
        size = Math.min(CAPACITY, size + 1);
    }

    /** 모든 바퀴의 띠를 끊는다 (차 바꾸기·되돌리기) */
    public void breakAll() {
        for (Wheel w : wheels) {
            w.active = false;
            w.hasEdge = false;
        }
    }

    public void clear() {
        breakAll();
        head = 0;
        size = 0;
    }

    public int size() {
        return size;
    }

    /** 오래된 것부터 효과 목록에 넣는다. 가장 오래된 20% 는 옅어진다 */
    public void write(Effects fx) {
        int start = (head - size + CAPACITY) % CAPACITY;
        float[] c = corners;
        for (int k = 0; k < size; k++) {
            int i = (start + k) % CAPACITY;
            int o = i * Effects.SKID_FLOATS;
            float age = (size - k) / (float) CAPACITY; // 1 = 곧 덮어쓸 가장 오래된 것
            float fade = age > 0.8f ? 1 - (age - 0.8f) / 0.2f : 1;
            System.arraycopy(ring, o, c, 0, 12);
            if (!fx.addSkid(c, ring[o + 12] * fade, ring[o + 13], ring[o + 14])) {
                break;
            }
        }
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
