// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import android.content.Context
import android.util.Log
import com.litertchat.plugin.Capability
import com.litertchat.plugin.PluginConst
import com.litertchat.plugin.PluginError
import com.litertchat.plugin.ResultBundle
import com.litertchat.plugin.SynthesisRequest
import com.litertchat.plugin.PluginManifest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 面向 UI 的插件门面：管理所有连接、按能力选插件、把回调收敛成一个简化接口。
 *
 * UI 只需要：
 * ```kotlin
 * val session = PluginSession(ctx)
 * session.listener = ...
 * session.refresh()                 // 扫一遍，更新列表
 * session.setEnabled(pkg, true)     // 启用 → 自动 bind
 * session.speak(text)               // 找一个 TTS 插件念出来
 * session.release()                 // 页面销毁时
 * ```
 *
 * 内部对所有已启用插件各维护一个 [PluginConnection]，按能力分发。
 * v1 只用第一个**已连接且有 TTS 能力**的插件；多插件竞争留到以后。
 */
class PluginSession(private val context: Context) {

    interface Listener {
        /** 扫描结果变化（列表要重画） */
        fun onPluginsChanged(items: List<Item>) {}

        /** 某个插件的状态变化 */
        fun onPluginStateChanged(pkg: String, state: PluginConnection.State, detail: String?) {}

        /** 朗读进度 */
        fun onSpeakStage(pkg: String, stage: Int, label: String?) {}

        /**
         * 引擎加载完成。
         *
         * 宿主可以在这里做「刚才因为引擎没就绪而失败的试听」的自动重试 ——
         * 用户点了按钮却得到一句"引擎尚未加载"是死路，重载一次再补上才是完整体验。
         */
        fun onEngineReady(pkg: String, info: com.litertchat.plugin.EngineInfo) {}

        /** 朗读完成，音频写在 [result] 里（宿主负责 close 其中的 FD） */
        fun onSpeakDone(pkg: String, result: ResultBundle) {}

        /** 朗读失败；[err] 的 message 已本地化，可直接显示 */
        fun onSpeakError(pkg: String, err: PluginError) {}

        /** 插件自己改了界面数据 → 如果正打开着它的界面，就重画 */
        fun onPluginSchemaChanged(pkg: String) {}

        /**
         * 插件送了一张结果图（v4）。[bytes] 是图片原始字节（PNG/JPEG…）。
         *
         * 已经读进内存了 —— FD 在 [PluginConnection] 的 Binder 线程就关掉了。
         */
        fun onPluginImage(pkg: String, bytes: ByteArray, width: Int, height: Int, mime: String?) {}

        /**
         * 插件要求原地改一个控件（v4）。宿主只改那一个 view，不重画整页。
         */
        fun onPluginUiPatch(pkg: String, key: String, value: String?, append: Boolean) {}
    }

    /**
     * 列表里的一行。
     *
     * @param enabled 用户是否启用（持久化）
     * @param state 当前连接状态（内存态）
     */
    data class Item(
        val found: PluginDiscovery.Found,
        val enabled: Boolean,
        val state: PluginConnection.State,
        val manifest: PluginManifest?,
        val detail: String?,
        /** 插件编译时的 SDK 版本；未连接时 0 */
        val apiVersion: Int = 0,
        /**
         * 插件比宿主认识的版本新。**照常可用**，只是新控件/新回调用不了。
         *
         * 和 [isOlderSdk] 是同一件事的两个方向，UI 提示的话术不同：
         * 旧插件 → "更新插件可获得更多功能"；
         * 新插件 → "更新 Ponko 可获得完整功能"（**不能说成不兼容**）
         */
        val newerThanHost: Boolean = false,
    ) {
        /** 是否可以作为 TTS 使用（**正式路由**用这个） */
        val canSpeak: Boolean
            get() = state == PluginConnection.State.CONNECTED &&
                manifest?.capabilities?.contains(Capability.TTS) == true

        /** 是不是测试 / 示例插件 */
        val isTest: Boolean
            get() = manifest?.capabilities?.contains(Capability.TEST) == true

        /**
         * 是否能在插件界面里"试听"。
         *
         * 包含测试插件：试听对它是**诊断动作**（验证整条音频链路），
         * 而不是"朗读文本"。但正式路由（[canSpeak]）不会选它。
         */
        val canPreviewAudio: Boolean
            get() = state == PluginConnection.State.CONNECTED &&
                (canSpeak || isTest)

        /**
         * 连接后是否要自动加载引擎。
         *
         * 测试插件也要 —— 否则用户点开插件界面点「试听」会立刻报"引擎未就绪"，
         * 而那看起来像插件坏了。
         */
        val wantsEngine: Boolean
            get() = canSpeak || isTest

        /**
         * 插件是否支持「界面」这一页（SDK v[PluginConst.API_WITH_SCHEMA] 起）。
         *
         * 不支持时卡片上不显示「插件界面」按钮 —— 点进去只会看到
         * "这个插件是旧版 SDK 构建的"，那个按钮没有意义。
         */
        val supportsSchema: Boolean
            get() = state == PluginConnection.State.CONNECTED &&
                apiVersion >= PluginConst.API_WITH_SCHEMA

        /** 连接上了，但用的是旧版 SDK —— UI 上可以提示一句"更新插件可获得更多功能" */
        val isOlderSdk: Boolean
            get() = apiVersion in 1 until PluginConst.API_VERSION
    }

