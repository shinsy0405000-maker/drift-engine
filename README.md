# Drift Engine

자체 제작 엔진으로 만드는 오픈월드 자동차 드리프트 게임입니다.
Java 25 + LWJGL 3 + Vulkan, 물리는 Jolt Physics (jolt-jni), 업스케일은 NVIDIA DLSS (Streamline, 선택).

> 등장하는 차·브랜드·장소는 모두 가상입니다. 차 성능 숫자만 실제 차급을 참고했고, 배기음은 합성합니다.

## 무엇이 들어 있나

- **렌더링**: Vulkan 1.3 동적 렌더링, PBR 재질, 단계 그림자(4단계), SSAO, 하늘·구름·공기 원근감, 블룸, 모션 블러, TAA 대신 DLSS (없으면 일반 업스케일)
- **차 물리**: 광선 서스펜션, 결합 슬립 타이어, 엔진·터보·슈퍼차저·니트로, 변속기, LSD, 트랙션 컨트롤, 드리프트 보조, 캠버·휠 오프셋
- **차 24대**: JDM·KDM 드리프트 차, 서킷 차·하이퍼카 (Blender 스크립트로 만든 모델)
- **맵 4곳**: 연습장(샵), 용인 서킷(3.6km), 태백 고갯길(3.2km 다운힐), 도시(6×6 블록 + 드리프트 광장)
- **게임**: 클래스(1~CS+), 딜러쉽, 클럽 이벤트(드리프트 점수·랩타임), 튜닝샵(7분류), 스타일링샵(바디킷·페인트·휠)
- **소리**: 엔진 배기음 합성 (실린더 수·점화 간격·로터리·전기), 터보 휘슬·블로우오프, 타이어

## 실행

필요한 것: Windows 10/11, Vulkan 1.3 GPU, [Vulkan SDK](https://vulkan.lunarg.com/) (셰이더 컴파일용 `glslc`), JDK 25 (Gradle 이 맞는 JDK 를 찾아 씀)

```
gradlew run
```

- DLSS 는 선택입니다. NVIDIA 바이너리는 저장소에 넣지 않습니다 (라이선스·용량). 받는 법은 `docs/DLSS.md` 의 "브리지 빌드와 실행"
  - 없으면 자동으로 일반 업스케일로 실행됩니다
- 로그: `logs/drift-latest.log`, 저장: `save/`, 설정: `settings.properties` (모두 실행하면 생김)

## 조작 (키보드)

| 키 | 동작 |
|---|---|
| W / S | 가속 / 브레이크 (멈춘 뒤 계속 누르면 후진) |
| A / D | 조향 |
| Space | 핸드브레이크 |
| Shift | 니트로 |
| E / Q, G | 수동 변속, 자동/수동 |
| X | 트랙션 컨트롤 (스포츠 → 전체 → 끔) |
| T | 차 세우기 (길에서 벗어나면 도로로) |
| N | 내 다음 차 |
| C | 자유 카메라 |
| Tab | 메뉴 (차고, 딜러쉽, 클럽, 지역 이동, 샵) |
| Enter | 샵 입구 원 안에서 들어가기 |

## 시험

GPU 없이 도는 자체 테스트 (물리·튜닝·맵·UI 배치·소리 등):

```
gradlew selfTest
```

## 폴더

- `src/main/java/driftengine` — 엔진·게임 (render, vulkan, physics, vehicle, world, world/map, game, ui, audio, upscale)
- `src/main/shaders` — GLSL 셰이더
- `src/selftest` — 자체 테스트, 컴퓨터 운전자(랩 목표 시간 맞추기), 맵 미리보기
- `cars` — 차 파일(`*.properties`) + 모델(`models/*.cmesh.gz`)
- `tools/carmodels` — 차 모델을 만드는 Blender(bpy) 스크립트
- `native/streamline-bridge` — DLSS(Streamline) 용 작은 C++ 브리지
- `docs` — 단계별 설계·확인 기록

## 사용한 라이브러리

- [LWJGL 3](https://www.lwjgl.org/) (BSD-3)
- [jolt-jni](https://github.com/stephengold/jolt-jni) / [Jolt Physics](https://github.com/jrouwe/JoltPhysics) (MIT)
- [NVIDIA Streamline / DLSS](https://github.com/NVIDIAGameWorks/Streamline) (선택, 저장소에 포함하지 않음)

## 저작권

© 2026 이 저장소의 소유자. 모든 권리 보유 (All rights reserved).
작업물을 보여 주기 위한 저장소입니다. 코드·차 모델·자료를 허락 없이 복사·수정·배포·사용할 수 없습니다.
(위 "사용한 라이브러리"는 각자의 라이선스를 따릅니다.)
