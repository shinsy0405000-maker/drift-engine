package driftengine.vulkan;

import driftengine.core.EngineException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static driftengine.vulkan.VulkanException.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

/**
 * SPIR-V 셰이더 모듈. 빌드 시 Gradle 의 compileShaders 태스크가
 * src/main/shaders/*.vert|frag 를 SPIR-V(.spv)로 컴파일해 클래스패스의 /shaders/ 에 넣는다.
 */
public final class ShaderModule extends DeviceResource {

    private static final int SPIRV_MAGIC = 0x07230203;

    private final String name;
    private final long handle;

    /** 클래스패스 리소스(예: "/shaders/triangle.vert.spv")에서 셰이더를 읽는다. */
    public static ShaderModule fromResource(VulkanDevice device, String resourcePath) {
        byte[] bytes;
        try (InputStream in = ShaderModule.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new EngineException("셰이더 리소스가 없습니다: " + resourcePath
                        + " (Gradle 로 빌드했는지, compileShaders 태스크가 실행됐는지 확인하세요)");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new EngineException("셰이더 읽기 실패: " + resourcePath, e);
        }
        return new ShaderModule(device, resourcePath, bytes);
    }

    private ShaderModule(VulkanDevice device, String name, byte[] spirv) {
        super(device);
        this.name = name;
        if (spirv.length < 4 || spirv.length % 4 != 0) {
            throw new EngineException("SPIR-V 크기가 4의 배수가 아닙니다: " + name + " (" + spirv.length + " bytes)");
        }

        ByteBuffer code = memAlloc(spirv.length); // pCode 는 4바이트 정렬이 필요하므로 네이티브 메모리에 복사
        try (MemoryStack stack = stackPush()) {
            code.put(spirv).flip();
            if (code.order(ByteOrder.LITTLE_ENDIAN).getInt(0) != SPIRV_MAGIC) {
                throw new EngineException("SPIR-V 파일이 아닙니다(매직 넘버 불일치): " + name);
            }
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(vkCreateShaderModule(vk(), createInfo, null, pModule), "vkCreateShaderModule(" + name + ")");
            this.handle = pModule.get(0);
        } finally {
            memFree(code);
        }
    }

    public long handle() {
        return handle;
    }

    public String name() {
        return name;
    }

    @Override
    protected void destroy() {
        vkDestroyShaderModule(vk(), handle, null);
    }
}
