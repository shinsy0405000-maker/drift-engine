package driftengine.render;

import driftengine.core.Log;
import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.Camera;
import driftengine.scene.SceneObject;
import driftengine.scene.mesh.VertexFormat;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.ShaderModule;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
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

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.function.Function;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * 해 그림자 4단계 (cascaded shadow maps): 카메라 앞 거리를 4구간으로 나눠 가까운 곳은 촘촘하게, 먼 곳은 넓게.
 * <p>
 * 그림자 맵은 4층짜리 깊이 배열 이미지 하나. 그릴 때는 층마다 2D 뷰, 셰이더는 배열 뷰 + 비교 샘플러로 읽는다.
 * 구간마다 카메라 절두체 조각을 감싸는 공(반지름은 화각·거리로만 정해져 회전해도 안 변함)에 맞춘 직교 투영을 쓰고,
 * 해 공간에서 텍셀 단위로 맞춰(스냅) 카메라가 움직여도 그림자 가장자리가 떨리지 않게 한다.
 * 지형을 포함한 모든 물체가 그림자를 드리운다 (castsShadow 가 켜진 것).
 */
public final class CascadedShadows extends DeviceResource {

    public static final int CASCADES = 4;
    /** 단계별 끝 거리 (카메라 앞 방향, m) */
    public static final float[] SPLITS = {9f, 28f, 85f, 260f};
    /** 해 쪽으로 더 넣는 깊이 (화면 밖 높은 물체·언덕도 그림자를 드리우게) */
    static final float CASTER_REACH = 300f;
    static final int PUSH_BYTES = 2 * 16 * Float.BYTES;

    private final int size;
    private final int format;
    private long image = VK_NULL_HANDLE;
    private long memory = VK_NULL_HANDLE;
    private long arrayView = VK_NULL_HANDLE;
    private final long[] layerViews = new long[CASCADES];
    private long sampler = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private final Mat4[] matrices = new Mat4[CASCADES];
    private final float[] texel = new float[CASCADES];
    private Vec3 cameraPos = Vec3.ZERO;
    private float sunHeight = 1;
    private final float[] planes = new float[24];
    private final float[] sphere = new float[4];
    private int drawn;
    private int skipped;

