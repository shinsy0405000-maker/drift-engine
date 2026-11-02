package driftengine.render;

import driftengine.core.EngineException;
import driftengine.core.Log;
import driftengine.platform.Window;
import driftengine.ui.FontAtlas;
import driftengine.ui.Overlay;
import driftengine.ui.UiCanvas;
import driftengine.ui.UiRenderer;
import driftengine.upscale.UpscaleCamera;
import driftengine.upscale.UpscaleInput;
import driftengine.upscale.Upscaler;
import driftengine.vulkan.ColorImage;
import driftengine.vulkan.DepthBuffer;
import driftengine.vulkan.FrameResources;
import driftengine.vulkan.GpuTimer;
import driftengine.vulkan.ImageBarrier;
import driftengine.vulkan.RenderTargets;
import driftengine.vulkan.Swapchain;
import driftengine.vulkan.VulkanDevice;
import driftengine.vulkan.VulkanException;
import driftengine.vulkan.VulkanSurface;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

import java.nio.IntBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * 한 프레임의 흐름을 담당한다:
 * <ol>
 *   <li>펜스 대기 → 스왑체인 이미지 획득</li>
 *   <li>렌더 해상도 오프스크린 이미지(색+모션 벡터+깊이)에 장면 그리기 (Renderer)</li>
 *   <li>업스케일러가 오프스크린 → 출력 해상도 HDR 이미지로 옮김 (Upscaler)</li>
 *   <li>화면 처리(PostProcess): HDR → 톤매핑 → 스왑체인 이미지</li>
 *   <li>제출 → 화면 표시</li>
 * </ol>
 * 창 크기가 바뀌거나 업스케일러(렌더 배율)가 바뀌면 필요한 이미지를 다시 만든다.
 */
public final class FrameLoop implements AutoCloseable {

    private static final long NO_TIMEOUT = -1L; // UINT64_MAX

    private final VulkanDevice device;
    private final VulkanSurface surface;
    private final Window window;
    private final FrameResources frames;
    private final GpuTimer timer;
    private final boolean vsync;
    private boolean gpuSampleReady;
    private final int depthFormat;
    private final int offscreenFormat;
    private Swapchain swapchain;
    private RenderTargets targets;
    /** 출력 해상도 HDR 이미지: 업스케일 결과 → 톤매핑(PostProcess) 입력 */
    private ColorImage output;
    private PostProcess post;
    /** 밝은 빛 번짐 (출력 HDR → 반 해상도 블룸 이미지) */
    private Bloom bloom;
    /** 화면 위 글자·사각형 (계기판, 메뉴) */
    private final FontAtlas fontAtlas = new FontAtlas();
    private final UiCanvas canvas = new UiCanvas(fontAtlas);
    private UiRenderer ui;
    private Upscaler upscaler;
    private boolean needsRecreate;
    private int frameIndex;
    private long frameNumber; // 제출한 프레임 수 (DLSS 프레임 번호)

    public FrameLoop(VulkanDevice device, VulkanSurface surface, Window window, Upscaler upscaler) {
        this(device, surface, window, upscaler, true);
    }

    /** @param vsync false 면 수직동기 없이 표시 (성능 측정용) */
    public FrameLoop(VulkanDevice device, VulkanSurface surface, Window window, Upscaler upscaler, boolean vsync) {
        this.device = device;
        this.surface = surface;
        this.window = window;
        this.upscaler = upscaler;
        this.vsync = vsync;
        this.frames = new FrameResources(device);
        this.timer = new GpuTimer(device, FrameResources.FRAMES_IN_FLIGHT);
        try {
            this.depthFormat = DepthBuffer.chooseFormat(device);
            Window.FramebufferSize size = window.framebufferSize();
            this.swapchain = new Swapchain(device, surface, size.width(), size.height(), VK_NULL_HANDLE, vsync);
            this.offscreenFormat = RenderTargets.chooseColorFormat(device, swapchain.format());
            if (!device.supportsOptimalTiling(offscreenFormat,
                    VK_FORMAT_FEATURE_BLIT_DST_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)) {
                throw new EngineException("출력 이미지 포맷 " + offscreenFormat + " 이 복사 대상/선형 샘플링을 지원하지 않습니다");
            }
            this.post = new PostProcess(device, swapchain.format());
            this.bloom = new Bloom(device, offscreenFormat);
            this.ui = new UiRenderer(device, swapchain.format(), fontAtlas);
            createTargets();
        } catch (RuntimeException e) {
            if (bloom != null) {
                bloom.close();
            }
            if (ui != null) {
                ui.close();
            }
            if (post != null) {
                post.close();
            }
            if (swapchain != null) {
                swapchain.close();
            }
            timer.close();
            frames.close();
            throw e;
        }
    }

