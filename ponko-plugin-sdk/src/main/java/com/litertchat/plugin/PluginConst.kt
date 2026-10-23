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
     * ## 为什么加到 3（2026-10-22）
     *
     * v3 加了 `getActionLabels()`：插件可以指定宿主那几个按钮叫什么
     * （「加载引擎」→「加载模型」这种）。同样是纯扩展，老插件照常可用。
     *
     * ## 为什么加到 4（2026-10-22）
     *
     * v4 给"看不见过程"的插件补了三件事：
     * · `UiType.LOG` —— 只读日志框，配 [IPonkoCallback.onUiPatch] 增量追加
     * · `UiType.IMAGE` —— 结果图，配 [IPonkoCallback.onImage] 送图
     * · `UiType.AUDIO` —— 播放进度条，宿主自己播、自己算进度
     *
     * 同样是纯扩展。但要注意**双向兼容不是对称的**：
     * 新插件调老宿主没有的 `onImage` / `onUiPatch` 时，oneway 调用会**静默无操作**，
     * 不报错也不生效。所以插件要用 `API_VERSION` 判断，别假定宿主一定收到了。
     *
     * ## 为什么加到 5（2026-10-22）
     *
     * v5 加了标签页 `TABS_START` / `TAB` / `TABS_END` —— 插件可以把界面分成
     * 「参数 / 结果」这种页，参数里再分「文 / 图 / 标签」，层数不限于一。
     *
     * 降级后果比 v4 的重：老宿主不认识这三个类型，会**逐个**降级成
     * 「未知控件」文字块 —— 用户看到的是一串方块加断断续续的内容，不是完整界面。
     * 所以想在老宿主上也体面的话，插件该按版本判断后再决定用不用标签页。
     *
     * ## 为什么加到 6（2026-10-22）
     *
     * v6 让插件能收**本地模型文件**：`UiType.MODEL_SLOT` 声明槽位，
     * 宿主画「导入 / 清除」，用户点导入后由**宿主**弹系统文件选择器，
     * 再把 FD 通过 `importModel` 交给插件。
     *
     * 选择器必须宿主弹，插件自己弹不了 —— 它是 bound Service，
     * `startActivityForResult` 是 Activity 的 API，而且 Android 10+ 禁止
     * 后台启动 Activity。见 `IPonkoPlugin.importModel` 的说明。
     *
     * ## 为什么加到 7（2026-10-22）
     *
     * v7 补的是"插件**反向**指挥宿主"的三件事，全部来自插件开发者的实测反馈：
     *
     * | 缺口 | 补法 |
     * |---|---|
     * | 参数页里放一个真能用的「开始推理」按钮，做不到 | `requestSynthesis()` |
     * | 宿主的控件搬不进插件的 tab | `UiType.HOST_SLOT` |
     * | 历史记录每条单独播/单独存，做不到（只有一份产物） | `onAudioFile(key, fd, name)` |
     *
     * 前两件之前根本没有通道：合成由宿主发起（插件只说"我有个按钮被点了"），
     * 功能块由 `build()` 写死挂在标题下面。
     *
     * ## 为什么加到 8（2026-10-22）
     *
     * 插件开发者问："能不能做触发事件？比如推理完成后跳到 a 页"。
     * 标签页状态（用户点哪页、上次停哪页）一直是宿主私有的，
     * v8 开了 `selectTab` —— 第一次允许插件决定**显示哪一页**。
     *
     * ## 为什么加到 9（2026-10-23）
     *
     * 插件开发者反馈："UI 那块限制得太死，固定画 5 个按钮，而不是由插件自由声明"。
     * 那 5 个按钮背后是宿主的状态机（引擎、合成、播放器、音乐库），执行不能外包；
     * 但**放哪些、放哪里、叫什么**没有任何理由由宿主规定。
     *
     * v9 让 `UiType.ACTION` 的 `value` 可以点名宿主动作（[HostActionId]），
     * 于是插件能把这些按钮分配进自己的标签页。逐个覆盖：只接管一个也行。
     *
     * ## 为什么加到 10（2026-10-23）
     *
     * 用户定的方向：「应该做成最大程度自由（除了 theme）」。
     *
     * 盘点下来，和 theme 无关的限制只剩一条大的：**布局**。`items` 一直是
     * 纯纵向单列，插件连"两个滑块并排"都做不到。v10 加了
     * `ROW_START` / `GRID_START` 两种容器（可嵌套），这条就补上了。
     *
     * **样式一律不放开**：插件读不到 `PonkoTheme`，自己写颜色必然出现两套配色、
     * 换主题时留在旧色里。所以自由的上限是"结构全自由 + 样式零自由"。
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
    const val API_VERSION = 12

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

    /**
     * 从这一版起，插件可以覆盖宿主按钮的文案（[IPonkoPlugin.getActionLabels]）。
     *
     * 宿主按版本跳过调用，而不是 try-catch：调一个不存在的方法时
     * 老插件的 Binder 会收到不认识的事务码，行为不保证（见上）。
     */
    const val API_WITH_ACTION_LABELS = 3

    /**
     * 从这一版起，插件可以用 [UiType.LOG] / [UiType.IMAGE] / [UiType.AUDIO]，
     * 以及 [IPonkoCallback.onImage] / [IPonkoCallback.onUiPatch]。
     *
     * 宿主按版本决定要不要渲染这三种控件；不认识就降级成纯文本，不崩。
     */
    const val API_WITH_RICH_UI = 4

    /**
     * 从这一版起，插件可以用标签页（[UiType.TABS_START] / [UiType.TAB] / [UiType.TABS_END]）。
     *
     * 宿主按版本判断要不要渲染；不认识就逐个降级成文字块（不会崩，但界面会散）。
     */
    const val API_WITH_TABS = 5

    /**
     * 从这一版起，插件可以声明 [UiType.MODEL_SLOT] 槽位，
     * 并通过 [IPonkoPlugin.importModel] / `deleteModel` 收下/删除用户选的模型文件。
     *
     * 宿主按版本决定要不要给槽位画「导入 / 清除」按钮。
     */
    const val API_WITH_MODEL_IMPORT = 6

    /**
     * 从这一版起，插件可以**反向指挥宿主**（v7 三件事一起来）：
     *
     * 1. [IPonkoCallback.requestSynthesis] —— 让宿主用当前参数合成一次
     * 2. [IPonkoCallback.onAudioFile] —— 给 [UiType.AUDIO] 条目送自己的文件
     *    （历史记录每条独立播放/保存）
     * 3. [UiType.HOST_SLOT] —— 在插件页面里挖个坑，让宿主把功能块画进来
     *
     * 三项同一个版本号，所以判断只要一句 `api >= API_WITH_SYNTH_REQUEST`。
     * 常量名沿用最先落地的那个（合成请求），**不代表只和它有关**。
     */
    const val API_WITH_SYNTH_REQUEST = 7

    /**
     * 从这一版起，插件可以**让宿主切换到指定标签页**
     * （`IPonkoCallback.selectTab`）。
     *
     * 用途：推理完成后自动翻到「结果」页，不用用户再点一下标签。
     * 宿主侧的标签页状态一直是私有的（用户点哪页、记住哪页），
     * 这是第一次开放"由插件决定显示哪一页"。
     */
    const val API_WITH_TAB_SELECT = 8

    /**
     * 从这一版起，`UiType.ACTION` 的 `value` 可以用来**点名宿主动作**
     * （见 [HostActionId]）。
     *
     * ## 为什么这次升版本，虽然 AIDL 一行没加
     *
     * 因为它确实多了一个"能和不能"的区分：老宿主会把 `value` 当成普通值忽略，
     * 按钮变回"回调插件"—— 插件以为合成了、其实没有。这种静默失效必须有
     * 一个判断依据，否则插件只能靠猜。
     *
     * 和 v3（`getActionLabels`）是同一类：协议面扩展了，插件要靠版本号决定
     * 能不能用。**不是破坏性改动**，所以 `API_MIN_SUPPORTED` 仍然不动。
     */
    const val API_WITH_HOST_ACTION_ITEMS = 9

    /**
     * 从这一版起，插件可以自己排布局：`ROW_START` / `GRID_START`（及对应的 `_END`）。
     *
     * 加之前 `items` 是一个**纯纵向**的有序列表 —— 「语速」和「音高」两个滑块
     * 想并排都做不到，那是当时最大的表达力缺口。
     *
     * 边界仍然只有一条：**样式不归插件管**（颜色 / 字号 / 圆角 / 深色模式
     * 全部走宿主的 `PonkoTheme`）。插件决定结构，宿主决定长相。
     */
    const val API_WITH_LAYOUT_CONTAINERS = 10

    /**
     * 从这一版起，插件送结果图时可以**指定贴到哪个 IMAGE 控件**
     * （`IPonkoCallback.onImageWithKey`）。
     *
     * ## 为什么现在才补
     *
     * `onImage` 是 v4 定的，签名里没有 key。页面上只有一张图时宿主"贴第一个"
     * 还能用，但两张以上（原图/结果对比）就完全不知道插件想更新哪张 ——
     * 老行为的做法是贴到第一张，屏幕上看着像插件贴错了地方。
     *
     * **这是宿主替插件做决定**，和 v10 放开布局是同一个方向上的问题：
     * 只要协议里缺一个"你说了算"的位置，宿主就不得不猜。
     */
    const val API_WITH_IMAGE_KEY = 11

    /**
     * 从这一版起，插件可以**让宿主滚到某一项**（`onScrollTo`）。
     *
     * ## 为什么是"插件说"而不是"宿主自动"
     *
     * 宿主曾经自己记住重画前的滚动位置、重画后放回去。那是宿主替页面作者
     * 决定停在哪 —— 而重画后停在哪本该是插件的事：可能想让用户看到刚算出的
     * 结果，也可能想留在参数区。
     *
     * 现在宿主**不自动滚动**（不调用就什么都不发生，Android 自己保持位置）。
     * 要滚就显式说。
     */
    const val API_WITH_SCROLL_TO = 12

    /** 宿主兼容区间：允许老插件继续跑，所以下限保持 1 */
    const val API_MIN_SUPPORTED = 1
    const val API_MAX_SUPPORTED = 12

    // ---- 插件 Action（宿主靠它发现插件）----
    const val ACTION_PLUGIN = "com.litertchat.ponko.PLUGIN"

    /**
     * bind Intent 里携带**宿主当前界面语言**的 key（BCP 47，如 `"zh-CN"`）。
     *
     * 插件侧不用直接碰它 —— 基类会读出来，暴露成
     * [PonkoPluginService.hostLocaleTag]。这里公开常量是给
     * "不继承基类、自己实现 Service" 的插件用的（少数情况）。
     *
     * 为什么走 Intent extra 而不是 AIDL 方法：加 AIDL 要升 `API_VERSION`、
     * 重发 AAR、让所有插件重编；而这个值静态、单向、每次绑定都会重给，
     * extra 天然满足，且**老插件不受影响**（不读就不知道）。
     */
    const val EXTRA_HOST_LOCALE = "com.litertchat.ponko.extra.HOST_LOCALE"

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
     * **支持 GPU / NPU 推理**（`"gpu.inference"`）。
     *
     * 声明了它，宿主才会在试听参数里显示 CPU/GPU 切换；没声明就不显示
     * （并说明该插件没有这个能力）。
     *
     * ## 为什么不加 Manifest 字段
     *
     * `PluginManifest` 是 `@Parcelize`，**加字段会改二进制布局** ——
     * 新宿主读老插件写出来的 Parcel 会读越界，轻则垃圾值、重则
     * `BadParcelableException`。而 `capabilities` 是个 `List<String>`，
     * 往里加一个新常量的字符串**不改任何结构**，老宿主只是不认识、直接忽略。
     *
     * 同一个理由也解释了为什么 v3 的按钮文案走独立方法而不是加字段。
     *
     * ## 声明之后要真的用得上
     *
     * 宿主会把用户的开关一路传到 `EngineConfig.useGpu`。插件收到 `true` 时：
     * · 能上 GPU 就上
     * · 上不了（设备/驱动不支持）就**回退 CPU 并在 `STATUS` 里写清楚**
     *   —— 静默回退会让用户以为 GPU 生效了，对着更慢的速度困惑
     */
    const val GPU = "gpu.inference"

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
 * 宿主按钮的文案 key，配合 [IPonkoPlugin.getActionLabels] 使用。
 *
 * ## 为什么插件能改这些字
 *
 * 同一件事在不同插件里叫法不同：宿主管它叫「加载引擎」，
 * 但一个 3.5 GB 的 TTS 插件更想说「加载模型（3.5 GB）」，
 * 一个走网络 API 的插件根本没有模型，说「连接服务」才准确。
 * 按钮是宿主的，但**名字的含义只有插件知道**。
 *
 * ## 能改什么、不能改什么
 *
 * 只能改**文字**。位置、个数、颜色、可点状态、点了做什么，全归宿主。
 * 所以插件拿不到「新按钮」，只能给自己那一格换个说法 ——
 * 否则插件就能靠文案把用户骗到一个破坏性动作上。
 *
 * ## 谁会用到
 *
 * 只有提供音频能力的插件（[Capability.TTS] / [Capability.TEST]）才看得到这组按钮。
 * 其它插件声明了也没用。
 */
