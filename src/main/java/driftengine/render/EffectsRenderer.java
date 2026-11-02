package driftengine.render;

import driftengine.core.Log;
import driftengine.math.Vec3;
import driftengine.scene.Effects;
import driftengine.scene.mesh.VertexFormat;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.FrameResources;
import driftengine.vulkan.GpuBuffer;
import driftengine.vulkan.ShaderModule;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
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
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * 반투명 효과 그리기: 스키드 자국(땅 위 띠) + 타이어 연기(카메라를 보는 사각형).
 * <p>
 * 불투명 장면을 다 그린 뒤 별도 렌더링 단계로 그린다: 색(섞기) + 깊이(읽기만). 모션 벡터는 쓰지 않는다
 * → 그 자리의 배경 모션 벡터가 남는데, 연기·자국은 거의 땅에 붙어 있으므로 DLSS 에 맞는 값이다.
 * <p>
 * 정점은 장면과 같은 56바이트 형식을 쓰고 의미만 바꾼다: material = (진하기, 종류 0 자국/1 연기, u, v),
 * surface = 무늬 씨앗. 정점 버퍼는 프레임 슬롯마다 하나(CPU 에서 씀), 인덱스는 사각형 반복이라 고정.
 */
public final class EffectsRenderer extends DeviceResource {

    public static final int MAX_QUADS = Effects.MAX_SKID + Effects.MAX_SMOKE;
    private static final int F = VertexFormat.FLOATS_PER_VERTEX;

    private final long pipelineLayout;
    private final int depthFormat;
    private long pipeline = VK_NULL_HANDLE;
    private int colorFormat;
    private final GpuBuffer[] vertexBuffers = new GpuBuffer[FrameResources.FRAMES_IN_FLIGHT];
    private GpuBuffer indexBuffer;
    private final float[] staging = new float[MAX_QUADS * 4 * F];
    private final long[] sortKeys = new long[Effects.MAX_SMOKE];

