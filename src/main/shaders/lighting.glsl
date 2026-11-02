// 표면 빛 계산 (scene.frag, glass.frag 공용). frame.glsl 다음에 include.
// 해(GGX + 클리어코트 + 도장 입자) + 하늘 주변광/반사 + 단계별 해 그림자 + AO + 높이 안개.

// 이 픽셀이 덮는 표면 크기 (m). scene.frag / glass.frag 가 main 첫머리에서 채운다.
// 픽셀보다 작은 무늬(도장 입자, 아스팔트 알갱이)는 이 값으로 줄여서 지글거림(에일리어싱)을 막는다
float gFootprint = 0.0;

const float SURFACE_ASPHALT = 1.0;
const float SURFACE_GROUND = 2.0;
const float SURFACE_TIRE = 3.0;
const float SURFACE_GLASS = 4.0;
const float SURFACE_LENS = 5.0;
const float SURFACE_CARBON = 6.0;

// ------------------------------------------------------------------ PBR (GGX)
float distributionGGX(float NoH, float a) {
    float a2 = a * a;
    float d = NoH * NoH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1e-7);
}

float geometrySmith(float NoV, float NoL, float rough) {
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float gv = NoV / (NoV * (1.0 - k) + k);
    float gl = NoL / (NoL * (1.0 - k) + k);
    return gv * gl;
}

vec3 fresnelSchlick(float cosT, vec3 f0) {
    return f0 + (1.0 - f0) * pow(1.0 - cosT, 5.0);
}

// 환경 반사 BRDF 근사 (Karis, 미리 계산한 표 대신)
vec2 envBRDF(float NoV, float rough) {
    const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 r = rough * c0 + c1;
    float a004 = min(r.x * r.x, exp2(-9.28 * NoV)) * r.x + r.y;
    return vec2(-1.04, 1.04) * a004 + r.zw;
}

// 반사 방향의 주변 색: 하늘, 아래쪽은 땅 반사. 거칠수록 흐린(평균) 하늘
vec3 environment(vec3 r, float rough) {
    vec3 sharp = skyColor(r);
    vec3 blurred = mix(skyColor(normalize(vec3(r.x, max(r.y, 0.0) + 0.5, r.z))), vec3(0.30, 0.31, 0.30) * f.sunDir.w, 0.25);
    vec3 env = mix(sharp, blurred, clamp(rough * 1.3, 0.0, 1.0));
    float below = smoothstep(0.05, -0.15, r.y);
    return mix(env, vec3(0.10, 0.10, 0.10) * f.sunDir.w, below);
}

// ------------------------------------------------------------------ 해 그림자 (4단계, 16탭 포아송 PCF)
const vec2 POISSON[16] = vec2[](
    vec2(-0.94201624, -0.39906216), vec2(0.94558609, -0.76890725), vec2(-0.09418410, -0.92938870), vec2(0.34495938, 0.29387760),
    vec2(-0.91588581, 0.45771432), vec2(-0.81544232, -0.87912464), vec2(-0.38277543, 0.27676845), vec2(0.97484398, 0.75648379),
    vec2(0.44323325, -0.97511554), vec2(0.53742981, -0.47373420), vec2(-0.26496911, -0.41893023), vec2(0.79197514, 0.19090188),
    vec2(-0.24188840, 0.99706507), vec2(-0.81409955, 0.91437590), vec2(0.19984126, 0.78641367), vec2(0.14383161, -0.14100790));