    var listener: Listener? = null

    private val connections = CopyOnWriteArrayList<PluginConnection>()

    /** 主线程 Handler：激活后的延迟重试用它 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 最近一次扫描结果（未启用的插件也要显示，所以这里存全量） */
    @Volatile private var found: List<PluginDiscovery.Found> = emptyList()

    /** 当前正在朗读的插件包名；null = 没在朗读 */
    @Volatile private var speakingPkg: String? = null

    @Volatile private var released = false

    // ==================== 扫描与启用 ====================

    /** 重新扫描。会顺带连接「已启用但还没连」的插件 */
    fun refresh() {
        if (released) return
        val list = PluginDiscovery.scan(context)
        found = list

        // 已启用但没连接的 → 连上
        list.filter { PluginRegistry.isEnabled(context, it.packageName) }
            .forEach { ensureConnection(it.packageName, connectIfIdle = true) }

        // 连接列表里那些已经被卸载的插件 → 清掉残留偏好
        val installed = list.map { it.packageName }.toSet()
        connections.filter { it.packageName !in installed }.forEach { c ->
            Log.i(TAG, "插件已卸载，清理：${c.packageName}")
            c.disconnect()
            connections.remove(c)
            PluginRegistry.forget(context, c.packageName)
        }

        // 还有一类残留：**曾经启用过、但从未成功连接**的包（比如装完就卸了）。
        // 它们不在 connections 里，上面那轮清不到，会在偏好里一直留着。
        // 这里按「启用列表 - 当前装着的」补一遍。
        runCatching {
            (PluginRegistry.enabledPackages(context) - installed).forEach { gone ->
                Log.i(TAG, "清理已卸载插件的残留偏好：$gone")
                PluginRegistry.forget(context, gone)
            }
        }

        notifyChanged()
    }

    fun setEnabled(pkg: String, enabled: Boolean) {
        PluginRegistry.setEnabled(context, pkg, enabled)
        if (enabled) {
            // 启用的这一刻顺手把 stopped 状态解掉 —— 见 activateIfStopped 的说明。
            // 这一步要在 connect 之前：stopped 没清的话 bind 一定失败
            if (activateIfStopped(pkg)) {
                // 已经拉起插件的激活组件了。**先不连** —— 标记还没清，
                // 现在连必然失败，会在界面上闪一条"连接失败"的假错误。
                notifyChanged()
                // 保底：激活组件是无界面的（Theme.NoDisplay + 立刻 finish），
                // 某些 ROM 上宿主的 onResume 可能不会走完整循环，
                // 光靠 onResume 重连不够。这里再安排一次延迟重试。
                // 两边都触发也没关系 —— connect() 内部有状态检查，不会重复绑
                main.postDelayed({ if (!released) refresh() }, 900)
                return
            }
            ensureConnection(pkg, connectIfIdle = true)
        } else {
            // 停用的插件可能正在朗读 —— 不清这两个状态的话，
            // isSpeaking() 会一直返回 true，界面上还挂着"正在生成…"，
            // 而实际上那个连接已经没了
            if (speakingPkg == pkg) speakingPkg = null
            connections.firstOrNull { it.packageName == pkg }?.let {
                it.disconnect()
                connections.remove(it)
            }
        }
        notifyChanged()
    }

