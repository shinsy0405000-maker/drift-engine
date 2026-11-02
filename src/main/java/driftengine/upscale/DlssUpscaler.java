package driftengine.upscale;

import driftengine.core.Log;
import driftengine.vulkan.ColorImage;
import driftengine.vulkan.GpuImage;
import driftengine.vulkan.ImageBarrier;
import driftengine.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * DLSS 업스케일러 (Streamline 경유).
 * <p>
 * 한 프레임의 순서 (SDK 2.14.16 ProgrammingGuideDLSS.md 의 3~7절):
 * <ol>
 *   <li>출력 크기가 바뀌면 slDLSSSetOptions</li>
 *   <li>slGetNewFrameToken → slSetConstants (지터 없는 행렬, 지터, 모션 벡터 배율)</li>
 *   <li>slEvaluateFeature: 색·깊이·모션 벡터(렌더 해상도) → 출력 이미지(출력 해상도, storage)</li>
 *   <li>출력 이미지를 스왑체인으로 복사 (스왑체인 sRGB 포맷은 storage 로 쓸 수 없어서 거친다)</li>
 * </ol>
 * 값 규약 (출처):
 * <ul>
 *   <li>모션 벡터 = 이전 위치 - 현재 위치, 화면 비율 단위([-1,1]) → mvecScale = (1, 1).
 *       sl.dlss 가 여기에 렌더 해상도를 곱해 픽셀 단위로 NGX 에 넘긴다 (sl.dlss/dlssEntry.cpp)</li>
 *   <li>지터 = 렌더 해상도 픽셀 단위, 모션 벡터와 같은 좌표계(오른쪽 +x, 아래 +y), 투영에 적용한 값 그대로
 *       (DLSS Programming Guide 3.7.3). 부호는 화면으로 확인해야 하는 항목</li>
 *   <li>깊이: 가까운 쪽 0, 먼 쪽 1 → depthInverted = false. 모션 벡터에 카메라 움직임 포함, 지터 미포함</li>
 * </ul>
 * 실패하면 그 이유를 한 번 기록하고, 같은 입력을 선형 복사로 늘려 화면이 끊기지 않게 한다.
 */
public final class DlssUpscaler implements Upscaler {

    /** DLSS 출력 이미지 포맷: 렌더 색과 같은 16비트 실수 (storage + 복사 원본으로 쓸 수 있어야 함) */
    public static final int OUTPUT_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    // TRANSFER_DST: DLSS(NGX)가 출력 이미지를 vkCmdClearColorImage 로 지우므로 필요 (4e 첫 실행의 검증 오류로 확인)
    static final int OUTPUT_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private final DlssSession session;
    private final VulkanDevice device;
    private final DlssMode mode;

    private ColorImage output;
    private boolean optionsSet;
    private boolean resetHistory = true;
    private boolean failed;
    private String failure;
    private long evaluations;

    private int cachedOutputWidth = -1;
    private int cachedOutputHeight = -1;
    private Resolution cachedRender;

    public DlssUpscaler(DlssSession session, VulkanDevice device, DlssMode mode) {
        this.session = session;
        this.device = device;
        this.mode = mode;
    }

    /** 이 GPU 가 DLSS 출력 이미지(storage + 복사 원본)를 만들 수 있는지 */
    public static boolean outputFormatSupported(VulkanDevice device) {
        return device.supportsOptimalTiling(OUTPUT_FORMAT,
                VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT);
    }

    public DlssMode mode() {
        return mode;
    }

    @Override
    public String name() {
        return "DLSS " + mode.label() + (failed ? " (실패 → 선형 복사)" : "");
    }

    @Override
    public UpscalerStatus status() {
        List<String> checks = new ArrayList<>();
        checks.add("[공식] slIsFeatureSupported(DLSS): 지원됨");
        checks.add(evaluations > 0 ? "[동작] slEvaluateFeature 성공 " + evaluations + "회" : "[미실행] slEvaluateFeature");
        if (failed) {
            checks.add("[실패] " + failure);
        }
        return new UpscalerStatus("DLSS " + mode.label(), UpscalerStatus.Support.SUPPORTED,
                evaluations > 0 && !failed, checks);
    }

    @Override
    public boolean temporal() {
        return true;
    }

    /** DLSS Programming Guide 3.7.1.1: 단계 수 = 8 × (출력 / 렌더)² */
    @Override
    public int recommendedJitterPhases(int renderWidth, int outputWidth) {
        return jitterPhases(renderWidth, outputWidth);
    }

    public static int jitterPhases(int renderWidth, int outputWidth) {
        if (renderWidth <= 0 || outputWidth <= renderWidth) {
            return 8;
        }
        double ratio = (double) outputWidth / renderWidth;
        return (int) Math.max(8, Math.round(8 * ratio * ratio));
    }

    @Override
    public Resolution renderResolution(int outputWidth, int outputHeight) {
        if (outputWidth == cachedOutputWidth && outputHeight == cachedOutputHeight && cachedRender != null) {
            return cachedRender;
        }
        int[] r = session.optimalSettings(mode, outputWidth, outputHeight);
        Resolution res;
        if (r[0] == 0 && r[1] > 0 && r[2] > 0 && r[1] <= outputWidth && r[2] <= outputHeight) {
            res = new Resolution(r[1], r[2]);
            Log.info(String.format("DLSS %s 권장 렌더 해상도: %dx%d (출력 %dx%d, slDLSSGetOptimalSettings)",
                    mode.label(), r[1], r[2], outputWidth, outputHeight));
        } else {
            res = new Resolution(BilinearUpscaler.scaled(outputWidth, mode.fallbackScale()),
                    BilinearUpscaler.scaled(outputHeight, mode.fallbackScale()));
            Log.warn("slDLSSGetOptimalSettings 실패 (" + SlResult.describe(r[0]) + " - " + session.lastError()
                    + ") → 배율표 값 사용 " + res.width() + "x" + res.height());
        }
        cachedOutputWidth = outputWidth;
        cachedOutputHeight = outputHeight;
        cachedRender = res;
        return res;
    }

