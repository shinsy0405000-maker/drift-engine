#version 450

// 그림자 맵: 해에서 본 깊이만 그린다 (조각 셰이더 없음). 정점 형식 중 위치만 읽는다.
layout(location = 0) in vec3 inPosition;

// CascadedShadows.PUSH_BYTES = 128
layout(push_constant) uniform Push {
    mat4 model;
    mat4 lightViewProj;
} pc;

void main() {
    gl_Position = pc.lightViewProj * pc.model * vec4(inPosition, 1.0);
}
