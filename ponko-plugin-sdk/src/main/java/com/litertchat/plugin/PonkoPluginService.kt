// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 插件基类。插件作者只需要继承它并实现 4 个方法：
 *
 * ```kotlin
 * class VoxCpm2PluginService : PonkoPluginService() {
 *     override fun createManifest() = PluginManifest(...)
 *     override fun loadEngineBlocking(models: ModelBundle?, config: EngineConfig): EngineInfo { ... }
 *     override fun closeEngineBlocking() { ... }
 *     override fun synthesizeBlocking(req: SynthesisRequest, out: Emitter): ResultBundle { ... }
 * }
 * ```
 *
 * 基类负责（插件作者**不要**自己再写一遍）：
 * - **线程**：所有 `oneway` 方法都从 Binder 线程进来，基类立刻转到**单线程 executor**，
 *   绝不在 Binder 线程里跑推理（Binder 线程池每进程只有 16 个，占死会连累别的 IPC）
 * - **前台服务**：在 [onBind] 里立刻 `startForeground`（见下方 ⚠）
 * - **唤醒锁**：推理期间持有 `PARTIAL_WAKE_LOCK`，四个出口都会撤
 * - **状态机**：`UNLOADED / LOADING / READY / RUNNING`，`isEngineReady()` 是 O(1) 的 volatile 读
 * - **取消**：`cancel()` 置标志，插件在耗时循环里调 [Emitter.throwIfCancelled]
 * - **回调去重**：`onResult` / `onError` 只发一次（`AtomicBoolean` 保证）
 * - **异常映射**：[PluginException] → 对应错误码，其它异常 → `PLUGIN_INTERNAL`
 *
 * ## ⚠ 前台服务的启动时机（Android 12+ 会抛异常）
 *
 * 插件自己没有 Activity，能不能起前台服务取决于**被绑定那一刻宿主在不在前台**。
 * 所以基类在 [onBind] 里就 `startForeground("准备中…")` ——
 * 那时宿主刚发起 `bindService`，进程算前台，一定能过。
 *
 * **绝不能**等到 [synthesizeBlocking] 里才起：那时用户可能已切后台，
 * 会抛 `ForegroundServiceStartNotAllowedException` → 服务起不来 → 推理被中途杀掉。
 *
 * ## 插件 Manifest 必须声明
 *
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
 * <uses-permission android:name="android.permission.WAKE_LOCK" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <service
 *     android:name=".VoxCpm2PluginService"
 *     android:exported="true"
 *     android:foregroundServiceType="specialUse"
 *     android:permission="com.litertchat.ponko.permission.BIND_PLUGIN">
 *     <intent-filter>
 *         <action android:name="com.litertchat.ponko.PLUGIN" />
 *     </intent-filter>
 * </service>
 * ```
 */
abstract class PonkoPluginService : Service() {

    // ==================== 插件作者要实现的部分 ====================

    /** 插件自述。**必须轻量且纯函数**（会在 Binder 线程上被调） */
    protected abstract fun createManifest(): PluginManifest

    /**
     * 加载引擎。**在后台单线程执行**，可以阻塞几十秒，可以直接 while 轮询进度。
     *
     * @param models v1 恒为 `null`（插件自管模型）
     * @throws PluginException 用 [PluginException.outOfMemory] 之类表达可映射错误
     */
    @Throws(PluginException::class)
    protected abstract fun loadEngineBlocking(models: ModelBundle?, config: EngineConfig): EngineInfo

    /** 卸载引擎、释放模型内存。**在后台单线程执行**。实现要幂等 */
    protected abstract fun closeEngineBlocking()

    /**
     * 执行一次合成。**在后台单线程执行**。
     *
     * 耗时循环里必须周期性调 [Emitter.isCancelled] / [Emitter.throwIfCancelled]，
     * 否则用户点取消要等到跑完才生效。
     *
     * @return 结果；音频大时把 [ResultBundle.audio] 填成已 open 的 FD
     */
    @Throws(PluginException::class)
    protected abstract fun synthesizeBlocking(req: SynthesisRequest, out: Emitter): ResultBundle

    /** 通知文案（"正在生成…"这类）。可覆盖做本地化 */
    protected open fun notificationText(stage: Int, label: String?): String =
        label ?: when (stage) {
            Stage.LOADING -> "正在加载模型…"
            Stage.TEXT_ENCODE -> "正在处理文本…"
            Stage.DECODE -> "正在生成语音…"
            Stage.AUDIO_DECODE -> "正在合成波形…"
            else -> "正在工作…"
        }

    // ==================== 插件界面（可选实现）====================

    /**
     * 返回界面数据，宿主用它渲染整页。**默认空页面**。
     *
     * 覆写它就能给插件配出一个界面，不用写任何 Android 布局 ——
     * 控件样式、深浅色主题、圆角间距全由宿主负责。
     *
     * ⚠ **必须快**：宿主在打开页面时会同步调它（这样页面不会先空一下再填充）。
     * 只读已缓存的状态，别在这里做 IO、别加载模型。
     * 慢动作（下载模型之类）做成 `TYPE_ACTION` 项，在 [onActionInvoked] 里做。
     *
     * ⚠ 值归插件管：宿主**不持久化**任何值，每次要用就调这里拿最新状态。
     * 所以覆写时要把"当前值"填进 `UiItem.value`，而不是从宿主那儿读。
     */
    protected open fun buildSettingsSchema(): UiSchema = UiSchema()

    /**
     * 用户改了某一项的值。**在后台线程调用**。
     *
     * 存好值之后，如果别的项的状态也变了（比如选了模式 A 就该让某滑块灰掉），
     * 调 [notifySchemaChanged] 让宿主重画。
     *
     * @param key [UiItem.key]
     * @param value 字符串化的新值（开关是 "true"/"false"）
     */
    protected open fun onSettingChanged(key: String, value: String) {}

    /**
     * 用户点了某个 `TYPE_ACTION` 项。**在后台线程调用**，可以放心做耗时操作。
     *
     * 做完调 [notifySchemaChanged] 更新界面（例如把「下载模型」变成「已下载」）。
     *
     * @param key [UiItem.key]
     */
    protected open fun onActionInvoked(key: String) {}

    /**
     * 用户通过宿主选好了一个 [UiType.MODEL_SLOT] 的模型文件（v6）。
     *
     * **在后台线程调用**，可以直接同步读那个 FD（几百 MB 也不会卡界面）。
     *
     * ## 你要做的事
     *
     * 1. 把 [fd] 读完、写进**自己的私有目录**（`filesDir` 之类）。
     *    宿主的目录你读不到，它也不会给你路径 —— 只有这个 FD。
     * 2. close 掉 [fd]。**基类也会兜底 close 一次**，所以忘了也不会泄漏，
     *    但自己关更清楚（尤其是你想读一半就放弃的时候）。
     * 3. 调 [notifySchemaChanged]，新状态从 [buildSettingsSchema] 的
     *    `UiItem.value` 里报出来（已导入就写大小，如 `"2.83 GB"`）。
     *
     * ## 为什么不是插件自己弹选择器
     *
     * 插件是 bound Service：`startActivityForResult` 是 Activity 的 API，
     * Service 没有；而且 Android 10+ 禁止后台启动 Activity。
     * 所以选择器只能宿主弹，插件只负责收 FD。
     *
     * ## 磁盘上只有一份
     *
     * 宿主只是把系统给的 FD 转发过来，自己不留副本。
     * 所以最终代价和"用户在宿主模型页导入"完全一样：复制一次到自己的目录。
     *
     * @param role        [UiType.MODEL_SLOT] 的 key
     * @param fd          只读 FD，读完请 close
     * @param displayName 原始文件名，**只用于显示/日志**，别拿它拼路径
     * @param sizeBytes   大小；SAF 给不出时是 -1
     */
    protected open fun onModelFileReceived(
        role: String,
        fd: android.os.ParcelFileDescriptor,
        displayName: String,
        sizeBytes: Long,
    ) {}

    /**
     * 用户点了 [UiType.MODEL_SLOT] 那行的「清除」（v6）。**在后台线程调用**。
     *
     * 删自己私有目录里那份文件 —— 宿主删不了（那是别的包的目录）。
     * 删完调 [notifySchemaChanged] 刷新状态。
     *
     * 引擎正在用这个模型时该不该让删，由你判断：更体面的做法是
     * 在 schema 里把那个槽位的 `enabled` 设成 `false` 直接灰掉按钮，
     * 而不是等用户点了才报错。
     */
    protected open fun onModelDeleteRequested(role: String) {}

    /**
     * 宿主按钮叫什么。键见 [ActionLabel]，值就是显示的文字。
     *
     * ```kotlin
     * override fun buildActionLabels() = mapOf(
     *     ActionLabel.ENGINE_LOAD   to "加载模型 3.5G",
     *     ActionLabel.ENGINE_UNLOAD to "释放模型",
     * )
     * ```
     *
     * **不覆写就用宿主默认**（加载引擎 / 卸载引擎 / 合成 / 播放 / 停止 / 保存）。
     *
     * ## 什么时候值得改
     *
     * 宿主默认文案是按"有个引擎要加载"写的。如果你的插件其实是：
     * · 走本地模型 → 「加载模型」比「加载引擎」具体，带上体积更好
     * · 走网络 API → 没有模型，「连接服务 / 断开服务」才对
     * · 模型能停在内存里复用 → 「卸载」不如「释放内存」说得清楚
     *
     * ## 不要用它做的事
     *
     * · 改不了按钮的位置、个数、颜色、可点状态、点了干什么
     * · 别想把「停止」改名叫「保存」—— 用户会按字面理解，然后丢东西
     * · 超过 [ActionLabel.MAX_LEN] 个字符会被**整个丢掉**，不是截断
     *
     * 做耗时活不要在这里做：这个方法在 Binder 线程上、打开界面时调，要 O(1)。
     */
    protected open fun buildActionLabels(): Map<String, String> = emptyMap()

    // ==================== 内部状态 ====================

    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "ponko-plugin-worker") } }

    /** Binder 线程上读，worker 线程上写 —— 必须 volatile */
    @Volatile private var callback: IPonkoCallback? = null

    @Volatile private var state: Int = EngineState.UNLOADED
    @Volatile private var modelBytes: Long = 0L
    @Volatile private var loadedAt: Long = 0L

    /**
     * 引擎是否**真的加载成功过**（加载失败 / closeEngine / onDestroy 时置 false）。
     *
     * ⚠ **绝不能用 `modelBytes > 0` 当"加载过"的标志**。
     * 没有模型文件的插件（Echo 测试插件、纯算法插件）加载成功后 `modelBytes` 依然是 0，
     * 拿体积判断会让它在**第一次合成结束**时被重置成 `UNLOADED` ——
     * 用户第二次点合成就收到「引擎尚未加载」，而插件自己明明还持有引擎。
     * 这是实际踩过的坑（2026-10-21）。
     */
    @Volatile private var engineLoaded = false

    /** 只允许一个任务在跑；用 CAS 保证**在投递到 worker 之前**就占住 */
    private val busy = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)

    /**
     * 正在 `closeEngine` 的窗口（从投递到 worker 到真正 free 完）。
     *
     * 独立于 [busy]：`busy` 表示"有任务占着"，而卸载在空闲时也会占住它。
     * 这个窗口对 [synthesize] 的正确语义是 `NOT_READY`（引擎要没了），
     * 不是 `BUSY`（上一段还在生成）。见 [closeEngine]。
     */
    @Volatile private var closing = false

    /** 取消标志。换新任务时重置 */
    @Volatile private var cancelFlag = false

    /** 当前任务 ID，用于丢弃过期回调 */
    @Volatile private var currentRequestId: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    /** 唤醒锁续期用的 Handler（主线程，只做 acquire，很快） */
    private val wlHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val wlRenew = object : Runnable {
        override fun run() {
            val wl = wakeLock ?: return
            if (!wl.isHeld) return
            // setReferenceCounted(false) 下再次 acquire 等于**重置超时**
            runCatching { wl.acquire(WAKELOCK_TIMEOUT_MS) }
            wlHandler.postDelayed(this, WAKELOCK_RENEW_MS)
        }
    }

    // ==================== Emitter（给插件作者用的进度通道）====================

    /**
     * 插件 → 宿主的进度通道。所有方法都是**线程安全**的，可以在 worker 任意位置调。
     */
    inner class Emitter internal constructor(private val requestId: String) {

        private var lastStageAt = 0L
        private var lastStage = -1

        /** 是否已被取消。插件在耗时循环里每轮都该检查 */
        val isCancelled: Boolean get() = cancelFlag

        /** 已被取消就抛 [PluginException]，基类会静默处理成 `CANCELLED` */
        fun throwIfCancelled() {
            if (cancelFlag) throw PluginException(ErrCode.CANCELLED, "已取消")
        }

        /**
         * 上报阶段。**自带 500ms 限流**（同一阶段内更频繁的调用会被丢掉），
         * 因为 `oneway` 回调没有背压，发太快会吃满宿主的 Binder 配额。
         *
         * @param label 可读文案，宿主直接显示，如「已生成 3.2 秒」
         */
        fun stage(stage: Int, label: String? = null) {
            val now = SystemClock.elapsedRealtime()
            // 阶段切换时立刻发；同阶段内按 STAGE_MIN_INTERVAL_MS 限流
            if (stage == lastStage && now - lastStageAt < PluginConst.STAGE_MIN_INTERVAL_MS) return
            lastStage = stage
            lastStageAt = now
            safeCallback { it.onStage(stage, label) }
            updateNotification(stage, label)
        }

        /**
         * 发一块音频。**仅在 `req.streamAudio == true` 时调用**。
         *
         * 单块必须 ≤ [PluginConst.MAX_AUDIO_CHUNK_BYTES]（64KB），超了会被截断并记日志。
         */
        fun chunk(pcm: ByteArray, sampleRate: Int, channelCount: Int, isFinal: Boolean) {
            if (pcm.size > PluginConst.MAX_AUDIO_CHUNK_BYTES) {
                Log.w(TAG, "chunk 过大 (${pcm.size}B > ${PluginConst.MAX_AUDIO_CHUNK_BYTES}B)，已截断")
            }
            val data = if (pcm.size > PluginConst.MAX_AUDIO_CHUNK_BYTES) {
                pcm.copyOf(PluginConst.MAX_AUDIO_CHUNK_BYTES)
            } else pcm
            safeCallback { it.onAudioChunk(data, sampleRate, channelCount, isFinal) }
        }

        internal fun matches(id: String?) = id == requestId
    }

    // ==================== Binder ====================

    private val binder = object : IPonkoPlugin.Stub() {

        override fun getApiVersion(): Int = PluginConst.API_VERSION

        override fun getManifest(): PluginManifest = createManifest()

        override fun registerCallback(cb: IPonkoCallback?) {
            callback = cb
            Log.i(TAG, "回调已注册: ${if (cb != null) "ok" else "null"}")
        }

        override fun loadEngine(models: ModelBundle?, config: EngineConfig?) {
            val cfg = config ?: EngineConfig()
            // 幂等：已在加载 / 已加载 → 直接忽略
            if (state == EngineState.READY || state == EngineState.RUNNING) return
            if (!loading.compareAndSet(false, true)) return

            state = EngineState.LOADING
            // 立刻挂一条通知，让用户知道"在准备"，也顺便让进程稳定下来
            goForeground(Stage.LOADING, null)

            worker.execute {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val info = loadEngineBlocking(models, cfg)
                    val elapsed = info.loadElapsedMs.takeIf { it > 0 }
                        ?: (SystemClock.elapsedRealtime() - t0)
                    modelBytes = info.modelBytes
                    loadedAt = SystemClock.elapsedRealtime()
                    engineLoaded = true
                    state = EngineState.READY
                    safeCallback { it.onEngineLoaded(info.copy(loadElapsedMs = elapsed)) }
                    Log.i(TAG, "引擎加载成功: ${info.modelBytes}B / ${elapsed}ms")
                } catch (t: Throwable) {
                    engineLoaded = false
                    state = EngineState.UNLOADED
                    modelBytes = 0L
                    loadedAt = 0L
                    safeCallback { it.onEngineError(toPluginError(t, null)) }
                    Log.e(TAG, "引擎加载失败", t)
                    stopForegroundAndWakeLock()
                } finally {
                    loading.set(false)
                }
            }
        }

        override fun closeEngine() {
            // 用一个**独立**的 closing 标志，而不是借用 busy。
            //
            // ## 为什么不能用 busy（踩过）
            //
            // 原来写的是 `if (!busy.compareAndSet(false, true)) { cancelFlag = true }` ——
            // 空闲时 CAS **会成功**，busy 就变成 true 了，而它要等 worker 里的
            // finally 才复位。于是这段窗口（free 几 GB 可能几百毫秒，正在推理时更久）
            // 里调 synthesize 会命中"已有任务在跑"的分支，回：
            //   BUSY「上一段语音还在生成，请稍候」
            // 而实际上什么都没在生成 —— 用户只是刚点了卸载。
            //
            // 这段窗口的正确语义是"引擎正在关闭，现在不能合成"，也就是 NOT_READY。
            // 宿主对 NOT_READY 有现成的处理：自动重载引擎 + 补发这次请求，
            // 语义正好对上；对 BUSY 则只会把"上一段还在生成"显示给用户。
            closing = true
            if (!busy.compareAndSet(false, true)) {
                // 有任务在跑 → 先取消，让 worker 自己收尾
                cancelFlag = true
            }
            worker.execute {
                try {
                    cancelFlag = true
                    runCatching { closeEngineBlocking() }
                        .onFailure { Log.e(TAG, "closeEngine 异常", it) }
                } finally {
                    engineLoaded = false
                    state = EngineState.UNLOADED
                    modelBytes = 0L
                    loadedAt = 0L
                    cancelFlag = false
                    busy.set(false)
                    closing = false
                    stopForegroundAndWakeLock()
                }
            }
        }

        // O(1)：只读 volatile，不加锁、不调原生（方法在宿主 Binder 线程上同步等返回）
        override fun isEngineReady(): Boolean =
            state == EngineState.READY || state == EngineState.RUNNING

        override fun getStatus(): EngineStatus =
            EngineStatus(state = state, modelBytes = modelBytes, loadedAtElapsedMs = loadedAt)

        override fun synthesize(req: SynthesisRequest?) {
            if (req == null) {
                postError(PluginException.invalidRequest("请求为空"), null)
                return
            }
            val st = state
            if (st != EngineState.READY && st != EngineState.RUNNING) {
                postError(PluginException.notReady(), req.requestId)
                return
            }
            // 正在 closeEngine 的窗口里：引擎马上要没了，回 NOT_READY 而不是 BUSY。
            // 见 closeEngine 里的说明 —— 这里说什么都不在生成，说"上一段还在生成"是撒谎。
            if (closing) {
                postError(PluginException.notReady(), req.requestId)
                return
            }
            // 不排队：并发调用立刻回 BUSY。排队会让用户以为点了没反应
            if (!busy.compareAndSet(false, true)) {
                postError(PluginException(ErrCode.BUSY, "上一段语音还在生成，请稍候"), req.requestId)
                return
            }

            currentRequestId = req.requestId
            cancelFlag = false
            state = EngineState.RUNNING
            goForeground(Stage.DECODE, "准备生成…")

            val emitter = Emitter(req.requestId)
            worker.execute {
                val t0 = SystemClock.elapsedRealtime()
                var finished = false
                try {
                    val result = synthesizeBlocking(req, emitter)
                    finished = true
                    safeCallback {
                        it.onResult(result.copy(elapsedMs = SystemClock.elapsedRealtime() - t0))
                    }
                } catch (e: PluginException) {
                    finished = true
                    if (e.code == ErrCode.CANCELLED) {
                        Log.i(TAG, "已取消: ${req.requestId}")
                    } else {
                        Log.e(TAG, "合成失败: ${e.code} ${e.message}", e)
                    }
                    // 取消也要回一次 onError(CANCELLED)，宿主靠它收尾
                    safeCallback { it.onError(toPluginError(e, req.requestId)) }
                } catch (t: Throwable) {
                    finished = true
                    Log.e(TAG, "合成异常", t)
                    safeCallback { it.onError(toPluginError(t, req.requestId)) }
                } finally {
                    if (!finished) {
                        // 极端情况（比如被 ThreadDeath 打断）也要保证宿主收到终态
                        safeCallback {
                            it.onError(
                                PluginError(
                                    code = ErrCode.PLUGIN_INTERNAL,
                                    message = "生成被意外中断",
                                    requestId = req.requestId,
                                )
                            )
                        }
                    }
                    cancelFlag = false
                    currentRequestId = null
                    busy.set(false)
                    // 合成结束后回到「已加载」而不是「未加载」——
                    // 依据是 engineLoaded，不是 modelBytes（无模型的插件 modelBytes 恒为 0）
                    state = if (engineLoaded) EngineState.READY else EngineState.UNLOADED
                    stopForegroundAndWakeLock()
                }
            }
        }

        override fun cancel() {
            if (!busy.get()) return   // 没任务 → 空操作
            cancelFlag = true
            Log.i(TAG, "收到取消请求")
        }

        // ---- 插件界面 ----

        /**
         * **同步方法**，跑在宿主的 Binder 线程上。
         *
         * 不能加锁、不能等 worker —— 那会占死宿主那 16 个 Binder 线程里的一个，
         * 期间宿主所有 IPC 都排队。所以直接读插件已缓存的状态。
         */
        override fun getSettingsSchema(): UiSchema = try {
            buildSettingsSchema()
        } catch (t: Throwable) {
            // 插件自己的 schema 实现崩了，不应该让宿主页面跟着崩
            Log.e(TAG, "buildSettingsSchema 失败", t)
            UiSchema(intro = "界面加载失败：${t.javaClass.simpleName}")
        }

        override fun setSetting(key: String, value: String) {
            worker.execute {
                try {
                    onSettingChanged(key, value)
                } catch (t: Throwable) {
                    Log.e(TAG, "onSettingChanged 失败：$key", t)
                }
            }
        }

        override fun invokeAction(key: String) {
            worker.execute {
                try {
                    onActionInvoked(key)
                } catch (t: Throwable) {
                    Log.e(TAG, "onActionInvoked 失败：$key", t)
                }
            }
        }

        /**
         * v6：宿主替用户选好了一个模型文件。
         *
         * **FD 在这里就交给 worker 线程了**，不留在 Binder 线程上 ——
         * 读 2.8GB 会占死 Binder 线程池，之后连 `cancel` 都进不来。
         * 兜底 close 也在 worker 里做：插件覆写的方法**没关**就由这里关掉，
         * 否则就是 FD 泄漏（每进程默认 1024 个）。
         */
        override fun importModel(role: String, fd: android.os.ParcelFileDescriptor,
                                 displayName: String, sizeBytes: Long) {
            worker.execute {
                try {
                    onModelFileReceived(role, fd, displayName, sizeBytes)
                } catch (t: Throwable) {
                    Log.e(TAG, "onModelFileReceived 失败：$role", t)
                } finally {
                    // 插件可能已经自己关了 —— close 两次是安全的（第二次是空操作）
                    runCatching { fd.close() }
                }
            }
        }

        override fun deleteModel(role: String) {
            worker.execute {
                try {
                    onModelDeleteRequested(role)
                } catch (t: Throwable) {
                    Log.e(TAG, "onModelDeleteRequested 失败：$role", t)
                }
            }
        }

        /**
         * 同步、O(1)。跑在宿主的 Binder 线程上，别做 IO。
         *
         * 返回 `["engine.load=加载模型", ...]`。在这里就过滤一遍坏项，
         * 免得把插件自己的错误推到宿主去判 —— 宿主那边也会再过滤一次，
         * 但两边都拦一次的成本几乎为零，而且能各自留下日志。
         */
        override fun getActionLabels(): List<String> {
            val labels = try {
                buildActionLabels()
            } catch (t: Throwable) {
                Log.e(TAG, "buildActionLabels 失败", t)
                return emptyList()
            }
            if (labels.isEmpty()) return emptyList()
            val out = ArrayList<String>(labels.size)
            for ((k, v) in labels) {
                if (k !in ActionLabel.ALL) {
                    Log.w(TAG, "忽略未知的按钮 key：$k")
                    continue
                }
                if (v.isBlank()) continue
                if (v.length > ActionLabel.MAX_LEN) {
                    // 整个丢弃而不是截断：截出来是「加载模型 3.5…」，
                    // 比原来那句默认文案更难懂
                    Log.w(TAG, "按钮文案过长（${v.length} > ${ActionLabel.MAX_LEN}），已忽略：$k=$v")
                    continue
                }
                out.add("$k=$v")
            }
            return out
        }
    }

    /**
     * 让宿主重画插件界面。插件在自己的界面数据变化时调它。
     *
     * 宿主收到后重新 `getSettingsSchema()` 拿最新数据。本方法不传任何数据 ——
     * 只有一个"请重画"的信号，避免两头各存一份状态。
     *
     * ⚠ 别高频调（每次都是一次跨进程调用 + 整页重绘）。节流到 500ms 以上。
     */
    protected fun notifySchemaChanged() {
        val cb = callback ?: return
        try {
            cb.onSchemaChanged()
        } catch (t: Throwable) {
            // 宿主已经死了/解绑了，忽略即可
            Log.w(TAG, "onSchemaChanged 回调失败", t)
        }
    }

    /**
     * **原地**改一个控件（v4）。比 [notifySchemaChanged] 轻得多。
     *
     * 典型用途：
     * - [UiType.LOG] 追加一行日志（`append = true`）
     * - [UiType.PROGRESS] 推进度（`append = false`，value 是 0–100 的字符串）
     * - [UiType.STATUS] 换一行值
     *
     * ## 为什么必须有它，不能靠 [notifySchemaChanged]
     *
     * `notifySchemaChanged` 会让宿主 `removeAllViews()` **整页重建**：
     * 用户滚到一半被拉回顶部、输入框失焦、正在编辑的 `TEXTAREA` 内容被覆盖。
     * 日志一行一条的话，那个界面基本没法用。
     *
     * ## 必须节流
     *
     * `IPonkoCallback` 是 `oneway`、**没有背压**。每读一行就发一个 Binder 事务，
     * 宿主处理慢于插件时消息会在宿主侧排队，极端情况吃满那 1MB 配额并连累其它 IPC。
     * 建议自己攒 100ms 再发一次。
     *
     * @param key    目标 [UiItem.key]，**必须和 schema 里那个 item 完全一致**；
     *               对不上宿主静默忽略（不会去猜）
     * @param value  新内容（[UiType.LOG] 是**要追加的那一段**）
     * @param append true = 追加到末尾；false = 整段替换
     */
    protected fun notifyUiPatch(key: String, value: String, append: Boolean) {
        val cb = callback ?: return
        try {
            cb.onUiPatch(key, value, append)
        } catch (t: Throwable) {
            Log.w(TAG, "onUiPatch 回调失败", t)
        }
    }

    /**
     * 送一张结果图（v4），显示在 [UiType.IMAGE] 控件里。
     *
     * ## FD 所有权
     *
     * 交给宿主后**不要自己 close** —— 宿主读完负责关。自己先关会让宿主读到
     * 已失效的句柄（而且它是异步读的，你无法知道它读没读完）。
     *
     * ## 为什么要自己 open
     *
     * 图片可能几十 MB，不能塞进 Parcel 的那 1MB 配额。宿主会在自己的 Binder 线程
     * 上把它读成字节并立刻关闭，所以这里传完就可以不再管它。
     *
     * @param fd     已打开的只读 FD
     * @param width  像素宽；不确定传 0，宿主按实际解码结果（**以解码结果为准**，
     *               插件算错尺寸比不传更常见）
     * @param height 像素高；同上
     * @param mime   形如 `image/png`，可为 null（宿主按魔数判断）
     */
    protected fun notifyImage(
        fd: android.os.ParcelFileDescriptor,
        width: Int,
        height: Int,
        mime: String?,
    ) {
        val cb = callback ?: run {
            // 没连上就别泄漏 FD
            runCatching { fd.close() }
            return
        }
        try {
            cb.onImage(fd, width, height, mime)
        } catch (t: Throwable) {
            Log.w(TAG, "onImage 回调失败", t)
            runCatching { fd.close() }
        }
    }

    /** 方便调用的重载：给一个文件路径，自己 open。 */
    protected fun notifyImage(file: java.io.File, width: Int, height: Int, mime: String? = "image/png") {
        val fd = try {
            android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
        } catch (t: Throwable) {
            Log.w(TAG, "打开结果图失败：$file", t)
            return
        }
        notifyImage(fd, width, height, mime)
    }

    /**
     * 送结果图并**指明贴到哪个 `IMAGE` 控件**（v11）。
     *
     * ## 什么时候该用它
     *
     * 页面上只有一张图 → [notifyImage] 就够了（宿主能唯一确定）。
     * 有两张以上（原图/结果对比、多方案预览）→ **必须**用它，
     * 否则宿主不知道贴哪张，会直接丢弃并留一行日志。
     *
     * ```kotlin
     * notifyImage("before", file)     // 贴到 key="before" 那张
     * ```
     *
     * @param key 目标 `UiItem(type = UiType.IMAGE, key = …)` 的 key
     */
    protected fun notifyImage(key: String, file: java.io.File, mime: String? = "image/png") {
        val fd = try {
            android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
        } catch (t: Throwable) {
            Log.w(TAG, "打开结果图失败：$file", t)
            return
        }
        // 尺寸从文件头读：插件只知道文件时不该被迫先 decode 一遍
        val (w, h) = runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
            o.outWidth to o.outHeight
        }.getOrDefault(0 to 0)
        sendImageWithKey(key, fd, w, h, mime)
    }

    /**
     * 送 Bitmap 并指定目标。**最常用的一行调用**。
     *
     * 内部先压成 PNG 落到自己的 `cacheDir`，再把 FD 交给宿主，然后删掉临时文件。
     * 走 FD 是因为 Binder 事务有 1MB 配额，几 MB 的位图直接塞 transaction 会
     * 抛 `TransactionTooLargeException`。
     */
    protected fun notifyImage(key: String, bitmap: android.graphics.Bitmap) {
        val f = java.io.File(cacheDir, "ponko_img_${System.nanoTime()}.png")
        try {
            java.io.FileOutputStream(f).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写临时图片失败", t)
            return
        }
        try {
            notifyImage(key, f, "image/png")
        } finally {
            // 宿主读完就不需要了；FD 已在宿主侧持有，删文件不影响已打开的描述符
            runCatching { f.delete() }
        }
    }

    /**
     * 让宿主滚到某个 item（v12）。
     *
     * ## 什么时候用
     *
     * 宿主**不会**自己动滚动位置 —— 重画之后停在哪由你决定：
     *
     * ```kotlin
     * // 结果出来了：重画 + 滚到结果区
     * notifySchemaChanged()
     * notifyScrollTo("result")
     *
     * // 只是改了某个值：什么都不用做，位置自然保持
     * notifyUiPatch("progress", "42")
     * ```
     *
     * 不调用就是"不发生任何滚动"，这对多数场景已经是对的
     * （同一帧内重建，Android 自己保持 `scrollY`）。
     *
     * ## 时机
     *
     * 可以和 `notifySchemaChanged()` 连着调 —— 宿主会等布局完成再滚，
     * 不需要你延时。
     *
     * @param key 目标 `UiItem.key`。找不到时宿主只留日志，不猜
     */
    protected fun notifyScrollTo(key: String) {
        val cb = callback ?: return
        try {
            cb.onScrollTo(key)
        } catch (t: Throwable) {
            // 老宿主没有这个方法（v12 追加），抛 RemoteException。
            // 滚动是锦上添花，失败不影响功能，所以只记一条日志
            Log.w(TAG, "notifyScrollTo 失败（老宿主？）key='$key'", t)
        }
    }

    /**
     * 底层出口：带 key 的 onImage。
     *
     * **老宿主上没有 `onImageWithKey`** —— 它的 Stub 会抛
     * `RemoteException`（未知事务码）。这里捕获后**退回** [onImage]：
     * 那个能贴到"唯一的图片控件"，总比什么都贴不上好。
     *
     * 用 try/catch 而不是版本判断：SDK 侧拿不到宿主版本号，
     * 而"调用失败"本身就是最准确的探测。
     */
    private fun sendImageWithKey(
        key: String,
        fd: android.os.ParcelFileDescriptor,
        width: Int,
        height: Int,
        mime: String?,
    ) {
        val cb = callback ?: run {
            runCatching { fd.close() }      // 没连上就别泄漏 FD
            return
        }
        try {
            cb.onImageWithKey(key, fd, width, height, mime)
        } catch (t: Throwable) {
            Log.w(TAG, "带 key 送图失败（老宿主？），退回不带 key 的 onImage", t)
            runCatching { cb.onImage(fd, width, height, mime) }
                .onFailure { runCatching { fd.close() } }
        }
    }

    /**
     * 请宿主**用当前参数**合成一次（v7）。
     *
     * ## 什么时候用
     *
     * 你想在「参数」页里放一个自己文案的「开始推理」按钮：
     *
     * ```kotlin
     * UiItem(key = "go", type = UiType.ACTION, label = "开始推理")
     * // 用户点了 → 宿主调 onActionInvoked("go")
     * override fun onActionInvoked(key: String) {
     *     if (key == "go") notifyRequestSynthesis()
     * }
     * ```
     *
     * ## 它到底做了什么
     *
     * 宿主走的和用户点它自己那个「合成」按钮**完全同一条路径**：
     * 读 `PreviewParams`（目标文本 / 模式 / 参考音频 / 逐字稿 / 种子）、
     * 校验、开 FD、发 `synthesize`、收音频、落盘。
     *
     * 所以：
     * · 参数不合法（文本空、缺参考音频）→ 宿主不发起，把拒绝理由显示出来
     * · 已经在合成中 → 这一次被忽略（不排队）
     * · 没有返回值 → 想知道开没开始，看 [onStage] 有没有来
     *
     * ## 一个前提
     *
     * 只有插件界面**正开着**时才有意义 —— 用户不在这一页，宿主没有
     * "当前参数"可读（那是这一页上的填写内容），请求会被丢掉。
     */
    protected fun notifyRequestSynthesis() {
        val cb = callback ?: return
        try {
            cb.requestSynthesis()
        } catch (t: Throwable) {
            Log.w(TAG, "requestSynthesis 回调失败", t)
        }
    }

    /**
     * 给某个 [UiType.AUDIO] 条目送一份**自己的**音频（v7）。
     *
     * v4 的播放条只能播"宿主最近一次产物"，所以历史记录里点第 3 条
     * 也只能听到最新那条。送过文件之后，那一行播的就是这份。
     *
     * ```kotlin
     * notifyAudioFile("hist-3", file)        // schema 里 key = "hist-3" 的 AUDIO 播它
     * ```
     *
     * **同一个 key 再送一次 = 替换**，宿主会删掉上一份，磁盘不会越攒越多。
     *
     * ## FD 所有权
     *
     * 交给宿主后**不要自己 close** —— 宿主读完负责关（和 [notifyImage] 一样）。
     */
    protected fun notifyAudioFile(
        key: String,
        fd: android.os.ParcelFileDescriptor,
        displayName: String?,
    ) {
        val cb = callback ?: run {
            runCatching { fd.close() }
            return
        }
        try {
            cb.onAudioFile(key, fd, displayName)
        } catch (t: Throwable) {
            Log.w(TAG, "onAudioFile 回调失败", t)
            runCatching { fd.close() }
        }
    }

    /** 方便调用的重载：给文件路径，自己 open。[displayName] 默认用文件名。 */
    protected fun notifyAudioFile(
        key: String,
        file: java.io.File,
        displayName: String? = null,
    ) {
        val fd = try {
            android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
        } catch (t: Throwable) {
            Log.w(TAG, "打开音频失败：$file", t)
            return
        }
        notifyAudioFile(key, fd, displayName ?: file.name)
    }

    /**
     * 让宿主切换到某一页（v8）。
     *
     * ## 最典型的一行：推理完自动翻到结果页
     *
     * ```kotlin
     * override fun synthesize(req: SynthesisRequest) {
     *     val result = runInference(req)
     *     callback.onResult(result)          // 结果先交出去
     *     notifySelectTab("main", "result")  // 界面再翻过去
     * }
     * ```
     *
     * 先 `onResult` 再切页的顺序是有意义的：宿主是在 `onResult` 里把音频落盘、
     * 让结果页的播放条变成可用的。反过来切过去可能先看到一条空播放条。
     * （oneway 调用不保证严格顺序，但宿主会在主线程按到达顺序处理，
     * 而这两个都在同一根 Binder 线程上发出，实际顺序是稳定的。）
     *
     * ## 参数
     *
     * @param groupKey 那个 `UiType.TABS_START` 的 `key`。**必须非空**，
     *                 否则宿主认不出是哪一组（`TABS_START.key` 空的话
     *                 这一组根本没法被外部定位）
     * @param tabKey   目标 `UiType.TAB` 的 `key`
     *
     * ## 找不到会怎样
     *
     * 不报错。宿主会把目标记成"这一组该停在哪页"，下一次重画时生效 ——
     * 所以"先发跳转再改 schema"也能正常跳到。
     *
     * 但如果 `groupKey` 一直对不上（拼错了），表现就是**跳转静默无效**。
     * 排查方式：`adb logcat | grep PonkoPluginUi`，宿主会打一行
     * `selectTab 没有匹配的组`。
     */
    protected fun notifySelectTab(groupKey: String, tabKey: String) {
        val cb = callback ?: return
        try {
            cb.selectTab(groupKey, tabKey)
        } catch (t: Throwable) {
            Log.w(TAG, "selectTab 回调失败", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        // ⚠ Android 12+ 禁止后台启动前台服务。此刻宿主刚 bindService、还在前台，
        //   所以这里起一定能过；等到 synthesize 里再起就可能抛
        //   ForegroundServiceStartNotAllowedException。
        goForeground(Stage.LOADING, "准备中…")
        acquireWakeLock()
        Log.i(TAG, "onBind: ${intent?.action}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        cancelFlag = true
        callback = null
        runCatching { closeEngineBlocking() }.onFailure { Log.e(TAG, "onDestroy 释放失败", it) }
        engineLoaded = false
        state = EngineState.UNLOADED
        stopForegroundAndWakeLock()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ==================== 内部工具 ====================

    /** 回调可能已被宿主置 null，或被 binder 死亡；所有回调都走这里兜住 */
    private inline fun safeCallback(block: (IPonkoCallback) -> Unit) {
        val cb = callback ?: return
        runCatching { block(cb) }
            .onFailure { Log.w(TAG, "回调失败（宿主可能已退出）: ${it.javaClass.simpleName}") }
    }

    /** 错误也走 safeCallback，但要在没有回调时至少留日志 */
    private fun postError(e: PluginException, requestId: String?) {
        if (callback == null) {
            Log.w(TAG, "无回调，错误被丢弃: ${e.code} ${e.message}")
            return
        }
        safeCallback { it.onError(toPluginError(e, requestId)) }
    }

    private fun toPluginError(t: Throwable, requestId: String?): PluginError = when (t) {
        is PluginException -> PluginError(
            code = t.code,
            message = t.message,
            detail = t.cause?.stackTraceToString() ?: "",
            retryable = t.retryable,
            requestId = requestId,
        )
        is OutOfMemoryError -> PluginError(
            code = ErrCode.OUT_OF_MEMORY,
            message = "可用内存不足，请先卸载其它模型或关闭其它应用",
            detail = "OutOfMemoryError",
            retryable = true,
            requestId = requestId,
        )
        else -> PluginError(
            code = ErrCode.PLUGIN_INTERNAL,
            message = "插件内部错误：${t.javaClass.simpleName}",
            detail = t.stackTraceToString(),
            retryable = false,
            requestId = requestId,
        )
    }

    // ---- 前台服务 ----

    private fun goForeground(stage: Int, label: String?) {
        try {
            ensureChannel()
            val n = buildNotification(notificationText(stage, label))
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            // 最可能是 ForegroundServiceStartNotAllowedException（宿主已进后台）
            // 不崩：推理可能仍然能跑完，只是随时可能被系统回收
            Log.w(TAG, "startForeground 失败（宿主可能已进后台）: ${t.javaClass.simpleName}", t)
        }
    }

    private fun updateNotification(stage: Int, label: String?) {
        if (!foregroundStarted) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(notificationText(stage, label)))
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("Ponko 插件")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "插件运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    private fun stopForegroundAndWakeLock() {
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        wlHandler.removeCallbacks(wlRenew)
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    // ---- 唤醒锁 ----

    /**
     * 推理期间保住 CPU（屏幕由宿主用 FLAG_KEEP_SCREEN_ON 负责，两者互不替代）。
     *
     * ## 为什么要续期
     *
     * `acquire(timeout)` 是**一次性**的：到点自动释放，不会因为"还在工作"而延长。
     * 30 分钟对一次 TTS 够用，但对**下载 3.5 GB 模型**不够 ——
     * 网速慢的时候下载到一半锁就掉了，然后进程被系统降级/冻结，下载卡死。
     *
     * 所以工作期间每 [WAKELOCK_RENEW_MS] 续一次：
     * `setReferenceCounted(false)` 的锁再次 `acquire` 就是**重置超时**，
     * 不需要先 release（先 release 反而有被瞬间回收的窗口）。
     * 锁本身的 30 分钟上限仍然保留，防止忘了 release 一直耗电。
     */
    private fun acquireWakeLock() {
        wlHandler.removeCallbacks(wlRenew)
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.acquire(WAKELOCK_TIMEOUT_MS) }
        } else {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PonkoPlugin:work").apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
            }.onFailure { Log.w(TAG, "获取唤醒锁失败", it) }
        }
        wlHandler.postDelayed(wlRenew, WAKELOCK_RENEW_MS)
    }

    companion object {
        private const val TAG = "PonkoPlugin"
        private const val CHANNEL_ID = "ponko_plugin_run"
        private const val NOTIF_ID = 0x50C0
        const val ACTION_PLUGIN = PluginConst.ACTION_PLUGIN

        /** 唤醒锁单次上限：防泄漏，忘了 release 也不会一直耗电 */
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /** 续期间隔。取上限的 1/3，留足余量 */
        private const val WAKELOCK_RENEW_MS = 10 * 60 * 1000L
    }
}
