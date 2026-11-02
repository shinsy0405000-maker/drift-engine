// drift_sl_bridge.cpp - Streamline 을 C 함수로 감싼다. 헤더(drift_sl_bridge.h)의 설명 참고.
//
// 사용한 Streamline API (공식 헤더 sl_core_api.h / sl_core_types.h 에서 확인한 것만 사용):
//   slInit, slShutdown, slIsFeatureSupported, slGetFeatureRequirements,
//   sl::Preferences, sl::AdapterInfo, sl::FeatureRequirements, sl::kFeatureDLSS
// Stage 4e (SDK 2.14.16 헤더/문서/소스로 확인): slGetFeatureFunction, slGetNewFrameToken, slSetConstants,
//   slEvaluateFeature, slFreeResources, slDLSSGetOptimalSettings, slDLSSSetOptions,
//   sl::Constants, sl::Resource, sl::ResourceTag, sl::SubresourceRange, sl::ViewportHandle, sl::DLSSOptions
// slSetVulkanInfo 는 쓰지 않는다: interposer 경유(자동 후킹)에서는 필요 없고, 수동 후킹 모드 전용이다.
//
// Streamline 함수는 sl.interposer.lib 로 링크하지 않고, 로드한 sl.interposer.dll 에서 GetProcAddress 로 찾는다.
//  - sl_core_api.h 에서 이 함수들은 extern "C" 로 선언되어 있고, 같은 헤더의 PFun_* 형식을 그대로 쓴다.
//  - 덕분에 MSVC 의 /DELAYLOAD 없이도 "서명 확인 → 로드" 순서가 보장되고,
//    MSVC 와 MinGW 어느 쪽으로 빌드해도 같은 소스가 동작한다.

#include "drift_sl_bridge.h"

#include <windows.h>
#include <softpub.h>
#include <wintrust.h>

#include <sl.h>
#include <sl_dlss.h>

#include <cstddef>
#include <cstdio>
#include <cstring>
#include <exception>
#include <string>

namespace {

HMODULE g_interposer = nullptr;
PFun_slInit* g_slInit = nullptr;
PFun_slShutdown* g_slShutdown = nullptr;
PFun_slIsFeatureSupported* g_slIsFeatureSupported = nullptr;
PFun_slGetFeatureRequirements* g_slGetFeatureRequirements = nullptr;
PFun_slGetFeatureFunction* g_slGetFeatureFunction = nullptr;
PFun_slGetNewFrameToken* g_slGetNewFrameToken = nullptr;
PFun_slSetConstants* g_slSetConstants = nullptr;
PFun_slEvaluateFeature* g_slEvaluateFeature = nullptr;
PFun_slFreeResources* g_slFreeResources = nullptr;
// DLSS 전용 함수는 slGetFeatureFunction 으로 얻는다 (sl_dlss.h 의 도우미와 같은 방식)
PFun_slDLSSGetOptimalSettings* g_slDLSSGetOptimalSettings = nullptr;
PFun_slDLSSSetOptions* g_slDLSSSetOptions = nullptr;
sl::FrameToken* g_frame = nullptr;
bool g_initialized = false;
std::string g_lastError;

// slInit 에 넘긴 문자열은 Streamline 이 보관할 수 있으므로 전역에 둬서 수명을 보장한다.
std::wstring g_pluginDir;
std::wstring g_logDir;
std::string g_engineVersion;
std::string g_projectId;
const wchar_t* g_pluginPaths[1] = {nullptr};
const sl::Feature g_features[1] = {sl::kFeatureDLSS};

int32_t fail(int32_t code, const std::string& message) {
    g_lastError = message;
    return code;
}

int32_t ok() {
    g_lastError.clear();
    return DSL_OK;
}

std::string resultMessage(const char* call, sl::Result result) {
    char buffer[128];
    std::snprintf(buffer, sizeof(buffer), "%s returned sl::Result %d", call, static_cast<int>(result));
    return buffer;
}

template <typename F>
F* findFunction(HMODULE module, const char* name) {
    return reinterpret_cast<F*>(reinterpret_cast<void*>(GetProcAddress(module, name)));
}

}  // namespace

