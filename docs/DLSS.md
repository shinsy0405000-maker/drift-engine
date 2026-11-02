# DLSS 통합 설계 (Stage 4 준비)

작성: 2026-10-26 · 기준: NVIDIA Streamline GitHub (최신 릴리스 2.14.16), LWJGL 3.4.3

## 1. 현재 상태

MinGW 빌드 명령 (클라우드/리눅스에서): `x86_64-w64-mingw32-g++ -std=c++17 -O2 -shared -static -static-libgcc -static-libstdc++ -DDSL_BUILDING -DUNICODE -D_UNICODE -DNOMINMAX -Inative/streamline-bridge/include -I<sdk>/include native/streamline-bridge/src/drift_sl_bridge.cpp -o drift_sl_bridge.dll -lwintrust -s`


- DLSS 지원 여부: **확인** (2026-10-27, slIsFeatureSupported(kFeatureDLSS) = eOk. RTX 3060 Laptop, 드라이버 610.88, Streamline 2.14.16, MinGW 빌드 브리지). interposer 경유 실행에서 검증 오류 0건, 143 FPS
- interposer 경유 시 검증 오류 2건 해결: DLSS 플러그인이 요구하는 VK_EXT_buffer_device_address 와 1.2 bufferDeviceAddress 충돌(→ VK_KHR_buffer_device_address 를 직접 넣으면 interposer 가 EXT 를 제거), sl.chi 의 vkCreatePrivateDataSlot(→ 1.3 privateData 켬). 근거: SDK 소스 sl.interposer/vulkan/wrapper.cpp, sl.chi/vulkan.cpp
- DLSS 실제 동작: **미확인** (4e 실행 경로 구현 완료, PC 에서 첫 slEvaluateFeature 결과 대기. 실패 시 선형 복사로 자동 대체)
- 4e 구현 (2026-10-27): DlssUpscaler — slDLSSGetOptimalSettings 로 렌더 해상도, slDLSSSetOptions(HDR, 자동 노출), slGetNewFrameToken → slSetConstants → slEvaluateFeature(로컬 태그 4개, eValidUntilEvaluate) → 출력(R16G16B16A16_SFLOAT storage) → 스왑체인 복사. R 키: DLSS 품질 → 균형 → 성능 → Native
- 4e 값 규약과 근거: mvecScale=(1,1) (sl.dlss 가 렌더 해상도를 곱함, dlssEntry.cpp), 지터=렌더 픽셀 단위·모션 벡터와 같은 좌표계(가이드 3.7.3), 행렬=엔진 열 우선 배열 그대로(Streamline 은 행 벡터 규약, sl_matrix_helpers.h), 깊이에는 SubresourceRange(DEPTH) 필수(sl.dlss 기본값이 COLOR aspect), 레이아웃은 SL 이 전환 후 되돌림(transitionResources + revTransitions)
- 4e 첫 실행 결과(2026-10-27): slIsFeatureSupported = eErrorFeatureNotSupported(32). 원인(sl.log): "Please provide correct application id" → NGX 초기화 안 됨. 4b 의 "지원됨"은 디바이스 생성 전(NGX 초기화 전) 판정이었다. 자체 엔진은 GUID 형식 projectId + 엔진 버전이 필수(DLSS 가이드 5.2.1, sl.common/commonEntry.cpp) → PROJECT_ID 추가. 지원 판정은 이제 디바이스 생성 뒤에 한다
- 4e 두 번째 실행(2026-10-27 10:24): projectId 적용 후 slIsFeatureSupported = 지원됨(디바이스 생성 후 판정), slEvaluateFeature 16146회 성공 → **DLSS 실제 동작: 예** (품질 853x480 → 1280x720, 지터 18단계). 검증 오류 10건: NGX 가 출력 이미지에 vkCmdClearColorImage → 출력 용도에 TRANSFER_DST 추가
- 4e 미검증: 지터 부호(화면으로 확인: 정지 화면이 흔들리거나 흐리면 부호 반대), DLSS 결과 화질, 검증 레이어 경고 여부
- 구현됨(4b 뼈대): C++ 브리지 `native/streamline-bridge`, Java 바인딩 `StreamlineBridge`(FFM), `DlssSession`
- 검증된 것: 브리지 C++ 문법(MinGW + 공식 선언을 흉내낸 스텁), 내보내기 함수 이름, Java ↔ C ABI(가짜 브리지로 문자열·구조체·반환값 왕복), 실패 시 Native 자동 전환
- 검증된 것(2026-10-27): 실제 SDK 2.14.16 zip SHA-256 이 릴리스 페이지 값과 일치, 실제 헤더로 브리지 컴파일 성공(MinGW), 실제 sl.interposer.lib 와 링크 시 slInit/slShutdown/slIsFeatureSupported/slGetFeatureRequirements 심볼 해결
- 변경(2026-10-27): 브리지가 sl.interposer.lib 를 링크하지 않고 로드한 sl.interposer.dll 에서 GetProcAddress 로 sl* 함수를 찾는다 (지연 로드 불필요, MSVC/MinGW 공용 소스). Visual Studio 없이 MinGW-w64 로 빌드한 drift_sl_bridge.dll 을 사용
- 검증된 것(2026-10-27): sl.interposer.dll 이 slInit/slShutdown/slIsFeatureSupported/slGetFeatureRequirements 를 장식 없는 이름으로 내보냄. sl::Preferences(144B)·sl::AdapterInfo(56B)·sl::FeatureRequirements(184B)·sl::BaseStructure 의 크기와 모든 필드 위치가 MSVC ABI 와 MinGW ABI 에서 같음 (clang 레이아웃 출력 비교, 브리지에 static_assert 로 고정). 브리지 DLL 의 의존성은 KERNEL32·msvcrt·WINTRUST 뿐
- 검증 안 된 것: MSVC 빌드, 실제 PC 에서 브리지 로드와 slInit/slIsFeatureSupported 결과, interposer 경유 렌더링

