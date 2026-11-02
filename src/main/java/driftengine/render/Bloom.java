package driftengine.render;

import driftengine.core.Log;
import driftengine.vulkan.ColorImage;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.ImageBarrier;
import driftengine.vulkan.ShaderModule;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * 블룸 (밝은 빛 번짐): 출력 HDR 이미지에서 밝은 부분만 뽑아 1/2, 1/4 … 크기로 줄이며 흐리게 한 뒤,
 * 작은 것부터 큰 쪽으로 더해 올라와 1/2 크기 이미지 하나에 모은다. PostProcess 가 그것을 더한다.
 * <p>
 * 각 단계는 화면 전체 삼각형 하나 (post.vert + bloom.frag). 단계 이미지는 출력 크기가 바뀔 때 다시 만든다.
 */
public final class Bloom extends DeviceResource {

    public static final int MAX_LEVELS = 6;
    static final int MIN_SIZE = 8;
    static final int PUSH_BYTES = 8 * Float.BYTES;
    /** 이 밝기(노출 전 HDR) 위만 번진다 */
    public static final float THRESHOLD = 1.6f;
    public static final float KNEE = 0.6f;

    private final int format;
    private long sampler = VK_NULL_HANDLE;
    private long setLayout = VK_NULL_HANDLE;
    private long pool = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long downPipeline = VK_NULL_HANDLE;
    private long upPipeline = VK_NULL_HANDLE;
    private final long[] downSets = new long[MAX_LEVELS];
    private final long[] upSets = new long[MAX_LEVELS];
    private final List<ColorImage> levels = new ArrayList<>();
    private int inputWidth;
    private int inputHeight;