DSL_API int32_t dsl_abi_version(void) {
    return DSL_ABI_VERSION;
}

DSL_API const char* dsl_last_error(void) {
    return g_lastError.c_str();
}

DSL_API int32_t dsl_verify_signature(const wchar_t* absolutePath) {
    if (absolutePath == nullptr || absolutePath[0] == L'\0') {
        return fail(DSL_ERR_INVALID_ARGUMENT, "path is empty");
    }

    WINTRUST_FILE_INFO fileInfo{};
    fileInfo.cbStruct = sizeof(fileInfo);
    fileInfo.pcwszFilePath = absolutePath;

    WINTRUST_DATA trustData{};
    trustData.cbStruct = sizeof(trustData);
    trustData.dwUIChoice = WTD_UI_NONE;
    trustData.fdwRevocationChecks = WTD_REVOKE_NONE;
    trustData.dwUnionChoice = WTD_CHOICE_FILE;
    trustData.pFile = &fileInfo;
    trustData.dwStateAction = WTD_STATEACTION_VERIFY;

    GUID action = WINTRUST_ACTION_GENERIC_VERIFY_V2;
    LONG status = WinVerifyTrust(nullptr, &action, &trustData);

    // 검증 상태 해제 (결과와 무관하게 항상 호출)
    trustData.dwStateAction = WTD_STATEACTION_CLOSE;
    WinVerifyTrust(nullptr, &action, &trustData);

    if (status == ERROR_SUCCESS) {
        ok();
        return 1;
    }
    char buffer[96];
    std::snprintf(buffer, sizeof(buffer), "WinVerifyTrust failed: 0x%08lX", static_cast<unsigned long>(status));
    g_lastError = buffer;
    return 0;
}

