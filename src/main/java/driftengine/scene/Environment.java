package driftengine.scene;

import driftengine.math.Vec3;

/**
 * 빛과 대기 (프레임마다 셰이더에 넘긴다).
 *
 * @param sunDirection 해가 있는 방향 (길이 1, 땅에서 해 쪽)
 * @param sunColor     햇빛 색 × 세기 (선형 RGB)
 * @param skyIntensity 하늘빛(주변광) 세기
 * @param fogDensity   공기 원근감 (m⁻¹). 멀수록 하늘색으로 흐려진다
 * @param exposure     노출 (톤매핑 전 곱)
 * @param time         초 (구름 흐름 등)
 * @param contactShadow 차 밑 접촉 그림자: 차 위치·앞 방향·반 크기 (없으면 null)
 */
public record Environment(Vec3 sunDirection, Vec3 sunColor, float skyIntensity, float fogDensity, float exposure,
                          float time, ContactShadow contactShadow) {

    /** 차 밑 바닥을 어둡게 (center = 차 밑 땅 높이). 그림자 맵 전 임시, 그림자 맵이 생겨도 접촉 부분 보강용으로 남긴다 */
    public record ContactShadow(Vec3 center, Vec3 forward, float halfWidth, float halfLength) {
    }

    /** 늦은 오후 해 (따뜻한 색, 낮은 각도라 차 옆면 하이라이트가 잘 보임) */
    public static Environment afternoon(float time) {
        Vec3 sun = new Vec3(-0.55f, 0.42f, -0.72f).normalize();
        return new Environment(sun, new Vec3(1.0f, 0.86f, 0.70f).scale(3.2f), 1.0f, 0.0012f, 1.0f, time, null);
    }

    public Environment withContactShadow(ContactShadow c) {
        return new Environment(sunDirection, sunColor, skyIntensity, fogDensity, exposure, time, c);
    }
}