float cascadeLit(int c, vec3 p, vec3 n, float NoL, float rot) {
    float texel = f.cascadeTexel[c];
    vec3 q = p + n * texel * (1.5 + 2.5 * (1.0 - NoL));
    vec4 clip = f.cascade[c] * vec4(q, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    vec2 uv = ndc.xy * 0.5 + 0.5;
    if (uv.x <= 0.0 || uv.y <= 0.0 || uv.x >= 1.0 || uv.y >= 1.0 || ndc.z >= 1.0 || ndc.z <= 0.0) {
        return -1.0;
    }
    vec2 px = 1.0 / vec2(textureSize(shadowCascades, 0).xy);
    float radius = 1.8;
    float s = sin(rot);
    float co = cos(rot);
    float sum = 0.0;
    for (int i = 0; i < 16; i++) {
        vec2 o = POISSON[i];
        o = vec2(o.x * co - o.y * s, o.x * s + o.y * co);
        sum += texture(shadowCascades, vec4(uv + o * px * radius, float(c), ndc.z));
    }
    return sum / 16.0;
}

float sunShadow(vec3 p, vec3 n, float NoL) {
    if (f.shadowParams.x < 0.5) {
        return 1.0;
    }
    float depth = dot(p - f.cameraPos.xyz, f.cameraForward.xyz);
    // 픽셀마다 PCF 돌리는 각: 인터리브드 그래디언트 노이즈 (시간 누적 AA/DLSS 가 잘 지우는 무늬). 프레임마다 조금 이동
    vec2 ign = gl_FragCoord.xy + 5.588238 * mod(f.features.w, 64.0);
    float rot = fract(52.9829189 * fract(dot(ign, vec2(0.06711056, 0.00583715)))) * 6.2831853;
    for (int c = 0; c < 4; c++) {
        float far = f.cascadeFar[c];
        if (depth < far) {
            float lit = cascadeLit(c, p, n, NoL, rot);
            if (lit < 0.0) {
                continue;
            }
            // 단계 끝부분은 다음 단계와 섞어 경계가 안 보이게
            float near = c == 0 ? 0.0 : f.cascadeFar[c - 1];
            float k = smoothstep(far - (far - near) * f.shadowParams.z, far, depth);
            if (k > 0.0 && c < 3) {
                float next = cascadeLit(c + 1, p, n, NoL, rot);
                if (next >= 0.0) {
                    lit = mix(lit, next, k);
                }
            }
            return mix(1.0, lit, f.shadowParams.w);
        }
    }
    return 1.0;
}

// 차 밑 접촉 그림자: 차 바닥 사각형 안쪽일수록 어둡게 (그림자 맵이 못 잡는 바로 밑 어둠)
float contactShadow(vec3 p) {
    if (f.shadowCenter.w <= 0.0) {
        return 1.0;
    }
    vec2 fwd = normalize(f.shadowForward.xy);
    vec2 side = vec2(-fwd.y, fwd.x);
    vec2 d = p.xz - f.shadowCenter.xz;
    vec2 local = vec2(dot(d, side) / (f.shadowCenter.w + 0.25), dot(d, fwd) / (f.shadowForward.z + 0.35));
    float inside = 1.0 - smoothstep(0.55, 1.15, length(pow(abs(local), vec2(3.0))));
    float height = clamp((p.y - f.shadowCenter.y) / 0.45, 0.0, 1.0);
    return 1.0 - 0.6 * inside * (1.0 - height);
}

// ------------------------------------------------------------------ 표면 무늬
void applySurface(int surface, vec3 p, inout vec3 base, inout float rough, inout float metallic, inout vec3 n) {
    vec2 q = p.xz;
    if (surface == 1) {
        // 아스팔트: 큰 얼룩 + 잔 알갱이 + 흰 원형 차선 (반지름 30m, 60m) + 출발선
        float blotch = fbm(q * 0.15);
        float grain = hash12(floor(q * 40.0));
        // 2.5cm 알갱이: 픽셀보다 작아지면 평균 밝기로 (먼 곳 지글거림 방지)
        float grainVis = 1.0 - smoothstep(0.012, 0.03, gFootprint);
        base = vec3(0.055, 0.056, 0.060) * (0.75 + 0.55 * blotch) + vec3(0.03) * mix(0.07, step(0.93, grain), grainVis);
        rough = 0.82 + 0.12 * blotch;
        float r = length(q);
        float ring = min(abs(r - 30.0), abs(r - 60.0));
        float line = 1.0 - smoothstep(0.12, 0.16, ring);
        float start = (1.0 - smoothstep(0.15, 0.19, abs(q.y + 22.0))) * step(abs(q.x), 6.0);
        float paint = max(line, start) * (0.75 + 0.25 * fbm(q * 3.0));
        base = mix(base, vec3(0.62, 0.62, 0.58), paint);
        rough = mix(rough, 0.55, paint);
        // 미세 요철 (거칠기 + 법선 흔들기)
        vec2 e = vec2(0.05, 0.0);
        float h0 = valueNoise(q * 9.0);
        n = normalize(n + vec3(h0 - valueNoise((q + e.xy) * 9.0), 0.0, h0 - valueNoise((q + e.yx) * 9.0)) * 0.6);
    } else if (surface == 7) {
        // 맵 도로: 정점 색이 어두우면 아스팔트(색은 밝기 비율로), 밝으면 그 색 페인트 (차선·연석·횡단보도)
        float paintMask = step(0.2, max(base.r, max(base.g, base.b)));
        vec3 tint = clamp(base / 0.06, vec3(0.6), vec3(1.6));
        float blotch = fbm(q * 0.15);
        float grain = hash12(floor(q * 40.0));
        float grainVis = 1.0 - smoothstep(0.012, 0.03, gFootprint);
        vec3 asphalt = (vec3(0.055, 0.056, 0.060) * (0.75 + 0.55 * blotch)
                + vec3(0.03) * mix(0.07, step(0.93, grain), grainVis)) * tint;
        vec3 paint = base * (0.82 + 0.18 * fbm(q * 3.0));
        base = mix(asphalt, paint, paintMask);
        rough = mix(0.82 + 0.12 * blotch, 0.55, paintMask);
        vec2 e = vec2(0.05, 0.0);
        float h0 = valueNoise(q * 9.0);
        n = normalize(n + vec3(h0 - valueNoise((q + e.xy) * 9.0), 0.0, h0 - valueNoise((q + e.yx) * 9.0)) * (0.6 - 0.35 * paintMask));
    } else if (surface == 2) {
        float large = fbm(q * 0.05);
        float small = fbm(q * 0.9);
        base *= 0.7 + 0.45 * large + 0.25 * small;
        base = mix(base, base * vec3(1.15, 1.05, 0.7), smoothstep(0.55, 0.8, large)); // 마른 풀
        rough = 0.95;
    } else if (surface == 3) {
        rough = 0.9;
    } else if (surface == 6) {
        // 카본 능직 (2x2 트윌): 세계 좌표 기준 작은 격자, 결 방향에 따라 밝기 다름
        vec3 a = abs(n);
        vec2 uv = a.y > 0.6 ? p.xz : (a.x > a.z ? p.zy : p.xy);
        vec2 cell = floor(uv * 160.0);
        float weave = mod(cell.x + cell.y, 4.0) < 2.0 ? 1.0 : 0.0;
        base *= mix(0.55, 1.35, weave);
        rough = mix(0.22, 0.38, weave);
    }
}

// 메탈릭 도장의 반짝이 (입자마다 조금씩 다른 방향으로 해를 반사)
float flakeSparkle(vec3 p, vec3 n, vec3 v, vec3 l) {
    if (f.features.y < 0.5) {
        return 0.0;
    }
    // 입자(1.1mm)가 픽셀보다 훨씬 작으면 점 하나하나가 프레임마다 튀어 노이즈가 된다 → 가까이서만 보이게
    float visible = 1.0 - smoothstep(0.002, 0.006, gFootprint);
    if (visible <= 0.0) {
        return 0.0;
    }
    vec3 cell = floor(p * 900.0);
    vec3 rnd = hash33(cell) * 2.0 - 1.0;
    vec3 fn = normalize(n + rnd * 0.35);
    vec3 h = normalize(v + l);
    float sp = pow(max(dot(fn, h), 0.0), 600.0);
    return sp * step(0.55, hash33(cell + 7.0).x) * 12.0 * visible;
}

// ------------------------------------------------------------------ 높이 안개 (해 쪽은 밝게 산란)
vec3 applyFog(vec3 color, vec3 p, vec3 v, float dist) {
    float fog = 1.0 - exp(-dist * f.sunColor.w);
    // 높이 안개: 낮은 곳일수록 짙음 (카메라 → 점 사이 적분)
    float k = f.fogParams.y;
    float h0 = f.cameraPos.y - f.fogParams.x;
    float h1 = p.y - f.fogParams.x;
    float dh = h1 - h0;
    float integral = abs(dh) > 1e-3 ? (exp(-k * h0) - exp(-k * h1)) / (k * dh) : exp(-k * h0);
    float hf = 1.0 - exp(-dist * f.features.z * clamp(integral, 0.0, 1.0));
    float total = clamp(1.0 - (1.0 - fog) * (1.0 - hf), 0.0, 1.0);
    vec3 dir = -v;
    float mu = max(dot(dir, f.sunDir.xyz), 0.0);
    vec3 inscatter = skyColor(normalize(vec3(dir.x, max(dir.y, 0.02), dir.z))) + f.sunColor.rgb * pow(mu, 8.0) * f.fogParams.z;
    return mix(color, inscatter, total);
}

// ------------------------------------------------------------------ 표면 하나의 빛 (불투명)
// p = 월드 위치, n = 법선(앞면 기준), ao = 화면 공간 AO
vec3 shadeOpaque(vec3 p, vec3 nIn, vec3 baseIn, vec4 mat, int surface, float ao) {
    vec3 n = nIn;
    vec3 toCam = f.cameraPos.xyz - p;
    float dist = length(toCam);
    vec3 v = toCam / max(dist, 1e-4);
    vec3 l = f.sunDir.xyz;

    vec3 base = baseIn;
    float metallic = mat.x;
    float rough = clamp(mat.y, 0.04, 1.0);
    float coat = mat.z;
    float emissive = mat.w;
    applySurface(surface, p, base, rough, metallic, n);

    float NoV = max(dot(n, v), 1e-4);
    float NoL = max(dot(n, l), 0.0);
    vec3 h = normalize(v + l);
    float NoH = max(dot(n, h), 0.0);
    float VoH = max(dot(v, h), 0.0);

    vec3 f0 = mix(vec3(0.04), base, metallic);
    vec3 albedo = base * (1.0 - metallic);

    float a = rough * rough;
    vec3 F = fresnelSchlick(VoH, f0);
    vec3 spec = distributionGGX(NoH, a) * geometrySmith(NoV, NoL, rough) * F / (4.0 * NoV * max(NoL, 1e-4) + 1e-4);
    vec3 kd = (1.0 - F);
    vec3 direct = (kd * albedo / PI + spec) * NoL;
    if (coat > 0.9 && metallic > 0.3) {
        direct += base * flakeSparkle(p, n, v, l) * NoL;
    }

    // 클리어코트: 맨 위 매끈한 막 (F0 0.04, 거칠기 0.04)
    float Fc = 0.04 + 0.96 * pow(1.0 - VoH, 5.0);
    float coatSpec = distributionGGX(NoH, 0.04 * 0.04) * geometrySmith(NoV, NoL, 0.04) * Fc / (4.0 * NoV * max(NoL, 1e-4) + 1e-4);
    float coatView = 0.04 + 0.96 * pow(1.0 - NoV, 5.0);
    direct = direct * (1.0 - coat * Fc) + coat * coatSpec * NoL;

    float contact = contactShadow(p);
    float sun = sunShadow(p, nIn, NoL);
    vec3 color = direct * f.sunColor.rgb * min(contact, sun);

    // 주변광: 위쪽은 하늘, 아래쪽은 땅에서 튄 빛. AO·접촉 그림자로 가림
    vec3 skyUp = skyColor(normalize(vec3(0.0, 1.0, 0.0) + n * 0.6));
    vec3 groundBounce = vec3(0.16, 0.15, 0.13) * f.sunDir.w;
    vec3 irradiance = mix(groundBounce, skyUp, clamp(n.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 r = reflect(-v, n);
    vec2 ab = envBRDF(NoV, rough);
    float specOcc = clamp(pow(NoV + ao, exp2(-16.0 * a - 1.0)) - 1.0 + ao, 0.0, 1.0);
    vec3 envSpec = environment(r, rough) * (f0 * ab.x + ab.y) * specOcc;
    vec3 ambient = albedo * irradiance * ao + envSpec;
    ambient = ambient * (1.0 - coat * coatView) + coat * coatView * environment(r, 0.04) * mix(1.0, ao, 0.5);
    color += ambient * mix(1.0, contact, 0.85) * mix(1.0, sun, 0.25);

    color += base * emissive;
    return applyFog(color, p, v, dist);
}
