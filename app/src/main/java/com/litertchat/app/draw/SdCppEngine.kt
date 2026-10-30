// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.draw

import android.graphics.Bitmap
import com.litertchat.app.InferConfig

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

        // 以下 7 项 sd.cpp 早已实现（见 runtime/denoiser.hpp 的分发），此前只是 UI 没暴露。
        // 一律追加在末尾：下拉框按声明顺序生成，旧用户偏好里存的是索引，插在中间会错位。
        RES_MULTISTEP(12, "Res Multistep"),
        RES_2S(13, "Res 2S"),
        ER_SDE(14, "ER SDE"),
        EULER_GE(17, "Euler GE"),
        DPMPP2M_SDE(18, "DPM++ 2M SDE"),
        DPMPP2M_SDE_BT(19, "DPM++ 2M SDE B&T"),
        LMS(20, "LMS"),
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
        /**
         * @param sampling true = 采样循环的第 step 步；false = 权重加载的进度。
         *
         * sd.cpp 用**同一个**全局进度回调报告这两件事，而权重是惰性加载的
         * （mmap，真正的 materialize 在第一次推理内部），所以两者会混在一起。
         * 不加这个区分的话，「加载 178 个张量」和「采样第 178 步」长得一样 ——
         * 2026-10-25 issue #3 里我和报告者都误读成了采样步数。
         */
        fun onStep(step: Int, steps: Int, sampling: Boolean)
    }

    /** 生成过程预览：每一步回调一张低分辨率小图（RGB888，行优先，左上原点） */
    fun interface PreviewCallback {
        fun onPreview(step: Int, width: Int, height: Int, rgb: ByteArray)
    }

    external fun nativeInfo(): String

    /** 最近一次 new_sd_ctx 的完整参数（诊断加载失败用） */
    external fun nativeLastParams(): String

    /**
     * 取走 sd.cpp 自上次调用以来报的 WARN/ERROR（每行一条），取完即清空。
     *
     * sd.cpp 的日志回调默认是 nullptr，不注册就整条丢掉 —— 包括
     * 「skip incompatible LoRA tensor」和「skipping ... row-split tensors」这两类
     * 决定 LoRA 是否真的生效的消息。每次出图结束调一次，写进 DRAW 诊断日志。
     */
    external fun nativeTakeWarnings(): String

    /** slots 长度 = SLOT_KEYS.size，元素可为 null（该槽位不填）。 */
    external fun nativeCreate(
        modelPath: String,
        vaePath: String?,
        nThreads: Int,
        wtype: Int,
        flashAttn: Boolean,
        backend: String?,
        slots: Array<String?>?,
    ): Long

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
        pcb: PreviewCallback?,
        initData: ByteArray?,
        initWidth: Int,
        initHeight: Int,
        strength: Float,
        refData: Array<ByteArray>?,
        refWidths: IntArray?,
        refHeights: IntArray?,
    ): IntArray?

    /** 已加载模型自带的参考图预设名；不支持参考图时返回 null（见 nativeRefImagePreset 注释） */
    external fun nativeRefImagePreset(handle: Long): String?

    external fun nativeCancel(handle: Long)

    /** ggml 后端设备清单：每行 "name\tdescription"（如 "Vulkan0\tAdreno (TM) 650"）。 */
    external fun nativeListDevices(): String

    external fun nativeFree(handle: Long)

    fun info(): String = runCatching { nativeInfo() }.getOrDefault("(n/a)")

    /** 最近一次 new_sd_ctx 用的参数（多行文本） */
    fun lastParams(): String = runCatching { nativeLastParams() }.getOrDefault("")

    /** 取走累积的 sd.cpp 警告（每行一条）；没有则返回空串。取完清空。 */
    fun takeWarnings(): String = runCatching { nativeTakeWarnings() }.getOrDefault("")

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
        nThreads: Int = InferConfig.DEFAULT,
        wtype: Int = WTYPE_KEEP,
        flashAttn: Boolean = true,
        backend: String? = null,
        slots: Array<String?>? = null,
    ): Long = nativeCreate(modelPath, vaePath, nThreads, wtype, flashAttn, backend, slots)

    /**
     * 槽位顺序（与 JNI 里的 SlotIndex 一一对应；sd.cpp 的 sd_ctx_params_t 路径字段）。
     * 单文件模型只用 "model"；多文件模型按家族填 diffusion / clip_l / t5xxl / llm / vae 等。
     * "taesd" 是可选的小自编码器权重（替代 VAE 编解码，CPU 出图更快）——追加在末尾，
     * 与 JNI 的 SlotIndex 保持同序。
     */
    val SLOT_KEYS = arrayOf(
        "model", "diffusion", "high_noise", "uncond",
        "clip_l", "clip_g", "clip_vision", "t5xxl",
        "llm", "llm_vision", "vae", "audio_vae", "taesd",
    )

    /** 把槽位表（key -> 文件绝对路径）转成 JNI 要的定长数组 */
    fun buildSlots(map: Map<String, String>): Array<String?> {
        val out = arrayOfNulls<String>(SLOT_KEYS.size)
        for ((k, v) in map) {
            val i = SLOT_KEYS.indexOf(k)
            if (i >= 0) out[i] = v
        }
        return out
    }

    /** 生成一张图；失败返回 null。
     *
     *  initImage 非空则会走图生图（img2img）：它会被缩放到 width×height 后作为初始 latent，
     *  strength(<1) 决定从第几步开始去噪（越小越接近原图）。
     *
     *  refImages 非空则走「参考图」通路：每张作为独立 token 交给 DiT，与 initImage 无关。
     *  只对自带参考图预设的架构有效（Krea2 style reference LoRA、FLUX Kontext 等），
     *  支持与否用 nativeRefImagePreset() 查。
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
        onPreview: PreviewCallback? = null,
        initImage: ByteArray? = null,
        initWidth: Int = 0,
        initHeight: Int = 0,
        strength: Float = 1.0f,
        refImages: List<RefImage> = emptyList(),
    ): Bitmap? {
        val effectiveSeed = if (seed < 0) (System.currentTimeMillis() % 1_000_000_000L) else seed
        val useInit = initImage != null && initImage.isNotEmpty() && initWidth > 0 && initHeight > 0
        val px = nativeGenerate(
            handle, prompt, negative, loraPath, loraScale,
            width, height, steps, cfg, effectiveSeed,
            sampler.code, scheduler.code, cb, onPreview,
            if (useInit) initImage else null,
            if (useInit) initWidth else 0,
            if (useInit) initHeight else 0,
            if (useInit) strength else 1.0f,
            if (refImages.isEmpty()) null else refImages.map { it.rgb }.toTypedArray(),
            if (refImages.isEmpty()) null else refImages.map { it.width }.toIntArray(),
            if (refImages.isEmpty()) null else refImages.map { it.height }.toIntArray(),
        ) ?: return null
        if (px.isEmpty()) return null
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    /** 一张参考图：RGB888 交错字节 + 尺寸 */
    class RefImage(val width: Int, val height: Int, val rgb: ByteArray)

        /** 预览小图（RGB888）→ Bitmap（ARGB_8888）；非法输入返回 null */
    fun rgbToBitmap(width: Int, height: Int, rgb: ByteArray): Bitmap? {
        if (width <= 0 || height <= 0 || rgb.size < width * height * 3) return null
        val px = IntArray(width * height)
        var j = 0
        for (i in px.indices) {
            px[i] = (0xFF shl 24) or ((rgb[j].toInt() and 0xFF) shl 16) or
                    ((rgb[j + 1].toInt() and 0xFF) shl 8) or (rgb[j + 2].toInt() and 0xFF)
            j += 3
        }
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
