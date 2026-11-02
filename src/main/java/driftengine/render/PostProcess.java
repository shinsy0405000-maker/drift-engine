package driftengine.render;

import driftengine.core.Log;
import driftengine.vulkan.DeviceResource;
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

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/**
 * 마지막 화면 처리: 출력 해상도 HDR 이미지(업스케일 결과) + 블룸을 읽어 노출 → ACES 톤매핑 → 색감 → 비네트를 거쳐
 * 스왑체인 이미지에 그린다 (post.vert / post.frag, 화면 전체 삼각형 하나).
 * <p>
 * DLSS 는 톤매핑 전 선형 HDR 색을 받는 것이 권장이므로, 톤매핑은 업스케일 뒤에 한다.
 * 스왑체인이 sRGB 포맷이면 하드웨어가 감마를 입히고, UNORM 이면 셰이더가 직접 입힌다.
 */
public final class PostProcess extends DeviceResource {

    static final int PUSH_BYTES = 16 * Float.BYTES;

    private long sampler = VK_NULL_HANDLE;
    private long setLayout = VK_NULL_HANDLE;
    private long pool = VK_NULL_HANDLE;
    private long set = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private int format;
    private float exposure = 1.0f;
    private float vignette = 0.35f;
    private final RenderSettings settings = RenderSettings.fromSystem();
    private float bloom = settings.bloom();

    public PostProcess(VulkanDevice device, int swapchainFormat) {
        super(device);
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0);
            check(vkCreateSampler(vk(), samplerInfo, null, p), "vkCreateSampler(post)");
            sampler = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int b = 0; b < 3; b++) {
                binding.get(b).binding(b).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            check(vkCreateDescriptorSetLayout(vk(), VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(binding), null, p), "vkCreateDescriptorSetLayout(post)");
            setLayout = p.get(0);

            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
            size.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
            check(vkCreateDescriptorPool(vk(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(size), null, p), "vkCreateDescriptorPool(post)");
            pool = p.get(0);
            check(vkAllocateDescriptorSets(vk(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), p), "vkAllocateDescriptorSets(post)");
            set = p.get(0);

            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_BYTES);
            check(vkCreatePipelineLayout(vk(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, p), "vkCreatePipelineLayout(post)");
            pipelineLayout = p.get(0);
            format = swapchainFormat;
            pipeline = createPipeline(format);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    /** sRGB 포맷이면 하드웨어가 감마 변환을 한다 */
    public static boolean isSrgb(int format) {
        return format == VK_FORMAT_B8G8R8A8_SRGB || format == VK_FORMAT_R8G8B8A8_SRGB || format == VK_FORMAT_A8B8G8R8_SRGB_PACK32;
    }

    /**
     * 읽을 HDR 이미지와 블룸 이미지 (출력 이미지를 새로 만들 때마다, GPU 가 쉬는 동안 부른다).
     * 둘 다 레이아웃 SHADER_READ_ONLY_OPTIMAL. 블룸이 없으면 bloomView 에 hdr 를 주고 세기를 0 으로.
     */
    public void setInput(long imageView, long bloomView, boolean bloomAvailable, long motionView) {
        long[] views = {imageView, bloomView, motionView};
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(3, stack);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(3, stack);
            for (int b = 0; b < 3; b++) {
                info.get(b).sampler(sampler).imageView(views[b]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                write.get(b).sType$Default().dstSet(set).dstBinding(b).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(VkDescriptorImageInfo.create(info.address(b), 1));
            }
            vkUpdateDescriptorSets(vk(), write, null);
        }
        this.bloomAvailable = bloomAvailable;
    }

    private boolean bloomAvailable;

    public void setBloom(float strength) {
        this.bloom = Math.max(0, strength);
    }

    /** 스왑체인 포맷이 바뀌면 파이프라인을 다시 만든다 */
    public void onFormatChanged(int newFormat) {
        if (newFormat == format) {
            return;
        }
        long next = createPipeline(newFormat);
        vkDestroyPipeline(vk(), pipeline, null);
        pipeline = next;
        format = newFormat;
    }

    public void setExposure(float exposure) {
        this.exposure = exposure;
    }

    /**
     * 스왑체인 이미지(레이아웃 COLOR_ATTACHMENT_OPTIMAL)에 그린다.
     */
    public void record(VkCommandBuffer cmd, long targetView, int width, int height, long frameNumber, float[] sun) {
        try (MemoryStack stack = stackPush()) {
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
            color.get(0).sType$Default().imageView(targetView).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color);
            info.renderArea().extent().set(width, height);
            vkCmdBeginRendering(cmd, info);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(set), null);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).width(width).height(height).minDepth(0).maxDepth(1);
            vkCmdSetViewport(cmd, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent().set(width, height);
            vkCmdSetScissor(cmd, 0, scissor);
            boolean sunOn = sun != null && sun[2] > 0.5f;
            FloatBuffer push = stack.floats(exposure, isSrgb(format) ? 0f : 1f, vignette, (float) (frameNumber % 1024),
                    bloomAvailable ? bloom : 0f, settings.motionBlur(), settings.grain(), settings.chromatic(),
                    sunOn ? sun[0] : 0f, sunOn ? sun[1] : 0f, sunOn ? 1f : 0f, bloomAvailable ? settings.flare() : 0f,
                    settings.agx() ? 1f : 0f, settings.saturation(), 0f, 0f);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
            vkCmdDraw(cmd, 3, 1, 0, 0);
            vkCmdEndRendering(cmd);
        }
    }

    private long createPipeline(int colorFormat) {
        try (ShaderModule vert = ShaderModule.fromResource(device, "/shaders/post.vert.spv");
             ShaderModule frag = ShaderModule.fromResource(device, "/shaders/post.frag.spv");
             MemoryStack stack = stackPush()) {
            ByteBuffer entry = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert.handle()).pName(entry);
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag.handle()).pName(entry);
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).blendEnable(false).colorWriteMask(0xF);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                            .pColorAttachmentFormats(stack.ints(colorFormat)).address())
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
            check(vkCreateGraphicsPipelines(vk(), VK_NULL_HANDLE, info, null, p), "vkCreateGraphicsPipelines(post)");
            Log.info("화면 처리 파이프라인 생성 (톤매핑, 스왑체인 포맷 " + colorFormat + (isSrgb(colorFormat) ? ", sRGB" : ", 셰이더 감마") + ")");
            return p.get(0);
        }
    }

    @Override
    protected void destroy() {
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), pipeline, null);
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
