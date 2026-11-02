package driftengine.render;

import driftengine.math.Mat4;
import driftengine.scene.SceneObject;
import driftengine.scene.mesh.MeshData;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 안 보이는 물체 건너뛰기: 물체를 감싸는 공이 절두체(카메라 또는 그림자 단계) 밖이면 그리지 않는다.
 * 행렬은 열 벡터 규약 (clip = M · p), Vulkan 깊이 0~1.
 */
public final class Culling {

    /** 메시마다 로컬 감싸는 공 (가운데 xyz, 반지름) + 상자 (최소 xyz, 최대 xyz). MeshData 는 바뀌지 않으므로 한 번만 계산 */
    private static final Map<MeshData, float[]> LOCAL = new WeakHashMap<>();

    private Culling() {
    }

    static synchronized float[] localSphere(MeshData m) {
        float[] s = LOCAL.get(m);
        if (s == null) {
            float[] v = m.vertices();
            int stride = driftengine.scene.mesh.VertexFormat.FLOATS_PER_VERTEX;
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE;
            float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
            for (int i = 0; i < v.length; i += stride) {
                x0 = Math.min(x0, v[i]);
                y0 = Math.min(y0, v[i + 1]);
                z0 = Math.min(z0, v[i + 2]);
                x1 = Math.max(x1, v[i]);
                y1 = Math.max(y1, v[i + 1]);
                z1 = Math.max(z1, v[i + 2]);
            }
            if (v.length == 0) {
                s = new float[10];
            } else {
                float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
                s = new float[]{(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / 2,
                        x0, y0, z0, x1, y1, z1};
            }
            LOCAL.put(m, s);
        }
        return s;
    }

    /** 월드 공간 높이 폭 (로컬 상자 8 모서리를 옮긴 뒤 y 최대 - 최소). 그림자 길이 어림용 */
    public static float worldHeightSpan(SceneObject o) {
        float[] l = localSphere(o.mesh());
        Mat4 m = o.model();
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            float x = (k & 1) == 0 ? l[4] : l[7];
            float y = (k & 2) == 0 ? l[5] : l[8];
            float z = (k & 4) == 0 ? l[6] : l[9];
            float wy = m.get(1, 0) * x + m.get(1, 1) * y + m.get(1, 2) * z + m.get(1, 3);
            lo = Math.min(lo, wy);
            hi = Math.max(hi, wy);
        }
        return hi - lo;
    }

    /** 월드 공간 감싸는 공 → out[0..3] */
    public static void worldSphere(SceneObject o, float[] out) {
        sphere(o.mesh(), o.model(), out);
    }

    public static void sphere(MeshData mesh, Mat4 model, float[] out) {
        float[] l = localSphere(mesh);
        for (int r = 0; r < 3; r++) {
            out[r] = model.get(r, 0) * l[0] + model.get(r, 1) * l[1] + model.get(r, 2) * l[2] + model.get(r, 3);
        }
        float scale = 0;
        for (int c = 0; c < 3; c++) {
            float x = model.get(0, c), y = model.get(1, c), z = model.get(2, c);
            scale = Math.max(scale, (float) Math.sqrt(x * x + y * y + z * z));
        }
        out[3] = l[3] * scale;
    }

    /** 투영 행렬의 6 평면 (a, b, c, d 정규화, 안쪽이 +) → planes[24] */
    public static void planes(Mat4 m, float[] planes) {
        // 왼 r3+r0, 오른 r3-r0, 아래 r3+r1, 위 r3-r1, 가까운 r2 (z>=0), 먼 r3-r2 (z<=w)
        int[][] combos = {{0, 1}, {0, -1}, {1, 1}, {1, -1}, {2, 0}, {2, -1}};
        for (int p = 0; p < 6; p++) {
            int row = combos[p][0];
            int sign = combos[p][1];
            float n2 = 0;
            for (int c = 0; c < 4; c++) {
                float v;
                if (sign == 0) {
                    v = m.get(row, c);
                } else {
                    v = m.get(3, c) + sign * m.get(row, c);
                }
                planes[p * 4 + c] = v;
                if (c < 3) {
                    n2 += v * v;
                }
            }
            float inv = n2 > 0 ? (float) (1 / Math.sqrt(n2)) : 0;
            for (int c = 0; c < 4; c++) {
                planes[p * 4 + c] *= inv;
            }
        }
    }

    /** 공이 절두체에 조금이라도 걸치면 true */
    public static boolean visible(float[] planes, float[] s, float margin) {
        for (int p = 0; p < 6; p++) {
            float d = planes[p * 4] * s[0] + planes[p * 4 + 1] * s[1] + planes[p * 4 + 2] * s[2] + planes[p * 4 + 3];
            if (d < -(s[3] + margin)) {
                return false;
            }
        }
        return true;
    }
}