    private void createTargets() {
        Upscaler.Resolution render = upscaler.renderResolution(swapchain.width(), swapchain.height());
        targets = new RenderTargets(device, offscreenFormat, depthFormat, render.width(), render.height());
        output = new ColorImage(device, offscreenFormat, swapchain.width(), swapchain.height(),
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "output");
        long outView = output.asGpuImage().view();
        bloom.resize(swapchain.width(), swapchain.height(), outView);
        long bloomView = bloom.resultView();
        post.setInput(outView, bloomView != VK_NULL_HANDLE ? bloomView : outView, bloomView != VK_NULL_HANDLE,
                targets.motionView());
        Log.info(String.format("렌더 %dx%d → 출력 %dx%d (%s, 색 포맷 %d, 깊이 포맷 %d)",
                targets.width(), targets.height(), swapchain.width(), swapchain.height(),
                upscaler.name(), offscreenFormat, depthFormat));
    }

    /** 렌더러 파이프라인이 사용할 색 포맷 (오프스크린 이미지 포맷) */
    public int colorFormat() {
        return offscreenFormat;
    }

    public int depthFormat() {
        return depthFormat;
    }

    public Upscaler upscaler() {
        return upscaler;
    }

    /** 현재 렌더 해상도 (카메라 지터를 픽셀 단위로 계산할 때 필요) */
    public int renderWidth() {
        return targets.width();
    }

    public int renderHeight() {
        return targets.height();
    }

    /** 이번 drawFrame 에서 GPU 구간 시간을 새로 읽었는지 (프레임 2개 전 제출분) */
    public boolean hasGpuSample() {
        return gpuSampleReady;
    }

    /** GPU: 프레임 시작 ~ 장면 끝 (ms) */
    public double gpuSceneMs() {
        return timer.lastSceneMs();
    }

    /** GPU: 장면 끝 ~ 업스케일과 스왑체인 복사 끝 (ms) */
    public double gpuUpscaleMs() {
        return timer.lastUpscaleMs();
    }

    public String presentModeText() {
        return Swapchain.presentModeName(swapchain.presentMode());
    }

    /** 출력(스왑체인) 해상도 너비 */
    public int outputWidth() {
        return swapchain.width();
    }

    /** 현재 렌더 해상도 / 출력 해상도 (창 제목 표시용) */
    public String resolutionText() {
        return targets.width() + "x" + targets.height() + " → " + swapchain.width() + "x" + swapchain.height();
    }

    /**
     * 업스케일러를 바꾼다 (예: 렌더 배율 변경). 이전 업스케일러는 호출한 쪽이 닫는다.
     * GPU 가 쉬는 것을 기다린 뒤 오프스크린 이미지를 새 해상도로 다시 만든다.
     */
    public void setUpscaler(Upscaler newUpscaler) {
        device.waitIdle();
        timer.discard(); // 이전 업스케일러로 잰 값이 새 업스케일러 몫으로 섞이지 않게
        this.upscaler = newUpscaler;
        closeTargets();
        createTargets();
    }

    /**
     * 한 프레임을 그린다.
     *
     * @param camera 이번 프레임 카메라 정보 (시간적 업스케일러용, 없으면 null)
     * @return 실제로 화면에 제출했으면 true, 최소화나 스왑체인 재생성으로 건너뛰었으면 false
     */
    public boolean drawFrame(Renderer renderer, UpscaleCamera camera) {
        return drawFrame(renderer, camera, null);
    }

