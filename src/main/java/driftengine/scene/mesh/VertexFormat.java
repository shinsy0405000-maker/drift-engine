package driftengine.scene.mesh;

/**
 * 정점 하나의 메모리 배치. Vulkan 파이프라인의 정점 입력 설정과 셰이더 layout(location)이
 * 이 값과 정확히 일치해야 한다 (scene.vert).
 * <pre>
 *  location 0: position (vec3)  바이트 0
 *  location 1: normal   (vec3)  바이트 12
 *  location 2: color    (vec3)  바이트 24   기본색 (선형 RGB)
 *  location 3: material (vec4)  바이트 36   금속성, 거칠기, 클리어코트, 발광 세기
 *  location 4: surface  (float) 바이트 52   표면 종류 (Material.SURFACE_*: 셰이더가 절차적 무늬를 더함)
 *  전체 56 바이트 (float 14개)
 * </pre>
 */
public final class VertexFormat {

    public static final int FLOATS_PER_VERTEX = 14;
    public static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    public static final int POSITION_OFFSET_BYTES = 0;
    public static final int NORMAL_OFFSET_BYTES = 3 * Float.BYTES;
    public static final int COLOR_OFFSET_BYTES = 6 * Float.BYTES;
    public static final int MATERIAL_OFFSET_BYTES = 9 * Float.BYTES;
    public static final int SURFACE_OFFSET_BYTES = 13 * Float.BYTES;

    private VertexFormat() {
    }
}
