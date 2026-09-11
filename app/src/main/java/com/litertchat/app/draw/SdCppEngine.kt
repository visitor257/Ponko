package com.litertchat.app.draw

import android.graphics.Bitmap

/**
 * Ponko 自编 stable-diffusion.cpp（GGUF）引擎。
 *
 * 相比 llmedge，这里能直接控制：线程数、采样器、调度器、LoRA、量化类型、取消。
 */
object SdCppEngine {

    init {
        // 依赖 libc++_shared.so（jniLibs 提供）与 libponko_sd.so（含 sd.cpp + ggml）
        System.loadLibrary("c++_shared")
        System.loadLibrary("ponko_sd")
    }

    /** 采样器（对应 sd.cpp enum sample_method_t） */
    enum class Sampler(val code: Int, val label: String) {
        EULER(0, "Euler"),
        EULER_A(1, "Euler a"),
        HEUN(2, "Heun"),
        DPM2(3, "DPM2"),
        DPMPP2S_A(4, "DPM++ 2S a"),
        DPMPP2M(5, "DPM++ 2M"),
        DPMPP2M_V2(6, "DPM++ 2M v2"),
        IPNDM(7, "IPNDM"),
        IPNDM_V(8, "IPNDM V"),
        LCM(9, "LCM"),
        DDIM_TRAILING(10, "DDIM"),
        TCD(11, "TCD"),
        EULER_CFG_PP(15, "Euler CFG++"),
        EULER_A_CFG_PP(16, "Euler a CFG++"),
    }

    /** 调度器（对应 sd.cpp enum scheduler_t） */
    enum class Scheduler(val code: Int, val label: String) {
        DISCRETE(0, "Discrete"),
        KARRAS(1, "Karras"),
        EXPONENTIAL(2, "Exponential"),
        AYS(3, "AYS"),
        SGM_UNIFORM(5, "SGM Uniform"),
        SIMPLE(6, "Simple"),
        SMOOTHSTEP(7, "Smoothstep"),
        KL_OPTIMAL(8, "KL Optimal"),
        LCM(9, "LCM"),
        BETA(15, "Beta"),
    }

    /** 权重类型（对应 sd.cpp enum sd_type_t）；KEEP = 保持模型原样 */
    const val WTYPE_KEEP = -1
    const val WTYPE_F32 = 0
    const val WTYPE_F16 = 1
    const val WTYPE_Q4_0 = 2
    const val WTYPE_Q4_1 = 3
    const val WTYPE_Q5_0 = 6
    const val WTYPE_Q5_1 = 7
    const val WTYPE_Q8_0 = 8

    fun interface StepCallback {
        fun onStep(step: Int, steps: Int)
    }

    external fun nativeInfo(): String

    /** 最近一次 new_sd_ctx 的完整参数（诊断加载失败用） */
    external fun nativeLastParams(): String

    external fun nativeCreate(modelPath: String, vaePath: String?, nThreads: Int, wtype: Int): Long

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        negative: String,
        loraPath: String?,
        loraScale: Float,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        sampleMethod: Int,
        scheduler: Int,
        cb: StepCallback?,
    ): IntArray?

    external fun nativeCancel(handle: Long)

    external fun nativeFree(handle: Long)

    fun info(): String = runCatching { nativeInfo() }.getOrDefault("(n/a)")

    /** 最近一次 new_sd_ctx 用的参数（多行文本） */
    fun lastParams(): String = runCatching { nativeLastParams() }.getOrDefault("")

    /** 加载模型并返回 handle；0 表示失败。 */
    fun create(
        modelPath: String,
        vaePath: String?,
        nThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
        wtype: Int = WTYPE_KEEP,
    ): Long = nativeCreate(modelPath, vaePath, nThreads, wtype)

    /** 生成一张图；失败返回 null。 */
    fun render(
        handle: Long,
        prompt: String,
        negative: String = "",
        loraPath: String? = null,
        loraScale: Float = 1.0f,
        width: Int = 512,
        height: Int = 512,
        steps: Int = 20,
        cfg: Float = 7.0f,
        seed: Long = -1L,
        sampler: Sampler = Sampler.EULER_A,
        scheduler: Scheduler = Scheduler.DISCRETE,
        cb: StepCallback? = null,
    ): Bitmap? {
        val effectiveSeed = if (seed < 0) (System.currentTimeMillis() % 1_000_000_000L) else seed
        val px = nativeGenerate(
            handle, prompt, negative, loraPath, loraScale,
            width, height, steps, cfg, effectiveSeed,
            sampler.code, scheduler.code, cb,
        ) ?: return null
        if (px.isEmpty()) return null
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }
}
