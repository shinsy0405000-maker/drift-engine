package driftengine.world;

import driftengine.math.Vec3;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshData;
import driftengine.scene.mesh.VertexFormat;

/**
 * 높이맵 지형 한 장(청크). 정사각형 격자, 격자점마다 높이 하나.
 * <p>
 * 좌표: 격자점 (ix, iz) 의 월드 위치 = (originX + ix * spacing, height, originZ + iz * spacing).
 * 높이 배열은 행 우선: index = iz * samples + ix (Jolt HeightFieldShapeSettings 와 같은 배치).
 * <p>
 * 격자 한 칸을 삼각형 2개로 나누는 방향(대각선)은 Jolt HeightFieldShape 와 같게 (ix,iz)–(ix+1,iz+1) 로 한다.
 * 그래야 화면에 보이는 땅과 바퀴가 닿는 땅의 높이가 같다 (Jolt HeightFieldShape.cpp 의 삼각형 A/B 확인).
 * 순수 계산이라 GPU·물리 엔진 없이 테스트할 수 있다.
 */
public final class Terrain {

    private final int samples;
    private final float spacing;
    private final float originX;
    private final float originZ;
    private final float[] heights;

    /**
     * @param samples 한 변의 격자점 수 (Jolt 조건: samples / 블록 크기(2) ≥ 2 → 4 이상, 2의 거듭제곱 권장)
     * @param spacing 격자 간격 (미터)
     * @param heights samples² 개, 행 우선
     */
    public Terrain(int samples, float spacing, float originX, float originZ, float[] heights) {
        if (samples < 4) {
            throw new IllegalArgumentException("격자점 수는 4 이상: " + samples);
        }
        if (!(spacing > 0)) {
            throw new IllegalArgumentException("격자 간격은 양수: " + spacing);
        }
        if (heights.length != samples * samples) {
            throw new IllegalArgumentException("높이 개수 " + heights.length + " ≠ " + samples + "²");
        }
        this.samples = samples;
        this.spacing = spacing;
        this.originX = originX;
        this.originZ = originZ;
        this.heights = heights.clone();
    }

    /**
     * 자동 생성 지형: 가운데는 평평하고(주행 연습장), 바깥으로 갈수록 언덕이 생긴다. 같은 seed 면 항상 같은 지형.
     *
     * @param flatRadius 가운데 평지 반지름 (미터)
     * @param hillHeight 언덕 최대 높이 (미터)
     */
    public static Terrain generate(int samples, float spacing, long seed, float flatRadius, float hillHeight) {
        float size = (samples - 1) * spacing;
        float origin = -size / 2;
        float[] h = new float[samples * samples];
        for (int iz = 0; iz < samples; iz++) {
            for (int ix = 0; ix < samples; ix++) {
                float x = origin + ix * spacing;
                float z = origin + iz * spacing;
                float dist = (float) Math.sqrt(x * x + z * z);
                // 평지 → 언덕으로 부드럽게 (smoothstep)
                float t = clamp01((dist - flatRadius) / Math.max(1f, flatRadius));
                float blend = t * t * (3 - 2 * t);
                h[iz * samples + ix] = blend * hillHeight * fractalNoise(x * 0.012f, z * 0.012f, seed);
            }
        }
        return new Terrain(samples, spacing, origin, origin, h);
    }

    // ------------------------------------------------------------------ 높이

    public float heightAtGrid(int ix, int iz) {
        return heights[iz * samples + ix];
    }

    /**
     * 월드 (x, z) 의 지면 높이. 격자 밖이면 가장자리 값으로 고정.
     * Jolt 와 같은 삼각형 분할로 보간한다: 칸 안의 위치 (fx, fz) 가 fz ≥ fx 면 삼각형 A, 아니면 B.
     */
    public float height(float x, float z) {
        float gx = clamp((x - originX) / spacing, 0, samples - 1);
        float gz = clamp((z - originZ) / spacing, 0, samples - 1);
        int ix = Math.min((int) gx, samples - 2);
        int iz = Math.min((int) gz, samples - 2);
        float fx = gx - ix;
        float fz = gz - iz;
        float h00 = heightAtGrid(ix, iz);
        float h11 = heightAtGrid(ix + 1, iz + 1);
        if (fz >= fx) {
            // 삼각형 A: (0,0), (0,1), (1,1)
            float h01 = heightAtGrid(ix, iz + 1);
            return h00 + (h01 - h00) * (fz - fx) + (h11 - h00) * fx;
        }
        // 삼각형 B: (0,0), (1,1), (1,0)
        float h10 = heightAtGrid(ix + 1, iz);
        return h00 + (h10 - h00) * (fx - fz) + (h11 - h00) * fz;
    }