## 1-1. 브리지 빌드와 실행 (PC에서)

1. GitHub Releases 에서 `streamline-sdk-v2.14.16.zip` 을 Downloads 폴더에 받는다 (압축은 풀지 않아도 됨)
2. `gradlew setupStreamline` → SHA-256 확인 후 배포용 DLL 4개를 `natives\streamline\`, 헤더와 lib 를 `native\external\streamline-sdk\` 로 꺼냄
3. 브리지 DLL: MinGW-w64 로 빌드한 `natives\streamline\drift_sl_bridge.dll` 을 사용 (Visual Studio 불필요).
   MSVC 로 직접 빌드하려면 Visual Studio Build Tools 2022 "C++를 사용한 데스크톱 개발" 설치 후 `gradlew buildStreamlineBridge`
4. `gradlew run` → DLSS 가 기본으로 켜진다 (settings.properties 의 render.dlss=true, render.dlssMode=quality). 끄려면 `--no-dlss` 또는 render.dlss=false. 로그의 `[공식] slIsFeatureSupported(DLSS)` 줄이 판정 결과

## 1-2. 성능 측정 (2026-10-27 추가)

- `gradlew run --args="--benchmark"`: Native 100% → DLSS 품질 → 균형 → 성능 → Bilinear 50% 를 단계마다 준비 2초 + 측정 5초, 끝나면 요약 로그 후 종료. 수직동기·검증 레이어 자동으로 끔, 카메라 고정
- `--gpu-load=N`: 픽셀마다 N 회 추가 계산(scene.frag). 데모 장면은 너무 가벼워 DLSS 자체 비용이 더 크게 보이므로, 무거운 셰이딩을 흉내 내 렌더 해상도를 낮추는 효과를 확인하는 용도
- GPU 시간: 타임스탬프 쿼리(GpuTimer)로 "장면"(프레임 시작~장면 끝)과 "업스케일"(장면 끝~스왑체인 복사 끝)을 따로 잰다. 창 제목과 종료 시 요약에 표시
- `render.vsync` / `--no-vsync`: 수직동기 끄기 (IMMEDIATE → MAILBOX → FIFO 중 지원되는 것)
- 측정 결과와 해석: docs/VALIDATION.md 4절 (부하 8000 에서 DLSS 균형 약 1.9배, 가벼운 장면에서는 DLSS 가 더 느림)

## 2. 공식 문서로 확인한 사실

- Streamline 지원 환경: Windows 10 20H1 이상, Vulkan 1.2 이상, NVIDIA 드라이버 512.15 이상.
- 배포 DLL: 필수 `sl.interposer.dll`, `sl.common.dll` / DLSS용 `sl.dlss.dll`, `nvngx_dlss.dll`.
  2.7.32 이후 바이너리는 저장소가 아니라 GitHub 릴리스 zip으로 받는다.
- Vulkan에서는 `vulkan-1.dll` 대신 `sl.interposer.dll`을 로드하고, 거기서 `vkGetInstanceProcAddr`를 얻는다.
- `slInit`은 Vulkan 함수를 하나라도 부르기 전에 호출. `Preferences.renderAPI`의 기본값은 D3D12라서 Vulkan으로 바꿔야 한다.
- 인스턴스/디바이스를 직접 만들면 `slGetFeatureRequirements`로 받은 확장·기능·큐를 직접 켜고 `slSetVulkanInfo`를 호출해야 한다.
- `applicationId`가 없으면 엔진 종류와 버전을 지정해야 한다(기본 `EngineType::eCustom`).
- 시작 시 `sl.interposer.dll`의 디지털 서명을 확인하고, DLL 경로는 절대 경로로 넘겨야 한다.
- API는 C++ 전용이다: `sl::` 네임스페이스, GUID 기반 `BaseStructure` 구조체, 참조(`&`) 매개변수.

## 3. 구조

```
Java (엔진, 렌더링)  ──FFM(java.lang.foreign)──▶  drift_sl_bridge.dll (C++, C ABI)  ──▶  Streamline
        │                                                                            │
        └── LWJGL VK / GLFW ── vkGetInstanceProcAddr ◀── sl.interposer.dll ◀──────────┘