    @Override
    public void record(VkCommandBuffer cmd, UpscaleInput in) {
        try (MemoryStack stack = stackPush()) {
            if (failed || !prepare(in)) {
                fallbackBlit(stack, cmd, in);
                return;
            }

            GpuImage out = output.asGpuImage();
            // 출력: 이전 프레임의 복사(읽기)가 끝난 뒤 → DLSS 가 compute 로 씀. 이전 내용은 필요 없다
            ImageBarrier.transition(stack, cmd, out.image(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_SHADER_READ_BIT);

            // Streamline 이 필요한 전환을 하고 끝나면 여기서 알려 준 레이아웃으로 되돌린다 (sl.dlss 소스 확인)
            int code = session.bridge().dlssEvaluate(DlssSession.VIEWPORT, cmd.address(),
                    in.color(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    out, VK_IMAGE_LAYOUT_GENERAL,
                    in.depth(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    in.motion(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (code != 0) {
                fail("slEvaluateFeature(DLSS) " + SlResult.describe(code) + " - " + session.bridge().lastError());
                fallbackBlit(stack, cmd, in);
                return;
            }

            // 출력: DLSS 쓰기 끝 → 복사 원본
            ImageBarrier.transition(stack, cmd, out.image(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
            // 같은 크기 복사 (포맷만 16비트 실수 → 스왑체인 sRGB 로 변환)
            ImageBlit.record(cmd, out.image(), out.width(), out.height(),
                    in.destinationImage(), in.destinationWidth(), in.destinationHeight(), VK_FILTER_NEAREST);

            evaluations++;
            resetHistory = false;
            if (evaluations == 1) {
                Log.info(String.format("DLSS 실행 시작: slEvaluateFeature 성공 (%s, %dx%d → %dx%d)",
                        mode.label(), in.sourceWidth(), in.sourceHeight(), out.width(), out.height()));
            }
        }
    }

    /** 출력 이미지·옵션·프레임 상수를 준비한다. 실패하면 false (이유는 기록됨). */
    private boolean prepare(UpscaleInput in) {
        if (output == null || output.width() != in.destinationWidth() || output.height() != in.destinationHeight()) {
            if (output != null) {
                output.close(); // FrameLoop 가 크기 변경 전에 GPU 대기를 마쳤다
            }
            output = new ColorImage(device, OUTPUT_FORMAT, in.destinationWidth(), in.destinationHeight(),
                    OUTPUT_USAGE, "dlss-output");
            optionsSet = false;
            resetHistory = true;
        }
        if (!optionsSet) {
            int code = session.setOptions(mode, in.destinationWidth(), in.destinationHeight());
            if (code != 0) {
                fail("slDLSSSetOptions " + SlResult.describe(code) + " - " + session.lastError());
                return false;
            }
            optionsSet = true;
        }
        if (in.camera() == null) {
            fail("카메라 정보 없음");
            return false;
        }
        int code = session.beginFrame((int) in.frameNumber());
        if (code != 0) {
            fail("slGetNewFrameToken " + SlResult.describe(code) + " - " + session.lastError());
            return false;
        }
        code = session.setConstants(in.camera(), new StreamlineBridge.ConstantFlags(
                1f, 1f, false, true, false, resetHistory));
        if (code != 0) {
            fail("slSetConstants " + SlResult.describe(code) + " - " + session.lastError());
            return false;
        }
        return true;
    }

    /** DLSS 를 못 쓸 때: 렌더 색을 그대로 선형 복사로 늘린다 (색은 SHADER_READ_ONLY 상태로 들어옴). */
    private void fallbackBlit(MemoryStack stack, VkCommandBuffer cmd, UpscaleInput in) {
        ImageBarrier.transition(stack, cmd, in.sourceImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
        ImageBlit.record(cmd, in);
    }

    private void fail(String reason) {
        if (!failed) {
            Log.warn("DLSS 실행 실패: " + reason + " → 이후 선형 복사로 대체 (DLSS 실제 동작: 아니오)");
        }
        failed = true;
        failure = reason;
    }

    /** FrameLoop/Engine 이 GPU 대기를 마친 뒤 호출한다. */
    @Override
    public void close() {
        // 한 번도 실행하지 않았으면 Streamline 쪽에 해제할 자원이 없다 (해제하면 eErrorInvalidParameter)
        if (evaluations > 0) {
            int code = session.freeResources();
            if (code != 0) {
                Log.warn("slFreeResources(DLSS) 실패: " + SlResult.describe(code) + " - " + session.lastError());
            }
        }
        if (output != null) {
            output.close();
            output = null;
        }
        if (evaluations > 0) {
            Log.info("DLSS " + mode.label() + " 종료: slEvaluateFeature 성공 " + evaluations + "회"
                    + (failed ? ", 이후 실패: " + failure : ""));
        }
    }
}
