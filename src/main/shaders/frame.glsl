// 프레임 공통 데이터 (FrameUniforms.java 와 같은 순서, std140, 720 바이트) + 공통 텍스처.
// #include "frame.glsl" 로 쓴다 (glslc 가 같은 폴더에서 찾음).

layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProjJittered;   // 0   그리기용 (DLSS 지터 포함)
    mat4 viewProj;           // 64  모션 벡터용 (지터 없음)
    mat4 prevViewProj;       // 128 이전 프레임
    mat4 invViewProj;        // 192 지터 포함 행렬의 역
    vec4 options;            // 256 x: 모션 벡터 보기, y: 보기 배율, z: 인공 GPU 부하, w: 시간(초)
    vec4 cameraPos;          // 272 xyz, w: 노출
    vec4 sunDir;             // 288 xyz: 해 방향(땅 → 해), w: 하늘빛 세기
    vec4 sunColor;           // 304 rgb, w: 안개 밀도
    vec4 shadowCenter;       // 320 차 밑 접촉 그림자 xyz 중심, w: 반 폭 (0 = 없음)
    vec4 shadowForward;      // 336 x, y(=z 성분): 앞 방향, z: 반 길이
    mat4 cascade[4];         // 352 해 그림자 단계별 행렬 (월드 → 그림자 맵 클립)
    vec4 cascadeFar;         // 608 단계별 끝 거리 (카메라 앞 방향 깊이, m)
    vec4 shadowParams;       // 624 x: 켬, y: 그림자 맵 해상도, z: 단계 사이 섞는 비율, w: 세기
    vec4 features;           // 640 x: AO 켬, y: 도장 입자 켬, z: 높이 안개 세기, w: 프레임 번호
    vec4 viewport;           // 656 렌더 해상도 w, h, 1/w, 1/h
    vec4 cameraForward;      // 672 xyz 카메라 앞, w: near
    vec4 fogParams;          // 688 x: 높이 안개 기준 높이, y: 높이 감쇠(1/m), z: 산란 세기, w: 0
    vec4 cascadeTexel;       // 704 단계별 그림자 맵 텍셀 크기 (m)
} f;

layout(set = 0, binding = 1) uniform sampler2DArrayShadow shadowCascades; // 해 그림자 (단계 4개)
layout(set = 0, binding = 2) uniform sampler2D aoTex;                     // 화면 공간 AO (렌더 해상도, 1 = 안 가려짐)
layout(set = 0, binding = 3) uniform sampler2D skyLut;                    // 하늘 색 (위도·경도 표, 대기 산란)

const float PI = 3.14159265;

// ------------------------------------------------------------------ 노이즈 (텍스처 없이 표면 무늬)
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 hash33(vec3 p3) {
    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yxx) * p3.zyx);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 u = fract(p);
    u = u * u * (3.0 - 2.0 * u);
    return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x), mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
}

float fbm(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * valueNoise(p);
        p *= 2.03;
        a *= 0.5;
    }
    return s;
}

// ------------------------------------------------------------------ 하늘 표 (sky_lut.frag 가 대기 산란으로 채움)
// 방향 → 표 좌표: u = 방위각, v = 고도 (지평선 근처를 촘촘하게)
vec2 skyLutUv(vec3 d) {
    float az = atan(d.x, -d.z) / (2.0 * PI) + 0.5;
    float el = asin(clamp(d.y, -1.0, 1.0));
    float v = 0.5 + 0.5 * sign(el) * sqrt(abs(el) / (PI * 0.5));
    return vec2(az, 1.0 - v);
}

vec3 skyLutDir(vec2 uv) {
    float az = (uv.x - 0.5) * 2.0 * PI;
    float v = 1.0 - uv.y;
    float s = (v - 0.5) * 2.0;
    float el = sign(s) * s * s * (PI * 0.5);
    float c = cos(el);
    return vec3(sin(az) * c, sin(el), -cos(az) * c);
}

// 하늘 색 (해 원반 제외). 아래쪽은 땅 색으로 어둡게
vec3 skyColor(vec3 d) {
    vec3 col = texture(skyLut, skyLutUv(d)).rgb;
    if (d.y < 0.0) {
        col = mix(col, vec3(0.30, 0.31, 0.30) * f.sunDir.w, clamp(-d.y * 4.0, 0.0, 1.0));
    }
    return col;
}
