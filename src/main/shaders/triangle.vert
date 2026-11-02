#version 450

// 정점 버퍼 없이 gl_VertexIndex(0,1,2)로 꼭짓점을 고른다.
// Vulkan 화면 좌표: x 는 -1(왼쪽)~1(오른쪽), y 는 -1(위)~1(아래).

layout(location = 0) out vec3 vColor;

const vec2 POSITIONS[3] = vec2[](
    vec2( 0.0, -0.6),   // 위
    vec2( 0.6,  0.6),   // 오른쪽 아래
    vec2(-0.6,  0.6)    // 왼쪽 아래
);

const vec3 COLORS[3] = vec3[](
    vec3(1.0, 0.2, 0.2),   // 빨강
    vec3(0.2, 1.0, 0.2),   // 초록
    vec3(0.2, 0.4, 1.0)    // 파랑
);

void main() {
    gl_Position = vec4(POSITIONS[gl_VertexIndex], 0.0, 1.0);
    vColor = COLORS[gl_VertexIndex];
}
