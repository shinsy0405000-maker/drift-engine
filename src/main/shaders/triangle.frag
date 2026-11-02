#version 450

// 정점 셰이더의 색이 삼각형 내부에서 보간되어 들어온다.

layout(location = 0) in vec3 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(vColor, 1.0);
}