object ActionLabel {

    /** 引擎未就绪时的按钮 */
    const val ENGINE_LOAD = "engine.load"

    /** 引擎已就绪时的按钮（同一个按钮的另一个状态） */
    const val ENGINE_UNLOAD = "engine.unload"

    /**
     * 跑一次推理得到音频。**只合成，不播放。**
     *
     * ## 为什么名字是 reasoning 而不是 preview
     *
     * 「preview / 试听」暗示的是**听**这个动作 —— 用户会以为点下去直接出声。
     * 但这个按钮做的是跑一遍推理，产出的是一个文件；听它需要再点「播放」。
     *
     * 名字错了会带来实际的困惑：用户点完没听到声音，会以为功能坏了，
     * 而实际上音频已经生成好了、就在那儿等着播。
     *
     * ## 也不带 .again 这种"第二次改名"的变体
     *
     * 曾经有过 `preview.again`：因为那时合成完会自动播，同一个按钮在
     * "还没听过"和"听过了"两种状态下含义不同，只好改名。
     * 改成仅合成之后，点几次都是同一个动作（再跑一次推理），歧义消失，
     * 那个 key 也就没有存在的理由了 —— **它是补丁，不是需求。**
     */
    const val REASONING = "reasoning"

    /** 重放上一次的结果，不重新推理 */
    const val PLAY = "play"

