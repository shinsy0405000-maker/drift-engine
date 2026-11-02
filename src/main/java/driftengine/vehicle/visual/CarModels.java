package driftengine.vehicle.visual;

import driftengine.core.Log;
import driftengine.math.Vec3;
import driftengine.scene.mesh.Material;
import driftengine.scene.mesh.MeshBuilder;
import driftengine.scene.mesh.MeshData;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 모델링한 차 (cars/models/&lt;차 id&gt;.cmesh.gz, tools/carmodels 가 Blender 로 만든 파일)를 읽어 메시로 만든다.
 * <p>
 * 파일 형식 (빅엔디안, gzip): 'CMSH', int 버전 1, int 부품 수, 부품마다 utf 이름, int 묶음 수,
 * 묶음마다 utf 재질 이름, int 정점 수, float[정점×6] (위치 xyz, 법선 xyz), int 인덱스 수, int[인덱스].
 * 좌표는 게임 기준(-z 앞), 높이는 땅 = 0. 바퀴는 바퀴 중심이 원점, 바깥면이 +X.
 * <p>
 * 부품: body, glass (유리·라이트 덮개, 반투명으로 따로 그림), kit.front.1~2, kit.side.1, kit.rear.1, kit.spoiler.1~2,
 * kit.wide.1, wheel.front, wheel.rear, caliper.front, caliper.rear (바퀴와 같은 좌표, 조향만 따라가고 돌지 않음).
 * 도장색·휠 색은 파일이 아니라 CarStyle 에서 (차고에서 바꿀 수 있게). 파일이 없으면 null → 옛 생성기로 대신한다.
 */
public final class CarModels {

    /** 재질 이름 하나의 묶음 */
    record Group(String material, float[] vertices, int[] indices) {
    }

