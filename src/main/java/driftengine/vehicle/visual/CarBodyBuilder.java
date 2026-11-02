package driftengine.vehicle.visual;

import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.MeshData;
import driftengine.vehicle.CarSpec;

import java.util.ArrayList;
import java.util.List;

import static driftengine.vehicle.visual.MeshTools.lerp;
import static driftengine.vehicle.visual.MeshTools.smoothstep;

/**
 * 차체 생성기: CarStyle 수치로 차체 메시를 만든다 (차체 로컬 좌표 = 물리 상자 중심이 원점, -Z 앞, 땅 = -정지 높이).
 * <p>
 * 만드는 법: 차 길이 방향으로 단면(링)을 여러 개 세우고 이어 붙인다(로프트).
 * 단면 하나 = 아래 → 옆면 → 어깨 → 옆유리 → 지붕 모서리 → 지붕 가운데 (오른쪽 반, 왼쪽은 거울).
 * 보닛·트렁크 구간에서는 유리 높이가 0 이 되어 어깨가 곧 보닛 면이 된다.
 * 바퀴 아치: 바퀴 위 단면의 아래쪽 점들을 아치 원 위로 밀어 올려 구멍을 만든다.
 * 재질(도장, 유리, 라이트, 그릴, 바닥)은 사각형마다 위치로 정한다 → 정점은 사각형마다 따로 두어 경계가 또렷하다.
 * 법선은 단면 격자 전체에서 이웃 점 차이로 구해 매끈하게 보인다.
 */
public final class CarBodyBuilder {

    static final int SECTIONS = 84;
    /** 오른쪽 반 단면의 점 구간: 점 0~10 아래·옆·어깨, 10~11 유리 테두리, 11~13 옆유리, 13~15 지붕 모서리, 15~17 지붕 */
    static final int SEG_SIDE_END = 11;
    static final int SEG_GLASS_END = 13;

    private static final Vec3 BLACK = new Vec3(0.02f, 0.02f, 0.022f);
    private static final Vec3 GLASS_TINT = new Vec3(0.015f, 0.02f, 0.025f);
    private static final Vec3 LIGHT_WHITE = new Vec3(1.0f, 0.96f, 0.88f);
    private static final Vec3 TAIL_RED = new Vec3(0.9f, 0.02f, 0.015f);
    private static final Vec3 CARBON_COLOR = new Vec3(0.025f, 0.025f, 0.028f);
    private static final Vec3 CHROME_COLOR = new Vec3(0.85f, 0.85f, 0.87f);

    private final CarSpec spec;
    private final CarStyle st;
    private final BodyKit kit;
    private final float ground;
    private final float zFront;
    private final float zRear;
    private final float flareTotal;

    private CarBodyBuilder(CarSpec spec, CarStyle style) {
        this.spec = spec;
        this.st = style;
        this.kit = style.kit();
        this.ground = -spec.restHeight();
        this.zFront = -style.length() / 2;
        this.zRear = style.length() / 2;
        this.flareTotal = style.flare() + kit.widebody() * 0.065f;
    }

    public static MeshData build(CarSpec spec, CarStyle style) {
        return new CarBodyBuilder(spec, style).build();
    }

    // ------------------------------------------------------------------ 옆모습·윗모습 곡선 (u: 앞 0 ~ 뒤 1)

    float topH(float u) {
        if (u <= st.cowlU()) {
            float t = u / st.cowlU();
            return lerp(st.noseH(), st.hoodH(), 1 - (1 - t) * (1 - t));
        }
        if (u <= st.roofStartU()) {
            float t = (u - st.cowlU()) / (st.roofStartU() - st.cowlU());
            return lerp(st.hoodH(), st.roofH(), (float) (1 - Math.pow(1 - t, 1.7)));
        }
        if (u <= st.roofEndU()) {
            float t = (u - st.roofStartU()) / (st.roofEndU() - st.roofStartU());
            return st.roofH() + 0.012f * (float) Math.sin(Math.PI * t);
        }
        if (u <= st.deckU()) {
            float t = (u - st.roofEndU()) / (st.deckU() - st.roofEndU());
            return lerp(st.roofH(), st.deckH(), (float) Math.pow(t, 1.5));
        }
        float t = (u - st.deckU()) / (1 - st.deckU());
        return lerp(st.deckH(), st.tailH(), t);
    }

