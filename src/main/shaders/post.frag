#version 450

// 출력 해상도 HDR(업스케일 결과) → 모션 블러 → 색수차 → 블룸 → 렌즈 플레어 → 노출·톤매핑(ACES 또는 AgX)
// → 색감 → 비네트 → 필름 그레인 → 화면
layout(set = 0, binding = 0) uniform sampler2D hdrImage;
layout(set = 0, binding = 1) uniform sampler2D bloomImage;  // 반 해상도 블룸 (Bloom.java)
layout(set = 0, binding = 2) uniform sampler2D motionImage; // 렌더 해상도 모션 벡터 (uv 단위, 현재 → 이전)

layout(push_constant) uniform Push {
    vec4 params;   // x: 노출, y: 1 이면 sRGB 변환을 셰이더가 함(UNORM 스왑체인), z: 비네트 세기, w: 프레임 번호
    vec4 params2;  // x: 블룸 세기, y: 모션 블러 세기, z: 그레인 세기, w: 색수차 세기
    vec4 sun;      // xy: 해의 화면 uv, z: 해가 화면 앞쪽에 있으면 1, w: 플레어 세기
    vec4 params3;  // x: 톤매핑 (0 ACES, 1 AgX), y: 채도, z, w: 0
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

// ACES 필름 곡선 근사 (Narkowicz 2015)
vec3 aces(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

// AgX 근사: 밝은 곳이 하얗게 날아가지 않고 색이 부드럽게 빠짐
vec3 agx(vec3 v) {
    const mat3 inset = mat3(0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                            0.0784335999999992, 0.878468636469772, 0.0784336,
                            0.0792237451477643, 0.0791661274605434, 0.879142973793104);
    const mat3 outset = mat3(1.19687900512017, -0.0528968517574562, -0.0529716355144438,
                             -0.0980208811401368, 1.15190312990417, -0.0980434501171241,
                             -0.0990297440797205, -0.0989611768448433, 1.15107367264116);
    const float minEv = -12.47393;
    const float maxEv = 4.026069;
    v = inset * max(v, vec3(1e-10));
    v = clamp(log2(v), minEv, maxEv);
    v = (v - minEv) / (maxEv - minEv);
    vec3 v2 = v * v;
    vec3 v4 = v2 * v2;
    v = 15.5 * v4 * v2 - 40.14 * v4 * v + 31.96 * v4 - 6.868 * v2 * v + 0.4298 * v2 + 0.1191 * v - 0.00232;
    v = outset * v;
    return clamp(pow(max(v, vec3(0.0)), vec3(2.2)), 0.0, 1.0);
}

vec3 linearToSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

vec3 srgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 sampleScene(vec2 uv) {
    // 색수차: 화면 가장자리로 갈수록 빨강·파랑이 조금 벌어짐
    vec2 d = uv - 0.5;
    vec2 off = d * dot(d, d) * pc.params2.w * 0.012;
    float r = texture(hdrImage, uv + off).r;
    float g = texture(hdrImage, uv).g;
    float b = texture(hdrImage, uv - off).b;
    return vec3(r, g, b);
}

// 해 렌즈 플레어: 해 → 화면 가운데 → 반대쪽 직선 위의 고스트 + 해 둘레 빛무리
vec3 flare(vec2 uv) {
    if (pc.sun.z < 0.5 || pc.sun.w <= 0.0) {
        return vec3(0.0);
    }
    vec2 s = pc.sun.xy;
    // 해가 실제로 보이는지: 블룸 이미지에서 해 자리가 밝은지 (건물·차에 가리면 어두움)
    float vis = 0.0;
    for (int i = 0; i < 6; i++) {
        vec2 o = vec2(float(i % 3) - 1.0, float(i / 3) - 0.5) * 0.004;
        vis += smoothstep(1.5, 6.0, dot(textureLod(bloomImage, s + o, 0.0).rgb, vec3(0.33)));
    }
    vis /= 6.0;
    vis *= smoothstep(-0.1, 0.05, s.x) * smoothstep(1.1, 0.95, s.x) * smoothstep(-0.1, 0.05, s.y) * smoothstep(1.1, 0.95, s.y);
    if (vis <= 0.001) {
        return vec3(0.0);
    }
    vec2 axis = vec2(0.5) - s;
    vec3 acc = vec3(0.0);
    const float pos[5] = float[](0.35, 0.62, 1.0, 1.35, 1.75);
    const float size[5] = float[](0.025, 0.06, 0.035, 0.09, 0.05);
    const vec3 tint[5] = vec3[](vec3(0.9, 0.5, 0.2), vec3(0.3, 0.6, 1.0), vec3(0.6, 1.0, 0.5), vec3(0.8, 0.4, 1.0), vec3(1.0, 0.8, 0.4));
    float aspect = 16.0 / 9.0;
    for (int i = 0; i < 5; i++) {
        vec2 c = s + axis * pos[i] * 2.0;
        vec2 d = (uv - c) * vec2(aspect, 1.0);
        float r = length(d) / size[i];
        acc += tint[i] * (smoothstep(1.0, 0.7, r) * 0.6 + smoothstep(1.0, 0.95, r) * smoothstep(0.85, 0.95, r) * 0.8) * 0.06;
    }
    // 해 둘레 빛무리 + 가로 줄 (아나모픽 느낌)
    vec2 ds = (uv - s) * vec2(aspect, 1.0);
    float halo = exp(-length(ds) * 7.0) * 0.5;
    float streak = exp(-abs(ds.y) * 220.0) * exp(-abs(ds.x) * 2.2) * 0.6;
    acc += vec3(1.0, 0.85, 0.65) * halo + vec3(0.7, 0.8, 1.0) * streak;
    return acc * vis * pc.sun.w;
}

void main() {
    // 모션 블러: 이 픽셀이 지난 프레임에 있던 쪽으로 여러 번 샘플 (빠를수록 길게, 최대 화면 4%)
    vec2 vel = texture(motionImage, vUv).rg * pc.params2.y;
    float speed = length(vel);
    if (speed > 0.04) {
        vel *= 0.04 / speed;
        speed = 0.04;
    }
    vec3 hdr;
    if (speed > 0.0005) {
        const int N = 12;
        float jitter = hash(gl_FragCoord.xy + pc.params.w) - 0.5;
        vec3 sum = vec3(0.0);
        for (int i = 0; i < N; i++) {
            float t = (float(i) + 0.5 + jitter) / float(N) - 0.5;
            sum += sampleScene(vUv + vel * t);
        }
        hdr = sum / float(N);
    } else {
        hdr = sampleScene(vUv);
    }
    hdr += texture(bloomImage, vUv).rgb * pc.params2.x;
    hdr += flare(vUv);
    hdr *= pc.params.x;

    vec3 col = pc.params3.x > 0.5 ? agx(hdr) : aces(hdr * 0.85);
    // 채도 (톤매핑이 색을 빼는 만큼)
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = clamp(mix(vec3(luma), col, pc.params3.y), 0.0, 1.0);
    // 비네트
    vec2 d = vUv - 0.5;
    col *= 1.0 - pc.params.z * dot(d, d) * 1.6;
    col = clamp(col, 0.0, 1.0);
    // 그레인·디더는 눈에 보이는 밝기(sRGB) 기준으로 더한다.
    // 선형 값에 더하면 어두운 곳에서 sRGB 로 바뀔 때 몇십 배로 커져서 지글거리는 노이즈가 된다
    vec3 s = linearToSrgb(col);
    float grain = hash(gl_FragCoord.xy * 1.37 + fract(pc.params.w * 0.618) * 100.0) - 0.5;
    s += grain * pc.params2.z;
    s += (hash(gl_FragCoord.xy + pc.params.w) - 0.5) / 255.0; // 띠 현상 방지 디더 (8비트 화면)
    s = clamp(s, 0.0, 1.0);
    // sRGB 스왑체인이면 하드웨어가 다시 sRGB 로 바꾸므로 선형으로 돌려준다
    outColor = vec4(pc.params.y > 0.5 ? s : srgbToLinear(s), 1.0);
}
