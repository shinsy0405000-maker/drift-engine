package driftengine.ui;

import driftengine.core.Log;
import driftengine.render.PostProcess;
import driftengine.vulkan.ColorImage;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.FrameResources;
import driftengine.vulkan.GpuBuffer;
import driftengine.vulkan.ImageBarrier;
import driftengine.vulkan.ShaderModule;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
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
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * 화면 위 글자·사각형 렌더러 (톤매핑 뒤 스왑체인에 바로, 알파 섞기).
 * 글자 아틀라스는 바뀔 때만 GPU 로 다시 올리고, 정점은 프레임마다 CPU 에서 만들어 프레임 슬롯별 버퍼에 쓴다.
 */
public final class UiRenderer extends DeviceResource {

    static final int MAX_VERTICES = 120_000;
    static final int STRIDE = UiCanvas.FLOATS_PER_VERTEX * Float.BYTES;

    private final FontAtlas atlas;
    private ColorImage atlasImage;
    /** 프레임 슬롯마다 따로 (앞 프레임의 GPU 복사가 끝나기 전에 CPU 가 덮어쓰지 않게) */
    private final GpuBuffer[] staging = new GpuBuffer[FrameResources.FRAMES_IN_FLIGHT];
    private final GpuBuffer[] vertexBuffers = new GpuBuffer[FrameResources.FRAMES_IN_FLIGHT];
    private final int[] vertexCounts = new int[FrameResources.FRAMES_IN_FLIGHT];
    private boolean atlasUploaded;
    private long sampler = VK_NULL_HANDLE;
    private long setLayout = VK_NULL_HANDLE;
    private long pool = VK_NULL_HANDLE;
    private long set = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private int format;

    public UiRenderer(VulkanDevice device, int swapchainFormat, FontAtlas atlas) {
        super(device);
        this.atlas = atlas;
        try (MemoryStack stack = stackPush()) {
            atlasImage = new ColorImage(device, VK_FORMAT_R8_UNORM, FontAtlas.SIZE, FontAtlas.SIZE,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, "font-atlas");
            for (int i = 0; i < vertexBuffers.length; i++) {
                staging[i] = new GpuBuffer(device, (long) FontAtlas.SIZE * FontAtlas.SIZE, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                vertexBuffers[i] = new GpuBuffer(device, (long) MAX_VERTICES * STRIDE, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            }
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSampler(vk(), VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, p), "vkCreateSampler(ui)");
            sampler = p.get(0);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(vk(), VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(binding), null, p), "vkCreateDescriptorSetLayout(ui)");
            setLayout = p.get(0);
            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
            size.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            check(vkCreateDescriptorPool(vk(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(size), null, p), "vkCreateDescriptorPool(ui)");
            pool = p.get(0);
            check(vkAllocateDescriptorSets(vk(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), p), "vkAllocateDescriptorSets(ui)");
            set = p.get(0);
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(atlasImage.asGpuImage().view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            vkUpdateDescriptorSets(vk(), write, null);
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(2 * Float.BYTES);
            check(vkCreatePipelineLayout(vk(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, p), "vkCreatePipelineLayout(ui)");
            pipelineLayout = p.get(0);
            format = swapchainFormat;
            pipeline = createPipeline(format);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    /**
     * 그리기 전 준비 (렌더 패스 밖): 아틀라스가 바뀌었으면 올리고, 이번 프레임 정점을 슬롯 버퍼에 쓴다.
     * sRGB 스왑체인이면 색(sRGB 로 적은 값)을 선형으로 바꿔 둔다 → 하드웨어가 다시 sRGB 로 바꿔 원래 색이 나옴.
     */
    public void prepare(MemoryStack stack, VkCommandBuffer cmd, int frameSlot, UiCanvas canvas) {
        if (atlas.dirty()) {
            byte[] px = atlas.pixels();
            staging[frameSlot].write(address -> MemoryUtil.memByteBuffer(address, px.length).put(px));
            long image = atlasImage.image();
            ImageBarrier.transition(stack, cmd, image, VK_IMAGE_ASPECT_COLOR_BIT,
                    atlasUploaded ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageExtent().set(FontAtlas.SIZE, FontAtlas.SIZE, 1);
            vkCmdCopyBufferToImage(cmd, staging[frameSlot].handle(), image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            ImageBarrier.transition(stack, cmd, image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT);
            atlas.clearDirty();
            atlasUploaded = true;
        }
        int n = Math.min(canvas.vertexCount(), MAX_VERTICES);
        vertexCounts[frameSlot] = n;
        if (n > 0) {
            float[] data = canvas.data();
            boolean linearize = PostProcess.isSrgb(format);
            vertexBuffers[frameSlot].write(address -> {
                java.nio.FloatBuffer fb = MemoryUtil.memFloatBuffer(address, n * UiCanvas.FLOATS_PER_VERTEX);
                fb.put(data, 0, n * UiCanvas.FLOATS_PER_VERTEX);
                if (linearize) {
                    for (int v = 0; v < n; v++) {
                        int o = v * UiCanvas.FLOATS_PER_VERTEX + 4;
                        for (int c = 0; c < 3; c++) {
                            fb.put(o + c, srgbToLinear(fb.get(o + c)));
                        }
                    }
                }
            });
        }
    }

    private static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    /** 스왑체인 이미지(COLOR_ATTACHMENT_OPTIMAL, 화면 처리 결과 위)에 그린다 */
    public void record(VkCommandBuffer cmd, long targetView, int width, int height, int frameSlot) {
        int n = vertexCounts[frameSlot];
        if (n == 0 || !atlasUploaded) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
            color.get(0).sType$Default().imageView(targetView).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
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
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, stack.floats(width, height));
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffers[frameSlot].handle()), stack.longs(0));
            vkCmdDraw(cmd, n, 1, 0, 0);
            vkCmdEndRendering(cmd);
        }
    }

    public void onFormatChanged(int newFormat) {
        if (newFormat == format) {
            return;
        }
        long next = createPipeline(newFormat);
        vkDestroyPipeline(vk(), pipeline, null);
        pipeline = next;
        format = newFormat;
    }

    private long createPipeline(int colorFormat) {
        try (ShaderModule vert = ShaderModule.fromResource(device, "/shaders/ui.vert.spv");
             ShaderModule frag = ShaderModule.fromResource(device, "/shaders/ui.frag.spv");
             MemoryStack stack = stackPush()) {
            ByteBuffer entry = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert.handle()).pName(entry);
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag.handle()).pName(entry);
            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
            binding.get(0).binding(0).stride(STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);
            attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attrs.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(2 * Float.BYTES);
            attrs.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(4 * Float.BYTES);
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD).colorWriteMask(0xF);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(colorFormat)).address())
                    .pStages(stages)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                            .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs))
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
            check(vkCreateGraphicsPipelines(vk(), VK_NULL_HANDLE, info, null, p), "vkCreateGraphicsPipelines(ui)");
            Log.info("화면 UI 파이프라인 생성 (글자·사각형, 포맷 " + colorFormat + ")");
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
        for (GpuBuffer b : vertexBuffers) {
            if (b != null) {
                b.close();
            }
        }
        for (GpuBuffer b : staging) {
            if (b != null) {
                b.close();
            }
        }
        if (atlasImage != null) {
            atlasImage.close();
        }
    }
}