    /**
     * 插件处于 stopped 状态时，**自动拉起它的激活页**。
     *
     * ## 为什么必须这么做
     *
     * 新装（或刚被「强制停止」）的包处于系统 stopped 状态，此时别的应用
     * `bindService` 到它的服务会**静默返回 false**。解除方式只有一个：
     * 包自己的 Activity 被启动一次。广播、`startService`、`pm enable` 都不行，
     * Android 15 起官方更明确「只能由用户操作解除」。
     *
     * ## 为什么要"自动"
     *
     * 不自动的话，用户得自己回桌面找到插件图标点开一次，再回来点启用 ——
     * 两步操作，而且没人知道为什么要做这件事。
     *
     * 自动之后：点「启用」→ 插件的激活页一闪（SDK 侧看到
     * [com.litertchat.plugin.PonkoActivation.EXTRA_ACTIVATE_ONLY] 会立刻 finish）
     * → 回来时自动连上。**用户只按了一次键。**
     *
     * ## 只有一次
     *
     * 清掉之后不会再变回去，除非卸载重装 / 用户手动「强制停止」。
     *
     * @return true = 已经发起激活（调用方不要再 bind）
     */
    private fun activateIfStopped(pkg: String): Boolean {
        val diag = try {
            PluginDiscovery.diagnose(context, pkg)
        } catch (t: Throwable) {
            return false
        }
        if (!diag.stopped) return false
        if (!diag.hasLauncher) {
            // 没有可点的入口就清不了 —— 这只能靠插件作者加一个 Activity
            Log.w(TAG, "$pkg 处于 stopped 且没有 launcher Activity，无法自动激活")
            return false
        }
        val intent = com.litertchat.plugin.PonkoActivation.buildIntent(
            context, pkg, PluginDiscovery.launcherClassOf(context, pkg)
        ) ?: return false

        return try {
            context.startActivity(intent)
            Log.i(TAG, "$pkg 处于 stopped，已拉起激活页")
            pendingActivation = pkg
            true
        } catch (t: Throwable) {
            // 极少数 ROM 会拦截；那就只能靠界面上的「打开插件」按钮手动做
            Log.w(TAG, "$pkg 自动激活失败", t)
            false
        }
    }

    /** 刚发起过激活的包；用于回来时给一句提示 */
    @Volatile private var pendingActivation: String? = null

    /** 取走"刚激活过"的包名（取完清空，只提示一次） */
    fun consumePendingActivation(): String? {
        val p = pendingActivation ?: return null
        pendingActivation = null
        return p
    }

    private fun ensureConnection(pkg: String, connectIfIdle: Boolean): PluginConnection {
        var c = connections.firstOrNull { it.packageName == pkg }
        if (c == null) {
            c = PluginConnection(context, pkg).also {
                it.listener = forwarder(pkg)
                connections.add(it)
            }
        }
        if (connectIfIdle &&
            (c.currentState == PluginConnection.State.IDLE ||
                c.currentState == PluginConnection.State.FAILED ||
                c.currentState == PluginConnection.State.DISCONNECTED)
        ) {
            c.connect()
        }
        return c
    }