    /** @param pipelineLayout 장면 파이프라인 레이아웃 (프레임 uniform + 물체 행렬 push) 을 같이 쓴다 */
    public EffectsRenderer(VulkanDevice device, int colorFormat, int depthFormat, long pipelineLayout) {
        super(device);
        this.pipelineLayout = pipelineLayout;
        this.depthFormat = depthFormat;
        try {
            int[] indices = new int[MAX_QUADS * 6];
            for (int q = 0; q < MAX_QUADS; q++) {
                int v = q * 4;
                int o = q * 6;
                indices[o] = v;
                indices[o + 1] = v + 1;
                indices[o + 2] = v + 2;
                indices[o + 3] = v;
                indices[o + 4] = v + 2;
                indices[o + 5] = v + 3;
            }
            indexBuffer = GpuBuffer.ofInts(device, indices, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            for (int i = 0; i < vertexBuffers.length; i++) {
                vertexBuffers[i] = new GpuBuffer(device, (long) staging.length * Float.BYTES, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            }
            this.colorFormat = colorFormat;
            pipeline = createPipeline(device, "/shaders/fx.frag.spv", colorFormat, depthFormat, pipelineLayout, false);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    /**
     * 효과 목록 → 정점 (GPU 없이 테스트 가능). 연기는 카메라에서 먼 것부터.
     *
     * @return 사각형 수
     */
    public static int build(Effects fx, Vec3 camPos, Vec3 right, Vec3 up, float[] out, long[] keys) {
        int quads = 0;
        float[] s = fx.skidData();
        for (int i = 0; i < fx.skidCount(); i++) {
            int o = i * Effects.SKID_FLOATS;
            float a = s[o + 12];
            float v0 = s[o + 13];
            float v1 = s[o + 14];
            int base = quads * 4 * F;
            // 왼A(u0,v0) 오른A(u1,v0) 오른B(u1,v1) 왼B(u0,v1)
            vertex(out, base, s[o], s[o + 1], s[o + 2], 0, 1, 0, 1, 1, 1, a, 0, 0, v0, 0);
            vertex(out, base + F, s[o + 3], s[o + 4], s[o + 5], 0, 1, 0, 1, 1, 1, a, 0, 1, v0, 0);
            vertex(out, base + 2 * F, s[o + 6], s[o + 7], s[o + 8], 0, 1, 0, 1, 1, 1, a, 0, 1, v1, 0);
            vertex(out, base + 3 * F, s[o + 9], s[o + 10], s[o + 11], 0, 1, 0, 1, 1, 1, a, 0, 0, v1, 0);
            quads++;
        }
        float[] p = fx.smokeData();
        int n = fx.smokeCount();
        for (int i = 0; i < n; i++) {
            int o = i * Effects.SMOKE_FLOATS;
            float dx = p[o] - camPos.x();
            float dy = p[o + 1] - camPos.y();
            float dz = p[o + 2] - camPos.z();
            float d2 = dx * dx + dy * dy + dz * dz; // 양수 float 의 비트는 크기 순서와 같다
            keys[i] = ((long) Float.floatToIntBits(d2) << 32) | i;
        }
        Arrays.sort(keys, 0, n);
        Vec3 fwd = right.cross(up); // 카메라 쪽을 보는 법선 (오른손: right × up = 뒤쪽)
        for (int k = n - 1; k >= 0; k--) {
            int i = (int) (keys[k] & 0xFFFFFFFFL);
            int o = i * Effects.SMOKE_FLOATS;
            float size = p[o + 3];
            float rot = p[o + 5];
            float c = (float) Math.cos(rot) * size;
            float sn = (float) Math.sin(rot) * size;
            // 회전한 축: r' = r·cos + u·sin, u' = -r·sin + u·cos
            float rx = right.x() * c + up.x() * sn;
            float ry = right.y() * c + up.y() * sn;
            float rz = right.z() * c + up.z() * sn;
            float ux = -right.x() * sn + up.x() * c;
            float uy = -right.y() * sn + up.y() * c;
            float uz = -right.z() * sn + up.z() * c;
            float x = p[o];
            float y = p[o + 1];
            float z = p[o + 2];
            float a = p[o + 4];
            float shade = p[o + 6];
            float seed = p[o + 7];
            int base = quads * 4 * F;
            vertex(out, base, x - rx - ux, y - ry - uy, z - rz - uz, fwd.x(), fwd.y(), fwd.z(), shade, shade, shade, a, 1, 0, 1, seed);
            vertex(out, base + F, x + rx - ux, y + ry - uy, z + rz - uz, fwd.x(), fwd.y(), fwd.z(), shade, shade, shade, a, 1, 1, 1, seed);
            vertex(out, base + 2 * F, x + rx + ux, y + ry + uy, z + rz + uz, fwd.x(), fwd.y(), fwd.z(), shade, shade, shade, a, 1, 1, 0, seed);
            vertex(out, base + 3 * F, x - rx + ux, y - ry + uy, z - rz + uz, fwd.x(), fwd.y(), fwd.z(), shade, shade, shade, a, 1, 0, 0, seed);
            quads++;
        }
        return quads;
    }

    private static void vertex(float[] out, int o, float x, float y, float z, float nx, float ny, float nz,
                               float r, float g, float b, float alpha, float kind, float u, float v, float seed) {
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        out[o + 3] = nx;
        out[o + 4] = ny;
        out[o + 5] = nz;
        out[o + 6] = r;
        out[o + 7] = g;
        out[o + 8] = b;
        out[o + 9] = alpha;
        out[o + 10] = kind;
        out[o + 11] = u;
        out[o + 12] = v;
        out[o + 13] = seed;
    }

    /** 이번 프레임 정점을 이 슬롯 버퍼에 올린다. @return 그릴 사각형 수 (0 이면 그릴 것 없음) */
    public int prepare(int slot, Effects fx, Vec3 camPos, Vec3 right, Vec3 up) {
        if (fx == null || fx.isEmpty()) {
            return 0;
        }
        int quads = build(fx, camPos, right, up, staging, sortKeys);
        int floats = quads * 4 * F;
        vertexBuffers[slot].write(address -> MemoryUtil.memFloatBuffer(address, floats).put(staging, 0, floats));
        return quads;
    }

    /**
     * 투명 단계(색 + 깊이 읽기, SceneRenderer 가 연 렌더링 안)에서 그린다. 뷰포트·시저는 이미 정해진 상태.
     *
     * @param pushIdentity 물체 행렬 push constant (단위 행렬 두 개)
     */
    public void draw(VkCommandBuffer cmd, MemoryStack stack, int slot, int quads, long frameSet, FloatBuffer pushIdentity) {
        if (quads <= 0) {
            return;
        }
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(frameSet), null);
        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pushIdentity);
        vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffers[slot].handle()), stack.longs(0));
        vkCmdBindIndexBuffer(cmd, indexBuffer.handle(), 0, VK_INDEX_TYPE_UINT32);
        vkCmdDrawIndexed(cmd, quads * 6, 1, 0, 0, 0);
    }

