package driftengine.render;

import driftengine.core.Log;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.ShaderModule;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
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

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

/**
 * 배경을 지우고 색이 섞인 삼각형 하나를 그린다.
 * <p>
 * 꼭짓점 좌표와 색은 정점 셰이더 안에 상수로 들어 있어 정점 버퍼가 필요 없다(gl_VertexIndex 사용).
 * Vulkan 1.3 dynamic rendering 을 써서 VkRenderPass / VkFramebuffer 객체도 만들지 않는다.
 * <p>
 * GPU 자원이므로 DeviceResource 를 상속하고(extends), 그리기 역할로서 Renderer 를 구현한다(implements).
 */
public final class TriangleRenderer extends DeviceResource implements Renderer {

    /** 배경색 (선형 공간 RGBA). sRGB 스왑체인이므로 화면에서는 조금 더 밝게 보인다. */
    private static final float[] CLEAR_COLOR = {0.02f, 0.02f, 0.04f, 1.0f};

    private static final String VERTEX_SHADER = "/shaders/triangle.vert.spv";
    private static final String FRAGMENT_SHADER = "/shaders/triangle.frag.spv";

    private long pipelineLayout = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private int colorFormat;

    public TriangleRenderer(VulkanDevice device, int colorFormat) {
        super(device);
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(vk(), layoutInfo, null, pLayout), "vkCreatePipelineLayout");
            pipelineLayout = pLayout.get(0);

            this.colorFormat = colorFormat;
            pipeline = createPipeline(colorFormat);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
        Log.info("삼각형 파이프라인 생성 (출력 포맷 " + colorFormat + ")");
    }

    private long createPipeline(int format) {
        try (ShaderModule vert = ShaderModule.fromResource(device, VERTEX_SHADER);
             ShaderModule frag = ShaderModule.fromResource(device, FRAGMENT_SHADER);
             MemoryStack stack = stackPush()) {

            ByteBuffer entryPoint = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert.handle()).pName(entryPoint);
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag.handle()).pName(entryPoint);

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default(); // 정점 버퍼 없음

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            // 뷰포트/시저는 매 프레임 동적으로 지정 → 창 크기가 바뀌어도 파이프라인을 다시 만들 필요 없음
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE)
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(false)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(blendAttachment);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            // dynamic rendering: 렌더 패스 대신 출력 포맷만 알려준다
            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType$Default()
                    .pColorAttachmentFormats(stack.ints(format));

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                    .sType$Default()
                    .pNext(renderingInfo.address())
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterization)
                    .pMultisampleState(multisample)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(VK_NULL_HANDLE);

            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(vk(), VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                    "vkCreateGraphicsPipelines");
            return pPipeline.get(0);
        } // 셰이더 모듈은 파이프라인 생성 후 바로 해제해도 된다
    }

    @Override
    public void record(VkCommandBuffer cmd, RenderTarget target) {
        try (MemoryStack stack = stackPush()) {
            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.get(0)
                    .sType$Default()
                    .imageView(target.imageView())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkClearValue clear = colorAttachment.get(0).clearValue();
            for (int i = 0; i < 4; i++) {
                clear.color().float32(i, CLEAR_COLOR[i]);
            }

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                    .sType$Default()
                    .layerCount(1)
                    .pColorAttachments(colorAttachment);
            renderingInfo.renderArea().offset().set(0, 0);
            renderingInfo.renderArea().extent().set(target.width(), target.height());

            vkCmdBeginRendering(cmd, renderingInfo);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0)
                    .x(0).y(0)
                    .width(target.width()).height(target.height())
                    .minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent().set(target.width(), target.height());
            vkCmdSetScissor(cmd, 0, scissor);

            vkCmdDraw(cmd, 3, 1, 0, 0); // 정점 3개, 인스턴스 1개

            vkCmdEndRendering(cmd);
        }
    }

    @Override
    public void onTargetFormatChanged(int newFormat) {
        if (newFormat == colorFormat) {
            return;
        }
        long newPipeline = createPipeline(newFormat);
        vkDestroyPipeline(vk(), pipeline, null);
        pipeline = newPipeline;
        colorFormat = newFormat;
        Log.info("출력 포맷 변경 → 삼각형 파이프라인 재생성 (" + newFormat + ")");
    }

    @Override
    protected void destroy() {
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), pipeline, null);
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(vk(), pipelineLayout, null);
        }
    }
}
