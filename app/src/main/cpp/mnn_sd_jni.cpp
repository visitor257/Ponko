//
// mnn_sd_jni.cpp
// Ponko 的 MNN 文生图/图生图 JNI 桥
//
// 包装 MNN 官方 transformers/diffusion/engine 的 Diffusion 接口，
// 暴露给 Kotlin（com.litertchat.app.draw.MnnSdEngine）。
//
#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <mutex>
#include <atomic>
#include "diffusion/diffusion.hpp"

#define LOG_TAG "PonkoMNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace MNN::DIFFUSION;

namespace {

// 缓存 JavaVM，供 native 线程回调使用
JavaVM* g_vm = nullptr;

struct SdHandle {
    std::unique_ptr<Diffusion> diffusion;
    std::atomic<bool> cancelled{false};
    std::mutex mtx;
    // 进度回调（Kotlin 侧对象、全局引用、方法 id）
    jobject cbObj = nullptr;
    jmethodID cbMethod = nullptr;
};

JNIEnv* getEnv(bool* needDetach) {
    *needDetach = false;
    if (g_vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    jint r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        *needDetach = true;
    }
    return env;
}

// 转成 UTF-8 的 std::string
std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = (c != nullptr) ? c : "";
    if (c != nullptr) env->ReleaseStringUTFChars(s, c);
    return r;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_litertchat_app_draw_MnnSdEngine_nativeInit(JNIEnv* env, jclass clazz) {
    if (g_vm == nullptr) {
        env->GetJavaVM(&g_vm);
    }
    return 0;
}

/**
 * 创建 Diffusion 实例。
 * @param resourcePath 模型目录（含 unet.mnn/.weight、text_encoder.mnn/.weight、
 *                     vae_decoder.mnn/.weight、vocab.json、merges.txt、alphas.txt）
 * @param backendType  MNN 后端：0=CPU 3=OpenCL 7=Vulkan
 * @param memoryMode   0=省内存 1=够内存（快） 2=平衡
 * @return 句柄（0 表示失败）
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_litertchat_app_draw_MnnSdEngine_nativeCreate(JNIEnv* env, jclass clazz,
                                                      jstring resourcePath,
                                                      jint modelType,
                                                      jint backendType,
                                                      jint memoryMode) {
    std::string path = jstr(env, resourcePath);
    LOGI("nativeCreate path=%s modelType=%d backend=%d memoryMode=%d",
         path.c_str(), (int) modelType, (int) backendType, (int) memoryMode);
    auto* h = new (std::nothrow) SdHandle();
    if (h == nullptr) {
        LOGE("nativeCreate: OOM");
        return 0;
    }
    h->diffusion.reset(Diffusion::createDiffusion(
            path, (DiffusionModelType) modelType,
            (MNNForwardType) backendType, (int) memoryMode));
    if (h->diffusion == nullptr) {
        LOGE("nativeCreate: createDiffusion returned null");
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

/** 加载模型（阻塞，可能耗时数十秒；请勿在主线程调用）。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_litertchat_app_draw_MnnSdEngine_nativeLoad(JNIEnv* env, jclass clazz, jlong handle) {
    auto* h = reinterpret_cast<SdHandle*>(handle);
    if (h == nullptr || h->diffusion == nullptr) return JNI_FALSE;
    LOGI("nativeLoad start");
    bool ok = h->diffusion->load();
    LOGI("nativeLoad done ok=%d", (int) ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * 生成图片（阻塞）。
 * @param mode        "text2img" 或 "img2img"
 * @param inputImage  图生图的输入图路径（text2img 传 null/空）
 * @param outputPath  输出图片路径
 * @param progressCb  可选，Kotlin 的 (Int)->Unit 回调对象；传 null 则无进度
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_litertchat_app_draw_MnnSdEngine_nativeRun(JNIEnv* env, jclass clazz,
                                                   jlong handle,
                                                   jstring prompt,
                                                   jstring mode,
                                                   jstring inputImage,
                                                   jstring outputPath,
                                                   jint width, jint height,
                                                   jint steps, jint seed,
                                                   jfloat cfgScale,
                                                   jobject progressCb) {
    auto* h = reinterpret_cast<SdHandle*>(handle);
    if (h == nullptr || h->diffusion == nullptr) return JNI_FALSE;

    std::string p = jstr(env, prompt);
    std::string m = jstr(env, mode);
    std::string inImg = jstr(env, inputImage);
    std::string outImg = jstr(env, outputPath);
    if (m.empty()) m = "text2img";

    if (progressCb != nullptr) {
        h->cbObj = env->NewGlobalRef(progressCb);
        jclass cls = env->GetObjectClass(progressCb);
        // Kotlin (Int)->Unit 编译后是 invoke(int) 方法
        h->cbMethod = env->GetMethodID(cls, "invoke", "(I)V");
        env->DeleteLocalRef(cls);
    }

    auto cb = [h](int progress) {
        if (h->cbObj == nullptr || h->cbMethod == nullptr) return;
        bool detach = false;
        JNIEnv* e = getEnv(&detach);
        if (e != nullptr) {
            e->CallVoidMethod(h->cbObj, h->cbMethod, (jint) progress);
            if (e->ExceptionCheck()) e->ExceptionClear();
        }
        if (detach && g_vm != nullptr) g_vm->DetachCurrentThread();
    };

    LOGI("nativeRun mode=%s %dx%d steps=%d seed=%d cfg=%.2f out=%s",
         m.c_str(), (int) width, (int) height, (int) steps, (int) seed,
         (float) cfgScale, outImg.c_str());

    bool ok;
    if (m == "img2img") {
        // 图生图走统一接口（input_embeds 传 nullptr，由引擎内部做文本编码）
        ok = h->diffusion->run(VARP(nullptr), m, inImg, outImg,
                               (int) width, (int) height, (int) steps,
                               (int) seed, true, (float) cfgScale, cb);
    } else {
        // 文生图：简单接口（iterNum + seed）
        ok = h->diffusion->run(p, outImg, (int) steps, (int) seed, cb);
    }

    if (h->cbObj != nullptr) {
        env->DeleteGlobalRef(h->cbObj);
        h->cbObj = nullptr;
        h->cbMethod = nullptr;
    }
    LOGI("nativeRun done ok=%d", (int) ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** 释放实例。 */
extern "C" JNIEXPORT void JNICALL
Java_com_litertchat_app_draw_MnnSdEngine_nativeDestroy(JNIEnv* env, jclass clazz, jlong handle) {
    auto* h = reinterpret_cast<SdHandle*>(handle);
    if (h == nullptr) return;
    if (h->cbObj != nullptr) env->DeleteGlobalRef(h->cbObj);
    h->diffusion.reset();
    delete h;
    LOGI("nativeDestroy done");
}