DSL_API int32_t dsl_load_interposer(const wchar_t* absolutePath, int32_t requireSignature) {
    if (g_interposer != nullptr) {
        return ok();  // 이미 로드됨
    }
    if (absolutePath == nullptr || absolutePath[0] == L'\0') {
        return fail(DSL_ERR_INVALID_ARGUMENT, "path is empty");
    }
    if (requireSignature != 0 && dsl_verify_signature(absolutePath) != 1) {
        return fail(DSL_ERR_SIGNATURE, "sl.interposer.dll signature check failed: " + g_lastError);
    }
    // 같은 폴더의 sl.common.dll 등을 찾을 수 있도록 DLL 의 폴더를 검색 경로에 포함시킨다.
    HMODULE module = LoadLibraryExW(absolutePath, nullptr,
                                    LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (module == nullptr) {
        char buffer[96];
        std::snprintf(buffer, sizeof(buffer), "LoadLibraryExW failed: error %lu", GetLastError());
        return fail(DSL_ERR_LOAD_FAILED, buffer);
    }
    g_slInit = findFunction<PFun_slInit>(module, "slInit");
    g_slShutdown = findFunction<PFun_slShutdown>(module, "slShutdown");
    g_slIsFeatureSupported = findFunction<PFun_slIsFeatureSupported>(module, "slIsFeatureSupported");
    g_slGetFeatureRequirements = findFunction<PFun_slGetFeatureRequirements>(module, "slGetFeatureRequirements");
    g_slGetFeatureFunction = findFunction<PFun_slGetFeatureFunction>(module, "slGetFeatureFunction");
    g_slGetNewFrameToken = findFunction<PFun_slGetNewFrameToken>(module, "slGetNewFrameToken");
    g_slSetConstants = findFunction<PFun_slSetConstants>(module, "slSetConstants");
    g_slEvaluateFeature = findFunction<PFun_slEvaluateFeature>(module, "slEvaluateFeature");
    g_slFreeResources = findFunction<PFun_slFreeResources>(module, "slFreeResources");
    if (g_slInit == nullptr || g_slShutdown == nullptr || g_slIsFeatureSupported == nullptr
            || g_slGetFeatureRequirements == nullptr || g_slGetFeatureFunction == nullptr
            || g_slGetNewFrameToken == nullptr || g_slSetConstants == nullptr || g_slEvaluateFeature == nullptr
            || g_slFreeResources == nullptr) {
        g_slInit = nullptr;
        g_slShutdown = nullptr;
        g_slIsFeatureSupported = nullptr;
        g_slGetFeatureRequirements = nullptr;
        g_slGetFeatureFunction = nullptr;
        g_slGetNewFrameToken = nullptr;
        g_slSetConstants = nullptr;
        g_slEvaluateFeature = nullptr;
        g_slFreeResources = nullptr;
        FreeLibrary(module);
        return fail(DSL_ERR_LOAD_FAILED, "sl.interposer.dll does not export the expected sl* functions");
    }
    g_interposer = module;
    return ok();
}

DSL_API int32_t dsl_init(const wchar_t* pluginDir, const wchar_t* logDir, int32_t verboseLog,
                         const char* engineVersion, const char* projectId) {
    if (g_interposer == nullptr) {
        return fail(DSL_ERR_NOT_LOADED, "call dsl_load_interposer first");
    }
    if (g_initialized) {
        return fail(DSL_ERR_ALREADY, "already initialized");
    }
    if (pluginDir == nullptr || engineVersion == nullptr) {
        return fail(DSL_ERR_INVALID_ARGUMENT, "pluginDir and engineVersion are required");
    }

    try {
        g_pluginDir = pluginDir;
        g_logDir = logDir != nullptr ? logDir : L"";
        g_engineVersion = engineVersion;
        g_projectId = projectId != nullptr ? projectId : "";
        g_pluginPaths[0] = g_pluginDir.c_str();

        sl::Preferences prefs{};
        prefs.showConsole = false;
        prefs.logLevel = verboseLog != 0 ? sl::LogLevel::eVerbose : sl::LogLevel::eDefault;
        prefs.pathsToPlugins = g_pluginPaths;
        prefs.numPathsToPlugins = 1;
        prefs.pathToLogsAndData = g_logDir.empty() ? nullptr : g_logDir.c_str();
        prefs.featuresToLoad = g_features;  // 지정하지 않으면 아무 기능도 로드되지 않는다 (문서)
        prefs.numFeaturesToLoad = 1;
        prefs.engine = sl::EngineType::eCustom;  // applicationId 가 없으므로 엔진 종류/버전 지정
        prefs.engineVersion = g_engineVersion.c_str();
        prefs.projectId = g_projectId.empty() ? nullptr : g_projectId.c_str();
        prefs.renderAPI = sl::RenderAPI::eVulkan;  // 기본값은 D3D12 이므로 반드시 바꿔야 함

        sl::Result result = g_slInit(prefs, sl::kSDKVersion);
        if (result != sl::Result::eOk) {
            return fail(static_cast<int32_t>(result), resultMessage("slInit", result));
        }
        g_initialized = true;
        return ok();
    } catch (const std::exception& e) {
        return fail(DSL_ERR_EXCEPTION, std::string("exception in dsl_init: ") + e.what());
    }
}

DSL_API int32_t dsl_is_dlss_supported(void* vkPhysicalDevice) {
    if (!g_initialized) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_init first");
    }
    if (vkPhysicalDevice == nullptr) {
        return fail(DSL_ERR_INVALID_ARGUMENT, "vkPhysicalDevice is null");
    }
    sl::AdapterInfo adapter{};
    adapter.vkPhysicalDevice = vkPhysicalDevice;

    sl::Result result = g_slIsFeatureSupported(sl::kFeatureDLSS, adapter);
    if (result != sl::Result::eOk) {
        return fail(static_cast<int32_t>(result), resultMessage("slIsFeatureSupported(DLSS)", result));
    }
    return ok();
}

