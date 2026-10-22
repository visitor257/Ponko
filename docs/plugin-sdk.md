# Ponko 插件 SDK 设计（v1）

> 目标版本 **2.0.0**（主线，`com.litertchat.app`）。本文只讲接口设计，不含实现。
> 第一个插件：VoxCPM2 TTS（`com.ponko.plugin.voxcpm2`）。

---

## 一、范围界定（先划清楚，避免做成平台工程）

**v1 要做**：
- 宿主发现 / 绑定 / 解绑插件
- 插件声明能力与版本
- 插件加载模型、执行一次推理、取消、卸载
- 进度回调 + 流式音频块 + 整段结果
- 错误码、超时、崩溃隔离

**v1 不做**（留出接口位置，不实现）：
- 第三方插件开放（v1 只允许**同签名**插件，见第五节）
- 宿主把自己的模型文件共享给插件（`ModelBundle` 传 null，插件自己管模型，见第四节）
- 插件的插件（二级扩展）
- 远程插件市场 / 自动更新

**刻意克制的地方**：能力枚举只留 3 个（TTS / ASR / 图像生成），
不做通用「任意 IO 类型」的抽象。等真有第 4 种再扩。

---

## 二、三个产物

| 产物 | 是什么 | 谁维护 |
|---|---|---|
| `ponko-plugin-sdk` | AAR：AIDL + Parcelable + 基类 Service + 常量 | Ponko 仓库内一个 module |
| Ponko 宿主 | 发现 / 绑定 / 路由 / UI | Ponko 主工程 |
| 插件 APK | 实现 SDK，自带 `.so` 和模型管理 | 各自独立 App |

**SDK 为什么要独立成 module**：插件作者只需要依赖一个 AAR，不用把 Ponko 整个工程拖进去。
版本号跟 Ponko 的 `API_VERSION` 绑定发布。

---

## 三、接口设计

### 3.1 `IPonkoPlugin.aidl` —— 主接口，绑定后立刻可用

```aidl
// package com.litertchat.plugin
package com.litertchat.plugin;

import com.litertchat.plugin.IPonkoCallback;
import com.litertchat.plugin.PluginManifest;
import com.litertchat.plugin.ModelBundle;
import com.litertchat.plugin.EngineConfig;
import com.litertchat.plugin.SynthesisRequest;
import com.litertchat.plugin.EngineStatus;
import com.litertchat.plugin.PluginError;

interface IPonkoPlugin {
    /**
     * 插件实现的 SDK 版本。
     *
     * 宿主只拿它跟 **API_MIN_SUPPORTED** 比：低于下限拒绝连接；
     * 高于 API_MAX_SUPPORTED 照常连接，只是不认识的新控件/回调用不了（见 3.6）。
     */
    int getApiVersion();

    /** 插件自述：id、名称、版本、能力、支持的模型家族、是否需要宿主提供模型 */
    PluginManifest getManifest();

    /** 注册回调。宿主绑定成功后第一件事就调它；重复调用以最后一次为准 */
    void registerCallback(in IPonkoCallback cb);

    /**
     * 加载引擎（模型）。异步：结果走 onEngineLoaded / onEngineError。
     * 已在加载中或已加载时重复调用 → 忽略（幂等），不报错。
     */
    oneway void loadEngine(in @nullable ModelBundle models, in EngineConfig config);

    /** 卸载引擎，释放模型内存。异步完成，完成后 isEngineReady() 变 false */
    oneway void closeEngine();

    boolean isEngineReady();

    EngineStatus getStatus();

    /**
     * 执行一次合成。异步：进度走 onStage/onAudioChunk，最终结果走 onResult / onError。
     * 同一时刻只允许一个任务；引擎未就绪时立刻回 onError(ERR_NOT_READY)。
     */
    oneway void synthesize(in SynthesisRequest req);

    /** 取消当前任务。没有任务时是空操作。取消后回 onError(ERR_CANCELLED) */
    oneway void cancel();
}
```

**为什么 `loadEngine` / `synthesize` 是 `oneway`**：
加载 2.83 GB 要几十秒。Binder 线程池只有 16 个槽（每进程），
同步调用会把宿主的一个 Binder 线程占死 30 秒——期间其它 IPC 都可能排队。
`oneway` 让调用立刻返回，结果全靠回调。

**为什么没有独立的 `IPonkoEngine` 对象**：
一个插件 = 一个模型家族 = 同一时刻只有一个引擎。
多加一层对象只会让插件作者多写样板。等真的需要"多实例并发"再说。

### 3.2 `IPonkoCallback.aidl` —— 插件回调宿主

```aidl
package com.litertchat.plugin;

import android.os.ParcelFileDescriptor;
import com.litertchat.plugin.EngineInfo;
import com.litertchat.plugin.ResultBundle;
import com.litertchat.plugin.PluginError;

oneway interface IPonkoCallback {
    // ---- 引擎生命周期 ----
    void onEngineLoaded(in EngineInfo info);       // 成功：回报采样率、声道、内存占用
    void onEngineError(in PluginError err);        // 加载失败

    // ---- 单次合成 ----
    /** 阶段变化。stage 见 3.7；label 是给人看的一句话（可为空） */
    void onStage(int stage, in @nullable String label);

    /**
     * 流式音频块。streamAudio=true 时才会收到。
     * PCM 16-bit 小端交织；每块 ≤ 64KB（约 0.33 秒 @48kHz 单声道）。
     * isFinal=true 表示最后一块，之后不再有 chunk。
     */
    void onAudioChunk(in byte[] pcm, int sampleRate, int channelCount, boolean isFinal);

    /** 最终结果。streamAudio=false 时音频在这里；true 时这里只带时长等元信息 */
    void onResult(in ResultBundle result);

    void onError(in PluginError err);

    // ---- v2：界面 ----
    /** 界面数据变了 → 宿主重拉 schema **整页重画**。能只改一处就别用这个 */
    void onSchemaChanged();

    // ---- v4：过程与产物（都追加在末尾，老的 transaction code 不移动）----
    /** 送一张结果图给 UiType.IMAGE。FD 归宿主所有，插件不要先 close */
    void onImage(in ParcelFileDescriptor fd, int width, int height, in @nullable String mime);

    /**
     * 原地改一个控件，不重画整页。
     * key 必须和 schema 里那个 item 完全一致；append=true 追加（日志），false 替换。
     */
    void onUiPatch(in String key, in @nullable String value, boolean append);

    // ---- v7：插件反向指挥宿主 ----

    /** 请宿主用**当前参数**合成一次。参数、校验、进度回报全归宿主，插件只是发起 */
    void requestSynthesis();

    /**
     * 给某个 AUDIO 条目送**它自己**的音频（历史记录场景）。
     * key 对应 UiItem.key；同一个 key 再送 = 替换。FD 归宿主所有。
     */
    void onAudioFile(in String key, in ParcelFileDescriptor fd, in @nullable String displayName);
}
```

**为什么 chunk 走 `byte[]` 而不是 FD**：
64 KB 远低于 1 MB 配额，走 Parcel 最省事；每块都要新开一个 FD 反而更贵。
**整段音频**（可能几十 MB）才走 FD，见 `ResultBundle`。

**为什么结果图也走 FD**：一张 1024×1024 的 PNG 通常 1–3 MB，已经超过单个
Binder 事务的余量，更别说多张。宿主收到后**在 Binder 线程就立刻读成字节并关掉 FD** ——
`oneway` 方法一返回 parcel 就被回收，插件那边可能已经 close，FD 会变成死句柄。

### ⚠ 追加方法必须加在 `.aidl` 的**最末尾**

Binder 的事务码按声明顺序分配。在中间插入会让后面所有方法的事务码整体后移，
老插件收到的码对不上 → **调用串味**（本该调 A 却执行了 B），比直接报错更难查。
v2 的 `onSchemaChanged`、v4 的两个方法、v7 的两个方法都守住了这条
（`getActionLabels` 至今仍是第 13 个事务码，没动过）。

### 3.3 Parcelable 定义

用 Kotlin `@Parcelize`（`id("kotlin-parcelize")`），比手写 `Parcelable` 少 90% 样板。

```kotlin
// PluginManifest —— 插件自述，宿主用它决定「要不要显示、显示什么」
@Parcelize
data class PluginManifest(
    val pluginId: String,          // 稳定唯一，如 "voxcpm2"；不要用包名，包名会改
    val displayName: String,       // "VoxCPM2 语音合成"
    val versionName: String,       // 插件自己的版本
    val author: String,
    val capabilities: List<String>,// 见 3.5，如 ["tts.synthesize"]
    val modelFamilies: List<String>,// 支持哪些模型家族，如 ["voxcpm2"]
    val needsHostModels: Boolean,  // true = 需要宿主通过 ModelBundle 传文件；false = 自己管
    val minSdkHint: String,        // 给人看的提示，如 "建议 8GB 内存"
) : Parcelable

// ModelBundle —— 宿主把自己的模型交给插件（v1 传 null，接口先留好）
@Parcelize
data class ModelBundle(
    val family: String,            // "voxcpm2"
    val entries: List<ModelEntry>,
) : Parcelable

@Parcelize
data class ModelEntry(
    val role: String,              // ROLE_*，见 3.4
    val fd: ParcelFileDescriptor,  // 只读 FD；插件用完自己 close，不要关宿主的
    val sizeBytes: Long,
    val displayName: String,       // 仅用于日志
) : Parcelable

// EngineConfig —— 加载期参数
@Parcelize
data class EngineConfig(
    val threads: Int,              // 0 = 插件自定（建议 min(4, 小核数)）
    val useGpu: Boolean,           // 手机端一般 false
    val logVerbose: Boolean,
) : Parcelable

// SynthesisRequest
@Parcelize
data class SynthesisRequest(
    val requestId: String,         // 宿主生成，用于丢弃过期回调
    val text: String,
    val mode: Int,                 // MODE_PLAIN / MODE_CLONE / MODE_CONTINUATION
    val referenceAudio: ParcelFileDescriptor?, // clone / continuation 需要；PCM 16-bit WAV
    val referenceSampleRate: Int,  // 0 = 从文件读
    val referenceTranscript: String, // continuation 需要
    val voiceDescription: String,  // 语音设计，如 "年轻女性，温和"；空则用默认音色
    val cfg: Float,                // 2.0 附近
    val timesteps: Int,            // 10 起步
    val seed: Int,                 // 0 = 随机
    val maxSteps: Int,             // 上限保护
    val streamAudio: Boolean,      // true → onAudioChunk；false → onResult 里给音频
) : Parcelable

// EngineInfo —— 加载成功后回报
@Parcelize
data class EngineInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val modelBytes: Long,          // 占用多少（**只用于显示**，见下方⚠）
) : Parcelable

// ⚠ modelBytes 只用来显示，**不能**当"引擎加载过"的标志。
//    没有模型文件的插件（纯算法 / 测试插件）加载成功后它依然是 0。
//    SDK 基类内部用独立的 engineLoaded 标记维护状态机（READY / UNLOADED），
//    插件作者不需要、也不应该用体积去推断就绪状态 —— 早期版本真这么写过，
//    结果第一次合成结束就被判成"未加载"，第二次试听直接回「引擎尚未加载」。

// ResultBundle
@Parcelize
data class ResultBundle(
    val requestId: String,
    val sampleRate: Int,
    val channelCount: Int,
    val durationMs: Long,          // 音频时长
    val elapsedMs: Long,           // 实际耗时
    val audio: ParcelFileDescriptor?,  // 大结果走这里（WAV 或裸 PCM）
    val audioFormat: Int,          // FMT_WAV / FMT_PCM16
    val inlineAudio: ByteArray?,   // 小结果（< 256 KB）可直接内联，省一次 FD
    val debugInfo: String,         // 可选，日志用
) : Parcelable

// PluginError
@Parcelize
data class PluginError(
    val code: Int,                 // 见 3.8
    val message: String,           // 已本地化，可直接显示
    val detail: String,            // 原始错误，仅日志
    val retryable: Boolean,
    val requestId: String?,
) : Parcelable

// EngineStatus
@Parcelize
data class EngineStatus(
    val state: Int,                // STATE_*，见 3.7
    val modelBytes: Long,
    val loadedAtElapsedMs: Long,   // 0 = 未加载
) : Parcelable
```

