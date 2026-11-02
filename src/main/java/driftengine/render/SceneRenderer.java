package driftengine.render;

import driftengine.core.Log;
import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.Camera;
import driftengine.scene.Environment;
import driftengine.scene.Scene;
import driftengine.scene.SceneObject;
import driftengine.scene.mesh.MeshData;
import driftengine.scene.mesh.VertexFormat;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.RenderTargets;
import driftengine.vulkan.ShaderModule;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
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
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * 3D 장면 렌더러. 한 프레임의 단계:
 * <ol>
 *   <li>하늘 표 (대기 산란, SkyLut)</li>
 *   <li>해 그림자 4단계 (CascadedShadows)</li>
 *   <li>깊이 사전 패스 → 화면 공간 AO (AmbientOcclusion)</li>
 *   <li>불투명 물체 (색 + 모션 벡터 + 깊이) → 하늘</li>
 *   <li>투명 단계: 차 유리·라이트 덮개 (먼 것부터) → 스키드 자국·연기</li>
 * </ol>
 * 카메라·빛은 프레임별 uniform buffer(FrameUniforms), 물체 행렬(현재/이전 model)은 push constant 128바이트.
 * 면 제거 없음(양면). 셰이더가 뒷면이면 법선을 뒤집는다.
 */
public final class SceneRenderer extends DeviceResource implements Renderer {

    /** scene.vert 의 Push 블록 크기 (model, prevModel = mat4 두 개) */
    static final int PUSH_CONSTANT_BYTES = 2 * 16 * Float.BYTES;

    /** 지우는 색 (하늘이 덮으므로 거의 안 보임, 선형 RGB) */
    private static final float[] CLEAR_COLOR = {0.25f, 0.38f, 0.55f, 1.0f};
    private static final Mat4 IDENTITY = new Mat4();

    private final Scene scene;
    private final Camera camera;
    private final int depthFormat;
    private final RenderSettings settings;
    private FrameUniforms uniforms;
    private boolean showMotion;
    private int gpuLoad;
    private long frameNumber;
    /** 슬롯 수 + 1 프레임 동안 장면에 없던 메시는 내린다 (GPU 가 확실히 다 쓴 뒤) */
    private final MeshCache<GpuMesh> meshes = new MeshCache<>(m -> new GpuMesh(device, m), GpuMesh::bytes,
            driftengine.vulkan.FrameResources.FRAMES_IN_FLIGHT + 1);
    private long pipelineLayout = VK_NULL_HANDLE;
    private long pipeline = VK_NULL_HANDLE;
    private long skyPipeline = VK_NULL_HANDLE;
    private long prepassPipeline = VK_NULL_HANDLE;
    private long glassPipeline = VK_NULL_HANDLE;
    private int colorFormat;
    private CascadedShadows shadows;
    private SkyLut skyLut;
    private AmbientOcclusion ao;
    private EffectsRenderer effects;
    private final float[] sunScreen = new float[3];
    private PassTimer passTimer;

