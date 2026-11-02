#version 450
#extension GL_GOOGLE_include_directive : require

// 정점 형식: VertexFormat.java 와 일치 (position, normal, color, material, surface)
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec3 inColor;
layout(location = 3) in vec4 inMaterial;   // 금속성, 거칠기, 클리어코트, 발광
layout(location = 4) in float inSurface;   // 표면 종류 (Material.SURFACE_*)

#include "frame.glsl"

// 물체마다 바뀌는 데이터: 128바이트 (SceneRenderer.PUSH_CONSTANT_BYTES)
layout(push_constant) uniform Push {
    mat4 model;
    mat4 prevModel;
} pc;

// 깊이 사전 패스(조각 셰이더 없음)와 본 패스가 정확히 같은 깊이를 내도록
invariant gl_Position;

layout(location = 0) out vec3 vColor;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec4 vCurrClip;
layout(location = 3) out vec4 vPrevClip;
layout(location = 4) out vec3 vWorld;
layout(location = 5) out vec4 vMaterial;
layout(location = 6) out float vSurface;

void main() {
    vec4 world = pc.model * vec4(inPosition, 1.0);
    vec4 prevWorld = pc.prevModel * vec4(inPosition, 1.0);

    gl_Position = f.viewProjJittered * world;
    vCurrClip = f.viewProj * world;
    vPrevClip = f.prevViewProj * prevWorld;

    // 크기 변환이 균일하지 않으면 역전치가 필요하다 → 법선 행렬 = transpose(inverse(mat3(model)))
    vNormal = transpose(inverse(mat3(pc.model))) * inNormal;
    vColor = inColor;
    vWorld = world.xyz;
    vMaterial = inMaterial;
    vSurface = inSurface;
}
