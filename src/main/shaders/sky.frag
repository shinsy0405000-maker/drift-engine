#version 450
#extension GL_GOOGLE_include_directive : require

// 하늘: 대기 산란 표(skyLut) + 해 원반 + 구름. 물체가 없는 곳(깊이 1)에만 그린다.
#include "frame.glsl"

layout(location = 0) in vec2 vNdc;
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outMotion;

float fbm5(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        s += a * valueNoise(p);
        p *= 2.02;
        a *= 0.5;
    }
    return s;
}

void main() {
    vec4 far = f.invViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w - f.cameraPos.xyz);

    vec3 col = skyColor(dir);
    // 해 원반
    float mu = dot(dir, f.sunDir.xyz);
    col += f.sunColor.rgb * 18.0 * smoothstep(0.99955, 0.99975, mu);

    // 구름: 높이 1500m 평면에 흐르는 fbm
    if (dir.y > 0.01) {
        float t = 1500.0 / dir.y;
        vec2 p = (f.cameraPos.xz + dir.xz * t) * 0.00045 + vec2(f.options.w * 0.004, 0.0);
        float c = fbm5(p) * 0.7 + fbm5(p * 3.1 + 7.0) * 0.3;
        float cover = smoothstep(0.48, 0.78, c) * smoothstep(0.01, 0.12, dir.y);
        float lit = 0.65 + 0.35 * clamp(dot(normalize(vec3(dir.x, 0.0, dir.z)), normalize(vec3(f.sunDir.x, 0.0, f.sunDir.z))), 0.0, 1.0);
        // 구름: 해 쪽 가장자리가 밝게 (은빛 테두리), 두꺼운 곳은 아래가 어둡게
        float thick = smoothstep(0.55, 0.95, c);
        float silver = pow(max(dot(dir, f.sunDir.xyz), 0.0), 12.0) * (1.0 - thick) * 2.5;
        vec3 cloud = mix(vec3(0.55, 0.58, 0.65), vec3(1.05, 0.98, 0.92), lit) * f.sunDir.w * 1.6 * (1.0 - 0.35 * thick)
                   + f.sunColor.rgb * silver * 0.3;
        col = mix(col, cloud, cover * 0.85);
    }

    // 모션 벡터: 하늘은 무한히 멀리 있으므로 카메라 회전만으로 움직인다
    vec3 pos = f.cameraPos.xyz + dir * 5000.0;
    vec4 curr = f.viewProj * vec4(pos, 1.0);
    vec4 prev = f.prevViewProj * vec4(pos, 1.0);
    outMotion = (prev.xy / prev.w - curr.xy / curr.w) * 0.5;
    if (f.options.x > 0.5) {
        outColor = vec4(0.5 + outMotion * f.options.y, 0.5, 1.0);
        return;
    }
    outColor = vec4(col, 1.0);
}
