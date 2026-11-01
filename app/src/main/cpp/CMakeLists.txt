cmake_minimum_required(VERSION 3.22.1)
project("llama_jni" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Pinned llama.cpp commit: 785322d17f2d51e5185cf996a1f4e46d87640841
set(LLAMA_CPP_PINNED_COMMIT "785322d17f2d51e5185cf996a1f4e46d87640841")

# Configure llama.cpp & ggml for 32-bit ARMv7 (armeabi-v7a) with NEON and no -march=native
set(GGML_NATIVE OFF CACHE BOOL "Disable -march=native for Android cross-compilation" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on low-end 32-bit Android" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile on 32-bit ARMv7" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "Disable KleidiAI on 32-bit ARMv7" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Do not build llama.cpp tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Do not build llama.cpp examples" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Do not build llama.cpp tools" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Do not build llama.cpp server" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "Disable libcurl in llama.cpp" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Link llama and ggml statically into libllama_jni.so" FORCE)

if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    add_compile_options(-mfpu=neon -mfloat-abi=softfp -fomit-frame-pointer)
    # On 32-bit Android API 21, Bionic hides ftello/fseeko when _FILE_OFFSET_BITS=64 is set
    # and exposes madvise/MADV_* instead of posix_madvise/POSIX_MADV_*.
    add_compile_definitions(
        ftello=ftell
        fseeko=fseek
        posix_madvise=madvise
        POSIX_MADV_WILLNEED=MADV_WILLNEED
        POSIX_MADV_RANDOM=MADV_RANDOM
    )
endif()

set(LLAMA_LOCAL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if(EXISTS "${LLAMA_LOCAL_DIR}/CMakeLists.txt")
    message(STATUS "Building integrated llama.cpp from ${LLAMA_LOCAL_DIR} (commit ${LLAMA_CPP_PINNED_COMMIT})")
    add_subdirectory("${LLAMA_LOCAL_DIR}" build-llama EXCLUDE_FROM_ALL)
else()
    message(STATUS "Fetching llama.cpp commit ${LLAMA_CPP_PINNED_COMMIT}")
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        ${LLAMA_CPP_PINNED_COMMIT}
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(
    llama_jni
    SHARED
    llama_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(
    llama_jni
    PRIVATE
    "${LLAMA_LOCAL_DIR}/include"
    "${LLAMA_LOCAL_DIR}/ggml/include"
)

target_compile_options(
    llama_jni
    PRIVATE
    -O3
    -DNDEBUG
    -fexceptions
    -frtti
)

target_link_libraries(
    llama_jni
    PRIVATE
    llama
    ggml
    ggml-base
    ggml-cpu
    ${log-lib}
    ${android-lib}
)
