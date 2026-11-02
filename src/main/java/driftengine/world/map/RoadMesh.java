package driftengine.world.map;

import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.MeshData;
import driftengine.world.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * 도로 면 메시: 지형(물리와 같은 높이) 위 3cm 에 얹는 띠. 옆으로 여러 줄(차선·연석)로 나누고
 * 줄마다 색이 바뀌는 곳에 정점을 겹쳐 넣어 선이 번지지 않게 한다. 셰이더(SURFACE_ROAD)가
 * 어두운 정점 색은 아스팔트 무늬로, 밝은 색은 페인트로 그린다.
 */
public final class RoadMesh {

    public static final float LIFT = 0.03f;
    /** 조각 길이 (m): 화면 밖 조각은 그리지 않는다 */
    public static final float PIECE = 120f;

    static final Vec3 ASPHALT = new Vec3(0.06f, 0.06f, 0.065f);
    static final Vec3 ASPHALT_OLD = new Vec3(0.072f, 0.071f, 0.07f);
    static final Vec3 WHITE = new Vec3(0.78f, 0.78f, 0.74f);
    static final Vec3 YELLOW = new Vec3(0.85f, 0.62f, 0.08f);
    static final Vec3 RED = new Vec3(0.72f, 0.05f, 0.04f);

    /** 옆 [lat0, lat1] 줄. 색 함수가 null 을 주면 그 구간은 비운다 */
    interface ColorAt {
        Vec3 at(float s);
    }

    record Column(float lat0, float lat1, ColorAt color) {
    }

    private RoadMesh() {
    }

    /** 이 도로의 줄 구성 */
    static List<Column> columns(Road road, float startLine) {
        RoadPath p = road.path();
        float hw = road.halfWidth();
        List<Column> c = new ArrayList<>();
        if (road.style() == Road.Style.CIRCUIT) {
            ColorAt asphalt = s -> Math.abs(wrap(s - startLine, p)) < 0.35f && startLine >= 0 ? WHITE : ASPHALT;
            // 연석: 굽은 곳 (반지름 160m 아래) 바깥 1.2m, 빨강·흰 2.5m 무늬
            ColorAt curb = s -> {
                float k = Math.abs(p.curvature(p.index(s)));
                if (k < 1 / 160f) {
                    return null;
                }
                return ((int) Math.floor(s / 2.5f) & 1) == 0 ? RED : WHITE;
            };
            c.add(new Column(-hw - 1.2f, -hw, curb));
            c.add(new Column(-hw, -hw + 0.25f, s -> WHITE));
            lanes(c, -hw + 0.25f, hw - 0.25f, asphalt);
            c.add(new Column(hw - 0.25f, hw, s -> WHITE));
            c.add(new Column(hw, hw + 1.2f, curb));
        } else {
            ColorAt asphalt = s -> ASPHALT_OLD;
            c.add(new Column(-hw, -hw + 0.2f, asphalt));
            c.add(new Column(-hw + 0.2f, -hw + 0.35f, s -> WHITE));
            lanes(c, -hw + 0.35f, -0.2f, asphalt);
            c.add(new Column(-0.2f, -0.08f, s -> YELLOW));
            c.add(new Column(-0.08f, 0.08f, asphalt));
            c.add(new Column(0.08f, 0.2f, s -> YELLOW));
            lanes(c, 0.2f, hw - 0.35f, asphalt);
            c.add(new Column(hw - 0.35f, hw - 0.2f, s -> WHITE));
            c.add(new Column(hw - 0.2f, hw, asphalt));
        }
        return c;
    }

    /** 넓은 아스팔트를 2m 이하 줄로 (지형 면을 잘 따라가게) */
    private static void lanes(List<Column> c, float a, float b, ColorAt color) {
        int n = Math.max(1, (int) Math.ceil((b - a) / 2f));
        for (int i = 0; i < n; i++) {
            c.add(new Column(a + (b - a) * i / n, a + (b - a) * (i + 1) / n, color));
        }
    }

    private static float wrap(float d, RoadPath p) {
        if (!p.closed()) {
            return d;
        }
        float l = p.length();
        return (float) (d - Math.rint(d / l) * l);
    }