DSL_API int32_t dsl_get_dlss_requirements(DslVkRequirements* out) {
    if (!g_initialized) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_init first");
    }
    if (out == nullptr) {
        return fail(DSL_ERR_INVALID_ARGUMENT, "out is null");
    }
    std::memset(out, 0, sizeof(*out));

    sl::FeatureRequirements reqs{};
    sl::Result result = g_slGetFeatureRequirements(sl::kFeatureDLSS, reqs);
    if (result != sl::Result::eOk) {
        return fail(static_cast<int32_t>(result), resultMessage("slGetFeatureRequirements(DLSS)", result));
    }
    out->numInstanceExtensions = reqs.vkNumInstanceExtensions;
    out->numDeviceExtensions = reqs.vkNumDeviceExtensions;
    out->numFeatures12 = reqs.vkNumFeatures12;
    out->numFeatures13 = reqs.vkNumFeatures13;
    out->numGraphicsQueues = reqs.vkNumGraphicsQueuesRequired;
    out->numComputeQueues = reqs.vkNumComputeQueuesRequired;
    out->numOpticalFlowQueues = reqs.vkNumOpticalFlowQueuesRequired;
    out->instanceExtensions = reqs.vkInstanceExtensions;
    out->deviceExtensions = reqs.vkDeviceExtensions;
    out->features12 = reqs.vkFeatures12;
    out->features13 = reqs.vkFeatures13;
    return ok();
}

namespace {

template <typename F>
int32_t featureFunction(const char* name, F*& out) {
    if (out != nullptr) {
        return DSL_OK;
    }
    void* fn = nullptr;
    sl::Result result = g_slGetFeatureFunction(sl::kFeatureDLSS, name, fn);
    if (result != sl::Result::eOk || fn == nullptr) {
        return fail(result != sl::Result::eOk ? static_cast<int32_t>(result) : DSL_ERR_LOAD_FAILED,
                    std::string("slGetFeatureFunction(") + name + ") failed");
    }
    out = reinterpret_cast<F*>(fn);
    return DSL_OK;
}

bool validMode(int32_t mode) {
    return mode >= 0 && mode < static_cast<int32_t>(sl::DLSSMode::eCount);
}

void copyMatrix(sl::float4x4& dst, const float* src) {
    for (int r = 0; r < 4; r++) {
        dst.row[r] = sl::float4(src[r * 4 + 0], src[r * 4 + 1], src[r * 4 + 2], src[r * 4 + 3]);
    }
}

sl::Boolean toBool(int32_t v) {
    return v != 0 ? sl::Boolean::eTrue : sl::Boolean::eFalse;
}

void fillResource(sl::Resource& r, const DslImage& img) {
    r.type = sl::ResourceType::eTex2d;
    r.native = reinterpret_cast<void*>(static_cast<uintptr_t>(img.image));
    r.memory = reinterpret_cast<void*>(static_cast<uintptr_t>(img.memory));
    r.view = reinterpret_cast<void*>(static_cast<uintptr_t>(img.view));
    r.state = img.layout;
    r.width = img.width;
    r.height = img.height;
    r.nativeFormat = img.format;
    r.mipLevels = 1;
    r.arrayLayers = 1;
    r.flags = 0;
    r.usage = img.usage;
}

}  // namespace

DSL_API int32_t dsl_dlss_optimal_settings(int32_t mode, uint32_t outputWidth, uint32_t outputHeight,
                                          uint32_t* renderWidth, uint32_t* renderHeight) {
    if (!g_initialized) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_init first");
    }
    if (!validMode(mode) || renderWidth == nullptr || renderHeight == nullptr || outputWidth == 0 || outputHeight == 0) {
        return fail(DSL_ERR_INVALID_ARGUMENT, "invalid mode, size or output pointer");
    }
    int32_t code = featureFunction("slDLSSGetOptimalSettings", g_slDLSSGetOptimalSettings);
    if (code != DSL_OK) {
        return code;
    }
    try {
        sl::DLSSOptions options{};
        options.mode = static_cast<sl::DLSSMode>(mode);
        options.outputWidth = outputWidth;
        options.outputHeight = outputHeight;
        sl::DLSSOptimalSettings settings{};
        sl::Result result = g_slDLSSGetOptimalSettings(options, settings);
        if (result != sl::Result::eOk) {
            return fail(static_cast<int32_t>(result), resultMessage("slDLSSGetOptimalSettings", result));
        }
        *renderWidth = settings.optimalRenderWidth;
        *renderHeight = settings.optimalRenderHeight;
        return ok();
    } catch (const std::exception& e) {
        return fail(DSL_ERR_EXCEPTION, std::string("exception in dsl_dlss_optimal_settings: ") + e.what());
    }
}