    /** @param overlay 화면 위에 그릴 계기판·메뉴 (없으면 null) */
    public boolean drawFrame(Renderer renderer, UpscaleCamera camera, Overlay overlay) {
        if (window.isMinimized()) {
            return false;
        }
        if (needsRecreate) {
            recreateSwapchain();
            return false;
        }

        VkDevice vk = device.handle();
        try (MemoryStack stack = stackPush()) {
            long fence = frames.inFlightFence(frameIndex);
            long imageAvailable = frames.imageAvailableSemaphore(frameIndex);

            // 1. 이 프레임 슬롯을 마지막으로 쓴 GPU 작업이 끝날 때까지 대기
            check(vkWaitForFences(vk, fence, true, NO_TIMEOUT), "vkWaitForFences");
            // 이 슬롯이 지난번에 기록한 GPU 구간 시간 (GPU 가 끝났으므로 바로 읽힌다)
            gpuSampleReady = timer.collect(frameIndex);

            // 2. 그릴 스왑체인 이미지 획득
            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquire = vkAcquireNextImageKHR(vk, swapchain.handle(), NO_TIMEOUT, imageAvailable,
                    VK_NULL_HANDLE, pImageIndex);
            if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain();
                return false;
            }
            if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
                throw new VulkanException("vkAcquireNextImageKHR", acquire);
            }
            int imageIndex = pImageIndex.get(0);

            // 이미지를 확실히 얻은 뒤에만 펜스를 리셋한다(먼저 리셋하면 재생성 경로에서 영원히 대기할 수 있음)
            check(vkResetFences(vk, fence), "vkResetFences");

            // 3. 명령 기록
            VkCommandBuffer cmd = frames.commandBuffer(frameIndex);
            check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer");
            canvas.begin(swapchain.width(), swapchain.height());
            if (overlay != null) {
                overlay.draw(canvas);
            }
            recordFrame(stack, cmd, imageIndex, frameIndex, renderer, camera);
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