    private fun forwarder(pkg: String) = object : PluginConnection.Listener {
        override fun onStateChanged(state: PluginConnection.State, detail: String?) {
            listener?.onPluginStateChanged(pkg, state, detail)
            notifyChanged()
            // 是否在连上时顺手加载引擎 —— **由插件自己声明**，不由宿主统一决定。
            //
            // 代价差别是数量级的：Echo 这种玩具插件不到 1 秒、零内存；
            // VoxCPM2 这种真模型几十秒、几 GB。宿主猜不出这个差别，
            // 一律加载的结果就是"用户只是来看一眼连接状态，后台开始灌 3.5 GB"。
            //
            // 声明了 Capability.AUTOLOAD_ENGINE 才加载；否则等用户点「加载」
            // 或点「试听」（试听路径里有 NOT_READY → 自动加载 → 补发）。
            if (state == PluginConnection.State.CONNECTED) {
                val conn = connections.firstOrNull { it.packageName == pkg }
                val caps = conn?.currentManifest?.capabilities
                val canAudio = caps?.contains(Capability.TTS) == true ||
                    caps?.contains(Capability.TEST) == true
                val autoLoad = caps?.contains(Capability.AUTOLOAD_ENGINE) == true
                if (canAudio && autoLoad) {
                    // 线程数从宿主偏好里取（用户可在插件页的试听参数里改）。
                    // 传 0 会让插件退回 min(4, 核数) —— 在 8 核机上明显跑不满
                    val threads = runCatching {
                        PreviewParams.load(context, pkg).threads
                    }.getOrDefault(0)
                    conn?.loadEngine(threads = threads)
                } else if (canAudio) {
                    Log.i(TAG, "[$pkg] 未声明 AUTOLOAD_ENGINE，等用户手动加载引擎")
                }
            }
        }

        override fun onEngineLoaded(info: com.litertchat.plugin.EngineInfo) {
            Log.i(TAG, "[$pkg] 引擎就绪：${info.sampleRate}Hz / ${info.modelBytes}B / ${info.loadElapsedMs}ms")
            listener?.onEngineReady(pkg, info)
            notifyChanged()
        }

        override fun onEngineError(err: PluginError) {
            Log.w(TAG, "[$pkg] 引擎加载失败：${err.code} ${err.message}")
            listener?.onSpeakError(pkg, err)
            notifyChanged()
        }

        override fun onStage(stage: Int, label: String?) {
            listener?.onSpeakStage(pkg, stage, label)
        }

        override fun onAudioChunk(pcm: ByteArray, sampleRate: Int, channelCount: Int, isFinal: Boolean) {
            // v1 走非流式（streamAudio=false），正常不会进这里；
            // 保留以便插件自作主张推流时至少不崩
            Log.d(TAG, "[$pkg] 收到音频块 ${pcm.size}B final=$isFinal")
        }

        override fun onResult(result: ResultBundle) {
            if (speakingPkg == pkg) speakingPkg = null
            listener?.onSpeakDone(pkg, result)
            notifyChanged()
        }

        override fun onError(err: PluginError) {
            if (err.code == com.litertchat.plugin.ErrCode.CANCELLED) {
                // 取消不是错误，UI 静默收尾
                if (speakingPkg == pkg) speakingPkg = null
                listener?.onSpeakStage(pkg, com.litertchat.plugin.Stage.CANCELLED, null)
                notifyChanged()
                return
            }
            if (speakingPkg == pkg) speakingPkg = null
            listener?.onSpeakError(pkg, err)
            notifyChanged()
        }

        override fun onSchemaChanged() {
            listener?.onPluginSchemaChanged(pkg)
        }

        override fun onImage(bytes: ByteArray, width: Int, height: Int, mime: String?) {
            Log.i(TAG, "[$pkg] 收到结果图 ${bytes.size}B ${width}x$height $mime")
            listener?.onPluginImage(pkg, bytes, width, height, mime)
        }

        override fun onUiPatch(key: String, value: String?, append: Boolean) {
            // 补丁是高频的（刷日志），**不写日志** —— 否则 logcat 会被刷满，
            // 真正要看的东西反而找不到
            listener?.onPluginUiPatch(pkg, key, value, append)
        }
    }

    private fun notifyChanged() {
        listener?.onPluginsChanged(items())
    }

    fun items(): List<Item> = found.map { f ->
        val c = connections.firstOrNull { it.packageName == f.packageName }
        Item(
            found = f,
            enabled = PluginRegistry.isEnabled(context, f.packageName),
            state = c?.currentState ?: PluginConnection.State.IDLE,
            manifest = c?.currentManifest,
            // 从连接里读回失败原因：视图重建后仍要能看到，否则用户切个 tab 就不知道
            // 刚才为什么连不上了
            detail = c?.lastDetail,
            apiVersion = c?.pluginApi ?: 0,
            newerThanHost = c?.pluginNewerThanHost ?: false,
        )
    }