### 3.4 常量

```kotlin
object PluginConst {
    // 模型文件角色
    const val ROLE_MAIN    = "main"       // 单文件模型的唯一文件
    const val ROLE_BASE_LM = "base_lm"    // VoxCPM2 主干
    const val ROLE_ACOUSTIC = "acoustic"  // VoxCPM2 声学 / AudioVAE
    const val ROLE_MMPROJ  = "mmproj"     // GGUF 视觉投影
    const val ROLE_VAE     = "vae"
    const val ROLE_TOKENIZER = "tokenizer"

    // 合成模式
    const val MODE_PLAIN = 0        // 纯 TTS / 语音设计（voiceDescription）
    const val MODE_CLONE = 1        // 参考音频克隆
    const val MODE_CONTINUATION = 2 // 参考音频 + 转写（极致克隆）

    // 音频格式
    const val FMT_WAV   = 0
    const val FMT_PCM16 = 1
}
```

### 3.5 能力枚举（刻意保持小）

```kotlin
object Capability {
    const val TTS = "tts.synthesize"   // 文本 → 音频
    const val ASR = "asr.transcribe"   // 音频 → 文本（预留）
    const val IMG = "img.generate"     // 图像生成（预留）
    const val TEST = "test.self"       // 测试/示例插件，不参与正式路由
    const val GPU = "gpu.inference"    // v6：支持 GPU/NPU 推理
}
```

宿主按能力把插件挂到不同 UI 位置。**没声明能力的插件不显示**——
这样以后加新类型不用改 AIDL，只加一个常量 + 一个 UI 挂点。

#### `Capability.GPU` 怎么用（v6）

声明了它，宿主才会在插件界面的试听参数里显示 **CPU / GPU** 切换：

```kotlin
override fun getManifest() = PluginManifest(
    ...
    capabilities = listOf(Capability.TTS, Capability.GPU),
)
```

用户的选择会一路传到 `EngineConfig.useGpu`。插件收到 `true` 时：

- 能上 GPU 就上
- 上不了（设备/驱动不支持）就**回退 CPU，并在 `UiType.STATUS` 里写清楚**
  —— 静默回退会让用户以为 GPU 生效了，对着更慢的速度困惑

**为什么放进 `capabilities` 而不是给 `PluginManifest` 加字段**：
`PluginManifest` 是 `@Parcelize`，加字段会改二进制布局，新宿主读老插件写出的
Parcel 会读越界。而 `capabilities` 是 `List<String>`，加个新字符串不改任何结构。

### 3.6 版本协商

**两个版本号，不要混**：

| 名称 | 谁提供 | 用途 |
|---|---|---|
| `API_VERSION` | SDK 常量（宿主与插件编译时同一份） | AIDL 表面版本，破坏性改动才 +1 |
| `versionName` | 插件自己的包信息 | 给人看 |

宿主逻辑：

```kotlin
val api = plugin.getApiVersion()   // 失败/异常 → 视为不兼容
if (api < SDK_MIN_API) {
    // 真不兼容：插件缺宿主必需的方法 → 不绑定，显示「插件太旧，请更新插件」
    return
}
if (api > SDK_MAX_API) {
    // **照常绑定**。插件比宿主新，老功能一个没少，只是新控件会降级、
    // 新回调会被丢弃。提示「更新 Ponko 可获得完整功能」——
    // **不能说成"不兼容"**，否则用户会去重装一个已经最新的插件
    newerThanHost = true
}
```

**为什么高版本不拒绝**（2026-10-22 改）：

原来写的是 `api !in MIN..MAX` 就拒连，方向错了。真实后果是：插件作者用新 SDK
编了插件，用户装上去看到「插件 SDK v4 不兼容，请更新插件」—— 但插件已经是最新的，
要更新的是 Ponko。更冤的是插件本来能用（老功能全在），只是宿主不认识新增那几个控件。

这么做安全的前提是 **AIDL 只在末尾追加方法**：老宿主收到不认识的 transaction code
会走 `Stub` 的 `default` 分支返回 false（可能是 null / 0 / 全零 Parcel），**不抛异常**。
特性判断也全都写成 `api >= API_WITH_XXX`，天然是"认不出来就当没有"。

**规则**：
- 只在**破坏性改动**时 +1（删方法、改参数含义、改 Parcelable 字段含义），
  并且把 `API_MIN_SUPPORTED` 跟着抬 —— 那才是真拒绝
- **加方法不算破坏**：版本 +1，`API_MAX_SUPPORTED` 跟着 +1，老插件继续能用。
  注意 AIDL 接口要求插件实现全部方法，所以加方法对插件仍是被动的（要重编），
  但对**已发布的旧插件**没有影响 —— 它们只是不认识新方法
- 新方法**必须加在 `.aidl` 的最末尾**（事务码按声明顺序分配，中间插入会串味）
- 宿主**只拒绝低于下限**的版本，高于上限的接受 + 提示

### 3.7 阶段与状态

```kotlin
// stage —— 合成过程中，配合 onStage 显示进度文案
object Stage {
    const val IDLE          = 0
    const val LOADING       = 1  // 加载模型（首次或显存不足被卸载后重载）
    const val TEXT_ENCODE   = 2  // 文本分词 + BaseLM prefill
    const val DECODE        = 3  // 自回归 + 扩散解码（最耗时，占 80%+）
    const val AUDIO_DECODE  = 4  // AudioVAE 解码成波形
    const val DONE          = 5
    const val CANCELLED     = 6
    const val FAILED        = 7
}

// state —— 引擎状态，getStatus() 返回
object EngineState {
    const val UNLOADED  = 0
    const val LOADING   = 1
    const val READY     = 2
    const val RUNNING   = 3   // 正在合成
}
```

**`DECODE` 阶段必须持续上报**：VoxCPM2 在这个阶段要跑 1-2 分钟，
不让用户看到"已生成 X 秒音频"的递增数字，用户会以为卡死。
`onStage(DECODE, "已生成 3.2 秒")` 每 500ms 一次。

### 3.8 错误码（一旦发布就不能改含义）

```kotlin
object ErrCode {
    const val UNKNOWN        = -1
    const val NOT_READY      = 1   // 引擎未加载
    const val MODEL_INVALID  = 2   // 文件损坏 / 格式不对 / 缺槽位
    const val OUT_OF_MEMORY  = 3   // OOM，建议 UI 提示关掉其它 App
    const val CANCELLED      = 4   // 用户取消
    const val INVALID_REQUEST = 5  // 参数不合理（空文本、缺参考音频等）
    const val PLUGIN_INTERNAL = 6  // 插件内部错误（含 JNI 崩溃后的兜底）
    const val UNSUPPORTED    = 7   // 不支持的模型/模式
    const val TIMEOUT        = 8   // 宿主侧超时（插件无响应）
    const val BUSY           = 9   // 已有任务在跑
}
```

`message` 由插件本地化（插件自己有中文/英文资源），宿主**直接显示**，不再二次加工。

---

## 四、模型归属（v1 决策：插件自己管）

**v1：`loadEngine(models = null)`，插件自己下载 / 导入模型到自己的私有目录。**

理由：
1. Ponko 现有模型 UI 是「单文件」或「按家族补槽位」，VoxCPM2 要
   `base_lm` + `acoustic` 两个文件带角色——塞进现有 UI 要改一堆东西
2. 插件自己管，用户心智更简单：装了插件 → 插件里选模型 → 就能用
3. 省掉宿主一大块「模型 → FD」的路由代码（问题①）

**v2 再做宿主共享模型**：当出现「同一个模型被多个插件用」时才值得。
接口已经预留 `ModelBundle`，届时不用改 AIDL，只是从 `null` 变成有值。

⚠ 一旦开始传 FD，**插件必须 `fdopen` 后自己 close，不能关宿主的 FD**——
FD 跨 Binder 是 dup 过的，两边各持一份，关错会连累对面。

---

## 五、安全（v1 用系统级约束，不自己写校验）

**核心做法：定义一个 signature 级权限，让系统替我们挡。**

宿主 `AndroidManifest.xml`：
```xml
<permission
    android:name="com.litertchat.ponko.permission.BIND_PLUGIN"
    android:protectionLevel="signature" />
```

插件 `AndroidManifest.xml`：
```xml
<service
    android:name=".VoxCpm2PluginService"
    android:exported="true"
    android:permission="com.litertchat.ponko.permission.BIND_PLUGIN">
    <intent-filter>
        <action android:name="com.litertchat.ponko.PLUGIN" />
    </intent-filter>
</service>
```

`protectionLevel="signature"` 意味着：**只有和宿主签同一把密钥的 APK 才能 bind**。
系统在 `bindService` 时就拒绝，代码里一行校验都不用写。

**代价与后续**：第三方插件用不了（他们没你的私钥）。
真要开放时，改成 `protectionLevel="normal"` + 宿主侧校验
「插件签名证书 SHA-256 在允许列表里」，并加一个"用户手动信任"的确认流程。
**v1 不做**——现在只有你自己的插件。

**顺带**：插件列表里显示 `签名指纹前 8 位`，让用户能看出"这是不是同一个人做的"。

---

## 六、发现与绑定流程

```
① 宿主启动/进入插件页
     ↓ queryIntentServices(action="com.litertchat.ponko.PLUGIN")
② 逐个读包信息：包名、versionName、是否 enabled
     ↓ （signature 权限已在系统层过滤，这里只剩正品）
③ 显示列表：名称 / 版本 / 能力 / 状态（未启用 / 已启用 / 版本偏旧 / 插件偏新）
     ↓ 用户点「启用」
④ bindService(intent, conn, BIND_AUTO_CREATE)
     ↓
⑤ onServiceConnected → IPonkoPlugin.Stub.asInterface(binder)
     ↓ registerCallback(this)
⑥ getApiVersion() 校验 → getManifest() 读能力
     ↓ api < API_MIN_SUPPORTED → unbind + 「插件太旧，请更新插件」
     ↓ api > API_MAX_SUPPORTED → **照常绑定** + 「更新 Ponko 可获得完整功能」
     ↓ 否则（含高于上限）→ **照常绑定**
⑦ 就绪。用户点「生成」时：
     ↓ loadEngine(null, config) → 等 onEngineLoaded
     ↓ synthesize(req) → onStage / onAudioChunk / onResult
⑧ 退出 Ponko / 用户关闭插件 → unbindService + closeEngine
```

**绑定失败的排查顺序**（写进日志，用户报障时能直接看）：
1. `bindService` 返回 false → 服务不存在 / 被禁用 / 权限不匹配
2. `onServiceConnected` 超时（5s）→ 插件进程启动失败（多半是缺 `.so` 或 ABI 不符）
3. `getApiVersion()` 抛 `DeadObjectException` → 插件在 `onBind` 里崩了

---

## 七、线程与生命周期规则（写进 SDK 文档，插件作者最容易错的地方）

**插件侧（Service 内）必须遵守**：
1. `loadEngine` / `synthesize` / `cancel` 三个 `oneway` 方法**都在 Binder 线程上调进来**，
   实现里**必须立刻切到自己的单线程 executor**，不能在 Binder 线程里跑推理
   （Binder 线程池只有 16 个，占死会导致别的 IPC 排队）
2. 同一时刻只允许一个 `synthesize`；并发调用回 `ERR_BUSY`
3. `cancel()` 必须能在推理中途打断（在 decode 循环每步检查一个 `AtomicBoolean`）
4. 推理期切前台服务 + `PARTIAL_WAKE_LOCK`（详见本文第八节）
5. `onDestroy` 里必须 `closeEngine` 并停前台服务，否则进程被杀了模型没释放

