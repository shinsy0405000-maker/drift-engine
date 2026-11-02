package driftengine.vulkan;

/**
 * 이미지 하나를 다른 모듈(업스케일러, Streamline)에 넘길 때 쓰는 핸들 묶음.
 * 소유권은 없다: 이 값을 받은 쪽은 이미지를 파괴하지 않는다.
 *
 * @param image  VkImage
 * @param view   VkImageView
 * @param memory VkDeviceMemory
 * @param format VkFormat
 * @param usage  생성할 때 준 VkImageUsageFlags
 * @param aspect VK_IMAGE_ASPECT_COLOR_BIT 또는 VK_IMAGE_ASPECT_DEPTH_BIT
 */
public record GpuImage(long image, long view, long memory, int format, int width, int height, int usage, int aspect) {
}
