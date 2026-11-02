package driftengine.render;

import driftengine.core.Log;
import driftengine.vulkan.ColorImage;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.FrameResources;
import driftengine.vulkan.ImageBarrier;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * 화면 공간 AO (구석·틈의 그늘): 깊이 사전 패스의 깊이로 ao.frag → ao_blur.frag 를 돌려 렌더 해상도 R8 이미지를 만든다.
 * 장면 셰이더는 그 결과로 주변광(하늘빛)만 어둡게 한다 (해 빛은 그림자 맵이 처리).
 * <p>
 * 이미지는 렌더 해상도를 따라 다시 만들고, 이전 이미지는 GPU 가 다 쓸 때까지(프레임 2개) 기다렸다 지운다.
 * 디스크립터 세트는 프레임 슬롯마다 따로 (쓰는 중인 세트를 고치지 않도록).
 */
public final class AmbientOcclusion extends DeviceResource {

    public static final int FORMAT = VK_FORMAT_R8_UNORM;

    private record Images(ColorImage raw, ColorImage done, int width, int height) {
        void close() {
            raw.close();
            done.close();
        }
    }

    private record Retired(Images images, long frame) {
    }

    private final long frameSetLayout;
    private long setLayout = VK_NULL_HANDLE;
    private long pool = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long aoPipeline = VK_NULL_HANDLE;
    private long blurPipeline = VK_NULL_HANDLE;
    private long depthSampler = VK_NULL_HANDLE;
    private long linearSampler = VK_NULL_HANDLE;
    private final long[] aoSets = new long[FrameResources.FRAMES_IN_FLIGHT];
    private final long[] blurSets = new long[FrameResources.FRAMES_IN_FLIGHT];
    private final long[] setDepth = new long[FrameResources.FRAMES_IN_FLIGHT];
    private final long[] setRaw = new long[FrameResources.FRAMES_IN_FLIGHT];
    private Images images;
    private final List<Retired> retired = new ArrayList<>();
    private long frames;

    public AmbientOcclusion(VulkanDevice device, long frameSetLayout) {
        super(device);
        this.frameSetLayout = frameSetLayout;
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            check(vkCreateDescriptorSetLayout(vk(), VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p),
                    "vkCreateDescriptorSetLayout(ao)");
            setLayout = p.get(0);
            int n = FrameResources.FRAMES_IN_FLIGHT * 2;
            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
            size.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(n * 2);
            check(vkCreateDescriptorPool(vk(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(n).pPoolSizes(size), null, p),
                    "vkCreateDescriptorPool(ao)");
            pool = p.get(0);
            LongBuffer layouts = stack.mallocLong(n);
            for (int i = 0; i < n; i++) {
                layouts.put(i, setLayout);
            }
            LongBuffer sets = stack.mallocLong(n);
            check(vkAllocateDescriptorSets(vk(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), sets), "vkAllocateDescriptorSets(ao)");
            for (int i = 0; i < FrameResources.FRAMES_IN_FLIGHT; i++) {
                aoSets[i] = sets.get(2 * i);
                blurSets[i] = sets.get(2 * i + 1);
            }
            check(vkCreatePipelineLayout(vk(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(frameSetLayout, setLayout)), null, p), "vkCreatePipelineLayout(ao)");
            pipelineLayout = p.get(0);
            check(vkCreateSampler(vk(), VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, p), "vkCreateSampler(ao depth)");
            depthSampler = p.get(0);
            check(vkCreateSampler(vk(), VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, p), "vkCreateSampler(ao)");
            linearSampler = p.get(0);
            aoPipeline = FullscreenPass.createPipeline(device, "/shaders/ao.frag.spv", FORMAT, pipelineLayout);
            blurPipeline = FullscreenPass.createPipeline(device, "/shaders/ao_blur.frag.spv", FORMAT, pipelineLayout);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    /**
     * 프레임 시작: 렌더 해상도에 맞는 이미지를 준비하고 결과 이미지 뷰를 돌려준다 (장면 세트 binding 2 에 연결용).
     */
    public long prepare(int width, int height) {
        frames++;
        retired.removeIf(r -> {
            if (frames - r.frame() > FrameResources.FRAMES_IN_FLIGHT + 1) {
                r.images().close();
                return true;
            }
            return false;
        });
        if (images == null || images.width() != width || images.height() != height) {
            if (images != null) {
                retired.add(new Retired(images, frames));
            }
            ColorImage raw = new ColorImage(device, FORMAT, width, height,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "aoRaw");
            ColorImage done = new ColorImage(device, FORMAT, width, height,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "ao");
            images = new Images(raw, done, width, height);
            Log.info("화면 공간 AO " + width + "x" + height);
        }
        return images.done().asGpuImage().view();
    }

    public long sampler() {
        return linearSampler;
    }

    private void write(long set, int binding, long view, long sampler) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            vkUpdateDescriptorSets(vk(), w, null);
        }
    }

