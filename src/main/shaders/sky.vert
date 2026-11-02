#version 450

// 화면 전체를 덮는 삼각형 하나 (정점 버퍼 없음). 깊이 = 1 (가장 먼 곳): 아무것도 안 그려진 곳에만 하늘이 남는다
layout(location = 0) out vec2 vNdc;

void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2)) * 2.0 - 1.0;
    vNdc = p;
    gl_Position = vec4(p, 1.0, 1.0);
}
