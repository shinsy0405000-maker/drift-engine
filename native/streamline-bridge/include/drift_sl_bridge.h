/*
 * drift_sl_bridge: Java(FFM) 와 NVIDIA Streamline(C++ 전용 API) 사이의 얇은 C 인터페이스.
 *
 * 원칙
 *  - 내보내는 함수는 모두 extern "C", 인자는 정수/포인터/문자열만 사용한다.
 *  - Streamline 구조체(sl::Preferences 등)는 이 DLL 안에서만 다룬다. Java 는 레이아웃을 몰라도 된다.
 *  - 반환값: DSL_OK(0) 또는 음수 오류 코드. Streamline 호출 실패는 sl::Result 값을 그대로
 *    양수로 돌려준다(0 = sl::Result::eOk). 자세한 내용은 dsl_last_error() 문자열로 확인한다.
 *
 * 상태: Stage 4b. Streamline SDK 2.14.16 헤더로 MinGW-w64 빌드 확인. 실제 PC 에서의 실행은 아직 확인 전.
 */
#ifndef DRIFT_SL_BRIDGE_H
#define DRIFT_SL_BRIDGE_H

#include <stdint.h>
#include <wchar.h>

#ifdef __cplusplus
#define DSL_EXTERN extern "C"
#else
#define DSL_EXTERN
#endif

#ifdef DSL_BUILDING
#define DSL_API DSL_EXTERN __declspec(dllexport)
#else
#define DSL_API DSL_EXTERN __declspec(dllimport)
#endif

/* 이 헤더의 ABI 버전. 함수나 구조체가 바뀌면 올린다. Java 쪽 StreamlineBridge.EXPECTED_ABI 와 같아야 한다. */
#define DSL_ABI_VERSION 2

/* 브리지 자체 오류 코드 (음수) */
#define DSL_OK                      0
#define DSL_ERR_INVALID_ARGUMENT  (-1)
#define DSL_ERR_NOT_LOADED        (-2) /* dsl_load_interposer 를 먼저 호출해야 함 */
#define DSL_ERR_NOT_INITIALIZED   (-3) /* dsl_init 을 먼저 호출해야 함 */
#define DSL_ERR_ALREADY           (-4) /* 이미 초기화됨 */
#define DSL_ERR_SIGNATURE         (-5) /* 디지털 서명 검증 실패 */
#define DSL_ERR_LOAD_FAILED       (-6) /* LoadLibrary 실패 */
#define DSL_ERR_EXCEPTION         (-7) /* C++ 예외 */

/*
 * DLSS 가 요구하는 Vulkan 설정 (slGetFeatureRequirements 결과).
 * 정수 8개 → 포인터 4개 순서로 배치해 패딩이 없다 (x64 에서 64바이트).
 * 문자열 포인터는 Streamline 이 소유하며 dsl_shutdown 전까지 유효하다고 가정한다(미검증).
 */
typedef struct DslVkRequirements {
    uint32_t numInstanceExtensions;
    uint32_t numDeviceExtensions;
    uint32_t numFeatures12;
    uint32_t numFeatures13;
    uint32_t numGraphicsQueues;
    uint32_t numComputeQueues;
    uint32_t numOpticalFlowQueues;
    uint32_t reserved;
    const char* const* instanceExtensions;
    const char* const* deviceExtensions;
    const char* const* features12;
    const char* const* features13;
} DslVkRequirements;

/* 브리지 ABI 버전 (DSL_ABI_VERSION) */
DSL_API int32_t dsl_abi_version(void);

/* 마지막 오류 설명 (UTF-8). 다음 호출 전까지 유효. 오류가 없으면 빈 문자열. */
DSL_API const char* dsl_last_error(void);

/*
 * 파일의 Authenticode 서명을 검증한다 (WinVerifyTrust).
 * 반환: 1 = 신뢰됨, 0 = 신뢰되지 않음/서명 없음, 음수 = 오류.
 */
DSL_API int32_t dsl_verify_signature(const wchar_t* absolutePath);

/*
 * sl.interposer.dll 을 절대 경로로 로드한다. requireSignature != 0 이면 먼저 서명을 검증한다.
 * 로드한 모듈에서 slInit 등 Streamline 함수를 GetProcAddress 로 찾는다.
 * 이 함수가 성공해야 이후 dsl_init 등을 호출할 수 있다.
 */
DSL_API int32_t dsl_load_interposer(const wchar_t* absolutePath, int32_t requireSignature);

/*
 * slInit (renderAPI = Vulkan, 로드할 기능 = DLSS).
 * Vulkan 함수를 하나라도 호출하기 전에 불러야 한다 (Streamline 문서).
 *  pluginDir      : sl.*.dll 이 있는 폴더 (절대 경로)
 *  logDir         : 로그 폴더 (NULL 가능)
 *  verboseLog     : 0 = 기본, 1 = 자세히
 *  engineVersion  : 엔진 버전 문자열 (applicationId 가 없을 때 필요)
 *  projectId      : 프로젝트 GUID 문자열 (NULL 가능)
 */