DSL_API int32_t dsl_dlss_set_options(uint32_t viewport, int32_t mode, uint32_t outputWidth, uint32_t outputHeight,
                                     int32_t colorHdr, int32_t autoExposure) {
    if (!g_initialized) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_init first");
    }
    if (!validMode(mode) || outputWidth == 0 || outputHeight == 0) {
        return fail(DSL_ERR_INVALID_ARGUMENT, "invalid mode or size");
    }
    int32_t code = featureFunction("slDLSSSetOptions", g_slDLSSSetOptions);
    if (code != DSL_OK) {
        return code;
    }
    try {
        sl::DLSSOptions options{};
        options.mode = static_cast<sl::DLSSMode>(mode);
        options.outputWidth = outputWidth;
        options.outputHeight = outputHeight;
        options.colorBuffersHDR = toBool(colorHdr);
        options.useAutoExposure = toBool(autoExposure);
        sl::Result result = g_slDLSSSetOptions(sl::ViewportHandle(viewport), options);
        if (result != sl::Result::eOk) {
            return fail(static_cast<int32_t>(result), resultMessage("slDLSSSetOptions", result));
        }
        return ok();
    } catch (const std::exception& e) {
        return fail(DSL_ERR_EXCEPTION, std::string("exception in dsl_dlss_set_options: ") + e.what());
    }
}

DSL_API int32_t dsl_begin_frame(uint32_t frameIndex) {
    if (!g_initialized) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_init first");
    }
    sl::FrameToken* token = nullptr;
    sl::Result result = g_slGetNewFrameToken(token, &frameIndex);
    if (result != sl::Result::eOk || token == nullptr) {
        g_frame = nullptr;
        return fail(result != sl::Result::eOk ? static_cast<int32_t>(result) : DSL_ERR_EXCEPTION,
                    resultMessage("slGetNewFrameToken", result));
    }
    g_frame = token;
    return ok();
}

DSL_API int32_t dsl_set_constants(uint32_t viewport, const DslConstants* c) {
    if (!g_initialized) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_init first");
    }
    if (g_frame == nullptr) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_begin_frame first");
    }
    if (c == nullptr) {
        return fail(DSL_ERR_INVALID_ARGUMENT, "constants is null");
    }
    try {
        sl::Constants k{};
        copyMatrix(k.cameraViewToClip, c->cameraViewToClip);
        copyMatrix(k.clipToCameraView, c->clipToCameraView);
        static const float kIdentity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        copyMatrix(k.clipToLensClip, kIdentity);  // 선택 항목: 렌즈 왜곡 없음
        copyMatrix(k.clipToPrevClip, c->clipToPrevClip);
        copyMatrix(k.prevClipToClip, c->prevClipToClip);
        k.jitterOffset = sl::float2(c->jitterOffset[0], c->jitterOffset[1]);
        k.mvecScale = sl::float2(c->mvecScale[0], c->mvecScale[1]);
        k.cameraPinholeOffset = sl::float2(0.0f, 0.0f);
        k.cameraPos = sl::float3(c->cameraPos[0], c->cameraPos[1], c->cameraPos[2]);
        k.cameraUp = sl::float3(c->cameraUp[0], c->cameraUp[1], c->cameraUp[2]);
        k.cameraRight = sl::float3(c->cameraRight[0], c->cameraRight[1], c->cameraRight[2]);
        k.cameraFwd = sl::float3(c->cameraFwd[0], c->cameraFwd[1], c->cameraFwd[2]);
        k.cameraNear = c->cameraNear;
        k.cameraFar = c->cameraFar;
        k.cameraFOV = c->cameraFov;
        k.cameraAspectRatio = c->cameraAspectRatio;
        k.depthInverted = toBool(c->depthInverted);
        k.cameraMotionIncluded = toBool(c->cameraMotionIncluded);
        k.motionVectors3D = sl::Boolean::eFalse;
        k.reset = toBool(c->reset);
        k.orthographicProjection = sl::Boolean::eFalse;
        k.motionVectorsDilated = sl::Boolean::eFalse;
        k.motionVectorsJittered = toBool(c->motionVectorsJittered);

        sl::Result result = g_slSetConstants(k, *g_frame, sl::ViewportHandle(viewport));
        if (result != sl::Result::eOk) {
            return fail(static_cast<int32_t>(result), resultMessage("slSetConstants", result));
        }
        return ok();
    } catch (const std::exception& e) {
        return fail(DSL_ERR_EXCEPTION, std::string("exception in dsl_set_constants: ") + e.what());
    }
}