**宿主侧必须遵守**：
1. 所有 AIDL 调用在后台线程发起（`createEngine` 虽 `oneway`，但绑定本身不能在主线程等）
2. 回调方法**都在插件进程的 Binder 线程**进来 → 更新 UI 前必须 `ui.post {}`
3. `requestId` 不匹配的回调**直接丢弃**（用户取消后插件可能还在吐最后几块）
4. 收到 `onError` 后如 `retryable=true`，最多自动重试 1 次，再失败就报给用户
5. `unbindService` 后把 `IPonkoPlugin` 引用置 null——留着会内存泄漏 + 后续调用抛 `DeadObject`

---

## 八、与保活/亮屏的配合（插件侧实现要点）

### ⚠ 前台服务的启动时机是关键（Android 12+ 会抛异常）

`startForeground()` **不能等到推理中途才调**。Android 12+ 禁止应用在后台启动前台服务，
而插件自己没有 Activity —— 它能不能起，取决于**被绑定那一刻宿主在不在前台**。

```kotlin
// ✅ 正确：bind 成功后（宿主还在前台）立刻起，带一条"准备中"通知
override fun onBind(intent: Intent): IBinder {
    goForeground("准备中…")        // 先占住前台身份
    worker.execute { /* 之后再慢加载模型 */ }
    return binder
}

// ❌ 错误：等到 synthesize 里才起 —— 用户此时可能已切后台，直接抛
//    ForegroundServiceStartNotAllowedException
```

### 完整时序

```kotlin
// 用户点「生成」的瞬间（宿主还在前台）
//   ① 宿主 window.addFlags(FLAG_KEEP_SCREEN_ON)     ← 宿主侧，屏幕归宿主管
//   ② 宿主 bindService(插件)                        ← 此刻进程算前台
//   ③ 插件 onBind 里立刻 goForeground("准备中…")     ← 拿到前台身份
//   ④ 插件 worker 线程 loadEngine（几十秒，不怕被杀）
//   ⑤ synthesize → 更新通知为「已生成 X 秒」
//   ⑥ 结束/取消/失败/onDestroy → 撤 wakeLock + stopForeground(STOP_FOREGROUND_REMOVE)

wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PonkoPlugin:vox").apply {
    setReferenceCounted(false)
}
wakeLock.acquire(30 * 60 * 1000L)   // 上限 30 分钟，防泄漏
```

**两条职责划分**：
- **屏幕亮** → 宿主加 `FLAG_KEEP_SCREEN_ON`（窗口级，屏幕是宿主的）
- **进程不死** → 插件 `PARTIAL_WAKE_LOCK` + 前台服务（进程是自己的）

**四处都要撤**：正常结束 / 用户取消 / 出错 / `onDestroy`。
少一处就会留下一个"永远转圈"的通知，或者耗电不停。

---

## 九、Ponko 宿主侧要新增的东西

```
com.litertchat.app.plugin/
├── PluginConst.kt          常量（与 SDK 同一份，靠 AAR 引入）
├── PluginDiscovery.kt      queryIntentServices + 包信息 + 缓存
├── PluginConnection.kt     bind/unbind 状态机 + IPonkoCallback 实现
├── PluginSession.kt        对 UI 暴露的简化 API（loadEngine/synthesize 的封装）
└── PluginRegistry.kt       已启用插件列表持久化（SharedPreferences）
```

**UI 挂点**（2.0.0 范围）：
- 「关于」页 → 新增「插件」入口 → 插件列表页（启用/停用/查看版本）
- 对话页：AI 回答气泡加一个「朗读」按钮，有 TTS 插件时出现

**Manifest 增量**：
```xml
<permission android:name="com.litertchat.ponko.permission.BIND_PLUGIN"
            android:protectionLevel="signature" />
<queries>
    <intent><action android:name="com.litertchat.ponko.PLUGIN" /></intent>
</queries>
```
（`<queries>` 是 Android 11+ 包可见性必需，漏了会查不到任何插件）

**不需要新增权限**：`FLAG_KEEP_SCREEN_ON` 零权限，`WAKE_LOCK` 已有。

---

## 十、插件侧要写的代码（以 VoxCPM2 为例）

### 10.0 关键结论：**JNI 零改动**（原方案这里算错了）

我原以为需要给 JNI 加「FD 变体」，**实际上不需要**。理由：v1 是**插件自管模型**，
三个 I/O 全是路径，插件用自己的路径就行。

| 数据 | 现状 | v1 怎么办 | JNI 改动 |
|---|---|---|---|
| 模型 `base_lm` / `acoustic` | 路径 | 插件自己的目录 → 直接传路径 | **0** |
| 参考音频 | 路径 | 宿主传 FD → 插件**复制到自己的 cacheDir** → 传路径 | **0** |
| 输出 WAV | 路径 | 插件写自己 cacheDir → `open` 拿 FD → 回传宿主 | **0** |

参考音频只有几秒到几十秒（几百 KB 到几 MB），复制一次的开销可以忽略。
**这是"插件自管模型"这个决策带来的额外好处**：把跨进程文件问题全挡在 Kotlin 层，
原生层一行不用碰。

> 那 60 行 `fdopen` 变体是给 **v2「宿主共享模型」** 准备的，v1 用不上。
> 保留在设计里是因为已验证可行（`gguf.h:83` / `llama.h:499` / `llama-mmap.cpp:399`），
> 到时不用重新论证。

### 10.1 只有「流式回传」需要改 JNI（+50~80 行）

现在 `nativeGenerate` 的回调只是把 chunk 攒进 `std::vector<float> wav`
（`voxcpm2_jni.cpp:249`），**没有实时回传给 Java**。要实现 `onAudioChunk` 必须改：

```cpp
// 初始化时把 Java 回调存成 GlobalRef
static jobject g_java_cb = nullptr;
static jmethodID g_mid_on_chunk = nullptr;

// 在 C++ 的 chunk callback 里
VoxCPM2AudioChunkCallback cb = [&](const std::vector<float> & chunk, bool fin) {
    if (g_cancel.load()) return false;
    // float → int16
    std::vector<int16_t> pcm(chunk.size());
    for (size_t i = 0; i < chunk.size(); ++i)
        pcm[i] = (int16_t)std::clamp(chunk[i] * 32767.0f, -32768.0f, 32767.0f);

    JNIEnv * env = nullptr;
    g_vm->AttachCurrentThread(&env, nullptr);          // 推理在独立线程，必须 attach
    jbyteArray arr = env->NewByteArray(pcm.size() * 2);
    env->SetByteArrayRegion(arr, 0, pcm.size() * 2, (const jbyte *) pcm.data());
    env->CallVoidMethod(g_java_cb, g_mid_on_chunk, arr, sr, 1, fin);
    env->DeleteLocalRef(arr);
    g_vm->DetachCurrentThread();
    return !g_cancel.load();
};
```

三个坑：
1. **必须 `AttachCurrentThread`** —— 推理跑在自己的线程，不是 JNI 进来的线程
2. **`DeleteLocalRef`** —— 不删会撑爆 local ref 表（默认 512 个，chunk 多了必崩）
3. **`GlobalRef` 记得在 `nativeFree` 里 `DeleteGlobalRef`**

**但 v1 建议先不做流式。** 理由见 10.2。

### 10.2 流式在 VoxCPM2 上收益有限（建议 v2 再做）

RTF 10–30 意味着**生成速度远慢于播放速度**。流式能做的是"把首音延迟从 90 秒降到 15 秒"，
但一旦开始播，**十几秒后就会追上生成进度 → 卡顿/断续**。

真实体验是：等 15 秒听到开头，播 10 秒，然后间歇性停顿。
**这比"等 90 秒听完整段"更难受**（后者至少知道还要多久）。

所以 v1 用 `streamAudio=false` 的**非流式**：进度条 + `onStage` 报"已生成 X 秒"，
生成完一次性交付。用户能看到进展，也不会被断续音频折磨。

### 10.3 改动量汇总（修正后）

| 现有 | 改成 | 行数变化 |
|---|---|---|
| `MainActivity`（616 行 UI） | **删掉** | −616 |
| `VoxCpm2Bridge`（45 行） | 保留，JNI 声明不变 | 0 |
| `ModelStore`（178 行） | 保留，删 UI 依赖 | −30 左右 |
| `AudioUtil`（209 行） | 保留，加「FD → 临时文件」与「文件 → FD」两个小函数 | +40 |
| `voxcpm2_jni.cpp`（343 行） | **v1 不动**（流式才 +50~80） | **0** |
| 新增 `PonkoPluginService` | 状态机 + 回调 + 保活 + 内存检查 | +300 |
| 新增 `AndroidManifest.xml` | Service 声明 + signature 权限 | +15 |

**净新增约 350 行 Kotlin，原生层零改动。**

---

## 十之一、检查时发现方案缺的 5 件事（已补进设计）

### ① 前台服务有真实的启动失败风险（**必须改实现时机**）

Android 12+ 禁止「应用在后台时启动前台服务」，否则抛 `ForegroundServiceStartNotAllowedException`。
**插件自己没有 Activity**，它能不能起前台服务，取决于进程算不算"前台"。

- ✅ **Ponko 在前台时 bind → 插件进程重要性跟随 client = FOREGROUND → 可以 `startForeground()`**
- ❌ 如果等到用户在后台触发（比如点通知上的按钮）才起 → 可能直接抛异常

**所以时机必须是**：用户点「生成」→ 宿主**此时在前台** → bind 插件 →
插件在 `onBind` 返回后**立刻** `startForeground()`（带一条"准备中"通知）→ 再开始加载。

**不能**等到推理跑到一半才想起来起前台服务。

### ② 内存是最大真实风险（8 GB 手机会崩）

- 宿主对话模型：1–4 GB（用户自选，可能很大）
- 插件 VoxCPM2：**2.83 GB**（Q4_K_M + Acoustic F16）
- 两者相加 + 系统占用 → **8 GB 机型边缘，6 GB 必 OOM**

**设计里必须加**：
1. 插件 `loadEngine` 前用 `ActivityManager.getMemoryInfo()` 检查可用内存，
   不够直接回 `ERR_OUT_OF_MEMORY` + 一句"请先在「模型」页卸载其它模型"
2. 宿主在收到这个错误时，**提供一键卸载对话模型的按钮**（`ChatCore.release()` 已有，
   但真正释放还要 `model.close()`，由持有者负责）
3. 插件的 `EngineConfig` 加一个 `maxMemoryBytes` 字段，让宿主主动限制
4. **不建议**宿主自动卸载——用户可能正在等一条长回答，静默卸载会让他莫名其妙

### ③ `voiceDescription` 不是参数，是**文本前缀**

VoxCPM2 的"语音设计"没有独立参数，实现方式是**在文本开头加括号描述**
（`voxcpm2 README:138`）：
```
"(A young woman, gentle and sweet voice)Hello, welcome!"
```
所以 `SynthesisRequest.voiceDescription` 是**给插件用的便捷字段**，
插件内部要拼成 `"(${desc})${text}"` 再传 JNI。

**必须在 SDK 文档里写明**，否则插件作者会以为漏了参数。
另外：**`text` 里如果用户自己写了括号开头，插件不要再加前缀**（避免双前缀）。

### ④ `isEngineReady()` / `getStatus()` 必须是 O(1)

这两个是**同步** AIDL 方法（非 `oneway`），会在宿主的 Binder 线程上等返回。
如果插件实现里加了锁、或者要等推理的 mutex，**会把宿主的 Binder 线程卡住**。

**规则**：内部只读一个 `@Volatile` 字段，**不加锁、不查文件、不调原生**。

### ⑤ `oneway` 回调没有背压，必须自己限流

`IPonkoCallback` 是 `oneway`：插件发得快、宿主处理慢 → 消息在宿主侧排队，
极端情况会把宿主的 Binder 缓冲吃满（占掉那 1 MB 配额，连累其它 IPC）。