    public SceneRenderer(VulkanDevice device, int colorFormat, int depthFormat, Scene scene, Camera camera) {
        super(device);
        this.scene = scene;
        this.camera = camera;
        this.depthFormat = depthFormat;
        this.settings = RenderSettings.fromSystem();
        try {
            uploadMeshes();
            uniforms = new FrameUniforms(device);
            createPipelineLayout();
            shadows = new CascadedShadows(device, depthFormat, settings.shadowSize());
            skyLut = new SkyLut(device, pipelineLayout);
            uniforms.bindStatic(shadows.arrayView(), shadows.sampler(), skyLut.view(), skyLut.sampler());
            ao = new AmbientOcclusion(device, uniforms.setLayout());
            this.colorFormat = colorFormat;
            createColorPipelines(colorFormat);
            prepassPipeline = createPipeline(colorFormat, Kind.PREPASS);
            effects = new EffectsRenderer(device, colorFormat, depthFormat, pipelineLayout);
            passTimer = new PassTimer(device, driftengine.vulkan.FrameResources.FRAMES_IN_FLIGHT, shadows::drawnLast, shadows::skippedLast);
            Log.info("그래픽 설정: " + settings);
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    private void createColorPipelines(int format) {
        pipeline = createPipeline(format, Kind.OPAQUE);
        skyPipeline = createPipeline(format, Kind.SKY);
        glassPipeline = EffectsRenderer.createPipeline(device, "/shaders/glass.frag.spv", format, depthFormat, pipelineLayout, true);
    }

    private void uploadMeshes() {
        for (SceneObject object : scene.objects()) {
            meshes.get(object.mesh(), frameNumber);
        }
        Log.info(String.format("장면 업로드: 물체 %d개, 메시 %d개, %.1f MB",
                scene.objects().size(), meshes.size(), meshes.totalBytes() / 1e6));
    }

    private void createPipelineLayout() {
        try (MemoryStack stack = stackPush()) {
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(PUSH_CONSTANT_BYTES);
            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(uniforms.setLayout()))
                    .pPushConstantRanges(range);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(vk(), info, null, pLayout), "vkCreatePipelineLayout(scene)");
            pipelineLayout = pLayout.get(0);
        }
    }

    private enum Kind { OPAQUE, SKY, PREPASS }

