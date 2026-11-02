package driftengine.world.map;

import driftengine.scene.mesh.MeshData;
import driftengine.world.Terrain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * 불러온 지역 하나의 내용 (순수 데이터, GPU·물리 없이 만들고 시험할 수 있다).
 * <ul>
 *   <li>지형 + 조각(가까우면 자세히, 멀면 듬성듬성)</li>
 *   <li>고정 메시 (도로 면, 가드레일, 나무, 건물 …)</li>
 *   <li>물리 상자 (벽, 건물)</li>
 *   <li>출발 위치, 이벤트 코스</li>
 * </ul>
 */
public final class ZoneMap {

    /** 고정 메시 하나 (shadow = 해 그림자를 드리우나) */
    public record Piece(MeshData mesh, boolean shadow) {
    }

    /** 출발 위치 (x, z, 차 앞 방향 yaw: 차 앞 −z 를 Y 축으로 yaw 만큼 돌림) */
    public record Spawn(float x, float z, float yaw) {
    }

    /** 지형 조각: 격자 범위 [x0..x1] × [z0..z1], 가운데·반지름 (월드) */
    public record Chunk(int x0, int z0, int x1, int z1, float cx, float cz, float radius) {
    }

    public final Zone zone;
    public final Terrain terrain;
    /** 점마다 가장 가까운 도로 포장 끝까지 거리 (안쪽 음수). 도로가 없는 지역은 null */
    public final float[] cover;
    public final Terrain.Paint paint;
    /** 지형 조각을 그리지 않는 칸 (도로 면·도시 바닥 아래). null = 다 그림 */
    public final IntPredicate hidden;
    /** 듬성한 조각에서 낮출 정점 (도로·도시 바닥 근처) */
    public IntPredicate lowerFar;
    public final int chunkCells;
    /** 이 거리(m, 조각 가장자리까지)보다 가까운 조각만 자세히 */
    public final float detailDistance;
    public final List<Chunk> chunks = new ArrayList<>();
    public final List<Piece> pieces = new ArrayList<>();
    public final List<float[]> boxes = new ArrayList<>();
    public final Map<String, Course> courses = new LinkedHashMap<>();
    public final RoadNet roads;
    public Spawn spawn = new Spawn(0, 0, 0);
    /** 코스 없는 드리프트 이벤트 출발 위치 (도시 광장). null = spawn */
    public Spawn eventSpawn;
    /** 상점이 있는 지역 (연습장) */
    public boolean shops;

    public ZoneMap(Zone zone, Terrain terrain, float[] cover, Terrain.Paint paint, IntPredicate hidden, int chunkCells,
                   float detailDistance, RoadNet roads) {
        this.zone = zone;
        this.terrain = terrain;
        this.cover = cover;
        this.paint = paint;
        this.hidden = hidden;
        this.chunkCells = chunkCells;
        this.detailDistance = detailDistance;
        this.roads = roads;
        this.lowerFar = cover == null ? null : i -> cover[i] < 4f;
        int last = terrain.samples() - 1;
        for (int z0 = 0; z0 < last; z0 += chunkCells) {
            for (int x0 = 0; x0 < last; x0 += chunkCells) {
                int x1 = Math.min(last, x0 + chunkCells);
                int z1 = Math.min(last, z0 + chunkCells);
                float sp = terrain.spacing();
                float cx = terrain.originX() + (x0 + x1) / 2f * sp;
                float cz = terrain.originZ() + (z0 + z1) / 2f * sp;
                float r = (float) Math.hypot((x1 - x0) * sp, (z1 - z0) * sp) / 2;
                chunks.add(new Chunk(x0, z0, x1, z1, cx, cz, r));
            }
        }
    }

    /** 자세한 조각 메시 (격자 1칸 = 정점 하나, 도로 아래 칸 빼기) */
    public MeshData detailed(int index) {
        Chunk c = chunks.get(index);
        return terrain.chunk("terrain-" + zone.name().toLowerCase() + "-" + index, c.x0(), c.z0(), c.x1(), c.z1(), 1,
                1.5f, paint, hidden, null);
    }

    /** 듬성한 조각 메시 (4칸마다 정점, 도로 근처는 낮춰서 도로 면 위로 솟지 않게) */
    public MeshData coarse(int index) {
        Chunk c = chunks.get(index);
        return terrain.chunk("terrain-far-" + zone.name().toLowerCase() + "-" + index, c.x0(), c.z0(), c.x1(), c.z1(), 4,
                4f, paint, null, lowerFar);
    }

    /** 차가 떨어졌다고 볼 높이 */
    public float fallHeight() {
        float lo = Float.MAX_VALUE;
        for (float h : terrain.heights()) {
            lo = Math.min(lo, h);
        }
        return lo - 25;
    }

    public Course course(String id) {
        return courses.get(id);
    }
}
