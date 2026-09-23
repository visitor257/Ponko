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

// nativeListDevices() -> "name\tdescription\n..."（ggml 后端设备清单；没有设备时返回空串）
extern "C" JNIEXPORT jstring JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeListDevices(JNIEnv* env, jobject /*thiz*/) {
    const size_t need = sd_list_devices(nullptr, 0);
    if (need == 0) return env->NewStringUTF("");
    std::vector<char> buf(need + 1, '\0');
    sd_list_devices(buf.data(), buf.size());
    return env->NewStringUTF(buf.data());
}

// 槽位顺序（与 Kotlin 侧 SdCppEngine.SLOT_KEYS 一一对应）
enum SlotIndex {
    SLOT_MODEL = 0,       // 单文件 all-in-one -> model_path
    SLOT_DIFFUSION,       // DiT/UNet 单独文件 -> diffusion_model_path
    SLOT_HIGH_NOISE,      // 高噪扩散模型
    SLOT_UNCOND,          // 无条件扩散模型
    SLOT_CLIP_L,
    SLOT_CLIP_G,
    SLOT_CLIP_VISION,
    SLOT_T5XXL,
    SLOT_LLM,
    SLOT_LLM_VISION,
    SLOT_VAE,
    SLOT_AUDIO_VAE,
    SLOT_COUNT
};

