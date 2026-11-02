#version 450
#extension GL_GOOGLE_include_directive : require

// 반투명 효과: 스키드 자국(종류 0) + 타이어 연기(종류 1). EffectsRenderer 가 정점을 만든다.
// material = (진하기, 종류, u, v), surface = 무늬 씨앗, color = 밝기
#include "frame.glsl"

layout(location = 0) in vec3 vColor;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec4 vCurrClip;
layout(location = 3) in vec4 vPrevClip;
layout(location = 4) in vec3 vWorld;
layout(location = 5) in vec4 vMaterial;
layout(location = 6) in float vSurface;

layout(location = 0) out vec4 outColor;

void main() {
    float alpha = vMaterial.x;
    int kind = int(vMaterial.y + 0.5);
    vec2 uv = vMaterial.zw;
    vec3 toCam = f.cameraPos.xyz - vWorld;
    float dist = length(toCam);
    vec3 v = toCam / max(dist, 1e-4);
    vec3 col;
    float a;
    if (kind == 0) {
        // 스키드 자국: 띠 가운데가 진하고, 길이 방향 얼룩 + 폭 방향 트레드 줄
        float across = 1.0 - smoothstep(0.55, 1.0, abs(uv.x * 2.0 - 1.0));
        float tread = 0.7 + 0.3 * step(0.35, fract(uv.x * 7.0));
        float smear = 0.75 + 0.25 * valueNoise(vec2(uv.x * 3.0, uv.y * 0.8) + vSurface * 0.37);
        a = alpha * across * tread * smear;
        col = vec3(0.010, 0.010, 0.012) * (0.4 + f.sunDir.w);
    } else {
        // 연기: 둥근 덩어리 + 무늬, 해를 등지고 보면(역광) 밝게 빛남
        vec2 d = uv * 2.0 - 1.0;
        float r = length(d);
        // 카메라 코앞의 연기는 옅게 (화면을 덮는 큰 사각형 = 픽셀 계산 폭증 방지), 모서리는 계산 전에 버림
        float nearFade = smoothstep(0.8, 3.0, dist);
        if (r > 1.25 || alpha * nearFade < 0.003) {
            discard;
        }
        alpha *= nearFade;
        float n = fbm(uv * 2.2 + vec2(vSurface * 13.7, vSurface * 7.3));
        float puff = 1.0 - smoothstep(0.25, 1.0, r + (n - 0.5) * 0.6);
        a = alpha * puff * (0.6 + 0.4 * n);
        float back = pow(max(dot(-v, f.sunDir.xyz), 0.0), 6.0);
        vec3 ambient = vec3(0.50, 0.55, 0.63) * f.sunDir.w;
        float sunUp = clamp(f.sunDir.y * 2.0, 0.2, 1.0);
        col = vColor * (ambient + f.sunColor.rgb * sunUp * (0.30 + 0.9 * back) * (0.6 + 0.4 * n));
    }
    // 먼 곳은 하늘색 공기에 묻힘 (scene.frag 와 비슷하게)
    float fog = clamp(1.0 - exp(-dist * f.sunColor.w), 0.0, 1.0);
    col = mix(col, skyColor(normalize(vec3(-v.x, max(-v.y, 0.02), -v.z))), fog);
    a *= 1.0 - 0.6 * fog;
    if (a < 0.003) {
        discard;
    }
    outColor = vec4(col, clamp(a, 0.0, 1.0));
}