    public Bloom(VulkanDevice device, int format) {
        super(device);
        this.format = format;
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSampler(vk(), VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0), null, p), "vkCreateSampler(bloom)");
            sampler = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(vk(), VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(binding), null, p), "vkCreateDescriptorSetLayout(bloom)");
            setLayout = p.get(0);

            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
            size.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2 * MAX_LEVELS);
            check(vkCreateDescriptorPool(vk(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(2 * MAX_LEVELS).pPoolSizes(size), null, p), "vkCreateDescriptorPool(bloom)");
            pool = p.get(0);
            LongBuffer layouts = stack.mallocLong(2 * MAX_LEVELS);
            for (int i = 0; i < 2 * MAX_LEVELS; i++) {
                layouts.put(i, setLayout);
            }
            LongBuffer sets = stack.mallocLong(2 * MAX_LEVELS);
            check(vkAllocateDescriptorSets(vk(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), sets), "vkAllocateDescriptorSets(bloom)");
            for (int i = 0; i < MAX_LEVELS; i++) {
                downSets[i] = sets.get(i);
                upSets[i] = sets.get(MAX_LEVELS + i);
            }

            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_BYTES);
            check(vkCreatePipelineLayout(vk(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, p), "vkCreatePipelineLayout(bloom)");
            pipelineLayout = p.get(0);
            downPipeline = createPipeline(false);
            upPipeline = createPipeline(true);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    /** 단계별 크기 (GPU 없이 테스트 가능): 1/2 부터 절반씩, 짧은 변이 MIN_SIZE 아래가 되기 전까지, 최대 MAX_LEVELS */
    public static List<int[]> levelSizes(int width, int height) {
        List<int[]> out = new ArrayList<>();
        int w = width / 2;
        int h = height / 2;
        while (out.size() < MAX_LEVELS && Math.min(w, h) >= MIN_SIZE) {
            out.add(new int[]{w, h});
            w /= 2;
            h /= 2;
        }
        return out;
    }

    /** 출력 크기가 정해지거나 바뀌면 (GPU 가 쉬는 동안) 단계 이미지와 디스크립터를 다시 만든다 */
    public void resize(int width, int height, long inputView) {
        closeLevels();
        inputWidth = width;
        inputHeight = height;
        for (int[] s : levelSizes(width, height)) {
            levels.add(new ColorImage(device, format, s[0], s[1],
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "bloom" + levels.size()));
        }
        for (int i = 0; i < levels.size(); i++) {
            write(downSets[i], i == 0 ? inputView : levels.get(i - 1).asGpuImage().view());
            write(upSets[i], levels.get(i).asGpuImage().view());
        }
        Log.info("블룸 단계 " + levels.size() + "개 (" + (levels.isEmpty() ? "없음" : levels.get(0).width() + "x" + levels.get(0).height() + " 부터") + ")");
    }

    /** 블룸 결과 (단계 0, SHADER_READ_ONLY_OPTIMAL). 단계가 없으면(아주 작은 창) 0 */
    public long resultView() {
        return levels.isEmpty() ? VK_NULL_HANDLE : levels.get(0).asGpuImage().view();
    }

    private void write(long set, long view) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            vkUpdateDescriptorSets(vk(), w, null);
        }
    }

    /**
     * 입력(출력 HDR 이미지)은 SHADER_READ_ONLY_OPTIMAL 이고 조각 셰이더 읽기가 기다려진 상태여야 한다.
     * 끝나면 단계 0 이 SHADER_READ_ONLY_OPTIMAL (PostProcess 가 읽음).
     */
    public void record(MemoryStack stack, VkCommandBuffer cmd) {
        int n = levels.size();
        if (n == 0) {
            return;
        }
        // 아래로: 단계 i ← (i == 0 ? 입력 : 단계 i-1)
        for (int i = 0; i < n; i++) {
            ColorImage dst = levels.get(i);
            int srcW = i == 0 ? inputWidth : levels.get(i - 1).width();
            int srcH = i == 0 ? inputHeight : levels.get(i - 1).height();
            ImageBarrier.transition(stack, cmd, dst.image(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            pass(stack, cmd, dst, downPipeline, downSets[i], false,
                    1f / srcW, 1f / srcH, i == 0 ? 0 : 1, 0, 0);
            ImageBarrier.transition(stack, cmd, dst.image(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }
        // 위로: 단계 i-1 += 흐린(단계 i)
        for (int i = n - 1; i >= 1; i--) {
            ColorImage dst = levels.get(i - 1);
            ColorImage src = levels.get(i);
            ImageBarrier.transition(stack, cmd, dst.image(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            pass(stack, cmd, dst, upPipeline, upSets[i], true,
                    1f / src.width(), 1f / src.height(), 2, 1.0f, 1.0f);
            ImageBarrier.transition(stack, cmd, dst.image(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }
    }

    private void pass(MemoryStack stack, VkCommandBuffer cmd, ColorImage dst, long pipeline, long set, boolean load,
                      float texelX, float texelY, int mode, float radius, float strength) {
        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
        color.get(0).sType$Default().imageView(dst.asGpuImage().view())
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(load ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color);
        info.renderArea().extent().set(dst.width(), dst.height());
        vkCmdBeginRendering(cmd, info);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(set), null);
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).width(dst.width()).height(dst.height()).minDepth(0).maxDepth(1);
        vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).extent().set(dst.width(), dst.height());
        vkCmdSetScissor(cmd, 0, scissor);
        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                stack.floats(texelX, texelY, THRESHOLD, KNEE, mode, radius, strength, 0));
        vkCmdDraw(cmd, 3, 1, 0, 0);
        vkCmdEndRendering(cmd);
    }

    private long createPipeline(boolean additive) {
        try (ShaderModule vert = ShaderModule.fromResource(device, "/shaders/post.vert.spv");
             ShaderModule frag = ShaderModule.fromResource(device, "/shaders/bloom.frag.spv");
             MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert.handle()).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag.handle()).pName(stack.UTF8("main"));
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).blendEnable(additive)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .alphaBlendOp(VK_BLEND_OP_ADD)
                    .colorWriteMask(0xF);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                            .pColorAttachmentFormats(stack.ints(format)).address())
                    .pStages(stages)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default())
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(pipelineLayout);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(vk(), VK_NULL_HANDLE, info, null, p), "vkCreateGraphicsPipelines(bloom)");
            return p.get(0);
        }
    }

    private void closeLevels() {
        for (ColorImage c : levels) {
            c.close();
        }
        levels.clear();
    }

    @Override
    protected void destroy() {
        closeLevels();
        if (downPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), downPipeline, null);
        }
        if (upPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), upPipeline, null);
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
        if (sampler != VK_NULL_HANDLE) {
            vkDestroySampler(vk(), sampler, null);
        }
    }
}