**约定**：
- `onStage` 最小间隔 **500 ms**（不要每步都发）
- `onAudioChunk` **只在 `req.streamAudio == true` 时发**，且每块 ≤ 64 KB
- `onResult` / `onError` **只发一次**（插件内部用 `AtomicBoolean` 保证）

---

## 十一、分阶段计划

| 阶段 | 内容 | 产出 | 状态 |
|---|---|---|---|
| **P0** | SDK module + AIDL 编译通过 | `ponko-plugin-sdk-release.aar`（72 KB） | ✅ 2026-10-20 |
| **P1** | 宿主发现 + 绑定 + 插件列表 UI | 「关于 → 管理插件」可用 | ✅ 2026-10-20 |
| **P2** | 示例插件（Echo）验证整条链路 | 独立 APK，能试听出声 | ✅ 2026-10-20 |
| **P3** | VoxCPM2 插件（Service + 加载 + 合成） | 真模型出声 | 待做 |
| **P4** | 对话气泡「朗读」按钮 | 完整可用 | 待做 |
| **P5** | 错误处理、超时、崩溃恢复、内存检查 | 健壮性 | 部分完成 |
| **P6（v2）** | 流式音频 + 宿主共享模型（FD） | 增强 | 待做 |

### P1 宿主侧新增（2026-10-20）

```
app/src/main/java/com/litertchat/app/plugin/
├── PluginRegistry.kt      启用列表持久化（键用包名）
├── PluginDiscovery.kt     queryIntentServices 扫描 + 签名指纹
├── PluginConnection.kt    单插件连接：bind/unbind、版本校验、回调转发、5s 绑定超时
├── PluginSession.kt       多插件门面：按能力选插件、speak()、状态聚合
├── PluginPage.kt          「关于 → 插件」页面
└── PluginActivity.kt      承载 PluginPage 的独立 Activity
```

Manifest 增量：
- `<queries>` 加 `com.litertchat.ponko.PLUGIN`（**漏了会静默扫不到插件**）
- `<permission android:name="com.litertchat.ponko.permission.BIND_PLUGIN"
   android:protectionLevel="signature" />`
- `<activity android:name=".plugin.PluginActivity" android:exported="false" />`

字符串：`s_454` ~ `s_466`（中英各一套）。

### P2 示例插件（Echo）

```
plugin-echo/                     独立 APK，包名 com.litertchat.plugin.echo
├── build.gradle.kts             com.android.application + 依赖 :ponko-plugin-sdk
└── src/main/
    ├── AndroidManifest.xml      Service（exported + permission + PLUGIN action）
    ├── res/drawable/ic_plugin.xml   自绘图标（不用宿主立绘，避免美术授权问题）
    └── java/.../echo/
        ├── EchoInfoActivity.kt  桌面图标点开的说明页
        └── EchoPluginService.kt 不加载模型，合成一段 C5-E5-G5 琶音
```

**Echo 存在的意义**：它不含模型、不含 `.so`，装完只有 2.4 MB。
能在几十秒内验证「发现 → 绑定 → 版本校验 → 进度回调 → **音频 FD 跨进程回传** → 解绑」
整条链路。以后接真模型时若出问题，可以直接排除接口层。

**它同时是 VoxCPM2 插件的模板**：把 `synthesizeBlocking` 里的琶音生成
换成真正的推理调用即可，其余（Manifest、保活、错误映射）都不用动。

### P0 实际产出（2026-10-20）

```
ponko-plugin-sdk/
├── build.gradle.kts          com.android.library + kotlin-parcelize + buildFeatures.aidl
└── src/main/
    ├── AndroidManifest.xml   空（SDK 不含组件声明）
    ├── aidl/com/litertchat/plugin/
    │   ├── IPonkoPlugin.aidl          主接口（15 方法）
    │   ├── IPonkoCallback.aidl        回调（11 方法：v4 加 2、v7 加 2）
    │   └── 12 个 Parcelable 声明文件
    └── java/com/litertchat/plugin/
        ├── PluginConst.kt             API 版本 / 角色 / 模式 / 限流常量
        ├── PluginManifest.kt          插件自述
        ├── ModelBundle.kt             ModelBundle + ModelEntry
        ├── EngineConfig.kt            EngineConfig + EngineInfo + EngineStatus
        ├── SynthesisRequest.kt        合成请求
        ├── ResultBundle.kt            ResultBundle + PluginError
        ├── PluginException.kt         插件作者 throw 的错误
        └── PonkoPluginService.kt      基类（线程/前台/唤醒锁/状态机/去重）
```

**编译验证**：
- `:ponko-plugin-sdk:assembleRelease` → **BUILD SUCCESSFUL**，AAR 72,027 B
- `classes.jar` 里确认有 `IPonkoPlugin$Stub` / `IPonkoCallback$Stub` / 各 `$Creator`
- `:app:compileReleaseKotlin` → **BUILD SUCCESSFUL**（加了 SDK 依赖后 app 照样编过）

**⚠ P0 踩到的坑（已写进 build.gradle.kts 注释）**：
**AGP 9.x 里 `aidl` 默认是关闭的**（早期版本默认开）。
不开 `buildFeatures { aidl = true }` 时 `.aidl` 完全不参与编译，
Kotlin 侧报 `Unresolved reference 'IPonkoPlugin'`，而且**不会提示"aidl 没开"**，
只有一堆看不懂的 unresolved reference。

**P1 的假插件很关键**：它不含任何模型和 `.so`，只回显字符串。
能在一小时内验证"绑定/版本/回调/解绑"整条链路，避免后面被原生层的问题干扰。

---

## 十二、待你确认的三件事

1. **插件 UI 放哪**：「关于 → 插件」还是顶部加第 5 个 tab？
   （建议前者，2.0.0 阶段不占主界面）
2. **朗读入口**：只做「回答气泡长按 → 朗读」，还是也做「自动朗读每条回答」？
   （建议只做前者，后者会很吵）
3. **versionCode**：`1.7.2 = 15`，`2.0.0` 用 16 还是跳到 20？
   （注：hw 线用的也是 16，但包名是 `com.litertchat.app.hw`，**互不影响**；
   只是记忆里看到 16 要记得分线）

---

## 十三、插件设置界面的归属（2026-10-21 定）

**决策：宿主用插件提供的「配置」来渲染，插件自带 UI 只作为逃生口。**

### 为什么不让插件自己画

| 理由 | 说明 |
|---|---|
| **插件读不到宿主主题** | `PonkoTheme` 在 `app` module，**不在 SDK 里**。插件要配色只能自己重写一套，还得跟着 Ponko 的浅/深色各写一遍 |
| **主题切换要免费** | 宿主渲染 → 用户换深浅色时插件界面自动跟着变；插件自带 UI 就永远停在旧配色 |
| **升级解耦** | Ponko 改设计规范，声明式界面自动跟着变；自带 UI 的插件要跟着发新版 |
| **插件作者省力** | 写一份字段清单，比写 Android 布局 + 适配两套主题容易得多 |
| **视觉一致性** | 插件应该是「Ponko 的一个能力」，不是「套在 Ponko 里的另一个应用」 |

⚠ 现状反例：`EchoInfoActivity` 用的是裸 `Theme.DeviceDefault`，
和 Ponko 的卡片/圆角/配色完全两回事 —— 这就是「插件自带 UI」的真实后果。

### 术语

- **插件设置（Settings）**：由**宿主渲染**的字段表单，插件只给 schema + 值
- **高级界面（Advanced）**：插件自带的 Activity，宿主提供入口打开，**只在设置表达不了时才用**

### 三、设置 schema 的数据结构

```aidl
// ---- 新增到 IPonkoPlugin ----
/** 返回当前设置项（含当前值）。宿主拿它渲染整页 */
UiSchema getSettingsSchema();

/** 用户改了某个值。宿主负责调用，插件负责持久化 */
oneway void setSetting(in String key, in String value);

/** 用户点了某个 TYPE_ACTION 的按钮 */
oneway void invokeAction(in String key);

// ---- 新增到 IPonkoCallback ----
/** 插件自己改动了设置（如下载进度、模型就绪）→ 宿主重新拉 schema 重画 */
void onSchemaChanged();

// ---- v4 追加到 IPonkoCallback（末尾）----
/** 送一张结果图给 UiType.IMAGE；FD 归宿主，插件不要先 close */
void onImage(in ParcelFileDescriptor fd, int width, int height, in @nullable String mime);

/** 原地改一个控件，不重画整页：日志追加、进度推进、状态换值 */
void onUiPatch(in String key, in @nullable String value, boolean append);

// ---- v6 追加到 IPonkoPlugin（末尾）----
/**
 * 宿主替用户选好了模型文件，把只读 FD 交过来。
 * 对应 UiType.MODEL_SLOT 的「导入」按钮。
 *
 * ⚠ 选择器必须**宿主**弹：插件是 bound Service，没有 startActivityForResult，
 *   Android 10+ 也禁止它后台启动 Activity。
 * ⚠ 插件要在 worker 线程读，读完 close 掉这个 FD（是 dup 过的一份）。
 */
oneway void importModel(in String role, in ParcelFileDescriptor fd,
                        in String displayName, long sizeBytes);

/** 删除某个槽位已导入的文件（插件删自己私有目录里的那份） */
oneway void deleteModel(in String role);
```

```kotlin
@Parcelize
data class UiSchema(
    /** 整页顶部的说明，可为空 */
    val intro: String = "",
    val items: List<UiItem> = emptyList(),
) : Parcelable

@Parcelize
data class UiItem(
    /** 稳定键。插件用它存值；宿主只在回调时原样带回，不解释 */
    val key: String = "",
    /** 见下面的 TYPE_* */
    val type: Int,
    val label: String = "",
    /** 次要说明，显示在控件下方（可以为空） */
    val help: String = "",
    /** 当前值（字符串化）：开关 "true"/"false"，滑块 "2.0"，单选是 option.value */
    val value: String = "",
    /** TYPE_SLIDER 用 */
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 0.1,
    /** TYPE_CHOICE 用 */
    val options: List<UiOption> = emptyList(),
    /** false = 灰显（如模型没下载时禁用"试听"） */
    val enabled: Boolean = true,
    /** true = 视觉上强调（如"下载模型"这种关键动作） */
    val primary: Boolean = false,
) : Parcelable

@Parcelize
data class UiOption(val value: String, val label: String) : Parcelable

object UiType {
    const val INFO    = 0  // 纯文本段落（label 即正文）
    const val SECTION = 1  // 分组标题
    const val SWITCH  = 2  // 布尔
    const val SLIDER  = 3  // 数值（min/max/step），宿主显示当前值
    const val CHOICE  = 4  // 单选（宿主画成下拉或分段）
    const val TEXT    = 5  // 单行文本输入
    const val ACTION  = 6  // 按钮，点了走 invokeAction(key)
    // ---- v2 ----
    const val PROGRESS   = 7   // 只读进度条（value = 0–100）
    const val STATUS     = 8   // 只读键值行（左 label 右 value）
    const val TEXTAREA   = 9   // 多行文本输入
    const val GROUP_START = 10 // 可折叠分组开始
    const val GROUP_END   = 11 // 分组结束
    // ---- v4 ----
    const val LOG     = 12  // 只读日志框（配 onUiPatch 追加）
    const val IMAGE   = 13  // 结果图（配 onImage 送图）
    const val AUDIO   = 14  // 播放条（播宿主持有的那份产物）
    // ---- v5 ----
    const val TABS_START = 15  // 标签页组开始（key 记上次停在哪页）
    const val TAB        = 16  // 一页（label 是标签文字）
    const val TABS_END   = 17  // 标签页组结束
    // ---- v6 ----
    const val MODEL_SLOT = 18  // 模型文件槽位（导入/清除由宿主出按钮）
    // ---- v7 ----
    const val HOST_SLOT  = 19  // 宿主功能块挂在这里（不写就还在标题下面）
    const val MAX_KNOWN = 19
}
```

