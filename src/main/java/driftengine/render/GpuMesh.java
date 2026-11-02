package driftengine.render;

import driftengine.scene.mesh.MeshData;
import driftengine.vulkan.GpuBuffer;
import driftengine.vulkan.VulkanDevice;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * GPU 에 올라간 메시: 정점 버퍼 + 인덱스 버퍼 (조합).
 */
public final class GpuMesh implements AutoCloseable {

    private final String name;
    private final GpuBuffer vertices;
    private final GpuBuffer indices;
    private final int indexCount;

    public GpuMesh(VulkanDevice device, MeshData mesh) {
        this.name = mesh.name();
        this.indexCount = mesh.indexCount();
        this.vertices = GpuBuffer.ofFloats(device, mesh.vertices(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        try {
            this.indices = GpuBuffer.ofInts(device, mesh.indices(), VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        } catch (RuntimeException e) {
            vertices.close();
            throw e;
        }
    }

    public String name() {
        return name;
    }

    public long vertexBuffer() {
        return vertices.handle();
    }

    public long indexBuffer() {
        return indices.handle();
    }

    public int indexCount() {
        return indexCount;
    }

    public long bytes() {
        return vertices.size() + indices.size();
    }

    @Override
    public void close() {
        indices.close();
        vertices.close();
    }
}
