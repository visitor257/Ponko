# Ponko —— 自编 stable-diffusion.cpp 的构建脚本
#
# 这里做三件事：
#   1. 以子目录方式引入 sd.cpp（源码在 ./sd，含内联的 ggml，已剔除不用的词表/示例等）
#   2. 使用实测最优的编译标志（ARMv8.2-A + dotprod + fp16 + -O3）
#   3. 编译 JNI 桥 → libponko_sd.so
#
# 此前这两个 .so 是手工编译后塞进 jniLibs/ 的，容易出现「改了 C++ 源码但 .so 没重编」
# 的静默错配（包能构建成功，跑起来却是旧行为）。改成这里现场编译后，
# 每次 assembleRelease 的产物必然与当前源码一致。

cmake_minimum_required(VERSION 3.22.1)
project(ponko_sd CXX C)

# ---------------------------------------------------------------------------
# 编译标志
# ---------------------------------------------------------------------------
# 实测：-O3 + ARMv8.2-A(dotprod+fp16) 使 6 步 256x256 从 116s 降到 56s（约 2x）。
# 代价：要求 2019 年之后的 arm64 设备，更老的 CPU 会 SIGILL。
add_compile_options(-march=armv8.2-a+dotprod+fp16)

# 强制各 Release 变体都是 -O3 且不带调试符号。
# （AGP 给 release 变体传的可能是 RelWithDebInfo = -O2 -g，性能与体积都不理想）
foreach(_cfg RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_C_FLAGS_${_cfg}   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS_${_cfg} "-O3 -DNDEBUG" CACHE STRING "" FORCE)
endforeach()

# 限制并行度：源码含若干超大翻译单元，8GB 内存的机器上 -j4 会 OOM（LLVM ERROR: out of memory）
set_property(GLOBAL PROPERTY JOB_POOLS ponko_compile=2)
set(CMAKE_JOB_POOL_COMPILE ponko_compile)

# ---------------------------------------------------------------------------
# sd.cpp 选项（必须在 add_subdirectory 之前用 CACHE ... FORCE 设好）
# ---------------------------------------------------------------------------
set(SD_BUILD_EXAMPLES        OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)  # 产出 libstable-diffusion.so
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)  # ggml 静态并入，少一个 .so
set(SD_USE_SYSTEM_GGML       OFF CACHE BOOL "" FORCE)
set(SD_WEBP   OFF CACHE BOOL "" FORCE)
set(SD_WEBM   OFF CACHE BOOL "" FORCE)
set(SD_CUDA   OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(SD_METAL  OFF CACHE BOOL "" FORCE)
set(SD_SYCL   OFF CACHE BOOL "" FORCE)
set(SD_HIPBLAS OFF CACHE BOOL "" FORCE)
set(SD_MUSA   OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP         ON  CACHE BOOL "" FORCE)  # OpenMP 多线程（运行时用 NDK 的 libomp.so）
set(GGML_CPU            ON  CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK     ON  CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE         OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# 引入 sd.cpp
# ---------------------------------------------------------------------------
# AGP 期望 .so 落在 CMAKE_LIBRARY_OUTPUT_DIRECTORY；而 sd.cpp 顶层会把它改成
# <build>/bin，导致产物打不进 APK。所以先记下 AGP 给的值，之后再拨回去。
set(PONKO_SO_OUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
message(STATUS "Ponko: AGP .so output dir = '${PONKO_SO_OUT}'")

add_subdirectory(sd)

if(PONKO_SO_OUT)
    foreach(_sfx "" _RELEASE _RELWITHDEBINFO _DEBUG)
        set_target_properties(stable-diffusion PROPERTIES
            "LIBRARY_OUTPUT_DIRECTORY${_sfx}" "${PONKO_SO_OUT}")
    endforeach()
endif()

# ---------------------------------------------------------------------------
# JNI 桥 → libponko_sd.so
# ---------------------------------------------------------------------------
add_library(ponko_sd SHARED sdcpp_jni.cpp)
target_link_libraries(ponko_sd PRIVATE stable-diffusion android log)
