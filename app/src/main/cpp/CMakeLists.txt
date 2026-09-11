cmake_minimum_required(VERSION 3.22)
project(ponko_mnn_sd CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------- MNN 头文件
set(MNN_INC   ${CMAKE_CURRENT_SOURCE_DIR}/mnn_include)
# ---------------------------------------------------------------- 引擎源码
set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/diffusion_engine)

# 只编 Stable Diffusion 相关的源文件。
# 排除 sana_diffusion.cpp / wan_diffusion.cpp（依赖 llm 引擎，本项目用不到）
# 排除 diffusion_sd35.cpp（SD3.5 额外管线，暂不需要）
set(ENGINE_SRCS
    ${ENGINE_DIR}/src/diffusion.cpp
    ${ENGINE_DIR}/src/stable_diffusion.cpp
    ${ENGINE_DIR}/src/scheduler.cpp
    ${ENGINE_DIR}/src/tokenizer.cpp
)

add_library(mnn_sd SHARED
    mnn_sd_jni.cpp
    ${ENGINE_SRCS}
)

target_include_directories(mnn_sd PRIVATE
    ${MNN_INC}
    ${ENGINE_DIR}/include
    ${ENGINE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# ---------------------------------------------------------------- MNN 预编译库
# jniLibs 里的 so 由打包环节提供；这里只做链接声明
set(MNN_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

target_link_libraries(mnn_sd
    ${MNN_LIB_DIR}/libMNN.so
    ${MNN_LIB_DIR}/libMNN_Express.so
    ${MNN_LIB_DIR}/libMNNOpenCV.so
    android
    log
    dl
    z
    m
)

target_compile_options(mnn_sd PRIVATE
    -fvisibility=hidden
    -O3
    -ffunction-sections
    -fdata-sections
)

target_link_options(mnn_sd PRIVATE
    -Wl,--gc-sections
)
