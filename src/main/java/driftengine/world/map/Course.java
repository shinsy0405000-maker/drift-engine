package driftengine.world.map;

import java.util.ArrayList;
import java.util.List;

/**
 * 이벤트 코스: 도로 위 문(게이트)들. 0번 문 = 출발선, 마지막 = 결승선 (한 바퀴 코스는 출발선을 다시 지나면 끝).
 * 문은 도로를 가로지르는 선분. 차가 이전 위치 → 지금 위치로 움직이며 앞으로 넘으면 통과.
 */
public final class Course {

    /** 문: 가운데 (x, z), 진행 방향 (tx, tz), 반폭, 도로 위치 s */
    public record Gate(float x, float z, float tx, float tz, float halfWidth, float s) {

        /** 이전 → 지금 위치가 이 문을 앞으로 넘었나 */
        public boolean crossed(float px, float pz, float cx, float cz) {
            float a = (px - x) * tx + (pz - z) * tz;
            float b = (cx - x) * tx + (cz - z) * tz;
            if (!(a < 0 && b >= 0)) {
                return false;
            }
            float t = a / (a - b);
            float ix = px + (cx - px) * t - x;
            float iz = pz + (cz - pz) * t - z;
            // 왼쪽 = (tz, −tx)
            float lateral = ix * tz - iz * tx;
            return Math.abs(lateral) <= halfWidth;
        }
    }

    private final String id;
    private final boolean loop;
    private final List<Gate> gates;
    private final float length;
    private final RoadPath path;
    private final float startS;

    private Course(String id, boolean loop, List<Gate> gates, float length, RoadPath path, float startS) {
        this.id = id;
        this.loop = loop;
        this.gates = List.copyOf(gates);
        this.length = length;
        this.path = path;
        this.startS = startS;
    }

    /**
     * 도로 위 코스.
     *
     * @param start  출발선 s
     * @param finish 결승선 s (한 바퀴면 무시)
     * @param every  중간 문 간격 (m)
     * @param margin 문 반폭 = 도로 반폭 + margin (갓길로 넘어도 인정)
     */
    public static Course on(String id, Road road, boolean loop, float start, float finish, float every, float margin) {
        RoadPath p = road.path();
        float hw = road.halfWidth() + margin;
        List<Gate> g = new ArrayList<>();
        float len = loop ? p.length() : finish - start;
        int n = Math.max(2, (int) Math.floor(len / every));
        for (int i = 0; i < n; i++) {
            g.add(gate(p, start + len * i / n, hw));
        }
        if (!loop) {
            g.add(gate(p, finish, hw));
        }
        return new Course(id, loop, g, len, p, start);
    }

    private static Gate gate(RoadPath p, float s, float hw) {
        float[] a = p.at(s);
        return new Gate(a[0], a[1], a[2], a[3], hw, s);
    }

    public String id() {
        return id;
    }

    public boolean loop() {
        return loop;
    }

    public List<Gate> gates() {
        return gates;
    }

    /** 코스 길이 (m) */
    public float length() {
        return length;
    }

    public RoadPath path() {
        return path;
    }

    /** 출발선에서 distance 만큼 뒤(음수면 앞)의 도로 위 점 {x, z, tx, tz, y} */
    public float[] behindStart(float distance) {
        return path.at(startS - distance);
    }
}