**类型集合是刻意克制的**。每加一种，宿主就要多一份渲染代码 + 多一套主题适配；
最早那 7 种能覆盖「配置型插件」的绝大多数需求（VoxCPM2 全部设置都在其中）。
v2 加到 12 个、v4 加到 15 个、v5 加到 18 个、v7 加到 19 个（编号 0–19），
都是被具体场景逼出来的，
不是为了"提供更多选择"。

### ⚠ 加一种控件类型 = 主 app 必须更新

这一点决定了整个方案能不能接受，必须讲清楚：

| 插件改了什么 | 要更新插件 APK | 要更新主 app |
|---|---|---|
| 换文字、换顺序、加/删已有类型的项 | ✅ | ❌ |
| 换布局结构（分组、折叠） | ✅ | ❌ |
| 改默认值、加新逻辑 | ✅ | ❌ |
| **用了一个宿主不认识的控件类型** | ✅ | ✅ **必须** |

原因很简单：**控件是宿主画的**。宿主不认识类型 12，就画不出来。

缓解手段有三条，已全部实现：

1. **一次放够类型**。2026-10-21 补到 12 种，全部来自「做 VoxCPM2 时一定会撞上」的
   真实场景（进度条、状态行、多行文本、可折叠分组）。2026-10-22 又补了日志、结果图、
   播放条（v4）、标签页（v5）、模型槽位（v6）、宿主功能块槽位（v7）。
   宁可多放，不要以后被迫发版。
2. **老宿主优雅降级**。`type > UiType.MAX_KNOWN` 时不崩、不整页空白，
   显示成「标签 + 值 + 这个控件需要更新 Ponko」的文字块。核心功能不受影响。
3. **`UiType.MAX_KNOWN`**：插件作者可以据此判断"我用的类型是不是太新了"。

**v4 的两个新回调是单向不对称的**：新插件调老宿主的 `onImage` / `onUiPatch` 时，
oneway 调用**静默无操作** —— 不报错、也不生效。所以插件必须按
`API_VERSION >= 4`（或宿主报的版本）判断，别假定宿主一定收到了。

### v2 补充的 5 种（2026-10-21）

| 类型 | 值 | 用途 | 缺了会怎样 |
|---|---|---|---|
| `PROGRESS` | 0–100 百分比 | 模型下载、长任务 | 界面不动，用户以为卡死并强杀 |
| `STATUS` | 显示用的值 | 「引擎：已就绪」这类只读信息 | 能用 `INFO` 凑，但和正文混在一起看不清 |
| `TEXTAREA` | 多行文本 | 音色描述、提示词 | `TEXT` 是单行，长文本没法输入 |
| `GROUP_START` | — | 可折叠分组开始（`key` 记折叠状态） | 参数十几个，一屏铺满要滚很久 |
| `GROUP_END` | — | 分组结束（**不支持嵌套**） | — |

### v4 补充的 3 种（2026-10-22）：过程与产物

前面 12 种都是**配置项** —— 用户改它、插件读它。这三种不是：

| 类型 | 值 | 用途 | 缺了会怎样 |
|---|---|---|---|
| `LOG` | 12 | 只读日志框（固定 160dp 高、内部滚动、长按可复制） | 加载/推理日志只能塞 `debugInfo`，用户要开 adb 才看得到 |
| `IMAGE` | 13 | 结果图（等比、点击全屏） | 图像插件根本送不出图 |
| `AUDIO` | 14 | 播放条（播放/暂停 + 可拖进度 + 时间） | 只有宿主那个「播放」按钮，不能 seek、看不到进度 |

**它们不参与 `setSetting`** —— 用户改不了。内容走另外两个回调：

```kotlin
// 追加一行日志（append = true）。**不要用 onSchemaChanged**，那会整页重建：
// 滚动位置复位、输入框失焦、正在编辑的 TEXTAREA 被覆盖
callback.onUiPatch("log", "[12:03:41] loading tokenizer…\n", true)

// 推进度（append = false 表示替换）
callback.onUiPatch("dl", "37.5", false)

// 送一张结果图。FD 所有权归宿主，插件不要自己 close
val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
callback.onImage(pfd, 1024, 1024, "image/png")
```

`AUDIO` 不需要插件送任何东西 —— 它播的是**宿主持有**的那份产物
（宿主最近一次合成结果）。插件只是要求"在这一页放一个播放器"。

#### `LOG` 的跟随行为（2026-10-22 定）

追加内容时，日志框**只在用户本来就停在底部**才自动滚到最新一行，容差 8dp。
用户往上翻看历史时，新日志不会把他拽回底部 —— 这是刻意做的，
"每一行都抢着把视图拉到底"会让日志框根本没法回看。

对插件的影响：**你不需要为滚动做任何事**，也不需要自己判断"用户在看哪里"。
但也别指望用 `onUiPatch` 去控制滚动位置 —— 补丁只改文字。

> 实现上不能用 `ScrollView.fullScroll(View.FOCUS_DOWN)`：它内部走
> `scrollAndFocus()` → `requestChildRectangleOnScreen()`，这个请求会**沿 View 树
> 向上冒泡**，外层整页 ScrollView 会跟着把日志滚进视野 —— 表现是"日志一有内容，
> 整个页面唰地跳过去"。宿主已改用 `scrollTo()`（只改自己的 `mScrollY`）。
> 这条写在这里是因为它是个很容易重犯的坑：`fullScroll` 看起来是更"正确"的写法。

### v5 补充：标签页（页中页，可以套多层）

三个类型凑成一组，和 `GROUP_START`/`GROUP_END` 同一套思路，所以**天然支持嵌套**
—— 页中页中页只是再套一层：

```kotlin
UiSchema(items = listOf(
    UiItem(type = UiType.TABS_START, key = "main", label = ""),

    UiItem(type = UiType.TAB, key = "param", label = "参数"),
    // 这一页里再分一层
    UiItem(type = UiType.TABS_START, key = "param.mode", label = ""),
    UiItem(type = UiType.TAB, key = "t2i", label = "文"),
    UiItem(type = UiType.TEXT, key = "prompt", label = "提示词"),
    UiItem(type = UiType.TAB, key = "i2i", label = "图"),
    UiItem(type = UiType.IMAGE, key = "ref", label = "参考图"),
    UiItem(type = UiType.TAB, key = "tag", label = "标签"),
    UiItem(type = UiType.TEXT, key = "tags", label = "标签词"),
    UiItem(type = UiType.TABS_END),

    UiItem(type = UiType.TAB, key = "result", label = "结果"),
    UiItem(type = UiType.IMAGE, key = "out", label = "输出"),
    UiItem(type = UiType.AUDIO, key = "play", label = "试听"),

    UiItem(type = UiType.TABS_END),
))
```

要点：

| 规则 | 说明 |
|---|---|
| 一定要成对 | `TABS_START` / `TABS_END` 少写一个，后面的项会落进最后一个还开着的区块 |
| `TABS_START.key` 必写 | 这是"上次停在哪一页"的键；空的话每次重画都跳回第一页 |
| `TAB.key` 也建议写 | 宿主按 **key** 而不是索引记选中页 —— 你把页的顺序调整了也能回到同一个 |
| 标签文字要短 | 一行放 3~4 个是上限，超了被省略号截断。五个以上在手机上已经难用了 |
| 切页不通知插件 | 宿主只改 `visibility`。**插件自己的状态要留在自己那边**，别指望靠 tab 切换触发什么 |

**切换是纯 visibility，不重建。** 所以切走再切回来，输入框内容、滚动位置、
滑块值都还在。这是刻意做的：反过来（切页重建）会滚动复位 + 输入框失焦。

**降级提醒**：老宿主（v4 及更早）不认识这三个类型，会把它们**逐个**当成
"未知控件"画成文字块 —— 界面上会是一串方块加断断续续的内容。
所以想在老宿主上也体面，先用版本号判断再决定用不用。

#### 可以左右滑动（2026-10-22 补）

**最外层**那组标签页会渲染成可滑动的分页，跟手左右翻，和「绘图页」的
「参数 / 结果」一个手感。内层嵌套的仍然只点标签切换，不滑动 ——
内外两层都能横滑的话手势会互相抢。

对插件的约束只有一条：**想要滑动手感，就别把最外层标签页塞进
`GROUP_START` 里**（那也算嵌套，会退化成点击切换）。

一个已知取舍：pager 的高度按**最高那一页**定，所以短页下方会留白。
按当前页定高的话，从长页滑到短页时中间那一帧会被裁掉半截，更像出故障。

### v6 补充：模型文件导入（`MODEL_SLOT`）

插件可以声明"我要这几份模型文件"，由**宿主**弹系统文件选择器，
选完把 FD 交给插件，插件自己复制进自己的私有目录。

```kotlin
override fun buildSettingsSchema() = UiSchema(items = listOf(
    UiItem(key = "base_lm", type = UiType.MODEL_SLOT,
           label = "主干模型 BaseLM",
           value = if (baseLmReady) fmtSize(baseLmSize) else "",   // 空 = 未导入
           help = "VoxCPM2 的 .safetensors 主干，约 2.4 GB"),
    UiItem(key = "acoustic", type = UiType.MODEL_SLOT,
           label = "声学模型 AudioVAE",
           value = if (acousticReady) fmtSize(acousticSize) else ""),
))

override fun onModelFileReceived(
    role: String,
    fd: android.os.ParcelFileDescriptor,
    displayName: String,
    sizeBytes: Long,
) {
    // 基类已经在 worker 线程调这里，可以同步读几百 MB
    val dst = File(filesDir, if (role == "base_lm") "base_lm.safetensors" else "acoustic.bin")
    java.io.FileInputStream(fd.fileDescriptor).use { ins ->
        dst.outputStream().use { outs -> ins.copyTo(outs, 1 shl 16) }
    }
    // fd 由基类兜底 close，自己关也可以
    notifySchemaChanged()      // 让宿主重画，新状态从 value 报出去
}

override fun onModelDeleteRequested(role: String) {
    File(filesDir, if (role == "base_lm") "base_lm.safetensors" else "acoustic.bin").delete()
    notifySchemaChanged()
}
```

#### 为什么选择器必须宿主弹

插件是 bound **Service**，在另一个进程里：

| 限制 | 后果 |
|---|---|
| `startActivityForResult` 是 Activity 的 API | Service 根本没有这个方法 |
| Android 10+ 禁止后台启动 Activity | 用户点「导入」时插件不在前台，真发起了也被系统拦掉 |

所以插件**不可能**自己弹文件选择器。这不是设计上偷懒，是平台限制。
（文档旧版本里"文件选择交给插件自己的 ACTION + 下载逻辑"那条，
就是因为这个原因走不通，v6 已推翻。）

#### 磁盘上只有一份

```
用户选文件(SAF) → 宿主拿 FD（不落盘、不存路径）
              → FD 转给插件（Binder dup 一份）
              → 插件复制到 /data/data/<插件包名>/files/xxx
              → 宿主那份 FD 关掉
```

最终代价和"用户在宿主模型页导入"**完全相同**：复制一次到自己的私有目录。
唯一的区别是落在哪个包下面（宿主读不到插件的目录，反之亦然）。

#### 几个容易踩的点

| 点 | 说明 |
|---|---|
| 读文件要在 worker 线程 | 基类已经放 worker 了。**别**自己改回 Binder 线程——读 2.8GB 会把线程池占死，连 `cancel` 都进不来 |
| 别关错 FD | 你拿到的是 Binder **dup** 过的一份，关它不影响宿主；不关就是泄漏（每进程 1024 个上限） |
| 状态由插件报 | `UiItem.value` 是你自己的账（如 `"2.83 GB"` / 空）。宿主不会去查你的目录——它读不到 |
| 槽位 `key` = 角色 | `key` 就是 `importModel` 的 `role`，必须非空且唯一 |
| 引擎跑着时别让换模型 | 把那个槽位的 `enabled` 设 `false`，按钮会灰掉。比等用户点了再报错体面 |