    /** 격자점의 법선 (주변 높이 차이로 근사, 길이 1) */
    public Vec3 normalAtGrid(int ix, int iz) {
        float hl = heightAtGrid(Math.max(ix - 1, 0), iz);
        float hr = heightAtGrid(Math.min(ix + 1, samples - 1), iz);
        float hd = heightAtGrid(ix, Math.max(iz - 1, 0));
        float hu = heightAtGrid(ix, Math.min(iz + 1, samples - 1));
        return new Vec3(hl - hr, 2 * spacing, hd - hu).normalize();
    }

    // ------------------------------------------------------------------ 그리기용 메시

    /** 지형 메시 (아스팔트 없음) */
    public MeshData toMesh() {
        return toMesh(0);
    }

    /**
     * 지형 메시. 가운데 asphaltRadius 안은 아스팔트(차선 무늬는 셰이더), 바깥은 풀, 경사가 급하면 바위.
     * 삼각형 분할은 height() 와 같다. 표면 무늬(얼룩, 풀 색 변화)는 scene.frag 가 위치로 만든다.
     */
    public MeshData toMesh(float asphaltRadius) {
        return region("terrain", asphaltRadius, 0, 0, samples - 1, samples - 1);
    }

    /**
     * 같은 지형을 cells × cells 칸짜리 조각들로 (가장자리 정점은 겹쳐서 이음매 없음).
     * 조각마다 따로 그리면 화면·그림자 단계 밖 조각을 건너뛸 수 있다.
     */
    public java.util.List<MeshData> toMeshChunks(float asphaltRadius, int cells) {
        java.util.List<MeshData> out = new java.util.ArrayList<>();
        int last = samples - 1;
        for (int z0 = 0; z0 < last; z0 += cells) {
            for (int x0 = 0; x0 < last; x0 += cells) {
                out.add(region("terrain-" + x0 / cells + "-" + z0 / cells, asphaltRadius, x0, z0,
                        Math.min(last, x0 + cells), Math.min(last, z0 + cells)));
            }
        }
        return out;
    }

    /** 격자 [x0..x1] × [z0..z1] (정점 번호, 끝 포함) */
    private MeshData region(String name, float asphaltRadius, int x0, int z0, int x1, int z1) {
        MeshBuilder b = new MeshBuilder(name);
        Vec3 grass = new Vec3(0.13f, 0.24f, 0.07f);
        Vec3 rock = new Vec3(0.30f, 0.28f, 0.25f);
        Vec3 asphalt = new Vec3(0.06f, 0.06f, 0.065f);
        int w = x1 - x0 + 1;
        int[] index = new int[w * (z1 - z0 + 1)];
        for (int iz = z0; iz <= z1; iz++) {
            for (int ix = x0; ix <= x1; ix++) {
                Vec3 n = normalAtGrid(ix, iz);
                float slope = 1 - n.y(); // 0 = 평평
                Vec3 p = position(ix, iz);
                boolean paved = Math.sqrt(p.x() * p.x() + p.z() * p.z()) < asphaltRadius;
                Vec3 color = paved ? asphalt : lerp(grass, rock, clamp01((slope - 0.08f) * 6f));
                b.material(paved ? Material.ASPHALT : Material.GROUND);
                index[(iz - z0) * w + (ix - x0)] = b.vertex(p, n, color);
            }
        }
        for (int iz = 0; iz < z1 - z0; iz++) {
            for (int ix = 0; ix < x1 - x0; ix++) {
                int p00 = index[iz * w + ix];
                int p10 = index[iz * w + ix + 1];
                int p01 = index[(iz + 1) * w + ix];
                int p11 = index[(iz + 1) * w + ix + 1];
                // 위에서 볼 때 반시계 (MeshBuilder 규약). A: (0,0)→(0,1)→(1,1), B: (0,0)→(1,1)→(1,0)
                b.triangle(p00, p01, p11);
                b.triangle(p00, p11, p10);
            }
        }
        return b.build();
    }

    /** 지형 정점 색·재질 (맵마다 다름) */
    public interface Paint {
        /** @param slope 0 = 평평, 1 = 수직  @param sample 격자점 번호 (iz * samples + ix) */
        void paint(MeshBuilder b, Vec3 position, float slope, int sample, Vec3[] colorOut);
    }

