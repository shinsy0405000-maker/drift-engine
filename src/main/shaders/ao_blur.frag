#version 450
#extension GL_GOOGLE_include_directive : require

// AO 흐리기: 4x4 (ao.frag 의 반복 무늬 크기), 깊이가 많이 다른 픽셀은 빼서 물체 경계가 번지지 않게
#include "frame.glsl"

layout(set = 1, binding = 0) uniform sampler2D depthTex;
layout(set = 1, binding = 1) uniform sampler2D rawAo;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outAo;

float viewDist(vec2 uv) {
    float d = texture(depthTex, uv).r;
    vec4 w = f.invViewProj * vec4(uv * 2.0 - 1.0, d, 1.0);
    return length(w.xyz / w.w - f.cameraPos.xyz);
}

void main() {
    float center = viewDist(vUv);
    float sum = 0.0;
    float wsum = 0.0;
    for (int y = -2; y < 2; y++) {
        for (int x = -2; x < 2; x++) {
            vec2 uv = vUv + (vec2(x, y) + 0.5) * f.viewport.zw;
            float dd = abs(viewDist(uv) - center) / max(center, 0.1);
            float w = exp(-dd * 40.0);
            sum += texture(rawAo, uv).r * w;
            wsum += w;
        }
    }
    outAo = vec4(sum / max(wsum, 1e-4));
}
