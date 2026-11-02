package driftengine.world;

import driftengine.core.Log;
import driftengine.math.Vec3;
import driftengine.scene.SceneObject;
import driftengine.scene.mesh.MeshData;
import driftengine.world.map.ZoneMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 지형 조각 보기: 카메라에서 가까운 조각은 자세한 메시, 먼 조각은 듬성한 메시.
 * 자세한 메시는 필요할 때 만들고(프레임마다 몇 개까지만, 끊김 방지) 최근 것만 남긴다.
 * 멀어진 조각의 자세한 메시는 장면에서 빠지므로 렌더러가 GPU 에서 내린다.
 */
final class ChunkView {

    /** 자세한 조각을 들고 있을 최대 개수 (조각 하나 ≈ 0.3 MB) */
    static final int KEEP = 140;
    /** 한 프레임에 새로 만들 자세한 조각 수 */
    static final int BUILD_PER_FRAME = 3;
    /** 가까움/멂 경계 흔들림 방지 (m) */
    static final float HYSTERESIS = 25f;

    private final ZoneMap map;
    private final SceneObject[] coarse;
    private final boolean[] detailed;
    private final Map<Integer, SceneObject> near = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, SceneObject> eldest) {
            return size() > KEEP;
        }
    };
    private int builtTotal;

    ChunkView(ZoneMap map) {
        this.map = map;
        int n = map.chunks.size();
        coarse = new SceneObject[n];
        detailed = new boolean[n];
        for (int i = 0; i < n; i++) {
            MeshData m = map.coarse(i);
            coarse[i] = new SceneObject(m.name(), m);
        }
    }

    /**
     * 카메라 위치로 조각마다 자세히/듬성 고르기.
     *
     * @param budget 이번에 새로 만들 수 있는 자세한 조각 수 (불러올 때는 크게)
     * @return 그릴 조각이 바뀌었으면 true
     */
    boolean update(Vec3 cam, int budget) {
        boolean changed = false;
        int built = 0;
        for (int i = 0; i < coarse.length; i++) {
            ZoneMap.Chunk c = map.chunks.get(i);
            float d = (float) Math.hypot(c.cx() - cam.x(), c.cz() - cam.z()) - c.radius() * 0.75f;
            float limit = map.detailDistance + (detailed[i] ? HYSTERESIS : 0);
            boolean want = d < limit;
            if (want && !detailed[i]) {
                if (!near.containsKey(i)) {
                    if (built >= budget) {
                        continue;
                    }
                    MeshData m = map.detailed(i);
                    near.put(i, new SceneObject(m.name(), m));
                    built++;
                    builtTotal++;
                }
                detailed[i] = true;
                changed = true;
            } else if (!want && detailed[i]) {
                detailed[i] = false;
                changed = true;
            } else if (want) {
                near.get(i); // 최근 사용 표시
            }
        }
        // 최근 목록에서 밀려난 조각은 듬성하게
        for (int i = 0; i < coarse.length; i++) {
            if (detailed[i] && !near.containsKey(i)) {
                detailed[i] = false;
                changed = true;
            }
        }
        return changed;
    }

    /** 들고 있는 자세한 조각 메시 (지금 듬성하게 그리는 조각 것도) */
    void forEachCached(java.util.function.Consumer<MeshData> keep) {
        for (SceneObject o : near.values()) {
            keep.accept(o.mesh());
        }
    }

    void collect(List<SceneObject> out) {
        for (int i = 0; i < coarse.length; i++) {
            SceneObject o = detailed[i] ? near.get(i) : null;
            out.add(o != null ? o : coarse[i]);
        }
    }

    int detailedCount() {
        int n = 0;
        for (boolean b : detailed) {
            n += b ? 1 : 0;
        }
        return n;
    }

    int builtTotal() {
        return builtTotal;
    }

    void logState(String where) {
        Log.info(String.format("지형 조각 (%s): 자세히 %d / %d, 지금까지 만든 자세한 조각 %d", where, detailedCount(), coarse.length, builtTotal));
    }
}
