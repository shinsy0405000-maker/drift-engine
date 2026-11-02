package driftengine.world.map;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 지역의 도로 모음 + 가까운 도로 찾기(격자 해시) + 지형 높이에 도로 새기기.
 */
public final class RoadNet {

    /** 가까운 도로 찾기 결과 */
    public record Hit(Road road, int roadIndex, int point, float distance, float lateral, float s, float y) {
    }

    private static final float CELL = 20f;

    private final List<Road> roads;
    private final java.util.Map<Long, int[]> grid = new java.util.HashMap<>();

    public RoadNet(List<Road> roads) {
        this.roads = List.copyOf(roads);
        java.util.Map<Long, List<Integer>> tmp = new java.util.HashMap<>();
        for (int r = 0; r < this.roads.size(); r++) {
            RoadPath p = this.roads.get(r).path();
            for (int i = 0; i < p.count(); i++) {
                tmp.computeIfAbsent(key(cell(p.x(i)), cell(p.z(i))), q -> new ArrayList<>()).add(r << 20 | i);
            }
        }
        for (var e : tmp.entrySet()) {
            grid.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
    }

    public List<Road> roads() {
        return roads;
    }

    private static int cell(float v) {
        return (int) Math.floor(v / CELL);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /**
     * 가장 가까운 도로 중심선 (선분에 수선을 내려 정확히). maxDist 안에 없으면 null.
     */
    public Hit nearest(float x, float z, float maxDist) {
        int r = (int) Math.ceil(maxDist / CELL);
        int cx = cell(x);
        int cz = cell(z);
        float best = maxDist * maxDist;
        int bestRoad = -1;
        int bestPoint = -1;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int[] list = grid.get(key(cx + dx, cz + dz));
                if (list == null) {
                    continue;
                }
                for (int packed : list) {
                    RoadPath p = roads.get(packed >>> 20).path();
                    int i = packed & 0xFFFFF;
                    float ex = p.x(i) - x;
                    float ez = p.z(i) - z;
                    float d2 = ex * ex + ez * ez;
                    if (d2 < best) {
                        best = d2;
                        bestRoad = packed >>> 20;
                        bestPoint = i;
                    }
                }
            }
        }
        if (bestRoad < 0) {
            return null;
        }
        // 이웃 두 선분 중 가까운 쪽에 수선
        Road road = roads.get(bestRoad);
        RoadPath p = road.path();
        float[] a = project(p, p.prev(bestPoint), bestPoint, x, z);
        float[] b = project(p, bestPoint, p.next(bestPoint), x, z);
        float[] h = a[0] <= b[0] ? a : b;
        float dist = (float) Math.sqrt(h[0]);
        return new Hit(road, bestRoad, bestPoint, dist, h[1], h[2], h[3]);
    }

    /** 선분 i→j 위로 수선: {거리², 옆(왼쪽 +), s, y} */
    private static float[] project(RoadPath p, int i, int j, float x, float z) {
        float ax = p.x(i), az = p.z(i);
        float dx = p.x(j) - ax, dz = p.z(j) - az;
        float len2 = dx * dx + dz * dz;
        float t = len2 < 1e-9f ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
        float px = ax + dx * t, pz = az + dz * t;
        float ex = x - px, ez = z - pz;
        float len = (float) Math.sqrt(len2);
        // 왼쪽 = (tz, −tx)
        float lateral = len < 1e-6f ? 0 : (ex * dz - ez * dx) / len;
        float s0 = p.s(i);
        float s1 = j > i ? p.s(j) : p.length();
        if (j == i) {
            s1 = s0;
        }
        return new float[]{ex * ex + ez * ez, lateral, s0 + (s1 - s0) * t, p.y(i) + (p.y(j) - p.y(i)) * t};
    }

    // ------------------------------------------------------------------ 지형에 새기기

    /**
     * 높이맵에 도로를 새긴다: 포장 + 갓길(flatHalf) 안은 도로 높이, 그 밖 blend 거리 동안 원래 지형으로.
     * 도로 높이는 가까운 중심선 점들의 무게 평균 (헤어핀처럼 두 길이 가까워도 끊김이 없음).
     *
     * @param base     원래 지형 높이 (samples², 행 우선). 결과로 덮어쓴다
     * @param coverOut 점마다 "가장 가까운 도로 포장 끝까지 거리" (안쪽이면 음수). null 이면 안 씀
     */
    public void stamp(float[] base, int samples, float spacing, float originX, float originZ, float[] coverOut) {
        int n = samples * samples;
        float[] wSum = new float[n];
        float[] hSum = new float[n];
        float[] flat = new float[n];
        float[] cover = coverOut != null ? coverOut : new float[n];
        java.util.Arrays.fill(cover, Float.MAX_VALUE);
        for (Road road : roads) {
            RoadPath p = road.path();
            float reach = road.flatHalf() + road.blend();
            float sigma = Math.max(2f, road.halfWidth());
            int rad = (int) Math.ceil(reach / spacing);
            for (int i = 0; i < p.count(); i++) {
                float px = p.x(i), pz = p.z(i), py = p.y(i);
                int gx = Math.round((px - originX) / spacing);
                int gz = Math.round((pz - originZ) / spacing);
                for (int iz = Math.max(0, gz - rad); iz <= Math.min(samples - 1, gz + rad); iz++) {
                    float wz = originZ + iz * spacing - pz;
                    for (int ix = Math.max(0, gx - rad); ix <= Math.min(samples - 1, gx + rad); ix++) {
                        float wx = originX + ix * spacing - px;
                        float d = (float) Math.sqrt(wx * wx + wz * wz);
                        if (d > reach) {
                            continue;
                        }
                        int at = iz * samples + ix;
                        float q = d / sigma;
                        float w = (float) Math.exp(-q * q) + 1e-12f;
                        wSum[at] += w;
                        hSum[at] += w * py;
                        float f = d <= road.flatHalf() ? 1 : 1 - smooth((d - road.flatHalf()) / road.blend());
                        if (f > flat[at]) {
                            flat[at] = f;
                        }
                        float edge = d - road.halfWidth();
                        if (edge < cover[at]) {
                            cover[at] = edge;
                        }
                    }
                }
            }
        }
        for (int at = 0; at < n; at++) {
            if (flat[at] > 0 && wSum[at] > 0) {
                float roadH = hSum[at] / wSum[at];
                base[at] = base[at] + (roadH - base[at]) * flat[at];
            }
        }
    }

    static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