```

- C++ 브리지가 필요한 이유: Streamline 헤더가 C++ 전용이라 Java에서 구조체 레이아웃을 직접 맞추면 버전마다 깨질 위험이 크다.
  브리지는 `extern "C"` 함수만 내보내고, 인자는 정수·포인터·핸들만 사용한다.
- Java ↔ 브리지 호출은 JDK 25 표준 FFM을 사용한다(추가 의존성 없음).
- Vulkan 로더 교체는 Java에서 이미 가능하다(`VulkanLoader`, LWJGL `VK.create(path)` + `glfwInitVulkanLoader`). 실제 동작은 미검증.

## 4. 브리지 C ABI (구현: `native/streamline-bridge/include/drift_sl_bridge.h`)

| 함수 | 하는 일 | 상태 |
|---|---|---|
| `dsl_abi_version` | 헤더/Java 버전 일치 확인 | 구현 |
| `dsl_verify_signature(path)` | WinVerifyTrust로 서명 확인 | 구현 |
| `dsl_load_interposer(path, requireSignature)` | 서명 확인 후 절대 경로로 로드 (브리지는 지연 로드로 링크) | 구현 |
| `dsl_init(pluginDir, logDir, verbose, engineVersion, projectId)` | `slInit` (renderAPI=Vulkan, 로드 기능={DLSS}, engine=eCustom) | 구현 |
| `dsl_is_dlss_supported(vkPhysicalDevice)` | `slIsFeatureSupported(kFeatureDLSS)` → **지원 여부의 유일한 판정 근거** | 구현 |
| `dsl_get_dlss_requirements(out)` | `slGetFeatureRequirements` → 확장, 1.2/1.3 기능, 추가 큐 수 | 구현 (아직 인스턴스/디바이스 생성에 반영 안 함) |
| `dsl_shutdown` | `slShutdown` (Vulkan 객체 파괴 전에) | 구현 |
| `dsl_set_vulkan_info` | `slSetVulkanInfo` | 예정 (선언 헤더를 SDK에서 확인 후) |
| `dsl_get_optimal_settings` | `slDLSSGetOptimalSettings` | 예정 |
| `dsl_evaluate` | 태그 + 상수 + `slEvaluateFeature` | 예정 |

반환값: 0 = 성공, 음수 = 브리지 오류(`DSL_ERR_*`), 양수 = `sl::Result` 값. Java의 `SlResult`가 이름으로 바꿔 로그에 남긴다.
`RenderAPI::eVulkan` 이름은 공식 문서가 아닌 보조 자료(DeepWiki)로만 확인했으므로 SDK 헤더로 최종 확인 필요.

## 5. 초기화 순서 변경 (DLSS 켤 때만)

1. 브리지 로드 → `dsl_init` (Vulkan 호출 전)
2. `VulkanLoader.configure(sl.interposer.dll 절대 경로)` → GLFW 초기화
3. `dsl_get_vk_requirements` 결과를 인스턴스/디바이스 생성에 합침 (현재 `VulkanInstance`, `VulkanDevice` 확장 필요)
4. 디바이스 생성 후 `dsl_set_vulkan_info`
5. GPU 선택 후 `dsl_is_dlss_supported` → 실패하면 DLSS만 끄고 Native 업스케일러로 계속 실행
   - 실행 중에 로더를 vulkan-1로 되돌리지 않는다. 그래서 Java 사전 점검(`DlssPrecheck`)을 통과한 경우에만 이 경로에 들어간다.
   - interposer가 DLSS 없이도 일반 렌더링을 그대로 통과시키는지는 4a 단계에서 직접 확인한다.
6. 매 프레임 `dsl_evaluate`, 종료 시 Vulkan 객체 파괴 전에 `dsl_shutdown`

## 6. DLSS가 요구하는 입력과 현재 엔진에 없는 것

| 입력 | 현재 엔진 |
|---|---|
| 저해상도 color (렌더 해상도) | 있음 – RenderTargets (R16G16B16A16_SFLOAT) |
| 출력 color (출력 해상도, storage 가능 이미지) | 없음 |
| depth (depth 전용 aspect의 이미지 뷰) | 있음 – 렌더 해상도 DepthBuffer (storeOp 를 STORE 로 바꿔야 함) |
| motion vectors | 있음 – R16G16_SFLOAT, "이전 위치 − 현재 위치", 화면 비율 단위 (MotionVectors.java). Streamline mvecScale 값은 4e에서 문서로 확인 |
| 지터 오프셋(픽셀 단위), 카메라 행렬(지터 제외, row-major) | 있음 – J 키로 켜는 Halton 지터(렌더 해상도 픽셀 단위), 지터 없는 행렬 별도 보관. 지터 부호 규칙은 4e에서 확인 |
| exposure (선택) | 없어도 됨 (auto exposure) |

정지된 삼각형만으로는 DLSS 품질을 검증할 수 없다. 최소 검증용으로 "움직이는 3D 물체 + 카메라 + depth + motion vector" 장면이 필요하다.

## 7. 단계 계획

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| 4a | Streamline 릴리스 zip을 `natives/streamline/`에 배치, `--vulkan-loader`로 interposer 경유 실행 | 삼각형이 그대로 보이고 검증 오류 0 |
| 4b | C++ 브리지 최소판 + CMake (**뼈대 작성 완료, 빌드 전**) | 로그에 "DLSS 지원 확인/안 됨"이 공식 API 결과로 출력 |
| 4c | 오프스크린 렌더 타겟 + depth + 단순 업스케일(blit) (**구현됨: R 키로 100/75/50% 전환**) | Native/blit 전환이 동작, 해상도 비율 변경 가능 |
| 4d | 움직이는 장면 + 카메라 + motion vector + 지터 (**구현됨: M 키 모션 벡터 보기, J 키 지터**) | 검증 오류 0 |
| 4e | `dsl_dlss_evaluate` 연결, DLSS 모드 선택 (**구현됨, PC 확인 대기**) | 화면 품질/FPS 비교, 실패 시 자동 선형 복사 전환 |

## 8. 미확인·위험

- Streamline 비수동(자동) 후킹 모드에서 LWJGL·GLFW가 interposer의 함수 포인터만으로 정상 동작하는지
- 노트북(Optimus)에서 interposer 사용 시 GPU 선택 문제 여부
- `slSetVulkanInfo` 구조체 정확한 정의 위치(헤더) – SDK 받은 뒤 확인
- 배포 라이선스: Streamline `license.txt`와 DLSS 관련 조건은 배포 전에 원문 확인 필요
- MinGW 로 빌드한 브리지는 NVIDIA 가 안내하는 빌드 도구(MSVC)가 아님. 구조체 배치는 비교로 확인했지만, 이상 동작 시 MSVC 빌드로 교차 확인
- `slGetFeatureRequirements`가 돌려준 문자열 포인터의 수명 (`dsl_shutdown` 전까지 유효하다고 가정)

## 출처

- https://developer.nvidia.com/rtx/streamline/get-started
- https://github.com/NVIDIAGameWorks/Streamline (README, releases)
- https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuide.md
- https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuideDLSS.md
- https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuideManualHooking.md
- https://github.com/NVIDIAGameWorks/Streamline/blob/main/include/sl_core_api.h, sl_core_types.h, sl_dlss.h
- https://javadoc.lwjgl.org/org/lwjgl/vulkan/VK.html
