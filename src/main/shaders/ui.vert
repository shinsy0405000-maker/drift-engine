#version 450

// UiCanvas 정점: 화면 픽셀 좌표, 아틀라스 UV, 색
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;

layout(push_constant) uniform Push {
    vec2 screen;   // 화면 크기 (px)
} pc;

layout(location = 0) out vec2 vUv;
layout(location = 1) out vec4 vColor;

void main() {
    vUv = inUv;
    vColor = inColor;
    gl_Position = vec4(inPos / pc.screen * 2.0 - 1.0, 0.0, 1.0);
}
