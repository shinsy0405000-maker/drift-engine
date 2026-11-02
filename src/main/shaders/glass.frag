#version 450
#extension GL_GOOGLE_include_directive : require

// 투명 물체 (차 유리, 라이트 덮개): 하늘 반사 + 해 하이라이트, 뒤쪽은 비치게.
// 미리 곱한 알파 섞기 (ONE, ONE_MINUS_SRC_ALPHA): 색 = 반사 빛, 알파 = 뒤가 가려지는 정도.
#include "frame.glsl"
#include "lighting.glsl"

layout(location = 0) in vec3 vColor;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec4 vCurrClip;
layout(location = 3) in vec4 vPrevClip;
layout(location = 4) in vec3 vWorld;
layout(location = 5) in vec4 vMaterial;
layout(location = 6) in float vSurface;

layout(location = 0) out vec4 outColor;

void main() {
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) {
        n = -n;
    }
    int surface = int(vSurface + 0.5);
    vec3 toCam = f.cameraPos.xyz - vWorld;
    float dist = length(toCam);
    vec3 v = toCam / max(dist, 1e-4);
    vec3 l = f.sunDir.xyz;
    float NoV = max(dot(n, v), 1e-4);
    float NoL = max(dot(n, l), 0.0);
    vec3 h = normalize(v + l);
    float NoH = max(dot(n, h), 0.0);
    float F = 0.04 + 0.96 * pow(1.0 - NoV, 5.0);
    float rough = 0.03;
    vec3 refl = environment(reflect(-v, n), rough);
    float sun = sunShadow(vWorld, n, NoL);
    float spec = distributionGGX(NoH, rough * rough) * F * NoL / (4.0 * NoV + 1e-4);
    // 라이트 덮개는 거의 투명, 차 유리는 썬팅 (재질 metallic 칸 = 틴트 단계 0~1 → 짙기 0.30~0.85)
    float base = surface == 5 ? 0.04 : mix(0.30, 0.85, clamp(vMaterial.x, 0.0, 1.0));
    float opacity = clamp(mix(base, 1.0, F), 0.0, 1.0);
    vec3 tint = vColor * (skyColor(vec3(0.0, 1.0, 0.0)) * 0.3) * (1.0 - F);
    vec3 color = refl * F + f.sunColor.rgb * spec * sun + tint * base;
    float fog = 1.0 - exp(-dist * f.sunColor.w);
    color = mix(color, skyColor(-v) * opacity, clamp(fog, 0.0, 1.0));
    outColor = vec4(color, opacity);
}