### v7 补充：插件反向指挥宿主

前三版都是"宿主问、插件答"。v7 补的是反方向 —— 这三件事都是插件开发者的
实测反馈，缺了它们，插件界面只能是个**只读的配置表单**。

#### ① `requestSynthesis()` —— 参数页里的「开始推理」

**问题**：`ACTION` 点下去只回到插件自己（`onActionInvoked`），而合成的执行者是宿主：
它读 `PreviewParams`、开参考音频 FD、发 `synthesize`、收音频、落盘。
插件没有任何办法说"现在开始合成一次"，用户只能滚回顶部去点宿主那个按钮。

**用法**：

```kotlin
UiItem(key = "go", type = UiType.ACTION, label = "开始推理", primary = true)

override fun onActionInvoked(key: String) {
    if (key == "go") notifyRequestSynthesis()
}
```

**语义**：

| 情况 | 结果 |
|---|---|
| 正常 | 宿主走**和用户点自己那个按钮完全同一条路径**：同样的参数校验、同样的参考音频 FD、同样的进度回报 |
| 参数不合法（文本空 / 缺参考音频 / 缺逐字稿） | 不发起，把那条拒绝理由显示在功能区的提示行上 |
| 已经在合成中 | 这一次被丢掉（不排队）—— 用户连点两下不该跑两遍一分钟的推理 |
| 没有返回值 | 想知道开没开始，看 `onStage` 有没有来。`oneway` 方法拿不到回执 |

**只有插件界面开着时才有意义**：所谓"当前参数"就是这一页上填的那些值，
页面不在就没有可用的参数，请求会被丢弃（logcat 有一条 `请求合成，但它的界面没开着`）。

> 如果只是想让按钮"待在参数页里"，其实用 ③ `HOST_SLOT` 把宿主那块整体搬过去更省事：
> 那是宿主自己的按钮，文案、可用态、进度全都自动正确。`requestSynthesis` 用在
> "我要自己排版、自己决定按钮长什么样"的场合。

#### ② `HOST_SLOT` —— 把宿主的功能块搬进你的 tab

```kotlin
TABS_START(key = "main")
  TAB(key = "param", label = "参数")
    UiItem(type = UiType.HOST_SLOT)        // ← 功能块画在这里
    UiItem(key = "cfg", type = UiType.SLIDER, label = "CFG", …)
  TAB(key = "result", label = "结果")
    UiItem(key = "play", type = UiType.AUDIO, label = "试听")
TABS_END
```

功能块 = 「加载引擎 / 合成 / 播放 / 停止 / 保存」那一排按钮 + 宿主的试听参数区
（目标文本、模式、参考音频、逐字稿、种子、线程、CPU/GPU）+ 进度提示 + 诊断。

| 规则 | 说明 |
|---|---|
| **只认第一个** | 放多个只有第一个生效，其余的什么都不画 |
| **不写就不变** | 没这个 item 的插件，功能块仍在标题下面（完全向后兼容） |
| **放进哪个 tab 就只在那个 tab 出现** | 宿主不会额外复制一份到别处 |
| **字段全忽略** | `key`/`label`/`value` 都不用，写 `key` 只为你自己好读 |
| 该页没有功能块 | 用户就看不到合成按钮 —— 这时配 ① `requestSynthesis` 自己出一个 |

**为什么不干脆让插件重画整个功能块**：那排按钮背后是宿主的状态机
（引擎是否已加载、是否正在合成、参考音频 FD、保存到音乐库）。插件重画就得把这些
也搬过去，两边各存一份必然打架。这里只改**挂载点**，逻辑仍然只有一份。

#### ③ `onAudioFile(key, fd, name)` —— 历史记录每条独立播/存

**问题**：v4 的 `AUDIO` 播的是"宿主最近一次产物"，界面上放 5 条历史记录，
点哪条都是**最新那条**的声音。

**用法**：

```kotlin
// 每生成一条就送一份，key 和 schema 里那条 AUDIO 对齐
notifyAudioFile("hist-3", File(dir, "hist-3.wav"))

UiItem(key = "hist-3", type = UiType.AUDIO, label = "第 3 条 · 12:03")
```

| 行为 | 说明 |
|---|---|
| 同一个 key 再送 = 替换 | 宿主删掉上一份，`tts/` 目录不会越攒越多 |
| 没送过文件的 key | 退回播宿主最近产物（v4 行为，老插件不用改） |
| 播放条上多一个「保存」 | 存的是**那一条**，不是最新那条 |
| 同一时间只响一条 | 点第 5 条会把第 3 条暂停 —— 两段声音叠着像"卡了" |
| 文件格式 | 按你给的文件名的扩展名存（`.wav` / `.mp3` / `.m4a`），`name` 传 null 就当 wav |

#### ④ `GROUP_START` 的 `value` —— 默认收起

```kotlin
UiItem(key = "advanced", type = UiType.GROUP_START,
       label = "高级参数", value = "collapsed")   // ← 默认收起
```

之前 `value` 对 `GROUP_START` **完全没被读取**，插件没有任何表达方式，
参数十几个的插件只能把用户丢进一屏长滚动里。

- 用户手动折过之后以用户的选择为准（`groupOpen` 优先），不会被下次刷新顶回默认
- 其它 `value` 一律当展开（只有 `"collapsed"` 这一个开关值，大小写不敏感）





`PonkoPluginService` 提供了几个 `protected` 辅助方法，插件直接用，不用碰 AIDL：

| 方法 | 用途 |
|---|---|
| `notifySchemaChanged()` | 整页重画（**能不用就不用**） |
| `notifyUiPatch(key, value, append)` | 原地改一个控件 |
| `notifyImage(fd / file, w, h, mime)` | 送结果图 |
| `notifyRequestSynthesis()` | v7：请宿主用当前参数合成一次 |
| `notifyAudioFile(key, fd / file, name)` | v7：给 `AUDIO` 条目送它自己的音频 |

```kotlin
class MyPluginService : PonkoPluginService() {

    private val logBuf = StringBuilder()
    private var lastFlush = 0L
    private var pct = 0.0

    override fun buildSettingsSchema(): UiSchema = UiSchema(
        items = listOf(
            // 日志框：key 用来收补丁，value 是**初始**内容
            UiItem(key = "log", type = UiType.LOG, label = "运行日志",
                   value = logBuf.toString(), help = "长按可复制"),
            // 进度条：value 是 0–100
            UiItem(key = "dl", type = UiType.PROGRESS, label = "下载模型",
                   value = pct.toString(), help = "1.05 / 2.83 GB"),
            // 结果图：value 是**还没图时**的占位文字
            UiItem(key = "out", type = UiType.IMAGE, label = "生成结果",
                   value = "点上面的按钮生成"),
            // 播放条：播宿主那份产物，插件什么都不用传
            UiItem(key = "play", type = UiType.AUDIO, label = "试听"),
        )
    )

    /** 刷日志。**必须节流** —— 每读一行就发一个 Binder 事务会吃满配额 */
    private fun log(line: String) {
        logBuf.append(line).append('\n')
        val now = SystemClock.uptimeMillis()
        if (now - lastFlush < 100) return
        lastFlush = now
        notifyUiPatch("log", line + "\n", append = true)
    }

    private fun setProgress(p: Double) {
        pct = p
        notifyUiPatch("dl", "%.1f".format(p), append = false)   // false = 替换
    }

    private fun deliverImage(f: File, w: Int, h: Int) {
        // FD 交给宿主后**不要自己 close**
        notifyImage(f, w, h, "image/png")
    }
}
```

四个容易踩的点：

1. **`log()` 里的节流不能省。** `onUiPatch` 是 `oneway`、没有背压。日志刷得快、
   宿主处理慢，消息会在宿主侧排队，极端情况吃满那 1MB Binder 配额并连累其它 IPC。
2. **`value` 只用于首次/整页刷新。** 之后的变化一律走 `notifyUiPatch` ——
   往 schema 里塞最新日志再 `notifySchemaChanged()` 会整页重建，
   用户滚到一半被拉回顶部、输入框失焦。
3. **key 必须和 schema 里那个 `UiItem.key` 完全一致。** 对不上宿主静默忽略，
   不会去猜"你大概想改哪个"。整页只有一个日志框时，空 key 能兜底，但只有这一种情况。
4. **老宿主收不到 `notifyImage` / `notifyUiPatch`。** oneway 调一个不存在的方法会
   **静默无操作**，不报错也不生效。要可靠的话先判断宿主报的 API 版本。

### ⚠ `onUiPatch` 的两个硬限制

1. **key 必须和 schema 里那个 item 完全一致。** 对不上就静默忽略（宿主不会去猜）。
   整页只有一个日志框时，key 写空也能兜底追加 —— 但也只在这一种情况下兜底。
2. **要节流。** 和所有回调一样是 oneway、没有背压。刷日志建议 ≥100ms 合并一次；
   每读一行就发一个 Binder 事务，宿主那 1MB 配额会被吃满。

### 值归谁管
**插件管。** 宿主不持久化任何设置值 —— 它只是每次 `getSettingsSchema()` 拿最新状态来画。
理由：插件最清楚自己的默认值、模型是否就绪、哪些值互斥；宿主存一份必然会出现
「宿主的值和插件的不一致」，那是很难查的 bug。

### 渲染规范（宿主侧）

- 用 `PluginPage` 现有的卡片风格：`pageTitle` / `hintText` / `smallButton` / `rounded(pal.surface)`
- **颜色全部取自 `pal`**，不写死
- 滑块显示「标签 · 当前值」，拖动时不上报（`onStopTrackingTouch` 才 `setSetting`），
  否则拖动过程会刷几十次 Binder
- `TYPE_ACTION` 用 `smallButton`，`primary=true` 时用 `actionButton`（主色填充）
- 插件返回 `enabled=false` 的项灰显 + 不可点，**不要隐藏**（用户要能看到"为什么不能做"）

### ~~「进阶设置」逃生口~~ —— 未实现，已作废（2026-10-21）

**这一节描述的东西从来没有实现过。** `PluginManifest` 里没有 `advancedActivity` 字段，
宿主也没有任何地方读它。留着会误导插件作者去声明一个不存在的属性，所以标记作废。

如果以后确实需要（波形预览、自定义手势这类 schema 表达不了的东西），
实现时要注意：插件 Activity 自己画界面 = 自己处理深浅色、字号缩放、横竖屏，
而且它读不到宿主的 `PonkoTheme`。这等于放弃了"所有插件自动跟主题"这个好处，
不能当成随手加的逃生口 —— 真要做，得先想清楚主题怎么同步。

**优先考虑的方向**仍然是在宿主侧加布局能力（比如 `ROW_START` / `ROW_END`），
而不是把整页布局交给插件。

### 已实现（2026-10-21 02:28）

**SDK 侧**（`ponko-plugin-sdk`）：
- `UiSchema.kt`：`UiSchema` / `UiItem` / `UiOption` 三个 `@Parcelize` + `UiType` 常量
  （v1 七个、v2 十二个、v4 十五个、v5 十八个，编号 0–17）
- `IPonkoPlugin.aidl` 新增：`UiSchema getSettingsSchema()`（同步）、
  `oneway setSetting(String,String)`、`oneway invokeAction(String)`
- `IPonkoCallback.aidl` 新增：`void onSchemaChanged()`
- `PonkoPluginService` 新增三个 **open** 方法（不覆写就是空页面）：
  `buildSettingsSchema()` / `onSettingChanged(key,value)` / `onActionInvoked(key)`，
  以及给插件用的 `notifySchemaChanged()`

**v4 追加（2026-10-22）**：
- `UiType` 补 `LOG` / `IMAGE` / `AUDIO`（当版本 `MAX_KNOWN` 由 11 → 14）
- `IPonkoCallback.aidl` 末尾追加 `onImage(...)` / `onUiPatch(...)`
- `PonkoPluginService` 追加两个 protected 方法 `notifyUiPatch(key,value,append)` /
  `notifyImage(fd|file, w, h, mime)` —— **不给这两个方法，插件根本调不到新回调**
  （`callback` 字段是 private，此前只有 `notifySchemaChanged()` 一个出口）