    /**
     * 把插件"点开一次"。
     *
     * 为什么需要这一步：**新安装的包处于 stopped 状态，其他应用 bind 不到它的服务，
     * `bindService` 直接返回 false。** 只有它自己被启动过一次，这个标记才会清掉。
     *
     * 带 [com.litertchat.plugin.PonkoActivation.EXTRA_ACTIVATE_ONLY]，
     * 所以插件那边会立刻 finish —— 用户只看到一闪，不会被扔进说明页。
     *
     * @return true = 已发起启动（用户回来时 onResume 会自动重连）
     */
    fun openPluginOnce(pkg: String): Boolean {
        val i = com.litertchat.plugin.PonkoActivation.buildIntent(
            context, pkg, PluginDiscovery.launcherClassOf(context, pkg)
        ) ?: return false
        return try {
            context.startActivity(i)
            pendingActivation = pkg
            true
        } catch (t: Throwable) {
            Log.w(TAG, "启动插件失败：$pkg", t)
            false
        }
    }

    // ==================== 朗读 ====================

    /** 当前可用的 TTS 插件；null = 没有 */
    fun ttsPlugin(): PluginConnection? =
        connections.firstOrNull { c ->
            c.currentState == PluginConnection.State.CONNECTED &&
                c.currentManifest?.capabilities?.contains(Capability.TTS) == true
        }

    /**
     * 取某个插件的连接（可能未连接）。
     *
     * UI 需要它来读 `stageName()` 这类"和具体插件有关"的信息 ——
     * 用 [ttsPlugin] 会拿错（测试插件不在 TTS 路由里，但它的进度文案也要显示）。
     */
    fun connectionOf(pkg: String): PluginConnection? =
        connections.firstOrNull { it.packageName == pkg }

    /**
     * 把一段文字念出来。
     *
     * @param voiceDescription 语音设计描述（可为空）；插件负责拼成 VoxCPM2 的文本前缀
     * @return requestId；没有可用插件时返回 null
     */
    fun speak(
        text: String,
        voiceDescription: String = "",
        streamAudio: Boolean = false,
    ): String? {
        val c = ttsPlugin() ?: run {
            listener?.onSpeakError(
                "—",
                PluginError(
                    code = com.litertchat.plugin.ErrCode.NOT_READY,
                    message = "没有可用的朗读插件",
                )
            )
            return null
        }
        return doSpeak(c, text, voiceDescription, streamAudio)
    }

    /**
     * 对**指定插件**发起一次合成。
     *
     * 和 [speak] 的区别：`speak` 走"谁有 TTS 能力就用谁"的正式路由；
     * 这个方法是插件界面里那个「试听」按钮用的 —— 用户明确站在某个插件页里，
     * 就该用那个插件，而不是路由到别的。
     *
     * 因此它也接受**测试插件**（[Capability.TEST]）：试听对它是诊断动作。
     *
     * ## 参数
     *
     * 除 `text` 外全部可选，默认值等于"纯 TTS / 不克隆 / 随机种子"，
     * 也就是旧行为。用户选了克隆模式时由宿主填 [referenceAudio] 等
     * （见 app 侧的 `PreviewParams`）。
     *
     * @param referenceAudio 参考音频 FD，**所有权仍在宿主**：插件读完后由宿主 close
     * @return requestId；插件未连接 / 没有音频能力时返回 null
     */
    fun speakTo(
        pkg: String,
        text: String,
        voiceDescription: String = "",
        streamAudio: Boolean = false,
        mode: Int = PluginConst.MODE_PLAIN,
        referenceAudio: android.os.ParcelFileDescriptor? = null,
        referenceTranscript: String = "",
        seed: Int = 0,
    ): String? {
        val c = connections.firstOrNull { it.packageName == pkg }
        if (c == null || c.currentState != PluginConnection.State.CONNECTED) {
            listener?.onSpeakError(
                pkg,
                PluginError(
                    code = com.litertchat.plugin.ErrCode.NOT_READY,
                    message = "插件未连接",
                )
            )
            return null
        }
        val caps = c.currentManifest?.capabilities
        val ok = caps?.contains(Capability.TTS) == true || caps?.contains(Capability.TEST) == true
        if (!ok) {
            listener?.onSpeakError(
                pkg,
                PluginError(
                    code = com.litertchat.plugin.ErrCode.UNSUPPORTED,
                    message = "这个插件没有提供音频合成能力",
                )
            )
            return null
        }
        return doSpeak(
            c, text, voiceDescription, streamAudio,
            mode = mode,
            referenceAudio = referenceAudio,
            referenceTranscript = referenceTranscript,
            seed = seed,
        )
    }

