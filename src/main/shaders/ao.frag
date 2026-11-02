#version 450
#extension GL_GOOGLE_include_directive : require

// 화면 공간 AO: 깊이로 월드 위치·법선을 되살리고, 법선 쪽 반구 안 16점이 다른 물체 뒤에 숨는지 본다.
// 결과(1 = 안 가려짐)는 ao_blur.frag 가 깊이를 보며 4x4 로 흐린다. 렌더 해상도.
#include "frame.glsl"

layout(set = 1, binding = 0) uniform sampler2D depthTex;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outAo;

vec3 worldAt(vec2 uv, float d) {
    vec4 w = f.invViewProj * vec4(uv * 2.0 - 1.0, d, 1.0);
    return w.xyz / w.w;
}

void main() {
    float d = texture(depthTex, vUv).r;
    if (d >= 1.0) {
        outAo = vec4(1.0);
        return;
    }
    vec3 p = worldAt(vUv, d);
    // 법선: 옆 픽셀과의 차이 중 작은 쪽 (물체 경계에서 튀지 않게)
    vec2 px = f.viewport.zw;
    vec3 pr = worldAt(vUv + vec2(px.x, 0.0), texture(depthTex, vUv + vec2(px.x, 0.0)).r);
    vec3 pl = worldAt(vUv - vec2(px.x, 0.0), texture(depthTex, vUv - vec2(px.x, 0.0)).r);
    vec3 pd = worldAt(vUv + vec2(0.0, px.y), texture(depthTex, vUv + vec2(0.0, px.y)).r);
    vec3 pu = worldAt(vUv - vec2(0.0, px.y), texture(depthTex, vUv - vec2(0.0, px.y)).r);
    vec3 dx = length(pr - p) < length(p - pl) ? pr - p : p - pl;
    vec3 dy = length(pd - p) < length(p - pu) ? pd - p : p - pu;
    vec3 n = normalize(cross(dy, dx));
    vec3 toCam = f.cameraPos.xyz - p;
    if (dot(n, toCam) < 0.0) {
        n = -n;
    }
    // 4x4 반복 무늬로 돌린 접선 틀 (흐리기가 무늬를 지운다)
    vec2 pix = floor(vUv * f.viewport.xy);
    float ang = (mod(pix.x, 4.0) * 4.0 + mod(pix.y, 4.0)) / 16.0 * 6.2831853 + mod(f.features.w, 8.0) * 0.785;
    vec3 t = normalize(abs(n.y) < 0.9 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, t);
    float c = cos(ang);
    float s = sin(ang);
    vec3 tr = t * c + b * s;
    vec3 br = -t * s + b * c;
    float dist = length(toCam);
    float radius = clamp(0.35 + dist * 0.02, 0.35, 1.2);
    float occ = 0.0;
    for (int i = 0; i < 16; i++) {
        // 반구 안 점 (가운데에 몰리게)
        float fi = float(i);
        float u = fract(fi * 0.618034 + 0.13);
        float r = mix(0.12, 1.0, (fi + 0.5) / 16.0);
        r *= r;
        float phi = fi * 2.399963;
        vec3 sdir = normalize(tr * cos(phi) * sqrt(1.0 - u) + br * sin(phi) * sqrt(1.0 - u) + n * (sqrt(u) + 0.15));
        vec3 sp = p + sdir * radius * r;
        vec4 clip = f.viewProjJittered * vec4(sp, 1.0);
        if (clip.w <= 0.0) {
            continue;
        }
        vec2 suv = clip.xy / clip.w * 0.5 + 0.5;
        if (suv.x < 0.0 || suv.y < 0.0 || suv.x > 1.0 || suv.y > 1.0) {
            continue;
        }
        float sd = texture(depthTex, suv).r;
        vec3 q = worldAt(suv, sd);
        float dq = length(q - f.cameraPos.xyz);
        float ds = length(sp - f.cameraPos.xyz);
        float range = smoothstep(0.0, 1.0, radius / max(length(q - p), 1e-3));
        occ += (dq < ds - 0.03 * (1.0 + dist * 0.02) ? 1.0 : 0.0) * range;
    }
    float ao = clamp(1.0 - occ / 16.0 * 1.4, 0.0, 1.0);
    outAo = vec4(ao * ao);
}
