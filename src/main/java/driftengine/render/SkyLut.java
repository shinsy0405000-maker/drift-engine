package driftengine.render;

import driftengine.vulkan.ColorImage;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.ImageBarrier;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * 하늘 표: 방향(방위각 × 고도) → 하늘 색, 대기 산란으로 매 프레임 채운다 (sky_lut.frag, 256×128).
 * 하늘·반사·안개가 모두 이 표를 읽어 서로 같은 색이 된다.
 */
public final class SkyLut extends DeviceResource {

    public static final int WIDTH = 256;
    public static final int HEIGHT = 128;
    public static final int FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

    private final ColorImage image;
    private long sampler = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private final long layout;

    /** @param layout 프레임 세트(set 0)를 쓰는 파이프라인 레이아웃 */
    public SkyLut(VulkanDevice device, long layout) {
        super(device);
        this.layout = layout;
        ColorImage img = null;
        try {
            img = new ColorImage(device, FORMAT, WIDTH, HEIGHT, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "skyLut");
            try (MemoryStack stack = stackPush()) {
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateSampler(vk(), VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)       // 방위각은 한 바퀴 이어짐
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .maxLod(0), null, p), "vkCreateSampler(skyLut)");
                sampler = p.get(0);
            }
            pipeline = FullscreenPass.createPipeline(device, "/shaders/sky_lut.frag.spv", FORMAT, layout);
        } catch (RuntimeException e) {
            if (img != null) {
                img.close();
            }
            destroy();
            throw e;
        }
        this.image = img;
    }

    /** 표를 다시 채우고 셰이더 읽기 상태로 (렌더링 단계 바깥에서) */
    public void record(VkCommandBuffer cmd, MemoryStack stack, long frameSet) {
        ImageBarrier.transition(stack, cmd, image.image(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        FullscreenPass.draw(cmd, stack, image.asGpuImage().view(), WIDTH, HEIGHT, pipeline, layout, frameSet);
        ImageBarrier.transition(stack, cmd, image.image(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }

    public long view() {
        return image.asGpuImage().view();
    }

    public long sampler() {
        return sampler;
    }

    @Override
    protected void destroy() {
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), pipeline, null);
        }
        if (sampler != VK_NULL_HANDLE) {
            vkDestroySampler(vk(), sampler, null);
        }
        if (image != null) {
            image.close();
        }
    }
}