    /** 打断当前这次生成 */
    const val STOP = "stop"

    /** 把音频存进系统音乐库 */
    const val SAVE = "save"

    /**
     * 一条文案的长度上限（字符数，不是字节）。
     *
     * 五个按钮排一行，超过这个长度就会换行或挤掉别的按钮。
     * **超长直接丢弃**，退回宿主默认文案 —— 截断会得到「加载模…」这种
     * 更看不懂的东西，不如让用户看到原来那个。
     */
    const val MAX_LEN = 12

    /** 全部合法的 key。解析时用来过滤插件乱写的项 */
    val ALL = setOf(ENGINE_LOAD, ENGINE_UNLOAD, REASONING, PLAY, STOP, SAVE)

    /**
     * 把 `getActionLabels()` 返回的 `["key=文字", ...]` 解析成 map。
     *
     * 容错优先：坏项**逐项跳过**，不抛异常、不影响其它项。
     * 插件写错一个 key 不该让整页按钮全变回默认。
     *
     * **这里没有"默认文案"** —— 默认文案是宿主界面的一部分，
     * 放在 SDK 里等于让每个插件 APK 都揣一份别的 App 的中文字符串，
     * 而且做 i18n 时要改两处。宿主自己维护那张表。
     *
     * @return key → 文案；只含合法的项
     */
    fun parse(raw: List<String>?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, String>(raw.size)
        for (line in raw) {
            if (line.isBlank()) continue
            // 只切第一个 `=`：文案里出现 `=` 是合法的
            val i = line.indexOf('=')
            if (i <= 0 || i == line.length - 1) continue
            val key = line.substring(0, i).trim()
            val text = line.substring(i + 1).trim()
            if (key !in ALL) continue
            if (text.isEmpty() || text.length > MAX_LEN) continue
            out[key] = text
        }
        return out
    }
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
