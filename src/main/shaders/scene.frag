#version 450
#extension GL_GOOGLE_include_directive : require

// 불투명 물체: 빛 계산(lighting.glsl) + 모션 벡터 (DLSS 입력)
#include "frame.glsl"
#include "lighting.glsl"

layout(location = 0) in vec3 vColor;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec4 vCurrClip;
layout(location = 3) in vec4 vPrevClip;
layout(location = 4) in vec3 vWorld;
layout(location = 5) in vec4 vMaterial;
layout(location = 6) in float vSurface;

layout(location = 0) out vec4 outColor;   // 선형 HDR (톤매핑은 post.frag)
layout(location = 1) out vec2 outMotion;  // RenderTargets.MOTION_FORMAT (R16G16_SFLOAT)

void main() {
    gFootprint = max(length(dFdx(vWorld)), length(dFdy(vWorld)));
    vec2 currNdc = vCurrClip.xy / vCurrClip.w;
    vec2 prevNdc = vPrevClip.xy / vPrevClip.w;
    outMotion = (prevNdc - currNdc) * 0.5;

    if (f.options.x > 0.5) {
        outColor = vec4(0.5 + outMotion * f.options.y, 0.5, 1.0);
        return;
    }

    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) {
        n = -n;
    }
    float ao = 1.0;
    if (f.features.x > 0.5) {
        ao = texture(aoTex, gl_FragCoord.xy * f.viewport.zw).r;
    }
    vec3 color = shadeOpaque(vWorld, n, vColor, vMaterial, int(vSurface + 0.5), ao);

    // 성능 측정용 인공 부하 (--gpu-load=N)
    int load = int(f.options.z);
    float acc = 0.0;
    for (int i = 0; i < load; i++) {
        acc = sin(acc + float(i) * 0.001 + gl_FragCoord.x * 0.0001);
    }
    color += vec3(acc * 1e-6);

    outColor = vec4(color, 1.0);
}
