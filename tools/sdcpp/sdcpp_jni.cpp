// sdcpp_jni.cpp — Ponko 自编 stable-diffusion.cpp 的 JNI 桥
// 目标：把 sd.cpp 的 C API 暴露给 Kotlin（可控线程数 / 采样器 / 调度器 / LoRA / 量化类型 / 取消）
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define TAG "PonkoSd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;

struct SdHandle {
    sd_ctx_t* ctx = nullptr;
    jobject   cb  = nullptr;    // StepCallback（GlobalRef）
    jmethodID cb_mid = nullptr; // onStep(II)V
    std::atomic<bool> cancelled{false};
};

SdHandle* g_active = nullptr;  // 同时只允许一个生成（进度回调是全局的）

// 最近一次 new_sd_ctx 的参数字符串（供 Kotlin 写进日志文件，方便定位加载失败）
std::string g_last_dump;

const char* jstr(JNIEnv* env, jstring s, std::string& holder) {
    if (s == nullptr) return nullptr;
    const char* c = env->GetStringUTFChars(s, nullptr);
    holder.assign(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return holder.c_str();
}

// 进度回调：由 native 推理线程调用 → 需要 attach 到 JVM
void progressTrampoline(int step, int steps, float /*time*/, void* data) {
    SdHandle* h = static_cast<SdHandle*>(data);
    if (!h || !h->cb || !h->cb_mid) return;
    if (h->cancelled.load()) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
        attached = true;
    }
    env->CallVoidMethod(h->cb, h->cb_mid, static_cast<jint>(step), static_cast<jint>(steps));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeInfo(JNIEnv* env, jobject /*thiz*/) {
    std::string info = std::string("sd.cpp ") + sd_version() + " | " + sd_get_system_info();
    return env->NewStringUTF(info.c_str());
}

// 最近一次 new_sd_ctx 用的完整参数（失败时写进日志文件用）
extern "C" JNIEXPORT jstring JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeLastParams(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(g_last_dump.c_str());
}

// nativeCreate(modelPath, vaePath, nThreads, wtype) -> handle
extern "C" JNIEXPORT jlong JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeCreate(
        JNIEnv* env, jobject /*thiz*/,
        jstring modelPath, jstring vaePath, jint nThreads, jint wtype) {
    if (modelPath == nullptr) return 0;

    std::string model, vae;
    jstr(env, modelPath, model);
    jstr(env, vaePath, vae);

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path   = model.c_str();
    p.vae_path     = vae.empty() ? nullptr : vae.c_str();
    p.n_threads    = (nThreads > 0) ? nThreads : sd_get_num_physical_cores();
    // wtype < 0 表示「保持模型原样」——此时【不能】覆盖，保留 sd_ctx_params_init 给的
    // SD_TYPE_COUNT。实测把 -1 强转成 sd_type_t 会让 sd.cpp 内部查表越界，native 直接崩溃。
    if (wtype >= 0) p.wtype = static_cast<enum sd_type_t>(wtype);
    p.enable_mmap  = true;
    p.flash_attn   = false;     // 华为/Mali 上不稳，先关
    p.lora_apply_mode = LORA_APPLY_AUTO;

    char* dump = sd_ctx_params_to_str(&p);
    g_last_dump = dump ? dump : "";
    LOGI("new_sd_ctx: %s", dump ? dump : "(null)");
    if (dump) free(dump);

    sd_ctx_t* ctx = new_sd_ctx(&p);
    if (ctx == nullptr) {
        LOGE("new_sd_ctx returned null");
        return 0;
    }
    SdHandle* h = new SdHandle();
    h->ctx = ctx;
    LOGI("ctx created: %p", static_cast<void*>(ctx));
    return reinterpret_cast<jlong>(h);
}

// nativeGenerate(handle, prompt, negative, loraPath, loraScale,
//                width, height, steps, cfg, seed, sampleMethod, scheduler, cb) -> IntArray(ARGB)
extern "C" JNIEXPORT jintArray JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle,
        jstring prompt, jstring negative,
        jstring loraPath, jfloat loraScale,
        jint width, jint height, jint steps, jfloat cfg, jlong seed,
        jint sampleMethod, jint scheduler,
        jobject cb) {
    SdHandle* h = reinterpret_cast<SdHandle*>(handle);
    if (h == nullptr || h->ctx == nullptr) return nullptr;

    std::string sPrompt, sNeg, sLora;
    jstr(env, prompt, sPrompt);
    jstr(env, negative, sNeg);
    jstr(env, loraPath, sLora);

    // 进度回调（全局注册，data 指向本 handle）
    if (cb != nullptr) {
        h->cb = env->NewGlobalRef(cb);
        jclass cls = env->GetObjectClass(cb);
        h->cb_mid = env->GetMethodID(cls, "onStep", "(II)V");
        if (h->cb_mid == nullptr) {
            LOGE("StepCallback.onStep(II)V not found");
            env->DeleteGlobalRef(h->cb);
            h->cb = nullptr;
        }
    }
    h->cancelled.store(false);
    g_active = h;
    sd_set_progress_callback(progressTrampoline, h);

    sd_lora_t lora{};
    bool useLora = !sLora.empty();

    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    g.prompt          = sPrompt.c_str();
    g.negative_prompt = sNeg.empty() ? nullptr : sNeg.c_str();
    g.width           = width;
    g.height          = height;
    g.seed            = seed;
    g.batch_count     = 1;
    g.strength        = 1.0f;
    g.clip_skip       = -1;
    if (useLora) {
        lora.is_high_noise = false;
        lora.multiplier    = loraScale;
        lora.path          = sLora.c_str();
        g.loras            = &lora;
        g.lora_count       = 1;
    }
    g.sample_params.sample_steps  = steps;
    g.sample_params.guidance.txt_cfg = cfg;
    g.sample_params.sample_method = static_cast<enum sample_method_t>(sampleMethod);
    g.sample_params.scheduler     = static_cast<enum scheduler_t>(scheduler);

    char* gdump = sd_img_gen_params_to_str(&g);
    LOGI("generate_image: %s", gdump ? gdump : "(null)");
    if (gdump) free(gdump);

    sd_image_t* out = nullptr;
    int n = 0;
    bool ok = generate_image(h->ctx, &g, &out, &n);
    if (n > 0 && out != nullptr && out->data != nullptr) ok = true;

    jintArray result = nullptr;
    if (ok && n > 0 && out != nullptr && out[0].data != nullptr) {
        const sd_image_t& img = out[0];
        const int w = static_cast<int>(img.width);
        const int hh = static_cast<int>(img.height);
        const int ch = static_cast<int>(img.channel);
        const int total = w * hh;
        std::vector<jint> px(static_cast<size_t>(total));
        for (int i = 0; i < total; ++i) {
            const uint8_t* s = img.data + static_cast<size_t>(i) * ch;
            jint r, gg, b;
            if (ch >= 3) { r = s[0]; gg = s[1]; b = s[2]; }
            else         { r = gg = b = s[0]; }
            px[static_cast<size_t>(i)] =
                static_cast<jint>(0xFF000000u | (static_cast<uint32_t>(r) << 16) |
                                  (static_cast<uint32_t>(gg) << 8) | static_cast<uint32_t>(b));
        }
        result = env->NewIntArray(total);
        env->SetIntArrayRegion(result, 0, total, px.data());
        free_sd_images(out, n);
    } else {
        if (out) free_sd_images(out, n);
        LOGE("generate_image failed (ok=%d, n=%d)", ok ? 1 : 0, n);
    }

    if (h->cb) { env->DeleteGlobalRef(h->cb); h->cb = nullptr; h->cb_mid = nullptr; }
    sd_set_progress_callback(nullptr, nullptr);
    g_active = nullptr;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeCancel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    SdHandle* h = reinterpret_cast<SdHandle*>(handle);
    if (h == nullptr) return;
    h->cancelled.store(true);
    if (h->ctx != nullptr) sd_cancel_generation(h->ctx, SD_CANCEL_ALL);
    LOGI("cancel requested");
}

extern "C" JNIEXPORT void JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeFree(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    SdHandle* h = reinterpret_cast<SdHandle*>(handle);
    if (h == nullptr) return;
    if (h->ctx != nullptr) free_sd_ctx(h->ctx);
    if (h->cb != nullptr) env->DeleteGlobalRef(h->cb);
    delete h;
    LOGI("ctx freed");
}