    /**
     * AO 를 계산한다. 깊이는 사전 패스가 쓴 상태(DEPTH_STENCIL_ATTACHMENT_OPTIMAL)로 들어와 같은 상태로 나간다.
     * 결과 이미지는 SHADER_READ_ONLY_OPTIMAL.
     */
    public void record(VkCommandBuffer cmd, MemoryStack stack, RenderTarget t, long frameSet) {
        int slot = t.frameIndex();
        long raw = images.raw().asGpuImage().view();
        if (setDepth[slot] != t.depthImageView() || setRaw[slot] != raw) {
            write(aoSets[slot], 0, t.depthImageView(), depthSampler);
            write(aoSets[slot], 1, raw, linearSampler);       // ao.frag 는 안 씀 (세트 모양을 맞추려고)
            write(blurSets[slot], 0, t.depthImageView(), depthSampler);
            write(blurSets[slot], 1, raw, linearSampler);
            setDepth[slot] = t.depthImageView();
            setRaw[slot] = raw;
        }
        long tests = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
        ImageBarrier.transition(stack, cmd, t.depthImage(), VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                tests, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        // 1) 날 AO
        ImageBarrier.transition(stack, cmd, images.raw().image(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        FullscreenPass.draw(cmd, stack, raw, images.width(), images.height(), aoPipeline, pipelineLayout, frameSet, aoSets[slot]);
        ImageBarrier.transition(stack, cmd, images.raw().image(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        // 2) 흐리기 → 결과
        ImageBarrier.transition(stack, cmd, images.done().image(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        FullscreenPass.draw(cmd, stack, images.done().asGpuImage().view(), images.width(), images.height(), blurPipeline,
                pipelineLayout, frameSet, blurSets[slot]);
        ImageBarrier.transition(stack, cmd, images.done().image(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        // 깊이는 다시 본 패스용으로
        ImageBarrier.transition(stack, cmd, t.depthImage(), VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                tests, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
    }

    /** AO 를 끈 프레임: 결과 이미지를 읽기 상태로만 맞춘다 (셰이더는 안 읽지만 세트가 유효해야 함) */
    public void recordDisabled(VkCommandBuffer cmd, MemoryStack stack) {
        ImageBarrier.transition(stack, cmd, images.done().image(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }

    @Override
    protected void destroy() {
        if (images != null) {
            images.close();
        }
        for (Retired r : retired) {
            r.images().close();
        }
        retired.clear();
        if (aoPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), aoPipeline, null);
        }
        if (blurPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), blurPipeline, null);
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(vk(), pipelineLayout, null);
        }
        if (pool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(vk(), pool, null);
        }
        if (setLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(vk(), setLayout, null);
        }
        if (depthSampler != VK_NULL_HANDLE) {
            vkDestroySampler(vk(), depthSampler, null);
        }
        if (linearSampler != VK_NULL_HANDLE) {
            vkDestroySampler(vk(), linearSampler, null);
        }
    }
}
