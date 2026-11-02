package driftengine.selftest;

import driftengine.scene.mesh.MeshData;
import driftengine.world.map.Zone;
import driftengine.world.map.ZoneMap;
import driftengine.world.map.Zones;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 맵 미리보기 (GPU 없이): 지형 조각 + 고정 메시를 원근 투영해 z 버퍼로 그린 PNG.
 * 정점 색 + 해 방향 램버트 + 안개만 쓴다 (실제 셰이더와 다름, 배치 확인용).
 * 실행: MapShot 지역 출력.png 눈x 눈y 눈z 보는x 보는y 보는z [가로 세로]
 */
public final class MapShot {

    public static void main(String[] a) throws Exception {
        Zone zone = Zone.valueOf(a[0]);
        ZoneMap m = Zones.build(zone);
        // 높이는 그 자리 지형 위로 (m)
        float ex = Float.parseFloat(a[2]), ez = Float.parseFloat(a[4]), ax = Float.parseFloat(a[5]), az = Float.parseFloat(a[7]);
        float[] eye = {ex, m.terrain.height(ex, ez) + Float.parseFloat(a[3]), ez};
        float[] at = {ax, m.terrain.height(ax, az) + Float.parseFloat(a[6]), az};
        int w = a.length > 8 ? Integer.parseInt(a[8]) : 1280, h = a.length > 9 ? Integer.parseInt(a[9]) : 720;
        List<MeshData> meshes = new ArrayList<>();
        for (int i = 0; i < m.chunks.size(); i++) {
            ZoneMap.Chunk c = m.chunks.get(i);
            float d = (float) Math.hypot(c.cx() - eye[0], c.cz() - eye[2]) - c.radius();
            meshes.add(d < m.detailDistance ? m.detailed(i) : m.coarse(i));
        }
        for (ZoneMap.Piece p : m.pieces) {
            meshes.add(p.mesh());
        }
        render(meshes, eye, at, w, h, new File(a[1]));
    }

    static void render(List<MeshData> meshes, float[] eye, float[] at, int w, int h, File out) throws Exception {
        float[] f = norm(sub(at, eye));
        float[] r = norm(cross(f, new float[]{0, 1, 0}));
        float[] u = cross(r, f);
        float fov = (float) Math.toRadians(60);
        float sy = (float) (1 / Math.tan(fov / 2)), sx = sy * h / w;
        float[] zb = new float[w * h];
        java.util.Arrays.fill(zb, Float.MAX_VALUE);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            float t = y / (float) h;
            int sky = rgb(0.45f + 0.3f * t, 0.62f + 0.2f * t, 0.85f + 0.1f * t);
            for (int x = 0; x < w; x++) {
                px[y * w + x] = sky;
            }
        }
        float[] sun = norm(new float[]{0.45f, 0.75f, 0.35f});
        for (MeshData m : meshes) {
            float[] v = m.vertices();
            int[] idx = m.indices();
            int st = 14;
            for (int k = 0; k < idx.length; k += 3) {
                float[][] p = new float[3][];
                float[][] cs = new float[3][];
                boolean ok = true;
                float cr = 0, cg = 0, cb = 0;
                for (int q = 0; q < 3; q++) {
                    int o = idx[k + q] * st;
                    float[] wv = {v[o] - eye[0], v[o + 1] - eye[1], v[o + 2] - eye[2]};
                    float zc = dot(wv, f);
                    if (zc < 0.5f) {
                        ok = false;
                        break;
                    }
                    p[q] = new float[]{(dot(wv, r) / zc * sx * 0.5f + 0.5f) * w, (0.5f - dot(wv, u) / zc * sy * 0.5f) * h, zc};
                    cr += v[o + 6];
                    cg += v[o + 7];
                    cb += v[o + 8];
                }
                if (!ok) {
                    continue;
                }
                int o0 = idx[k] * st, o1 = idx[k + 1] * st, o2 = idx[k + 2] * st;
                float[] e1 = {v[o1] - v[o0], v[o1 + 1] - v[o0 + 1], v[o1 + 2] - v[o0 + 2]};
                float[] e2 = {v[o2] - v[o0], v[o2 + 1] - v[o0 + 1], v[o2 + 2] - v[o0 + 2]};
                float[] n = norm(cross(e1, e2));
                float lam = Math.abs(dot(n, sun)) * 0.8f + 0.3f;
                float dist = p[0][2];
                float fog = (float) Math.exp(-dist / 2500f);
                float em = v[o0 + 12];
                float rr = cr / 3 * (lam + em), gg = cg / 3 * (lam + em), bb = cb / 3 * (lam + em);
                rr = rr * fog + 0.6f * (1 - fog);
                gg = gg * fog + 0.7f * (1 - fog);
                bb = bb * fog + 0.85f * (1 - fog);
                int col = rgb(tone(rr), tone(gg), tone(bb));
                fill(p, zb, px, w, h, col);
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(img, "png", out);
    }

    static float tone(float c) {
        c = c * 2.2f / (1 + c * 2.2f);
        return (float) Math.pow(Math.max(0, c), 1 / 1.4);
    }

    static void fill(float[][] p, float[] zb, int[] px, int w, int h, int col) {
        int minX = Math.max(0, (int) Math.floor(Math.min(p[0][0], Math.min(p[1][0], p[2][0]))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(p[0][0], Math.max(p[1][0], p[2][0]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(p[0][1], Math.min(p[1][1], p[2][1]))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(p[0][1], Math.max(p[1][1], p[2][1]))));
        if (minX > maxX || minY > maxY) {
            return;
        }
        float area = (p[1][0] - p[0][0]) * (p[2][1] - p[0][1]) - (p[2][0] - p[0][0]) * (p[1][1] - p[0][1]);
        if (Math.abs(area) < 1e-6f) {
            return;
        }
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float cx = x + 0.5f, cy = y + 0.5f;
                float w0 = ((p[1][0] - cx) * (p[2][1] - cy) - (p[2][0] - cx) * (p[1][1] - cy)) / area;
                float w1 = ((p[2][0] - cx) * (p[0][1] - cy) - (p[0][0] - cx) * (p[2][1] - cy)) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) {
                    continue;
                }
                float iz = w0 / p[0][2] + w1 / p[1][2] + w2 / p[2][2];
                float z = 1 / iz;
                int at = y * w + x;
                if (z < zb[at]) {
                    zb[at] = z;
                    px[at] = col;
                }
            }
        }
    }

    static int rgb(float r, float g, float b) {
        return (c8(r) << 16) | (c8(g) << 8) | c8(b);
    }

    static int c8(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    static float[] sub(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static float[] norm(float[] a) {
        float l = (float) Math.sqrt(dot(a, a));
        return new float[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
