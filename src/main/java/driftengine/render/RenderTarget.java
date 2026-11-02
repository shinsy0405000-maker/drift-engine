package driftengine.render;

/**
 * 렌더러가 그릴 대상. 전달 시점 레이아웃: 색/모션 = COLOR_ATTACHMENT_OPTIMAL,
 * 깊이 = DEPTH_STENCIL_ATTACHMENT_OPTIMAL.
 *
 * @param imageView       색 이미지의 VkImageView
 * @param format          색 이미지 포맷(VkFormat)
 * @param motionImageView 모션 벡터 이미지의 VkImageView (안 쓰는 렌더러는 무시)
 * @param motionFormat    모션 벡터 포맷
 * @param depthImageView  깊이 이미지의 VkImageView (안 쓰는 렌더러는 무시)
 * @param depthImage      깊이 VkImage (AO 처럼 렌더러가 깊이를 읽으려고 상태를 바꿀 때)
 * @param depthFormat     깊이 포맷(VkFormat)
 * @param frameIndex      동시에 처리 중인 프레임 슬롯 번호 (프레임별 버퍼 선택용)
 */
public record RenderTarget(long imageView, int format,
                           long motionImageView, int motionFormat,
                           long depthImageView, int depthFormat, long depthImage,
                           int width, int height, int frameIndex) {
}