    /**
     * 도로 면 조각들.
     *
     * @param startLine 출발선 위치 (호 길이, 서킷만. 없으면 -1)
     */
    public static List<MeshData> build(Road road, Terrain terrain, float startLine) {
        RoadPath p = road.path();
        List<Column> cols = columns(road, startLine);
        float len = p.length();
        int pieces = Math.max(1, (int) Math.ceil(len / PIECE));
        List<MeshData> out = new ArrayList<>();
        for (int k = 0; k < pieces; k++) {
            float s0 = len * k / pieces;
            float s1 = len * (k + 1) / pieces;
            MeshBuilder b = new MeshBuilder("road-" + road.name() + "-" + k);
            b.material(Material.ROAD);
            float[] rows = rows(p, s0, s1);
            for (Column col : cols) {
                strip(b, p, terrain, col, rows, s0, s1);
            }
            out.add(b.build());
        }
        return out;
    }

    /** 기본 줄 간격: 2m, 많이 굽은 곳 1m */
    private static float[] rows(RoadPath p, float s0, float s1) {
        List<Float> r = new ArrayList<>();
        float s = s0;
        while (s < s1) {
            r.add(s);
            float k = Math.abs(p.curvature(p.index(s)));
            s += k > 1 / 40f ? 1f : 2f;
        }
        r.add(s1);
        float[] a = new float[r.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = r.get(i);
        }
        return a;
    }

    /** 줄 하나: 행마다 정점 2개, 색이 바뀌는 곳(0.25m 단위로 찾음)에는 같은 자리에 정점을 겹쳐 넣는다 */
    private static void strip(MeshBuilder b, RoadPath p, Terrain t, Column col, float[] rows, float s0, float s1) {
        // 색 바뀌는 지점 찾기
        List<Float> cuts = new ArrayList<>();
        Vec3 last = col.color().at(s0 + 0.01f);
        for (float s = s0 + 0.25f; s < s1; s += 0.25f) {
            Vec3 c = col.color().at(s);
            if (!java.util.Objects.equals(c, last)) {
                cuts.add(s - 0.125f);
                last = c;
            }
        }
        // 행 + 자르는 곳 합치기
        float[] all = new float[rows.length + cuts.size()];
        int a = 0, ci = 0, n = 0;
        while (a < rows.length || ci < cuts.size()) {
            if (ci >= cuts.size() || (a < rows.length && rows[a] <= cuts.get(ci))) {
                all[n++] = rows[a++];
            } else {
                all[n++] = cuts.get(ci++);
            }
        }
        int prevL = -1, prevR = -1;
        Vec3 prevColor = null;
        for (int i = 0; i < n; i++) {
            float s = all[i];
            float mid = i + 1 < n ? (s + all[i + 1]) / 2 : s;
            Vec3 color = col.color().at(mid);
            float[] c = p.at(s);
            // 이 행에서 시작하는 칸의 색으로 정점 (앞 칸과 색이 다르면 앞 칸 끝 정점을 따로 만든다)
            int l = -1, r = -1;
            if (prevColor != null && prevL >= 0) {
                int el = vertex(b, t, c, col.lat0(), prevColor);
                int er = vertex(b, t, c, col.lat1(), prevColor);
                Props.tri(b, prevL, prevR, er);
                Props.tri(b, prevL, er, el);
                if (java.util.Objects.equals(prevColor, color)) {
                    l = el;
                    r = er;
                }
            }
            if (color != null && i + 1 < n && l < 0) {
                l = vertex(b, t, c, col.lat0(), color);
                r = vertex(b, t, c, col.lat1(), color);
            }
            prevL = color != null ? l : -1;
            prevR = color != null ? r : -1;
            prevColor = color;
        }
    }

    private static int vertex(MeshBuilder b, Terrain t, float[] c, float lat, Vec3 color) {
        // 왼쪽 = (tz, −tx)
        float x = c[0] + c[3] * lat;
        float z = c[1] - c[2] * lat;
        float y = t.height(x, z) + LIFT;
        float e = 0.6f;
        float hx = t.height(x + e, z) - t.height(x - e, z);
        float hz = t.height(x, z + e) - t.height(x, z - e);
        Vec3 n = new Vec3(-hx, 2 * e, -hz).normalize();
        return b.vertex(new Vec3(x, y, z), n, color);
    }
}
