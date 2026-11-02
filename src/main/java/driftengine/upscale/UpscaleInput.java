package driftengine.upscale;

import driftengine.vulkan.GpuImage;

/**
 * 업스케일 한 번에 필요한 입력.
 * <p>
 * 호출 시점 레이아웃 (FrameLoop 가 맞춰 둔다):
 * <ul>
 *   <li>복사 방식(temporal() == false): color = TRANSFER_SRC_OPTIMAL</li>
 *   <li>시간적 방식(temporal() == true, DLSS): color, motion, depth = SHADER_READ_ONLY_OPTIMAL</li>
 *   <li>destination(스왑체인) = TRANSFER_DST_OPTIMAL. 업스케일러는 끝날 때도 이 레이아웃으로 두고, 복사(transfer)로 써야 한다</li>
 * </ul>
 *
 * @param color            렌더 해상도 색 이미지
 * @param motion           렌더 해상도 모션 벡터
 * @param depth            렌더 해상도 깊이
 * @param destinationImage 출력 해상도 이미지 (스왑체인 VkImage)
 * @param camera           이번 프레임 카메라 (지터 없는 행렬 + 지터 값)
 * @param frameNumber      0 부터 1씩 늘어나는 프레임 번호
 */
public record UpscaleInput(GpuImage color, GpuImage motion, GpuImage depth,
                           long destinationImage, int destinationWidth, int destinationHeight,
                           UpscaleCamera camera, long frameNumber) {

    public long sourceImage() {
        return color.image();
    }

    public int sourceWidth() {
        return color.width();
    }

    public int sourceHeight() {
        return color.height();
    }
}