**v5 追加（2026-10-22）**：
- `UiType` 补 `TABS_START` / `TAB` / `TABS_END`（`MAX_KNOWN` 14 → 17），
  宿主用渲染栈实现，嵌套层数不限
- 无新增回调 —— 标签页是纯 schema 结构，不涉及跨进程通信

**宿主侧**（`app`）：
- `PluginUiPage.kt`：schema → 控件的渲染器，
  v1 的 7 种 + v2 的 5 种 + v4 的 3 种 + v5 的 3 种全部实现（共 18 种）
- `PluginConnection`：`settingsSchema()` / `setSetting()` / `invokeAction()`
- `PluginSession`：`schemaOf(pkg)` / `setSetting(pkg,…)` / `invokeAction(pkg,…)`
  + `Listener.onPluginSchemaChanged(pkg)`
  + v4 的 `Listener.onPluginImage` / `Listener.onPluginUiPatch`
- 卡片上加了「插件界面」按钮（**已连接才显示**）
- 插件界面**叠在列表上**，用 `visibility` 切换而不是新 Activity：
  主题/配色/标题栏自动一致，也不用在 Manifest 里多声明 Activity。
  返回键优先关掉它（`MainActivity.onBackPressed`），否则按返回会直接退出 App。
- 空 schema 显示成「这个插件没有可配置项」的**空页面**，不是错误

### 渲染时的四个约定（实现里已遵守）

1. **值全部取自 `pal`**，没有一处写死颜色 → 主题切换自动跟随
2. **滑块拖动中不上报**，`onStopTrackingTouch` 才 `setSetting`；
   拖动期间还禁止整页重画（`draggingKey`），否则控件会被换掉、手感断裂
3. **单选只重画同组的选中态**，不整页重画 —— 整页重画会让滚动位置跳动
4. **`enabled=false` 灰显但不隐藏**，用户要能看到"为什么这个不能点"

---

## 十三之一、插件必须有一个可启动的 Activity（stopped 状态）

**这不是建议，是硬要求。** 没有它，宿主永远连不上插件。

⭐ **但「必须有 Activity」≠「必须有桌面图标」。** 见下面「隐藏式激活组件」。

### 问题

新安装（或刚被「强制停止」）的包处于系统 **stopped** 状态。此时别的应用
`bindService` 到它的服务会**静默返回 false** —— 不抛异常、不报错，
日志里只有一句 `Unable to start service ...: not found`。

### 解除方式只有一个

**包自己的 Activity 被启动一次。**

| 尝试过的手段 | 能清 stopped 吗 |
|---|---|
| 启动 Activity | ✅ **唯一有效** |
| 发广播（含 `FLAG_INCLUDE_STOPPED_PACKAGES`） | ❌ |
| `startService` | ❌ |
| `pm enable` / `disable` | ❌ |
| 重装同一个 APK | ❌ |

Android 15 起官方态度更明确：**只能由用户的直接或间接操作解除**
（分享菜单、桌面小部件等算"间接"）。

### 所以插件要写两处

**1. Manifest 里声明 MAIN/LAUNCHER Activity**（哪怕只是一页说明）

```xml
<activity android:name=".MyInfoActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

（这是**旧做法**，桌面会有图标。推荐用下面「隐藏式激活组件」代替。）

### ⭐ 隐藏式激活组件（推荐，2026-10-21 加）

**目标：桌面无图标 + 启动时无界面，但 stopped 仍能清掉。**

关键认识：**清 stopped 只要求「某个组件被启动」，不要求那个组件对用户可见。**
所以把「可启动」和「可见」拆开就行。

SDK 提供了 `PonkoActivatorActivity` 基类，插件只需两处：

**① Manifest** —— 注意**不要**加 `CATEGORY_LAUNCHER`：

```xml
<activity
    android:name=".MyActivator"
    android:exported="true"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:taskAffinity=""
    android:theme="@android:style/Theme.NoDisplay">
    <intent-filter>
        <action android:name="com.litertchat.ponko.ACTIVATOR" />
    </intent-filter>
</activity>
```

**② Kotlin** —— 一行：

```kotlin
class MyActivator : PonkoActivatorActivity()
```

**效果**：

| 项 | 结果 |
|---|---|
| 桌面图标 | ❌ 没有（无 `CATEGORY_LAUNCHER`） |
| 启动时的界面 | ❌ 没有（`Theme.NoDisplay` + `onCreate` 里立刻 `finish()`） |
| 转场动画 | ❌ 没有（`overridePendingTransition(0,0)`） |
| 最近任务 / 返回栈 | ❌ 不出现（`excludeFromRecents` / `noHistory`） |
| 能清 stopped | ✅ 能 |
| 用户在哪看到这个插件 | Ponko 的插件列表 + 系统「设置 → 应用」 |

**四个属性缺一不可**，尤其 `Theme.NoDisplay` —— 它要求 `onResume` 之前必须
`finish()`，否则系统会抛 `did not call finish() prior to onResume()`。
基类已经处理好了。

**宿主怎么发现它**：`PluginDiscovery` 先查 `ACTION_ACTIVATOR`，
查不到才退回 `CATEGORY_LAUNCHER`（兼容旧插件）。

**代价**：用户装完在桌面找不到任何东西。这是有意的 ——
插件的名字、版本、能力在 Ponko 的插件列表里都能看到，不需要多一个说明页。
**2. 在那页的 `onCreate` 第一行加一句**

```kotlin
if (PonkoActivation.consumeIfActivationOnly(this)) return
```

### 为什么加那一句，体验差很多

宿主为了清标记而启动这一页时，会带 `EXTRA_ACTIVATE_ONLY`：

| 有没有那一句 | 用户看到什么 |
|---|---|
| **有** | 屏幕闪一下 → 自动回到 Ponko → 已连上 |
| **没有** | 被扔进插件的说明页 → 得自己按返回 |

而且宿主已经把这一步做成**自动的**：用户点「启用」时如果检测到 stopped，
会自己拉起激活页（用户不需要去桌面找图标）。**用户只按一次键。**

### 什么时候会再次变成 stopped

只有三种情况：卸载重装 / 用户在设置里「强制停止」/
部分国产 ROM 的"清理后台"。**正常使用不会反复出现。**

---

## 十三之二、Ponko 更新后，插件要不要跟着更新？（2026-10-21 定）
**多数情况不用。** 这取决于改动落在哪一层：

| 改了什么 | 插件要动吗 | 原因 |
|---|---|---|
| **宿主 UI / 功能 / 文案**（改卡片、改主题、加页面） | ❌ 不用 | 插件只通过 AIDL 和宿主说话，宿主内部怎么改它不知道 |
| **宿主内部重构**（换实现、拆分文件） | ❌ 不用 | 同上 |
| **SDK 加新方法 / 新字段 / 新常量**（扩展） | ⚠️ 旧插件继续能跑，但**用不了新能力** | Binder 事务码按声明顺序分配，加在末尾不影响老方法 |
| **SDK 删方法 / 改参数语义**（破坏） | ✅ **必须重编** | 老插件调用会失败或语义错乱 |
| **改了插件的 Manifest 要求**（如必须加某个权限） | ✅ 必须改 | 系统层拦截，宿主帮不了 |

**结论**：只有 SDK 的**破坏性**改动才强制所有插件重编。这种改动应该尽量避免 ——
每来一次，所有插件作者都要跟着发版，生态会散掉。

### 靠什么保证这件事

**API_VERSION 就是这个开关。** 规则：

- **扩展**（加方法、加字段、加常量）→ `API_VERSION` +1、`API_MAX_SUPPORTED` 跟着 +1，
  `API_MIN_SUPPORTED` **不动**。老插件继续被接受，宿主按版本号判断"哪些新能力可用"。
- **破坏**（删方法、改语义）→ `API_VERSION` **+1**，`API_MIN_SUPPORTED` **跟着抬**。
  老插件被明确拒绝，界面上说"请更新插件"。

**`API_MAX_SUPPORTED` 不是拒绝阈值**，是"宿主认识哪些新东西"的上限。
`api > API_MAX_SUPPORTED` 只意味着插件用了宿主还不认识的控件/回调，
**照常绑定**，提示用户"更新 Ponko 可获得完整功能"。

### ⚠ 三个必须遵守的规则

**1. 新方法只能加在 AIDL 文件最末尾**

Binder 的事务码按**声明顺序**分配。在中间插一个方法，后面所有方法的事务码整体后移 ——
老插件收到的码对不上，会**执行错的方法**（本该调 A 却跑了 B），比直接报错糟糕得多。

**2. 判断"新能力是否可用"必须看版本号，不能靠 try-catch**

AIDL 调用遇到对方不认识的方法时，行为**不保证抛异常**：
同步方法可能读到空 Parcel 而返回 null 或怪值，`oneway` 方法则**静默无操作**。
所以宿主在调用前就用 `pluginApi >= API_WITH_SCHEMA` 判断，而不是"调了再说，崩了再兜"。

**3. 插件编译时用哪个 SDK，就报哪个版本**

`API_VERSION` 是编译期写死进插件二进制的。所以"旧插件报 1、新插件报 2"这件事
自动成立，不需要插件作者做任何事。

### 现状（v7 = 2026-10-22）

| 版本 | 引入 | 宿主能否连接 |
|---|---|---|
| v1 | 基础：绑定、加载、合成、取消 | ✅ 支持 |
| v2 | 插件界面（`getSettingsSchema` / `setSetting` / `invokeAction`） | ✅ 支持 |
| v3 | 按钮文案（`getActionLabels()`） | ✅ 支持 |
| v4 | 日志框 / 结果图 / 播放条 + `onImage` / `onUiPatch` | ✅ 支持 |
| v5 | 标签页（`TABS_START` / `TAB` / `TABS_END`），可多层嵌套 | ✅ 支持 |
| v6 | 模型槽位（`MODEL_SLOT`）+ `importModel` / `deleteModel` | ✅ 支持 |
| v7 | `requestSynthesis` / `onAudioFile` / `HOST_SLOT`，以及分组的默认收起 | ✅ 支持 |

**「能否连接」和「能力是否齐」是两件事。** 上表问的是前者，答案永远是"能"——
只要 `apiVersion >= API_MIN_SUPPORTED`（现在是 1）就会绑定。
后者要看 `apiVersion` 落在哪：

| 插件版本 | 跑在当前宿主（v7）上的实际效果 |
|---|---|
| v1 | 能合成，没有界面 |
| v2 / v3 | 有界面，缺日志框/结果图/播放条 |
| v4 | 有过程与产物，缺标签页、模型槽位、反向指挥 |
| v5 | 缺模型槽位、反向指挥 |
| v6 | 缺 `requestSynthesis` / `onAudioFile` / `HOST_SLOT` |
| v7 | 全部功能 |
| v8+（未来） | 老功能照常，新控件降级成文字块，新回调被丢弃 |

宿主自己的 `API_MAX_SUPPORTED` 只影响最后一行的**提示文案**，不影响能否连接。

- `API_VERSION = 7`，`API_WITH_SCHEMA = 2`，`API_WITH_ACTION_LABELS = 3`，
  `API_WITH_RICH_UI = 4`，`API_WITH_TABS = 5`，`API_WITH_MODEL_IMPORT = 6`，
  `API_WITH_SYNTH_REQUEST = 7`（v7 的三项共用这一个版本号 —— 常量名沿用最先落地的那个，
  判断写 `api >= API_WITH_SYNTH_REQUEST` 即可）
- 宿主兼容下限 `API_MIN_SUPPORTED = 1` → **v1–v7 老插件照常连接、照常合成**，
  只是拿不到对应版本才有的能力：
  · v1 插件没有插件界面，卡片上不显示「进入」，并提示"插件 SDK v1（当前 v7）"
  · v1 / v2 插件不能改按钮文案，一律用宿主默认
  · v1–v3 插件的日志/图片/播放条三项不会出现（它们不认识这些类型）
  · v1–v4 插件没有标签页
  · v1–v5 插件没有模型槽位
  · v1–v6 插件不能反向指挥宿主（`requestSynthesis` / `onAudioFile` / `HOST_SLOT`），
    每条历史记录也只能播"最近一次产物"
- `API_MAX_SUPPORTED = 7` 是**认识上限**，不是拒绝阈值。
  `api > 7` 的插件仍会连接，只是新控件降级、新回调被丢弃
- `API_MIN_SUPPORTED` **一直是 1** —— 至今所有改动都是扩展，没有破坏性改动

### v3 的 `getActionLabels()`：为什么是一个方法，不是 manifest 的字段

本来最自然的做法是给 `PluginManifest` 加一个 `actionLabels` 字段。**没这么做**，原因很具体：

`PluginManifest` 是 `@Parcelize` 生成的，`CREATOR` 按固定顺序读字段。
老插件用旧 SDK 编，它写出来的 Parcel **少一个字段**；宿主用新 SDK 去读，
会读到 Parcel 末尾之外 → 抛异常。也就是说加字段等于**所有插件必须同时重编**，
而这恰恰是版本机制想避免的事。

想绕开就得手写 `PluginManifest` 的 `CREATOR`，在里面按 `apiVersion` 判断要不要读新字段。
那比加一个方法复杂得多，而且以后每加一个字段都要再想一遍。

所以：**新方法加在 AIDL 末尾，宿主按 `pluginApi >= API_WITH_ACTION_LABELS` 决定要不要调。**
既保住了 Parcelable 的顺序安全，也不用碰老插件的二进制。

### 按钮文案：插件只能改名，不能改行为

`getActionLabels()` 返回 `["engine.load=加载模型", ...]`。宿主只取其中的**文字**。

**位置、个数、颜色、可点状态、点了做什么，全部由宿主决定。** 这不是限制得不够大方，
而是必要的：如果插件能把「停止」改叫「保存」，用户会按字面理解去点，然后丢掉正在生成的东西。
名字可以商量，行为不能外包。

六个 key（2026-10-22 从七个减到六个，见下）：

| key | 宿主默认 | 说明 |
|---|---|---|
| `engine.load` | 加载引擎 | 引擎未就绪 |
| `engine.unload` | 卸载引擎 | 引擎已就绪（同一个按钮） |
| `reasoning` | 合成 | **只跑推理产出文件，不播放** |
| `play` | 播放 | 只重放，不推理 |
| `stop` | 停止 | 打断当前生成 |
| `save` | 保存 | 写进系统音乐库 |

具体约束（`ActionLabel.parse` / `PonkoPluginService.getActionLabels` 各拦一次）：

| 情况 | 处理 |
|---|---|
| key 不在 `ActionLabel.ALL` 里 | 丢弃该项，其它项照常生效 |
| 值为空 | 丢弃该项 |
| 值超过 `ActionLabel.MAX_LEN`（12 字符） | **整个丢弃**，不截断 |
| 重复的 key | 后一个覆盖前一个 |
| 方法抛异常 | 全部退回宿主默认，**不影响连接** |

**为什么超长是丢弃而不是截断**：截出来是「加载模型 3.5…」，比原来那句默认文案更难懂。
不如让用户看到完整但有意义的话。

**为什么失败不影响连接**：文案是锦上添花。插件写错一个 key 就不让用户用这个插件，
是把装饰性错误当成了致命错误。

### `preview` → `reasoning`，并删掉 `preview.again`（2026-10-22）

这个改动很小，但值得记：**它是把一个症状当成需求设计过的例子。**

原来的行为是「合成完立刻自动播放」。于是同一个按钮有两种含义 ——
还没听过时它意味着"听一下"，听过之后同一次点击应该意味着"再算一遍"。
为了区分，加了第二个 key `preview.again`，在合成完成的瞬间给按钮改名。

问题出在第一步：**合成和播放被绑成了一个动作**。用户想生成一段音频时被迫听完，
在安静场合只能立刻点停止。而 `.again` 只是为这个绑定打的补丁 ——
它解决的是"按钮含义漂移"，而漂移本身是自动播放造成的。

改法：

- 合成**只产出文件，不出声**。状态提示说「已生成 X 秒音频，点『播放』试听」
- 于是点几次「合成」都是同一个动作，**不存在需要改名的第二种状态**
- `preview.again` 删掉，key 从七个减到六个
- `preview` 改名 `reasoning`：`preview / 试听` 描述的是"听"，而按钮做的是跑推理

**教训**：当一个功能需要"第二次点它时改个名字"时，先怀疑是不是两个动作被绑在了一起。
改名是权宜，拆开才是解法。

⚠ v3 当时尚未发布，所以直接改了 key 的字符串值，没有留兼容别名。
如果有插件已经按 `preview` 写过覆盖，改那一个词即可。

### 默认文案在宿主，不在 SDK

`ActionLabel`（SDK）里**只有 key 和解析逻辑**，没有默认文字。
默认表在宿主的 `app/.../plugin/ActionLabels.kt`。

理由：默认文案是宿主界面的一部分。放进 SDK 等于让每个插件 APK 都打包一份
别的 App 的中文字符串，而且做多语言时要改两处、还得让所有插件重编才生效。

---

## 十四、插件项目已拆分为独立工程 Ponko-EX（2026-10-21）

**改动**：插件不再放在宿主工程里。宿主只保留 SDK；插件是独立 Gradle 工程。

```
LiteRT-Chat/                       ← 宿主（com.litertchat.app）
├── ponko-plugin-sdk/              ← 只留 SDK，负责发布 AAR
└── app/src/main/java/.../plugin/  ← Discovery / Connection / Session / Page / Registry