    /**
     * 조각 하나 (자세히/듬성듬성). step 칸마다 정점 하나, 가장자리에 아래로 늘어진 치마(skirt)를 달아
     * 자세한 조각과 듬성한 조각이 만나는 틈을 가린다.
     *
     * @param hidden 칸(ix, iz)을 그리지 않을지 (도로 면 아래). null = 다 그림. step 1 일 때만 쓴다
     * @param lower  듬성한 조각에서 정점 높이를 낮출지 (도로 근처: 굵은 삼각형이 도로 위로 솟지 않게). null = 그대로
     */
    public MeshData chunk(String name, int x0, int z0, int x1, int z1, int step, float skirt, Paint paint,
                          java.util.function.IntPredicate hidden, java.util.function.IntPredicate lower) {
        MeshBuilder b = new MeshBuilder(name);
        int nx = (x1 - x0 + step - 1) / step + 1;
        int nz = (z1 - z0 + step - 1) / step + 1;
        int[] index = new int[nx * nz];
        Vec3[] color = new Vec3[1];
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int ix = Math.min(x1, x0 + i * step);
                int iz = Math.min(z1, z0 + j * step);
                Vec3 n = normalAtGrid(ix, iz);
                Vec3 p = position(ix, iz);
                if (step > 1 && lower != null && lower.test(iz * samples + ix)) {
                    float lo = p.y();
                    for (int dz = -step; dz <= step; dz++) {
                        for (int dx = -step; dx <= step; dx++) {
                            int qx = Math.max(0, Math.min(samples - 1, ix + dx));
                            int qz = Math.max(0, Math.min(samples - 1, iz + dz));
                            lo = Math.min(lo, heightAtGrid(qx, qz));
                        }
                    }
                    p = new Vec3(p.x(), lo - 0.3f, p.z());
                }
                paint.paint(b, p, 1 - n.y(), iz * samples + ix, color);
                index[j * nx + i] = b.vertex(p, n, color[0]);
            }
        }
        for (int j = 0; j < nz - 1; j++) {
            for (int i = 0; i < nx - 1; i++) {
                if (step == 1 && hidden != null && hidden.test((z0 + j) * samples + x0 + i)) {
                    continue;
                }
                int p00 = index[j * nx + i];
                int p10 = index[j * nx + i + 1];
                int p01 = index[(j + 1) * nx + i];
                int p11 = index[(j + 1) * nx + i + 1];
                b.triangle(p00, p01, p11);
                b.triangle(p00, p11, p10);
            }
        }
        if (skirt > 0) {
            // 네 변: 가장자리 정점을 skirt 만큼 아래로 복사해 세로 띠
            int[][] edges = new int[4][];
            edges[0] = new int[nx];
            edges[1] = new int[nx];
            edges[2] = new int[nz];
            edges[3] = new int[nz];
            for (int i = 0; i < nx; i++) {
                edges[0][i] = index[i];
                edges[1][i] = index[(nz - 1) * nx + i];
            }
            for (int j = 0; j < nz; j++) {
                edges[2][j] = index[j * nx];
                edges[3][j] = index[j * nx + nx - 1];
            }
            float[] v = b.peekVertices();
            for (int[] edge : edges) {
                int prev = -1;
                int prevTop = -1;
                for (int top : edge) {
                    int f = top * VertexFormat.FLOATS_PER_VERTEX;
                    Vec3 p = new Vec3(v[f], v[f + 1] - skirt, v[f + 2]);
                    Vec3 c = new Vec3(v[f + 6], v[f + 7], v[f + 8]);
                    int low = b.vertex(p, new Vec3(v[f + 3], v[f + 4], v[f + 5]), c);
                    v = b.peekVertices();
                    if (prev >= 0) {
                        b.triangle(prevTop, prev, low);
                        b.triangle(prevTop, low, top);
                    }
                    prev = low;
                    prevTop = top;
                }
            }
        }
        return b.build();
    }

    public Vec3 position(int ix, int iz) {
        return new Vec3(originX + ix * spacing, heightAtGrid(ix, iz), originZ + iz * spacing);
    }

    // ------------------------------------------------------------------ 값

    public int samples() {
        return samples;
    }

    public float spacing() {
        return spacing;
    }

    public float originX() {
        return originX;
    }

    public float originZ() {
        return originZ;
    }

    public float size() {
        return (samples - 1) * spacing;
    }

    /** 높이 배열 복사본 (물리 엔진에 넘길 때) */
    public float[] heights() {
        return heights.clone();
    }

    // ------------------------------------------------------------------ 노이즈 (외부 라이브러리 없이)

    /** 값 노이즈 4옥타브, 대략 [0, 1] */
    static float fractalNoise(float x, float z, long seed) {
        float sum = 0;
        float amplitude = 0.5f;
        float frequency = 1;
        float norm = 0;
        for (int octave = 0; octave < 4; octave++) {
            sum += amplitude * valueNoise(x * frequency, z * frequency, seed + octave * 1013);
            norm += amplitude;
            amplitude *= 0.5f;
            frequency *= 2.1f;
        }
        return sum / norm;
    }

    /** 값 노이즈 한 옥타브 [0, 1] (맵 만들 때도 쓴다) */
    public static float noise(float x, float z, long seed) {
        return valueNoise(x, z, seed);
    }

    private static float valueNoise(float x, float z, long seed) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        float tx = smooth(x - x0);
        float tz = smooth(z - z0);
        float a = hash01(x0, z0, seed);
        float b = hash01(x0 + 1, z0, seed);
        float c = hash01(x0, z0 + 1, seed);
        float d = hash01(x0 + 1, z0 + 1, seed);
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz;
    }

    private static float hash01(int x, int z, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) {
        return clamp(v, 0, 1);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, float t) {
        return a.add(b.sub(a).scale(t));
    }
}