    public CascadedShadows(VulkanDevice device, int depthFormat, int size) {
        super(device);
        this.size = size;
        this.format = depthFormat;
        for (int i = 0; i < CASCADES; i++) {
            matrices[i] = new Mat4();
        }
        try {
            createImage();
            createSampler();
            createPipeline();
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
        Log.info(String.format("해 그림자 %d단계 %dx%d, 구간 %.0f / %.0f / %.0f / %.0f m", CASCADES, size, size,
                SPLITS[0], SPLITS[1], SPLITS[2], SPLITS[3]));
    }

    // ------------------------------------------------------------------ 계산 (GPU 없이 테스트 가능)

    /**
     * 구간 i 의 그림자 행렬을 out[i], 텍셀 크기(m)를 texelOut[i] 에 쓴다.
     *
     * @param aspect 화면 가로/세로
     */
    public static void computeCascades(Vec3 camPos, Vec3 camForward, float fovY, float aspect, float near,
                                       Vec3 sunDir, int size, Mat4[] out, float[] texelOut) {
        Vec3 l = sunDir.normalize();
        Vec3 up = Math.abs(l.y()) > 0.95f ? Vec3.UNIT_X : Vec3.UNIT_Y;
        Mat4 view = new Mat4().lookAt(Vec3.ZERO, l.scale(-1), up);
        float tanY = (float) Math.tan(fovY / 2);
        float tanX = tanY * aspect;
        Vec3 fwd = camForward.normalize();
        for (int i = 0; i < CASCADES; i++) {
            float n = i == 0 ? near : SPLITS[i - 1];
            float fz = SPLITS[i];
            // 절두체 조각을 감싸는 공: 가운데는 앞 방향 위 (회전과 무관한 반지름)
            float k = tanX * tanX + tanY * tanY;
            float center = Math.min(fz, (n + fz) / 2 * (1 + k));
            float rNear = (float) Math.sqrt((center - n) * (center - n) + n * n * k);
            float rFar = (float) Math.sqrt((fz - center) * (fz - center) + fz * fz * k);
            float radius = (float) Math.ceil(Math.max(rNear, rFar) * 16f) / 16f;
            Vec3 c = camPos.add(fwd.scale(center));
            Vec3 cl = view.transformPoint(c);
            float t = 2 * radius / size;
            float sx = (float) Math.floor(cl.x() / t) * t;
            float sy = (float) Math.floor(cl.y() / t) * t;
            float centerDepth = -cl.z();
            Mat4 proj = new Mat4().orthoVulkan(radius, radius, centerDepth - radius - CASTER_REACH, centerDepth + radius + 20f);
            Mat4 shifted = new Mat4().mul(new Mat4().translation(-sx, -sy, 0), view);
            out[i].mul(proj, shifted);
            texelOut[i] = t;
        }
    }

    /** 이번 프레임 단계 행렬 (record 전에) */
    public void update(Camera camera, Vec3 sunDir) {
        computeCascades(camera.position(), camera.forward(), camera.fovY(), camera.aspect(), camera.near(), sunDir, size,
                matrices, texel);
        cameraPos = camera.position();
        sunHeight = sunDir.normalize().y();
    }

    /**
     * 단계 c 에 이 물체(감싸는 공)를 그려야 하나.
     * 1) 단계 상자 밖이면 안 그림. 2) 물체와 그 그림자가 모두 앞 단계 안쪽(섞는 구간 전)에 있으면 앞 단계가 맡으므로 안 그림.
     */
    public static boolean needed(int c, float[] s, float heightSpan, Vec3 cam, float sunY, float[] cascadePlanes) {
        if (!Culling.visible(cascadePlanes, s, 0.5f)) {
            return false;
        }
        if (c == 0) {
            return true;
        }
        float prevFar = SPLITS[c - 1];
        float prevNear = c >= 2 ? SPLITS[c - 2] : 0f;
        float handled = prevFar - (prevFar - prevNear) * 0.2f; // 셰이더 섞는 비율(0.18)보다 조금 넉넉히
        float dx = s[0] - cam.x(), dy = s[1] - cam.y(), dz = s[2] - cam.z();
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (sunY < 0.05f) {
            return true; // 해가 지평선 근처: 그림자가 아주 길어서 어림하지 않는다
        }
        // 그림자가 닿는 가장 먼 수평 거리 = 높이 / tan(고도) <= 높이 / sin(고도)
        float shadowLen = Math.min(2 * s[3], heightSpan + 0.5f) / sunY;
        return dist + s[3] + shadowLen >= handled;
    }

    /** 지난 record 에서 그린 / 건너뛴 (물체 × 단계) 수 */
    public int drawnLast() {
        return drawn;
    }

    public int skippedLast() {
        return skipped;
    }

    public Mat4[] matrices() {
        return matrices;
    }

    public float[] texelSizes() {
        return texel;
    }

    public int size() {
        return size;
    }

    // ------------------------------------------------------------------ GPU

    private void createImage() {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(CASCADES)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().width(size).height(size).depth(1);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateImage(vk(), info, null, p), "vkCreateImage(cascades)");
            image = p.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(vk(), image, req);
            check(vkAllocateMemory(vk(), VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(device.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, p),
                    "vkAllocateMemory(cascades)");
            memory = p.get(0);
            check(vkBindImageMemory(vk(), image, memory, 0), "vkBindImageMemory(cascades)");
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY).format(format);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(CASCADES);
            check(vkCreateImageView(vk(), vi, null, p), "vkCreateImageView(cascades array)");
            arrayView = p.get(0);
            for (int i = 0; i < CASCADES; i++) {
                VkImageViewCreateInfo li = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
                li.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(i).layerCount(1);
                check(vkCreateImageView(vk(), li, null, p), "vkCreateImageView(cascade " + i + ")");
                layerViews[i] = p.get(0);
            }
        }
    }

    private void createSampler() {
        boolean linear = device.supportsOptimalTiling(format, VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSampler(vk(), VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .minFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                    .compareEnable(true)
                    .compareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                    .maxLod(0), null, p), "vkCreateSampler(cascades)");
            sampler = p.get(0);
        }
    }

    private void createPipeline() {
        try (ShaderModule vert = ShaderModule.fromResource(device, "/shaders/shadow.vert.spv");
             MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(PUSH_BYTES);
            check(vkCreatePipelineLayout(vk(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pPushConstantRanges(range), null, p), "vkCreatePipelineLayout(cascades)");
            pipelineLayout = p.get(0);
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(1, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert.handle()).pName(stack.UTF8("main"));
            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
            binding.get(0).binding(0).stride(VertexFormat.STRIDE_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(1, stack);
            attr.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(VertexFormat.POSITION_OFFSET_BYTES);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().depthAttachmentFormat(format).address())
                    .pStages(stages)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                            .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attr))
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                            .depthBiasEnable(true).depthBiasConstantFactor(1.25f).depthBiasSlopeFactor(1.75f)
                            .lineWidth(1.0f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                            .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default())
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(pipelineLayout);
            check(vkCreateGraphicsPipelines(vk(), VK_NULL_HANDLE, info, null, p), "vkCreateGraphicsPipelines(cascades)");
            pipeline = p.get(0);
        }
    }

    private void barrier(MemoryStack stack, VkCommandBuffer cmd, int oldLayout, int newLayout, long srcStage, long srcAccess,
                         long dstStage, long dstAccess) {
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
        b.get(0).sType$Default()
                .srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(CASCADES);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
    }

    /**
     * 4단계를 그리고 셰이더 읽기 상태로 둔다 (vkCmdBeginRendering 바깥). enabled 가 false 면 그리지 않고 상태만 맞춘다.
     */
    public void record(VkCommandBuffer cmd, List<SceneObject> objects, Function<SceneObject, GpuMesh> meshes, boolean enabled) {
        try (MemoryStack stack = stackPush()) {
            long tests = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
            barrier(stack, cmd, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_NONE,
                    tests, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            FloatBuffer push = stack.mallocFloat(32);
            LongBuffer vb = stack.mallocLong(1);
            LongBuffer offsets = stack.longs(0);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).width(size).height(size).minDepth(0).maxDepth(1);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent().set(size, size);
            drawn = 0;
            skipped = 0;
            for (int c = 0; c < CASCADES; c++) {
                Culling.planes(matrices[c], planes);
                VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                        .imageView(layerViews[c])
                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                depth.clearValue().depthStencil().depth(1.0f).stencil(0);
                VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pDepthAttachment(depth);
                info.renderArea().extent().set(size, size);
                vkCmdBeginRendering(cmd, info);
                if (enabled) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                    vkCmdSetViewport(cmd, 0, viewport);
                    vkCmdSetScissor(cmd, 0, scissor);
                    for (SceneObject o : objects) {
                        if (!o.visible() || !o.castsShadow() || o.transparent()) {
                            continue;
                        }
                        Culling.worldSphere(o, sphere);
                        if (!needed(c, sphere, Culling.worldHeightSpan(o), cameraPos, sunHeight, planes)) {
                            skipped++;
                            continue;
                        }
                        GpuMesh mesh = meshes.apply(o);
                        if (mesh == null) {
                            continue;
                        }
                        drawn++;
                        push.clear();
                        o.model().storeColumnMajor(push);
                        matrices[c].storeColumnMajor(push);
                        push.flip();
                        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, push);
                        vb.put(0, mesh.vertexBuffer());
                        vkCmdBindVertexBuffers(cmd, 0, vb, offsets);
                        vkCmdBindIndexBuffer(cmd, mesh.indexBuffer(), 0, VK_INDEX_TYPE_UINT32);
                        vkCmdDrawIndexed(cmd, mesh.indexCount(), 1, 0, 0, 0);
                    }
                }
                vkCmdEndRendering(cmd);
            }
            barrier(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    tests, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }
    }

    public long arrayView() {
        return arrayView;
    }

    public long sampler() {
        return sampler;
    }

    @Override
    protected void destroy() {
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vk(), pipeline, null);
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(vk(), pipelineLayout, null);
        }
        if (sampler != VK_NULL_HANDLE) {
            vkDestroySampler(vk(), sampler, null);
        }
        for (long v : layerViews) {
            if (v != VK_NULL_HANDLE) {
                vkDestroyImageView(vk(), v, null);
            }
        }
        if (arrayView != VK_NULL_HANDLE) {
            vkDestroyImageView(vk(), arrayView, null);
        }
        if (image != VK_NULL_HANDLE) {
            vkDestroyImage(vk(), image, null);
        }
        if (memory != VK_NULL_HANDLE) {
            vkFreeMemory(vk(), memory, null);
        }
    }
}