    /**
     * OPAQUE: 색 + 모션 벡터, 깊이 "같거나 가까우면" 통과·쓰기 (사전 패스가 있어도 없어도 맞음).
     * SKY: 정점 버퍼 없는 화면 전체 삼각형, 깊이 1 에서 통과, 쓰기 없음.
     * PREPASS: 깊이만 (조각 셰이더 없음, 색 첨부 없음).
     */
    private long createPipeline(int format, Kind kind) {
        boolean sky = kind == Kind.SKY;
        boolean prepass = kind == Kind.PREPASS;
        try (ShaderModule vert = ShaderModule.fromResource(device, sky ? "/shaders/sky.vert.spv" : "/shaders/scene.vert.spv");
             ShaderModule frag = prepass ? null : ShaderModule.fromResource(device, sky ? "/shaders/sky.frag.spv" : "/shaders/scene.frag.spv");
             MemoryStack stack = stackPush()) {

            ByteBuffer entry = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(prepass ? 1 : 2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert.handle()).pName(entry);
            if (!prepass) {
                stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag.handle()).pName(entry);
            }

            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
            binding.get(0).binding(0).stride(VertexFormat.STRIDE_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(5, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(VertexFormat.POSITION_OFFSET_BYTES);
            attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(VertexFormat.NORMAL_OFFSET_BYTES);
            attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(VertexFormat.COLOR_OFFSET_BYTES);
            attributes.get(3).location(3).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(VertexFormat.MATERIAL_OFFSET_BYTES);
            attributes.get(4).location(4).binding(0).format(VK_FORMAT_R32_SFLOAT).offset(VertexFormat.SURFACE_OFFSET_BYTES);
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (!sky) {
                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes);
            }

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(true)
                    .depthWriteEnable(!sky)
                    .depthCompareOp(prepass ? VK_COMPARE_OP_LESS : VK_COMPARE_OP_LESS_OR_EQUAL);

            // 색 출력 2개(색, 모션 벡터) 모두 섞지 않고 덮어쓴다 (independentBlend 를 켜지 않았으므로 두 설정이 같아야 함)
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                blend.get(i).blendEnable(false).colorWriteMask(0xF);
            }
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            if (!prepass) {
                colorBlend.pAttachments(blend);
            }
            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .depthAttachmentFormat(depthFormat);
            if (!prepass) {
                renderingInfo.pColorAttachmentFormats(stack.ints(format, RenderTargets.MOTION_FORMAT));
            }

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                    .sType$Default()
                    .pNext(renderingInfo.address())
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL)
                            .cullMode(VK_CULL_MODE_NONE)
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                            .lineWidth(1.0f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(pipelineLayout)
                    .renderPass(VK_NULL_HANDLE);

            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(vk(), VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                    "vkCreateGraphicsPipelines(" + kind + ")");
            Log.info("장면 파이프라인 생성: " + kind + " (색 " + format + ", 깊이 " + depthFormat + ")");
            return pPipeline.get(0);
        }
    }

    /** true 면 색 대신 모션 벡터를 화면에 보여준다 (M 키) */
    public void setShowMotion(boolean show) {
        this.showMotion = show;
    }

    public boolean showMotion() {
        return showMotion;
    }

    /** 성능 측정용 인공 GPU 부하: 픽셀마다 n 번 추가 계산 (0 이면 없음) */
    public void setGpuLoad(int n) {
        this.gpuLoad = Math.max(0, n);
    }

    public RenderSettings settings() {
        return settings;
    }

    /** 물체의 GPU 메시. 처음 보는 메시(차 바꾸기 등)는 지금 올린다 (CPU 에서 보이는 버퍼라 바로 쓸 수 있음) */
    private GpuMesh meshFor(SceneObject object) {
        return meshes.get(object.mesh(), frameNumber);
    }

    /** 장면에 남은 메시 표시 → 오래 빠져 있던 메시 내리기 */
    private void releaseUnusedMeshes(List<SceneObject> objects) {
        for (SceneObject o : objects) {
            meshes.mark(o.mesh(), frameNumber);
        }
        scene.retainMeshes(m -> meshes.mark(m, frameNumber));
        if (meshes.sweep(frameNumber) > 0) {
            Log.info(String.format("GPU 메시 정리: %d개 내림 (%.1f MB) → 남은 메시 %d개, %.1f MB",
                    meshes.freedCount(), meshes.freedBytes() / 1e6, meshes.size(), meshes.totalBytes() / 1e6));
        }
    }

    /** GPU 에 올라가 있는 메시 바이트 (기록용) */
    public long meshBytes() {
        return meshes.totalBytes();
    }

    /** 해의 화면 위치 (렌즈 플레어): [u, v, 앞쪽이면 1] */
    @Override
    public float[] sunScreen() {
        return sunScreen;
    }

    static void computeSunScreen(Camera camera, Vec3 sunDir, float[] out) {
        Vec3 p = camera.position().add(sunDir.normalize().scale(5000));
        float[] c = camera.viewProjection().transform(p.x(), p.y(), p.z(), 1);
        if (c[3] <= 0) {
            out[0] = out[1] = out[2] = 0;
            return;
        }
        out[0] = c[0] / c[3] * 0.5f + 0.5f;
        out[1] = c[1] / c[3] * 0.5f + 0.5f;
        out[2] = 1;
    }

    private static void viewportScissor(VkCommandBuffer cmd, MemoryStack stack, int w, int h) {
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(w).height(h).minDepth(0).maxDepth(1);
        vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).extent().set(w, h);
        vkCmdSetScissor(cmd, 0, scissor);
    }

    private final float[] viewPlanes = new float[24];
    private final float[] cullSphere = new float[4];

    /** 카메라 절두체 밖 물체는 건너뛴다 (지형 조각 등) */
    private void drawObjects(VkCommandBuffer cmd, MemoryStack stack, List<SceneObject> objects) {
        FloatBuffer push = stack.mallocFloat(32);
        LongBuffer vertexBuffers = stack.mallocLong(1);
        LongBuffer offsets = stack.longs(0);
        Culling.planes(camera.viewProjection(), viewPlanes);
        for (SceneObject object : objects) {
            Culling.worldSphere(object, cullSphere);
            if (!Culling.visible(viewPlanes, cullSphere, 1.0f)) {
                continue;
            }
            GpuMesh mesh = meshFor(object);
            push.clear();
            object.model().storeColumnMajor(push);
            object.previousModel().storeColumnMajor(push);
            push.flip();
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, push);
            vertexBuffers.put(0, mesh.vertexBuffer());
            vkCmdBindVertexBuffers(cmd, 0, vertexBuffers, offsets);
            vkCmdBindIndexBuffer(cmd, mesh.indexBuffer(), 0, VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexed(cmd, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    @Override
    public void record(VkCommandBuffer cmd, RenderTarget target) {
        int slot = target.frameIndex();
        frameNumber++;
        releaseUnusedMeshes(scene.objects());
        Environment env = scene.environment();
        boolean useShadows = settings.shadows() && !showMotion;
        boolean useAo = settings.ao() && !showMotion;
        if (useShadows) {
            shadows.update(camera, env.sunDirection());
        }
        computeSunScreen(camera, env.sunDirection(), sunScreen);
        long aoView = ao.prepare(target.width(), target.height());
        // 이 프레임 슬롯의 GPU 작업은 펜스로 끝났으므로 그 슬롯의 버퍼·세트를 고쳐도 안전하다
        uniforms.bindAo(slot, aoView, ao.sampler());
        uniforms.update(slot, camera, showMotion, gpuLoad, env, new FrameUniforms.Extras(
                useShadows ? shadows.matrices() : null, shadows.texelSizes(), shadows.size(), useAo, settings.flakes(),
                settings.heightFog(), frameNumber, target.width(), target.height()));
        long frameSet = uniforms.descriptorSet(slot);

        // 불투명 / 투명 물체 나누기
        List<SceneObject> opaque = new ArrayList<>();
        List<SceneObject> glass = new ArrayList<>();
        for (SceneObject o : scene.objects()) {
            if (!o.visible()) {
                continue;
            }
            (o.transparent() ? glass : opaque).add(o);
        }

        try (MemoryStack stack = stackPush()) {
            // 1) 하늘 표, 2) 해 그림자
            passTimer.begin(cmd, slot);
            skyLut.record(cmd, stack, frameSet);
            passTimer.end(cmd, slot, 0);
            shadows.record(cmd, scene.objects(), this::meshFor, useShadows);
            passTimer.end(cmd, slot, 1);

            // 3) 깊이 사전 패스 + AO
            if (useAo) {
                VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                        .imageView(target.depthImageView())
                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                depth.clearValue().depthStencil().depth(1.0f).stencil(0);
                VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pDepthAttachment(depth);
                info.renderArea().extent().set(target.width(), target.height());
                vkCmdBeginRendering(cmd, info);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, prepassPipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(frameSet), null);
                viewportScissor(cmd, stack, target.width(), target.height());
                drawObjects(cmd, stack, opaque);
                vkCmdEndRendering(cmd);
                ao.record(cmd, stack, target, frameSet);
            } else {
                ao.recordDisabled(cmd, stack);
            }
            passTimer.end(cmd, slot, 2);

            // 4) 불투명 물체 + 하늘
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(2, stack);
            color.get(0).sType$Default()
                    .imageView(target.imageView())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkClearValue clearColor = color.get(0).clearValue();
            for (int i = 0; i < 4; i++) {
                clearColor.color().float32(i, showMotion ? 0.5f : CLEAR_COLOR[i]);
            }
            // 모션 벡터: 하늘(물체 없음)은 0 으로 지운다. 나중에 DLSS·모션 블러가 STORE 된 값을 읽는다
            color.get(1).sType$Default()
                    .imageView(target.motionImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                    .imageView(target.depthImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(useAo ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE); // DLSS 가 그린 뒤의 깊이를 읽는다
            depth.clearValue().depthStencil().depth(1.0f).stencil(0);
            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                    .layerCount(1).pColorAttachments(color).pDepthAttachment(depth);
            renderingInfo.renderArea().extent().set(target.width(), target.height());

            vkCmdBeginRendering(cmd, renderingInfo);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(frameSet), null);
            viewportScissor(cmd, stack, target.width(), target.height());
            drawObjects(cmd, stack, opaque);
            // 하늘: 물체를 다 그린 뒤 빈 곳(깊이 1)만 채운다
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, skyPipeline);
            vkCmdDraw(cmd, 3, 1, 0, 0);
            vkCmdEndRendering(cmd);
            passTimer.end(cmd, slot, 3);

            // 5) 투명 단계 (모션 벡터 보기 중에는 생략)
            if (!showMotion) {
                recordTransparent(cmd, stack, target, frameSet, glass);
            }
            passTimer.end(cmd, slot, 4);
        }
    }

    private void recordTransparent(VkCommandBuffer cmd, MemoryStack stack, RenderTarget target, long frameSet,
                                   List<SceneObject> glass) {
        int quads = effects.prepare(target.frameIndex(), scene.effects(), camera.position(), camera.right(), camera.up());
        boolean drawGlass = settings.glass() && !glass.isEmpty();
        if (quads == 0 && !drawGlass) {
            return;
        }
        // 앞 단계의 색·깊이 쓰기가 끝난 뒤 → 섞기(색 읽기+쓰기), 깊이 읽기
        VkMemoryBarrier2.Buffer mb = VkMemoryBarrier2.calloc(1, stack);
        mb.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT
                        | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                .srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT
                        | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                .dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                        | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(mb));

        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
        color.get(0).sType$Default()
                .imageView(target.imageView())
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(target.depthImageView())
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1)
                .pColorAttachments(color).pDepthAttachment(depth);
        info.renderArea().extent().set(target.width(), target.height());
        vkCmdBeginRendering(cmd, info);
        viewportScissor(cmd, stack, target.width(), target.height());
        if (drawGlass) {
            // 먼 것부터 (투명은 뒤에서 앞으로 겹쳐야 맞게 섞임)
            Vec3 cam = camera.position();
            glass.sort((a, b) -> Float.compare(distance2(b, cam), distance2(a, cam)));
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, glassPipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(frameSet), null);
            drawObjects(cmd, stack, glass);
        }
        FloatBuffer push = stack.mallocFloat(32);
        IDENTITY.storeColumnMajor(push);
        IDENTITY.storeColumnMajor(push);
        push.flip();
        effects.draw(cmd, stack, target.frameIndex(), quads, frameSet, push);
        vkCmdEndRendering(cmd);
    }

    private static float distance2(SceneObject o, Vec3 cam) {
        float x = o.model().get(0, 3) - cam.x();
        float y = o.model().get(1, 3) - cam.y();
        float z = o.model().get(2, 3) - cam.z();
        return x * x + y * y + z * z;
    }

    @Override
    public void onTargetFormatChanged(int newFormat) {
        if (newFormat == colorFormat) {
            return;
        }
        vkDestroyPipeline(vk(), pipeline, null);
        vkDestroyPipeline(vk(), skyPipeline, null);
        vkDestroyPipeline(vk(), glassPipeline, null);
        createColorPipelines(newFormat);
        effects.onTargetFormatChanged(newFormat);
        colorFormat = newFormat;
    }

    @Override
    protected void destroy() {
        if (passTimer != null) {
            passTimer.close();
        }
        if (effects != null) {
            effects.close();
            effects = null;
        }
        for (long p : new long[]{pipeline, skyPipeline, prepassPipeline, glassPipeline}) {
            if (p != VK_NULL_HANDLE) {
                vkDestroyPipeline(vk(), p, null);
            }
        }
        if (ao != null) {
            ao.close();
        }
        if (skyLut != null) {
            skyLut.close();
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(vk(), pipelineLayout, null);
        }
        if (uniforms != null) {
            uniforms.close();
        }
        if (shadows != null) {
            shadows.close();
        }
        meshes.clear();
    }
}
