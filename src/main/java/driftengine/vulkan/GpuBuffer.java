package driftengine.vulkan;

import driftengine.core.EngineException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU 버퍼 + 전용 메모리. CPU 에서 바로 쓸 수 있는 메모리(HOST_VISIBLE | HOST_COHERENT)를 사용한다.
 * <p>
 * 지금은 단순함을 우선한다. 큰 정적 데이터는 나중에 "스테이징 버퍼 → GPU 전용 메모리" 복사 방식으로
 * 바꾸는 것이 성능상 좋다. 또 버퍼마다 메모리를 따로 할당하므로 수천 개를 만들면 안 된다
 * (드라이버 할당 횟수 제한). 그 단계에서 메모리 할당기를 도입한다.
 */
public final class GpuBuffer extends DeviceResource {

    private long buffer = VK_NULL_HANDLE;
    private long memory = VK_NULL_HANDLE;
    private final long size;

    /**
     * @param sizeBytes 바이트 크기 (> 0)
     * @param usage     VkBufferUsageFlags (예: VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
     */
    public GpuBuffer(VulkanDevice device, long sizeBytes, int usage) {
        super(device);
        if (sizeBytes <= 0) {
            throw new EngineException("버퍼 크기는 양수여야 합니다: " + sizeBytes);
        }
        this.size = sizeBytes;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(vk(), info, null, pBuffer), "vkCreateBuffer");
            buffer = pBuffer.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(vk(), buffer, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(device.findMemoryType(req.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(vk(), alloc, null, pMemory), "vkAllocateMemory");
            memory = pMemory.get(0);
            check(vkBindBufferMemory(vk(), buffer, memory, 0), "vkBindBufferMemory");
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    /** float 배열로 버퍼를 만들고 내용을 채운다. */
    public static GpuBuffer ofFloats(VulkanDevice device, float[] data, int usage) {
        GpuBuffer b = new GpuBuffer(device, (long) data.length * Float.BYTES, usage);
        b.write(address -> MemoryUtil.memFloatBuffer(address, data.length).put(data));
        return b;
    }

    /** int 배열로 버퍼를 만들고 내용을 채운다. */
    public static GpuBuffer ofInts(VulkanDevice device, int[] data, int usage) {
        GpuBuffer b = new GpuBuffer(device, (long) data.length * Integer.BYTES, usage);
        b.write(address -> MemoryUtil.memIntBuffer(address, data.length).put(data));
        return b;
    }

    /** 메모리를 잠시 CPU 주소로 연결(map)해서 writer 가 쓰게 한다. COHERENT 라서 flush 불필요. */
    public void write(java.util.function.LongConsumer writer) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            check(vkMapMemory(vk(), memory, 0, size, 0, pData), "vkMapMemory");
            try {
                writer.accept(pData.get(0));
            } finally {
                vkUnmapMemory(vk(), memory);
            }
        }
    }

    public long handle() {
        return buffer;
    }

    public long size() {
        return size;
    }

    @Override
    protected void destroy() {
        if (buffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(vk(), buffer, null);
        }
        if (memory != VK_NULL_HANDLE) {
            vkFreeMemory(vk(), memory, null);
        }
    }
}