DSL_API int32_t dsl_dlss_evaluate(uint32_t viewport, void* vkCommandBuffer,
                                  const DslImage* colorIn, const DslImage* colorOut,
                                  const DslImage* depth, const DslImage* motionVectors) {
    if (!g_initialized) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_init first");
    }
    if (g_frame == nullptr) {
        return fail(DSL_ERR_NOT_INITIALIZED, "call dsl_begin_frame first");
    }
    if (vkCommandBuffer == nullptr || colorIn == nullptr || colorOut == nullptr || depth == nullptr
            || motionVectors == nullptr) {
        return fail(DSL_ERR_INVALID_ARGUMENT, "null command buffer or image");
    }
    try {
        const DslImage* images[4] = {colorIn, colorOut, depth, motionVectors};
        const sl::BufferType types[4] = {sl::kBufferTypeScalingInputColor, sl::kBufferTypeScalingOutputColor,
                                         sl::kBufferTypeDepth, sl::kBufferTypeMotionVectors};

        // sl::Resource 에는 기본 생성자가 없으므로 생성자로 만든 뒤 채운다
        sl::Resource resources[4] = {
            sl::Resource(sl::ResourceType::eTex2d, nullptr, nullptr, nullptr),
            sl::Resource(sl::ResourceType::eTex2d, nullptr, nullptr, nullptr),
            sl::Resource(sl::ResourceType::eTex2d, nullptr, nullptr, nullptr),
            sl::Resource(sl::ResourceType::eTex2d, nullptr, nullptr, nullptr)};
        // 하위 리소스 범위: 지정하지 않으면 Streamline(sl.dlss)이 COLOR aspect 로 가정하므로 깊이에는 꼭 필요하다
        sl::SubresourceRange ranges[4]{};
        sl::Extent extents[4]{};
        for (int i = 0; i < 4; i++) {
            fillResource(resources[i], *images[i]);
            ranges[i].aspectMask = images[i]->aspectMask;
            ranges[i].baseMipLevel = 0;
            ranges[i].levelCount = 1;
            ranges[i].baseArrayLayer = 0;
            ranges[i].layerCount = 1;
            resources[i].next = &ranges[i];
            extents[i].top = 0;
            extents[i].left = 0;
            extents[i].width = images[i]->width;
            extents[i].height = images[i]->height;
        }
        sl::ResourceTag tags[4] = {
            sl::ResourceTag(&resources[0], types[0], sl::ResourceLifecycle::eValidUntilEvaluate, &extents[0]),
            sl::ResourceTag(&resources[1], types[1], sl::ResourceLifecycle::eValidUntilEvaluate, &extents[1]),
            sl::ResourceTag(&resources[2], types[2], sl::ResourceLifecycle::eValidUntilEvaluate, &extents[2]),
            sl::ResourceTag(&resources[3], types[3], sl::ResourceLifecycle::eValidUntilEvaluate, &extents[3])};

        sl::ViewportHandle vp(viewport);
        const sl::BaseStructure* inputs[5] = {&vp, &tags[0], &tags[1], &tags[2], &tags[3]};
        sl::Result result = g_slEvaluateFeature(sl::kFeatureDLSS, *g_frame, inputs, 5, vkCommandBuffer);
        if (result != sl::Result::eOk) {
            return fail(static_cast<int32_t>(result), resultMessage("slEvaluateFeature(DLSS)", result));
        }
        return ok();
    } catch (const std::exception& e) {
        return fail(DSL_ERR_EXCEPTION, std::string("exception in dsl_dlss_evaluate: ") + e.what());
    }
}

