package driftengine.world.map;

import driftengine.world.Terrain;

/**
 * 도로에 맞춘 지형 만들기:
 * <ol>
 *   <li>도로 근처는 도로 높이를 거리 가중 평균한 부드러운 면 (산길 층 사이가 자연스러운 비탈이 됨)</li>
 *   <li>도로에서 멀어질수록 기본 지형(defaultHeight)으로</li>
 *   <li>도로에서 멀수록 큰 노이즈 (산등성이, 언덕)</li>
 *   <li>마지막으로 도로를 새김 (RoadNet.stamp)</li>
 * </ol>
 */
public final class MapTerrain {

    /** 기본 지형 높이 (도로와 상관없는 먼 곳) */
    public interface Height {
        float at(float x, float z);
    }

    /** 결과: 지형 + 점마다 포장 끝까지 거리 (지형 아래 숨김·차선 판단용) */
    public record Result(Terrain terrain, float[] cover) {
    }

    private MapTerrain() {
    }

    /**
     * @param noiseNear 도로 30m 안 노이즈 세기 (m)
     * @param noiseFar  도로에서 180m 넘게 먼 곳 노이즈 세기 (m)
     * @param farFrom   이 거리(m)부터 기본 지형으로 넘어가기 시작
     */
    public static Result build(int samples, float spacing, RoadNet net, Height def, float noiseNear, float noiseFar,
                               float noiseScale, long seed, float farFrom) {
        float size = (samples - 1) * spacing;
        float origin = -size / 2;
        float coarse = 16f;
        int cn = (int) Math.ceil(size / coarse) + 2;
        float[] cH = new float[cn * cn];
        float[] cD = new float[cn * cn];
        // 도로 점 (4개마다 하나)
        int total = 0;
        for (Road r : net.roads()) {
            total += (r.path().count() + 3) / 4;
        }
        float[] rx = new float[total], rz = new float[total], ry = new float[total];
        int q = 0;
        for (Road r : net.roads()) {
            RoadPath p = r.path();
            for (int i = 0; i < p.count(); i += 4) {
                rx[q] = p.x(i);
                rz[q] = p.z(i);
                ry[q] = p.y(i);
                q++;
            }
        }
        float farTo = farFrom + 200;
        for (int j = 0; j < cn; j++) {
            for (int i = 0; i < cn; i++) {
                float x = origin + (i - 1) * coarse;
                float z = origin + (j - 1) * coarse;
                double w = 0, h = 0;
                float dmin = Float.MAX_VALUE;
                for (int k = 0; k < q; k++) {
                    float dx = rx[k] - x, dz = rz[k] - z;
                    float d2 = dx * dx + dz * dz;
                    dmin = Math.min(dmin, d2);
                    if (d2 < 400f * 400f) {
                        double wk = 1.0 / (d2 + 64.0);
                        w += wk;
                        h += wk * ry[k];
                    }
                }
                dmin = (float) Math.sqrt(dmin);
                float far = def.at(x, z);
                float near = w > 0 ? (float) (h / w) : far;
                float b = RoadNet.smooth((dmin - farFrom) / (farTo - farFrom));
                cH[j * cn + i] = near + (far - near) * b;
                cD[j * cn + i] = dmin;
            }
        }
        float[] heights = new float[samples * samples];
        for (int iz = 0; iz < samples; iz++) {
            for (int ix = 0; ix < samples; ix++) {
                float x = origin + ix * spacing;
                float z = origin + iz * spacing;
                float gx = (x - origin) / coarse + 1;
                float gz = (z - origin) / coarse + 1;
                float h = bilinear(cH, cn, gx, gz);
                float d = bilinear(cD, cn, gx, gz);
                float amp = noiseNear + (noiseFar - noiseNear) * RoadNet.smooth((d - 30) / 150);
                float n = fbm(x * noiseScale, z * noiseScale, seed) * 2 - 1;
                heights[iz * samples + ix] = h + amp * n;
            }
        }
        float[] cover = new float[samples * samples];
        net.stamp(heights, samples, spacing, origin, origin, cover);
        return new Result(new Terrain(samples, spacing, origin, origin, heights), cover);
    }

    private static float bilinear(float[] g, int n, float gx, float gz) {
        int i = Math.max(0, Math.min(n - 2, (int) gx));
        int j = Math.max(0, Math.min(n - 2, (int) gz));
        float fx = Math.max(0, Math.min(1, gx - i));
        float fz = Math.max(0, Math.min(1, gz - j));
        float a = g[j * n + i] + (g[j * n + i + 1] - g[j * n + i]) * fx;
        float b = g[(j + 1) * n + i] + (g[(j + 1) * n + i + 1] - g[(j + 1) * n + i]) * fx;
        return a + (b - a) * fz;
    }

    /** 값 노이즈 5옥타브 (산등성이가 조금 날카롭게: 큰 옥타브는 능선 모양) */
    static float fbm(float x, float z, long seed) {
        float sum = 0, amp = 0.5f, f = 1, norm = 0;
        for (int o = 0; o < 5; o++) {
            float v = Terrain.noise(x * f, z * f, seed + o * 7919L);
            if (o == 0) {
                v = 1 - Math.abs(v * 2 - 1); // 능선
            }
            sum += amp * v;
            norm += amp;
            amp *= 0.5f;
            f *= 2.03f;
        }
        return sum / norm;
    }
}
