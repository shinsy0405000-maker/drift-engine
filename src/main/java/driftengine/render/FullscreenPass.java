package driftengine.render;

import driftengine.vulkan.ShaderModule;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/** 화면 전체 삼각형 하나로 그리는 단계의 도우미 (post.vert + 조각 셰이더, 색 첨부 하나, 깊이 없음, 섞기 없음). */
final class FullscreenPass {

    private FullscreenPass() {
    }

    static long createPipeline(VulkanDevice device, String fragSpv, int colorFormat, long layout) {
        try (ShaderModule vert = ShaderModule.fromResource(device, "/shaders/post.vert.spv");
             ShaderModule frag = ShaderModule.fromResource(device, fragSpv);
             MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert.handle()).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag.handle()).pName(stack.UTF8("main"));
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
                    .layout(layout);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE, info, null, p), "vkCreateGraphicsPipelines(" + fragSpv + ")");
            return p.get(0);
        }
    }

    /** 첨부(COLOR_ATTACHMENT_OPTIMAL)에 삼각형 하나. sets 는 set 0 부터 순서대로 묶는다 */
    static void draw(VkCommandBuffer cmd, MemoryStack stack, long view, int width, int height, long pipeline, long layout,
                     long... sets) {
        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
        color.get(0).sType$Default().imageView(view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color);
        info.renderArea().extent().set(width, height);
        vkCmdBeginRendering(cmd, info);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(sets), null);
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).width(width).height(height).minDepth(0).maxDepth(1);
        vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).extent().set(width, height);
        vkCmdSetScissor(cmd, 0, scissor);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        vkCmdEndRendering(cmd);
    }
}
