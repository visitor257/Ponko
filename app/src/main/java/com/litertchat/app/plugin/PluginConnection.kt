// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import com.litertchat.app.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.litertchat.plugin.IPonkoCallback
import com.litertchat.plugin.IPonkoPlugin
import com.litertchat.plugin.PluginConst
import com.litertchat.plugin.PluginError
import com.litertchat.plugin.PluginManifest
import com.litertchat.plugin.ResultBundle
import com.litertchat.plugin.Stage
import com.litertchat.plugin.EngineInfo

/**
 * 与**一个**插件的连接。负责 bind / unbind、版本校验、回调转发、超时与崩溃处理。
 *
 * ## 线程模型
 *
 * - 所有 AIDL 调用都在**后台线程**发起（[io] 单线程 executor）
 * - 插件的回调从**插件的 Binder 线程**进来 → 一律 `main.post{}` 后交给 [Listener]
 * - [Listener] 的所有方法都保证在主线程被调用
 *
 * ## 生命周期
 *
 * ```
 * connect()  → 绑定中 → 绑上 → 版本校验 → CONNECTED
 *                    ↘ 5 秒没绑上 → FAILED（多半是插件进程起不来：缺 .so / ABI 不符）
 *   ↓
 * loadEngine() → LOADING → onEngineLoaded → READY
 *   ↓
 * synthesize() → onStage / onResult / onError
 *   ↓
 * disconnect() → 解绑 + closeEngine
 * ```
 */
