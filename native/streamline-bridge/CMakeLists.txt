# drift_sl_bridge - Streamline C 브리지 (Windows x64, MSVC 또는 MinGW-w64)
#
# Streamline 함수는 sl.interposer.dll 에서 GetProcAddress 로 찾으므로 sl.interposer.lib 는 링크하지 않는다.
#
# 빌드 예 (Developer PowerShell for VS):
#   cmake -S native/streamline-bridge -B native/build -A x64 -DSTREAMLINE_SDK="C:/sdk/streamline-sdk-v2.x.x"
#   cmake --build native/build --config Release
# 결과 DLL 은 프로젝트의 natives/streamline/ 으로 복사된다.

cmake_minimum_required(VERSION 3.20)
project(drift_sl_bridge LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "Streamline 은 Windows 전용입니다.")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# C++ 런타임을 정적으로 넣어 VC++ 재배포 패키지 없이도 브리지 DLL 이 로드되게 한다.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

set(STREAMLINE_SDK "" CACHE PATH "Streamline SDK 릴리스 zip 을 푼 폴더 (include/sl.h 가 있는 곳)")
set(DSL_OUTPUT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../natives/streamline" CACHE PATH "빌드된 DLL 복사 위치")

if(NOT EXISTS "${STREAMLINE_SDK}/include/sl.h")
    message(FATAL_ERROR "STREAMLINE_SDK 가 올바르지 않습니다: '${STREAMLINE_SDK}' (include/sl.h 없음)")
endif()

add_library(drift_sl_bridge SHARED src/drift_sl_bridge.cpp)

target_include_directories(drift_sl_bridge PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${STREAMLINE_SDK}/include")

target_compile_definitions(drift_sl_bridge PRIVATE DSL_BUILDING UNICODE _UNICODE NOMINMAX)

target_link_libraries(drift_sl_bridge PRIVATE wintrust)

if(MSVC)
    target_compile_options(drift_sl_bridge PRIVATE /W4 /EHsc /utf-8)
elseif(MINGW)
    target_compile_options(drift_sl_bridge PRIVATE -Wall -Wextra)
    # libstdc++/libgcc 를 정적으로 넣어 MinGW 런타임 DLL 없이 로드되게 한다.
    target_link_options(drift_sl_bridge PRIVATE -static -static-libgcc -static-libstdc++)
    set_target_properties(drift_sl_bridge PROPERTIES PREFIX "")
endif()

add_custom_command(TARGET drift_sl_bridge POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${DSL_OUTPUT_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:drift_sl_bridge>" "${DSL_OUTPUT_DIR}"
    COMMENT "drift_sl_bridge.dll -> ${DSL_OUTPUT_DIR}")
