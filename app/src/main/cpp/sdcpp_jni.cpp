// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
// sdcpp_jni.cpp — Ponko 自编 stable-diffusion.cpp 的 JNI 桥
// 目标：把 sd.cpp 的 C API 暴露给 Kotlin（可控线程数 / 采样器 / 调度器 / LoRA / 量化类型 / 取消）
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <utility>
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
    jmethodID cb_mid = nullptr; // onStep(IIZ)V
    jobject   pcb = nullptr;    // PreviewCallback（GlobalRef）
    jmethodID pcb_mid = nullptr; // onPreview(III[B)V
    std::atomic<bool> cancelled{false};

    // ---- 进度回调的"这是哪个阶段" ----
    //
    // sd.cpp 的 sd_progress_cb 是**全局唯一**的，而它被两处复用：
    //   · 采样循环      diffusion_engine.cpp  pretty_progress(step, total_steps)
    //   · 权重加载      model_loader.cpp      pretty_bytes_progress(张量数, 总张量数)
    //
    // 更麻烦的是权重是**惰性**加载的（mmap）：真正的 materialize 发生在
    // 第一次推理内部，所以那段张量进度也走我们这个回调。
    //
    // 结果：不做区分的话，日志里"加载 178 个张量"和"采样第 3 步"长得一模一样，
    // 用户和我都以为是采样步数 —— 2026-10-25 issue #3 就是这么被误读的。
    //
    // 判别依据：采样进度报告的总数**就等于请求的步数**，而加载进度报告的是
    // 某个文件的张量数（几十到几百）。用 req_steps 比对即可。
    // 一旦匹配上就锁存 sampling_started，避免"某文件恰好也有 N 个张量"在采样开始后误判。
    int  req_steps        = 0;
    bool sampling_started = false;
};

SdHandle* g_active = nullptr;  // 同时只允许一个生成（进度回调是全局的）

// 最近一次 new_sd_ctx 的参数字符串（供 Kotlin 写进日志文件，方便定位加载失败）
std::string g_last_dump;

// ==================== sd.cpp 日志回传 ====================
//
// 为什么必须做这件事：sd.cpp 的所有 LOG_WARN / LOG_ERROR 都走 sd_set_log_callback，
// 而那个回调默认是 nullptr（util.cpp:595）。不注册，消息就被 if (sd_log_cb) 直接丢掉，
// 连 logcat 都进不去。于是这些最关键的诊断信息一条都到不了任何人手里：
//
//   skip incompatible LoRA tensor |x|: model shape = [...], LoRA shape = [...]
//   model manager skipping direct lora application to row-split tensors
//   required lora load failed: ...
//
// 结果就是「LoRA 挂上去了、出图不对」，从外部完全看不出是张量被跳过、还是根本没加载。
//
// 只收 WARN 和 ERROR：INFO 级别每条张量都要打一行（sd3 有几百个），会把有用的淹掉。
// 环形缓冲 + 去重，避免同一个警告重复几百遍撑爆日志文件。
constexpr size_t kMaxWarnLines = 40;

std::mutex g_log_mutex;
std::vector<std::string> g_warn_lines;
std::set<std::string> g_warn_seen;

void sd_log_trampoline(enum sd_log_level_t level, const char* text, void* /*data*/) {
    if (text == nullptr) return;
    // 默认只收 WARN/ERROR，但有几条 INFO 是「LoRA 到底有没有生效」的唯一答案：
    //   runtime_lora_models:            ← 只有 runtime_lora_models 非空才打
    //   (N / M) LoRA tensors have been applied
    //   apply_loras completed
    // 少了它们，「LoRA 挂了但完全没作用」和「LoRA 一切正常」在日志里长得一模一样 ——
    // lora_stat() 的第一句就是 if (!runtime_lora_models.empty())，空的时候一个字都不输出。
    bool interesting = level >= SD_LOG_WARN;
    if (!interesting && level == SD_LOG_INFO) {
        std::string lower(text);
        for (auto& c : lower) c = static_cast<char>(::tolower(static_cast<unsigned char>(c)));
        interesting = lower.find("lora") != std::string::npos;
    }
    if (!interesting) return;
    std::string line(text);
    // 去掉末尾换行，日志文件里每行一条更整齐
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
    if (line.empty()) return;

    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (!g_warn_seen.insert(line).second) return;  // 同一条只留一次
    if (g_warn_lines.size() >= kMaxWarnLines) return;
    const char* tag = level == SD_LOG_ERROR ? "E: " : (level == SD_LOG_WARN ? "W: " : "I: ");
    g_warn_lines.push_back(tag + line);
    __android_log_print(level == SD_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                        TAG, "sd.cpp %s", line.c_str());
}