DSL_API int32_t dsl_init(const wchar_t* pluginDir, const wchar_t* logDir, int32_t verboseLog,
                         const char* engineVersion, const char* projectId);

/*
 * slIsFeatureSupported(kFeatureDLSS) 를 해당 VkPhysicalDevice 로 호출한다.
 * 반환: DSL_OK = 지원됨, 양수 = sl::Result (미지원 사유), 음수 = 브리지 오류.
 */
DSL_API int32_t dsl_is_dlss_supported(void* vkPhysicalDevice);

/* slGetFeatureRequirements(kFeatureDLSS) 결과를 out 에 채운다. dsl_init 이후 호출. */
DSL_API int32_t dsl_get_dlss_requirements(DslVkRequirements* out);

/* ------------------------------------------------------------------ DLSS 실행 (ABI 2, Stage 4e) */

/*
 * Vulkan 이미지 하나 (DLSS 입력/출력). x64 에서 48바이트, 패딩 없음.
 *  layout     : 호출 시점의 실제 VkImageLayout. Streamline 은 이 값을 기준으로 전환하고, 끝나면 되돌린다.
 *  aspectMask : 색 이미지는 VK_IMAGE_ASPECT_COLOR_BIT, 깊이는 VK_IMAGE_ASPECT_DEPTH_BIT
 */
typedef struct DslImage {
    uint64_t image;   /* VkImage */
    uint64_t view;    /* VkImageView */
    uint64_t memory;  /* VkDeviceMemory */
    uint32_t layout;
    uint32_t format;  /* VkFormat */
    uint32_t width;
    uint32_t height;
    uint32_t usage;   /* VkImageUsageFlags */
    uint32_t aspectMask;
} DslImage;

/*
 * sl::Constants 의 C 판. 행렬은 Streamline 규약(행 우선, 행 벡터 v*M) 그대로 16개씩.
 * 열 벡터(M*v) 규약의 열 우선 배열은 그대로 넣으면 같은 값이 된다 (전치 관계).
 * x64 에서 352바이트.
 */
typedef struct DslConstants {
    float cameraViewToClip[16];
    float clipToCameraView[16];
    float clipToPrevClip[16];
    float prevClipToClip[16];
    float jitterOffset[2];   /* 렌더 해상도 픽셀 단위 */
    float mvecScale[2];      /* 모션 벡터를 [-1,1] 로 맞추는 배율 */
    float cameraPos[3];
    float cameraUp[3];
    float cameraRight[3];
    float cameraFwd[3];
    float cameraNear;
    float cameraFar;
    float cameraFov;         /* 세로 시야각, 라디안 */
    float cameraAspectRatio;
    int32_t depthInverted;          /* 0/1 */
    int32_t cameraMotionIncluded;   /* 0/1 */
    int32_t motionVectorsJittered;  /* 0/1 */
    int32_t reset;                  /* 0/1: 이전 프레임과 연결 끊김 */
} DslConstants;

/*
 * slDLSSGetOptimalSettings. mode 는 sl::DLSSMode 값
 * (0 Off, 1 MaxPerformance, 2 Balanced, 3 MaxQuality, 4 UltraPerformance, 5 UltraQuality, 6 DLAA).
 */
DSL_API int32_t dsl_dlss_optimal_settings(int32_t mode, uint32_t outputWidth, uint32_t outputHeight,
                                          uint32_t* renderWidth, uint32_t* renderHeight);

/* slDLSSSetOptions. colorHdr != 0 이면 colorBuffersHDR, autoExposure != 0 이면 useAutoExposure */
DSL_API int32_t dsl_dlss_set_options(uint32_t viewport, int32_t mode, uint32_t outputWidth, uint32_t outputHeight,
                                     int32_t colorHdr, int32_t autoExposure);

/* slGetNewFrameToken(frameIndex). 이후 set_constants / evaluate 가 이 프레임 토큰을 쓴다. */
DSL_API int32_t dsl_begin_frame(uint32_t frameIndex);

/* slSetConstants (현재 프레임 토큰) */
DSL_API int32_t dsl_set_constants(uint32_t viewport, const DslConstants* constants);

/*
 * slEvaluateFeature(kFeatureDLSS). 네 이미지는 "로컬 태그"(eValidUntilEvaluate)로 함께 넘긴다.
 * vkCommandBuffer 는 기록 중인 VkCommandBuffer. 호출 후 명령 버퍼의 바인딩 상태는 바뀌어 있을 수 있다.
 */
DSL_API int32_t dsl_dlss_evaluate(uint32_t viewport, void* vkCommandBuffer,
                                  const DslImage* colorIn, const DslImage* colorOut,
                                  const DslImage* depth, const DslImage* motionVectors);

/* slFreeResources(kFeatureDLSS, viewport). GPU 가 해당 자원을 다 쓴 뒤 호출해야 한다. */
DSL_API int32_t dsl_dlss_free(uint32_t viewport);

/* slShutdown. Vulkan 인스턴스/디바이스를 파괴하기 전에 호출해야 한다. 여러 번 불러도 안전. */
DSL_API int32_t dsl_shutdown(void);

#endif /* DRIFT_SL_BRIDGE_H */