    private fun doSpeak(
        c: PluginConnection,
        text: String,
        voiceDescription: String,
        streamAudio: Boolean,
        mode: Int = PluginConst.MODE_PLAIN,
        referenceAudio: android.os.ParcelFileDescriptor? = null,
        referenceTranscript: String = "",
        seed: Int = 0,
    ): String? {
        speakingPkg = c.packageName
        val req = SynthesisRequest(
            requestId = UUID.randomUUID().toString(),
            text = text,
            mode = mode,
            referenceAudio = referenceAudio,
            referenceTranscript = referenceTranscript,
            voiceDescription = voiceDescription,
            seed = seed,
            streamAudio = streamAudio,
        )
        return c.synthesize(req)
    }

    /** 取消当前朗读 */
    fun stopSpeaking() {
        val pkg = speakingPkg ?: return
        connections.firstOrNull { it.packageName == pkg }?.cancel()
    }

    /** 是否正在朗读 */
    fun isSpeaking(): Boolean = speakingPkg != null

    /** 某个插件是否正在合成。UI 用它决定「试听/停止」哪个可点 */
    fun isSpeaking(pkg: String): Boolean = speakingPkg == pkg

    /** 某个插件的引擎是否已就绪。插件页的「加载 / 卸载」按钮用它决定文案 */
    fun engineReady(pkg: String): Boolean =
        connections.firstOrNull { it.packageName == pkg }?.engineReady() == true

    /** 引擎状态（UNLOADED / LOADING / READY / RUNNING）。用于轮询到目标状态 */
    fun engineState(pkg: String): Int =
        connections.firstOrNull { it.packageName == pkg }
            ?.status()?.state ?: com.litertchat.plugin.EngineState.UNLOADED

    /**
     * 按钮该显示什么字：插件覆盖过就用它的，否则宿主默认。
     *
     * 插件连不上时也走默认 —— 界面在没连上的状态下照样要能画出来
     * （显示"未连接"+ 一排灰按钮比空白页好）。
     *
     * @param key 见 [com.litertchat.plugin.ActionLabel]
     */
    fun actionLabel(pkg: String, key: String): String =
        connections.firstOrNull { it.packageName == pkg }?.label(key)
            ?: ActionLabels.resolve(key, emptyMap())

    /**
     * 用户手动加载 / 卸载引擎。
     *
     * 只有插件没声明 [Capability.AUTOLOAD_ENGINE] 时才会被用到 ——
     * 声明了的插件连上就已经加载好了。
     *
     * @param load true = 加载，false = 卸载（释放模型内存）
     */
    fun toggleEngine(pkg: String, load: Boolean, threads: Int = 0) {
        val conn = connections.firstOrNull { it.packageName == pkg } ?: return
        if (load) {
            conn.loadEngine(threads = threads)
        } else {
            conn.closeEngine()
            notifyChanged()
        }
    }

    // ==================== 插件界面 ====================

    /**
     * 取某个插件的界面数据。插件没连接时返回一句说明而不是空对象 ——
     * 用户点进插件界面却看到一片空白，会以为是 bug。
     */
    fun schemaOf(pkg: String): com.litertchat.plugin.UiSchema {
        val c = connections.firstOrNull { it.packageName == pkg }
            ?: return com.litertchat.plugin.UiSchema(intro = "插件未连接，请先在列表里启用它")
        if (c.currentState != PluginConnection.State.CONNECTED) {
            return com.litertchat.plugin.UiSchema(intro = "插件还没连上（当前：${c.currentState}）")
        }
        return c.settingsSchema()
    }

    fun setSetting(pkg: String, key: String, value: String) {
        connections.firstOrNull { it.packageName == pkg }?.setSetting(key, value)
    }

    fun invokeAction(pkg: String, key: String) {
        connections.firstOrNull { it.packageName == pkg }?.invokeAction(key)
    }

    // ==================== 释放 ====================

    /** 页面销毁时调。解绑全部连接（插件进程会自己降级） */
    fun release() {
        released = true
        listener = null
        // 撤掉所有待执行的延迟重试，否则页面销毁后还会去连插件
        main.removeCallbacksAndMessages(null)
        connections.forEach { runCatching { it.disconnect() } }
        connections.clear()
    }

    companion object {
        private const val TAG = "PonkoPluginSession"
    }
}
