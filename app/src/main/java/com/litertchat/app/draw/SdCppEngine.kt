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

    external fun nativeCreate(modelPath: String, vaePath: String?, nThreads: Int, wtype: Int, flashAttn: Boolean, backend: String?): Long

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
        initData: ByteArray?,
        initWidth: Int,
        initHeight: Int,
        strength: Float,
    ): IntArray?

    external fun nativeCancel(handle: Long)

    /** ggml 后端设备清单：每行 "name\tdescription"（如 "Vulkan0\tAdreno (TM) 650"）。 */
    external fun nativeListDevices(): String

    external fun nativeFree(handle: Long)

    fun info(): String = runCatching { nativeInfo() }.getOrDefault("(n/a)")

    /** 最近一次 new_sd_ctx 用的参数（多行文本） */
    fun lastParams(): String = runCatching { nativeLastParams() }.getOrDefault("")

    /** 后端设备清单（名字 -> 描述）；取不到时返回空表。 */
    fun listDevices(): List<Pair<String, String>> =
        runCatching { nativeListDevices() }.getOrDefault("")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val i = line.indexOf('\t')
                if (i < 0) line to "" else line.substring(0, i) to line.substring(i + 1)
            }
            .toList()

    /** 是否有可用的 Vulkan(GPU) 设备；有则返回设备名（如 "Vulkan0"）。 */
    fun vulkanDevice(): String? =
        listDevices().firstOrNull { it.first.contains("vulkan", true) }?.first

    /** 加载模型并返回 handle；0 表示失败。
     *
     *  flashAttn：CLIP 与 UNet 的 FlashAttention。实测不开会让每步慢约 28%，默认开。
     *  backend：ggml 后端/设备名（"CPU" / "Vulkan0"）；null = 交给 sd.cpp 自动挑。
     */
    fun create(
        modelPath: String,
        vaePath: String?,
        nThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
        wtype: Int = WTYPE_KEEP,
        flashAttn: Boolean = true,
        backend: String? = null,
    ): Long = nativeCreate(modelPath, vaePath, nThreads, wtype, flashAttn, backend)

    /** 生成一张图；失败返回 null。
     *
     *  initImage 非空则会走图生图（img2img）：它会被缩放到 width×height 后作为初始 latent，
     *  strength(<1) 决定从第几步开始去噪（越小越接近原图）。
     */
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
        initImage: ByteArray? = null,
        initWidth: Int = 0,
        initHeight: Int = 0,
        strength: Float = 1.0f,
    ): Bitmap? {
        val effectiveSeed = if (seed < 0) (System.currentTimeMillis() % 1_000_000_000L) else seed
        val useInit = initImage != null && initImage.isNotEmpty() && initWidth > 0 && initHeight > 0
        val px = nativeGenerate(
            handle, prompt, negative, loraPath, loraScale,
            width, height, steps, cfg, effectiveSeed,
            sampler.code, scheduler.code, cb,
            if (useInit) initImage else null,
            if (useInit) initWidth else 0,
            if (useInit) initHeight else 0,
            if (useInit) strength else 1.0f,
        ) ?: return null
        if (px.isEmpty()) return null
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Bitmap → RGB888 交错字节（每像素 3 字节，行优先，左上原点）。
     * sd.cpp 的 init_image 就吃这个布局（见 sd_image_get_f32）。
     */
    fun bitmapToRgb888(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        var j = 0
        for (i in px.indices) {
            val p = px[i]
            out[j++] = ((p shr 16) and 0xFF).toByte()
            out[j++] = ((p shr 8) and 0xFF).toByte()
            out[j++] = (p and 0xFF).toByte()
        }
        return out
    }
}