    float botH(float u) {
        return st.sillH() + 0.03f * (1 - smoothstep(0, 0.12f, u)) + 0.09f * smoothstep(0.86f, 1, u);
    }

    float beltH(float u) {
        return st.beltH() + (u - 0.5f) * 0.05f;
    }

    float z(float u) {
        return lerp(zFront, zRear, u);
    }

    /** 반 폭: 앞뒤로 갈수록 좁아지고, 바퀴 위는 펜더가 불룩 */
    float halfWidth(float u) {
        float w = st.width() / 2 - st.flare();
        float taper = (0.80f + 0.20f * (float) Math.sqrt(smoothstep(0, 0.16f, u)))
                * (0.86f + 0.14f * (float) Math.sqrt(1 - smoothstep(0.86f, 1, u)));
        float zz = z(u);
        float bulge = 0;
        for (float axle : new float[]{spec.frontAxleZ(), spec.rearAxleZ()}) {
            float d = (zz - axle) / 0.62f;
            bulge = Math.max(bulge, (float) Math.exp(-d * d));
        }
        float result = w * taper + flareTotal * bulge;
        // 펜더가 타이어를 덮도록
        for (int axleIndex = 0; axleIndex < 2; axleIndex++) {
            float axle = axleIndex == 0 ? spec.frontAxleZ() : spec.rearAxleZ();
            float track = axleIndex == 0 ? spec.trackFront() : spec.trackRear();
            float tire = axleIndex == 0 ? st.tireWidthF() : st.tireWidthR();
            float d = Math.abs(zz - axle);
            if (d < spec.wheelRadius() + 0.1f) {
                result = Math.max(result, track / 2 + tire / 2 + 0.012f);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 단면

    /** u 위치 단면의 오른쪽 반 점들 (땅 높이 반영한 로컬 좌표, x ≥ 0) */
    List<Vec3> ring(float u, float zz, float shrink) {
        float w = halfWidth(u);
        float yb = ground + botH(u);
        float top = topH(u);
        float sh = Math.min(top, beltH(u));
        float ysh = ground + sh;
        float ytop = ground + top;
        float gh = ytop - ysh;
        float rb = 0.05f;
        float rs = Math.min(0.15f, (ysh - yb) * 0.32f);
        float wg0 = w - rs;
        float wroof = lerp(wg0, w * st.cabinWidth(), smoothstep(0, 0.12f, gh));
        float rr = Math.min(0.075f, gh * 0.4f);
        float crown = 0.018f;

        List<Vec3> pts = new ArrayList<>();
        pts.add(new Vec3(0, yb, zz));                               // 0 바닥 가운데
        pts.add(new Vec3(w * 0.55f, yb, zz));                       // 1
        pts.add(new Vec3(w - rb, yb, zz));                          // 2
        pts.add(new Vec3(w - rb + rb * 0.707f, yb + rb - rb * 0.707f, zz)); // 3 아래 모서리
        pts.add(new Vec3(w, yb + rb, zz));                          // 4
        pts.add(new Vec3(w + 0.012f, lerp(yb, ysh, 0.45f), zz));    // 5 옆면 (살짝 볼록)
        pts.add(new Vec3(w + 0.006f, lerp(yb, ysh, 0.75f), zz));    // 6
        pts.add(new Vec3(w, ysh - rs, zz));                         // 7 어깨 시작
        pts.add(new Vec3(w - rs + rs * 0.866f, ysh - rs + rs * 0.5f, zz)); // 8
        pts.add(new Vec3(w - rs + rs * 0.5f, ysh - rs + rs * 0.866f, zz)); // 9
        pts.add(new Vec3(wg0, ysh, zz));                            // 10 어깨 끝 = 유리 아랫선
        pts.add(new Vec3(lerp(wg0, wroof, 0.08f), ysh + gh * 0.04f, zz)); // 11 유리 고무 테두리
        pts.add(new Vec3(lerp(wg0, wroof, 0.55f), ysh + gh * 0.55f, zz)); // 12 옆유리 가운데
        pts.add(new Vec3(wroof, ytop - rr, zz));                    // 13 지붕 모서리 시작
        pts.add(new Vec3(wroof - rr + rr * 0.707f, ytop - rr + rr * 0.707f, zz)); // 14
        pts.add(new Vec3(wroof - rr, ytop, zz));                    // 15
        pts.add(new Vec3(wroof * 0.5f, ytop + crown * 0.75f, zz));  // 16
        pts.add(new Vec3(0, ytop + crown, zz));                     // 17 지붕 가운데

        // 바퀴 아치: 바퀴 위쪽 원보다 아래에 있는 바깥쪽 점을 원 위로 밀어 올린다
        for (int axleIndex = 0; axleIndex < 2; axleIndex++) {
            float axle = axleIndex == 0 ? spec.frontAxleZ() : spec.rearAxleZ();
            float track = axleIndex == 0 ? spec.trackFront() : spec.trackRear();
            float tire = axleIndex == 0 ? st.tireWidthF() : st.tireWidthR();
            float ra = spec.wheelRadius() + 0.045f + kit.widebody() * 0.015f;
            float dz = zz - axle;
            if (Math.abs(dz) < ra) {
                float archTop = ground + spec.wheelRadius() + (float) Math.sqrt(ra * ra - dz * dz);
                float innerX = track / 2 - tire / 2 - 0.05f;
                // 바깥쪽 점은 모두 아치 위로: 보닛이 바퀴보다 낮은 차(프로토타입)는 펜더가 보닛 위로 솟는다
                for (int k = 1; k < pts.size(); k++) {
                    Vec3 p = pts.get(k);
                    if (p.x() > innerX && p.y() < archTop) {
                        pts.set(k, new Vec3(p.x(), archTop, p.z()));
                    }
                }
            }
        }
        if (shrink < 1) {
            // 앞뒤 끝 둥글게: 단면을 가운데 쪽으로 줄인다
            float cy = (yb + Math.max(ysh, ytop)) / 2;
            for (int k = 0; k < pts.size(); k++) {
                Vec3 p = pts.get(k);
                pts.set(k, new Vec3(p.x() * lerp(1, shrink, 0.6f), cy + (p.y() - cy) * shrink, p.z()));
            }
        }
        return pts;
    }

    // ------------------------------------------------------------------ 만들기

    private MeshData build() {
        // 단면 위치: 앞끝 둥근 부분 2개 + 본체 + 뒤끝 둥근 부분 2개
        List<float[]> sections = new ArrayList<>(); // {u, z, shrink}
        float uEnd = 0.04f / st.length();
        sections.add(new float[]{0, zFront, 0.80f});
        sections.add(new float[]{0, zFront + 0.018f, 0.94f});
        for (int i = 0; i < SECTIONS; i++) {
            float u = lerp(uEnd, 1 - uEnd, i / (float) (SECTIONS - 1));
            sections.add(new float[]{u, z(u), 1});
        }
        sections.add(new float[]{1, zRear - 0.018f, 0.94f});
        sections.add(new float[]{1, zRear, 0.80f});

        int n = sections.size();
        Vec3[][] p = new Vec3[n][];
        for (int i = 0; i < n; i++) {
            float[] s = sections.get(i);
            p[i] = ring(s[0], s[1], s[2]).toArray(new Vec3[0]);
        }
        int k = p[0].length;
        Vec3[][] nrm = normals(p);

        MeshBuilder b = new MeshBuilder("car-" + spec.id());
        for (int side = 0; side < 2; side++) {
            float mirror = side == 0 ? 1 : -1;
            for (int i = 0; i + 1 < n; i++) {
                for (int j = 0; j + 1 < k; j++) {
                    Vec3 a = flip(p[i][j], mirror);
                    Vec3 bb = flip(p[i + 1][j], mirror);
                    Vec3 c = flip(p[i + 1][j + 1], mirror);
                    Vec3 d = flip(p[i][j + 1], mirror);
                    if (c.sub(a).cross(d.sub(bb)).length() < 1e-8f) {
                        continue; // 넓이 0 (보닛 구간에서 유리 높이 0 등)
                    }
                    Vec3 center = a.add(bb).add(c).add(d).scale(0.25f);
                    float u = (sections.get(i)[0] + sections.get(i + 1)[0]) / 2;
                    Surface surf = surfaceAt(u, center, j, i, sections);
                    b.material(surf.material);
                    Vec3 na = flipN(nrm[i][j], mirror);
                    Vec3 nb = flipN(nrm[i + 1][j], mirror);
                    Vec3 nc = flipN(nrm[i + 1][j + 1], mirror);
                    Vec3 nd = flipN(nrm[i][j + 1], mirror);
                    int ia = b.vertex(a, na, surf.color);
                    int ib = b.vertex(bb, nb, surf.color);
                    int ic = b.vertex(c, nc, surf.color);
                    int id = b.vertex(d, nd, surf.color);
                    MeshTools.orientedQuad(b, new int[]{ia, ib, ic, id}, na.add(nb).add(nc).add(nd));
                }
            }
        }
        cap(b, p[0], new Vec3(0, 0, -1), 0, sections);
        cap(b, p[n - 1], new Vec3(0, 0, 1), 1, sections);
        addDetails(b);
        addKit(b);
        return b.build();
    }

    private static Vec3 flip(Vec3 v, float mirror) {
        return new Vec3(v.x() * mirror, v.y(), v.z());
    }

    private static Vec3 flipN(Vec3 v, float mirror) {
        return new Vec3(v.x() * mirror, v.y(), v.z());
    }

    /** 격자 법선: 길이 방향 차이 × 단면 방향 차이, 바깥(단면 중심에서 먼 쪽)을 향하게 */
    private static Vec3[][] normals(Vec3[][] p) {
        int n = p.length;
        int k = p[0].length;
        Vec3[][] out = new Vec3[n][k];
        for (int i = 0; i < n; i++) {
            Vec3 centroid = Vec3.ZERO;
            for (Vec3 q : p[i]) {
                centroid = centroid.add(new Vec3(0, q.y(), q.z()));
            }
            centroid = centroid.scale(1f / k);
            for (int j = 0; j < k; j++) {
                Vec3 du = p[Math.min(n - 1, i + 1)][j].sub(p[Math.max(0, i - 1)][j]);
                Vec3 dk = neighbor(p[i], j, 1).sub(neighbor(p[i], j, -1));
                Vec3 nn = dk.cross(du);
                if (nn.length() < 1e-9f) {
                    nn = p[i][j].sub(centroid);
                }
                nn = nn.normalize();
                Vec3 out0 = p[i][j].sub(centroid);
                if (i == 0) {
                    out0 = out0.add(new Vec3(0, 0, -0.5f));
                } else if (i == n - 1) {
                    out0 = out0.add(new Vec3(0, 0, 0.5f));
                }
                if (nn.dot(out0) < 0) {
                    nn = nn.scale(-1);
                }
                out[i][j] = nn;
            }
        }
        return out;
    }

    /** 단면 j 의 이웃 점 (가운데 끝은 거울 쪽 점), 같은 점(아치로 눌린 점)은 건너뜀 */
    private static Vec3 neighbor(Vec3[] ring, int j, int dir) {
        int k = ring.length;
        Vec3 self = ring[j];
        for (int step = 1; step < 4; step++) {
            int idx = j + dir * step;
            Vec3 q;
            if (idx < 0) {
                Vec3 m = ring[-idx];
                q = new Vec3(-m.x(), m.y(), m.z());
            } else if (idx >= k) {
                Vec3 m = ring[2 * (k - 1) - idx];
                q = new Vec3(-m.x(), m.y(), m.z());
            } else {
                q = ring[idx];
            }
            if (q.sub(self).length() > 1e-4f) {
                return q;
            }
        }
        return self;
    }

    private record Surface(Material material, Vec3 color) {
    }

    /** 사각형 하나의 재질: 위치(앞뒤 u, 높이, 폭)와 단면 구간(j)으로 정한다 */
    private Surface surfaceAt(float u, Vec3 c, int j, int i, List<float[]> sections) {
        Material paint = st.metallicPaint() ? Material.PAINT_METALLIC : Material.PAINT_SOLID;
        Surface body = new Surface(paint, st.color());
        Surface black = new Surface(Material.PLASTIC_BLACK, BLACK);
        Surface glass = new Surface(Material.GLASS, GLASS_TINT);
        float w = halfWidth(u);
        float yb = ground + botH(u);
        float ysh = ground + Math.min(topH(u), beltH(u));
        float ax = Math.abs(c.x());

        // 바닥
        if (j == 0 || (j == 1 && c.y() < yb + 0.02f)) {
            return black;
        }
        // 아치 안쪽 (밀어 올린 점들 사이)
        if (j <= 6 && isArchLiner(c)) {
            return black;
        }
        boolean front = c.z() < zFront + 0.11f;
        boolean rear = c.z() > zRear - 0.14f;
        // 라이트
        if (front && c.y() > ysh - 0.11f && c.y() < ysh - 0.02f && ax > 0.40f * w && ax < 0.93f * w) {
            boolean on = switch (st.headlight()) {
                case 1 -> hypot(ax - 0.70f * w, c.y() - (ysh - 0.075f)) < 0.075f;
                case 2 -> ((int) Math.floor(ax / 0.045f) + (int) Math.floor((c.y() - ground) / 0.045f)) % 2 == 0;
                default -> c.y() > ysh - 0.075f;
            };
            if (on) {
                return new Surface(Material.LIGHT, LIGHT_WHITE);
            }
            return black;
        }
        if (rear && c.y() > ysh - 0.16f && c.y() < ysh - 0.03f && ax > 0.22f * w && ax < 0.96f * w) {
            boolean on = st.taillight() == 1
                    ? hypot(ax - 0.52f * w, c.y() - (ysh - 0.095f)) < 0.055f || hypot(ax - 0.80f * w, c.y() - (ysh - 0.095f)) < 0.055f
                    : true;
            return on ? new Surface(Material.TAIL_LIGHT, TAIL_RED) : black;
        }
        // 그릴 (앞 아래 가운데), 뒤 아래 (디퓨저 자리)
        if (front && c.y() > yb + 0.05f && c.y() < yb + 0.22f && ax < 0.64f * w) {
            return black;
        }
        if (rear && c.y() < yb + 0.07f) {
            return black;
        }
        // 유리: 옆유리(j 구간 11~12)와 앞·뒷유리(지붕 구간 중 앞유리/뒷유리 위치)
        boolean cabin = u > st.cowlU() && u < st.deckU();
        if (j >= SEG_SIDE_END - 1 && j < SEG_GLASS_END && cabin) {
            float glassEnd = st.roofEndU() + 0.3f * (st.deckU() - st.roofEndU());
            if (j == SEG_SIDE_END - 1) {
                return black; // 유리 테두리 고무 (점 10 → 11)
            }
            if (u > st.cowlU() + 0.035f && u < glassEnd) {
                if (st.bPillar() && Math.abs(u - (st.roofStartU() + st.roofEndU()) / 2) < 0.012f) {
                    return black;
                }
                return glass;
            }
            return body;
        }
        if (j >= SEG_GLASS_END + 2) {
            if (u > st.cowlU() + 0.01f && u < st.roofStartU() - 0.005f && ax < w * st.cabinWidth() * 0.96f) {
                return glass; // 앞유리
            }
            if (u > st.roofEndU() + 0.01f && u < st.deckU() - 0.01f && ax < w * st.cabinWidth() * 0.96f) {
                return glass; // 뒷유리
            }
        }
        return body;
    }

    private boolean isArchLiner(Vec3 c) {
        for (int axleIndex = 0; axleIndex < 2; axleIndex++) {
            float axle = axleIndex == 0 ? spec.frontAxleZ() : spec.rearAxleZ();
            float ra = spec.wheelRadius() + 0.045f + kit.widebody() * 0.015f;
            float dz = c.z() - axle;
            if (Math.abs(dz) < ra) {
                float archTop = ground + spec.wheelRadius() + (float) Math.sqrt(ra * ra - dz * dz);
                if (Math.abs(c.y() - archTop) < 0.025f) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float hypot(float a, float b) {
        return (float) Math.sqrt(a * a + b * b);
    }

    /**
     * 앞뒤 끝 막기: 마지막 단면을 가운데로 4단계 줄인 동심 고리들로 채운다.
     * 사각형마다 재질을 정하므로 앞면의 라이트·그릴, 뒷면의 테일램프가 끝면에도 그려진다.
     */
    private void cap(MeshBuilder b, Vec3[] ring, Vec3 outward, int end, List<float[]> sections) {
        Vec3 center = Vec3.ZERO;
        for (Vec3 q : ring) {
            center = center.add(new Vec3(0, q.y(), q.z()));
        }
        center = center.scale(1f / ring.length);
        int levels = 5;
        float u = end == 0 ? 0 : 1;
        for (int side = 0; side < 2; side++) {
            float m = side == 0 ? 1 : -1;
            for (int lv = 0; lv < levels; lv++) {
                float t0 = 1 - lv / (float) levels;
                float t1 = 1 - (lv + 1) / (float) levels;
                for (int j = 0; j + 1 < ring.length; j++) {
                    Vec3 a = shrinkTo(flip(ring[j], m), center, t0, outward, lv);
                    Vec3 bb = shrinkTo(flip(ring[j + 1], m), center, t0, outward, lv);
                    Vec3 c = shrinkTo(flip(ring[j + 1], m), center, t1, outward, lv + 1);
                    Vec3 d = shrinkTo(flip(ring[j], m), center, t1, outward, lv + 1);
                    if (c.sub(a).cross(d.sub(bb)).length() < 1e-9f && a.sub(bb).length() < 1e-5f) {
                        continue;
                    }
                    Vec3 cc = a.add(bb).add(c).add(d).scale(0.25f);
                    Surface surf = surfaceAt(u, cc, 5, 0, sections);
                    b.material(surf.material);
                    int i0 = b.vertex(a, outward, surf.color);
                    int i1 = b.vertex(bb, outward, surf.color);
                    int i2 = b.vertex(c, outward, surf.color);
                    int i3 = b.vertex(d, outward, surf.color);
                    MeshTools.orientedQuad(b, new int[]{i0, i1, i2, i3}, outward);
                }
            }
        }
    }

    /** 끝면 안쪽 고리: 가운데 쪽으로 t 만큼, 안쪽일수록 살짝 앞(뒤)으로 볼록 */
    private static Vec3 shrinkTo(Vec3 p, Vec3 center, float t, Vec3 outward, int level) {
        Vec3 q = new Vec3(p.x() * t, center.y() + (p.y() - center.y()) * t, p.z());
        return q.add(outward.scale(0.004f * level));
    }

    // ------------------------------------------------------------------ 작은 부품

    float uAt(float zz) {
        return (zz - zFront) / (zRear - zFront);
    }

    private void addDetails(MeshBuilder b) {
        // 사이드 미러: 앞유리 아래 끝 옆
        float mu = st.cowlU() + 0.05f;
        float mw = halfWidth(mu);
        float my = ground + Math.min(topH(mu), beltH(mu)) + 0.07f;
        for (float s : new float[]{1, -1}) {
            float mx = mw - Math.min(0.15f, 0.3f * (my - ground - botH(mu))) + 0.07f;
            MeshTools.box(b, new Vec3(s * mx, my, z(mu)), new Vec3(0.07f, 0.045f, 0.075f), 0,
                    st.metallicPaint() ? Material.PAINT_METALLIC : Material.PAINT_SOLID, st.color());
            MeshTools.box(b, new Vec3(s * (mx - 0.05f), my - 0.02f, z(mu) + 0.03f), new Vec3(0.03f, 0.012f, 0.03f), 0,
                    Material.PLASTIC_BLACK, BLACK);
        }
        // 배기구 (전기차는 없음)
        if (!spec.electric()) {
            float ey = ground + botH(0.98f) + 0.06f;
            float ez = zRear - 0.02f;
            float[] xs = spec.engine().cylinders() >= 6 ? new float[]{-0.55f, -0.42f, 0.42f, 0.55f}
                    : spec.engine().type() == driftengine.vehicle.EngineSpec.EngineType.ROTARY ? new float[]{-0.08f, 0.08f}
                    : new float[]{-0.45f, 0.45f};
            for (float x : xs) {
                MeshTools.cylinderZ(b, new Vec3(x * st.width() / 2, ey, ez), 0.042f, 0.16f, 14, Material.CHROME, CHROME_COLOR);
                MeshTools.cylinderZ(b, new Vec3(x * st.width() / 2, ey, ez + 0.075f), 0.030f, 0.012f, 14, Material.PLASTIC_BLACK, BLACK);
            }
        }
        // 번호판 자리 (뒤)
        float py = ground + botH(1) + 0.16f;
        MeshTools.box(b, new Vec3(0, py, zRear + 0.02f), new Vec3(0.26f, 0.06f, 0.008f), 0, Material.PLASTIC_BLACK,
                new Vec3(0.55f, 0.55f, 0.52f));
    }

    private void addKit(MeshBuilder b) {
        Material paint = st.metallicPaint() ? Material.PAINT_METALLIC : Material.PAINT_SOLID;
        float w = st.width() / 2;
        // 앞 립 / 에어로
        if (kit.front() >= 1) {
            float y = ground + botH(0) - 0.012f;
            MeshTools.box(b, new Vec3(0, y, zFront + 0.12f), new Vec3(w * 0.86f, 0.012f, 0.13f), 0, Material.CARBON, CARBON_COLOR);
        }
        if (kit.front() >= 2) {
            for (float s : new float[]{1, -1}) {
                MeshTools.box(b, new Vec3(s * w * 0.86f, ground + botH(0) + 0.10f, zFront + 0.05f),
                        new Vec3(0.09f, 0.006f, 0.05f), -0.25f, Material.CARBON, CARBON_COLOR);
            }
        }
        // 사이드 스커트
        if (kit.side() >= 1) {
            float z0 = spec.frontAxleZ() + spec.wheelRadius() + 0.08f;
            float z1 = spec.rearAxleZ() - spec.wheelRadius() - 0.08f;
            float u = uAt((z0 + z1) / 2);
            float sw = halfWidth(u);
            for (float s : new float[]{1, -1}) {
                MeshTools.box(b, new Vec3(s * (sw - 0.01f), ground + botH(u) - 0.005f, (z0 + z1) / 2),
                        new Vec3(0.04f, 0.035f, (z1 - z0) / 2), 0, Material.CARBON, CARBON_COLOR);
            }
        }
        // 디퓨저
        if (kit.rear() >= 1) {
            float y = ground + botH(1) - 0.01f;
            MeshTools.box(b, new Vec3(0, y, zRear - 0.12f), new Vec3(w * 0.72f, 0.01f, 0.16f), 0.18f, Material.CARBON, CARBON_COLOR);
            for (float x : new float[]{-0.45f, -0.15f, 0.15f, 0.45f}) {
                MeshTools.box(b, new Vec3(x * w, y + 0.035f, zRear - 0.1f), new Vec3(0.006f, 0.04f, 0.13f), 0.18f,
                        Material.CARBON, CARBON_COLOR);
            }
        }
        // 스포일러
        float deckTop = ground + topH(0.97f);
        if (kit.spoiler() == 1) {
            MeshTools.box(b, new Vec3(0, deckTop + 0.025f, zRear - 0.07f), new Vec3(w * 0.80f, 0.012f, 0.07f), -0.30f, paint, st.color());
        } else if (kit.spoiler() == 2) {
            float wy = deckTop + 0.30f;
            float wz = zRear - 0.16f;
            for (float s : new float[]{1, -1}) {
                MeshTools.box(b, new Vec3(s * w * 0.42f, deckTop + 0.15f, wz + 0.02f), new Vec3(0.012f, 0.15f, 0.06f), 0,
                        Material.CARBON, CARBON_COLOR);
                MeshTools.box(b, new Vec3(s * w * 0.96f, wy, wz), new Vec3(0.006f, 0.08f, 0.17f), 0, Material.CARBON, CARBON_COLOR);
            }
            MeshTools.box(b, new Vec3(0, wy, wz), new Vec3(w * 0.95f, 0.012f, 0.15f), 0.14f, Material.CARBON, CARBON_COLOR);
        }
    }
}
