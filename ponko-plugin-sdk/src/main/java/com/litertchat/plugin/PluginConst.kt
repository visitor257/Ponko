// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

/**
 * 所有 AIDL 常量都放这里 —— 一旦发布，**含义不能改**，只能新增。
 *
 * 机器可读的部分（`@Parcelize` 类）在别的文件；这里只有纯常量，
 * 刻意用普通 `object` 而不是 enum：AIDL 里穿 int 最稳，加值也不会破坏旧插件。
 */
object PluginConst {

    /**
     * AIDL 表面版本。**只在破坏性改动时 +1**（删方法、改参数含义、改字段语义）。
     *
     * ## 为什么加到 2（2026-10-21）
     *
     * v2 新增了插件界面那组方法（`getSettingsSchema` / `setSetting` / `invokeAction`
     * / `onSchemaChanged`）。严格说这属于**向后兼容的扩展** —— 老插件不实现它，
     * 老方法照常可用。但版本号必须 +1，否则宿主没法区分「这个插件实现了界面」
     * 和「这个插件是旧 SDK 编的、根本没有界面」：
     *
     * 老插件由旧 SDK 编译，`API_VERSION` 写死在它里面，只会报 1。
     * 宿主据此就能判断**要不要去调** `getSettingsSchema()`，
     * 而不是靠 try-catch 兜异常 —— 靠异常判断是不可靠的（见下面的注释）。
     *
     * ## 两类不兼容，处理方式不同
     *
     * · **扩展**（加方法、加字段、加常量）→ 版本 +1，老插件继续能用，
     *   宿主按版本判断"哪些新能力可用"。**这是绝大多数改动**。
     * · **破坏**（删方法、改语义）→ 版本 +1 **并把 [API_MIN_SUPPORTED] 跟着抬**，
     *   老插件直接被拒绝。这种事应该尽可能避免。
     *
     * ## 新方法必须加在 AIDL 的**最末尾**
     *
     * Binder 的事务码按声明顺序分配。在中间插入会让后面所有方法的事务码整体后移，
     * 老插件收到的码对不上 → **调用串味**（本该调 A 却执行了 B），比直接报错更糟。
     */
    const val API_VERSION = 2

    /**
     * 从这一版起，插件支持「界面」那组方法（`getSettingsSchema` 等）。
     *
     * 宿主用它决定**要不要调**：`pluginApi < API_WITH_SCHEMA` 就直接跳过，
     * 显示"该插件由旧版 SDK 构建，没有界面"。
     *
     * 为什么不靠 try-catch 兜：AIDL 调用遇到不认识的函数时，生成代码的行为
     * 不保证抛异常 —— 同步方法可能读到空 Parcel 而返回 null 或奇怪的值，
     * oneway 方法则**静默无操作**。把它当异常处理是不可靠的。
     */
    const val API_WITH_SCHEMA = 2

    /** 宿主兼容区间：允许老插件继续跑，所以下限保持 1 */
    const val API_MIN_SUPPORTED = 1
    const val API_MAX_SUPPORTED = 2

    // ---- 插件 Action（宿主靠它发现插件）----
    const val ACTION_PLUGIN = "com.litertchat.ponko.PLUGIN"

    // ---- 宿主定义的 signature 级权限（只有同签名 APK 能 bind）----
    const val PERMISSION_BIND_PLUGIN = "com.litertchat.ponko.permission.BIND_PLUGIN"

    // ---- 模型文件角色 ----
    // 单文件模型的唯一文件
    const val ROLE_MAIN = "main"
    // VoxCPM2 主干（BaseLM）
    const val ROLE_BASE_LM = "base_lm"
    // VoxCPM2 声学模型 / AudioVAE
    const val ROLE_ACOUSTIC = "acoustic"
    // GGUF 视觉投影
    const val ROLE_MMPROJ = "mmproj"
    const val ROLE_VAE = "vae"
    const val ROLE_TOKENIZER = "tokenizer"

    // ---- 合成模式（对应 VoxCPM2 的 mode 0/1/2）----
    /** 纯 TTS；可用 [SynthesisRequest.voiceDescription] 做语音设计 */
    const val MODE_PLAIN = 0
    /** 参考音频克隆 */
    const val MODE_CLONE = 1
    /** 参考音频 + 转写（极致克隆） */
    const val MODE_CONTINUATION = 2

    // ---- 音频格式 ----
    const val FMT_WAV = 0
    const val FMT_PCM16 = 1

    // ---- 回调限流（见设计文档「oneway 无背压」）----
    /** onStage 最小上报间隔，避免把宿主的 Binder 配额刷满 */
    const val STAGE_MIN_INTERVAL_MS = 500L
    /** 单个音频块上限；超过就自己切 */
    const val MAX_AUDIO_CHUNK_BYTES = 64 * 1024
    /** 结果小于这个值可以直接内联到 [ResultBundle.inlineAudio]，省一次 FD */
    const val INLINE_AUDIO_MAX_BYTES = 256 * 1024
}

