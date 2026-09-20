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
set(SD_VULKAN ON  CACHE BOOL "" FORCE)  # 编入 Vulkan 后端（会自己编 ggml-vulkan + 生成 shader）；设备没有 Vulkan 时运行时回退 CPU
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

# ---------------------------------------------------------------------------
# Vulkan 后端依赖自举（不要求安装 Vulkan SDK）
# ---------------------------------------------------------------------------
# ggml-vulkan 需要两样东西，NDK 里其实都自带了：
#   1) glslc：把 .comp 着色器编译成 SPIR-V —— NDK 的 shader-tools/<host>/glslc[.exe]
#   2) SPIRV-Headers：一个 CMake 包配置（find_package(SPIRV-Headers CONFIG)）
#      —— NDK 在 sources/third_party/shaderc 里带了 spirv-headers 头文件，这里就地生成最小配置
# 另外交叉编译时 ggml 还要一个 host 工具（vulkan-shaders-gen）：Windows 需要 cl.exe，
# Linux/macOS 用自带的 gcc/clang 即可。构建命令里带上 MSVC 环境（vcvars64）就行。
if (SD_VULKAN)
    # NDK 只带 Vulkan 的 C 头，没有 C++ 头（vulkan.hpp）。这里用仓库里自带的 Vulkan-Hpp
    # （与 NDK 的 vulkan_core.h 同为 1.3.275）。只追加 include 路径，不覆盖 NDK 的 C 头。
    include_directories("${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/include")
    # ggml-vulkan 还会 include SPIRV-Headers 的 C++ 头（spirv/unified1/spirv.hpp），NDK 里自带有
    set(_ponko_spirv_hpp_inc "${ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
    if (EXISTS "${_ponko_spirv_hpp_inc}/spirv/unified1/spirv.hpp")
        include_directories("${_ponko_spirv_hpp_inc}")
    else ()
        message(WARNING "Ponko: spirv.hpp not found in the NDK (${_ponko_spirv_hpp_inc})")
    endif ()

    # 注意：交叉编译时 WIN32 指的是【目标平台】（这里是 Android），所以必须用 host 判断
    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        # 先把 ExternalProject 里 define_property(... INHERITED) 的定义加载进来，
        # 否则 EP_BASE 会被当成普通属性设上、不会继承给子目录（实测无效）。
        include(ExternalProject)
        # Windows 的 MAX_PATH：ggml 的 host 工具（vulkan-shaders-gen）用 ExternalProject 构建，
        # 默认会落在 <build>/.cxx/<config>/<hash>/<abi>/sd/ggml/... 这种超深目录里，
        # MSVC 因对象文件路径超过 250 字符直接报 C1083。用 EP_BASE 把它挪到短目录（只影响这个 host 工具）。
        set(_ponko_ep_base "$ENV{TEMP}/ponko-ep")
        if (NOT _ponko_ep_base OR _ponko_ep_base STREQUAL "/ponko-ep")
            set(_ponko_ep_base "C:/ponko-ep")
        endif ()
        file(TO_CMAKE_PATH "${_ponko_ep_base}" _ponko_ep_base)
        set_property(DIRECTORY PROPERTY EP_BASE "${_ponko_ep_base}")
        message(STATUS "Ponko: external project base = ${_ponko_ep_base}")
    endif ()

    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(_ponko_host_tag "windows-x86_64")
        set(_ponko_glslc_name "glslc.exe")
    elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(_ponko_host_tag "darwin-x86_64")
        set(_ponko_glslc_name "glslc")
    else ()
        set(_ponko_host_tag "linux-x86_64")
        set(_ponko_glslc_name "glslc")
    endif ()

    if (NOT Vulkan_GLSLC_EXECUTABLE)
        set(_ponko_glslc "${ANDROID_NDK}/shader-tools/${_ponko_host_tag}/${_ponko_glslc_name}")
        if (EXISTS "${_ponko_glslc}")
            set(Vulkan_GLSLC_EXECUTABLE "${_ponko_glslc}" CACHE FILEPATH "glslc bundled with the NDK" FORCE)
            message(STATUS "Ponko: glslc = ${_ponko_glslc}")
        else ()
            message(WARNING "Ponko: glslc not found in the NDK (${_ponko_glslc}); set Vulkan_GLSLC_EXECUTABLE or install the Vulkan SDK")
        endif ()
    endif ()

    if (NOT SPIRV-Headers_DIR)
        set(_ponko_spirv_inc "${ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
        if (EXISTS "${_ponko_spirv_inc}/spirv/unified1/spirv.hpp")
            set(_ponko_cfg_dir "${CMAKE_BINARY_DIR}/ponko-spirv-headers")
            file(MAKE_DIRECTORY "${_ponko_cfg_dir}")
            file(WRITE "${_ponko_cfg_dir}/SPIRV-HeadersConfig.cmake"
"# generated by Ponko: minimal SPIRV-Headers config (headers come from the NDK)
set(SPIRV-Headers_FOUND TRUE)
set(SPIRV-Headers_INCLUDE_DIR \"${_ponko_spirv_inc}\")
set(SPIRV-Headers_INCLUDE_DIRS \"${_ponko_spirv_inc}\")
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${_ponko_spirv_inc}\")
endif()
")
            set(SPIRV-Headers_DIR "${_ponko_cfg_dir}" CACHE PATH "generated minimal SPIRV-Headers config" FORCE)
            message(STATUS "Ponko: SPIRV-Headers config generated at ${_ponko_cfg_dir}")
        else ()
            message(WARNING "Ponko: SPIRV headers not found in the NDK (${_ponko_spirv_inc}); set SPIRV-Headers_DIR manually")
        endif ()
    endif ()
endif ()

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