    private static Path directory = Path.of("cars", "models");
    /** 읽어 둔 모델 파일: 최근에 쓴 차 PART_CARS 대까지만 (차 한 대 ≈ 8 MB) */
    public static final int PART_CARS = 6;
    private static final Map<String, Map<String, List<Group>>> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, List<Group>>> eldest) {
            return size() > PART_CARS;
        }
    };
    /**
     * 같은 차·바디킷·색이면 같은 MeshData 를 돌려준다 → 렌더러가 GPU 메시를 다시 올리지 않음 (튜닝만 바꿀 때).
     * 색 미리보기를 넘길 때마다 차체(≈ 7.5 MB)가 새로 생기므로 최근 것부터 MESH_BUDGET 바이트까지만 남긴다.
     * 여기서 빠져도 지금 장면에 붙어 있는 메시는 그대로 쓰인다 (다시 고르면 새로 만들 뿐).
     */
    public static final long MESH_BUDGET = 128L << 20;
    private static final MeshLru MESHES = new MeshLru(MESH_BUDGET);

    private static final Vec3 GLASS_TINT = new Vec3(0.015f, 0.02f, 0.025f);
    private static final Vec3 TRIM = new Vec3(0.012f, 0.012f, 0.013f);
    private static final Vec3 BLACK = new Vec3(0.02f, 0.02f, 0.022f);
    private static final Material TRIM_MAT = new Material(0.0f, 0.25f, 0.6f, 0, Material.SURFACE_PLAIN);
    private static final Material REFLECTOR = new Material(1.0f, 0.12f, 0.5f, 0, Material.SURFACE_PLAIN);
    private static final Material DISC = new Material(1.0f, 0.45f, 0, 0, Material.SURFACE_PLAIN);
    private static final Material PLATE = new Material(0.0f, 0.5f, 0.2f, 0, Material.SURFACE_PLAIN);
    private static final Material RIM = new Material(1.0f, 0.3f, 0.3f, 0, Material.SURFACE_PLAIN);
    private static final Material INTERIOR = new Material(0.0f, 0.7f, 0.1f, 0, Material.SURFACE_PLAIN);
    private static final Material SEAT = new Material(0.0f, 0.8f, 0, 0, Material.SURFACE_PLAIN);
    private static final Material CAGE = new Material(1.0f, 0.35f, 0.4f, 0, Material.SURFACE_PLAIN);
    /** 꺼진 화면: 매끈한 검정에 아주 약한 빛 */
    private static final Material SCREEN = new Material(0.0f, 0.08f, 0.8f, 0.4f, Material.SURFACE_PLAIN);
    private static final Material CALIPER = new Material(0.0f, 0.3f, 0.9f, 0, Material.SURFACE_PLAIN);

    /** 이 클래스가 아는 재질 이름 (tools/carmodels/carlib.py 의 MATERIALS 와 같아야 한다) */
    public static final java.util.Set<String> MATERIALS = java.util.Set.of("paint", "glass", "lens", "trim", "black", "chrome",
            "head", "drl", "tail", "amber", "carbon", "rubber", "rim", "disc", "caliper", "caliper_gold", "caliper_blue",
            "caliper_black", "plate", "interior", "seat", "accent", "cage", "screen");

    private CarModels() {
    }

    public static synchronized void setDirectory(Path dir) {
        directory = dir;
        CACHE.clear();
        MESHES.clear();
    }

    /** 이 차의 모델 파일이 있나 */
    public static boolean available(String carId) {
        return parts(carId) != null;
    }

    /**
     * 차체 + 바디킷 메시 (차체 로컬 좌표: 물리 상자 중심 = 원점).
     *
     * @param restHeight 순정 정지 높이 (땅 → 상자 중심). 순정 값을 주면 낮춘 차는 차체가 내려간 만큼 바퀴가 아치 안으로 들어간다
     * @return 파일이 없으면 null
     */
    public static synchronized MeshData body(String carId, CarStyle style, float restHeight) {
        Map<String, List<Group>> p = parts(carId);
        if (p == null) {
            return null;
        }
        String key = "body|" + carId + "|" + style.kit().text() + "|" + style.color() + "|" + style.metallicPaint() + "|"
                + style.livery().finish() + "|" + restHeight;
        MeshData cached = MESHES.get(key);
        if (cached != null) {
            return cached;
        }
        BodyKit k = style.kit();
        List<String> names = new ArrayList<>(List.of("body"));
        add(names, "kit.front.", k.front());
        add(names, "kit.side.", k.side());
        add(names, "kit.rear.", k.rear());
        add(names, "kit.spoiler.", k.spoiler());
        add(names, "kit.wide.", k.widebody());
        MeshBuilder b = new MeshBuilder("car-" + carId);
        for (String n : names) {
            List<Group> groups = p.get(n);
            if (groups == null) {
                if (!n.equals("body")) {
                    Log.warn("모델 " + carId + " 에 부품 " + n + " 이 없음 (건너뜀)");
                }
                continue;
            }
            for (Group g : groups) {
                append(b, g, style, -restHeight, false);
            }
        }
        MeshData m = b.build();
        MESHES.put(key, m);
        return m;
    }

    /** 바퀴 메시 (front = 앞바퀴, outward = +1 오른쪽 / -1 왼쪽). 파일이 없으면 null */
    public static synchronized MeshData wheel(String carId, CarStyle style, boolean front, float outward) {
        Map<String, List<Group>> p = parts(carId);
        if (p == null) {
            return null;
        }
        String rimFrom = style.livery().rimFrom();
        String key = "wheel|" + carId + "|" + front + "|" + outward + "|" + style.rimColor() + "|" + rimFrom;
        MeshData cached = MESHES.get(key);
        if (cached != null) {
            return cached;
        }
        String part = front ? "wheel.front" : "wheel.rear";
        List<Group> groups = p.get(part);
        if (groups == null) {
            return null;
        }
        // 다른 차 휠 디자인: 그 차의 휠을 이 차 휠 크기(반지름·폭)에 맞춰 늘인다
        List<Group> design = groups;
        float[] scale = {1, 1, 1};
        if (rimFrom != null && !rimFrom.equals(carId)) {
            Map<String, List<Group>> src = parts(rimFrom);
            if (src != null && src.get(part) != null) {
                float[] own = extent(groups);
                float[] other = extent(src.get(part));
                design = src.get(part);
                scale = new float[]{own[0] / other[0], own[1] / other[1], own[2] / other[2]};
            }
        }
        MeshBuilder b = new MeshBuilder("wheel-" + carId);
        for (Group g : design) {
            append(b, scaled(g, scale), style, 0, outward < 0);
        }
        MeshData m = b.build();
        MESHES.put(key, m);
        return m;
    }

    /** 유리·라이트 덮개 (차체와 같은 좌표, 반투명으로 그린다). 파일이나 부품이 없으면 null */
    public static synchronized MeshData glass(String carId, CarStyle style, float restHeight) {
        return single(carId, "glass", style, -restHeight, false, "glass|" + carId + "|" + restHeight + "|" + style.livery().tint());
    }

    /** 브레이크 캘리퍼 (바퀴 좌표). 파일이나 부품이 없으면 null */
    public static synchronized MeshData caliper(String carId, CarStyle style, boolean front, float outward) {
        return single(carId, front ? "caliper.front" : "caliper.rear", style, 0, outward < 0,
                "caliper|" + carId + "|" + front + "|" + outward + "|" + style.livery().caliper());
    }

    private static MeshData single(String carId, String part, CarStyle style, float dy, boolean mirror, String key) {
        Map<String, List<Group>> p = parts(carId);
        if (p == null || p.get(part) == null) {
            return null;
        }
        MeshData cached = MESHES.get(key);
        if (cached != null) {
            return cached;
        }
        MeshBuilder b = new MeshBuilder(part + "-" + carId);
        for (Group g : p.get(part)) {
            append(b, g, style, dy, mirror);
        }
        MeshData m = b.build();
        MESHES.put(key, m);
        return m;
    }

    /** 모델 파일이 있으면 그것, 없으면 옛 생성기 (CarBodyBuilder) */
    public static MeshData bodyOrBuilt(driftengine.vehicle.CarSpec spec, CarStyle style, float stockRestHeight) {
        MeshData m = body(spec.id(), style, stockRestHeight);
        return m != null ? m : CarBodyBuilder.build(spec, style);
    }

    /** 모델 파일이 있으면 그것, 없으면 옛 생성기 (WheelBuilder). wheel = 0 앞왼, 1 앞오, 2 뒤왼, 3 뒤오 */
    public static MeshData wheelOrBuilt(driftengine.vehicle.CarSpec spec, CarStyle style, int wheel) {
        boolean front = driftengine.vehicle.CarSpec.isFront(wheel);
        float outward = wheel % 2 == 0 ? -1 : 1;
        MeshData m = wheel(spec.id(), style, front, outward);
        if (m != null) {
            return m;
        }
        float width = front ? style.tireWidthF() : style.tireWidthR();
        return WheelBuilder.build(spec.wheelRadius(), width, style.rimStyle(), style.rimColor(), outward);
    }

    /** 묶음들의 크기 (x 폭, y 높이, z 길이) */
    static float[] extent(List<Group> groups) {
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Group g : groups) {
            float[] v = g.vertices();
            for (int i = 0; i < v.length; i += 6) {
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], v[i + a]);
                    hi[a] = Math.max(hi[a], v[i + a]);
                }
            }
        }
        return new float[]{Math.max(1e-4f, hi[0] - lo[0]), Math.max(1e-4f, hi[1] - lo[1]), Math.max(1e-4f, hi[2] - lo[2])};
    }

    /** 위치를 축마다 늘이고, 법선은 역방향으로 늘여 다시 정규화 */
    private static Group scaled(Group g, float[] s) {
        if (s[0] == 1 && s[1] == 1 && s[2] == 1) {
            return g;
        }
        float[] v = g.vertices().clone();
        for (int i = 0; i < v.length; i += 6) {
            v[i] *= s[0];
            v[i + 1] *= s[1];
            v[i + 2] *= s[2];
            float nx = v[i + 3] / s[0], ny = v[i + 4] / s[1], nz = v[i + 5] / s[2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0) {
                v[i + 3] = nx / len;
                v[i + 4] = ny / len;
                v[i + 5] = nz / len;
            }
        }
        return new Group(g.material(), v, g.indices());
    }

    private static void add(List<String> names, String prefix, int level) {
        if (level > 0) {
            names.add(prefix + level);
        }
    }

    private static void append(MeshBuilder b, Group g, CarStyle style, float dy, boolean mirrorX) {
        Look look = look(g.material(), style);
        b.material(look.material);
        int base = b.vertexCount();
        float[] v = g.vertices();
        float sx = mirrorX ? -1 : 1;
        for (int i = 0; i < v.length; i += 6) {
            b.vertex(new Vec3(v[i] * sx, v[i + 1] + dy, v[i + 2]), new Vec3(v[i + 3] * sx, v[i + 4], v[i + 5]), look.color);
        }
        int[] idx = g.indices();
        for (int i = 0; i < idx.length; i += 3) {
            if (mirrorX) { // 거울은 감기는 방향이 뒤집히므로 바꿔 준다
                b.triangle(base + idx[i], base + idx[i + 2], base + idx[i + 1]);
            } else {
                b.triangle(base + idx[i], base + idx[i + 1], base + idx[i + 2]);
            }
        }
    }

    private record Look(Material material, Vec3 color) {
    }

    /** 파일의 재질 이름 → 엔진 재질 + 색 */
    static Look look(String name, CarStyle style) {
        if (name.startsWith("caliper") && style.livery().caliper() >= 0) {
            return new Look(CALIPER, Livery.CALIPERS.get(style.livery().caliper()).linear());
        }
        return switch (name) {
            case "paint" -> new Look(switch (style.livery().finish()) {
                case 2 -> Material.PAINT_MATTE;
                case 3 -> Material.PAINT_CHROME;
                default -> style.metallicPaint() ? Material.PAINT_METALLIC : Material.PAINT_SOLID;
            }, style.color());
            case "glass" -> new Look(style.livery().tint() == 0 ? Material.GLASS
                    : new Material(Livery.TINT_VALUE[style.livery().tint()], 0.05f, 0.6f, 0, Material.SURFACE_GLASS), GLASS_TINT);
            case "lens" -> new Look(Material.LENS, new Vec3(0.9f, 0.92f, 0.95f));
            case "interior" -> new Look(INTERIOR, new Vec3(0.035f, 0.035f, 0.038f));
            case "seat" -> new Look(SEAT, new Vec3(0.05f, 0.05f, 0.052f));
            case "accent" -> new Look(SEAT, new Vec3(0.55f, 0.06f, 0.04f));
            case "cage" -> new Look(CAGE, new Vec3(0.12f, 0.12f, 0.13f));
            case "screen" -> new Look(SCREEN, new Vec3(0.02f, 0.05f, 0.09f));
            case "caliper_gold" -> new Look(CALIPER, new Vec3(0.85f, 0.6f, 0.08f));
            case "caliper_blue" -> new Look(CALIPER, new Vec3(0.05f, 0.18f, 0.75f));
            case "caliper_black" -> new Look(CALIPER, new Vec3(0.03f, 0.03f, 0.03f));
            case "trim" -> new Look(TRIM_MAT, TRIM);
            case "chrome" -> new Look(Material.CHROME, new Vec3(0.85f, 0.85f, 0.87f));
            case "head" -> new Look(REFLECTOR, new Vec3(0.42f, 0.44f, 0.47f));
            case "drl" -> new Look(Material.LIGHT, new Vec3(1.0f, 0.97f, 0.92f));
            case "tail" -> new Look(Material.TAIL_LIGHT, new Vec3(0.75f, 0.02f, 0.015f));
            case "amber" -> new Look(Material.TAIL_LIGHT, new Vec3(0.9f, 0.35f, 0.02f));
            case "carbon" -> new Look(Material.CARBON, new Vec3(0.03f, 0.03f, 0.033f));
            case "rubber" -> new Look(Material.RUBBER, new Vec3(0.025f, 0.025f, 0.027f));
            case "rim" -> new Look(RIM, style.rimColor());
            case "disc" -> new Look(DISC, new Vec3(0.35f, 0.35f, 0.36f));
            case "caliper" -> new Look(CALIPER, new Vec3(0.7f, 0.04f, 0.03f));
            case "plate" -> new Look(PLATE, new Vec3(0.75f, 0.76f, 0.74f));
            default -> new Look(Material.PLASTIC_BLACK, BLACK); // black 및 모르는 이름
        };
    }

    /** 읽은 부품 (없거나 깨졌으면 null). 한 번 읽은 파일은 기억한다 */
    static synchronized Map<String, List<Group>> parts(String carId) {
        if (CACHE.containsKey(carId)) {
            return CACHE.get(carId);
        }
        Path file = directory.resolve(carId + ".cmesh.gz");
        Map<String, List<Group>> parts = null;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                parts = read(in);
                Log.info(String.format("차 모델 %s: 부품 %d개, 삼각형 %,d", carId, parts.size(), triangles(parts)));
            } catch (IOException | RuntimeException e) {
                Log.warn("차 모델 파일을 읽지 못함 (옛 생성기로 대신): " + file + " → " + e.getMessage());
            }
        }
        CACHE.put(carId, parts);
        return parts;
    }

    static int triangles(Map<String, List<Group>> parts) {
        int n = 0;
        for (List<Group> gs : parts.values()) {
            for (Group g : gs) {
                n += g.indices().length / 3;
            }
        }
        return n;
    }

    static Map<String, List<Group>> read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw), 1 << 16));
        byte[] magic = in.readNBytes(4);
        if (!"CMSH".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("CMSH 파일이 아님");
        }
        int version = in.readInt();
        if (version != 1) {
            throw new IOException("모르는 버전 " + version);
        }
        int count = in.readInt();
        Map<String, List<Group>> out = new LinkedHashMap<>();
        for (int p = 0; p < count; p++) {
            String name = utf(in);
            int groups = in.readInt();
            List<Group> list = new ArrayList<>();
            for (int g = 0; g < groups; g++) {
                String mat = utf(in);
                int vc = in.readInt();
                if (vc < 0 || vc > 5_000_000) {
                    throw new IOException("정점 수 이상: " + vc);
                }
                float[] v = new float[vc * 6];
                for (int i = 0; i < v.length; i++) {
                    v[i] = in.readFloat();
                }
                int ic = in.readInt();
                if (ic < 0 || ic % 3 != 0 || ic > 30_000_000) {
                    throw new IOException("인덱스 수 이상: " + ic);
                }
                int[] idx = new int[ic];
                for (int i = 0; i < ic; i++) {
                    idx[i] = in.readInt();
                    if (idx[i] < 0 || idx[i] >= vc) {
                        throw new IOException("인덱스 범위 밖: " + idx[i] + " / " + vc);
                    }
                }
                list.add(new Group(mat, v, idx));
            }
            out.put(name, list);
        }
        return out;
    }

    private static String utf(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }

    /** 지금 남아 있는 메시 바이트 / 개수 (테스트·기록용) */
    public static synchronized long cachedMeshBytes() {
        return MESHES.bytes;
    }

    public static synchronized int cachedMeshCount() {
        return MESHES.map.size();
    }

    public static synchronized int cachedPartCars() {
        return CACHE.size();
    }

    /** 바이트 한도가 있는 최근 사용 순 목록 */
    static final class MeshLru {
        final LinkedHashMap<String, MeshData> map = new LinkedHashMap<>(64, 0.75f, true);
        final long budget;
        long bytes;

        MeshLru(long budget) {
            this.budget = budget;
        }

        static long size(MeshData m) {
            return 4L * (m.vertices().length + m.indices().length);
        }

        MeshData get(String key) {
            return map.get(key);
        }

        void put(String key, MeshData m) {
            MeshData old = map.put(key, m);
            if (old != null) {
                bytes -= size(old);
            }
            bytes += size(m);
            var it = map.entrySet().iterator();
            while (bytes > budget && map.size() > 1) {
                var e = it.next();
                if (e.getKey().equals(key)) {
                    continue; // 방금 넣은 것은 남긴다
                }
                bytes -= size(e.getValue());
                it.remove();
            }
        }

        void clear() {
            map.clear();
            bytes = 0;
        }
    }
}
