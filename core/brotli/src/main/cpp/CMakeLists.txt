cmake_minimum_required(VERSION 3.22.1)
project(vipbrotli C)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT ANDROID)
    find_path(JNI_INCLUDE_DIR jni.h HINTS "$ENV{JAVA_HOME}/include" REQUIRED)
    find_path(JNI_PLATFORM_INCLUDE_DIR jni_md.h
        HINTS "${JNI_INCLUDE_DIR}/linux" "${JNI_INCLUDE_DIR}/darwin" "${JNI_INCLUDE_DIR}/win32"
        REQUIRED)
endif()

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BROTLI_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    brotli
    URL https://codeload.github.com/google/brotli/tar.gz/refs/tags/v1.2.1
    URL_HASH SHA256=98d35d11a78dc453aebb7d10c3748f3f73ff78c8267c0dc697b8631f67bf7e8f
)
FetchContent_MakeAvailable(brotli)

add_library(vipbrotli SHARED VipBrotli.c)
if(NOT ANDROID)
    target_include_directories(vipbrotli PRIVATE "${JNI_INCLUDE_DIR}" "${JNI_PLATFORM_INCLUDE_DIR}")
endif()
target_link_libraries(vipbrotli PRIVATE brotlienc brotlidec)
if(ANDROID)
    target_link_options(vipbrotli PRIVATE "-Wl,-z,max-page-size=16384")
endif()
