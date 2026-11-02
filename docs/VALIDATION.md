# 기술 검증 결과 (2026-10-27)

자체 엔진(Java + LWJGL + Vulkan)에서 DLSS(NVIDIA Streamline)를 실제로 쓸 수 있는지 확인한 결과다.
"확인됨"은 이 PC에서 실행해서 로그나 화면으로 본 것만 적었다. 짐작이나 문서만으로 아는 것은 "미검증"에 둔다.

## 1. 결론

- DLSS 지원: 확인됨. 공식 API slIsFeatureSupported(kFeatureDLSS) 가 GPU 디바이스 생성 뒤에 "지원됨"을 돌려줬다
- DLSS 실제 동작: 확인됨. slEvaluateFeature 가 한 번 실행에서 16146번 연속 성공했고, 화면이 정상으로 보였고, Vulkan 검증 오류 0건
- 기본 렌더링 유지: 확인됨. DLSS 를 못 쓰는 상황(브리지 없음, NGX 초기화 실패)에서 경고만 남기고 Native/Bilinear 로 계속 그렸다
- Java 비중: 약 92% (Java 7710줄, C++ 644줄, 빈 줄 제외 · 2026-10-27 기준). 정확한 값은 `gradlew languageStats`
- 성능: 무거운 장면(인공 부하 8000)에서 DLSS 균형 모드가 Native 보다 약 1.9배 빠르다. 가벼운 장면에서는 DLSS 자체 비용(약 1ms) 때문에 오히려 느리다

## 2. 시험 환경

- Windows 11, NVIDIA GeForce RTX 3060 Laptop GPU, 드라이버 610.88 (내장 Intel UHD 도 있음, 엔진은 RTX 를 고름)
- JDK 25 (Gradle 툴체인), Gradle 9.8.2, LWJGL 3.4.3, Vulkan 로더 1.4.363, Vulkan SDK 1.4.363 (검증 레이어)
- Streamline SDK 2.14.16 (GitHub 릴리스 zip, SHA-256 확인). 실행 중에는 드라이버가 받아 둔 최신 플러그인(OTA)이 같은 버전으로 로드됐다
- C++ 브리지: MinGW-w64 로 빌드 (Visual Studio 없이). NVIDIA 공식 빌드 도구(MSVC)와 구조체 배치가 같은지는 clang 으로 비교해서 확인

## 3. 단계별로 확인한 것

- 1단계: 창, Vulkan 인스턴스, GPU 선택(외장 GPU 우선), 디바이스, 검증 레이어 연결
- 2단계: 삼각형 그리기, 스왑체인 재생성(창 크기 변경)
- 3단계: 3D 장면, 카메라, 고정 틱 게임 루프, 키 입력 매핑, 설정 파일
- 4a: Vulkan 로더를 sl.interposer.dll 로 바꿔도 LWJGL·GLFW 가 정상 동작
- 4b: 브리지 로드 → 서명 확인 → slInit → 공식 지원 판정
- 4c: 낮은 해상도 오프스크린 렌더링 + 선형 확대(R 키로 100/75/50%)
- 4d: 모션 벡터(M 키로 보기), Halton 지터(J 키)
- 4e: slDLSSGetOptimalSettings → slDLSSSetOptions → slSetConstants → slEvaluateFeature → 스왑체인 복사. R 키로 품질/균형/성능/Native 전환
- 설정: DLSS 기본 켜짐(render.dlss, render.dlssMode), --no-dlss, 예전 설정 파일에 새 항목 자동 추가
- 성능 측정: --benchmark(자동 단계 측정), GPU 타임스탬프로 장면/업스케일 시간 분리, --gpu-load, 수직동기 끄기
- 자체 테스트 91개 통과 (`gradlew selfTest`, GPU 없이 도는 계산 부분)

## 4. 성능 측정 결과

조건: 출력 1280x720, 수직동기 끔(IMMEDIATE), 검증 레이어 끔, 카메라 고정, 단계마다 2초 준비 후 5초 측정.
"GPU" 는 타임스탬프로 잰 GPU 작업 시간 = 장면 + 업스케일(스왑체인 복사 포함).

인공 부하 없음 (데모 장면 그대로, 측정 중 창 크기가 바뀌어 참고용):
- Native 100%: 약 2100 FPS, GPU 0.24 ms
- DLSS 는 업스케일 비용만 1.3~3.6 ms 라 Native 보다 느림 (250~560 FPS)

인공 부하 2000 (손익분기점 근처):
- Native 100%: 374 FPS, GPU 2.35 ms
- DLSS 품질 (853x480): 370 FPS, GPU 장면 1.55 + DLSS 0.91 = 2.46 ms
- DLSS 균형 (742x418): 377 FPS, GPU 1.38 + 0.91 = 2.30 ms
- DLSS 성능 (640x360): 346 FPS, GPU 1.25 + 1.63 = 2.88 ms
- Bilinear 50% (640x360, 비교 기준): 680 FPS, GPU 1.18 ms

