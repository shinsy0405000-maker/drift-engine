package driftengine.world.map;

import java.util.function.DoubleUnaryOperator;

/**
 * 도로 중심선: 조종점을 지나는 캐트멀-롬 곡선을 거의 같은 간격(step)으로 촘촘히 나눈 점들.
 * 점마다 호 길이 s, 방향(단위 벡터), 굽음(곡률, 왼쪽으로 굽으면 +), 높이 y 를 가진다.
 * <p>
 * 좌표: x, z 는 수평(미터), y 는 높이 (오른손 좌표, 차 앞 = −z 일 때 오른쪽 = +x).
 * 진행 방향 (tx, tz) 의 왼쪽 = 위 × 앞 = (tz, −tx). "옆 +" 는 왼쪽이다.
 */
public final class RoadPath {

    private final boolean closed;
    private final float[] x;
    private final float[] z;
    private final float[] s;
    private final float[] tx;
    private final float[] tz;
    private final float[] k;
    private final float[] y;
    private final float length;

    private RoadPath(boolean closed, float[] x, float[] z) {
        this.closed = closed;
        int n = x.length;
        this.x = x;
        this.z = z;
        s = new float[n];
        tx = new float[n];
        tz = new float[n];
        k = new float[n];
        y = new float[n];
        for (int i = 1; i < n; i++) {
            s[i] = s[i - 1] + (float) Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
        }
        length = closed ? s[n - 1] + (float) Math.hypot(x[0] - x[n - 1], z[0] - z[n - 1]) : s[n - 1];
        for (int i = 0; i < n; i++) {
            int a = prev(i);
            int b = next(i);
            float dx = x[b] - x[a];
            float dz = z[b] - z[a];
            float len = (float) Math.hypot(dx, dz);
            tx[i] = dx / len;
            tz[i] = dz / len;
        }
        // 굽음: 앞뒤 방향 차이 ÷ 거리, 몇 점 평균 (잡음 줄이기)
        float[] raw = new float[n];
        for (int i = 0; i < n; i++) {
            int a = prev(i);
            int b = next(i);
            float cross = tx[a] * tz[b] - tz[a] * tx[b];
            float dot = tx[a] * tx[b] + tz[a] * tz[b];
            float ds = Math.max(1e-3f, dist(a, b));
            // 위에서 볼 때 왼쪽으로 굽으면 +: 방향을 (−tz, tx) 쪽으로 돌림 → cross(t_a, t_b) 의 부호를 맞춘다
            raw[i] = (float) -Math.atan2(cross, dot) / ds;
        }
        int w = 3;
        for (int i = 0; i < n; i++) {
            float sum = 0;
            int c = 0;
            for (int j = -w; j <= w; j++) {
                int q = i + j;
                if (closed) {
                    q = Math.floorMod(q, n);
                } else if (q < 0 || q >= n) {
                    continue;
                }
                sum += raw[q];
                c++;
            }
            k[i] = sum / c;
        }
    }

    /**
     * 조종점을 지나는 곡선 (구심 캐트멀-롬). 끝이 열린 길은 첫·끝 점에서 직선으로 이어진다.
     *
     * @param points {x, z} 쌍들
     * @param step   점 간격 (미터)
     */
    public static RoadPath through(float[][] points, boolean closed, float step) {
        int m = points.length;
        if (m < (closed ? 3 : 2)) {
            throw new IllegalArgumentException("조종점이 너무 적음: " + m);
        }
        // 1) 조각마다 잘게 나눈 점 (간격은 조금 고르지 않음)
        java.util.List<float[]> fine = new java.util.ArrayList<>();
        int segs = closed ? m : m - 1;
        for (int i = 0; i < segs; i++) {
            float[] p0 = points[closed ? Math.floorMod(i - 1, m) : Math.max(0, i - 1)];
            float[] p1 = points[i];
            float[] p2 = points[(i + 1) % m];
            float[] p3 = points[closed ? (i + 2) % m : Math.min(m - 1, i + 2)];
            float chord = (float) Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            int parts = Math.max(4, (int) Math.ceil(chord / (step * 0.25f)));
            for (int j = 0; j < parts; j++) {
                fine.add(catmullRom(p0, p1, p2, p3, j / (float) parts));
            }
        }
        if (!closed) {
            fine.add(points[m - 1].clone());
        }
        // 2) 호 길이로 다시 나눠 거의 같은 간격으로
        int f = fine.size();
        float[] acc = new float[f + 1];
        for (int i = 1; i < f; i++) {
            acc[i] = acc[i - 1] + dist(fine.get(i - 1), fine.get(i));
        }
        float total = closed ? acc[f - 1] + dist(fine.get(f - 1), fine.get(0)) : acc[f - 1];
        acc[f] = total;
        int n = Math.max(closed ? 3 : 2, Math.round(total / step) + (closed ? 0 : 1));
        float ds = closed ? total / n : total / (n - 1);
        float[] xs = new float[n];
        float[] zs = new float[n];
        int seg = 0;
        for (int i = 0; i < n; i++) {
            float target = i * ds;
            while (seg < f - 1 && acc[seg + 1] < target) {
                seg++;
            }
            if (!closed && seg >= f - 1) {
                xs[i] = fine.get(f - 1)[0];
                zs[i] = fine.get(f - 1)[1];
                continue;
            }
            float[] a = fine.get(seg);
            float[] b = seg + 1 < f ? fine.get(seg + 1) : fine.get(0);
            float l0 = acc[seg];
            float l1 = seg + 1 < f ? acc[seg + 1] : total;
            float t = l1 > l0 ? (target - l0) / (l1 - l0) : 0;
            t = Math.max(0, Math.min(1, t));
            xs[i] = a[0] + (b[0] - a[0]) * t;
            zs[i] = a[1] + (b[1] - a[1]) * t;
        }
        return new RoadPath(closed, xs, zs);
    }

