package driftengine.render;

import driftengine.math.Mat4;
import driftengine.math.Vec3;
import driftengine.scene.Camera;
import driftengine.scene.Environment;
import driftengine.vulkan.DeviceResource;
import driftengine.vulkan.FrameResources;
import driftengine.vulkan.GpuBuffer;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 프레임마다 바뀌는 카메라·빛 데이터(uniform buffer)와 공용 텍스처, 셰이더가 그것을 찾는 디스크립터 세트.
 * <p>
 * 셰이더 쪽 정의는 shaders/frame.glsl (std140, 720 바이트). binding 0 = 이 버퍼, 1 = 해 그림자 배열,
 * 2 = 화면 공간 AO, 3 = 하늘 표. 동시 처리 프레임 수만큼 세트를 두어 GPU 가 읽는 중인 것을 덮어쓰지 않는다.
 * AO 이미지는 화면 크기를 따라 바뀌므로 세트마다 따로 연결한다 ({@link #bindAo}).
 */
public final class FrameUniforms extends DeviceResource {

    public static final int FLOAT_COUNT = 180;
    public static final int SIZE_BYTES = FLOAT_COUNT * Float.BYTES; // 720
    /** 단계별 그림자 행렬이 시작하는 float 위치 */
    public static final int CASCADE_OFFSET_FLOATS = 88;
    public static final int SHADOW_PARAMS_FLOATS = 156;
    public static final int FEATURES_FLOATS = 160;

    /** 모션 벡터 보기 배율: 화면 비율 단위 움직임(아주 작은 값)을 눈에 보이게 키운다 */
    public static final float MOTION_VIEW_GAIN = 25f;

    /**
     * 그림자·효과 값.
     *
     * @param cascades 단계별 그림자 행렬 (null 이면 그림자 끔)
     */
    public record Extras(Mat4[] cascades, float[] cascadeTexel, int shadowSize, boolean ao, boolean flakes,
                         float heightFog, long frameIndex, int renderWidth, int renderHeight) {
        public static final Extras NONE = new Extras(null, null, 1, false, false, 0, 0, 1, 1);
    }

    private long setLayout = VK_NULL_HANDLE;
    private long pool = VK_NULL_HANDLE;
    private final GpuBuffer[] buffers = new GpuBuffer[FrameResources.FRAMES_IN_FLIGHT];
    private final long[] sets = new long[FrameResources.FRAMES_IN_FLIGHT];
    private final long[] boundAo = new long[FrameResources.FRAMES_IN_FLIGHT];

    public FrameUniforms(VulkanDevice device) {
        super(device);
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(4, stack);
            binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            for (int b = 1; b <= 3; b++) {
                binding.get(b).binding(b).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            LongBuffer pHandle = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(vk(), VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(binding), null, pHandle), "vkCreateDescriptorSetLayout(frame)");
            setLayout = pHandle.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(2, stack);
            poolSize.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(sets.length);
            poolSize.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(sets.length * 3);
            check(vkCreateDescriptorPool(vk(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(sets.length).pPoolSizes(poolSize), null, pHandle), "vkCreateDescriptorPool(frame)");
            pool = pHandle.get(0);

            LongBuffer layouts = stack.mallocLong(sets.length);
            for (int i = 0; i < sets.length; i++) {
                layouts.put(i, setLayout);
            }
            LongBuffer pSets = stack.mallocLong(sets.length);
            check(vkAllocateDescriptorSets(vk(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts), pSets), "vkAllocateDescriptorSets(frame)");
            for (int i = 0; i < sets.length; i++) {
                sets[i] = pSets.get(i);
                buffers[i] = new GpuBuffer(device, SIZE_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.get(0).buffer(buffers[i].handle()).offset(0).range(SIZE_BYTES);
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
                write.get(0).sType$Default().dstSet(sets[i]).dstBinding(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bufferInfo);
                vkUpdateDescriptorSets(vk(), write, null);
            }
        } catch (RuntimeException e) {
            destroy();
            throw e;
        }
    }

    /** 셰이더 레이아웃 순서대로 float 배열에 담는다 (GPU 없이 테스트 가능). */
    public static void pack(Camera camera, boolean showMotion, FloatBuffer dst) {
        pack(camera, showMotion, 0, Environment.afternoon(0), Extras.NONE, dst);
    }

    /** @param gpuLoad 픽셀당 인공 부하 반복 횟수 (성능 측정용, 보통 0) */
    public static void pack(Camera camera, boolean showMotion, int gpuLoad, Environment env, FloatBuffer dst) {
        pack(camera, showMotion, gpuLoad, env, Extras.NONE, dst);
    }

    public static void pack(Camera camera, boolean showMotion, int gpuLoad, Environment env, Extras x, FloatBuffer dst) {
        camera.jitteredViewProjection().storeColumnMajor(dst);
        camera.viewProjection().storeColumnMajor(dst);
        camera.previousViewProjection().storeColumnMajor(dst);
        Mat4 inv = new Mat4();
        if (!inv.invert(camera.jitteredViewProjection())) {
            inv.identity();
        }
        inv.storeColumnMajor(dst);
        dst.put(showMotion ? 1f : 0f).put(MOTION_VIEW_GAIN).put(gpuLoad).put(env.time());
        Vec3 p = camera.position();
        dst.put(p.x()).put(p.y()).put(p.z()).put(env.exposure());
        Vec3 s = env.sunDirection();
        dst.put(s.x()).put(s.y()).put(s.z()).put(env.skyIntensity());
        Vec3 c = env.sunColor();
        dst.put(c.x()).put(c.y()).put(c.z()).put(env.fogDensity());
        Environment.ContactShadow cs = env.contactShadow();
        if (cs == null) {
            dst.put(0f).put(0f).put(0f).put(0f).put(0f).put(0f).put(0f).put(0f);
        } else {
            dst.put(cs.center().x()).put(cs.center().y()).put(cs.center().z()).put(cs.halfWidth());
            dst.put(cs.forward().x()).put(cs.forward().z()).put(cs.halfLength()).put(0f);
        }
        // 352: 그림자 단계 행렬 4개
        for (int i = 0; i < CascadedShadows.CASCADES; i++) {
            (x.cascades() != null ? x.cascades()[i] : new Mat4()).storeColumnMajor(dst);
        }
        // 608: 단계 끝 거리
        for (int i = 0; i < CascadedShadows.CASCADES; i++) {
            dst.put(CascadedShadows.SPLITS[i]);
        }
        // 624: 그림자 설정
        dst.put(x.cascades() != null ? 1f : 0f).put(x.shadowSize()).put(0.18f).put(0.9f);
        // 640: 효과
        dst.put(x.ao() ? 1f : 0f).put(x.flakes() ? 1f : 0f).put(x.heightFog()).put((float) (x.frameIndex() % 4096));
        // 656: 렌더 해상도
        dst.put(x.renderWidth()).put(x.renderHeight()).put(1f / x.renderWidth()).put(1f / x.renderHeight());
        // 672: 카메라 앞
        Vec3 fw = camera.forward();
        dst.put(fw.x()).put(fw.y()).put(fw.z()).put(camera.near());
        // 688: 높이 안개 (기준 높이 0m, 감쇠 0.06/m, 해 쪽 산란 0.6)
        dst.put(0f).put(0.06f).put(0.6f).put(0f);
        // 704: 단계별 텍셀 크기
        for (int i = 0; i < CascadedShadows.CASCADES; i++) {
            dst.put(x.cascadeTexel() != null ? x.cascadeTexel()[i] : 0.01f);
        }
    }

    /** 이번 프레임 슬롯의 버퍼에 쓴다. 그 슬롯의 GPU 작업은 펜스로 끝난 상태여야 한다. */
    public void update(int frameSlot, Camera camera, boolean showMotion, int gpuLoad, Environment env, Extras extras) {
        buffers[frameSlot].write(address -> pack(camera, showMotion, gpuLoad, env, extras,
                MemoryUtil.memFloatBuffer(address, FLOAT_COUNT)));
    }

    private void writeImage(long set, int binding, long view, long sampler) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            vkUpdateDescriptorSets(vk(), write, null);
        }
    }

    /** 모든 세트에 그림자 배열·하늘 표를 연결 (처음 그리기 전, GPU 가 안 쓸 때) */
    public void bindStatic(long shadowView, long shadowSampler, long skyView, long skySampler) {
        for (long set : sets) {
            writeImage(set, 1, shadowView, shadowSampler);
            writeImage(set, 3, skyView, skySampler);
        }
    }

    /** 이 슬롯 세트의 AO 연결 (그 슬롯의 GPU 작업이 끝난 뒤 = record 시작에서 부름). 같은 뷰면 아무것도 안 함 */
    public void bindAo(int frameSlot, long view, long sampler) {
        if (boundAo[frameSlot] == view) {
            return;
        }
        writeImage(sets[frameSlot], 2, view, sampler);
        boundAo[frameSlot] = view;
    }

    public long setLayout() {
        return setLayout;
    }

    public long descriptorSet(int frameSlot) {
        return sets[frameSlot];
    }

    @Override
    protected void destroy() {
        for (GpuBuffer b : buffers) {
            if (b != null) {
                b.close();
            }
        }
        if (pool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(vk(), pool, null); // 풀에서 할당한 세트도 함께 해제됨
        }
        if (setLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(vk(), setLayout, null);
        }
    }
}
