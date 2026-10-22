// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

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
         * 插件要求**原地**改一个控件（v4）。不走整页重画。
         *
         * @param append true = 追加（日志）；false = 替换（进度、状态）
         */
        fun onUiPatch(key: String, value: String?, append: Boolean) {}
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
     * 取按钮文字：插件覆盖过就用它的，没覆盖用宿主默认。
     *
     * @param key 见 [com.litertchat.plugin.ActionLabel]
     */
    fun label(key: String): String = ActionLabels.resolve(key, actionLabels)

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
            post { listener?.onImage(bytes, width, height, mime) }
        }

        override fun onUiPatch(key: String, value: String?, append: Boolean) {
            post { listener?.onUiPatch(key, value, append) }
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
                setState(State.FAILED, "插件返回了空 binder")
                return
            }
            val p = IPonkoPlugin.Stub.asInterface(binder)
            plugin = p

            // ---- 版本校验：不匹配就**不绑定**，避免后续调用全炸 ----
            val api = try {
                p.apiVersion
            } catch (t: Throwable) {
                setState(State.FAILED, "读取 API 版本失败：${t.javaClass.simpleName}")
                safeUnbind()
                return
            }
            if (api !in PluginConst.API_MIN_SUPPORTED..PluginConst.API_MAX_SUPPORTED) {
                setState(
                    State.FAILED,
                    "插件 SDK v$api 不兼容（当前支持 v${PluginConst.API_MIN_SUPPORTED}–v${PluginConst.API_MAX_SUPPORTED}），请更新插件"
                )
                safeUnbind()
                return
            }
            // 记下来：决定「能不能调插件界面」以及「UI 上要不要提示插件偏旧」
            pluginApi = api

            manifest = try {
                p.manifest
            } catch (t: Throwable) {
                setState(State.FAILED, "读取插件信息失败：${t.javaClass.simpleName}")
                safeUnbind()
                return
            }

            try {
                p.registerCallback(callback)
            } catch (t: Throwable) {
                setState(State.FAILED, "注册回调失败：${t.javaClass.simpleName}")
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
            setState(State.FAILED, "插件进程已断开")
        }

        override fun onBindingDied(name: ComponentName?) {
            plugin = null
            setState(State.FAILED, "插件被重新安装，连接失效")
            safeUnbind()
        }

        override fun onNullBinding(name: ComponentName?) {
            setState(State.FAILED, "插件的 onBind 返回了 null")
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
        val candidates = buildList {
            if (!cls.isNullOrEmpty()) {
                add(Intent(PluginConst.ACTION_PLUGIN)
                    .setComponent(ComponentName(packageName, cls)) to "显式")
            }
            add(Intent(PluginConst.ACTION_PLUGIN).setPackage(packageName) to "隐式")
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
            return "系统找不到插件服务（bindService=false）。插件可能已被卸载或安装不完整"
        }
        return buildString {
            append(diag.headline())
            append("\n（诊断：")
            append(diag.detailLine())
            append(" 最后尝试=").append(lastKind ?: "?")
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
            return "绑定失败：${t.javaClass.simpleName}${t.message?.let { "（$it）" } ?: ""}"
        }
        val d = diag ?: return "绑定被拒（SecurityException），诊断信息读取失败"
        return buildString {
            append("签名校验未通过（SecurityException）：Ponko=")
            append(d.selfSigner.ifEmpty { "?" })
            append("，插件=")
            append(d.pluginSigner.ifEmpty { "?" })
            when {
                !d.holdsPermission -> append("（Ponko 自己没拿到插件权限 → 重启手机后再试）")
                d.signerMatch -> append("（指纹相同但权限未生效 → 重启手机后再试）")
                else -> append("（指纹不同 → 两个 APK 不是同一把密钥签的，必须换成配套版本）")
            }
        }
    }

    private fun armTimeout() {        if (timeoutPosted) return
        timeoutPosted = true
        main.postDelayed({
            timeoutPosted = false
            if (state == State.BINDING) {
                setState(State.FAILED, "绑定超时（插件进程可能起不来：缺 .so 或 ABI 不符）")
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
                        message = "插件未连接",
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
            post { listener?.onEngineError(PluginError(code = 6, message = "调用插件失败：${t.javaClass.simpleName}")) }
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
            post { listener?.onError(PluginError(code = 6, message = "调用插件失败：${t.javaClass.simpleName}", requestId = req.requestId)) }
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
        val p = plugin ?: return com.litertchat.plugin.UiSchema(intro = "插件未连接")
        // 旧插件根本没实现这组方法，别去调 —— 见 supportsSchema 的说明
        if (!supportsSchema) {
            return com.litertchat.plugin.UiSchema(
                intro = "这个插件是用旧版 SDK（v$pluginApi）构建的，没有提供界面。\n" +
                    "插件功能本身不受影响，只是少了这一页。"
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
                intro = "读取插件界面失败：${t.javaClass.simpleName}"
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
        Stage.LOADING -> "加载模型"
        Stage.TEXT_ENCODE -> "处理文本"
        Stage.DECODE -> "生成语音"
        Stage.AUDIO_DECODE -> "合成波形"
        Stage.DONE -> "完成"
        Stage.CANCELLED -> "已取消"
        Stage.FAILED -> "失败"
        else -> "空闲"
    }
}