    /** 구심 캐트멀-롬 (alpha 0.5): 조종점 사이가 고르지 않아도 고리·뾰족점이 안 생긴다 */
    private static float[] catmullRom(float[] p0, float[] p1, float[] p2, float[] p3, float u) {
        float t0 = 0;
        float t1 = t0 + (float) Math.sqrt(Math.max(1e-4f, dist(p0, p1)));
        float t2 = t1 + (float) Math.sqrt(Math.max(1e-4f, dist(p1, p2)));
        float t3 = t2 + (float) Math.sqrt(Math.max(1e-4f, dist(p2, p3)));
        float t = t1 + (t2 - t1) * u;
        float[] a1 = lerp(p0, p1, (t - t0) / (t1 - t0));
        float[] a2 = lerp(p1, p2, (t - t1) / (t2 - t1));
        float[] a3 = lerp(p2, p3, (t - t2) / (t3 - t2));
        float[] b1 = lerp(a1, a2, (t - t0) / (t2 - t0));
        float[] b2 = lerp(a2, a3, (t - t1) / (t3 - t1));
        return lerp(b1, b2, (t - t1) / (t2 - t1));
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    private static float dist(float[] a, float[] b) {
        return (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private float dist(int a, int b) {
        return (float) Math.hypot(x[b] - x[a], z[b] - z[a]);
    }

    // ------------------------------------------------------------------ 높이

    /** 높이 = 함수(호 길이) */
    public RoadPath heights(DoubleUnaryOperator profile) {
        for (int i = 0; i < y.length; i++) {
            y[i] = (float) profile.applyAsDouble(s[i]);
        }
        return this;
    }

    /** 높이를 직접 (점 개수만큼) */
    public RoadPath heights(float[] values) {
        if (values.length != y.length) {
            throw new IllegalArgumentException("높이 개수 " + values.length + " ≠ " + y.length);
        }
        System.arraycopy(values, 0, y, 0, y.length);
        return this;
    }

    // ------------------------------------------------------------------ 값

    public boolean closed() {
        return closed;
    }

    public int count() {
        return x.length;
    }

    public float length() {
        return length;
    }

    public float x(int i) {
        return x[i];
    }

    public float z(int i) {
        return z[i];
    }

    public float y(int i) {
        return y[i];
    }

    public float s(int i) {
        return s[i];
    }

    public float tx(int i) {
        return tx[i];
    }

    public float tz(int i) {
        return tz[i];
    }

    /** 굽음 (1/m, 왼쪽 +) */
    public float curvature(int i) {
        return k[i];
    }

    public int next(int i) {
        return closed ? (i + 1) % x.length : Math.min(x.length - 1, i + 1);
    }

    public int prev(int i) {
        return closed ? Math.floorMod(i - 1, x.length) : Math.max(0, i - 1);
    }

    /** 호 길이 → 가장 가까운 점 번호 (닫힌 길은 감아서) */
    public int index(float at) {
        float ds = length / (closed ? x.length : x.length - 1);
        if (closed) {
            at = (float) (at - Math.floor(at / length) * length);
            return Math.floorMod(Math.round(at / ds), x.length);
        }
        return Math.max(0, Math.min(x.length - 1, Math.round(at / ds)));
    }

    /** 호 길이 at 의 {x, z, tx, tz, y} (점 사이 직선 보간, 닫힌 길은 감아서) */
    public float[] at(float at) {
        int n = x.length;
        if (closed) {
            at = (float) (at - Math.floor(at / length) * length);
        } else {
            at = Math.max(0, Math.min(length, at));
        }
        float ds = length / (closed ? n : n - 1);
        int i = Math.min((int) (at / ds), closed ? n - 1 : n - 2);
        int j = next(i);
        float t = Math.max(0, Math.min(1, (at - i * ds) / ds));
        float ttx = tx[i] + (tx[j] - tx[i]) * t;
        float ttz = tz[i] + (tz[j] - tz[i]) * t;
        float len = (float) Math.hypot(ttx, ttz);
        return new float[]{x[i] + (x[j] - x[i]) * t, z[i] + (z[j] - z[i]) * t, ttx / len, ttz / len, y[i] + (y[j] - y[i]) * t};
    }

    /** 경사 (높이 변화 ÷ 거리) 가장 큰 값 */
    public float maxGrade() {
        float g = 0;
        for (int i = 0; i < x.length; i++) {
            int b = next(i);
            if (b == i) {
                continue;
            }
            g = Math.max(g, Math.abs(y[b] - y[i]) / Math.max(1e-3f, dist(i, b)));
        }
        return g;
    }

    /** 가장 작은 굽음 반지름 (m) */
    public float minRadius() {
        float r = Float.MAX_VALUE;
        for (int i = 0; i < x.length; i++) {
            if (!closed && (i < 4 || i > x.length - 5)) {
                continue;
            }
            r = Math.min(r, 1 / Math.max(1e-5f, Math.abs(k[i])));
        }
        return r;
    }
}
