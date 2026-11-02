package driftengine.vulkan;

import driftengine.core.Log;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdWriteTimestamp2;

/**
 * GPU 타임스탬프로 한 프레임 안의 구간 시간을 잰다 (CPU 시간이 아니라 GPU 가 실제로 일한 시간).
 * <p>
 * 프레임 슬롯마다 지점 3개: 시작 → 장면 끝 → 업스케일 끝.
 * 결과는 그 슬롯의 펜스를 기다린 뒤(=GPU 가 끝난 뒤) 읽으므로 기다림 없이 바로 나온다.
 * GPU/큐가 타임스탬프를 지원하지 않으면 꺼진 채로 동작한다(측정값 없음).
 */
public final class GpuTimer extends DeviceResource {

    public static final int POINTS = 3;

    private final int slots;
    private final double nanosPerTick;
    private final long validMask;
    private long pool = VK_NULL_HANDLE;
    private final boolean[] written;
    private double lastSceneMs = Double.NaN;
    private double lastUpscaleMs = Double.NaN;

    public GpuTimer(VulkanDevice device, int slots) {
        super(device);
        this.slots = slots;
        this.written = new boolean[slots];
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().device(), props);
            nanosPerTick = props.limits().timestampPeriod();

            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device.gpu().device(), count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device.gpu().device(), count, families);
            int validBits = families.get(device.gpu().graphicsFamily()).timestampValidBits();
            validMask = validBits >= 64 ? -1L : (1L << validBits) - 1;

            if (validBits == 0 || nanosPerTick <= 0) {
                Log.warn("GPU 타임스탬프 미지원 → GPU 구간 시간 측정 안 함");
                return;
            }
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(slots * POINTS);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateQueryPool(vk(), info, null, pPool), "vkCreateQueryPool");
            pool = pPool.get(0);
        }
    }

    public boolean enabled() {
        return pool != VK_NULL_HANDLE;
    }

    /** 명령 버퍼 맨 앞: 이 슬롯의 쿼리를 비우고 시작 지점을 기록 */
    public void begin(VkCommandBuffer cmd, int slot) {
        if (!enabled()) {
            return;
        }
        vkCmdResetQueryPool(cmd, pool, slot * POINTS, POINTS);
        mark(cmd, slot, 0);
    }

    /** point: 1 = 장면 끝, 2 = 업스케일 끝 */
    public void mark(VkCommandBuffer cmd, int slot, int point) {
        if (!enabled()) {
            return;
        }
        // ALL_COMMANDS: 앞선 모든 명령이 끝난 시점을 기록
        vkCmdWriteTimestamp2(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, slot * POINTS + point);
        if (point == POINTS - 1) {
            written[slot] = true;
        }
    }

    /**
     * 이 슬롯이 지난번에 기록한 결과를 읽는다. 펜스 대기 직후 호출한다.
     *
     * @return 읽었으면 true (lastSceneMs / lastUpscaleMs 갱신)
     */
    public boolean collect(int slot) {
        if (!enabled() || !written[slot]) {
            return false;
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer ticks = stack.mallocLong(POINTS);
            int result = vkGetQueryPoolResults(vk(), pool, slot * POINTS, POINTS, ticks, Long.BYTES,
                    VK_QUERY_RESULT_64_BIT);
            if (result != VK_SUCCESS) {
                return false; // VK_NOT_READY 등: 이번에는 건너뛴다
            }
            double[] ms = intervalsMs(new long[]{ticks.get(0), ticks.get(1), ticks.get(2)}, validMask, nanosPerTick);
            lastSceneMs = ms[0];
            lastUpscaleMs = ms[1];
            return true;
        }
    }

    /** 타임스탬프 3개 → {장면 ms, 업스케일 ms}. 유효 비트 밖 값은 버리고, 한 번의 넘침(wrap)은 보정한다. */
    public static double[] intervalsMs(long[] ticks, long validMask, double nanosPerTick) {
        double[] out = new double[ticks.length - 1];
        for (int i = 0; i < out.length; i++) {
            long delta = ((ticks[i + 1] & validMask) - (ticks[i] & validMask)) & validMask;
            out[i] = delta * nanosPerTick / 1e6;
        }
        return out;
    }

    /** 업스케일러가 바뀌는 등 이전 기록을 쓰면 안 될 때 (GPU 대기 후 호출) */
    public void discard() {
        java.util.Arrays.fill(written, false);
        lastSceneMs = Double.NaN;
        lastUpscaleMs = Double.NaN;
    }

    public double lastSceneMs() {
        return lastSceneMs;
    }

    public double lastUpscaleMs() {
        return lastUpscaleMs;
    }

    public int slots() {
        return slots;
    }

    @Override
    protected void destroy() {
        if (pool != VK_NULL_HANDLE) {
            vkDestroyQueryPool(vk(), pool, null);
        }
    }
}