DSL_API int32_t dsl_dlss_free(uint32_t viewport) {
    if (!g_initialized) {
        return ok();
    }
    sl::Result result = g_slFreeResources(sl::kFeatureDLSS, sl::ViewportHandle(viewport));
    if (result != sl::Result::eOk) {
        return fail(static_cast<int32_t>(result), resultMessage("slFreeResources(DLSS)", result));
    }
    return ok();
}

DSL_API int32_t dsl_shutdown(void) {
    if (!g_initialized) {
        return ok();  // 초기화 전이거나 이미 종료됨
    }
    g_initialized = false;
    g_frame = nullptr;
    g_slDLSSGetOptimalSettings = nullptr;
    g_slDLSSSetOptions = nullptr;
    sl::Result result = g_slShutdown();
    if (result != sl::Result::eOk) {
        return fail(static_cast<int32_t>(result), resultMessage("slShutdown", result));
    }
    return ok();
}

// Streamline 구조체 크기 (SDK 2.14.16 기준). MSVC 와 MinGW 에서 크기와 모든 필드 위치가 같음을
// clang 레이아웃 출력으로 비교해 확인했다. SDK 를 바꿔서 여기서 실패하면 그 비교를 다시 해야 한다.
static_assert(sizeof(void*) != 8 || sizeof(sl::Preferences) == 144, "sl::Preferences layout changed");
static_assert(sizeof(void*) != 8 || sizeof(sl::AdapterInfo) == 56, "sl::AdapterInfo layout changed");
static_assert(sizeof(void*) != 8 || sizeof(sl::FeatureRequirements) == 184, "sl::FeatureRequirements layout changed");

static_assert(sizeof(void*) != 8 || sizeof(DslImage) == 48, "DslImage layout must match Java StreamlineBridge.IMAGE_LAYOUT");
static_assert(sizeof(DslConstants) == 352, "DslConstants layout must match Java StreamlineBridge.CONSTANTS_LAYOUT");
static_assert(sizeof(sl::float4x4) == 64, "sl::float4x4 must be 16 floats");
// Java CONSTANTS_LAYOUT / IMAGE_LAYOUT 의 필드 위치와 같아야 한다 (selftest 에서 같은 숫자를 확인)
static_assert(offsetof(DslConstants, jitterOffset) == 256, "DslConstants.jitterOffset");
static_assert(offsetof(DslConstants, cameraPos) == 272, "DslConstants.cameraPos");
static_assert(offsetof(DslConstants, cameraNear) == 320, "DslConstants.cameraNear");
static_assert(offsetof(DslConstants, depthInverted) == 336, "DslConstants.depthInverted");
static_assert(offsetof(DslConstants, reset) == 348, "DslConstants.reset");
static_assert(offsetof(DslImage, layout) == 24, "DslImage.layout");
static_assert(offsetof(DslImage, aspectMask) == 44, "DslImage.aspectMask");
// Streamline 구조체 크기 (SDK 2.14.16, MSVC/MinGW 레이아웃 비교로 확인)
static_assert(sizeof(void*) != 8 || sizeof(sl::Constants) == 456, "sl::Constants layout changed");
static_assert(sizeof(void*) != 8 || sizeof(sl::Resource) == 112, "sl::Resource layout changed");
static_assert(sizeof(void*) != 8 || sizeof(sl::ResourceTag) == 64, "sl::ResourceTag layout changed");
static_assert(sizeof(void*) != 8 || sizeof(sl::SubresourceRange) == 56, "sl::SubresourceRange layout changed");
static_assert(sizeof(void*) != 8 || sizeof(sl::ViewportHandle) == 40, "sl::ViewportHandle layout changed");
static_assert(sizeof(void*) != 8 || sizeof(sl::DLSSOptions) == 88, "sl::DLSSOptions layout changed");
static_assert(sizeof(void*) != 8 || sizeof(sl::DLSSOptimalSettings) == 64, "sl::DLSSOptimalSettings layout changed");

static_assert(sizeof(void*) != 8 || sizeof(DslVkRequirements) == 64,
              "DslVkRequirements layout must match Java StreamlineBridge.REQUIREMENTS_LAYOUT");