인공 부하 8000 (무거운 셰이딩 흉내):
- Native 100%: 119 FPS, GPU 8.22 ms
- DLSS 품질: 193 FPS, GPU 4.02 + 1.00 = 5.02 ms (Native 대비 약 1.6배)
- DLSS 균형: 230 FPS, GPU 3.29 + 0.92 = 4.21 ms (약 1.9배)
- DLSS 성능: 231 FPS, GPU 2.67 + 1.64 = 4.31 ms (약 1.9배)
- Bilinear 50%: 384 FPS, GPU 2.38 ms (가장 빠르지만 화질은 가장 낮음)

해석:
- DLSS 자체 비용은 720p 출력에서 품질·균형 약 0.9~1.0 ms, 성능 약 1.6 ms 로 세 번의 측정 모두 비슷했다
- 장면 비용 중 해상도와 상관없는 고정 부분이 약 0.8 ms 있어, 렌더 해상도를 낮춰도 그만큼은 줄지 않는다
- 따라서 DLSS 는 "해상도에 비례하는 GPU 비용"이 DLSS 비용보다 충분히 클 때 이득이다. 실제 게임 장면(조명, 그림자, 후처리)은 이쪽에 해당할 가능성이 높지만, 이 엔진의 실제 장면으로는 아직 확인하지 않았다
- 성능 모드의 DLSS 비용이 더 큰 이유는 미확인. SDK 헤더에 성능 모드 기본 프리셋은 M, 품질·균형은 K 로 적혀 있어 프리셋 차이로 추정한다

## 5. 진행 중 발견한 문제와 해결 (같은 실수를 반복하지 않기 위한 기록)

- 색 첨부 2개의 블렌드 설정이 다르면 independentBlend 기능이 필요하다 → 두 첨부를 같은 설정으로
- NVIDIA 드라이버의 디바이스 확장은 200개가 넘어 LWJGL 스택(64KB)에 담기지 않는다 → 힙 할당
- interposer 가 DLSS 용 확장을 넣으면서 VK_EXT_buffer_device_address 와 1.2 bufferDeviceAddress 가 충돌 → VK_KHR_buffer_device_address 를 직접 넣으면 interposer 가 EXT 를 뺀다 (SDK 소스 확인)
- Streamline 내부가 privateData 기능 확인 없이 vkCreatePrivateDataSlot 호출 → privateData 켬
- 지원 판정을 디바이스 생성 전에 하면 "지원됨"이 나오지만 실제로는 NGX 가 꺼져 있을 수 있다 → 판정은 디바이스 생성 뒤에
- NVIDIA 발급 ID 가 없는 자체 엔진은 GUID 형식 projectId 가 필수 (DLSS 가이드 5.2.1). 없으면 NGX 초기화 실패
- 깊이 버퍼를 태그할 때 SubresourceRange(DEPTH)를 붙이지 않으면 sl.dlss 가 COLOR aspect 로 가정한다
- NGX 가 DLSS 출력 이미지를 vkCmdClearColorImage 로 지운다 → 출력 이미지에 TRANSFER_DST 용도 필요
- 한 번도 실행하지 않은 DLSS 뷰포트를 slFreeResources 하면 eErrorInvalidParameter → 실행한 적 있을 때만 해제
- PC 로 파일을 옮길 때 같은 임시 경로를 재사용하면 이전 내용이 쓰일 수 있다 → 매번 새 경로, 옮긴 뒤 내용 비교

## 6. 미검증 · 남은 위험

- MSVC 로 빌드한 브리지: 아직 빌드·실행하지 않음 (구조체 배치는 비교로 확인)
- 다른 GPU: AMD, Intel, 다른 세대 NVIDIA 에서의 실행. DLSS 를 못 쓰는 GPU 에서의 자동 대체는 같은 PC 에서 실패 상황을 만들어서만 확인
- 지터 부호: 화면이 안정적으로 보여 맞는 것으로 판단했지만 DLSS 디버그 오버레이 등으로 정밀 확인은 안 함
- DLSS 화질: 모드별 화질을 체계적으로 비교하지 않음 (눈으로 "잘 된다"까지)
- 출력 해상도 1080p 이상, 창 크기 변경 중의 DLSS 동작은 짧게만 봄
- 성능 모드 비용이 큰 원인 (프리셋 추정)
- Reflex(NvLowLatencyVk.dll)는 넣지 않음. 매 실행 "NOT correctly signed" 메시지는 이 파일이 없어서 나는 것
- 배포: Streamline·DLSS 재배포 라이선스(license.txt, nvngx_dlss.license.txt) 원문 검토 전
- 노트북 GPU 전환(Optimus) 환경에서 interposer 와 GPU 선택이 항상 맞는지
- 콘솔 한글 깨짐: 터미널 코드 페이지 문제(chcp 65001 로 해결), 엔진 기능과는 무관

## 7. 다음에 할 수 있는 것

- 실제 게임에 가까운 장면(조명, 그림자, 후처리)으로 DLSS 이득 다시 측정
- 프리셋을 고정(K)해서 성능 모드 비용 원인 확인
- MSVC 빌드로 교차 확인, 다른 GPU 에서 실행
- 기술 검증을 마치고 차량 물리 / 오픈월드 단계 계획