Ponko-EX/                          ← 插件项目（独立工程，自己的 git 与发版节奏）
└── plugins/echo/                  ← 主线测试插件
```

> **2026-10-21 位置变更**：Ponko-EX 现在是**独立仓库**
> （[visitor257/Ponko-EX](https://github.com/visitor257/Ponko-EX)，MIT），
> 不再放在宿主的源码树里。请把它 clone 到一个**纯 ASCII 路径**下构建
> （AGP 拒绝非 ASCII 构建路径），这样就不用再复制沙箱副本。

**依赖方式：本地 maven 仓库**（不用 `project(...)`，跨项目不可用）

| 环节 | 做法 |
|---|---|
| 发布 | 宿主里跑 `gradle :ponko-plugin-sdk:publishReleasePublicationToLocalRepoRepository` |
| 仓库位置 | `C:\ponko-maven`（**ASCII 路径** —— AGP 拒绝中文路径） |
| 消费 | Ponko-EX 的 `settings.gradle.kts` 里 `maven { url = uri("file:///C:/ponko-maven") }` |
| 坐标 | `com.litertchat.ponko:plugin-sdk:1.0.0` |

⚠ **改了 AIDL / Parcelable 必须重新发布 SDK**，否则插件侧用的还是旧接口。
症状是运行时 `NoSuchMethodError` 或 Parcel 读写错位 —— 极难查。

**为什么"拆出去"比"放一起"好**：

1. 发版节奏不同 —— 宿主一年发几次，插件按需发
2. 插件只依赖 AAR，不碰宿主任何实现代码（`PonkoTheme` 这类东西本来就该留在宿主里）
3. VoxCPM2 这类大插件以后另开一条线，不污染主线测试插件
4. 各自的 git 历史、各自的 tag

**构建**：工程在桌面（纯 ASCII 路径）→ 就地构建：`tools\build.bat :plugins:echo:assembleRelease`。
（原先是中文路径，需要同步到 `C:\PonkoExBuild`；移走之后那一步已取消。）

---

## 十五、底部菜单：Ponko-EX 成为第 4 个 tab（2026-10-21）

**用户要求**：插件入口从「关于」页移到底部菜单，顺序为
**对话 · 绘图 · 模型 · Ponko-EX · 关于**。

### 索引映射（改前 → 改后）

| 页 | 旧索引 | 新索引 |
|---|---|---|
| 对话 | 0 | **0** |
| 模型 | 1 | **2** |
| 绘图 | 2 | **1** |
| Ponko-EX | — | **3**（新增） |
| 关于 | 3 | **4** |

### 换序时要一起改的四处（漏一处就会出现"点错页"）

1. `switchTab()` 里的 `visibility` 映射
2. `buildBottomNav()` 的 `items` 列表
3. `buildUi()` 里 `contentFrame.addView` 的顺序
4. `restoreTab in 0..4`（换主题后恢复那一页，越界会静默失效）

另外**散落在业务代码里的 `switchTab(N)` 也要查** —— 本次修了
`switchTab(2)` → `switchTab(1)`（对话里「发送图片到图生图/打标」）。

### 生命周期

`PluginPage` 现在挂在 tab 里，不再有独立 Activity：

- **切换 tab 时不 `release()`** —— 切回来要保留插件连接与引擎状态
- **`MainActivity.onDestroy()` 才 `release()`** —— 解绑插件、停掉试听播放
- 换主题时（`applyThemeInPlace` → `buildUi`）复用同一个 `PluginPage` 实例，
  只 `setPalette()` + 重新 `build()`，连接不重建

### 其他清理

- 删除 `PluginActivity.kt` 与 Manifest 声明（不再需要独立 Activity）
- 「关于」页里那个「管理插件」按钮也一并删掉（已有 tab 入口）
- `PluginPage.build()` 现在**自带 ScrollView**，调用方不用再包一层

---

## 十六、这一版刻意不做的事（防止范围膨胀）

- ❌ 不做插件市场 / 在线安装（用户自己下载 APK 装上）
- ❌ 不做插件自动更新
- ❌ 不做三级扩展（插件再加载插件）
- ❌ 不做跨插件通信
- ❌ 不做插件权限细分（v1 要么全信要么不用）
- ❌ 不做 `ModelBundle` 的 FD 路由实现（接口留好，v2 再实现）
  —— ⚠ 2026-10-22 更新：这条仍然成立，但 **v6 改了做法**。
  插件要"用宿主已导入的模型"依然没做（`ModelBundle` 还是 `null`）；
  做的是另一件事：**插件自己导入自己的模型**，走 `UiType.MODEL_SLOT` +
  `importModel`，宿主只转发 FD、不留副本。见 3.x「模型文件导入」。
- ~~❌ 设置 schema 不做嵌套布局（不能一行放两个控件，不做 tab / 折叠）~~
  —— **部分作废**：`GROUP_START/END`（v2）和 `TABS_START/TAB/TABS_END`（v5）已实现，
  tab 可多层嵌套。**仍然不做**的是「一行放两个控件」这种横向布局
- ~~❌ 设置 schema 不做 TYPE_MODEL_SLOT~~
  —— **已推翻**（v6 实现了 `MODEL_SLOT`）。当初的理由是"文件选择交给插件自己的
  ACTION + 下载逻辑"，但那条路**根本走不通**：插件是 bound Service，
  没有 `startActivityForResult`，Android 10+ 也禁止它后台启动 Activity。
  所以选择器只能宿主弹 —— 那就必然需要一个宿主侧的槽位控件
- ~~❌ 合成的发起方只能是宿主~~
  —— **已推翻**（v7 实现了 `requestSynthesis`）。当初的理由是"参数归宿主、
  执行归宿主，插件没必要发起"，但实际做插件时"参数页里放一个能用的开始按钮"
  是最基本的要求，而用户必须滚回顶部点按钮这件事本身就是缺陷。
  **参数仍然归宿主**（插件不传参数，只说"现在开始"），所以没有出现两套参数
- ~~❌ 功能块只能挂在标题下面~~
  —— **已推翻**（v7 实现了 `HOST_SLOT`）。当初根本没考虑过"插件想把宿主控件
  放进自己 tab"这种需求；实现成本只是改挂载点，逻辑仍只有一份

