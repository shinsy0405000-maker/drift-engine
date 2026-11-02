package driftengine.vulkan;

import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 선택된 물리 디바이스와 사용할 큐 패밀리 정보.
 *
 * @param graphicsFamily 그래픽 명령을 받는 큐 패밀리 인덱스
 * @param presentFamily  화면 출력(present)을 지원하는 큐 패밀리 인덱스 (보통 graphicsFamily 와 같음)
 */
public record GpuInfo(
        VkPhysicalDevice device,
        String name,
        int vendorId,
        int deviceType,
        int apiVersion,
        int driverVersion,
        int graphicsFamily,
        int presentFamily
) {
    public static final int VENDOR_NVIDIA = 0x10DE;
    public static final int VENDOR_AMD = 0x1002;
    public static final int VENDOR_INTEL = 0x8086;

    public boolean isNvidia() {
        return vendorId == VENDOR_NVIDIA;
    }

    /** NVIDIA 는 드라이버 버전을 자체 비트 레이아웃(10.8.8.6)으로 인코딩한다. */
    public String driverVersionString() {
        if (isNvidia()) {
            return nvidiaDriverMajor() + "." + String.format("%02d", nvidiaDriverMinor());
        }
        return VulkanInstance.versionString(driverVersion);
    }

    /** NVIDIA 드라이버 주 버전 (예: 610.88 → 610). NVIDIA 가 아니면 의미 없음. */
    public int nvidiaDriverMajor() {
        return (driverVersion >>> 22) & 0x3FF;
    }

    /** NVIDIA 드라이버 부 버전 (예: 610.88 → 88). */
    public int nvidiaDriverMinor() {
        return (driverVersion >>> 14) & 0xFF;
    }

    public String deviceTypeString() {
        return switch (deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "외장 GPU";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "내장 GPU";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "가상 GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU(소프트웨어)";
            default -> "기타";
        };
    }

    public String vendorString() {
        return switch (vendorId) {
            case VENDOR_NVIDIA -> "NVIDIA";
            case VENDOR_AMD -> "AMD";
            case VENDOR_INTEL -> "Intel";
            default -> String.format("0x%04X", vendorId);
        };
    }

    public String describe() {
        return name + " [" + vendorString() + ", " + deviceTypeString()
                + ", Vulkan " + VulkanInstance.versionString(apiVersion)
                + ", 드라이버 " + driverVersionString()
                + ", 큐 graphics=" + graphicsFamily + " present=" + presentFamily + "]";
    }
}