// nativeCreate(modelPath, vaePath, nThreads, wtype, flashAttn, backend, slots) -> handle
//
// backend：ggml 后端/设备名（如 "CPU" / "Vulkan0" / "vulkan"），空串或 null = 交给 sd.cpp 自动挑。
// 显式传值会关掉 sd.cpp 的 auto_fit，行为更可控（选 CPU 就真是 CPU）。
//
// slots：多文件模型用。长度 12 的字符串数组，按 SlotIndex 顺序给槽位文件路径；
// 元素可以为 null / 空串（表示该槽位不填）。传 null = 走旧的两参数路径（单文件 + 可选 VAE）。
extern "C" JNIEXPORT jlong JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeCreate(
        JNIEnv* env, jobject /*thiz*/,
        jstring modelPath, jstring vaePath, jint nThreads, jint wtype, jboolean flashAttn,
        jstring backend, jobjectArray slots) {
    std::string model, vae, backendStr;
    jstr(env, modelPath, model);
    jstr(env, vaePath, vae);
    jstr(env, backend, backendStr);
    if (modelPath == nullptr && slots == nullptr) return 0;

    // 槽位路径（std::string 持有内容，new_sd_ctx 期间必须有效）
    std::vector<std::string> slotStr(SLOT_COUNT);
    std::vector<const char*> slotPtr(SLOT_COUNT, nullptr);
    if (slots != nullptr) {
        const jsize n = env->GetArrayLength(slots);
        for (jsize i = 0; i < n && i < SLOT_COUNT; ++i) {
            jstring js = static_cast<jstring>(env->GetObjectArrayElement(slots, i));
            if (js == nullptr) continue;
            const char* cs = env->GetStringUTFChars(js, nullptr);
            if (cs != nullptr && cs[0] != '\0') {
                slotStr[i] = cs;
                slotPtr[i] = slotStr[i].c_str();
            }
            if (cs != nullptr) env->ReleaseStringUTFChars(js, cs);
            env->DeleteLocalRef(js);
        }
        bool any = false;
        for (int i = 0; i < SLOT_COUNT; ++i) if (slotPtr[i] != nullptr) any = true;
        if (!any) return 0;
    }

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    const char* modelPathC = slotPtr[SLOT_MODEL] != nullptr ? slotPtr[SLOT_MODEL]
                           : (model.empty() ? nullptr : model.c_str());
    const char* vaePathC   = slotPtr[SLOT_VAE] != nullptr ? slotPtr[SLOT_VAE]
                           : (vae.empty() ? nullptr : vae.c_str());
    p.model_path            = modelPathC;
    p.diffusion_model_path  = slotPtr[SLOT_DIFFUSION];
    p.high_noise_diffusion_model_path = slotPtr[SLOT_HIGH_NOISE];
    p.uncond_diffusion_model_path     = slotPtr[SLOT_UNCOND];
    p.clip_l_path           = slotPtr[SLOT_CLIP_L];
    p.clip_g_path           = slotPtr[SLOT_CLIP_G];
    p.clip_vision_path      = slotPtr[SLOT_CLIP_VISION];
    p.t5xxl_path            = slotPtr[SLOT_T5XXL];
    p.llm_path              = slotPtr[SLOT_LLM];
    p.llm_vision_path       = slotPtr[SLOT_LLM_VISION];
    p.vae_path              = vaePathC;
    p.audio_vae_path        = slotPtr[SLOT_AUDIO_VAE];
    p.n_threads    = (nThreads > 0) ? nThreads : sd_get_num_physical_cores();
    // wtype < 0 表示「保持模型原样」——此时【不能】覆盖，保留 sd_ctx_params_init 给的
    // SD_TYPE_COUNT。实测把 -1 强转成 sd_type_t 会让 sd.cpp 内部查表越界，native 直接崩溃。
    if (wtype >= 0) p.wtype = static_cast<enum sd_type_t>(wtype);
    p.enable_mmap  = true;
    // 后端：非空则显式指定（同时关掉 auto_fit），空则保持 sd.cpp 默认（自动挑设备）
    if (!backendStr.empty()) p.backend = backendStr.c_str();
    // 两个 FlashAttention 都要开：flash_attn 影响 CLIP，diffusion_flash_attn 影响 UNet（算力主体）。
    // 实测不开比开慢约 28%（6 步 125s vs 98s）。注意 sd_ctx_params_init 默认都是 false。
    const bool fa = (flashAttn == JNI_TRUE);
    p.flash_attn           = fa;
    p.diffusion_flash_attn = fa;
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
//                width, height, steps, cfg, seed, sampleMethod, scheduler,
//                cb, initData, initWidth, initHeight, strength) -> IntArray(ARGB)
//
// initData 非空 → 图生图（img2img）：RGB888 交错字节（每像素 3 字节，行优先，左上原点），
// sd.cpp 会把它按 request 尺寸缩放后编码进 latent，并按 strength(<1) 决定起始噪声步数。
// initData 为空 → 文生图，strength 忽略。
extern "C" JNIEXPORT jintArray JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle,
        jstring prompt, jstring negative,
        jstring loraPath, jfloat loraScale,
        jint width, jint height, jint steps, jfloat cfg, jlong seed,
        jint sampleMethod, jint scheduler,
        jobject cb,
        jbyteArray initData, jint initWidth, jint initHeight, jfloat strength) {
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

    // 图生图：拿到 RGB888 原始字节，指针在 generate_image 期间必须保持有效。
    // 生成结束（含失败）后统一在下方 ReleaseByteArrayElements 释放。
    jbyte* initBytes = nullptr;
    if (initData != nullptr && initWidth > 0 && initHeight > 0) {
        initBytes = env->GetByteArrayElements(initData, nullptr);
        if (initBytes != nullptr) {
            g.init_image.width   = static_cast<uint32_t>(initWidth);
            g.init_image.height  = static_cast<uint32_t>(initHeight);
            g.init_image.channel = 3;
            g.init_image.data    = reinterpret_cast<uint8_t*>(initBytes);
            // strength 夹到 (0,1)：=1 会退化成「全部重绘」（等价文生图，但白跑一遍 VAE 编码）
            float s = strength;
            if (!(s > 0.f)) s = 0.01f;
            if (s >= 1.f)   s = 0.99f;
            g.strength = s;
            LOGI("img2img: init %dx%d, strength=%.2f", initWidth, initHeight, s);
        } else {
            LOGE("img2img: GetByteArrayElements returned null");
        }
    }
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

    if (initBytes != nullptr && initData != nullptr) {
        env->ReleaseByteArrayElements(initData, initBytes, JNI_ABORT);
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
