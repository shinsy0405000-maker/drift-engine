package driftengine.render;

import driftengine.core.Log;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.GpuTimer;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Locale;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdWriteTimestamp2;

/**
 * 장면 단계별 GPU 시간 (하늘 표, 그림자, 깊이+AO, 불투명, 반투명). 몇 초마다 평균을 로그로 남긴다.
 * 어느 효과가 무거운지 찾으려는 용도. 타임스탬프를 지원하지 않으면 아무것도 안 한다.
 */
final class PassTimer extends DeviceResource {

    static final String[] NAMES = {"하늘표", "그림자", "깊이+AO", "불투명", "반투명"};
    static final int POINTS = NAMES.length + 1;
    private static final double REPORT_SECONDS = 5.0;

    private final int slots;
    private final boolean[] written;
    private double nanosPerTick;
    private long validMask;
    private long pool = VK_NULL_HANDLE;

    private final double[] sum = new double[NAMES.length];
    private final double[] total = new double[NAMES.length];
    private int count;
    private int totalCount;
    private long lastReport = System.nanoTime();

    private final java.util.function.IntSupplier shadowDrawn;
    private final java.util.function.IntSupplier shadowSkipped;

    PassTimer(VulkanDevice device, int slots, java.util.function.IntSupplier shadowDrawn, java.util.function.IntSupplier shadowSkipped) {
        super(device);
        this.shadowDrawn = shadowDrawn;
        this.shadowSkipped = shadowSkipped;
        this.slots = slots;
        this.written = new boolean[slots];
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().device(), props);
            nanosPerTick = props.limits().timestampPeriod();
            IntBuffer n = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device.gpu().device(), n, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(n.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device.gpu().device(), n, families);
            int bits = families.get(device.gpu().graphicsFamily()).timestampValidBits();
            validMask = bits >= 64 ? -1L : (1L << bits) - 1;
            if (bits == 0 || nanosPerTick <= 0) {
                return;
            }
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateQueryPool(vk(), VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(slots * POINTS), null, p), "vkCreateQueryPool(pass)");
            pool = p.get(0);
        }
    }

    /** 이 슬롯의 지난 결과를 읽고(펜스로 끝난 뒤라 기다림 없음) 새로 시작한다 */
    void begin(VkCommandBuffer cmd, int slot) {
        if (pool == VK_NULL_HANDLE) {
            return;
        }
        collect(slot);
        vkCmdResetQueryPool(cmd, pool, slot * POINTS, POINTS);
        vkCmdWriteTimestamp2(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, slot * POINTS);
    }

    /** pass 번째 단계가 끝난 지점 (0 부터) */
    void end(VkCommandBuffer cmd, int slot, int pass) {
        if (pool == VK_NULL_HANDLE) {
            return;
        }
        vkCmdWriteTimestamp2(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, slot * POINTS + pass + 1);
        if (pass == NAMES.length - 1) {
            written[slot] = true;
        }
    }

    private void collect(int slot) {
        if (!written[slot]) {
            return;
        }
        written[slot] = false;
        try (MemoryStack stack = stackPush()) {
            LongBuffer ticks = stack.mallocLong(POINTS);
            if (vkGetQueryPoolResults(vk(), pool, slot * POINTS, POINTS, ticks, Long.BYTES, VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) {
                return;
            }
            long[] t = new long[POINTS];
            for (int i = 0; i < POINTS; i++) {
                t[i] = ticks.get(i);
            }
            double[] ms = GpuTimer.intervalsMs(t, validMask, nanosPerTick);
            for (int i = 0; i < ms.length; i++) {
                sum[i] += ms[i];
                total[i] += ms[i];
            }
            count++;
            totalCount++;
        }
        if ((System.nanoTime() - lastReport) / 1e9 >= REPORT_SECONDS && count > 0) {
            Log.info("GPU 단계(최근 평균 ms): " + format(sum, count) + " | 그림자 그린 물체×단계 " + shadowDrawn.getAsInt()
                    + ", 건너뜀 " + shadowSkipped.getAsInt());
            java.util.Arrays.fill(sum, 0);
            count = 0;
            lastReport = System.nanoTime();
        }
    }

    static String format(double[] s, int n) {
        StringBuilder b = new StringBuilder();
        double all = 0;
        for (int i = 0; i < NAMES.length; i++) {
            all += s[i] / n;
            b.append(String.format(Locale.ROOT, "%s %.2f, ", NAMES[i], s[i] / n));
        }
        return b.append(String.format(Locale.ROOT, "합 %.2f", all)).toString();
    }

    @Override
    protected void destroy() {
        if (totalCount > 0) {
            Log.info("GPU 단계(전체 평균 ms, " + totalCount + " 프레임): " + format(total, totalCount));
        }
        if (pool != VK_NULL_HANDLE) {
            vkDestroyQueryPool(vk(), pool, null);
        }
    }
}