class PluginConnection(
    private val context: Context,
    val packageName: String,
) {

    /**
     * 取本地化字符串（2026-10-23 接 i18n 时加）。
     *
     * 这个类不是 `Context`，只持有 `context` 字段，所以没有继承来的
     * `getString`。走 `context.getString` —— 它带当前配置（含语言）。
     */
    private fun getString(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    /** 连接状态。UI 直接拿它决定按钮文案 */
    enum class State { IDLE, BINDING, CONNECTED, FAILED, DISCONNECTED }

    interface Listener {
        fun onStateChanged(state: State, detail: String?) {}
        fun onManifest(manifest: PluginManifest) {}
        fun onEngineLoaded(info: EngineInfo) {}
        fun onEngineError(err: PluginError) {}
        fun onStage(stage: Int, label: String?) {}
        fun onAudioChunk(pcm: ByteArray, sampleRate: Int, channelCount: Int, isFinal: Boolean) {}
        fun onResult(result: ResultBundle) {}
        fun onError(err: PluginError) {}

        /** 插件自己改了界面数据（下载进度、模型就绪等）→ UI 应重新拉 schema 重画 */
        fun onSchemaChanged() {}

        /**
         * 插件送了一张结果图（v4）。[bytes] 已经**读进内存**，FD 在回调前就关了 ——
         * 见 [callback] 里的说明，FD 不能跨线程传。
         */
        fun onImage(bytes: ByteArray, width: Int, height: Int, mime: String?) {}

        /**
         * 插件送图并指明了目标控件（v11）。
         *
         * 默认实现**退化成 [onImage]** —— 只关心"收到图了"的调用方不用改。
         */
        fun onImageWithKey(key: String, bytes: ByteArray, width: Int, height: Int, mime: String?) {
            onImage(bytes, width, height, mime)
        }

        /** 插件要求滚到某个控件（v12） */
        fun onScrollTo(key: String) {}

        /**
         * 插件要求**原地**改一个控件（v4）。不走整页重画。
         *
         * @param append true = 追加（日志）；false = 替换（进度、状态）
         */
        fun onUiPatch(key: String, value: String?, append: Boolean) {}

        /**
         * 插件请求宿主**用当前参数合成一次**（v7）。
         *
         * 什么时候算「当前参数」由宿主决定（就是插件页上那份 `PreviewParams`）。
         * 插件页没开着时这条请求没有意义 —— 那时没有"当前参数"。
         */
        fun onSynthesisRequested() {}

        /**
         * 插件给某个 `AUDIO` 条目送了自己那份音频（v7）。
         *
         * [bytes] 已读进内存、FD 已关（同 [onImage] 的理由：FD 不能跨线程传）。
         *
         * @param key 对应 `UiItem.key`；空 = 绑定到"最近一次产物"那个位置
         */
        fun onAudioFile(key: String, bytes: ByteArray, displayName: String?) {}

        /**
         * 插件要求宿主切换到某一页（v8）。
         *
         * @param groupKey `TABS_START` 的 key
         * @param tabKey   目标 `TAB` 的 key
         */
        fun onSelectTab(groupKey: String, tabKey: String) {}
    }

    companion object {
        private const val TAG = "PonkoPluginConn"

        /** 绑定超时。超过这个时间没 `onServiceConnected`，基本可以判定插件进程起不来 */
        private const val BIND_TIMEOUT_MS = 5_000L

        /**
         * 结果图的大小上限（32MB）。
         *
         * 一张 4096×4096 的 PNG 大约 20MB 上下，留一倍余量。
         * 超了直接丢 —— 结果图是给人看的，不是数据通道；
         * 真有大图需求该走"存成文件、把路径还给用户"，不是塞进界面。
         */
        private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

        /**
         * 插件送来的单份音频上限（64MB）。
         *
         * 48kHz / 16bit / 单声道 WAV 约 96KB/s，64MB ≈ 11 分钟。
         * 历史记录场景下插件送的是**已经生成好**的片段，不该有这么长的；
         * 真超过说明用法不对（想拿宿主当存储用），直接丢比 OOM 好。
         */
        private const val MAX_AUDIO_BYTES = 64 * 1024 * 1024
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var plugin: IPonkoPlugin? = null
    @Volatile private var state: State = State.IDLE
    @Volatile private var manifest: PluginManifest? = null

    /**
     * 最近一次状态变化的说明（失败原因等）。
     *
     * 必须存下来：UI 每次重建视图都会重新读一遍状态，如果只靠回调里的瞬时参数，
     * 用户切个 tab 回来就看不到失败原因了 —— 那等于白诊断。
     */
    @Volatile var lastDetail: String? = null
        private set

    var listener: Listener? = null

    /** 当前状态（主线程读也行，因为都是 volatile） */
    val currentState: State get() = state

    /** 插件自述；绑定并校验通过后才有值 */
    val currentManifest: PluginManifest? get() = manifest

    /**
     * 插件编译时用的 SDK 版本；未连接时 0。
     *
     * 用途有两个：
     * 1. 决定**要不要调**插件界面那组方法（见 [supportsSchema]）
     * 2. UI 上提示"这个插件偏旧"，让用户知道更新插件可能多出功能
     */
    @Volatile var pluginApi: Int = 0
        private set

    /**
     * 插件比宿主认识的 SDK 更新（`pluginApi > API_MAX_SUPPORTED`）。
     *
     * **这种情况下插件照常能用**，只是：
     * · 宿主不认识的新控件类型会降级成「标签 + 值 + 需要更新 Ponko」
     * · 新回调（如 v4 的 `onImage` / `onUiPatch`）会被静默丢弃
     *
     * UI 上要提示用户"更新 Ponko 可获得完整功能"——
     * **别说成"不兼容"**，那会让用户去更新一个已经最新的插件，白折腾。
     */
    @Volatile var pluginNewerThanHost: Boolean = false
        private set

    /**
     * 这个插件是否支持界面（SDK v[PluginConst.API_WITH_SCHEMA] 起）。
     *
     * **必须按版本判断，不能靠 try-catch 兜** —— AIDL 调用遇到不认识的函数时，
     * 同步方法可能读到空 Parcel 返回 null 或怪值，oneway 方法则静默无操作。
     * 把它当异常处理是不可靠的。
     */
    val supportsSchema: Boolean
        get() = pluginApi >= PluginConst.API_WITH_SCHEMA

    /**
     * 这个插件能不能自己命名宿主的按钮（SDK v[PluginConst.API_WITH_ACTION_LABELS] 起）。
     *
     * 同样的理由：按版本判断，不靠 try-catch。
     */
    val supportsActionLabels: Boolean
        get() = pluginApi >= PluginConst.API_WITH_ACTION_LABELS

    /**
     * 插件给的按钮文案（key → 文字）。连接时拉一次，之后不再变。
     *
     * 为什么只在连接时拉：这些是**静态文案**，和引擎状态无关
     * （"加载"和"卸载"是两个不同的 key，不是同一个 key 的两个值）。
     * 每次打开界面都跨进程拉一遍纯属浪费。
     */
    @Volatile private var actionLabels: Map<String, String> = emptyMap()

    /**
     * 宿主当前界面语言的 BCP 47 标签（`"zh-CN"` / `"en"` / `"ru"` …）。
     *
     * ## 为什么要"从 context 取"而不是 `Locale.getDefault()`
     *
     * `Locale.getDefault()` 是**进程级**的默认值，在 Android 13+ 有人给单个
     * App 设过语言时，它和这个 App 实际在用的语言可能不一致。
     * `context.resources.configuration.locales[0]` 拿到的才是**本 App 正在用的那个**，
     * 也就是用户在界面上看到的语言 —— 传给插件时应该给这个。
     *
     * 取 `locales[0]` 而不是拼 `language + "-" + country`：标签可能是
     * `zh-Hans-CN`、`sr-Latn` 这种带文字系统的，拼字符串会把它们截坏。
     * 让 `toLanguageTag()` 生成，插件那边用 `Locale.forLanguageTag()` 解。
     */
    private fun currentLocaleTag(): String {
        val loc = try {
            context.resources.configuration.locales[0]
        } catch (t: Throwable) {
            // 极小概率拿不到（context 被回收等）→ 退回进程默认，不要因此不让绑定
            java.util.Locale.getDefault()
        }
        return loc.toLanguageTag()
    }

    /**
     * 取按钮文字：插件覆盖过就用它的，没覆盖用宿主默认（走资源，跟随系统语言）。
     *
     * @param key 见 [com.litertchat.plugin.ActionLabel]
     */
    fun label(key: String): String = ActionLabels.resolve(context, key, actionLabels)

    /** 宿主生成的请求 ID，用于丢弃过期回调 */
    @Volatile private var activeRequestId: String? = null

    private var bindToken = 0
    private var timeoutPosted = false

    // ==================== 回调实现（跑在插件的 Binder 线程）====================

    private val callback = object : IPonkoCallback.Stub() {

        override fun onEngineLoaded(info: EngineInfo) {
            post { listener?.onEngineLoaded(info) }
        }

        override fun onEngineError(err: PluginError) {
            post { listener?.onEngineError(err) }
        }

        override fun onStage(stage: Int, label: String?) {
            post { listener?.onStage(stage, label) }
        }

        override fun onAudioChunk(pcm: ByteArray, sampleRate: Int, channelCount: Int, isFinal: Boolean) {
            post { listener?.onAudioChunk(pcm, sampleRate, channelCount, isFinal) }
        }

        override fun onResult(result: ResultBundle) {
            // 过期回调直接丢：用户取消后插件可能还在吐最后一批数据
            if (!matchesActive(result.requestId)) {
                Log.i(TAG, "丢弃过期 onResult: ${result.requestId}")
                return
            }
            post { listener?.onResult(result) }
        }

        override fun onError(err: PluginError) {
            if (err.requestId != null && !matchesActive(err.requestId)) {
                Log.i(TAG, "丢弃过期 onError: ${err.requestId}")
                return
            }
            post { listener?.onError(err) }
        }

        override fun onSchemaChanged() {
            post { listener?.onSchemaChanged() }
        }

        /**
         * v4：结果图。
         *
         * ⚠ **必须在 Binder 线程就把字节读出来，不能把 FD 交给主线程**。
         *
         * 原因有两条：
         * 1. 这个回调是 `oneway`，方法一返回，Parcel 就被回收了 ——
         *    此时插件那边可能已经把自己的文件关掉，FD 变成失效句柄
         * 2. `ParcelFileDescriptor` 跨线程持有还得自己管生命周期，
         *    漏关就是 FD 泄漏（每个进程默认 1024 个）
         *
         * 读字节有大小上限：图片不像音频，超过 [MAX_IMAGE_BYTES] 的一律丢弃 ——
         * 那已经不是"结果图"了，是插件在乱传数据。
         */
        override fun onImage(fd: android.os.ParcelFileDescriptor?, width: Int, height: Int, mime: String?) {
            readImage(fd, width, height, mime) { bytes, w, h, m ->
                post { listener?.onImage(bytes, w, h, m) }
            }
        }

        /**
         * v11：带 key 的图 —— 插件指明了贴到哪个 `IMAGE` 控件。
         *
         * 走同一条 `readImage` 读取逻辑，只是把 key 一起交给上层。
         * 单独一个回调而不是给 [onImage] 加参数，是因为**改老方法签名会破坏
         * 所有已发布插件**（Binder 事务码与参数列表都变）。AIDL 只能追加。
         */
        override fun onImageWithKey(
            key: String?,
            fd: android.os.ParcelFileDescriptor?,
            width: Int,
            height: Int,
            mime: String?,
        ) {
            val k = key ?: ""
            readImage(fd, width, height, mime) { bytes, w, h, m ->
                Log.i(TAG, "onImageWithKey key='$k'")
                post { listener?.onImageWithKey(k, bytes, w, h, m) }
            }
        }

        /** 两个 onImage 回调共用的读取逻辑（读字节 + 限长） */
        private fun readImage(
            fd: android.os.ParcelFileDescriptor?,
            width: Int,
            height: Int,
            mime: String?,
            onBytes: (ByteArray, Int, Int, String?) -> Unit,
        ) {
            if (fd == null) {
                Log.w(TAG, "onImage 收到空 fd，忽略")
                return
            }
            val bytes: ByteArray? = try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { ins ->
                    readLimited(ins, MAX_IMAGE_BYTES)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "读取结果图失败", t)
                null
            }
            if (bytes == null) return
            onBytes(bytes, width, height, mime)
        }

        override fun onScrollTo(key: String?) {
            val k = key ?: ""
            Log.i(TAG, "onScrollTo key='$k'")
            post { listener?.onScrollTo(k) }
        }

        override fun onUiPatch(key: String, value: String?, append: Boolean) {
            post { listener?.onUiPatch(key, value, append) }
        }

        override fun requestSynthesis() {
            // 没有数据要读，直接转主线程
            post { listener?.onSynthesisRequested() }
        }

        override fun onAudioFile(key: String?, fd: android.os.ParcelFileDescriptor?, displayName: String?) {
            if (fd == null) {
                Log.w(TAG, "onAudioFile 收到空 fd，忽略")
                return
            }
            val bytes: ByteArray? = try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { ins ->
                    readLimited(ins, MAX_AUDIO_BYTES)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "读取插件音频失败", t)
                null
            }
            if (bytes == null) {
                Log.w(TAG, "插件音频超过上限或读取失败，已丢弃")
                return
            }
            val k = key ?: ""
            post { listener?.onAudioFile(k, bytes, displayName) }
        }

        override fun selectTab(groupKey: String?, tabKey: String?) {
            // 两个都空就没有意义；只空一个也定位不到，交给上层记日志
            val g = groupKey ?: ""
            val t = tabKey ?: ""
            Log.i(TAG, "selectTab -> group='$g' tab='$t'")
            post { listener?.onSelectTab(g, t) }
        }
    }

    /** 读到 [limit] 字节就停。超了返回 null（宁可丢图，也不让宿主 OOM） */
    private fun readLimited(ins: java.io.InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) {
                Log.w(TAG, "结果图超过 $limit 字节，丢弃")
                return null
            }
            out.write(buf, 0, n)
        }
        return if (total == 0) null else out.toByteArray()
    }

    private fun matchesActive(id: String?): Boolean {
        val active = activeRequestId ?: return false
        return id == active
    }

    private inline fun post(crossinline block: () -> Unit) {
        main.post { runCatching(block).onFailure { Log.w(TAG, "回调处理异常", it) } }
    }

    private fun setState(s: State, detail: String? = null) {
        // 失败/断开的说明要留下；成功时清掉旧错误，否则 UI 会一直挂着过期原因
        if (detail != null) lastDetail = detail
        else if (s == State.CONNECTED || s == State.DISCONNECTED) lastDetail = null
        if (state == s) return
        state = s
        Log.i(TAG, "[$packageName] 状态 → $s${detail?.let { "（$it）" } ?: ""}")
        post { listener?.onStateChanged(s, detail) }
    }

    // ==================== 连接 ====================

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cancelTimeout()
            if (binder == null) {
                setState(State.FAILED, getString(R.string.s_670))
                return
            }
            val p = IPonkoPlugin.Stub.asInterface(binder)
            plugin = p

            // ---- 版本校验 ----
            //
            // ## 只拒绝**更低**的版本，不拒绝更高的
            //
            // 原来的判断是 `api !in MIN..MAX` → 拒绝。这个方向是错的：
            // 新版插件配旧版宿主时，宿主会**直接拒连**，用户看到
            // 「插件 SDK v4 不兼容，请更新插件」—— 但插件已经是最新的，
            // 他要更新的是 Ponko。更糟的是插件本来能用（老功能一个没少），
            // 只是宿主不认识新增的那几个控件。
            //
            // 这样做之所以安全，是因为 AIDL 只**在末尾追加**方法：
            // 老宿主收到不认识的 transaction code 会走 Stub 的 default 分支
            // 返回 false（可能是 null / 0 / 全零 Parcel），不抛异常。
            // 特性判断也都写成 `api >= API_WITH_XXX`，天然是"认不出来就当没有"。
            //
            // 所以判据是：**低于下限 = 真不兼容**（老插件缺宿主必需的方法），
            // **高于上限 = 能用，但要让用户知道更新 Ponko 才有完整功能**。
            val api = try {
                p.apiVersion
            } catch (t: Throwable) {
                setState(State.FAILED, getString(R.string.s_671, t.javaClass.simpleName))
                safeUnbind()
                return
            }
            if (api < PluginConst.API_MIN_SUPPORTED) {
                setState(
                    State.FAILED,
                    getString(R.string.s_672, api, PluginConst.API_MIN_SUPPORTED)
                )
                safeUnbind()
                return
            }
            // 记下来：决定「能不能调插件界面」以及「UI 上要不要提示版本差异」
            pluginApi = api
            pluginNewerThanHost = api > PluginConst.API_MAX_SUPPORTED
            if (pluginNewerThanHost) {
                Log.i(TAG, "[$packageName] 插件 SDK v$api 比宿主（v${PluginConst.API_MAX_SUPPORTED}）新，" +
                        "老功能照常可用，不认识的控件会降级显示")
            }

            manifest = try {
                p.manifest
            } catch (t: Throwable) {
                setState(State.FAILED, getString(R.string.s_673, t.javaClass.simpleName))
                safeUnbind()
                return
            }

            try {
                p.registerCallback(callback)
            } catch (t: Throwable) {
                setState(State.FAILED, getString(R.string.s_674, t.javaClass.simpleName))
                safeUnbind()
                return
            }

            // 按钮文案（v3 起才有）。失败**不影响连接** —— 文案是锦上添花，
            // 拿不到就全用宿主默认，没必要因此让整个插件不可用。
            actionLabels = if (api >= PluginConst.API_WITH_ACTION_LABELS) {
                try {
                    // 用方法名而不是属性语法，和 getSettingsSchema() 那处保持一致
                    com.litertchat.plugin.ActionLabel.parse(p.getActionLabels())
                } catch (t: Throwable) {
                    Log.w(TAG, "[${packageName}] 读取按钮文案失败，用默认", t)
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            if (actionLabels.isNotEmpty()) {
                Log.i(TAG, "[${packageName}] 插件覆盖了 ${actionLabels.size} 个按钮文案：$actionLabels")
            }

            setState(State.CONNECTED, manifest?.displayName)
            post { manifest?.let { listener?.onManifest(it) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 进程被杀了（OOM / 崩溃 / 用户强停）
            plugin = null
            setState(State.FAILED, getString(R.string.s_675))
        }

        override fun onBindingDied(name: ComponentName?) {
            plugin = null
            setState(State.FAILED, getString(R.string.s_676))
            safeUnbind()
        }

        override fun onNullBinding(name: ComponentName?) {
            setState(State.FAILED, getString(R.string.s_677))
        }
    }

    /** 发起绑定。重复调用安全（会先解绑旧的） */
    fun connect() {
        if (state == State.BINDING || state == State.CONNECTED) return
        bindToken++
        setState(State.BINDING)

        // 先体检：把「为什么可能连不上」在 bind 之前就查清楚。
        // 这些信息在失败时直接变成用户能看懂的一句话，省掉一整轮来回猜。
        val diag = try {
            PluginDiscovery.diagnose(context, packageName)
        } catch (t: Throwable) {
            null
        }

        val cls = diag?.serviceClass

        // ⚠ **两种 intent 都试**，不只是为了兼容 —— 它们走的是系统里**不同**的代码路径：
        //
        //   显式 ComponentName → getServiceInfo(component) → 找不到就 return null
        //   隐式 action+package → resolveService(filter)   → 被可见性过滤就 return null
        //
        //   两条都 `return null` → bindService 返回 false，而且**打同一句日志**，
        //   普通应用又读不到 logcat。所以只试一种，等于把另一半原因藏起来了。
        //   实测过：改显式之后失败了、退回隐式却能连上（或反之）的情况都真实存在。
        //
        // 两个候选都带上**宿主当前界面语言**（v12.5）：插件基类在 onBind 里读它，
        // 暴露成 `hostLocaleTag`。这是 Intent extra 而不是 AIDL 方法，
        // 所以老插件（不读这个 key）行为完全不变，也不需要重编。
        val hostLocale = currentLocaleTag()
        val candidates = buildList {
            if (!cls.isNullOrEmpty()) {
                add(Intent(PluginConst.ACTION_PLUGIN)
                    .setComponent(ComponentName(packageName, cls))
                    .putExtra(PluginConst.EXTRA_HOST_LOCALE, hostLocale) to getString(R.string.s_678))
            }
            add(Intent(PluginConst.ACTION_PLUGIN).setPackage(packageName)
                .putExtra(PluginConst.EXTRA_HOST_LOCALE, hostLocale) to getString(R.string.s_679))
        }

        var lastError: String? = null
        var boundOnce = false

        for ((intent, kind) in candidates) {
            Log.i(TAG, "[$packageName] 尝试绑定（$kind）${intent.component ?: intent.action}")
            val ok = try {
                context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                // SecurityException 基本只剩「签名不一致」或「宿主没拿到权限」两种。
                // 这种错换 intent 也没用，直接给结论
                setState(State.FAILED, describeBindFailure(t, diag))
                return
            }
            if (ok) {
                boundOnce = true
                break
            }
            lastError = kind
        }

        if (!boundOnce) {
            // 返回 false ≠ 权限问题（那个会抛异常），而是**系统解析不到服务**。
            setState(State.FAILED, describeFalseReturn(diag, lastError))
            return
        }
        armTimeout()
    }

    /**
     * `bindService` 返回 false 的解释。
     *
     * 这个返回值只有一个含义：**系统没能解析到那个服务**。权限不足走的是异常分支，
     * 所以这里不需要再提签名。
     */
    private fun describeFalseReturn(diag: PluginDiscovery.Diag?, lastKind: String?): String {
        if (diag == null) {
            return getString(R.string.s_680)
        }
        return buildString {
            append(diag.headline(context))
            append(getString(R.string.s_681))
            append(diag.detailLine(context))
            append(getString(R.string.s_682)).append(lastKind ?: "?")
            append("）")
        }
    }

    /**
     * 把 bind 抛的异常翻译成人能看懂的一句话。
     *
     * `SecurityException` 在 bind 场景下只有两个原因：**两边签名不一致**，
     * 或者**宿主自己没拿到 signature 权限**。所以这里把权限状态和两个指纹
     * 一起摆出来 —— 光说一句 "SecurityException"，用户完全不知道该干什么。
     */
    private fun describeBindFailure(t: Throwable, diag: PluginDiscovery.Diag?): String {
        if (t !is SecurityException) {
            return getString(R.string.s_683, t.javaClass.simpleName, t.message?.let { " ($it)" } ?: "")
        }
        val d = diag ?: return getString(R.string.s_684)
        return buildString {
            append(getString(R.string.s_685))
            append(d.selfSigner.ifEmpty { "?" })
            append(getString(R.string.s_686))
            append(d.pluginSigner.ifEmpty { "?" })
            when {
                !d.holdsPermission -> append(getString(R.string.s_687))
                d.signerMatch -> append(getString(R.string.s_688))
                else -> append(getString(R.string.s_689))
            }
        }
    }

    private fun armTimeout() {        if (timeoutPosted) return
        timeoutPosted = true
        main.postDelayed({
            timeoutPosted = false
            if (state == State.BINDING) {
                setState(State.FAILED, getString(R.string.s_690))
                safeUnbind()
            }
        }, BIND_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutPosted = false
        main.removeCallbacksAndMessages(null)
    }

    /** 解绑并尽量释放插件侧资源 */
    fun disconnect() {
        activeRequestId = null
        cancelTimeout()
        val p = plugin
        if (p != null) {
            // 先关引擎再解绑，否则插件那边的模型会一直占着内存
            runCatching { p.closeEngine() }
        }
        safeUnbind()
        plugin = null
        manifest = null
        pluginApi = 0
        // 必须清：插件被换成了另一版（比如重装成不带覆盖的旧版）时，
        // 残留的文案会让界面显示成上一个插件说的话
        actionLabels = emptyMap()
        setState(State.DISCONNECTED)
    }

    private fun safeUnbind() {
        runCatching { context.unbindService(conn) }
        plugin = null
    }

    // ==================== 调用 ====================

    private fun requirePlugin(): IPonkoPlugin? {
        val p = plugin
        if (p == null || state != State.CONNECTED) {
            post {
                listener?.onEngineError(
                    PluginError(
                        code = 1, // ErrCode.NOT_READY
                        message = getString(R.string.s_691),
                    )
                )
            }
            return null
        }
        return p
    }

    fun loadEngine(threads: Int = 0, useGpu: Boolean = false) {
        val p = requirePlugin() ?: return
        try {
            // oneway：立刻返回，结果走 onEngineLoaded / onEngineError
            p.loadEngine(null, com.litertchat.plugin.EngineConfig(threads = threads, useGpu = useGpu))
        } catch (t: Throwable) {
            post { listener?.onEngineError(PluginError(code = 6, message = getString(R.string.s_692, t.javaClass.simpleName))) }
        }
    }

    fun closeEngine() {
        runCatching { plugin?.closeEngine() }
    }

    /**
     * 发起一次合成。
     *
     * @return 宿主生成的 requestId；插件未连接时返回 null
     */
    fun synthesize(req: com.litertchat.plugin.SynthesisRequest): String? {
        val p = requirePlugin() ?: return null
        activeRequestId = req.requestId
        return try {
            p.synthesize(req)
            req.requestId
        } catch (t: Throwable) {
            activeRequestId = null
            post { listener?.onError(PluginError(code = 6, message = getString(R.string.s_692, t.javaClass.simpleName), requestId = req.requestId)) }
            null
        }
    }

    fun cancel() {
        runCatching { plugin?.cancel() }
    }

    // ==================== 插件界面 ====================

    /**
     * 拉取插件的界面数据。
     *
     * **同步调用**（插件那边也保证 O(1)），因为打开页面要立刻有内容 ——
     * 走回调会让页面先空一下再填充，观感差。
     *
     * 三种失败都归成「空 schema + 一句说明」，不让页面崩：
     *   · 插件未连接
     *   · 插件是旧版、没实现这个方法
     *   · 插件自己抛异常
     */
    fun settingsSchema(): com.litertchat.plugin.UiSchema {
        val p = plugin ?: return com.litertchat.plugin.UiSchema(intro = getString(R.string.s_691))
        // 旧插件根本没实现这组方法，别去调 —— 见 supportsSchema 的说明
        if (!supportsSchema) {
            return com.litertchat.plugin.UiSchema(
                // 两句话合成一条资源：英文里 "only this page is missing"
                // 跟在前一句后面，拆成两段拼接会让语序对不上
                intro = getString(R.string.s_696, pluginApi)
            )
        }
        return try {
            // 用方法名而不是属性语法：AIDL 生成的接口里它是 getSettingsSchema()，
            // 写成属性虽然也能编过，但在这里容易和「本地字段」混淆
            p.getSettingsSchema() ?: com.litertchat.plugin.UiSchema()
        } catch (t: Throwable) {
            // 版本对得上却还失败 → 插件自己的实现问题（或进程刚死）
            Log.w(TAG, "[$packageName] 读取界面数据失败", t)
            com.litertchat.plugin.UiSchema(
                intro = getString(R.string.s_698, t.javaClass.simpleName)
            )
        }
    }

    /** 用户改了某项的值。值归插件管，宿主只转发 */
    fun setSetting(key: String, value: String) {
        if (!supportsSchema) return
        runCatching { plugin?.setSetting(key, value) }
            .onFailure { Log.w(TAG, "[$packageName] setSetting 失败：$key", it) }
    }

    /** 用户点了某个 ACTION 项 */
    fun invokeAction(key: String) {
        if (!supportsSchema) return
        runCatching { plugin?.invokeAction(key) }
            .onFailure { Log.w(TAG, "[$packageName] invokeAction 失败：$key", it) }
    }

    /**
     * v6：把用户选好的模型文件交给插件。
     *
     * ## FD 所有权
     *
     * 调用方（[PluginPage]）把 `ParcelFileDescriptor` 传进来，这里的 AIDL 调用
     * 会让 Binder **dup 一份**给插件。**不管转发成功与否，本方法都会关掉
     * 传进来的那个 FD** —— 它是从 `ContentResolver.openFileDescriptor` 拿的，
     * 属于宿主，不关就是泄漏（每进程默认 1024 个）。
     *
     * 插件那份由插件自己关；SDK 的 `importModel` 兜底还会再关一次（重复 close 安全）。
     *
     * ## 为什么是 oneway
     *
     * 插件收到 FD 后要读几百 MB 落盘，同步调用会把宿主的 Binder 线程堵住。
     * 进度由插件自己通过 `onUiPatch` 推给它声明的 PROGRESS 项。
     */
    fun importModel(role: String, fd: android.os.ParcelFileDescriptor, displayName: String, sizeBytes: Long) {
        try {
            if (!supportsModelImport) {
                Log.w(TAG, "[$packageName] 插件 SDK v$pluginApi 不支持导入模型，丢弃 $role")
                return
            }
            val p = plugin ?: return
            runCatching { p.importModel(role, fd, displayName, sizeBytes) }
                .onFailure { Log.w(TAG, "[$packageName] importModel 失败：$role", it) }
        } finally {
            // 转发完就关自己这份。插件拿到的是 dup 过的，关这个不影响它
            runCatching { fd.close() }
        }
    }

    /** v6：用户点了槽位上的「清除」 */
    fun deleteModel(role: String) {
        if (!supportsModelImport) return
        runCatching { plugin?.deleteModel(role) }
            .onFailure { Log.w(TAG, "[$packageName] deleteModel 失败：$role", it) }
    }

    /**
     * 插件是否支持模型导入（SDK v[PluginConst.API_WITH_MODEL_IMPORT] 起）。
     *
     * 和 [supportsSchema] 同样按版本判断，不靠 try-catch —— 调一个不存在的方法时
     * 老插件会收到不认识的事务码，行为不保证；oneway 甚至静默丢弃，
     * 看起来"点了没反应"却查不出原因。
     */
    val supportsModelImport: Boolean
        get() = state == State.CONNECTED &&
            pluginApi >= PluginConst.API_WITH_MODEL_IMPORT

    /**
     * 引擎是否已就绪。
     *
     * 这是**同步** AIDL 调用（插件侧保证 O(1) 只读 volatile），
     * 所以可以直接在 UI 线程问 —— 但别在滚动/重绘里高频调，
     * 每次都过一次 Binder。
     *
     * 用途：插件页的「加载 / 卸载」按钮要显示成哪一个。
     * 引擎状态是插件的内部状态，宿主除了问没有别的办法知道
     * （`onEngineLoaded` 回调只在状态**变化**时来一次，页面重建后拿不到）。
     */
    fun engineReady(): Boolean = try {
        state == State.CONNECTED && plugin?.isEngineReady == true
    } catch (t: Throwable) {
        // 进程死了：当作未就绪，让用户能重新点加载（点下去会走重连或报错）
        false
    }

    /**
     * 引擎状态快照。
     *
     * 同步 AIDL 调用（插件侧保证 O(1) 只读 volatile）。和 [engineReady] 的区别：
     * 这个能区分 `LOADING` / `RUNNING` / `UNLOADED`，用来**轮询到目标状态**。
     *
     * 为什么需要轮询：`closeEngine` 是 oneway，真正 free 发生在插件的 worker 线程里，
     * 耗时从几百毫秒（free 几 GB 模型）到几秒（正在推理，要先等它退出）。
     * 宿主没有任何"卸载完成"的回调可等，只能看状态什么时候真的变。
     *
     * @return 拿不到时返回 [com.litertchat.plugin.EngineState.UNLOADED] ——
     *         连接断了等同于"没有引擎"，这样轮询会正常终止而不是空转到超时
     */
    fun status(): com.litertchat.plugin.EngineStatus {
        val fallback = com.litertchat.plugin.EngineStatus(
            state = com.litertchat.plugin.EngineState.UNLOADED
        )
        if (state != State.CONNECTED) return fallback
        return try {
            plugin?.getStatus() ?: fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    /**
     * 心跳探测：判断连接是否还活着（UI 刷新列表时用）。
     *
     * 真去调一次 AIDL 方法 —— 进程死了会抛 `DeadObjectException`，
     * 这比检查引用是否为 null 可靠（引用还在但进程已死的窗口期是真实存在的）。
     */
    fun ping(): Boolean = try {
        plugin?.isEngineReady
        plugin != null
    } catch (t: Throwable) {
        plugin = null
        state = State.FAILED
        false
    }

    /** 给 UI 用的阶段名（本地化） */
    fun stageName(stage: Int): String = when (stage) {
        Stage.LOADING -> getString(R.string.s_473)
        Stage.TEXT_ENCODE -> getString(R.string.s_740)
        Stage.DECODE -> getString(R.string.s_741)
        Stage.AUDIO_DECODE -> getString(R.string.s_742)
        Stage.DONE -> getString(R.string.s_743)
        Stage.CANCELLED -> getString(R.string.s_479)
        Stage.FAILED -> getString(R.string.s_587)
        else -> getString(R.string.s_746)
    }
}