/**
 * 插件能力。宿主按能力把插件挂到不同 UI 位置。
 *
 * **没声明能力的插件不显示** —— 这样以后加新类型不用改 AIDL，只加一个常量 + 一个 UI 挂点。
 */
object Capability {
    /** 文本 → 音频 */
    const val TTS = "tts.synthesize"
    /** 音频 → 文本（预留，v1 不实现） */
    const val ASR = "asr.transcribe"
    /** 图像生成（预留，v1 不实现） */
    const val IMG = "img.generate"

    /**
     * **测试 / 示例插件**。不承诺任何实际用途，只用来验证插件链路。
     *
     * 为什么单独一个常量而不是让它假装成 [TTS]：
     * 能力是"这个插件**承诺**能做什么"。示例插件确实会生成一段音频，
     * 但它合成的是固定琶音，不是真的语音 —— 标成 [TTS] 会让宿主
     * 在「朗读回答」这类真实场景里选到它，用户会听到一段莫名其妙的音调。
     *
     * 宿主对它的处理：
     * · 卡片上标注「测试插件」，不参与正式的朗读路由
     * · 仍然允许在插件界面里试听（那是诊断动作）
     * · **是否连上就加载引擎由 [AUTOLOAD_ENGINE] 单独决定**，和这个能力无关
     */
    const val TEST = "test.self"

    /**
     * **连上就自动加载引擎**。不声明 = 默认手动加载。
     *
     * ## 为什么加载时机必须由插件决定
     *
     * 加载引擎的代价差别是数量级的：
     *
     * | 插件类型 | 加载耗时 | 内存 |
     * |---|---|---|
     * | 玩具 / 纯算法（Echo） | 不到 1 秒 | 0 |
     * | 真模型（VoxCPM2） | 几十秒到分钟 | 数 GB |
     *
     * 宿主**不可能猜**出这个差别。以前宿主一律在连上时 `loadEngine`，
     * 结果是：用户只是切到插件页看一眼"连上没"，后台就开始灌几 GB ——
     * 用户没请求过这个动作，也没有任何办法拒绝（除了不进那一页）。
     * 那还会挤掉宿主自己的对话 / 绘图模型，甚至触发 OOM。
     *
     * ## 所以
     *
     * · **声明了** → 允许宿主在连接成功后直接加载（玩具插件、轻量引擎用这个）
     * · **没声明** → 宿主不主动加载；用户点「加载」或点「试听」时才加载
     *
     * 这和 Ponko 主界面的模型页一致：那里加载模型也是手动点按钮的。
     *
     * ⚠ 别用它规避"加载很慢"这件事 —— 慢不是问题，替用户做决定才是。
     */
    const val AUTOLOAD_ENGINE = "engine.autoload"
}

/**
 * 合成阶段。配合 [IPonkoCallback.onStage] 使用。
 *
 * ⚠ [DECODE] 阶段在 VoxCPM2 上占 80% 以上耗时（1-2 分钟），
 * **必须每 500ms 上报一次**「已生成 X 秒」，否则用户会以为卡死。
 */
object Stage {
    const val IDLE = 0
    /** 加载模型（首次，或内存不足被卸载后重载） */
    const val LOADING = 1
    /** 文本分词 + BaseLM prefill */
    const val TEXT_ENCODE = 2
    /** 自回归 + 扩散解码（最耗时） */
    const val DECODE = 3
    /** AudioVAE 解码成波形 */
    const val AUDIO_DECODE = 4
    const val DONE = 5
    const val CANCELLED = 6
    const val FAILED = 7
}

/** 引擎状态，[IPonkoPlugin.getStatus] 返回。 */
object EngineState {
    const val UNLOADED = 0
    const val LOADING = 1
    const val READY = 2
    /** 正在合成 */
    const val RUNNING = 3
}

/**
 * 错误码。**一旦发布就不能改含义**，只能新增。
 *
 * [PluginError.message] 由插件本地化，宿主要**直接显示**，不再二次加工。
 */
object ErrCode {
    const val UNKNOWN = -1
    /** 引擎未加载 */
    const val NOT_READY = 1
    /** 文件损坏 / 格式不对 / 缺槽位 */
    const val MODEL_INVALID = 2
    /** OOM；UI 应提示用户关掉其它模型或其它 App */
    const val OUT_OF_MEMORY = 3
    /** 用户取消（**不是错误**，UI 静默处理） */
    const val CANCELLED = 4
    /** 参数不合理：空文本、缺参考音频、缺转写等 */
    const val INVALID_REQUEST = 5
    /** 插件内部错误（含 JNI 崩溃后的兜底） */
    const val PLUGIN_INTERNAL = 6
    /** 不支持的模型 / 模式 */
    const val UNSUPPORTED = 7
    /** 宿主侧超时（插件无响应） */
    const val TIMEOUT = 8
    /** 已有任务在跑 */
    const val BUSY = 9
}
