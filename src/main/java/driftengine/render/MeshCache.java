package driftengine.render;

import driftengine.scene.mesh.MeshData;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * GPU 에 올린 메시 목록. 장면에서 더 이상 쓰지 않는 메시(차 바꾸기, 색 미리보기 등)는
 * GPU 가 그 메시를 쓰던 프레임을 다 끝낸 뒤에 내린다.
 * <p>
 * 프레임 N 을 기록할 때는 같은 슬롯의 펜스를 기다린 뒤이므로 N - 프레임 슬롯 수 까지는 GPU 가 끝났다.
 * 그래서 마지막으로 본 프레임이 지금보다 keepFrames 이상 지난 메시만 내린다.
 *
 * @param <T> GPU 메시 (테스트에서는 가짜)
 */
public final class MeshCache<T extends AutoCloseable> {

    private static final class Entry<T> {
        final T gpu;
        final long bytes;
        long lastSeen;

        Entry(T gpu, long bytes, long lastSeen) {
            this.gpu = gpu;
            this.bytes = bytes;
            this.lastSeen = lastSeen;
        }
    }

    private final Map<MeshData, Entry<T>> entries = new IdentityHashMap<>();
    private final Function<MeshData, T> upload;
    private final ToLongFunction<T> size;
    private final int keepFrames;
    private long totalBytes;
    private int freedCount;
    private long freedBytes;

    public MeshCache(Function<MeshData, T> upload, ToLongFunction<T> size, int keepFrames) {
        this.upload = upload;
        this.size = size;
        this.keepFrames = keepFrames;
    }

    /** GPU 메시 (없으면 지금 올린다). frame = 지금 기록 중인 프레임 번호 */
    public T get(MeshData mesh, long frame) {
        Entry<T> e = entries.get(mesh);
        if (e == null) {
            T gpu = upload.apply(mesh);
            e = new Entry<>(gpu, size.applyAsLong(gpu), frame);
            entries.put(mesh, e);
            totalBytes += e.bytes;
        } else if (frame > e.lastSeen) {
            e.lastSeen = frame;
        }
        return e.gpu;
    }

    /** 장면이 아직 들고 있는 메시 표시 (화면 밖이거나 숨겨져 있어도 장면에 있으면 남긴다) */
    public void mark(MeshData mesh, long frame) {
        Entry<T> e = entries.get(mesh);
        if (e != null && frame > e.lastSeen) {
            e.lastSeen = frame;
        }
    }

    /** 오래 안 쓴 메시를 내린다. 이번에 내린 개수 */
    public int sweep(long frame) {
        int n = 0;
        long bytes = 0;
        Iterator<Entry<T>> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry<T> e = it.next();
            if (frame - e.lastSeen >= keepFrames) {
                close(e.gpu);
                it.remove();
                totalBytes -= e.bytes;
                bytes += e.bytes;
                n++;
            }
        }
        freedCount = n;
        freedBytes = bytes;
        return n;
    }

    /** 바로 앞 sweep 이 내린 바이트 */
    public long freedBytes() {
        return freedBytes;
    }

    public int freedCount() {
        return freedCount;
    }

    public int size() {
        return entries.size();
    }

    public long totalBytes() {
        return totalBytes;
    }

    public boolean contains(MeshData mesh) {
        return entries.containsKey(mesh);
    }

    /** 전부 내린다 (렌더러를 닫을 때: GPU 는 이미 멈춘 상태) */
    public void clear() {
        for (Entry<T> e : entries.values()) {
            close(e.gpu);
        }
        entries.clear();
        totalBytes = 0;
    }

    private static void close(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ex) {
            throw new IllegalStateException("메시 해제 실패", ex);
        }
    }
}
