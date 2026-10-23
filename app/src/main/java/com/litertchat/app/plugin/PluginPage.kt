// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.litertchat.app.PonkoTheme
import com.litertchat.app.R
import com.litertchat.plugin.Capability
import com.litertchat.plugin.ErrCode
import com.litertchat.plugin.PluginConst
import com.litertchat.plugin.PluginError
import com.litertchat.plugin.Stage

/**
 * **Ponko-EX 页**（底部菜单第 4 个 tab）。
 *
 * 只做三件事：**列表、启用/停用、看状态**。不在这里下载插件（用户自己装 APK）。
 *
 * 刷新很便宜（一次 `queryIntentServices`），所以每次切到这一页都重扫，
 * 不做缓存 —— 缓存会导致「装了新插件但列表不更新」，那是最容易引起误解的行为。
 *
 * 生命周期由 [MainActivity] 管：切换 tab 时不 [release]（切回来还要保持连接与状态），
 * 只有 Activity 真正销毁时才调。
 */
class PluginPage(
    private val act: Activity,
    pal: PonkoTheme,
    /**
     * 全屏看图。由 [MainActivity] 注入它已有的 `showImageFullscreen` ——
     * 缩放/双击/关闭手势只该有一份，这里再写一套必然和别的页面手感不一致。
     */
    private val onZoomImage: ((android.graphics.Bitmap) -> Unit)? = null,
) {
    /** 配色表；换主题时先 setPalette 再 build() 重建视图（与本工程其他页一致） */
    var pal: PonkoTheme = pal
        private set

    /** 换配色（下次 [build] 生效） */
    fun setPalette(p: PonkoTheme) {
        pal = p
    }

    private val session = PluginSession(act)

    /**
     * 取本地化字符串。
     *
     * `PluginPage` 不是 `Context`（它是个普通类，只持有 `act`），所以没有
     * `getString` 可继承 —— 2026-10-23 接 i18n 时加的这个转发。
     *
     * 走 `act.getString` 而不是 `context.resources.getString`：
     * Activity 的 context 已经带好当前配置（含语言），
     * `createConfigurationContext` 那种固定语言的 context 不能用来取文案。
     */
    private fun getString(id: Int, vararg args: Any): String =
        if (args.isEmpty()) act.getString(id) else act.getString(id, *args)

    /** 每次重建视图时换一个容器引用，回调里往它里面画 */
    private var listHost: LinearLayout? = null

    /**
     * 搜索框的输入内容。空串 = 不过滤。
     *
     * 存成字段而不是只读控件里的文本：`onPluginsChanged` 会拿新的插件列表
     * 重新渲染，那时要按**当前的**查询再过滤一遍。只看控件的话，
     * 重建视图之后搜索就悄悄失效了。
     */
    private var searchQuery: String = ""

    /** 搜索框本身，给「清空」和后续可能的"聚焦"用 */
    private var searchInputRef: EditText? = null

    /**
     * 最近一次扫描到的完整插件列表（**未过滤**）。
     *
     * 输入时不必重新扫描磁盘，直接从这份里筛 —— 扫描要走 PackageManager、
     * 读签名、读 manifest，每敲一个字跑一遍是不可接受的。
     */
    private var allItems: List<PluginSession.Item> = emptyList()

    /**
     * 试听 / 播放的状态文本。
     *
     * 显示位置在**插件界面的功能区**（`PluginUiPage.setActionHint`），不在卡片上 ——
     * 按钮搬进去之后，进度还留在卡片上用户根本看不到。
     *
     * 这里存一份是因为插件界面可能被重建（schema 变化、切 tab 再回来），
     * 重建时要用它把文字恢复出来。
     */
    private var speakHintText: String = ""

    /**
     * 「引擎未就绪」自动补救的状态。
     *
     * 场景：插件进程被系统回收后又重连、或插件自己把引擎卸了，用户点试听会
     * 立刻收到 `NOT_READY`（"引擎尚未加载"）。以前这就只是一句错误 —— 用户不知道
     * 下一步该干什么，只能反复点。现在宿主自己重载一次引擎，加载完把这次试听补上。
     *
     * [previewRetryUsed] 保证**一次点击只补救一次**，
     * 否则"加载失败 → 重试 → 又失败"会变成死循环。
     */
    private var previewRetryPkg: String? = null
    private var previewRetryUsed = false

    /**
     * 试听参数（按包名缓存，落盘在 SharedPreferences）。
     *
     * 这些是**宿主的**参数：用什么文本、哪个模式、哪段参考音频。
     * 和插件 `UiSchema` 里那些"插件自己的配置"是两回事，见 [PreviewParams]。
     */
    private val paramsCache = HashMap<String, PreviewParams>()

    /** 每个插件最近一次的诊断文本（debugInfo + 最后一条错误），供诊断区显示 */
    private val diagCache = HashMap<String, String>()

    /**
     * 正在等用户选参考音频的包名。
     *
     * `startActivityForResult` 回来时只有 requestCode，没有"哪个插件"的信息 ——
     * 用户可能在等待期间切走了，所以这里记下来，回来再对。
     */
    private var pendingRefPkg: String? = null

    /**
     * 正在等用户给某个模型槽位挑文件（v6）。
     *
     * 和 [pendingRefPkg] 同样的道理：`onActivityResult` 回来时只有 requestCode，
     * 没有"哪个插件、哪个槽位"的信息。用户可能在等选择器的时候切走了，
     * 所以两个都记下来，回来再对。
     */
    private var pendingModelPkg: String? = null
    private var pendingModelRole: String? = null

    /** 参考音频那一行文字（选完之后要就地更新，不重建整页） */
    private var refLabel: android.widget.TextView? = null

    /**
     * 「文本」输入框（要合成的内容）。
     *
     * 存引用只为一件事：文本为空时把焦点送过去，让用户立刻能填。
     * 行为本身不依赖它 —— 合成读的是 [params] 里那份值。
     */
    private var speakTextInput: android.widget.EditText? = null

    /**
     * 上一次生成的音频文件。
     *
     * 写进 `getExternalFilesDir("tts")`（带时间戳，不覆盖），
     * 「保存」按钮再把它复制到系统「音乐/Ponko」，别的播放器才看得到。
     */
    private var lastAudioFile: java.io.File? = null
    private var lastAudioPkg: String? = null

    /**
     * 参考音频的 FD。
     *
     * 从 SAF Uri 打开，随 `synthesize` 一起交给插件（Binder 会 dup 一份过去）。
     * **自己这份要留着，等这次请求彻底结束再 close** —— 插件是异步读的，
     * 提早关会让它读到 EBADF。
     */
    private var refFd: android.os.ParcelFileDescriptor? = null

    /** 当前这次试听是否在进行中（决定「试听/停止」两个按钮谁可点） */
    private var speaking = false

    /**
     * 每张卡片的展开状态（key = 包名）。true = 展开。
     *
     * 只在内存里，没落盘：重启 App 回到默认展开是对的 ——
     * 用户隔一天进来，多半想直接看到状态和按钮，而不是一堆折叠条。
     */
    private val expandedMap = HashMap<String, Boolean>()

    /** 列表与插件界面之间的容器（build 时创建） */
    private var pageRoot: android.widget.FrameLayout? = null

    /**
     * 标题左边那个 ◀（返回插件列表）。
     *
     * 只在插件界面打开时可见 —— 文案层在 `build()` 里生成，
     * 但显示/隐藏要跟着 [currentUiPage] 走，所以留住引用
     * （`openPluginUi` / `closePluginUi` 各改一次）。
     */
    private var backToPluginList: TextView? = null
    /** 列表那一层。打开插件界面时把它藏起来，而不是销毁 —— 回来要保住滚动位置 */
    private var listLayer: View? = null
    /** 当前正在显示的插件界面；null = 在列表 */
    private var currentUiPage: PluginUiPage? = null
    /** 当前插件界面对应的包名；null = 在列表 */
    private var openUiPkg: String? = null

    /**
     * 当前打开的插件是不是**真的 TTS**（有能力 `tts.synthesize`）。
     *
     * 存成字段是因为"引擎重载后自动补发那次试听"（`onEngineReady`）拿不到
     * `PluginSession.Item`，而它也要知道该不该走克隆参数。
     */
    private var openUiIsTts = false

    /** 试听播放器；换一次试听就换一个，页面销毁时释放 */
    private var player: android.media.MediaPlayer? = null

    /**
     * v7：插件给 `AUDIO` 条目送的**自己那份**音频，按 `包名|key` 索引。
     *
     * 存在的理由：历史记录里每条都要能单独播/单独存。宿主那一个
     * `lastAudioFile` 只能表示"最新一条"，点第 3 条听到的会是第 9 条的声音。
     *
     * 同一个 key 再来一份就替换，旧文件顺手删掉 —— 否则插件每出一条记录
     * 就在 `getExternalFilesDir("tts")` 里多留一个文件，用户看不见也删不掉。
     */
    private val keyAudioFiles = HashMap<String, java.io.File>()

    /**
     * 每个 key 一个播放器。
     *
     * 为什么不能共用一个：切到第 5 条时第 3 条的位置/时长就没意义了，
     * 而播放条是**同时**显示在所有条目上的（进度、▶/⏸）。
     *
     * 空 key（`""`）那条复用 [player]，那是"宿主最近产物"那条路径。
     */
    private val keyPlayers = HashMap<String, android.media.MediaPlayer>()

    /** 每个 key 的播放器当前打开的是哪个文件。文件换了要重建播放器 */
    private val keyPlayerFiles = HashMap<String, java.io.File>()

    /**
     * 时长缓存。索引是 `"dur:<绝对路径>"`。
     *
     * 为什么要缓存：`MediaMetadataRetriever` 每次都要打开文件解析头部，
     * 而播放条每 200ms 就会问一次时长。
     */
    private val keyDurations = HashMap<String, Int>()

    // 这里原来有个 `lastToasted` 字段，用于给连接失败的 toast 去重。
    // 2026-10-23 起失败不再弹 toast（原因见 onPluginStateChanged），字段一并删掉。

    /**
     * 构建整页。返回的是**自带滚动**的视图，可直接塞进底部菜单的内容区 ——
     * 调用方不需要再包 ScrollView。
     */
    fun build(): View {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            // 显式给背景，和 ScrollView 同色。
            // 不给的话内容不足一屏时 root 会被 isFillViewport 拉伸，
            // 下方露出 ScrollView 底色 —— 视觉上像一块"来路不明的灰区"
            setBackgroundColor(pal.bg)
        }

        // ---- 顶部：◀ + 标题 ----
        //
        // 「Ponko-EX」这个标题在**列表层外面**（listLayer 是下面那个 container
        // 里的 ScrollView），所以打开插件界面之后它仍然在顶部 —— 正好拿来做
        // 固定的返回入口。
        //
        // 为什么需要它：PluginUiPage 自己那个「‹ 返回」在**正文顶部**，
        // 插件界面内容一长，滚下去就看不见了，用户得一路滚回顶部才能退出。
        // 这一行的 ◀ 不跟着内容滚，随时能回列表。
        //
        // 只在插件界面打开时显示：在列表页它没有去处（那已经是 Ponko-EX
        // 自己的页面），常驻一个点了没反应的按钮比不显示更糟。
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(act).apply {
            text = "◀"
            textSize = 13f
            setTextColor(pal.primary)
            gravity = Gravity.CENTER
            // 图标按钮必须自己撑点击区域。文字只有 13sp 宽，
            // 不给内边距的话热区不到 20dp，拇指点不准
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            // 图标没有文字，屏幕阅读器读不出来 —— 必须给
            contentDescription = getString(R.string.s_747)
            setOnClickListener { closePluginUi() }
            // build() 可能被重新调用（换主题），状态要以 currentUiPage 为准
            visibility = if (currentUiPage != null) View.VISIBLE else View.GONE
        }
        header.addView(back, wrapWrap().apply { rightMargin = dp(2) })
        header.addView(pageTitle("Ponko-EX"), wrapWrap())
        root.addView(header, matchWrap())
        backToPluginList = back

        // 这一行原来有一段解释（"插件是独立的 APK，安装后在这里启用…"）。
        // 2026-10-23 删：它讲的事，第一次用的人点一下「怎么装插件」就能看到，
        // 而每次进这一页都要先读过一段话才能看到按钮。页面上不再留前情提要。

        // ---- 环境自检 ----
        // 放在最显眼处。出 SecurityException 时，用户第一眼就能分清是
        // 「权限没拿到」还是「签名不一致」，不用再猜。
        val holdsPerm = PluginDiscovery.holdsBindPermission(act)
        val selfSign = PluginDiscovery.selfSignerShort(act)
        root.addView(TextView(act).apply {
            text = getString(R.string.s_474) +
                (if (holdsPerm) getString(R.string.s_475) else getString(R.string.s_476)) +
                getString(R.string.s_477) + selfSign.ifEmpty { getString(R.string.s_478) }
            textSize = 11f
            setTextColor(if (holdsPerm) pal.hint else pal.err)
            setPadding(0, dp(6), 0, 0)
        })

        // ---- 扫描按钮 + 状态 ----
        val toolbar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnRefresh = smallButton(getString(R.string.s_479)) { refresh() }
        toolbar.addView(btnRefresh)
        val btnHelp = smallButton(getString(R.string.s_480)) { showInstallHelp() }
        toolbar.addView(btnHelp, wrapWrap().apply { leftMargin = dp(8) })
        root.addView(toolbar, matchWrap().apply { topMargin = dp(12) })

        // ---- 搜索框 ----
        //
        // 单独一行，不和上面两个按钮挤在一起：插件多了之后
        // 「重新扫描 / 怎么装插件 / 搜索框」三个抢一行，搜索框会窄到只能
        // 显示两三个字，hint 都看不全。
        //
        // 空查询时列表原样显示（不做任何过滤），所以没装几个插件的人
        // 不会因为多了一个框而多看见任何东西。
        val searchRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchInput = EditText(act).apply {
            hint = getString(R.string.s_481)
            // Kotlin 里要用 setSingleLine()，`singleLine` 那个是 XML 属性
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            styleField(this)
            // 页面可能被重建（主题切换等），而 [searchQuery] 是实例字段、会留着。
            // 不把文本填回去的话，就会出现"框是空的、列表却被过滤了"的矛盾状态
            if (searchQuery.isNotEmpty()) setText(searchQuery)
        }
        // 清空按钮：只在有输入时出现。
        // 不做成输入框内的 X 图标，是因为那要往 EditText 里塞 drawable，
        // 还得管点击区域和深浅色 —— 一个按钮更直白，也更好点。
        val btnClear = smallButton(getString(R.string.s_482)) {
            searchInput.setText("")
            searchQuery = ""
            applyFilter()
            // 光标回到输入框：清空通常是为了接着输入下一个词，
            // 焦点留在这里键盘就不会收起来
            searchInputRef?.requestFocus()
        }.apply { visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                if (q == searchQuery) return
                searchQuery = q
                btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                applyFilter()
            }
        })
        searchInputRef = searchInput

        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, wrapWrap().height, 1f))
        searchRow.addView(btnClear, wrapWrap().apply { leftMargin = dp(8) })
        root.addView(searchRow, matchWrap().apply { topMargin = dp(10) })

        // ---- 列表容器 ----
        val host = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        listHost = host
        root.addView(host, matchWrap().apply { topMargin = dp(8) })

        // ---- 说明 ----
        //
        // 包成一个和插件卡片同样底色的区块。
        // 之前这里直接往页面背景上加文字，卡片（白）下面是整片页面灰，
        // 看起来像"卡片下面挂着一个灰色框"。给说明区一个明确的边界就正常了。
        val aboutCard = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(14))
            background = rounded(pal.surface, 12)
        }
        aboutCard.addView(pageTitle(getString(R.string.s_483)), matchWrap().apply { topMargin = dp(10) })
        // 色条图例。收起状态只剩名字，颜色就是唯一的状态信号 ——
        // 不解释一下，用户会以为那只是装饰
        aboutCard.addView(colorLegend(), matchWrap().apply { topMargin = dp(6) })
        aboutCard.addView(hintText(
            getString(R.string.s_614) +
                getString(R.string.s_485) +
                getString(R.string.s_486) +
                getString(R.string.s_487) +
                getString(R.string.s_615) +
                getString(R.string.s_616) +
                getString(R.string.s_490)
        ))
        root.addView(aboutCard, matchWrap().apply { topMargin = dp(16) })

        session.listener = object : PluginSession.Listener {
            override fun onPluginsChanged(items: List<PluginSession.Item>) {
                renderList(items)
            }

            override fun onPluginStateChanged(pkg: String, state: PluginConnection.State, detail: String?) {
                // ⚠ 这里**不弹** toast（2026-10-23 删）。
                //
                // 原来连接失败会 toast 一句结论。问题是宿主每次启动都会
                // 自动重连所有已启用插件，而插件只要处于 stopped 状态
                // （装完没激活过、或被系统清掉）就必然失败 ——
                // **每次冷启动都会弹**，装了几个插件就弹几个。
                //
                // 而那些时候用户什么都没做，弹窗只是噪音；真正需要诊断的人
                // 会在卡片上看到**完整多行原因**（下面 renderCard 里那段红字，
                // 可长按复制）。toast 一秒就没了，本来也不够用。
                //
                // 所以：失败信息只留在卡片上，不再主动弹。
            }

            override fun onSpeakStage(pkg: String, stage: Int, label: String?) {
                // 取消是走**这条路**回来的，不是 onSpeakError ——
                // PluginSession 把 CANCELLED 当"静默收尾"，只发一个 stage。
                // 这里不处理的话「试听」会一直灰着，变成新的死路。
                if (stage == Stage.CANCELLED) {
                    finishRequest()
                    setUiHint(getString(R.string.s_491))
                    return
                }
                val conn = session.connectionOf(pkg)
                val name = conn?.stageName(stage) ?: getString(R.string.s_492)
                setUiHint(if (label != null) "$name · $label" else name)
            }

            override fun onSpeakDone(pkg: String, result: com.litertchat.plugin.ResultBundle) {
                finishRequest()
                // 状态提示统一由 storeAudio 出（它知道文件到底写没写成），
                // 这里只留诊断信息
                setDiag(pkg, buildString {
                    append(getString(R.string.s_493)).append(result.sampleRate).append(" Hz / ")
                    append(result.channelCount).append(" ch\n")
                    append(getString(R.string.s_494)).append(result.durationMs).append(" ms\n")
                    append(getString(R.string.s_495)).append(result.elapsedMs).append(" ms")
                    if (result.debugInfo.isNotBlank()) {
                        append("\n— debugInfo —\n").append(result.debugInfo)
                    }
                })
                storeAudio(pkg, result)
            }

            override fun onSpeakError(pkg: String, err: PluginError) {
                // 取消不是失败：用户自己按的，静默收尾
                if (err.code == ErrCode.CANCELLED) {
                    finishRequest()
                    setUiHint(getString(R.string.s_491))
                    return
                }
                // 「引擎尚未加载」不该是死路：插件重连后引擎可能已经不在了，
                // 宿主替用户重载一次，加载完自动补上这次试听（见 onEngineReady）
                if (err.code == ErrCode.NOT_READY && pkg == openUiPkg && !previewRetryUsed) {
                    previewRetryUsed = true
                    previewRetryPkg = pkg
                    setUiHint(getString(R.string.s_496))
                    session.connectionOf(pkg)?.loadEngine()
                    return
                }
                finishRequest()
                setUiHint(getString(R.string.s_591, err.message))
                setDiag(pkg, buildString {
                    append(getString(R.string.s_497)).append(err.code).append("（").append(errName(err.code)).append("）\n")
                    append("message：").append(err.message)
                    if (err.retryable) append("\nretryable = true")
                    if (err.detail.isNotBlank()) {
                        append("\n— detail —\n").append(err.detail.take(4000))
                    }
                })
            }

            override fun onEngineReady(pkg: String, info: com.litertchat.plugin.EngineInfo) {
                // 「加载引擎」按钮 → 「卸载引擎」，并说一句加载完了
                if (openUiPkg == pkg) {
                    refreshEngineLabel(pkg)
                    currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.ENGINE, !speaking)
                    if (!speaking) {
                        setUiHint(
                            // 拆成两条资源而不是拼字符串：带上体积和不带体积
                            // 在英文里语序不同（", using 1.2 GB, took 8.0 s"），
                            // 不能靠中文的拼接顺序照搬
                            if (info.modelBytes > 0) {
                                getString(
                                    R.string.s_588,
                                    info.sampleRate,
                                    "%.1f".format(info.modelBytes / 1024.0 / 1024.0),
                                    "%.1f".format(info.loadElapsedMs / 1000.0),
                                )
                            } else {
                                getString(
                                    R.string.s_589,
                                    info.sampleRate,
                                    "%.1f".format(info.loadElapsedMs / 1000.0),
                                )
                            }
                        )
                    }
                }
                // 刚才因为"引擎未加载"失败的那次试听 —— 现在补上
                if (pkg != previewRetryPkg) return
                previewRetryPkg = null
                startPreview(pkg)
            }

            override fun onPluginSchemaChanged(pkg: String) {
                // 插件自己改了界面数据（比如模型下载完）→ 正开着它的界面就重画。
                // 没开着就忽略：下次点进去自然会重新拉一遍
                if (openUiPkg == pkg) currentUiPage?.refresh()
            }

            override fun onPluginImage(pkg: String, bytes: ByteArray, width: Int, height: Int, mime: String?) {
                handlePluginImage(pkg, null, bytes)
            }

            /** v11：插件指明了贴到哪个 IMAGE 控件，不用宿主猜 */
            override fun onPluginImageWithKey(
                pkg: String, key: String, bytes: ByteArray, width: Int, height: Int, mime: String?,
            ) {
                handlePluginImage(pkg, key, bytes)
            }

            /**
             * 两个送图回调共用的解码 + 落图逻辑。
             *
             * @param key 空 = 老协议（没指明目标），由 [PluginUiPage.setImage] 决定
             *            能不能唯一确定；非空 = 插件点名要贴那个控件
             */
            private fun handlePluginImage(pkg: String, key: String?, bytes: ByteArray) {
                if (openUiPkg != pkg) return
                val bmp = try {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (t: Throwable) {
                    android.util.Log.w("PonkoPlugin", "结果图解码失败 ${bytes.size}B", t)
                    null
                } catch (o: OutOfMemoryError) {
                    // OOM 是 Error 不是 Exception，上面的 catch 抓不到 ——
                    // 解一张超大图完全可能触发，不能让它把进程带走
                    android.util.Log.w("PonkoPlugin", "结果图太大，解码时内存不足")
                    null
                }
                if (bmp == null) {
                    setUiHint(getString(R.string.s_498))
                    return
                }
                // 声明里的宽高只是提示；以实际解码结果为准 ——
                // 插件算错尺寸比不传更常见，信它反而会画出变形的图
                currentUiPage?.setImage(bmp, key)
                setUiHint(getString(R.string.s_592, bmp.width, bmp.height))
            }

            /** v12：插件要求滚到某个控件。界面没开着就丢（和 selectTab 同理） */
            override fun onPluginScrollTo(pkg: String, key: String) {
                if (openUiPkg != pkg) return
                currentUiPage?.scrollTo(key)
            }

            override fun onPluginUiPatch(pkg: String, key: String, value: String?, append: Boolean) {
                if (openUiPkg != pkg) return
                currentUiPage?.patch(key, value, append)
            }

            /**
             * v7：插件请求"用当前参数合成一次"。
             *
             * 走的和用户点宿主那个「合成」按钮**完全同一条路**（`testSpeak`
             * → `startPreview`），所以参数校验、参考音频 FD、进度回报全都一致 ——
             * 没有第二条合成路径，也就没有第二套 bug。
             *
             * 页面没开着时直接丢：那时根本不存在"当前参数"（那是这一页上填的），
             * 用上一次的缓存值合成是对用户意图的臆测。
             */
            override fun onPluginSynthesisRequested(pkg: String) {
                if (openUiPkg != pkg || currentUiPage == null) {
                    android.util.Log.i("PonkoPluginPage", "[$pkg] 请求合成，但它的界面没开着，已忽略")
                    return
                }
                if (speaking) {
                    // 不排队：用户连点两下不该跑两遍一分钟的推理
                    setUiHint(getString(R.string.s_499))
                    return
                }
                testSpeak(pkg, openUiIsTts)
            }

            /** v7：插件给某条播放器送了自己那份音频 */
            override fun onPluginAudioFile(pkg: String, key: String, bytes: ByteArray, displayName: String?) {
                acceptPluginAudio(pkg, key, bytes, displayName)
            }

            /**
             * v8：插件要求切到某一页（"推理完成 → 跳结果页"）。
             *
             * 界面没开着时直接丢 —— 那种"过去的意图"没有意义，
             * 等用户下次进来再自动跳过去会显得界面自己在乱动。
             */
            override fun onPluginSelectTab(pkg: String, groupKey: String, tabKey: String) {
                if (openUiPkg != pkg || currentUiPage == null) {
                    android.util.Log.i("PonkoPluginPage", "[$pkg] selectTab 时界面没开着，已忽略")
                    return
                }
                currentUiPage?.selectTab(groupKey, tabKey)
            }
        }

        refresh()
        // 自带滚动：这一页可能比屏幕高（插件多了以后），
        // 而底部菜单的内容区是 FrameLayout，不会自动滚
        val listScroll = android.widget.ScrollView(act).apply {
            setBackgroundColor(pal.bg)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listLayer = listScroll

        // 外层容器：列表和插件界面叠在一起，靠 visibility 切换。
        // 不用新 Activity 的理由：主题/配色/标题栏会自动一致，
        // 也不用在 Manifest 里多声明一个 Activity。
        val container = android.widget.FrameLayout(act).apply {
            setBackgroundColor(pal.bg)
            addView(listScroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        pageRoot = container
        return container
    }

    /** 当前是否正显示某个插件的界面（给 MainActivity 的返回键用） */
    fun isShowingPluginUi(): Boolean = currentUiPage != null

    /** 返回列表。给 MainActivity 的返回键用 */
    fun backToList() = closePluginUi()

    /**
     * 按插件 manifest 里的 `ponko.actions` 白名单过滤功能按钮。
     *
     * ## 为什么要有这个
     *
     * 宿主固定画五个按钮，插件没有否决权。插件唯一能动的开关是
     * `AUTOLOAD_ENGINE`（让「加载模型」消失），但代价是一连接就加载 3.3 GB 权重 ——
     * 完全不能用。于是"我只想要合成和停止"这种合理诉求没有出口。
     *
     * ## 三种情况
     *
     * | 白名单 | 结果 |
     * |---|---|
     * | `null`（没声明） | **原样返回**，老插件显示完全不变 |
     * | 空列表 | 一个按钮都不给（插件明确要求） |
     * | 非空 | 只保留列表里的；列表写错/写了不存在的 id 会打日志 |
     *
     * ## 为什么不过滤成"插件说啥就是啥"
     *
     * 白名单只能**减少**按钮，不能凭空造出插件本来没有的动作。
     * 比如插件没有 `canPreviewAudio`（纯配置型插件），`actions` 里本来就没有
     * 合成/播放/保存 —— 这时白名单写 `"reasoning"` 也不会变出一个来。
     * 过滤是在"宿主已经决定要给的按钮"之上做减法，这是安全的。
     */
    private fun applyActionWhitelist(
        pkg: String,
        actions: List<PluginUiPage.HostAction>,
        whitelist: List<String>?,
    ): List<PluginUiPage.HostAction> {
        if (whitelist == null) return actions

        if (whitelist.isEmpty()) {
            android.util.Log.i("PonkoPluginPage", "[$pkg] ponko.actions 为空，不显示任何功能按钮")
            return emptyList()
        }

        val known = actions.map { it.id }.toSet()
        val unknown = whitelist.filter { it !in known }
        if (unknown.isNotEmpty()) {
            // 两种情况都常见：id 拼错，或者要了一个这个插件本来没有的动作
            // （比如纯配置型插件要 reasoning）。都只打日志，不报错 ——
            // 界面上少一个按钮比弹一个看不懂的错误好
            android.util.Log.w(
                "PonkoPluginPage",
                "[$pkg] ponko.actions 里这些动作不存在，已忽略：$unknown" +
                    "（该插件可用：$known）"
            )
        }

        val kept = actions.filter { it.id in whitelist }
        android.util.Log.i(
            "PonkoPluginPage",
            "[$pkg] ponko.actions 过滤功能按钮：$known → ${kept.map { it.id }}"
        )
        return kept
    }

    /**
     * 打开某个插件的界面。
     *
     * 内容由插件给（[com.litertchat.plugin.UiSchema]），样式由 [PluginUiPage] 渲染。
     * 插件没提供任何项时就是一个空页面 —— 这是合法状态，不是错误。
     */
    private fun openPluginUi(item: PluginSession.Item) {
        // 先把列表收到最外层，再叠上插件界面
        val root = pageRoot ?: return
        closePluginUi()

        val pkg = item.found.packageName
        val name = item.manifest?.displayName ?: item.found.label

        // 功能按钮由**宿主**提供：插件只声明"能做什么"（能力），
        // 怎么触发（按钮文字、放哪儿、状态显示在哪儿）是宿主的职责。
        //
        // 按钮分工（**一个动作一个按钮**）：
        //   · 加载 / 卸载 —— 引擎生命周期（和 Ponko 模型页的 toggleLoad 一个思路）
        //   · 合成 —— 只跑推理、产出文件，不播放
        //   · 播放 —— 只重放那个文件，不碰推理
        //   · 停止 —— 调 session.stopSpeaking()，插件那边是真打断（每块都查取消标志）
        //   · 保存 —— 把刚生成的音频复制到「音乐/Ponko」，否则它只能听一次
        val isTts = item.canSpeak
        openUiIsTts = isTts
        val canPreview = item.canPreviewAudio
        // 插件自己声明了"连上就加载"的话，加载按钮没有意义（已经加载好了，
        // 而且用户也没必要手动卸了再装）。所以只在**没声明**时才给这个按钮。
        val autoLoaded = item.manifest?.capabilities
            ?.contains(Capability.AUTOLOAD_ENGINE) == true
        val engineUp = session.engineReady(pkg)
        // 按钮文字一律走 session.actionLabel(code)：插件可以覆盖（v3 起），
        // 没覆盖就是宿主默认。**不要在下面直接写中文字面量** ——
        // 写死之后，同一个按钮在不同插件下就没法叫不同名字了。
        val lEngine = session.actionLabel(
            pkg,
            if (engineUp) com.litertchat.plugin.ActionLabel.ENGINE_UNLOAD
            else com.litertchat.plugin.ActionLabel.ENGINE_LOAD,
        )
        val actionLabels = com.litertchat.plugin.ActionLabel
        val actions = buildList {
            if (canPreview) {
                if (!autoLoaded) {
                    add(
                        PluginUiPage.HostAction(
                            id = com.litertchat.plugin.HostActionId.ENGINE,
                            label = lEngine,
                            enabled = !speaking,
                            run = { toggleEngine(pkg) },
                        )
                    )
                }
                add(
                    PluginUiPage.HostAction(
                        id = com.litertchat.plugin.HostActionId.REASONING,
                        // 名字固定，**不随状态变**。
                        // 曾经有过「试听 → 重新合成」的改名：那时合成完会自动播，
                        // 同一个按钮在"还没听过"和"听过了"两种状态下含义不同。
                        // 现在只合成不播放，点几次都是同一个动作，改名就没有理由了。
                        label = session.actionLabel(pkg, actionLabels.REASONING),
                        help = if (item.isTest)
                            getString(R.string.s_500)
                        else
                            getString(R.string.s_501) +
                                getString(R.string.s_502),
                        enabled = !speaking,
                        run = { testSpeak(pkg, isTts) },
                    )
                )
                // 「播放」只重放上一次的结果，不重新推理。
                //
                // 为什么必须有：以前合成完就自动播，想再听一遍只能再等 1~2 分钟。
                // 现在合成和播放是两个独立动作 —— 合成只管产出文件，听它点这里。
                add(
                    PluginUiPage.HostAction(
                        id = com.litertchat.plugin.HostActionId.PLAY,
                        label = session.actionLabel(pkg, actionLabels.PLAY),
                        enabled = lastAudioPkg == pkg && lastAudioFile?.exists() == true,
                        run = { lastAudioFile?.let { playFile(it) } },
                    )
                )
                add(
                    PluginUiPage.HostAction(
                        id = com.litertchat.plugin.HostActionId.STOP,
                        label = session.actionLabel(pkg, actionLabels.STOP),
                        enabled = speaking,
                        run = { stopPreview() },
                    )
                )
                add(
                    PluginUiPage.HostAction(
                        id = com.litertchat.plugin.HostActionId.SAVE,
                        label = session.actionLabel(pkg, actionLabels.SAVE),
                        enabled = lastAudioPkg == pkg && lastAudioFile?.exists() == true,
                        run = { saveAudio() },
                    )
                )
            }
        }

        val page = PluginUiPage(
            act, pal, pkg, name, session,
            // 白名单在这里过滤：插件 manifest 的 `ponko.actions` 决定顶部那排
            // 显示哪几个。没声明（null）时原样返回，老插件不受影响
            applyActionWhitelist(pkg, actions, item.found.actionWhitelist),
            hostExtra = { buildPreviewParams(pkg, isTts) },
        ) {
            closePluginUi()
        }
        // 播放条要能控制**宿主**的播放器（插件在别的进程，它管不到声音）
        page.audioHost = audioHostFor(pkg)
        // v7：每条播放器自己那个「保存」。插件不送文件的话这条仍能用 ——
        // 存的就是宿主那份最近产物
        page.audioSaver = { key -> saveAudioOf(pkg, key) }
        // 点结果图全屏看 —— 复用宿主已有的缩放视图，别写第二套手势
        page.zoomable = { bmp -> onZoomImage?.invoke(bmp) }
        // v6：模型槽位的两个动作。选择器必须由**宿主 Activity** 弹 ——
        // 插件是 bound Service，自己没有 startActivityForResult，
        // 而且 Android 10+ 禁止后台启动 Activity
        page.modelPicker = { role -> pickModelForPlugin(pkg, role) }
        page.modelDeleter = { role -> deleteModelOf(pkg, role) }
        val v = page.build()
        currentUiPage = page
        openUiPkg = pkg
        // 把已有状态写回去（比如从列表切进来时上一次的"播放完毕"）
        if (speakHintText.isNotEmpty()) page.setActionHint(speakHintText)
        diagCache[pkg]?.let { page.setDiagnostics(it) }
        root.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        listLayer?.visibility = View.GONE
        // 标题左边那个 ◀ 跟着出现 —— 插件界面的正文可能很长，
        // 内部那个「‹ 返回」会滚出屏幕，这个不会
        backToPluginList?.visibility = View.VISIBLE
    }

    private fun closePluginUi() {
        val root = pageRoot ?: return
        // 输入框里的内容可能还没落盘（边打字边写会在输入法预测时刷很多次，
        // 所以只在特定时机存），这里补一次
        openUiPkg?.let { pkg -> paramsCache[pkg]?.let { PreviewParams.save(act, pkg, it) } }
        val page = currentUiPage
        if (page != null) {
            val v = root.getChildAt(root.childCount - 1)
            if (v != null && v !== listLayer) root.removeView(v)
        }
        currentUiPage = null
        openUiPkg = null
        refLabel = null
        // 这个引用指向即将被移除的视图，留着只会让 focusSpeakText 摸到死控件
        speakTextInput = null
        listLayer?.visibility = View.VISIBLE
        // 回到列表了，◀ 没有去处（这就是 Ponko-EX 自己的页面），收起来
        backToPluginList?.visibility = View.GONE
    }

    /** Activity 真正销毁时调，解绑所有插件。切换 tab 时**不要**调（切回来要保留状态） */
    fun release() {        // 先停播放：不停的话页面走了声音还在响
        // 播放条的 200ms 轮询也要停 —— 它持着 handler 和 view，
        // 不停的话每次 release 都漏一个 Runnable 和一堆视图
        currentUiPage?.stopAudioTicker()
        runCatching { player?.release() }
        player = null
        // 每条播放器自己的播放器也要放 —— 只放共享那个的话，
        // 正在播的历史条目会在退出这一页之后继续响
        keyPlayers.values.forEach { runCatching { it.release() } }
        keyPlayers.clear()
        keyPlayerFiles.clear()
        keyAudioFiles.clear()
        keyDurations.clear()
        runCatching { refFd?.close() }
        refFd = null
        session.release()
        listHost = null
        speakHintText = ""
        expandedMap.clear()
        paramsCache.clear()
        diagCache.clear()
        lastAudioFile = null
        lastAudioPkg = null
        speaking = false
        refLabel = null
        pendingRefPkg = null
        currentUiPage = null
        openUiPkg = null
        listLayer = null
        pageRoot = null
        // 视图要销毁了，引用留着会指向死控件（下一次 build() 会重新赋值）
        backToPluginList = null
    }

    // ==================== 内部 ====================

    /** 外部触发重扫（切到本 tab 时由 MainActivity 调，用户可能刚装完插件回来） */
    fun refreshNow() {
        // build() 还没跑过时 listHost 为 null，refresh 里的渲染会安全跳过，
        // 但 session.refresh 仍会尝试连接已启用的插件 —— 这是期望行为
        refresh()
    }

    private fun refresh() {
        session.refresh()
        renderList(session.items())
    }

    private fun renderList(items: List<PluginSession.Item>) {
        // 记住完整列表（未过滤），输入时从这里筛，不用重新扫描
        allItems = items
        applyFilter()
    }

    /**
     * 按 [searchQuery] 过滤并重画列表。
     *
     * ## 匹配什么
     *
     * 显示名（`label`）、包名、插件自报的名字（`manifest.displayName`）——
     * 三者任一包含查询串即命中，不区分大小写。
     *
     * 不匹配 `about` 全文和 `detail`（诊断信息）：那些可能很长，
     * 搜"model"会命中一堆无关插件，看起来像搜索坏了。
     *
     * ## 为什么每次输入都整片重画
     *
     * 卡片是现建的（`itemCard`），没有稳定 id 也没有 view holder。
     * 插件数量是个位数，重建开销远小于维护"增量更新"的复杂度。
     * 折叠状态存在 [expandedMap] 里（按包名），所以重画不会把用户
     * 展开过的卡片收起来。
     */
    private fun applyFilter() {
        val host = listHost ?: return
        host.removeAllViews()

        val q = searchQuery.trim()
        val shown = if (q.isEmpty()) allItems else allItems.filter { matches(it, q) }

        if (shown.isEmpty()) {
            host.addView(
                if (q.isEmpty()) {
                    emptyCard(
                        getString(R.string.s_503),
                        getString(R.string.s_617) +
                            getString(R.string.s_618) +
                            getString(R.string.s_506)
                    )
                } else {
                    // 有查询却说"没有找到插件"会让人以为扫描坏了，
                    // 必须说清是**这个搜索词**没结果，并且告诉他一共有几个
                    emptyCard(
                        getString(R.string.s_593, q),
                        // 总插件数作为参数传进去，不拼字符串 ——
                        // 中文是"已装的 N 个插件里…"，英文语序是 "None of the N …"
                        getString(R.string.s_594, allItems.size)
                    )
                }
            )
            return
        }

        shown.forEach { item ->
            host.addView(itemCard(item), matchWrap().apply { topMargin = dp(8) })
        }
    }

    /** 一个插件是否命中查询串。三者任一包含即算命中 */
    private fun matches(item: PluginSession.Item, q: String): Boolean {
        val needle = q.lowercase()
        fun hit(s: String?) = s != null && s.lowercase().contains(needle)
        return hit(item.found.label) ||
            hit(item.found.packageName) ||
            hit(item.manifest?.displayName)
    }

    private fun itemCard(item: PluginSession.Item): View {
        val pkg = item.found.packageName
        // 折叠状态存在内存里：切 tab、重新扫描都不会丢。
        // 默认展开 —— 新用户第一次进来必须能直接看到「启用」按钮，
        // 默认收起的话他只会看到一行名字，不知道要干什么。
        val expandedNow = expandedMap[pkg] ?: true

        // 卡片本体就是普通 LinearLayout，左侧色条**画在背景里**。
        //
        // ⚠ 这里踩过一个坑，别再改回去：
        // 之前用 FrameLayout + 一个 4dp 宽、height=MATCH_PARENT 的 View 当色条。
        // 结果卡片被撑到**满屏高**、而且永远收不起来 ——
        // 因为 `View.getDefaultSize()` 在 AT_MOST 模式下返回的是 **specSize**，
        // 也就是父容器给的整个可用高度。wrap_content 的 FrameLayout 传的正是
        // AT_MOST(可用高度)，于是那条"色条"变成了满屏高，把卡片顶开了。
        //
        // 改成背景绘制之后，色条高度天然等于视图高度，和布局测量完全无关。
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            // 左边留 4dp 给色条 + 14dp 内边距
            setPadding(dp(18), 0, dp(14), 0)
            background = cardBackground(statusColor(item))
        }

        // ---- 展开区（收起时整块 GONE，只留上面那行名字）----
        // 先创建、暂不 addView：head 的点击闭包要引用它改 visibility，
        // 所以它必须先于 head 出现在作用域里；但**显示顺序**要排在 head 后面。
        val detail = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            // 底部留白放这里：收起时它是 GONE，不占高度
            setPadding(0, 0, 0, dp(11))
            visibility = if (expandedNow) View.VISIBLE else View.GONE
        }

        // ---- 标题行（整行可点，切换展开/收起）----
        val arrow = TextView(act).apply {
            text = if (expandedNow) "▾" else "▸"
            textSize = 13f
            setTextColor(pal.subText)
            setPadding(dp(6), 0, 0, 0)
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 收起时整张卡片就只剩这一行，所以垂直留白全在这里：
            // 9dp + 单行文字(~19dp) + 9dp ≈ 37dp，是个紧凑但还算好点的行高
            setPadding(0, dp(9), 0, dp(9))
            isClickable = true
            setOnClickListener {
                val wasOpen = detail.visibility == View.VISIBLE
                expandedMap[pkg] = !wasOpen
                detail.visibility = if (wasOpen) View.GONE else View.VISIBLE
                arrow.text = if (wasOpen) "▸" else "▾"
            }
        }
        head.addView(TextView(act).apply {
            text = item.manifest?.displayName ?: item.found.label
            textSize = 14f
            setTextColor(pal.text)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(act).apply {
            text = "v${item.manifest?.versionName ?: item.found.versionName}"
            textSize = 12f
            setTextColor(pal.subText)
        })
        head.addView(arrow, wrapWrap())
        card.addView(head, matchWrap())
        card.addView(detail, matchWrap())
        // 状态行
        val status = statusText(item)
        detail.addView(TextView(act).apply {
            text = status.first
            textSize = 12f
            setTextColor(status.second)
            setPadding(0, dp(4), 0, 0)
        })

        // 失败原因（多行，可复制给作者）。放在卡片里而不是只弹 toast ——
        // toast 一秒钟就没了，而这种信息往往要对照着一步步排查
        if (item.state == PluginConnection.State.FAILED && !item.detail.isNullOrBlank()) {
            detail.addView(TextView(act).apply {
                text = item.detail
                textSize = 11f
                setTextColor(pal.err)
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
                // 长按可全选，方便把诊断贴出来
                isLongClickable = true
                setTextIsSelectable(true)
            })
        }

        // 能力行
        val caps = item.manifest?.capabilities
        if (!caps.isNullOrEmpty()) {
            val capText = caps.joinToString(" · ") { cap ->
                when (cap) {
                    Capability.TTS -> getString(R.string.s_508)
                    Capability.ASR -> getString(R.string.s_509)
                    Capability.IMG -> getString(R.string.s_510)
                    Capability.TEST -> getString(R.string.s_511)
                    else -> cap
                }
            }
            detail.addView(TextView(act).apply {
                text = getString(R.string.s_595, capText)
                textSize = 12f
                setTextColor(pal.subText)
                setPadding(0, dp(2), 0, 0)
            })
        }

        // 签名指纹（让用户能看出「是不是同一个人做的」）
        if (item.found.signerShort.isNotEmpty()) {
            detail.addView(TextView(act).apply {
                text = getString(R.string.s_596, item.found.signerShort)
                textSize = 11f
                setTextColor(pal.hint)
                setPadding(0, dp(2), 0, 0)
            })
        }

        // 插件偏旧：说清「不影响用、只是少功能」。
        // 不说的话用户只会觉得"为什么别人的插件有界面我没有"
        if (item.isOlderSdk) {
            detail.addView(TextView(act).apply {
                // 版本号和后半句合成一条资源：英文语序不同（"current v12" 在括号里），
                // 靠两段拼接会让英文读起来是散的
                text = getString(
                    R.string.s_599, item.apiVersion,
                    com.litertchat.plugin.PluginConst.API_VERSION
                )
                textSize = 11f
                setTextColor(pal.warn)
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        // 插件比宿主新：同样「不影响用」，但**方向相反** —— 要更新的是 Ponko。
        // 话术必须和上面那条区分开：写成"不兼容"会让用户去重装一个
        // 已经是最新的插件，然后发现怎么都没用。
        if (item.newerThanHost) {
            detail.addView(TextView(act).apply {
                text = getString(R.string.s_620, item.apiVersion, com.litertchat.plugin.PluginConst.API_VERSION)
                textSize = 11f
                setTextColor(pal.warn)
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }

        // ---- 按钮行：启用|停用 · 激活 · 进入 · 关于 · 卸载 ----
        //
        // 「启用」与「停用」互斥，按当前状态二选一；「激活」「进入」「关于」「卸载」常驻。
        // 每张卡片的按钮集合因此永远可预期，用户不用先"找按钮在哪"。
        //
        // 一行排五个：正常字号下实测约 270dp，卡片内宽约 296dp（360dp 屏），
        // **已经接近上限**。再造按钮就得换行或收进菜单了 ——
        // 极窄屏 + 系统大字号时会挤，但按钮都是 wrap_content，
        // 挤的后果是文字换行，不会错位或截断按钮 —— 可接受。
        val actions = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        if (item.enabled) {
            actions.addView(smallButton(getString(R.string.s_514)) {
                session.setEnabled(item.found.packageName, false)
                refresh()
            })
        } else {
            actions.addView(smallButton(getString(R.string.s_515)) {
                session.setEnabled(item.found.packageName, true)
                // 如果刚才是因为 stopped 而自动拉起了插件的激活页，说一句 ——
                // 否则用户只看到屏幕闪了一下、状态没变，会以为没反应
                if (session.consumePendingActivation() != null) {
                    toast(getString(R.string.s_516))
                }
                refresh()
            })
        }

        // 「激活」：清系统的 stopped 标记。
        //
        // 新装的包处于 stopped 状态，别的应用 bind 不到它的服务，bindService
        // 直接返回 false。解除方式只有「包自己的 Activity 被启动一次」。
        // 点「启用」时已经自动做过一次，这里是**手动补救入口**：
        // 被系统强停过、或自动激活被 ROM 拦掉时，用户还能自己点一下。
        actions.addView(smallButton(getString(R.string.s_517)) { activatePlugin(item) },
            wrapWrap().apply { leftMargin = dp(8) })

        // 「进入」：进插件的界面（配置项 + 宿主提供的功能按钮）。
        // 「试听」不是卡片上的按钮 —— 那是**功能**，在插件界面里。
        actions.addView(smallButton(getString(R.string.s_518)) { enterPlugin(item) },
            wrapWrap().apply { leftMargin = dp(8) })

        // 「关于」：弹窗显示插件自己写的关于文档（纯文本，可复制）。
        //
        // 位置在「卸载」**左边**：卸载是这一行里唯一的破坏性按钮，
        // 按惯例钉在最右（离拇指最远、也和其它按钮拉开视觉距离）。
        // 关于是只读的信息入口，放在它前面，不会和卸载挨着误点。
        //
        // 内容来自插件的 manifest（`ponko.about`，见 PluginDiscovery.aboutOf），
        // **不走 AIDL** —— 插件连不上（stopped / 失败）时也必须能看，
        // 而那正是用户最想翻"这插件是什么"的时候。
        actions.addView(smallButton(getString(R.string.s_519)) { showPluginAbout(item) },
            wrapWrap().apply { leftMargin = dp(8) })

        // 「卸载」：跳系统的应用信息页，由用户在那边点「卸载」。
        //
        // 用**红字幽灵按钮**（透明底 + 细红边）而不是实心主色块：
        // 同一个行里必须能一眼看出"这个和前面三个不是一类"。
        //
        // ⚠ 这一步本身并不会卸载任何东西 —— 只是打开设置页，
        //   系统那边还要用户自己确认一次。所以它和「进入」挨着放是安全的，
        //   误点的代价仅是多看一页设置。
        actions.addView(dangerButton(getString(R.string.s_520)) { openSystemAppInfo(item) },
            wrapWrap().apply { leftMargin = dp(8) })

        detail.addView(actions)

        // 处于 stopped / 连接失败时补一句说明：
        // 红色状态行只说"出了什么问题"，不说"该点哪个键"。
        // 这句话只解释，不额外加按钮 —— 按钮就是上面那个「激活」。
        if (item.found.hasLauncher &&
            (item.found.stopped || item.state == PluginConnection.State.FAILED)
        ) {
            detail.addView(TextView(act).apply {
                text = getString(R.string.s_521) +
                    getString(R.string.s_522)
                textSize = 11f
                setTextColor(pal.warn)
                setPadding(0, dp(8), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }

        return card
    }

    /** 点「激活」：拉起插件的激活组件，让系统清掉 stopped 标记 */
    private fun activatePlugin(item: PluginSession.Item) {
        val pkg = item.found.packageName
        if (!item.found.hasLauncher) {
            // 没有可启动的组件就没法清——只能靠用户卸载重装
            toast(getString(R.string.s_523))
            return
        }
        if (session.openPluginOnce(pkg)) {
            toast(getString(R.string.s_524))
        } else {
            toast(getString(R.string.s_525))
        }
    }

    /** 点「进入」：进插件界面。相关前置条件在这里一次性说清 */
    private fun enterPlugin(item: PluginSession.Item) {
        when {
            !item.enabled -> toast(getString(R.string.s_526))
            !item.supportsSchema -> toast(getString(R.string.s_527))
            else -> openPluginUi(item)
        }
    }

    /**
     * 卸载插件：**直接跳到系统的「应用信息」页**，由用户在那里点「卸载」。
     *
     * ## 为什么卡片上这个按钮是红字幽灵样式
     *
     * 卡片上一行里排着 启用|停用 / 激活 / 进入 / 卸载 四个按钮，
     * 卸载是唯一不可逆的那个，必须能在扫一眼时就被区分出来 ——
     * 所以它不跟前面几个共用实心主色块，而是透明底 + 细红边 + 红字。
     *
     * 至于误点：**这一步本身不卸载任何东西**，只是打开系统的应用信息页，
     * 真正卸载要用户在那里再点一次「卸载」并确认。所以挨着放是安全的。
     *
     * ## 为什么不在 App 里自己弹确认框
     *
     * 试过两版（自带 AlertDialog + `ACTION_DELETE`），在真机上都是"点了没反应"。
     * 与其继续猜是弹框主题的问题还是系统卸载界面被 ROM 裁了，
     * 不如换成**一条最不容易失效的路**：
     *
     * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 是系统「设置 → 应用 → 某应用」
     * 那一页的入口 —— 就是用户能点「强制停止 / 卸载」的界面。它：
     * · 所有 Android 设备都有，不存在被裁掉的情况
     * · 卸载和二次确认都由系统负责，我们不用管
     * · 和"跳系统设置"是同一类调用，不依赖对话框主题
     *
     * ## 不先停用插件
     *
     * 用户在系统页面可能不卸载就返回。那时如果已经把它停用了，白丢状态。
     * 真的卸载了的话，回来 `onResume → refresh()` 会发现包没了并自动清理。
     */
    private fun openSystemAppInfo(item: PluginSession.Item) {
        val pkg = item.found.packageName
        val name = item.manifest?.displayName ?: item.found.label

        // 先把"去哪里、点什么"说清楚 —— 系统页是通用界面，
        // 不说的话用户不知道该在那儿做什么
        toast(getString(R.string.s_597, name))

        val details = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg")
        )

        // 优先用包名定位到这一页。个别 ROM 只认不开 package 的通用入口，
        // 那样会落到"应用列表"，仍然能手动找到 —— 比什么都不发生好
        if (!startSafely(details)) {
            val list = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_SETTINGS
            )
            if (!startSafely(list)) {
                toast(getString(R.string.s_528))
                return
            }
            toast(getString(R.string.s_598, name))
        }
        // 回来后重扫：真卸载了的话条目会消失
        refresh()
    }

    /** 启动 Activity，失败时返回 false 并记日志（不静默吞掉） */
    private fun startSafely(i: android.content.Intent): Boolean = try {
        act.startActivity(i)
        true
    } catch (t: Throwable) {
        android.util.Log.w("PonkoPluginPage", "startActivity 失败：$i", t)
        false
    }

    /**
     * 左侧色条的颜色。**这是这页最主要的状态信号** ——
     * 收起状态只剩名字，用户靠这个颜色判断"要不要点开看看"。
     */
    private fun statusColor(item: PluginSession.Item): Int = when {
        !item.found.installed -> pal.err          // 包被系统停用
        !item.found.resolvable -> pal.err         // 服务组件被禁
        item.state == PluginConnection.State.FAILED -> pal.err
        item.state == PluginConnection.State.BINDING -> pal.warn
        // 连上就算正常。不能只看 canSpeak —— 测试插件没有 TTS 能力但它是好的，
        // 判成 warn 会让用户以为它有问题
        item.state == PluginConnection.State.CONNECTED -> pal.ok
        item.found.stopped -> pal.warn            // 装完没启动过
        item.enabled -> pal.warn                  // 启用了但还没连上
        else -> pal.hint                          // 未启用：中性灰
    }

    private fun statusText(item: PluginSession.Item): Pair<String, Int> = when {
        !item.found.installed -> getString(R.string.s_529) to pal.err
        !item.found.resolvable -> getString(R.string.s_530) to pal.err
        item.state == PluginConnection.State.FAILED -> {
            val head = item.detail?.lineSequence()?.firstOrNull { it.isNotBlank() }
            (getString(R.string.s_531) + (head ?: getString(R.string.s_532))) to pal.err
        }
        item.state == PluginConnection.State.BINDING -> getString(R.string.s_533) to pal.warn
        item.enabled && item.state == PluginConnection.State.CONNECTED -> {
            // 三种"连上了"要分开说，否则测试插件会被说成"没有语音合成能力"，
            // 听起来像坏了
            val text = when {
                item.isTest -> getString(R.string.s_534)
                item.canSpeak -> getString(R.string.s_535)
                else -> getString(R.string.s_536)
            }
            text to (if (item.isTest || item.canSpeak) pal.ok else pal.warn)
        }
        // 装好后从没启动过：还没点启用时也先提醒，省得点完才报错
        item.found.stopped -> getString(R.string.s_537) to pal.warn
        item.enabled -> getString(R.string.s_538) to pal.warn
        else -> getString(R.string.s_539) to pal.subText
    }

    /**
     * 加载 / 卸载引擎（手动路径）。
     *
     * 只有插件**没**声明 [Capability.AUTOLOAD_ENGINE] 时才有这个按钮 ——
     * 声明了的连上就加载好了，不需要用户操心。
     *
     * 文案切换靠 [PluginSession.engineReady]，它在点击前后各问一次插件。
     * 插件那边 `closeEngine` 是异步的（走 worker），所以卸载后稍微延迟一点再问，
     * 否则读到的还是"未启用"，按钮会白闪一下。
     */
    private fun toggleEngine(pkg: String) {
        val up = session.engineReady(pkg)
        if (up) {
            // 提示里说的事就是"那个按钮正在做的事"，所以用按钮自己的名字，
            // 不另写一套说法 —— 插件把按钮叫「释放模型」时，
            // 这里还说"正在卸载引擎"会让人以为是两件事
            setUiHint(
                getString(R.string.s_601, session.actionLabel(pkg, com.litertchat.plugin.ActionLabel.ENGINE_UNLOAD))
            )
            // 卸载期间先禁掉按钮：避免用户连点出「卸载 → 又加载」
            currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.ENGINE, false)
            session.toggleEngine(pkg, load = false)
            awaitEngineUnloaded(pkg)
        } else {
            val p = params(pkg)
            setUiHint(
                getString(R.string.s_602, session.actionLabel(pkg, com.litertchat.plugin.ActionLabel.ENGINE_LOAD)) +
                    getString(R.string.s_621, if (p.useGpu) "GPU" else "CPU",
                        if (p.threads == 0) getString(R.string.s_622) else getString(R.string.s_605, p.threads))
            )
            session.toggleEngine(pkg, load = true, threads = p.threads, useGpu = p.useGpu)
        }
        // 加载成功走 onEngineReady 回调（那里会刷新文案与诊断区）
    }

    /**
     * 等引擎真的变成 `UNLOADED`，然后复位按钮和文案。
     *
     * ## 为什么不能只查一次（这条被真机反馈打回过）
     *
     * 原来的写法是 `postDelayed(250) { 查一次 }`。这个"一次"会落在三种情况之一上，
     * 每一种都可能还没卸完：
     *
     * | 情况 | 实际耗时 |
     * |---|---|
     * | 空闲，但模型几 GB，free 本身慢 | 几百毫秒 |
     * | 正在推理，要等插件在耗时循环里查 cancelFlag 再逐块退出 | 几秒 |
     * | 插件进程被系统冻结过 | 不确定 |
     *
     * 抽查落空 → 显示「引擎仍在卸载中…」→ **而此后没有任何时机再刷新按钮**
     * （只有这里和 `onEngineReady` 会调 `refreshEngineLabel`）。
     * 用户再点一次时 `engineReady` 已经是 false（其实早就卸完了），
     * 于是走 else 分支 → **反而把模型重新加载回来**。
     *
     * ## 所以改成轮询到状态真的变
     *
     * AIDL 里本来就有 `getStatus()`（同步、O(1)），只是宿主从来没调过。
     * 现在每隔 [ENGINE_POLL_MS] 问一次，直到 `UNLOADED` 或超时。
     * 轮询期间按钮保持禁用，用户点不动，也就不会误触发。
     */
    private fun awaitEngineUnloaded(pkg: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + ENGINE_POLL_TIMEOUT_MS
        val probe = object : Runnable {
            override fun run() {
                // 页面已经切走/销毁 → 不再刷 UI，省掉无意义的 Binder 调用
                if (openUiPkg != pkg) return
                val st = session.engineState(pkg)
                val done = st == com.litertchat.plugin.EngineState.UNLOADED
                if (!done && android.os.SystemClock.elapsedRealtime() < deadline) {
                    act.window.decorView.postDelayed(this, ENGINE_POLL_MS)
                    return
                }
                currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.ENGINE, !speaking)
                refreshEngineLabel(pkg)
                setUiHint(
                    if (done) getString(R.string.s_540)
                    // 超时不是失败，只是慢：说清楚还能做什么，别让用户卡在疑惑里
                    else getString(R.string.s_603, ENGINE_POLL_TIMEOUT_MS / 1000)
                )
            }
        }
        act.window.decorView.postDelayed(probe, ENGINE_POLL_MS)
    }

    /** 把「加载引擎 / 卸载引擎」的文案刷成当前真实状态 */
    private fun refreshEngineLabel(pkg: String) {
        if (openUiPkg != pkg) return
        currentUiPage?.setActionLabel(
            "engine",
            session.actionLabel(
                pkg,
                if (session.engineReady(pkg)) com.litertchat.plugin.ActionLabel.ENGINE_UNLOAD
                else com.litertchat.plugin.ActionLabel.ENGINE_LOAD,
            )
        )
    }

    /**
     * 跑一次合成（**指定插件**，不再是"谁有 TTS 就用谁"）。
     *
     * 用户站在某个插件的界面里点「合成」，就该用那个插件。
     * 测试插件也允许 —— 对它这是诊断整条音频链路的动作。
     */
    private fun testSpeak(pkg: String, isTts: Boolean) {
        // 每次用户主动点，都重新给一次补救机会（但也只给一次）
        previewRetryUsed = false
        previewRetryPkg = null
        openUiIsTts = isTts
        startPreview(pkg)
    }

    /**
     * 真正发起一次合成。用户点击、以及"引擎重载完自动补发"都走这里。
     *
     * 参数从 [PreviewParams] 取（界面上填的那份），不再是写死的一句话。
     */
    private fun startPreview(pkg: String) {
        val p = params(pkg)
        if (p.text.isBlank()) {
            // 只说"不能为空"是不够的：用户不一定知道那个框在哪（插件自己的
            // schema 可能很长，文本区被挤到很上面）。直接把焦点给过去 ——
            // 输入框拿到焦点会自动滚进视野，而且能立刻开始打字
            setUiHint(getString(R.string.s_541))
            focusSpeakText()
            return
        }
        val tts = openUiIsTts
        if (tts && p.needsTranscript && p.transcript.isBlank()) {
            setUiHint(getString(R.string.s_542))
            return
        }
        if (tts && p.needsReference && !p.hasReference) {
            setUiHint(getString(R.string.s_543))
            return
        }

        var fd: android.os.ParcelFileDescriptor? = null
        if (tts && p.needsReference) {
            fd = openReference(p)
            if (fd == null) {
                setUiHint(getString(R.string.s_544))
                return
            }
        }

        closeRefFd()
        refFd = fd
        speaking = true
        currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.REASONING, false)
        currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.STOP, true)

        setUiHint(
            getString(R.string.s_545) +
                "「${session.actionLabel(pkg, com.litertchat.plugin.ActionLabel.STOP)}」）"
        )
        val id = session.speakTo(
            pkg,
            p.text,
            // 测试插件没有克隆能力，模式对它没意义 —— 强行传 CLONE 只会让它困惑
            mode = if (tts) p.mode else PluginConst.MODE_PLAIN,
            referenceAudio = fd,
            referenceTranscript = if (tts) p.transcript else "",
            seed = if (tts) p.seed else 0,
        )
        if (id == null) {
            speaking = false
            closeRefFd()
            currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.REASONING, true)
            currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.STOP, false)
            setUiHint(getString(R.string.s_546))
        }
    }

    /** 用户点「停止」。插件那边是真打断（音频循环每块都查取消标志） */
    private fun stopPreview() {
        if (!session.isSpeaking()) {
            setUiHint(getString(R.string.s_547))
            return
        }
        // 先给个反馈，再等插件的 onError(CANCELLED) 收尾 ——
        // 有些插件要几百毫秒才走到检查点，不给反馈用户会以为按钮没生效
        setUiHint(getString(R.string.s_548))
        session.stopSpeaking()
    }

    /** 一次请求彻底结束（成功 / 失败 / 取消）都要调：复位按钮、放掉参考音频 FD */
    private fun finishRequest() {
        speaking = false
        closeRefFd()
        currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.REASONING, true)
        currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.STOP, false)
    }

    private fun closeRefFd() {
        runCatching { refFd?.close() }
        refFd = null
    }

    /**
     * 把焦点送到「文本」输入框。
     *
     * 用 `requestFocus()` 而不是手动算滚动位置：输入框拿到焦点时，
     * Android 会自己 `requestChildRectangleOnScreen` 把它滚进视野 ——
     * 这正是我们要的效果（以前这个机制在日志框上是 bug，这里反过来是特性）。
     */
    private fun focusSpeakText() {
        val v = speakTextInput ?: return
        if (v.parent == null) return      // 页面已经换掉了
        v.requestFocus()
        v.setSelection(v.text.length)
    }

    /** 按 SAF Uri 打开参考音频。失败返回 null（授权过期是最常见的原因） */
    private fun openReference(p: PreviewParams): android.os.ParcelFileDescriptor? {
        val uri = p.refUriOrNull() ?: return null
        return try {
            act.contentResolver.openFileDescriptor(uri, "r")
        } catch (t: Throwable) {
            android.util.Log.w("PonkoPluginPage", "打开参考音频失败：$uri", t)
            null
        }
    }

    /** 把状态写到**插件界面**的功能区 —— 按钮在那儿，进度也该在那儿 */
    private fun setUiHint(text: String) {
        speakHintText = text
        currentUiPage?.setActionHint(text)
    }

    /** 写诊断区（[com.litertchat.plugin.ResultBundle.debugInfo] 和最后一条错误） */
    private fun setDiag(pkg: String, text: String) {
        diagCache[pkg] = text
        if (openUiPkg == pkg) currentUiPage?.setDiagnostics(text)
    }

    // ==================== 试听参数（宿主自己的 UI）====================

    /**
     * 功能区下面的「试听参数」。
     *
     * 这块**只能由宿主画**：插件根本不知道"用户上次试听用了哪句话、哪段参考音频"。
     * 参数随 [com.litertchat.plugin.SynthesisRequest] 一起发出去
     * （字段对照见 [PreviewParams]）。
     *
     * 只对**真有 TTS 能力**的插件显示模式 / 参考音频 / 逐字稿：
     * 测试插件（Echo）没有克隆能力，摆一排点了没用的控件只会让人困惑。
     * 线程数对谁都有效，所以一直显示。
     */
    private fun buildPreviewParams(pkg: String, isTts: Boolean): View {
        var cur = params(pkg)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun remember(next: PreviewParams, persist: Boolean = false) {
            cur = next
            paramsCache[pkg] = next
            if (persist) PreviewParams.save(act, pkg, next)
        }

        box.addView(smallSection(getString(R.string.s_549)), matchWrap())

        // ---- 文本 ----
        //
        // **不设长度上限，也不截断。** 用户想合成多长、愿意等多久，是他的判断；
        // 宿主不知道插件的 RTF，没有依据去定"多长算太长"。真有限制该由插件
        // 自己拒绝并在 message 里说明，宿主照实转达。
        //
        // ⚠ 这里原来有一行"实时耗时估算"，2026-10-23 删掉了。
        //
        // 删的原因不是嫌麻烦，而是它**算不准而且不可能算准**：
        // 那个公式按"中文 4.5 字/秒 × RTF 10~30"硬编码，而真实速度取决于
        // 插件实现、机型、后端（CPU/GPU）、线程数 —— 换个语言（英文的
        // 字符/秒和中文的 字/秒 不是一回事）连数量级都会偏。
        // 给用户一个偏差几倍的倒计时，比不给更糟：他会按那个数字判断要不要
        // 继续等，等超了就以为卡死了。
        //
        // 真需要知道进度，应该由插件自己报（`onSpeakStage` 已经能报阶段，
        // 真要精确到秒就该在协议里带 progress），而不是宿主猜。
        box.addView(fieldLabel(getString(R.string.s_550)), wrapWrap())
        val textInput = EditText(act).apply {
            setText(cur.text)
            hint = getString(R.string.s_552)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setLineSpacing(dp(3).toFloat(), 1f)
            styleField(this)
            addTextChangedListener(simpleWatcher { s -> remember(cur.copy(text = s)) })
        }
        speakTextInput = textInput
        box.addView(textInput, matchWrap().apply { topMargin = dp(4) })

        // 显隐要在 chip 回调里用，所以先声明成可变量（Kotlin 局部函数不能前向引用）
        var syncMode: () -> Unit = {}
        var refRow: View? = null
        var transcriptBox: View? = null

        if (isTts) {
            // ---- 模式 ----
            box.addView(fieldLabel(getString(R.string.s_553)), wrapWrap().apply { topMargin = dp(10) })
            box.addView(
                chipRow(
                    listOf(
                        PluginConst.MODE_PLAIN to getString(R.string.s_554),
                        PluginConst.MODE_CLONE to getString(R.string.s_555),
                        PluginConst.MODE_CONTINUATION to getString(R.string.s_556),
                    ),
                    cur.mode,
                ) { v ->
                    remember(cur.copy(mode = v), persist = true)
                    syncMode()
                },
                matchWrap()
            )

            // ---- 参考音频 ----
            val ref = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            val refText = TextView(act).apply {
                text = if (cur.hasReference) getString(R.string.s_604, cur.refName) else getString(R.string.s_557)
                textSize = 12f
                setTextColor(if (cur.hasReference) pal.text else pal.hint)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            refLabel = refText
            ref.addView(
                refText,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            ref.addView(
                smallButton(getString(R.string.s_558)) { pickReference(pkg) },
                wrapWrap().apply { leftMargin = dp(8) }
            )
            refRow = ref
            box.addView(ref, matchWrap())

            // ---- 逐字稿 ----
            val trBox = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            trBox.addView(fieldLabel(getString(R.string.s_559)), wrapWrap())
            val trInput = EditText(act).apply {
                setText(cur.transcript)
                hint = getString(R.string.s_560)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setSingleLine(false)
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
                setLineSpacing(dp(3).toFloat(), 1f)
                styleField(this)
                addTextChangedListener(simpleWatcher { s -> remember(cur.copy(transcript = s)) })
            }
            trBox.addView(trInput, matchWrap().apply { topMargin = dp(4) })
            transcriptBox = trBox
            box.addView(trBox, matchWrap())

            // ---- 种子 ----
            box.addView(
                fieldLabel(getString(R.string.s_561)),
                wrapWrap().apply { topMargin = dp(10) }
            )
            val seedInput = EditText(act).apply {
                setText(cur.seed.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                styleField(this)
                addTextChangedListener(simpleWatcher { s ->
                    remember(cur.copy(seed = s.toIntOrNull() ?: 0))
                })
            }
            box.addView(seedInput, matchWrap().apply { topMargin = dp(4) })
        }

        // ---- 线程数（对任何插件都有意义：它是引擎加载参数）----
        box.addView(
            fieldLabel(getString(R.string.s_562)),
            wrapWrap().apply { topMargin = dp(10) }
        )
        box.addView(
            chipRow(
                PreviewParams.THREAD_CHOICES.map { if (it == 0) 0 to getString(R.string.s_563) else it to getString(R.string.s_605, it) },
                cur.threads,
            ) { v ->
                remember(cur.copy(threads = v), persist = true)
                setUiHint(
                    if (session.isSpeaking()) getString(R.string.s_564)
                    else getString(R.string.s_565)
                )
            },
            matchWrap()
        )
        box.addView(
            hintText(getString(R.string.s_566)),
            wrapWrap()
        )

        // ---- 推理后端（CPU / GPU）----
        //
        // 只有插件在 manifest 的 capabilities 里声明了 Capability.GPU
        // （"gpu.inference"）才显示。
        //
        // 为什么不无条件显示：插件没声明就说明它不打算用 GPU。用户在这里切到
        // GPU、插件照样跑 CPU，那个开关就是在撒谎 —— 比不显示更糟。
        // 也不做成灰掉的选项：灰掉一个用户永远用不上的东西只会引出
        // "为什么我不能选"的疑问，那还得再解释一遍。
        val supportsGpu = session.manifestOf(pkg)?.capabilities
            ?.contains(com.litertchat.plugin.Capability.GPU) == true
        if (supportsGpu) {
            box.addView(
                fieldLabel(getString(R.string.s_567)),
                wrapWrap().apply { topMargin = dp(10) }
            )
            box.addView(
                chipRow(
                    listOf(0 to "CPU", 1 to "GPU"),
                    if (cur.useGpu) 1 else 0,
                ) { v ->
                    remember(cur.copy(useGpu = v == 1), persist = true)
                    setUiHint(
                        if (session.isSpeaking()) getString(R.string.s_564)
                        else getString(R.string.s_565)
                    )
                },
                matchWrap()
            )
            box.addView(
                hintText(getString(R.string.s_568)),
                wrapWrap()
            )
        }

        syncMode = {
            val needRef = cur.needsReference
            refRow?.visibility = if (needRef) View.VISIBLE else View.GONE
            transcriptBox?.visibility = if (needRef) View.VISIBLE else View.GONE
        }
        if (isTts) syncMode()

        return box
    }

    /** 一排单选 chip。选中态自己维护，选中后回调 */
    private fun chipRow(
        options: List<Pair<Int, String>>,
        initial: Int,
        onPick: (Int) -> Unit,
    ): LinearLayout {
        var sel = initial
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val chips = ArrayList<TextView>()

        fun restyle() {
            chips.forEachIndexed { i, tv ->
                val on = options[i].first == sel
                tv.setTextColor(if (on) pal.primary else pal.text)
                tv.background = rounded(if (on) pal.primarySoft else pal.fieldBg, 9)
            }
        }

        options.forEachIndexed { i, pair ->
            val tv = TextView(act).apply {
                text = pair.second
                textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                setOnClickListener {
                    if (sel == pair.first) return@setOnClickListener
                    sel = pair.first
                    restyle()
                    onPick(pair.first)
                }
            }
            chips.add(tv)
            row.addView(tv, wrapWrap().apply { if (i > 0) leftMargin = dp(6) })
        }
        restyle()
        return row
    }

    /** 打开系统文件选择器挑参考音频 */
    private fun pickReference(pkg: String) {
        pendingRefPkg = pkg
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        val ok = try {
            act.startActivityForResult(i, REQ_REF_AUDIO)
            true
        } catch (t: Throwable) {
            android.util.Log.w("PonkoPluginPage", "打开音频选择器失败", t)
            false
        }
        if (!ok) {
            pendingRefPkg = null
            toast(getString(R.string.s_569))
        }
    }

    /**
     * 文件选择结果。由 [MainActivity] 的 `onActivityResult` 转发进来。
     *
     * @return true = 这个请求码是我们的，已经处理完（调用方不要再往下走）
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
        // v6：给插件挑模型文件
        if (requestCode == REQ_PICK_PLUGIN_MODEL) {
            val pkg = pendingModelPkg
            val role = pendingModelRole
            pendingModelPkg = null
            pendingModelRole = null
            if (pkg == null || role == null) return true
            // 用户按了返回：不算错误，也别说"失败"，就是取消了
            if (resultCode != Activity.RESULT_OK) return true
            val uri = data?.data ?: return true
            handModelToPlugin(pkg, role, uri)
            return true
        }

        if (requestCode != REQ_REF_AUDIO) return false
        val pkg = pendingRefPkg
        pendingRefPkg = null
        if (pkg == null) return true
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true

        // 持久化授权：不然 App 重启后这个 Uri 就读不了了，
        // 而用户会以为是"选过的东西又没了"
        runCatching {
            act.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { android.util.Log.w("PonkoPluginPage", "持久化 Uri 授权失败", it) }

        val name = displayNameOf(uri) ?: uri.lastPathSegment ?: getString(R.string.s_570)
        val next = params(pkg).copy(refUri = uri.toString(), refName = name)
        paramsCache[pkg] = next
        PreviewParams.save(act, pkg, next)
        refLabel?.apply {
            text = getString(R.string.s_604, name)
            setTextColor(pal.text)
        }
        toast(getString(R.string.s_607, name))
        return true
    }

    // ==================== v6：模型文件导入 ====================

    /**
     * 弹系统文件选择器，给某个模型槽位挑文件。
     *
     * ## 为什么由宿主弹，而不是插件
     *
     * 插件是 bound **Service**（另一个进程）：
     * · `startActivityForResult` 是 Activity 的 API，Service 没有
     * · Android 10+ 禁止后台启动 Activity —— 用户点「导入」那一刻插件不在前台
     *
     * 所以宿主代弹、代拿 FD、再转给插件。用户看到的和"宿主模型页导入"
     * 是同一个系统选择器，操作感受完全一致。
     *
     * ## 为什么类型选「任意」
     *
     * 模型文件没有统一的 MIME：`.gguf` / `.litertlm` / `.safetensors` / `.bin`
     * 在系统里多半被识别成 `application/octet-stream`。限定类型反而会把
     * 用户真正想选的文件灰掉 —— 那种"文件明明在那儿却选不了"的体验很差。
     *
     * （这里不写那个通配类型字面量：它的后两个字符是块注释的结束符，
     * 写进 KDoc 会把注释提前截断。）
     */
    private fun pickModelForPlugin(pkg: String, role: String) {
        pendingModelPkg = pkg
        pendingModelRole = role
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        val ok = try {
            act.startActivityForResult(i, REQ_PICK_PLUGIN_MODEL)
            true
        } catch (t: Throwable) {
            android.util.Log.w("PonkoPluginPage", "打开文件选择器失败", t)
            false
        }
        if (!ok) {
            pendingModelPkg = null
            pendingModelRole = null
            toast(getString(R.string.s_569))
        }
    }

    /**
     * 拿到用户选的文件 → 开 FD → 转给插件。
     *
     * ## 宿主不落盘
     *
     * 这里只 `openFileDescriptor` 拿一个只读 FD，**不复制、不缓存路径**。
     * 插件的活是把这个 FD 读进它自己的私有目录。所以磁盘上最终只有
     * 一份文件 —— 和"用户在宿主模型页导入"的代价相同。
     *
     * ## 为什么不留持久化授权
     *
     * 导入是一次性的：插件读完之后，那个 SAF Uri 对双方都没用了
     * （插件存的是自己目录里的副本）。参考音频那条路要留授权，是因为
     * 每次合成都要重新读原始文件；这里不是。
     */
    private fun handModelToPlugin(pkg: String, role: String, uri: android.net.Uri) {
        val name = displayNameOf(uri) ?: uri.lastPathSegment ?: "model.bin"
        val res = act.contentResolver

        // 大小只用来给插件显示/校验；拿不到就 -1，别让它变成错误
        val size = try {
            res.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (t: Throwable) {
            -1L
        }

        toast(getString(R.string.s_608, name))
        // 开 FD 和转发都要快，但 openFileDescriptor 可能碰网络盘，放后台
        Thread {
            var fd: android.os.ParcelFileDescriptor? = null
            try {
                fd = res.openFileDescriptor(uri, "r")
                if (fd == null) {
                    act.runOnUiThread { toast(getString(R.string.s_571)) }
                    return@Thread
                }
                val ready = fd
                act.runOnUiThread {
                    // importModel 内部会关掉这份 FD（Binder 已 dup 一份给插件）
                    session.importModel(pkg, role, ready, name, size)
                    toast(getString(R.string.s_609, name))
                }
            } catch (t: Throwable) {
                android.util.Log.w("PonkoPluginPage", "转发模型文件失败：$name", t)
                runCatching { fd?.close() }
                act.runOnUiThread {
                    toast(getString(R.string.s_610, t.message ?: t.javaClass.simpleName))
                }
            }
        }.start()
    }

    private fun deleteModelOf(pkg: String, role: String) {
        session.deleteModel(pkg, role)
        toast(getString(R.string.s_572))
    }


    private fun displayNameOf(uri: android.net.Uri): String? = try {
        act.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (t: Throwable) {
        null    }

    private fun smallSection(t: String): TextView = TextView(act).apply {
        text = t
        textSize = 12f
        setTextColor(pal.subText)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, 0)
    }

    private fun fieldLabel(t: String): TextView = TextView(act).apply {
        text = t
        textSize = 12f
        setTextColor(pal.subText)
    }

    private fun styleField(e: EditText) {
        e.textSize = 13f
        e.setTextColor(pal.text)
        e.setHintTextColor(pal.hint)
        e.setPadding(dp(10), dp(8), dp(10), dp(8))
        e.background = rounded(pal.fieldBg, 9)
    }

    // 这里原来有个 `fmtDur`（秒 → "45 秒" / "2.3 分钟" / "1 小时 5 分"），
    // 只服务于那个已删除的耗时估算。2026-10-23 随估算一起去掉 ——
    // 留着会让人以为还有地方在显示时长。

    /** 输入框变化回调 */
    private fun simpleWatcher(onChanged: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            onChanged(s?.toString() ?: "")
        }
    }

    private fun errName(code: Int): String = when (code) {
        ErrCode.NOT_READY -> "NOT_READY"
        ErrCode.MODEL_INVALID -> "MODEL_INVALID"
        ErrCode.OUT_OF_MEMORY -> "OUT_OF_MEMORY"
        ErrCode.CANCELLED -> "CANCELLED"
        ErrCode.INVALID_REQUEST -> "INVALID_REQUEST"
        ErrCode.PLUGIN_INTERNAL -> "PLUGIN_INTERNAL"
        ErrCode.UNSUPPORTED -> "UNSUPPORTED"
        ErrCode.TIMEOUT -> "TIMEOUT"
        ErrCode.BUSY -> "BUSY"
        else -> "UNKNOWN"
    }

    private fun params(pkg: String): PreviewParams =
        paramsCache.getOrPut(pkg) { PreviewParams.load(act, pkg) }

    /**
     * 收下插件回传的音频：落盘，并让「播放 / 保存」变成可点。
     *
     * ## 这里**不播放**（2026-10-22 改）
     *
     * 之前合成完会直接 `playFile(f)` 自动播。那样做有两个后果：
     *
     * 1. **一个按钮干了两件事**。用户想要的是"生成一段话"，却被迫听完；
     *    想在安静场合用就只能马上点停止。
     * 2. **逼出了一个多余的 key**。合成即播放之后，同一个按钮在"还没听过"和
     *    "听过了"两种状态下含义不同（一个是听，一个是再算一遍），只好加
     *    `preview.again` 给它改名。改成仅合成之后，点几次都是同一个动作，
     *    那个 key 就没有存在理由了 —— 它是自动播放的补丁，不是需求。
     *
     * 现在：合成 → 出文件 → 点「播放」才出声。两件事各有一个按钮。
     *
     * ## 做法是先把 FD 内容落到文件，而不是 `setDataSource(fd)`
     *
     * 后者对 WAV 需要精确的 offset/length，而 FD 跨进程传递后长度信息不一定可靠，
     * 容易出现「播不动但不报错」。
     *
     * ## 落盘位置（2026-10-21）
     *
     * 不再固定写 `cache/plugin_preview.wav` 每次覆盖 —— 那样生成的音频只能听一次。
     * 现在带时间戳写进 `getExternalFilesDir("tts")`，并记成「保存」的目标。
     */
    private fun storeAudio(pkg: String, result: com.litertchat.plugin.ResultBundle) {
        // 小结果直接内联，省掉 FD
        val bytes = result.inlineAudio ?: result.audio?.let { pfd ->
            try {
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            } finally {
                // FD 归宿主所有：读完必须关，否则插件那边的文件句柄一直挂着
                runCatching { pfd.close() }
            }
        }
        if (bytes == null || bytes.isEmpty()) {
            setUiHint(getString(R.string.s_573))
            return
        }
        try {
            val f = writeAudio(pkg, bytes)
            // 换新文件了：旧的播放器还指着上一份，先放掉 ——
            // 不放的话进度条的时长/位置会继续显示上一段音频的
            runCatching { player?.release() }
            player = null
            lastAudioFile = f
            lastAudioPkg = pkg
            currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.SAVE, true)
            currentUiPage?.setActionEnabled(com.litertchat.plugin.HostActionId.PLAY, true)
            // 播放条要立刻从"还没有可播放的音频"变成可用，并把进度归零
            currentUiPage?.refreshAudio()
            // 合成完了，但**不出声** —— 得明确告诉用户音频在哪、下一步点哪儿，
            // 否则他会以为没成功（这是"仅合成"唯一要付的代价）
            val playLabel = session.actionLabel(
                pkg, com.litertchat.plugin.ActionLabel.PLAY
            )
            setUiHint(
                getString(R.string.s_613, 
                    result.durationMs / 1000.0,
                    result.elapsedMs / 1000.0,
                    playLabel,
                )
            )
        } catch (t: Throwable) {
            // 这里失败的是**写文件**，不是播放 —— 报"播放失败"会把人引向错误方向
            setUiHint(getString(R.string.s_625, t.javaClass.simpleName))
        }
    }

    /**
     * 给插件界面用的播放器接口（[UiType.AUDIO] 的背后）。
     *
     * ## 为什么要包一层，不直接给 MediaPlayer
     *
     * `MediaPlayer` 的每个查询（`currentPosition`、`isPlaying`）都有状态前提，
     * 在没 prepare / 已 release 时会抛 `IllegalStateException`。
     * 插件界面的播放条每 200ms 就会问一次，用户还可能在手忙脚乱时连点 —
     * 这里统一 catch 掉，把"播放器此刻不健康"表达成 `false` / `0`，
     * 而不是让界面崩掉。
     *
     * 同时它把"播哪份文件"锁死成**宿主最近一次产物**：插件拿不到路径，
     * 也就没法让宿主去播任意文件。
     */
    private fun audioHostFor(pkg: String): PluginUiPage.AudioHost =
        object : PluginUiPage.AudioHost {

            override fun hasAudio(key: String): Boolean = fileOf(pkg, key) != null

            override fun isPlaying(key: String): Boolean = try {
                targetPlayer(pkg, key)?.isPlaying == true
            } catch (t: Throwable) {
                false
            }

            override fun positionMs(key: String): Int = try {
                targetPlayer(pkg, key)?.currentPosition ?: 0
            } catch (t: Throwable) {
                0
            }

            override fun durationMs(key: String): Int {
                // 先问播放器（它拿到的是准的），问不出来再读文件元信息。
                // 还没点过播放的条目只有后者能给 —— 播放条上显示 "0:00 / 0:12"
                // 比 "0:00 / 0:00" 有用得多
                val live = try {
                    targetPlayer(pkg, key)?.duration ?: 0
                } catch (t: Throwable) {
                    0
                }
                if (live > 0) return live
                return fileOf(pkg, key)?.let { durationOf(it) } ?: 0
            }

            override fun play(key: String) {
                val own = ownFile(pkg, key)
                // 同一时间只响一条。不这么做的话，点第 3 条再点第 5 条会两段声音
                // 叠在一起，用户第一反应是"卡了"
                pauseEverythingExcept(pkg, key)
                if (own == null) {
                    // 走"宿主最近产物"这条：沿用「播放」按钮那个播放器，
                    // 两边状态必须一致，否则会一个说在播、一个说没播
                    val p = player
                    if (p != null) {
                        try {
                            p.start()
                            return
                        } catch (t: Throwable) {
                            // 已完成 / 被释放 → 落到下面重开一个
                        }
                    }
                    lastAudioFile?.let { playFile(it) }
                    return
                }
                try {
                    keyPlayer(pkg, key, own)?.start()
                } catch (t: Throwable) {
                    android.util.Log.w("PonkoPluginPage", "播放插件音频失败：$key", t)
                }
            }

            override fun pause(key: String) {
                try {
                    targetPlayer(pkg, key)?.pause()
                } catch (t: Throwable) {
                    // 已完成或未准备，忽略
                }
            }

            override fun seekTo(key: String, ms: Int) {
                try {
                    targetPlayer(pkg, key)?.seekTo(ms)
                } catch (t: Throwable) {
                    // 同上
                }
            }
        }

    /** [keyAudioFiles] / [keyPlayers] 的索引键。带包名：两个插件可能用同一个 key */
    private fun keySlot(pkg: String, key: String) = "$pkg|$key"

    /** 插件给这个 key **自己送**的那份文件（还活着的话） */
    private fun ownFile(pkg: String, key: String): java.io.File? =
        keyAudioFiles[keySlot(pkg, key)]?.takeIf { it.exists() }

    /**
     * 这个 key 实际该播哪个文件。
     *
     * 插件送的优先；没送过就退回"宿主最近一次产物"—— 那正是 v4 的行为，
     * 所以只会放一个播放器、不送文件的老插件完全不用改。
     * `lastAudioPkg` 要一起比：换了插件就不该再播上一个插件的结果。
     */
    private fun fileOf(pkg: String, key: String): java.io.File? =
        ownFile(pkg, key)
            ?: lastAudioFile?.takeIf { lastAudioPkg == pkg && it.exists() }

    /** 这个 key 现在由哪个播放器负责。null = 还没建（第一次 `play` 时才建） */
    private fun targetPlayer(pkg: String, key: String): android.media.MediaPlayer? =
        if (ownFile(pkg, key) != null) keyPlayers[keySlot(pkg, key)] else player

    /**
     * 拿到 key 自己的播放器；文件换了就重建。
     *
     * 不重建的话，播放器还指着上一份文件 —— 时长、进度、播出来的声音全是旧的，
     * 而界面上写的是新那条的标题。这类错位比"播不出来"难查得多。
     */
    private fun keyPlayer(
        pkg: String,
        key: String,
        file: java.io.File,
    ): android.media.MediaPlayer? {
        val slot = keySlot(pkg, key)
        val old = keyPlayers[slot]
        // 同一个文件就复用：暂停后接着播，不该跳回开头
        if (old != null && keyPlayerFiles[slot] == file) return old
        runCatching { old?.release() }
        keyPlayers.remove(slot)
        return try {
            val p = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
            keyPlayers[slot] = p
            keyPlayerFiles[slot] = file
            p
        } catch (t: Throwable) {
            android.util.Log.w("PonkoPluginPage", "准备插件音频失败：${file.name}", t)
            null
        }
    }

    /** 停掉除 [keepKey] 以外的所有声音（含宿主那个共享播放器） */
    private fun pauseEverythingExcept(pkg: String, keepKey: String) {
        val keepSlot = keySlot(pkg, keepKey)
        keyPlayers.forEach { (slot, p) ->
            if (slot != keepSlot) runCatching { if (p.isPlaying) p.pause() }
        }
        // 正在播的是"插件自己那份"时，共享播放器（最近产物）也得停
        if (ownFile(pkg, keepKey) != null) {
            runCatching { if (player?.isPlaying == true) player?.pause() }
        }
    }

    /**
     * 读文件元信息里的时长（毫秒）。失败返回 0 —— 那只是播放条上少一个数字，
     * 不该让整条播放条不可用。
     */
    private fun durationOf(file: java.io.File): Int {
        val cacheKey = "dur:" + file.absolutePath
        keyDurations[cacheKey]?.let { return it }
        val d = try {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toIntOrNull() ?: 0
            } finally {
                runCatching { r.release() }
            }
        } catch (t: Throwable) {
            0
        }
        keyDurations[cacheKey] = d
        return d
    }

    /**
     * 插件送来的音频（v7）：落盘、登记到它的 key、换掉旧播放器。
     *
     * 同一个 key 再来一份就是**替换**：旧文件删掉。不删的话插件每出一条
     * 历史记录就在 `tts/` 里留一个文件，用户在系统文件管理器里看不到也删不掉，
     * 只能靠"清除应用数据"。
     */
    private fun acceptPluginAudio(pkg: String, key: String, bytes: ByteArray, name: String?) {
        if (bytes.isEmpty()) return
        try {
            val f = writePluginAudio(pkg, key, bytes, name)
            val slot = keySlot(pkg, key)
            val old = keyAudioFiles.put(slot, f)
            if (old != null && old != f) runCatching { old.delete() }
            runCatching { keyPlayers.remove(slot)?.release() }
            keyPlayerFiles.remove(slot)
            // 那条播放条要立刻从"还没有可播放的音频"变成可用
            if (openUiPkg == pkg) currentUiPage?.refreshAudio()
        } catch (t: Throwable) {
            android.util.Log.w("PonkoPluginPage", "保存插件音频失败：$key", t)
        }
    }

    /**
     * 把插件送来的音频写成文件。
     *
     * 扩展名取插件给的文件名的（插件送 mp3 就得是 `.mp3`，存成 `.wav`
     * 文件名在撒谎）；key 只用来区分同一次会话里的多条，所以先过一遍白名单，
     * 免得 key 里的 `/` 把文件写到别处去。
     */
    private fun writePluginAudio(
        pkg: String,
        key: String,
        bytes: ByteArray,
        displayName: String?,
    ): java.io.File {
        val base = act.getExternalFilesDir("tts") ?: act.cacheDir
        if (!base.exists()) base.mkdirs()
        val stamp = java.text.SimpleDateFormat(
            "MMdd-HHmmss", java.util.Locale.US
        ).format(java.util.Date())
        val tag = pkg.substringAfterLast('.').ifBlank { "plugin" }
        val ext = displayName?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
            ?: "wav"
        val safeKey = key.ifBlank { "latest" }
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(24)
            .ifBlank { "k" }
        val f = java.io.File(base, "ponko-$tag-$safeKey-$stamp.$ext")
        f.writeBytes(bytes)
        return f
    }

    /**
     * 播放一个已存在的音频文件。
     *
     * 现在只有「播放」按钮会调它 —— 合成**不会**自动播放。
     * 抽成独立函数的理由：播放这件事只该有一份实现，
     * 万一以后又要在别处出声（比如朗读路由），不能再抄一遍。
     */
    private fun playFile(f: java.io.File) {
        // 提示里会引用「保存」按钮的名字，所以得知道是哪个插件 ——
        // 插件可以把这个按钮叫成别的（见 ActionLabel.SAVE）
        val saveLabel = openUiPkg?.let {
            session.actionLabel(it, com.litertchat.plugin.ActionLabel.SAVE)
        } ?: getString(R.string.s_574)
        try {
            // 一开播就先把"逐条播放器"停掉：用户点了顶部的「播放」，
            // 下面某条历史还在响就成了两段声音叠着
            keyPlayers.values.forEach { runCatching { if (it.isPlaying) it.pause() } }
            player?.release()
            player = android.media.MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener {
                    setUiHint(getString(R.string.s_626, saveLabel))
                    // 播完了：播放条的 ▶ 要复原，进度停在末尾
                    currentUiPage?.refreshAudio()
                }
                setOnErrorListener { _, what, extra ->
                    setUiHint(getString(R.string.s_627, what, extra))
                    true
                }
                prepare()
                start()
            }
            // 开播了：播放条要从 ▶ 变 ⏸，并开始走
            currentUiPage?.refreshAudio()
        } catch (t: Throwable) {
            setUiHint(getString(R.string.s_628, t.javaClass.simpleName))
        }
    }

    /** 写一份带时间戳的 WAV。不覆盖，每次试听都留一份 */
    private fun writeAudio(pkg: String, bytes: ByteArray): java.io.File {
        val base = act.getExternalFilesDir("tts") ?: act.cacheDir
        if (!base.exists()) base.mkdirs()
        val stamp = java.text.SimpleDateFormat(
            "MMdd-HHmmss", java.util.Locale.US
        ).format(java.util.Date())
        // 包名最后一段做前缀，一眼能看出是谁生成的
        val tag = pkg.substringAfterLast('.').ifBlank { "plugin" }
        val f = java.io.File(base, "ponko-$tag-$stamp.wav")
        f.writeBytes(bytes)
        return f
    }

    /**
     * 「保存」按钮：把**最近一次合成**的音频存进系统音乐库（`音乐/Ponko`）。
     *
     * 为什么走 MediaStore 而不是直接文件路径：Android 10 起应用不能随便往
     * 公共目录写。MediaStore 是官方通道，而且存完立刻能在任何播放器里看到。
     *
     * API 28 上没有 MediaStore.Audio 的 RELATIVE_PATH，退化成"告诉用户文件在哪" ——
     * 那份文件本来就在应用专属外部目录里，能长期留存，只是别的 App 找不到。
     */
    private fun saveAudio() {
        val f = lastAudioFile
        if (f == null || !f.exists()) {
            setUiHint(getString(R.string.s_575))
            return
        }
        saveAudioFile(f)
    }

    /**
     * 存**某一条**播放器的音频（v7 起每个 `AUDIO` 条目都有自己的「保存」）。
     *
     * 和 [saveAudio] 的区别只有"选哪份文件"：那个永远存最新产物，
     * 这个按 key 找 —— 插件送过就存插件那份，没送过退回最新产物。
     */
    private fun saveAudioOf(pkg: String, key: String) {
        val f = fileOf(pkg, key)
        if (f == null || !f.exists()) {
            setUiHint(getString(R.string.s_576))
            return
        }
        saveAudioFile(f)
    }

    /** 把一份音频复制进系统音乐库（`音乐/Ponko`） */
    private fun saveAudioFile(f: java.io.File) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            setUiHint(getString(R.string.s_629, f.absolutePath))
            return
        }
        try {
            val resolver = act.contentResolver
            // 插件送的可能是 mp3/m4a（v7 按插件给的文件名保留扩展名），
            // 一律写成 audio/wav 会让音乐库按错误容器解析
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "audio/wav"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, mime)
                put(
                    android.provider.MediaStore.Audio.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_MUSIC + "/Ponko"
                )
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            ) ?: run {
                setUiHint(getString(R.string.s_577))
                return
            }
            val ok = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                setUiHint(getString(R.string.s_578))
                return
            }
            values.clear()
            values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            setUiHint(getString(R.string.s_579))
        } catch (t: Throwable) {
            android.util.Log.e("PonkoPluginPage", "保存音频失败", t)
            setUiHint(getString(R.string.s_630, t.javaClass.simpleName))
        }
    }

    /**
     * 弹「关于」：插件自己写的纯文本文档，可复制。
     *
     * ## 为什么不用 setMessage
     *
     * `AlertDialog.setMessage` 显示的是不可选中的 `TextView` ——
     * 长按选不了、复制不了。而这个弹窗的内容是**插件作者写的一段说明**
     * （版本要求、模型许可、作者联系方式之类），用户经常需要复制其中一行
     * 去搜、去贴。所以自己建一个可选中、可滚动的 TextView。
     *
     * ## 没有文档时显示"无"
     *
     * 不是错误、不弹提示条 —— 缺一个关于文档是正常情况（老插件都没有）。
     */
    private fun showPluginAbout(item: PluginSession.Item) {
        val text = item.found.about?.trim()

        // 用 ScrollView 包住：关于文档可能很长（作者会写模型许可、变更记录），
        // 直接塞进弹窗会顶满屏幕、按钮都露不出来
        val body = TextView(act).apply {
            this.text = text ?: getString(R.string.s_580)
            textSize = 13f
            setTextColor(if (text != null) pal.text else pal.hint)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            // 有内容才让它可选中 —— 空的"无"没什么好复制的，
            // 而且 selectable 会让它抢焦点
            if (text != null) setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(act).apply {
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val builder = android.app.AlertDialog.Builder(
            act, android.R.style.Theme_DeviceDefault_Dialog_Alert
        )
            .setTitle(getString(R.string.s_631, item.found.label))
            .setView(scroll)
            .setPositiveButton(getString(R.string.s_581), null)

        // 只有真有内容才给「复制」——对着一个"无"提供复制按钮很荒唐
        if (text != null) {
            builder.setNeutralButton(getString(R.string.s_582)) { _, _ ->
                val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                if (cm != null) {
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            getString(R.string.s_632, item.found.label), text
                        )
                    )
                    toast(getString(R.string.s_583))
                }
            }
        }
        builder.show()
    }

    private fun showInstallHelp() {
        val msg = """
            安装插件的方法：

            1. 下载插件 APK（.apk 文件）
            2. 用文件管理器点开它
            3. 系统会提示「未知来源」→ 允许
            4. 安装完成后回到这一页，点「重新扫描」

            如果扫描不到，检查插件和 Ponko 是不是同一把密钥签的名。
        """.trimIndent()
        android.app.AlertDialog.Builder(
            act, android.R.style.Theme_DeviceDefault_Dialog_Alert
        )
            .setTitle(getString(R.string.s_480))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.s_584), null)
            .show()
    }

    // ==================== UI 小工具（与主界面风格一致）====================

    private fun pageTitle(t: String) = TextView(act).apply {
        text = t
        textSize = 15f
        setTextColor(pal.text)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun hintText(t: String) = TextView(act).apply {
        text = t
        textSize = 12f
        setTextColor(pal.subText)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(5), 0, 0)
    }

    /** 色条图例：四小段颜色 + 文字，横排 */
    private fun colorLegend(): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val entries = listOf(
            pal.ok to getString(R.string.s_585),
            pal.warn to getString(R.string.s_586),
            pal.err to getString(R.string.s_587),
            pal.hint to getString(R.string.s_539),
        )
        entries.forEachIndexed { i, (color, label) ->
            // 小色块做成和卡片色条一样的细竖条，视觉上能对上号
            row.addView(View(act).apply { setBackgroundColor(color) },
                LinearLayout.LayoutParams(dp(4), dp(12)).apply {
                    if (i > 0) leftMargin = dp(12)
                })
            row.addView(TextView(act).apply {
                text = label
                textSize = 11f
                setTextColor(pal.subText)
            }, wrapWrap().apply { leftMargin = dp(5) })
        }
        return row
    }

    private fun emptyCard(title: String, body: String) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = rounded(pal.fieldBg, 12)
        addView(TextView(act).apply {
            text = title
            textSize = 14f
            setTextColor(pal.subText)
            gravity = Gravity.CENTER
        }, matchWrap())
        addView(TextView(act).apply {
            text = body
            textSize = 12f
            setTextColor(pal.hint)
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWrap())
    }

    /**
     * 卡片背景：圆角底板 + 左侧状态色条。
     *
     * ## 为什么色条要画在背景里，而不是放个子 View
     *
     * 子 View 想撑满卡片高度只能写 `MATCH_PARENT`，而 `View.getDefaultSize()`
     * 在 **AT_MOST** 模式下返回的是 `specSize`（父给的整个可用高度）。
     * `wrap_content` 的父容器传的正是 `AT_MOST(可用高度)` ——
     * 于是那条"4dp 色条"实际高度等于**满屏**，把卡片顶成满屏高，
     * 收起时也永远不会变矮。
     *
     * 画在背景里就没有这个问题：`bounds` 天然等于视图自己的尺寸。
     */
    private fun cardBackground(stripe: Int): Drawable {
        val radius = dp(12).toFloat()
        val stripeW = dp(4).toFloat()
        val surface = GradientDrawable().apply {
            setColor(pal.surface)
            cornerRadius = radius
        }
        return object : Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val path = Path()
            private val tmp = RectF()

            override fun draw(canvas: Canvas) {
                // 1) 圆角底板
                surface.bounds = bounds
                surface.draw(canvas)

                // 2) 左侧色条：先裁出左边 4dp，再画整个圆角矩形 ——
                //    这样色条的上/下左角正好跟着卡片圆角走，不会出现方角戳出去。
                //    clipRect + drawPath 都是硬件加速支持的；
                //    clipPath 在部分机型上不好使，所以不用它。
                paint.color = stripe
                val save = canvas.save()
                canvas.clipRect(
                    bounds.left.toFloat(), bounds.top.toFloat(),
                    bounds.left + stripeW, bounds.bottom.toFloat()
                )
                tmp.set(bounds)
                path.reset()
                path.addRoundRect(tmp, radius, radius, Path.Direction.CW)
                canvas.drawPath(path, paint)
                canvas.restoreToCount(save)
            }

            override fun setAlpha(alpha: Int) {
                surface.alpha = alpha
                paint.alpha = alpha
            }

            override fun setColorFilter(cf: ColorFilter?) {
                surface.colorFilter = cf
                paint.colorFilter = cf
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun smallButton(text: String, onClick: () -> Unit): TextView = TextView(act).apply {
        this.text = text
        textSize = 12f
        setTextColor(pal.primary)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(pal.primarySoft, 10)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * 破坏性操作用的小按钮（和 [smallButton] 同一行并排时的版本）。
     *
     * 尺寸、圆角、内边距都和 [smallButton] 一致，只有配色不同：
     * **透明底 + 1dp 红描边 + 红字**。这样一行四个按钮里，
     * 「卸载」一眼就能和 启用/停用、激活、进入 分开 —— 不用靠位置去记。
     *
     * 为什么不做成实心红块：实心红太抢眼，四个按钮里它会变成视觉焦点，
     * 而卸载恰恰是最不该被优先点的那个。
     */
    private fun dangerButton(text: String, onClick: () -> Unit): View {
        val radiusPx = dp(10).toFloat()
        val stroke = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = radiusPx
            setStroke(dp(1), pal.err)
        }
        // 水波纹：点下去必须有视觉反馈，否则"没反应"和"没点中"分不清
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                (pal.err and 0x00FFFFFF) or 0x33000000   // err 色的 20% 透明度
            ),
            stroke,
            null
        )
        val btn = TextView(act).apply {
            this.text = text
            textSize = 12f
            setTextColor(pal.err)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = ripple
            isClickable = true
            isFocusable = true
            setOnClickListener {
                android.util.Log.i("PonkoPluginPage", "点击卸载：$text")
                try {
                    onClick()
                } catch (t: Throwable) {
                    // 不能让异常静默消失 —— 那正是"点了没反应"最难查的形态
                    android.util.Log.e("PonkoPluginPage", "卸载流程异常", t)
                    toast(getString(R.string.s_633, t.javaClass.simpleName))
                }
            }
        }
        return btn
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun wrapWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    companion object {
        /**
         * 选参考音频的请求码。
         *
         * 刻意避开 MainActivity 那一串 1001–1020（模型/图片/权限），
         * 否则会串到宿主的其它回调里 —— 那种 bug 表现为"选完音频，
         * 聊天里多出一张图"，很难查。
         */
        const val REQ_REF_AUDIO = 0x9E01

        /**
         * 给插件的模型槽位挑文件的请求码（v6）。
         *
         * 同样避开 MainActivity 的 1001–1020，也和 [REQ_REF_AUDIO] 分开 ——
         * 两者走同一个 `onActivityResult`，共用一个码会把"选模型"当成"选音频"处理。
         */
        const val REQ_PICK_PLUGIN_MODEL = 0x9E02

        /** 等引擎卸载完成的轮询间隔。200ms ≈ 5 次/秒，对一次几百毫秒的操作够细 */
        private const val ENGINE_POLL_MS = 200L

        /** 卸载超时。给"正在推理 + free 几 GB"留足余量（要等插件查 cancelFlag） */
        private const val ENGINE_POLL_TIMEOUT_MS = 5_000L
    }
}
