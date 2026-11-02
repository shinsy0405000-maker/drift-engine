#version 450

// 블룸: 밝은 부분을 절반씩 줄여 가며 흐리게(아래로) → 다시 키우며 더함(위로).
// 아래로: 13탭 (Jimenez 2014, CoD). 첫 단계는 밝은 부분만 남기고(부드러운 문턱) 반딧불 깜빡임을 막는 Karis 평균.
// 위로: 3x3 텐트 필터, 파이프라인이 더하기 섞기(ONE, ONE)로 아래 단계에 더한다.
layout(set = 0, binding = 0) uniform sampler2D src;

layout(push_constant) uniform Push {
    vec4 a;   // xy: 원본 텍셀 크기(uv), z: 문턱, w: 무릎(부드러움)
    vec4 b;   // x: 0 첫 아래, 1 아래, 2 위 / y: 위 필터 반경(텍셀) / z: 위 단계 세기
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

vec3 tap(vec2 offset) {
    return texture(src, vUv + offset * pc.a.xy).rgb;
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 threshold(vec3 c) {
    float br = max(c.r, max(c.g, c.b));
    float knee = pc.a.w;
    float soft = clamp(br - pc.a.z + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-5);
    float contrib = max(soft, br - pc.a.z) / max(br, 1e-5);
    return c * contrib;
}

void main() {
    int mode = int(pc.b.x + 0.5);
    if (mode == 2) {
        float r = pc.b.y;
        vec3 s = tap(vec2(-r, -r)) + 2.0 * tap(vec2(0.0, -r)) + tap(vec2(r, -r))
               + 2.0 * tap(vec2(-r, 0.0)) + 4.0 * tap(vec2(0.0)) + 2.0 * tap(vec2(r, 0.0))
               + tap(vec2(-r, r)) + 2.0 * tap(vec2(0.0, r)) + tap(vec2(r, r));
        outColor = vec4(s / 16.0 * pc.b.z, 1.0);
        return;
    }
    vec3 A = tap(vec2(-2.0, -2.0));
    vec3 B = tap(vec2(0.0, -2.0));
    vec3 C = tap(vec2(2.0, -2.0));
    vec3 D = tap(vec2(-1.0, -1.0));
    vec3 E = tap(vec2(1.0, -1.0));
    vec3 F = tap(vec2(-2.0, 0.0));
    vec3 G = tap(vec2(0.0, 0.0));
    vec3 H = tap(vec2(2.0, 0.0));
    vec3 I = tap(vec2(-1.0, 1.0));
    vec3 J = tap(vec2(1.0, 1.0));
    vec3 K = tap(vec2(-2.0, 2.0));
    vec3 L = tap(vec2(0.0, 2.0));
    vec3 M = tap(vec2(2.0, 2.0));
    // 다섯 묶음 (가운데 0.5, 모서리 넷 각 0.125)
    vec3 g0 = (D + E + I + J) * 0.25;
    vec3 g1 = (A + B + F + G) * 0.25;
    vec3 g2 = (B + C + G + H) * 0.25;
    vec3 g3 = (F + G + K + L) * 0.25;
    vec3 g4 = (G + H + L + M) * 0.25;
    vec3 c;
    if (mode == 0) {
        g0 = threshold(g0);
        g1 = threshold(g1);
        g2 = threshold(g2);
        g3 = threshold(g3);
        g4 = threshold(g4);
        // Karis 평균: 아주 밝은 한 점이 화면 전체를 깜빡이게 하지 않게
        float w0 = 0.5 / (1.0 + luma(g0));
        float w1 = 0.125 / (1.0 + luma(g1));
        float w2 = 0.125 / (1.0 + luma(g2));
        float w3 = 0.125 / (1.0 + luma(g3));
        float w4 = 0.125 / (1.0 + luma(g4));
        c = (g0 * w0 + g1 * w1 + g2 * w2 + g3 * w3 + g4 * w4) / (w0 + w1 + w2 + w3 + w4);
    } else {
        c = g0 * 0.5 + (g1 + g2 + g3 + g4) * 0.125;
    }
    outColor = vec4(max(c, vec3(0.0)), 1.0);
}