    public void onTargetFormatChanged(int newFormat) {
        if (newFormat == colorFormat) {
            return;
        }
        long next = createPipeline(device, "/shaders/fx.frag.spv", newFormat, depthFormat, pipelineLayout, false);
        vkDestroyPipeline(vk(), pipeline, null);
        pipeline = next;
        colorFormat = newFormat;
    }

    /**
     * 투명 단계용 파이프라인 (scene.vert + frag): 색 하나 + 깊이 읽기만, 양면.
     *
     * @param premultiplied true 면 미리 곱한 알파 (ONE, 1 - a: 유리), false 면 일반 알파 (a, 1 - a: 연기·자국)
     */
    static long createPipeline(VulkanDevice device, String fragSpv, int format, int depthFormat, long pipelineLayout,
                               boolean premultiplied) {
        try (ShaderModule vert = ShaderModule.fromResource(device, "/shaders/scene.vert.spv");
             ShaderModule frag = ShaderModule.fromResource(device, fragSpv);
             MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert.handle()).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag.handle()).pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
            binding.get(0).binding(0).stride(VertexFormat.STRIDE_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(5, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(VertexFormat.POSITION_OFFSET_BYTES);
            attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(VertexFormat.NORMAL_OFFSET_BYTES);
            attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(VertexFormat.COLOR_OFFSET_BYTES);
            attributes.get(3).location(3).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(VertexFormat.MATERIAL_OFFSET_BYTES);
            attributes.get(4).location(4).binding(0).format(VK_FORMAT_R32_SFLOAT).offset(VertexFormat.SURFACE_OFFSET_BYTES);

            // 일반 알파 섞기: 색 = 새 색·a + 기존·(1-a)
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blend.get(0).blendEnable(true)
                    .srcColorBlendFactor(premultiplied ? VK_BLEND_FACTOR_ONE : VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                            .pColorAttachmentFormats(stack.ints(format)).depthAttachmentFormat(depthFormat).address())
                    .pStages(stages)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                            .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes))
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                            .viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                            .depthTestEnable(true).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(pipelineLayout);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device.handle(), VK_NULL_HANDLE, info, null, p), "vkCreateGraphicsPipelines(" + fragSpv + ")");
            Log.info("투명 파이프라인 생성 (" + fragSpv + (premultiplied ? ", 미리 곱한 알파" : ", 알파 섞기") + ")");
            return p.get(0);
        }
    }

    @Override
    protected void destroy() {
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), pipeline, null);
        }
        for (GpuBuffer b : vertexBuffers) {
            if (b != null) {
                b.close();
            }
        }
        if (indexBuffer != null) {
            indexBuffer.close();
        }
    }
}