/** 取出并清空累积的警告。Kotlin 在每次出图结束时调用，写进 DRAW 日志。 */
std::string take_warn_lines() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_warn_lines.empty()) return "";
    std::string out;
    for (const auto& l : g_warn_lines) {
        if (!out.empty()) out += "\n";
        out += l;
    }
    g_warn_lines.clear();
    g_warn_seen.clear();
    return out;
}


const char* jstr(JNIEnv* env, jstring s, std::string& holder) {
    if (s == nullptr) return nullptr;
    const char* c = env->GetStringUTFChars(s, nullptr);
    holder.assign(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return holder.c_str();
}

// 进度回调：由 native 推理线程调用 → 需要 attach 到 JVM
//
// 这里要把**采样进度**和**权重加载进度**区分开（两者共用同一个全局回调）。
// 判据见 SdHandle 的注释：报告的总数 == 请求的采样步数 → 这是采样。
void progressTrampoline(int step, int steps, float /*time*/, void* data) {
    SdHandle* h = static_cast<SdHandle*>(data);
    if (!h || !h->cb || !h->cb_mid) return;
    if (h->cancelled.load()) return;

    // steps 是本次回调报告的总数：
    //   == req_steps  → 采样循环
    //   != req_steps  → 某个模型文件的张量加载
    // 锁存：采样一旦开始就不再回到加载态，避免采样中途某个数恰好相等时误判。
    if (h->req_steps > 0 && steps == h->req_steps) {
        h->sampling_started = true;
    }
    const bool sampling = h->sampling_started;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
        attached = true;
    }
    env->CallVoidMethod(h->cb, h->cb_mid,
                        static_cast<jint>(step), static_cast<jint>(steps),
                        sampling ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

// 实时预览回调：native 推理线程每步调用一次。frames 指向的内存回调返回后立即释放，
// 必须当场拷成 jbyteArray 抛给 Kotlin。
void previewTrampoline(int step, int frame_count, sd_image_t* frames, bool /*is_noisy*/, void* data) {
    SdHandle* h = static_cast<SdHandle*>(data);
    if (!h || !h->pcb || !h->pcb_mid) return;
    if (h->cancelled.load()) return;
    if (frames == nullptr || frame_count <= 0) return;
    const sd_image_t& im = frames[0];
    if (im.data == nullptr || im.width == 0 || im.height == 0 || im.channel != 3) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
        attached = true;
    }
    const jsize n = static_cast<jsize>(im.width * im.height * 3u);
    jbyteArray arr = env->NewByteArray(n);
    if (arr != nullptr) {
        env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte*>(im.data));
        env->CallVoidMethod(h->pcb, h->pcb_mid, static_cast<jint>(step),
                            static_cast<jint>(im.width), static_cast<jint>(im.height), arr);
        env->DeleteLocalRef(arr);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    // 注册 sd.cpp 的日志回调：不注册的话 WARN/ERROR 全被 util.cpp 的 if (sd_log_cb) 丢掉。
    // 放在 OnLoad 里一次注册即可 —— 回调是全局的，与具体 ctx 无关。
    sd_set_log_callback(sd_log_trampoline, nullptr);
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

// 取走自上次调用以来 sd.cpp 报的 WARN/ERROR（每行一条），取完即清空。
// Kotlin 在每次出图结束（成功或失败）时调用，拼进 DRAW 诊断日志。
extern "C" JNIEXPORT jstring JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeTakeWarnings(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(take_warn_lines().c_str());
}

// nativeRefImagePreset(handle) -> 模型自带的参考图预设名；不支持参考图时返回 null。
//
// 由界面用它决定要不要显示「参考图」入口。硬编码家族列表在 Kotlin 里迟早会和 sd.cpp
// 漂移（比如 FLUX 家族里只有 Kontext 变体接受参考图），所以直接问 native。
// 参考图交给没有预设的架构是有害的：pass_to_dit 默认 true，会在模型没预期的情况下
// 改变图结构。
extern "C" JNIEXPORT jstring JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeRefImagePreset(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    SdHandle* h = reinterpret_cast<SdHandle*>(handle);
    if (h == nullptr || h->ctx == nullptr) return nullptr;
    const char* preset = sd_get_default_ref_image_preset(h->ctx);
    if (preset == nullptr || preset[0] == '\0') return nullptr;
    return env->NewStringUTF(preset);
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
    SLOT_TAESD,           // 可选：TAESD 小自编码器（替代 VAE 做编解码，CPU 出图更快）
    SLOT_COUNT
};

// nativeCreate(modelPath, vaePath, nThreads, wtype, flashAttn, backend, slots) -> handle
//
// backend：ggml 后端/设备名（如 "CPU" / "Vulkan0" / "vulkan"），空串或 null = 交给 sd.cpp 自动挑。
// 显式传值会关掉 sd.cpp 的 auto_fit，行为更可控（选 CPU 就真是 CPU）。
//
// slots：模型槽位路径数组，按 SlotIndex 顺序给槽位文件路径（当前 13 项）；
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
    // TAESD：非空即启用（sd.cpp 里加载成功就把编解码换成 TAE，不再走上面的 vae_path）。
    // 需要含编码器 + 解码器的完整权重——图生图要用编码器，纯解码器权重在 Kotlin 侧就被拦下了。
    p.taesd_path            = slotPtr[SLOT_TAESD];
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
//                cb, pcb, initData, initWidth, initHeight, strength,
//                refData, refWidths, refHeights) -> IntArray(ARGB)
//
// initData 非空 → 图生图（img2img）：RGB888 交错字节（每像素 3 字节，行优先，左上原点），
// sd.cpp 会把它按 request 尺寸缩放后编码进 latent，并按 strength(<1) 决定起始噪声步数。
// initData 为空 → 文生图，strength 忽略。
//
// refData 非空 → 参考图（ref_images）。**这跟 initData 不是一回事**，别混：
//   init_image 是「起始 latent 加噪后重绘」，一张，带 strength；
//   ref_images 是「作为独立 token 交给 DiT」，可以多张，强度由模型自带的
//   ref_image_preset 决定（Krea2 → krea2_ostris_edit，即 timestep 0 的 style reference）。
// 两者可以同时给，但正常情况下只需其一。
extern "C" JNIEXPORT jintArray JNICALL
Java_com_litertchat_app_draw_SdCppEngine_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle,
        jstring prompt, jstring negative,
        jstring loraPath, jfloat loraScale,
        jint width, jint height, jint steps, jfloat cfg, jlong seed,
        jint sampleMethod, jint scheduler,
        jobject cb, jobject pcb,
        jbyteArray initData, jint initWidth, jint initHeight, jfloat strength,
        jobjectArray refData, jintArray refWidths, jintArray refHeights) {
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
        // 第三个参数 sampling 区分采样进度 / 权重加载进度，见 SdHandle 注释
        h->cb_mid = env->GetMethodID(cls, "onStep", "(IIZ)V");
        if (h->cb_mid == nullptr) {
            LOGE("StepCallback.onStep(IIZ)V not found");
            env->DeleteGlobalRef(h->cb);
            h->cb = nullptr;
        }
    }
    // 预览回调：每步把 latent 投影成小图（PREVIEW_PROJ 不需要额外模型）
    if (pcb != nullptr) {
        h->pcb = env->NewGlobalRef(pcb);
        jclass pcls = env->GetObjectClass(pcb);
        h->pcb_mid = env->GetMethodID(pcls, "onPreview", "(III[B)V");
        if (h->pcb_mid == nullptr) {
            LOGE("PreviewCallback.onPreview(III[B)V not found");
            env->DeleteGlobalRef(h->pcb);
            h->pcb = nullptr;
        } else {
            sd_set_preview_callback(previewTrampoline, PREVIEW_PROJ, 1, true, false, h);
        }
    } else {
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, true, false, nullptr);
    }
    h->cancelled.store(false);
    // 每次推理都要重置阶段判定：上一次的 sampling_started 不能带过来，
    // 否则本次的权重加载会被误标成采样进度。
    h->req_steps        = steps;
    h->sampling_started = false;
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

    // ---- 参考图（ref_images）----
    // 每张都是 RGB888 交错字节，指针在 generate_image 期间必须有效 → 统一在函数末尾释放。
    std::vector<sd_image_t> refImages;
    std::vector<std::pair<jbyteArray, jbyte*>> refHolds;
    if (refData != nullptr) {
        const jsize refN = env->GetArrayLength(refData);
        std::vector<jint> rw(static_cast<size_t>(refN), 0);
        std::vector<jint> rh(static_cast<size_t>(refN), 0);
        if (refWidths != nullptr) env->GetIntArrayRegion(refWidths, 0, refN, rw.data());
        if (refHeights != nullptr) env->GetIntArrayRegion(refHeights, 0, refN, rh.data());
        refImages.reserve(static_cast<size_t>(refN));
        for (jsize i = 0; i < refN; ++i) {
            auto arr = static_cast<jbyteArray>(env->GetObjectArrayElement(refData, i));
            if (arr == nullptr) continue;
            jbyte* bytes    = env->GetByteArrayElements(arr, nullptr);
            const jsize len = env->GetArrayLength(arr);
            const size_t need = static_cast<size_t>(rw[i]) * static_cast<size_t>(rh[i]) * 3;
            if (bytes == nullptr || rw[i] <= 0 || rh[i] <= 0 || static_cast<size_t>(len) < need) {
                LOGE("ref image %d rejected: %dx%d, %d bytes (need %zu)", i, rw[i], rh[i], len, need);
                if (bytes != nullptr) env->ReleaseByteArrayElements(arr, bytes, JNI_ABORT);
                env->DeleteLocalRef(arr);
                continue;
            }
            sd_image_t img;
            img.width   = static_cast<uint32_t>(rw[i]);
            img.height  = static_cast<uint32_t>(rh[i]);
            img.channel = 3;
            img.data    = reinterpret_cast<uint8_t*>(bytes);
            refImages.push_back(img);
            refHolds.emplace_back(arr, bytes);
        }
        if (!refImages.empty()) {
            g.ref_images       = refImages.data();
            g.ref_images_count = static_cast<int>(refImages.size());
            LOGI("ref images: %d", static_cast<int>(refImages.size()));
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

    // sd.cpp 新版把"出图 + 张数"合并成一个结果对象，失败时置 nullptr 并由调用方释放。
    sd_image_generation_result_t* res = nullptr;
    bool ok = generate_image(h->ctx, &g, &res);
    const int n = (res != nullptr) ? res->count : 0;
    sd_image_t* out = (n > 0) ? &res->items[0].image : nullptr;

    jintArray result = nullptr;
    if (ok && out != nullptr && out->data != nullptr) {
        const sd_image_t& img = *out;
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
    } else {
        LOGE("generate_image failed (ok=%d, n=%d)", ok ? 1 : 0, n);
    }
    if (res != nullptr) free_sd_image_generation_result(res);

    if (initBytes != nullptr && initData != nullptr) {
        env->ReleaseByteArrayElements(initData, initBytes, JNI_ABORT);
    }
    for (auto& hold : refHolds) {
        env->ReleaseByteArrayElements(hold.first, hold.second, JNI_ABORT);
        env->DeleteLocalRef(hold.first);
    }
    if (h->cb) { env->DeleteGlobalRef(h->cb); h->cb = nullptr; h->cb_mid = nullptr; }
    if (h->pcb) { env->DeleteGlobalRef(h->pcb); h->pcb = nullptr; h->pcb_mid = nullptr; }
    sd_set_progress_callback(nullptr, nullptr);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, true, false, nullptr);
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
    if (h->pcb != nullptr) env->DeleteGlobalRef(h->pcb);
    delete h;
    LOGI("ctx freed");
}
