#version 450
#extension GL_GOOGLE_include_directive : require

// 하늘 표 채우기: 표의 각 칸 = 한 방향, 대기 산란(레일리 + 미)을 광선 따라 적분 (단일 산란).
// 결과는 frame.glsl 의 skyColor() 가 읽는다 (하늘, 반사, 안개가 모두 같은 하늘을 씀).
#include "frame.glsl"

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

const float RG = 6360e3;
const float RT = 6420e3;
const vec3 BETA_R = vec3(5.8e-6, 13.5e-6, 33.1e-6);
const float HR = 8000.0;
const float BETA_M = 6e-6;
const float HM = 1200.0;
const float G = 0.76;
const float SUN_INTENSITY = 55.0;

vec2 raySphere(vec3 o, vec3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) {
        return vec2(-1.0);
    }
    float s = sqrt(disc);
    return vec2(-b - s, -b + s);
}

// 점 p 에서 해 쪽으로 대기 끝까지의 광학 깊이 (레일리, 미). 땅에 막히면 큰 값
vec2 sunDepth(vec3 p, vec3 s) {
    vec2 tg = raySphere(p, s, RG);
    if (tg.x > 0.0) {
        return vec2(1e9);
    }
    float t = raySphere(p, s, RT).y;
    float ds = t / 8.0;
    vec2 acc = vec2(0.0);
    for (int i = 0; i < 8; i++) {
        float h = length(p + s * (ds * (float(i) + 0.5))) - RG;
        acc += vec2(exp(-h / HR), exp(-h / HM)) * ds;
    }
    return acc;
}

void main() {
    vec3 d = skyLutDir(vUv);
    vec3 s = normalize(f.sunDir.xyz);
    vec3 o = vec3(0.0, RG + max(2.0, f.cameraPos.y) + 200.0, 0.0);
    float tmax = raySphere(o, d, RT).y;
    vec2 tg = raySphere(o, d, RG);
    if (tg.x > 0.0) {
        tmax = tg.x;
    }
    const int N = 32;
    float ds = tmax / float(N);
    float mu = dot(d, s);
    float pr = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float pm = 3.0 / (8.0 * PI) * ((1.0 - G * G) * (1.0 + mu * mu)) / ((2.0 + G * G) * pow(1.0 + G * G - 2.0 * G * mu, 1.5));
    vec3 sum = vec3(0.0);
    vec2 od = vec2(0.0);
    for (int i = 0; i < N; i++) {
        vec3 p = o + d * (ds * (float(i) + 0.5));
        float h = length(p) - RG;
        vec2 dens = vec2(exp(-h / HR), exp(-h / HM)) * ds;
        od += dens;
        vec2 sd = sunDepth(p, s);
        if (sd.x > 1e8) {
            continue;
        }
        vec3 tau = BETA_R * (od.x + sd.x) + BETA_M * 1.1 * (od.y + sd.y);
        sum += exp(-tau) * (dens.x * BETA_R * pr + dens.y * BETA_M * pm);
    }
    vec3 L = sum * SUN_INTENSITY;
    // 다중 산란을 생략한 대신 밝기 범위를 부드럽게 눌러 기존 노출에 맞춤 (지평선이 하얗게 타지 않게)
    float lum = dot(L, vec3(0.2126, 0.7152, 0.0722));
    L *= 1.3 / (1.0 + 0.3 * lum);
    outColor = vec4(L * f.sunDir.w, 1.0);
}