            // 4. 제출: 이미지 획득을 기다렸다가(첫 사용이 복사 단계) 실행, 끝나면 renderFinished + 펜스 신호
            long renderFinished = swapchain.renderFinishedSemaphore(imageIndex);
            VkSemaphoreSubmitInfo.Buffer waitInfo = VkSemaphoreSubmitInfo.calloc(1, stack);
            waitInfo.get(0).sType$Default()
                    .semaphore(imageAvailable)
                    .stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
            VkSemaphoreSubmitInfo.Buffer signalInfo = VkSemaphoreSubmitInfo.calloc(1, stack);
            signalInfo.get(0).sType$Default()
                    .semaphore(renderFinished)
                    .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            VkCommandBufferSubmitInfo.Buffer cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack);
            cmdInfo.get(0).sType$Default().commandBuffer(cmd);

            VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, stack);
            submit.get(0).sType$Default()
                    .pWaitSemaphoreInfos(waitInfo)
                    .pCommandBufferInfos(cmdInfo)
                    .pSignalSemaphoreInfos(signalInfo);
            check(vkQueueSubmit2(device.graphicsQueue(), submit, fence), "vkQueueSubmit2");

            // 5. 화면 표시
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(renderFinished))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain.handle()))
                    .pImageIndices(stack.ints(imageIndex));
            int present = vkQueuePresentKHR(device.presentQueue(), presentInfo);

            frameIndex = (frameIndex + 1) % FrameResources.FRAMES_IN_FLIGHT;
            frameNumber++;

            boolean resized = window.consumeResized();
            if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR || resized) {
                recreateSwapchain();
            } else if (present != VK_SUCCESS) {
                throw new VulkanException("vkQueuePresentKHR", present);
            }
            return true;
        }
    }

    /**
     * 이미지 레이아웃 흐름:
     * 오프스크린 색: (이전 내용 버림) → 그리기용 → 복사 원본용(복사 방식) 또는 셰이더 읽기용(DLSS)
     * 모션 벡터:     (이전 내용 버림) → 그리기용 → (DLSS) 셰이더 읽기용
     * 깊이:         (이전 내용 버림) → 깊이 테스트용 → (DLSS) 셰이더 읽기용
     * 출력 HDR:    (이전 내용 버림) → 복사 대상용 → 셰이더 읽기용
 * 스왑체인:     (이전 내용 버림) → 그리기용(화면 처리) → 화면 표시용
     * 오프스크린 이미지는 두 프레임이 같이 쓰므로, 장벽(barrier)으로 이전 프레임의 사용(복사 또는 DLSS 의
     * compute 읽기)이 끝난 뒤에 쓰게 한다. 이전 사용 단계가 업스케일러마다 달라서 ALL_COMMANDS 로 기다린다.
     */
    private void recordFrame(MemoryStack stack, VkCommandBuffer cmd, int imageIndex, int frameSlot, Renderer renderer,
                             UpscaleCamera camera) {
        long offscreen = targets.colorImage();
        long swapImage = swapchain.image(imageIndex);
        boolean temporal = upscaler.temporal();
        timer.begin(cmd, frameSlot);

        // 오프스크린 색: 이전 프레임의 읽기가 끝난 뒤 → 그리기
        ImageBarrier.transition(stack, cmd, offscreen, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);

        // 모션 벡터: 이전 프레임의 쓰기/읽기가 끝난 뒤 → 그리기
        ImageBarrier.transition(stack, cmd, targets.motionImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);

        // 깊이: 이전 프레임의 깊이 쓰기/읽기가 끝난 뒤 → 이번 프레임 깊이 테스트
        long depthStages = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
        ImageBarrier.transition(stack, cmd, targets.depth().image(), VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                depthStages, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

        renderer.record(cmd, new RenderTarget(targets.colorView(), targets.colorFormat(),
                targets.motionView(), RenderTargets.MOTION_FORMAT,
                targets.depth().view(), targets.depth().format(), targets.depth().image(),
                targets.width(), targets.height(), frameSlot));

        if (temporal) {
            // DLSS: 색·모션 벡터·깊이를 셰이더(compute)로 읽는다
            ImageBarrier.transition(stack, cmd, offscreen, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_READ_BIT);
            ImageBarrier.transition(stack, cmd, targets.motionImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_READ_BIT);
            ImageBarrier.transition(stack, cmd, targets.depth().image(), VK_IMAGE_ASPECT_DEPTH_BIT,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    depthStages, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_READ_BIT);
        } else {
            // 복사 방식: 오프스크린 색 그리기 끝 → 복사 원본
            ImageBarrier.transition(stack, cmd, offscreen, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
            // 모션 벡터: 화면 처리(모션 블러)가 읽음
            ImageBarrier.transition(stack, cmd, targets.motionImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT);
        }

        timer.mark(cmd, frameSlot, 1); // 장면(+ 업스케일 입력 준비) 끝

        // 출력 HDR 이미지: 이전 프레임의 화면 처리 읽기가 끝난 뒤 → 업스케일 결과 복사 대상
        long outImage = output.image();
        ImageBarrier.transition(stack, cmd, outImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);

        upscaler.record(cmd, new UpscaleInput(targets.colorGpuImage(), targets.motionGpuImage(),
                targets.depth().asGpuImage(), outImage, swapchain.width(), swapchain.height(),
                camera, frameNumber));

        // 출력 HDR: 복사 끝 → 화면 처리 셰이더가 읽음
        ImageBarrier.transition(stack, cmd, outImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT);
        bloom.record(stack, cmd);
        ui.prepare(stack, cmd, frameSlot, canvas);
        // 스왑체인: 화면 처리(톤매핑)가 그림
        ImageBarrier.transition(stack, cmd, swapImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        post.record(cmd, swapchain.imageView(imageIndex), swapchain.width(), swapchain.height(), frameNumber,
                renderer.sunScreen());
        ui.record(cmd, swapchain.imageView(imageIndex), swapchain.width(), swapchain.height(), frameSlot);
        timer.mark(cmd, frameSlot, 2); // 업스케일 + 화면 처리 끝

        // 스왑체인: 그리기 끝 → 화면 표시
        ImageBarrier.transition(stack, cmd, swapImage, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_NONE, VK_ACCESS_2_NONE);
    }

    private void recreateSwapchain() {
        Window.FramebufferSize size = window.framebufferSize();
        if (size.isEmpty()) {
            needsRecreate = true; // 최소화 상태: 복원되면 다시 시도
            return;
        }
        device.waitIdle();

        Swapchain old = swapchain;
        swapchain = new Swapchain(device, surface, size.width(), size.height(), old.handle(), vsync);
        old.close();

        // 오프스크린 이미지도 새 출력 크기에 맞춰 다시 (렌더 포맷은 바뀌지 않으므로 파이프라인은 그대로)
        post.onFormatChanged(swapchain.format());
        ui.onFormatChanged(swapchain.format());
        closeTargets();
        createTargets();
        needsRecreate = false;
    }

    private void closeTargets() {
        if (targets != null) {
            targets.close();
            targets = null;
        }
        if (output != null) {
            output.close();
            output = null;
        }
    }

    @Override
    public void close() {
        closeTargets();
        if (bloom != null) {
            bloom.close();
        }
        if (ui != null) {
            ui.close();
        }
        if (post != null) {
            post.close();
        }
        if (swapchain != null) {
            swapchain.close();
        }
        timer.close();
        frames.close();
        Log.info("프레임 루프 자원 해제");
    }
}
