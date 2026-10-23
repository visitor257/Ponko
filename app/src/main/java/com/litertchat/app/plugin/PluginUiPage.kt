// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.litertchat.app.PonkoTheme
import com.litertchat.plugin.UiItem
import com.litertchat.plugin.UiSchema
import com.litertchat.plugin.UiType

/**
 * **插件自己的界面** —— 由宿主渲染。
 *
 * 插件只提供一份字段清单（[UiSchema]），控件长什么样、用什么颜色、圆角多少，
 * 全部由这里按 Ponko 的主题画。这样插件不用写任何布局，也自动跟着深浅色切换。
 *
 * ## 为什么值不在宿主存
 *
 * 每次重画都重新 `getSettingsSchema()`，值由插件提供。宿主存一份必然出现
 * 「宿主以为是这样、插件实际是那样」—— 这类 bug 极难查。
 *
 * ## 只有 7 种控件
 *
 * 这是刻意的：每多一种，宿主就多一份渲染代码 + 一套主题适配。
 * 真表达不了的东西（波形预览之类）走「进阶界面」逃生口。
 */
class PluginUiPage(
    private val act: Activity,
    private val pal: PonkoTheme,
    private val pkg: String,
    private val title: String,
    private val session: PluginSession,
    /**
     * 宿主侧的功能动作（一份**注册表**，不是"固定要画的那几个"）。
     *
     * 「试听」「朗读」这类不是插件自己实现的功能 —— 它们是**宿主**发起一次合成
     * 再播放结果。所以执行归宿主，插件只提供配置项（schema）。
     *
     * ## v9 起：放哪里由插件决定
     *
     * 以前这 5 个按钮固定画在标题下面。插件想在「参数」页里放一个「开始推理」，
     * 做不到 —— 只能让用户滚回顶部。
     *
     * 现在插件可以在 schema 里用 `UiItem(type = ACTION, value = HostAction.REASONING)`
     * **点名**要哪个动作，那个按钮就画在它指定的位置（含 tab 里）；
     * 顶部那排会自动跳过被点名的那些。没点名的照旧留顶部。
     *
     * 是**逐个覆盖**而不是全有全无 —— 插件可以只接管「合成」，其余不动。
     */
    private val hostActions: List<HostAction> = emptyList(),
    /**
     * 功能区下面追加一块**宿主自己画的**内容（试听参数之类）。
     *
     * 为什么不塞进 [HostAction]：那是一个"按钮"，而这里是一组输入控件。
     * 也不塞进插件的 `UiSchema` —— 这些参数是宿主发起 `synthesize` 时的入参，
     * 归宿主所有（见 [PreviewParams] 的说明）。
     *
     * 由调用方（[PluginPage]）构建，因为它知道 `act` / `pal` 和参数模型。
     */
    private val hostExtra: (() -> View)? = null,
    private val onBack: () -> Unit,
) {
    /**
     * 宿主提供的一个功能按钮。
     *
     * @param id 动作 id，要和 `com.litertchat.plugin.HostAction` 里的常量一致 ——
     *           插件在 schema 里就是靠这个值点名要它的
     * @param label 默认文案。插件点名时可以覆盖，也可以被 [setActionLabel] 改
     * @param enabled 初始是否可点。运行中要改就用 [setActionEnabled]
     */
    data class HostAction(
        val id: String,
        val label: String,
        val help: String = "",
        val enabled: Boolean = true,
        val run: () -> Unit,
    )

    /**
     * 一个宿主动作按钮的实例。
     *
     * 同一个动作可能在**两个地方**各有一份：顶部那排、以及插件自己声明的位置
     * （`UiItem.value` = 动作 id）。两处状态必须同步，否则会出现
     * "顶部的「合成」灰了、插件页里那个还能点"——所以按 id 存一个列表。
     *
     * @param compact true = 顶部那排的等宽小按钮；false = 插件页里的全宽按钮
     */
    private class ActionBtn(val view: TextView, val compact: Boolean, val primary: Boolean)

    /**
     * 一个**共用槽位**：两个互斥的按钮占同一格，靠可见性交替（v9.1）。
     *
     * ## 为什么需要
     *
     * 「开始推理」和「停止」不会同时可用 —— 没在合成时只有前者有意义，
     * 合成中只有后者。并排放着会让一行里永远有一个灰按钮，白占一格宽度。
     *
     * ## 为什么必须用 `INVISIBLE`，不能用 `GONE`
     *
     * `GONE` 会把后面的按钮往左拽、整行重排 —— 那不是"停止顶到开始的位置"，
     * 而是"保存挤过来了"。`INVISIBLE` 保留占位，槽宽恒定，交替时整行纹丝不动。
     *
     * ## 结构
     *
     * `FrameLayout` 里两个按钮都 `MATCH_PARENT` 叠放。叠着的好处：
     * 槽高取两者较大值，交替时**高度也不跳**（`GONE` 方案还会让行高抖一下）。
     */
    private class ActionSlot(
        /** 槽位本身，由外部给 weight 决定宽度 */
        val view: android.widget.FrameLayout,
        /** 参与交替的按钮，按 `[默认显示, 备选]` 顺序 */
        val members: List<Pair<String, TextView>>,
    ) {
        /** 当前显示哪个 id */
        var shown: String = members.first().first

        /**
         * 刷新：**挑"可点"的那个显示**；都不可点就显示默认那个（灰着）。
         *
         * 为什么以"可点"而不是"外部显式设了可见"为依据：宿主只调
         * `setActionEnabled`，没人会去设可见性。用可点状态推导，调用方不用
         * 学第二套 API。
         */
        fun refresh(enabledOf: (String) -> Boolean) {
            val pick = members.firstOrNull { enabledOf(it.first) }?.first ?: members.first().first
            shown = pick
            members.forEach { (id, v) ->
                // INVISIBLE 而不是 GONE：见类注释
                v.visibility = if (id == pick) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    /** 所有共用槽位（目前只有「开始/停止」这一对） */
    private val actionSlots = mutableListOf<ActionSlot>()


    /** 动作 id → 它在界面上的**所有**实例，用于运行期启用/禁用 */
    private val actionButtons = HashMap<String, MutableList<ActionBtn>>()

    /**
     * 动作 id → 动作。插件在 schema 里可以用 `UiItem.value` 点名要哪个。
     *
     * 见 [HostAction] 常量的说明：能放开的只有"放哪些、放哪里、叫什么"，
     * 执行仍然归宿主。
     */
    private val hostActionMap = LinkedHashMap<String, HostAction>()

    /**
     * 这一帧 schema 里，插件**声明过**的宿主动作 id。
     *
     * 顶部那排会把它们跳过 —— 逐个覆盖，不是全有全无。
     * 每次 [render] 开头清空，解析 schema 时填充。
     */
    private val schemaDeclaredActions = HashSet<String>()

    /**
     * 顶部那排功能按钮的容器。
     *
     * 内容**不能**在 [buildActionBlock] 里一次填死：那时还不知道 schema 里
     * 插件声明了哪些动作。所以先建空容器，等 [render] 解析完再填。
     */
    private var actionRowHost: LinearLayout? = null

    /**
     * 「功能」区的**三段**（v9.1 拆分）。
     *
     * 以前是一整块，块内顺序写死：标题 → 按钮行 → 参数区 → 诊断。
     * 插件想要「开始按钮在文本下面、进度条在按钮下面」这种顺序，做不到 ——
     * 只能整块搬，搬过去还是那个固定顺序。
     *
     * 现在拆成三段，插件在自己的 schema 里各挖一个坑，宿主分别填进去：
     *
     * | `HOST_SLOT` 的 `value` | 填什么 |
     * |---|---|
     * | `actions` | 「功能」小标题 + 按钮行 + 动作说明 + 状态提示 |
     * | `params` | 宿主的试听参数区（目标文本 / 模式 / 参考音频 / 逐字稿 / 种子 / 线程） |
     * | `diag` | 诊断信息（默认折叠） |
     * | 空（或不写） | 老行为：整块（三段 + 卡片背景） |
     *
     * ## 三段各自只建一次
     *
     * 参数区里是**输入框**，重建就等于清空用户正在打的字。所以三段跟
     * [actionBlock] 一样是"建一次、之后只 re-parent"，绝不在 render 里重建。
     *
     * ## 一帧只用一种模式
     *
     * 整块模式要有卡片背景、把三段装进一个外壳；分段模式要把三段分别交给
     * 三个不同的坑。同一个 View 不能挂两处，所以两者互斥 ——
     * 只要这一版 schema 里出现任何一个分段坑，就整体走分段模式。
     */
    private var partActions: LinearLayout? = null
    private var partParams: View? = null
    private var partDiag: LinearLayout? = null

    /** 整块模式的外壳（带卡片背景）。只在没有分段坑时用 */
    private var wholeBlock: LinearLayout? = null

    /** 动作说明那一行。分段/整块都在 actions 段里 */
    private var actionHelpView: TextView? = null

    /** 「功能」小标题。所有动作都被插件接管后要藏掉它 */
    private var actionBlockTitle: TextView? = null

    init {
        // 注册表：插件在 schema 里用 value 点名时查的就是它。
        // 放 init 而不是 build()，因为 render() 可能在 build 之前被调到（refresh）
        hostActions.forEach { hostActionMap[it.id] = it }
    }


    /**
     * 动作 id → **当前**标签。
     *
     * 有些按钮的文字会随状态变（「加载引擎」↔「卸载引擎」），
     * 所以文字单独存一份：只改样式时（[setActionEnabled]）不会把文字冲回初始值。
     */
    private val actionLabels = HashMap<String, String>()

    /**
     * 动作 id → **当前**启用态。
     *
     * 和 [actionLabels] 同理，是"运行期状态"，不是"初始值"。
     * [render] 会重建所有按钮，重建时必须从这份状态里取 ——
     * 否则"正在合成 → 合成按钮灰掉"会在下一次重画后变回可点。
     */
    private val actionEnabled = HashMap<String, Boolean>()

    /** 内容容器。重画时清空它再填 */
    private var host: LinearLayout? = null

    /**
     * 「功能」块的备用挂载点（标题下面那一格）。
     *
     * 插件没在 schema 里放 [UiType.HOST_SLOT] 时，功能块就挂这儿 ——
     * 也就是老行为。放在字段里是因为 [render] 每次都要判断：
     * 这一版 schema 有没有坑位。
     */
    private var topSlot: LinearLayout? = null

    /**
     * 「功能」块本体。**建一次，之后只搬不重建**（见 [build] 里的说明）。
     * 空 = 这个插件没有可用的宿主动作。
     */
    private var actionBlock: View? = null

    /** 这一帧 schema 里有没有画过 `HOST_SLOT`。每次 [render] 开头复位 */
    private var hostSlotConsumed = false

    /**
     * 这一帧已经被挖走的**分段名**（actions / params / diag）。
     *
     * 和 [hostSlotConsumed] 分开记：整块只认一个坑，分段是三个各自独立。
     * 插件写了两个 `actions` 坑时，第二个拿到 null（不画）而不是把 View
     * 从第一个坑里拽过来。
     */
    private val hostSlotTaken = HashSet<String>()

    /**
     * 这一帧是**分段模式**（有 `HOST_SLOT` 的 `value` 指向 actions/params/diag）。
     *
     * 为什么需要预扫：渲染是流式的，整块坑可能出现在分段坑**之前**，
     * 那时 `hostSlotTaken` 还是空的，判断不出"这一帧其实是分段模式"，
     * 于是会把整块搬走、后面分段坑再想搬某一段就冲突了。
     *
     * 在 [render] 开头扫一遍 `schema.items` 就没有这个问题。代价是一次
     * List 遍历 —— 和"渲染出错要不要查半天"比，可以忽略。
     */
    private var segmentMode = false

    /** 「功能」区的状态提示（试听进度、完成、失败） */
    private var actionHint: TextView? = null

    /** 状态提示的文本。重建后会用它恢复，否则进度文字会凭空消失 */
    private var actionHintText: String = ""

    /** 诊断区内容（debugInfo + 最近一条错误）。空 = 不显示这一块 */
    private var diagText: String = ""

    /**
     * 诊断区是否展开。
     *
     * 默认折叠 —— 正常使用不该看到堆栈，但排查时必须在手边：
     * 这些信息以前只进 logcat，而用户装不了 adb。
     */
    private var diagOpen: Boolean = false

    /** 诊断区的两个 View（标题行 / 正文），[setDiagnostics] 直接改它们 */
    private var diagHeadView: TextView? = null
    private var diagBodyView: TextView? = null

    /**
     * 外层滚动容器（build 时创建）。
     *
     * 存成 `ScrollView` 而不是 `View`：日志追加时要读/恢复它的 `scrollY`，
     * 不然日志内容一变，整页可能被焦点机制带走（见 [appendLog]）。
     */
    private var self: android.widget.ScrollView? = null

    /** 日志框的固定高度。见 [logBlock] 的说明：不随内容长，页面结构才稳 */
    private val LOG_HEIGHT_DP = 160

    /**
     * 拖动中的滑块不要被重画覆盖 —— 用户手还按在上面，值突然跳回去会很难受。
     *
     * 存的是正在拖的 key。`onStopTrackingTouch` 时清掉。
     */
    private var draggingKey: String? = null

    /**
     * 可折叠分组的展开状态（key = [UiItem.key]，为空时用 label）。
     *
     * 只在内存里：重启 App 回到默认展开是对的 ——
     * 用户隔一天进来，多半想直接看到参数，而不是一堆折叠条。
     */
    private val groupOpen = HashMap<String, Boolean>()

    // ==================== v4：过程与产物的状态 ====================

    /**
     * 日志框的正文控件，按 [UiItem.key] 索引。
     *
     * [patch] 靠它做原地追加 —— 不重建整页，用户滚到哪就停在哪。
     * key 为空的 item 不进这里：它没法被定位，只能靠整页刷新。
     */
    private val logBodies = HashMap<String, TextView>()

    /** 日志框的滚动容器，追加后要滚到底 */
    private val logScrolls = HashMap<String, android.widget.ScrollView>()

    /**
     * 结果图：key → ImageView + 占位文字。
     *
     * 图走 [setImage] 单独送，不经过 schema —— 和音频同一个理由（Binder 配额）。
     */
    private val imageViews = HashMap<String, android.widget.ImageView>()
    private val imagePlaceholders = HashMap<String, TextView>()

    /** 最近一张结果图。重建页面后要重新贴回去，否则切走再回来图就没了 */
    private var lastBitmap: android.graphics.Bitmap? = null

    /**
     * item.key → 它渲染出来的最外层 View（v12）。
     *
     * 用来支持 `notifyScrollTo(key)` —— 插件说"滚到这一项"，宿主得有办法
     * 从 key 找回 View。**只在 key 非空时登记**：空 key 定位不到，
     * 登记进去只会让"多个空 key 互相覆盖"。
     *
     * 每次 [render] 清空（视图全换了，旧引用必须丢 ——
     * 这是这个文件里反复出现的同一个坑）。
     */
    private val keyedViews = HashMap<String, View>()

    /**
     * 点结果图时全屏查看。由 [PluginPage] 注入（复用它已有的 ZoomImageView）。
     *
     * 不在这里自己写一套缩放逻辑：宿主已经有一份（全屏看图），
     * 两份手势代码必然会走偏（一个支持双击、一个不支持，用户会觉得"有时候能用"）。
     */
    var zoomable: ((android.graphics.Bitmap) -> Unit)? = null

    /**
     * 用户点了模型槽位的「导入」（v6）。由 [PluginPage] 注入 ——
     * 它才知道怎么弹 SAF、怎么把结果转成 FD 交给插件。
     *
     * 参数是槽位的 role（= `UiItem.key`）。
     */
    var modelPicker: ((String) -> Unit)? = null

    /** 用户确认了「清除」（v6）。参数同上。 */
    var modelDeleter: ((String) -> Unit)? = null

    /**
     * 播放器。宿主注入（[PluginPage] 里那个 MediaPlayer 的包装）。
     *
     * 为空说明宿主没提供播放能力 —— 那 [UiType.AUDIO] 会画成灰掉的假控件，
     * 而不是崩溃。
     */
    var audioHost: AudioHost? = null
        set(v) {
            field = v
            rebuildAudioBars()
        }

    /**
     * 播放器控件。重建页面时按 key 收集，[AudioHost] 的状态变化会推到它们身上。
     */
    private val audioBars = LinkedHashMap<String, AudioBar>()

    private var audioTicker: Runnable? = null

    /**
     * 把播放条那一条音频存进音乐库。
     *
     * 由 [PluginPage] 注入。参数是 `AUDIO` 条目的 key —— v7 起每条可以有
     * **自己那份**文件，所以"存哪一条"必须跟着条目的 key 走，
     * 不能只存"最近一次产物"。
     *
     * 为空 = 宿主不支持逐条保存，那时保存按钮不显示。
     */
    var audioSaver: ((key: String) -> Unit)? = null

    /**
     * 宿主的音频播放能力。由 [PluginPage] 实现，包着它那些 MediaPlayer。
     *
     * ## 为什么要按 [key] 分
     *
     * v4 时只有一个播放器、只播"宿主最近一次产物"，所以接口没有参数。
     * v7 起插件可以给每个 `AUDIO` 条目送自己那份音频（历史记录场景），
     * 于是"第 3 条"和"第 5 条"必须是两个独立播放器 —— 点第 3 条不能听到最新那条。
     *
     * ## 为什么不让插件自己播
     *
     * 插件在另一个进程里，宿主看不见也停不了它的播放器 ——
     * 用户退出页面后声音还在响，找不到地方关。
     */
    interface AudioHost {
        /** 这个 key 有没有可播的音频（插件送的那份，或宿主最近一次产物） */
        fun hasAudio(key: String): Boolean
        fun isPlaying(key: String): Boolean
        /** 当前位置（毫秒） */
        fun positionMs(key: String): Int
        /** 总长（毫秒）；没有音频返回 0 */
        fun durationMs(key: String): Int
        fun play(key: String)
        fun pause(key: String)
        fun seekTo(key: String, ms: Int)
    }

    /** 一个播放条的所有控件。[AudioHost] 变化时刷它 */
    private class AudioBar(
        val btn: TextView,
        val seek: SeekBar,
        val time: TextView,
        val empty: TextView,
        /** 这个条目的 key —— 所有 [AudioHost] 调用都靠它区分播哪份 */
        val key: String,
        /** 用户正按着滑块时不能被 tick 覆盖 */
        var dragging: Boolean = false,
        /** 「保存」小按钮；宿主没提供保存能力时为 null */
        var save: TextView? = null,
    )

    fun build(): View {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // ---- 返回 + 标题 ----
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(act).apply {
            text = "‹ 返回"
            textSize = 13f
            setTextColor(pal.primary)
            setPadding(0, dp(4), dp(12), dp(4))
            isClickable = true
            setOnClickListener { onBack() }
        }, wrapWrap())
        top.addView(TextView(act).apply {
            text = title
            textSize = 15f
            setTextColor(pal.text)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, matchWrap())

        // ---- 功能（宿主提供的动作）----
        //
        // ## v7：挂载点由插件决定
        //
        // 以前这块**写死**挂在标题下面。插件想把「开始推理」放进自己的
        // 「参数」标签页里做不到 —— schema 没有任何语法能说"把宿主那块画进我的 tab"。
        //
        // 现在：先建好但**不挂**，等 render() 看完 schema 再决定挂哪儿：
        // · 有 [UiType.HOST_SLOT] → 塞进那个坑
        // · 没有 → 挂在这个 topSlot 里（老行为，向后兼容）
        //
        // **只建一次**，之后靠 re-parent 搬家。重建的话里面那些输入框
        // （目标文本 / 逐字稿）会被换掉，用户正在打的字全没了。
        val slot = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        topSlot = slot
        root.addView(slot, matchWrap())
        // 条件里带上 hostExtra：插件可以用 `ponko.actions=""` 把按钮全关掉，
        // 但它照样需要参数区（填要合成的文本）。只看 hostActions 会让那种插件
        // 连输入框都没有 —— 整个界面变成空白页
        if (hostActions.isNotEmpty() || hostExtra != null) {
            actionBlock = buildActionBlock()
            actionBlock?.let { slot.addView(it, matchWrap().apply { topMargin = dp(10) }) }
        }

        // ---- 内容区（每次重画都重建这里）----
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        host = content
        root.addView(content, matchWrap().apply { topMargin = dp(10) })

        val sv = android.widget.ScrollView(act).apply {
            setBackgroundColor(pal.bg)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        self = sv
        render()
        return sv
    }

    /** 重新拉 schema 并重画（插件的 onSchemaChanged、或用户操作后调） */
    fun refresh() {
        // 正在拖滑块时不重画，否则手指还没松、控件就被换掉了
        if (draggingKey != null) return
        render()
    }

    /** 当前是否正在显示（用于判断要不要响应刷新） */
    fun isAttached(): Boolean = self?.parent != null

    // ==================== 渲染 ====================

    private fun render() {
        val host = host ?: return

        // 控件全要重建，这些索引指向的对象立刻作废。
        // **不清的话 patch 会写进已经脱离视图树的老控件** ——
        // 表现是"日志在涨，但屏幕上不动"，极难查。
        logBodies.clear()
        logScrolls.clear()
        patchableViews.clear()
        patchableBars.clear()
        imageViews.clear()
        imagePlaceholders.clear()
        keyedViews.clear()
        audioBars.clear()
        hostSlotConsumed = false
        hostSlotTaken.clear()
        // 视图全要换，按 key 索引的组实例必须一起丢 ——
        // 留着会让 selectTab 切到已经脱离视图树的旧页（屏幕上看不出变化）
        tabGroups.clear()
        // 按钮实例同理：旧的还在 actionButtons 里的话，setActionEnabled 会去改
        // 一个已经不在屏幕上的 View，而屏幕上那个永远是灰的
        actionButtons.clear()
        schemaDeclaredActions.clear()
        stopAudioTicker()
        host.removeAllViews()

        val schema = try {
            session.schemaOf(pkg)
        } catch (t: Throwable) {
            UiSchema(intro = "读取界面失败：${t.javaClass.simpleName}")
        }

        // 预扫一遍：这一帧是分段模式吗？（见 segmentMode 的说明）
        segmentMode = schema.items.any {
            it.type == UiType.HOST_SLOT &&
                it.value.trim().lowercase() in hostSlotSegments
        }

        if (schema.intro.isNotBlank()) {
            host.addView(hintText(schema.intro))
        }

        if (schema.items.isEmpty()) {
            // 空 schema 是合法的 —— 这个插件没提供任何可配置项。
            // 不要显示成错误：用户点进来看到空页面，本来就是这个插件本来的样子
            //
            // 但顶部按钮行**必须先填上** —— 下面直接 return 了，
            // 不填的话这个插件会一个按钮都没有，彻底不可用
            rebuildTopActionRow()
            host.addView(emptyBlock(
                "这个插件没有可配置项",
                "插件作者还没有为它提供界面内容。\n功能仍然可以通过上面的按钮直接使用。"
            ), matchWrap().apply { topMargin = dp(12) })
            return
        }

        // 分组 / 标签页要按顺序累积，所以不能简单 forEach —— 需要维护「当前往哪个容器里加」。
        //
        // ## 为什么是栈，且元素有四种形状
        //
        // `GROUP_START` / `TABS_START` / `ROW_START` / `GRID_START` 都会开一个
        // 新区块、`*_END` 关掉它，层数不固定（「页中页中页」「行里套页」「网格里套分组」
        // 都只是再套一层）。用一个栈最省事。
        //
        // 但四种区块**往里加东西的方式不同**：
        // · 分组（[Slot.Box]）只有一条内容流，直接往 body 里加
        // · 标签页（[Slot.Tab]）有多个页，要往**最后开始的那一页**里加
        //   （不是"用户正在看的那一页" —— 那两者在渲染期不是一回事，
        //    见 [TabGroup.fillTarget] 里记的那个 bug）
        // · 横向行（[Slot.Row]）用 weight 分宽度，不是 MATCH_PARENT
        // · 网格（[Slot.Grid]）还要决定"这一项该进第几行的哪一格"
        //
        // 所以栈元素不能只是一个 `LinearLayout`，而"怎么加"也不能各处自己写 ——
        // 统一走 [place]。
        val target = ArrayDeque<Slot>()
        target.addLast(Slot.Box(host))

        schema.items.forEach { item ->
            when (item.type) {
                UiType.GROUP_START -> {
                    val group = buildGroup(item)
                    place(target.last(), group.first)
                    target.addLast(Slot.Box(group.second))
                }
                UiType.GROUP_END -> {
                    // 只有一个元素时弹出去会把根也弹掉 —— 插件写错了也不能崩
                    if (target.size > 1) target.removeLast()
                }
                UiType.TABS_START -> {
                    // 深度 = 栈里已经有几层标签页。用数出来的，不用计数器：
                    // 插件少写/多写 TABS_END 时计数器会永久偏掉
                    val depth = target.count { it is Slot.Tab }
                    // 最外层（depth==0）**且**在最顶层（栈里只有根）才做成可滑动：
                    // 嵌在分组里的 pager 横向手势会和纵向滚动打架
                    val swipeable = depth == 0 && target.size == 1
                    val tabs = buildTabGroup(item, depth, swipeable)
                    place(target.last(), tabs.view)
                    target.addLast(Slot.Tab(tabs))
                }
                UiType.TAB -> {
                    // TAB 只对**最内层**那个标签页组有效。
                    // 不在 TABS 里出现的 TAB 是插件写错了 —— 忽略，不崩
                    val tab = target.lastOrNull() as? Slot.Tab
                    if (tab != null) tab.group.beginTab(item) else {
                        android.util.Log.w("PonkoPluginUi", "TAB 不在 TABS_START 内，已忽略：${item.label}")
                    }
                }
                UiType.TABS_END -> {
                    // 只在栈顶**确实是标签页组**时才弹。
                    // 插件多写一个 TABS_END 时若无条件 pop，会把外面的分组弹掉，
                    // 后面所有内容都会跑到更外层 —— 那种错位比"忽略多余结束标记"难查得多
                    val top = target.lastOrNull()
                    if (top is Slot.Tab) {
                        top.group.finish()
                        target.removeLast()
                    } else {
                        android.util.Log.w("PonkoPluginUi", "多余的 TABS_END，已忽略")
                    }
                }
                UiType.ROW_START -> {
                    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                    place(target.last(), row)
                    target.addLast(Slot.Row(row, parseWeights(item.value), dp(8)))
                }
                UiType.ROW_END -> {
                    // 只弹 Row：插件多写一个 ROW_END 时若无条件 pop，
                    // 会把外面的分组弹掉，后面内容全跑到更外层 —— 那种错位比
                    // "忽略多余结束标记"难查得多（TABS_END 同理）
                    if (target.lastOrNull() is Slot.Row) target.removeLast()
                    else android.util.Log.w("PonkoPluginUi", "多余的 ROW_END，已忽略")
                }
                UiType.GRID_START -> {
                    val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                    place(target.last(), grid)
                    // 列数：空/写错 → 2；上限 4（5 列以上每格不到 60dp，文字全被吃掉）
                    val cols = item.value.trim().toIntOrNull()?.coerceIn(1, 4) ?: 2
                    target.addLast(Slot.Grid(grid, cols, dp(8)))
                }
                UiType.GRID_END -> {
                    val top = target.lastOrNull()
                    if (top is Slot.Grid) {
                        top.finish()          // 补最后一行占位，见 Grid.finish
                        target.removeLast()
                    } else {
                        android.util.Log.w("PonkoPluginUi", "多余的 GRID_END，已忽略")
                    }
                }
                UiType.HOST_SLOT -> {
                    // v7：插件挖的坑，把宿主功能块搬进来。
                    // v9.1：value 可以指定分段（actions / params / diag），
                    //       让插件把三段分散到不同位置（"开始按钮在文本下面、
                    //       进度条在按钮下面"）。空 = 老行为，整块。
                    //
                    // 返回 null 的两种情况都**静默不画**：这一帧已经被别的坑占走了，
                    // 或者这一段本就不存在（比如这个插件没有参数区）
                    val blk = takeHostSlot(item.value)
                    if (blk != null) {
                        (blk.parent as? ViewGroup)?.removeView(blk)
                        // 走 place 而不是直接 addView：在 Row / Grid 里要按权重放，
                        // 而且那里不能加 topMargin（纵向容器的语义）
                        place(target.last(), blk)
                    }
                }
                else -> {
                    val v = renderItem(item) ?: return@forEach
                    // DIVIDER / SPACER 自带尺寸，别让 place 用 WRAP_CONTENT 覆盖
                    val keep = item.type == UiType.DIVIDER || item.type == UiType.SPACER
                    place(target.last(), v, keepSize = keep)
                    // 登记给 scrollTo(key) 用。空 key 不登记：定位不到，
                    // 而且多个空 key 会互相覆盖
                    if (item.key.isNotBlank()) keyedViews[item.key] = v
                }
            }
        }

        // 功能块的去向，三种情况：
        //   · 整块坑被挖（hostSlotConsumed）→ 已经在插件指定的位置
        //   · 分段坑被挖（hostSlotTaken 非空）→ 被挖走的段在插件位置，
        //     **没被挖走的段仍要回到顶部**（否则参数区会凭空消失，用户填不了文本）
        //   · 都没挖 → 整块回顶部
        //
        // 少了这一步，上一帧搬走的块会跟着那个已脱离视图树的父容器一起消失
        if (actionBlock != null && !hostSlotConsumed) ensureActionBlockAtTop()

        // 插件可能漏写 *_END —— 那些区块在这里收尾。从内到外弹：
        // 内层先收尾，再收外层（外层的高度依赖内层）
        //
        // 三种收尾各不相同：
        // · Tab  → finish()：藏掉空标签条、按 key 恢复上次那页、给 pager 定高
        // · Grid → finish()：最后一行不满时补透明占位，否则已填的格子会被拉满整行
        // · Row  / Box → 无需收尾（内容已经加进去了）
        while (target.size > 1) {
            when (val top = target.removeLast()) {
                is Slot.Tab -> top.group.finish()
                is Slot.Grid -> top.finish()
                else -> Unit
            }
        }

        // 顶部那排要**最后**填：schemaDeclaredActions 是上面渲染时攒出来的，
        // 提前填会把插件已经接管的动作又画一遍（同一个按钮出现两次）
        rebuildTopActionRow()

        // 重画完了：播放条要立刻反映当前播放状态（否则切回页面时显示成「▶」却在响）
        refreshAudioBars()
        startAudioTicker()

        // 这里**不**恢复滚动位置（2026-10-23 删）。
        //
        // 曾经有一段 `post { scrollTo(0, keepScrollY) }`，想修"点按钮后页面跳走"。
        // 那是个错误的方向，两个理由：
        //
        // 1. **时机不可靠**：`post` 只保证"下一帧的消息队列"，不保证
        //    measure/layout 已经完成。那一刻子 View 高度还是 0，
        //    `scrollTo` 会被 clamp 到 0，反而把位置改错了 —— 用户看到的
        //    "跳到加载按钮那里"就是它干的。
        // 2. **更根本：这不归宿主管**。重画之后停在哪，是页面作者的决定；
        //    宿主替它选一个位置，就是替它做决定。
        //
        // 删掉之后反而正确：`removeAllViews()` 只 requestLayout 不立即
        // layout，同一帧内 `addView` 完内容已恢复，帧末 layout 时
        // `mScrollY` 保持原值 —— **Android 自己就把位置保住了**，
        // 不需要我干预。
        //
        // 插件若确实要控制滚动，用 `notifyScrollTo(key)` 明说（v12）。
    }

    // ==================== 功能区（宿主提供）====================

    /**
     * 功能块回到标题下面（老位置）。已经在就不动，避免无谓的重排。
     *
     * ## 分段模式下的"残余段"
     *
     * 插件可能只挖了 `actions` 和 `diag` 两个坑、没挖 `params` ——
     * 那参数区没有任何地方可去，但**绝不能消失**（用户要靠它填文本）。
     *
     * 所以这里把「这一帧没被挖走的段」按固定顺序装回整块外壳放顶部；
     * 全被挖走了就把外壳藏起来（否则顶部留一个空卡片）。
     *
     * `addView` 会自动把 View 从原父容器摘掉，所以不用手动 remove ——
     * 但要按 `actions / params / diag` 的固定顺序装，不能沿用上一次的顺序
     * （上一次可能是"被拿走又还回来"的状态，顺序已经乱了）。
     */
    private fun ensureActionBlockAtTop() {
        val blk = actionBlock ?: return
        val slot = topSlot ?: return

        if (blk is LinearLayout) {
            val keep = listOfNotNull(
                partActions?.takeIf { "actions" !in hostSlotTaken },
                partParams?.takeIf { "params" !in hostSlotTaken },
                partDiag?.takeIf { "diag" !in hostSlotTaken },
            )
            // 顺序已经对就什么都不做 —— 每次 render 都重装会丢输入框焦点
            var same = blk.childCount == keep.size
            if (same) {
                keep.forEachIndexed { i, v -> if (blk.getChildAt(i) !== v) same = false }
            }
            if (!same) {
                blk.removeAllViews()
                keep.forEach { blk.addView(it, matchWrap()) }
            }
            // 全被挖走了：外壳留着只会是个空卡片
            blk.visibility = if (keep.isEmpty()) View.GONE else View.VISIBLE
            if (keep.isEmpty()) return
        }

        if (blk.parent === slot) return
        (blk.parent as? ViewGroup)?.removeView(blk)
        slot.addView(blk, matchWrap().apply { topMargin = dp(10) })
    }

    /**
     * 重建**顶部那排**功能按钮（v9）。
     *
     * ## 为什么每次 render 都要重建
     *
     * 因为"哪些还留在顶部"取决于这一版 schema 里插件接管了哪些动作。
     * 插件可能上一版声明了 `reasoning`、这一版删掉了 —— 那它得回到顶部，
     * 否则用户就再也点不到「合成」了。
     *
     * ## 等宽均分，超三个折两行
     *
     * 原来是 `WRAP_CONTENT` + 左右各 16dp 内边距 + 8dp 间距。宽度一超，
     * 最后的按钮就被裁掉（实测第 4 个「保存」少 46dp，文字显示不全）——
     * 而横向 LinearLayout **不会**因为放不下就换行，它直接把尾巴切掉。
     *
     * 现在按等权重分，文案超长单行省略。四个以上折两行：
     * 5 个挤一行每个只剩 50dp，不如两行平均。
     */
    /**
     * 哪两个动作共用一格。见 [ActionSlot] 的说明。
     *
     * 只有「开始/停止」这一对：它们**互斥**（没在合成时只有开始有意义，
     * 合成中只有停止有意义），并排放着必然有一个是废按钮。
     *
     * 不放进来的一对反例：`play` 和 `save` 可以同时可用
     * （有产物时两个都能点），合并会让用户以为一次只能干一件事。
     */
    private val sharedSlots = listOf(
        com.litertchat.plugin.HostActionId.REASONING to
            com.litertchat.plugin.HostActionId.STOP,
    )

    /** 这一格能不能合并：两个动作都在、都还在顶部（没被插件搬走） */
    private fun pairInRow(
        first: String,
        second: String,
        visible: List<HostAction>,
    ): Pair<HostAction, HostAction>? {
        val a = visible.firstOrNull { it.id == first } ?: return null
        val b = visible.firstOrNull { it.id == second } ?: return null
        return a to b
    }

    private fun rebuildTopActionRow() {
        val host = actionRowHost ?: return
        host.removeAllViews()
        actionSlots.clear()

        val top = hostActions.filter { it.id !in schemaDeclaredActions }
        // 全被接管了就藏掉「功能」小标题，否则标题下面空着、紧跟一行提示，像坏了
        actionBlockTitle?.visibility = if (top.isEmpty()) View.GONE else View.VISIBLE
        if (top.isEmpty()) return

        // 先把能合并的成对动作挑出来，它们占**一格**而不是两格 ——
        // 这会影响分行的数量，所以必须在算行数之前定下来
        val paired = LinkedHashMap<String, Pair<HostAction, HostAction>>()
        sharedSlots.forEach { (first, second) ->
            pairInRow(first, second, top)?.let { paired[first] = it }
        }
        val consumed = paired.values.flatMap { listOf(it.first.id, it.second.id) }.toSet()

        // 排版单元：要么单独一个动作，要么一个合并槽
        val cells = buildList {
            top.forEach { action ->
                when {
                    action.id in consumed -> {
                        // 只在自己是这一对里的**第一个**时加入，避免加两次
                        paired[action.id]?.let { add(Cell.Merged(it)) }
                    }
                    else -> add(Cell.Single(action))
                }
            }
        }
        if (cells.isEmpty()) return

        val perRow = if (cells.size <= 3) cells.size else (cells.size + 1) / 2
        var row = newActionRow()
        var inRow = 0
        cells.forEachIndexed { i, cell ->
            if (i > 0 && i % perRow == 0) {
                host.addView(row, matchWrap().apply { topMargin = dp(8) })
                row = newActionRow()
                inRow = 0
            }
            val cellView = when (cell) {
                is Cell.Single -> makeHostActionButton(
                    action = cell.action,
                    label = cell.action.label,
                    compact = true,
                    primary = true,      // 顶部那排本来就是主色按钮
                )
                is Cell.Merged -> makeActionSlot(cell.a.first, cell.a.second)
            }
            row.addView(cellView, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { if (inRow > 0) leftMargin = dp(8) })
            inRow++
        }
        host.addView(row, matchWrap().apply { topMargin = dp(6) })
    }

    /**
     * 按钮行里的一个排版单元。
     *
     * 第二个分支**不能叫 `Pair`** —— 那会遮蔽 `kotlin.Pair`，
     * 里面再用 `Pair<HostAction, HostAction>` 就会解析成自己（无限递归的
     * "No type arguments expected"）。叫 `Merged`。
     */
    private sealed class Cell {
        class Single(val action: HostAction) : Cell()
        class Merged(val a: Pair<HostAction, HostAction>) : Cell()
    }

    /**
     * 造一个合并槽（「开始 ⇄ 停止」）。
     *
     * 两个按钮都 `MATCH_PARENT` 叠在 `FrameLayout` 里，靠可见性交替 ——
     * 细节和"为什么不用 GONE"见 [ActionSlot]。
     */
    private fun makeActionSlot(first: HostAction, second: HostAction): View {
        val slot = android.widget.FrameLayout(act)
        val views = listOf(first, second).map { a ->
            // compact=true：和同一行里其它按钮一个样式。
            // 叠放时两个按钮都要 MATCH_PARENT，宽度由槽位 weight 决定
            a.id to makeHostActionButton(
                action = a,
                label = a.label,
                compact = true,
                primary = true,
            )
        }
        views.forEach { (_, v) ->
            slot.addView(v, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val s = ActionSlot(slot, views)
        actionSlots.add(s)
        s.refresh { id -> actionEnabled[id] ?: (id == first.id) }
        return slot
    }

    /**
     * 造一个宿主动作按钮。顶部那排（`compact = true`）和插件页里
     * （`compact = false`，全宽）都用它 —— 好让"点下去发生什么"只有一份实现。
     *
     * **enabled 一律取 `action.enabled`**（宿主的实时状态），不看 `UiItem.enabled`：
     * 插件不知道引擎加载到几成、是不是正在合成。让它说了算会出现
     * "插件说能点、宿主拒绝"的死路。
     */
    private fun makeHostActionButton(
        action: HostAction,
        label: String,
        compact: Boolean,
        primary: Boolean,
        /** true = 用传进来的 [label]（插件显式声明了文案）；false = 缓存优先（顶部那排） */
        useGivenLabel: Boolean = false,
    ): TextView {
        // 运行期状态优先于初始值：render 会重建按钮，这时"正在合成所以不可点"
        // 必须还在（见 actionEnabled 的说明）。
        // 文案则看调用方：插件显式写了 label 就以它为准（它有权决定按钮叫什么），
        // 顶部那排用缓存 —— 那里读的是 [setActionLabel] 改过的文案
        val enabled = actionEnabled[action.id] ?: action.enabled
        val shownLabel = if (useGivenLabel) label else (actionLabels[action.id] ?: label)
        val btn = TextView(act).apply {
            gravity = Gravity.CENTER
            textSize = if (compact) 13f else 14f
            setPadding(
                if (compact) dp(6) else dp(14),
                if (compact) dp(9) else dp(11),
                if (compact) dp(6) else dp(14),
                if (compact) dp(9) else dp(11),
            )
            if (compact) {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            text = shownLabel
            styleHostAction(this, enabled, compact, primary)
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                android.util.Log.i("PonkoPluginUi", "功能动作：${action.id}")
                try {
                    action.run()
                } catch (t: Throwable) {
                    android.util.Log.e("PonkoPluginUi", "功能动作失败：${action.id}", t)
                    setActionHint("出错了：${t.javaClass.simpleName}")
                }
            }
        }
        actionButtons.getOrPut(action.id) { mutableListOf() }
            .add(ActionBtn(btn, compact, primary))
        actionLabels[action.id] = shownLabel
        return btn
    }

    /**
     * 宿主动作按钮的配色。
     *
     * @param compact 顶部那排（等宽小按钮，实心主色）
     *                对比 插件页里的（全宽，`primary` 决定实心还是浅底）
     */
    private fun styleHostAction(
        tv: TextView,
        enabled: Boolean,
        compact: Boolean,
        primary: Boolean,
    ) {
        tv.isEnabled = enabled
        tv.isClickable = enabled
        if (!enabled) {
            tv.setTextColor(pal.hint)
            tv.background = rounded(pal.fieldBg, 10)
            tv.alpha = 0.9f
            return
        }
        val solid = compact || primary
        tv.setTextColor(if (solid) pal.surface else pal.primary)
        tv.background = rounded(if (solid) pal.primary else pal.primarySoft, 10)
        tv.alpha = 1f
    }


    /**
     * 「功能」区：宿主自己的动作按钮 + 可选的参数区 + 一行状态提示 + 诊断区。
     *
     * ## 为什么这块由宿主画
     *
     * 这些动作的执行者是**宿主**（发起合成、播放音频、路由到别的页面），
     * 插件只是被动接受一次 `synthesize` 调用。
     *
     * ## v9 起：按钮**放哪里**交给插件
     *
     * 但"画在标题下面"这件事不该由宿主规定死。插件可以在 schema 里用
     * `UiItem(type = ACTION, value = HostActionId.REASONING)` 点名，
     * 那个按钮就画到它指定的位置（含 tab 里）—— 见 [rebuildTopActionRow]。
     *
     * 所以这里的按钮行是**空容器**：真正填什么要等 [render] 解析完 schema
     * 才知道哪些被插件接管了。
     */
    private fun buildActionBlock(): View {
        ensureActionParts()

        // 整块模式：三段装进一个带卡片背景的外壳。
        // 分段模式不用它 —— 那种情况下三段会被插件分别搬到自己的坑里
        val block = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
            background = rounded(pal.surface, 12)
        }
        partActions?.let { block.addView(it, matchWrap()) }
        partParams?.let { block.addView(it, matchWrap().apply { topMargin = dp(10) }) }
        partDiag?.let { block.addView(it, matchWrap()) }
        wholeBlock = block
        return block
    }

    /**
     * 建三段（**只建一次**）。
     *
     * 参数区里有输入框，重建就等于清空用户正在打的字 —— 这是这个文件里
     * 反复出现的同一个坑（actionBlock、tabGroups、日志框都是）。
     * 所以这里用 `if (partActions != null) return` 一次性守住。
     */
    private fun ensureActionParts() {
        if (partActions != null) return

        // ---- 1) actions 段：标题 + 按钮行 + 说明 + 状态提示 ----
        val actions = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val titleView = TextView(act).apply {
            text = "功能"
            textSize = 13f
            setTextColor(pal.subText)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(2))
        }
        actionBlockTitle = titleView
        actions.addView(titleView, matchWrap())

        // 按钮行：先建空容器，内容在 render 里填（那时才知道哪些动作被插件接管）
        actionRowHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(actionRowHost, matchWrap())

        val helpText = hostActions.firstOrNull { it.help.isNotBlank() }?.help
        if (!helpText.isNullOrBlank()) {
            val help = TextView(act).apply {
                text = helpText
                textSize = 11f
                setTextColor(pal.hint)
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            actionHelpView = help
            actions.addView(help, matchWrap())
        }

        // 状态提示（"正在合成…"）。**必须常驻**：setActionHint 持有它的引用，
        // 每次 render 换一个新的会让提示永远显示不出来
        val hint = TextView(act).apply {
            text = actionHintText
            textSize = 12f
            setTextColor(pal.subText)
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = if (actionHintText.isEmpty()) View.GONE else View.VISIBLE
        }
        actionHint = hint
        actions.addView(hint, matchWrap())
        partActions = actions

        // ---- 2) params 段：宿主的试听参数区 ----
        partParams = hostExtra?.let { builder ->
            try {
                builder()
            } catch (t: Throwable) {
                // 参数区画不出来不该让整页空白 —— 页面本身还得能用
                android.util.Log.e("PonkoPluginUi", "参数区构建失败", t)
                null
            }
        }

        // ---- 3) diag 段：诊断（默认折叠）----
        //
        // 内容来自 ResultBundle.debugInfo 和最后一条 PluginError.detail。
        // 默认收起、且**先建成 GONE** —— 结果回来的时候要用 setDiagnostics
        // 直接把它显示出来，不能等下次进页面才出现（那时候用户已经拿不到上下文了）。
        // 这些信息以前只进 logcat，而普通用户装不了 adb。
        val diag = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val diagHead = TextView(act).apply {
            text = if (diagOpen) "诊断信息 \u25be" else "诊断信息 \u25b8"
            textSize = 11f
            setTextColor(pal.subText)
            setPadding(0, dp(10), 0, 0)
            isClickable = true
            visibility = if (diagText.isEmpty()) View.GONE else View.VISIBLE
        }
        val diagBody = TextView(act).apply {
            text = diagText
            textSize = 11f
            setTextColor(pal.hint)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(pal.fieldBg, 8)
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)      // 长按可复制，方便贴出来
            visibility = if (diagOpen && diagText.isNotEmpty()) View.VISIBLE else View.GONE
        }
        diagHead.setOnClickListener {
            diagOpen = !diagOpen
            diagHead.text = if (diagOpen) "诊断信息 \u25be" else "诊断信息 \u25b8"
            diagBody.visibility = if (diagOpen) View.VISIBLE else View.GONE
        }
        diagHeadView = diagHead
        diagBodyView = diagBody
        diag.addView(diagHead, matchWrap())
        diag.addView(diagBody, matchWrap().apply { topMargin = dp(4) })
        partDiag = diag
    }

    /** `HOST_SLOT` 的合法分段名 */
    private val hostSlotSegments = setOf("actions", "params", "diag")

    /**
     * 取一段给 `HOST_SLOT` 挂。`value` 空 = 整块。
     *
     * **只认第一个**（和整块模式一致）：同一段搬两处会把 View 从上一个坑里
     * 拽走，表现为"上面那个坑空了"。
     */
    /**
     * 取一段给 `HOST_SLOT` 挂。`value` 空 = 整块。
     *
     * **只认第一个**（和整块模式一致）：同一段搬两处会把 View 从上一个坑里
     * 拽走，表现为"上面那个坑空了"。
     *
     * ## 整块和分段互斥
     *
     * 这一帧只要有**任何**分段坑，整块坑就返回 null（见 [segmentMode]）。
     * 渲染是流式的，整块坑可能出现在分段坑之前 —— 靠"已经取过"判断不出来，
     * 所以必须在 [render] 开头预扫一遍。
     */
    private fun takeHostSlot(segment: String): View? {
        val seg = segment.trim().lowercase()

        if (seg.isEmpty()) {
            if (segmentMode) {
                android.util.Log.w(
                    "PonkoPluginUi",
                    "这一版 schema 同时有整块和分段 HOST_SLOT，只保留分段（整块已忽略）"
                )
                return null
            }
            if (hostSlotConsumed) return null
            hostSlotConsumed = true
            return wholeBlock
        }

        if (seg !in hostSlotSegments) {
            // 写错的分段名：**按整块处理**比什么都不画好 ——
            // 用户至少还能看到按钮，而"点了没反应的空洞"最难查
            android.util.Log.w(
                "PonkoPluginUi",
                "HOST_SLOT 的分段名 '" + seg + "' 不认识（可用：" + hostSlotSegments + "），按整块处理"
            )
            if (segmentMode || hostSlotConsumed) return null
            hostSlotConsumed = true
            return wholeBlock
        }

        if (!hostSlotTaken.add(seg)) return null
        return when (seg) {
            "actions" -> partActions
            "params" -> partParams
            else -> partDiag
        }
    }

    /** 功能按钮的一行。按钮按 `weight = 1` 均分宽度，见 [rebuildTopActionRow] 里的说明 */
    private fun newActionRow(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * 运行期启用/禁用某个功能按钮。
     *
     * 典型用途：「试听」在生成中要灰掉（否则用户会连点，插件回 BUSY），
     * 「停止」反过来只在生成中可点（平时点了没意义）。
     */
    fun setActionEnabled(id: String, enabled: Boolean) {
        // 先记账**再**遍历：按钮可能此刻还没建（render 之前、或插件这一版
        // 没声明这个动作且它不在顶部），但状态必须留住 ——
        // 否则"正在合成 → 灰掉"会在下一次 render 后变回可点
        actionEnabled[id] = enabled
        // 遍历**所有**实例：同一个动作可能同时存在于顶部那排和插件声明的页面里。
        // 只改第一个的话会出现"顶部灰了、插件页里还能点"这种分裂状态
        actionButtons[id]?.forEach {
            styleHostAction(it.view, enabled, it.compact, it.primary)
        }
        // 合并槽（开始 ⇄ 停止）要跟着换显隐：宿主改 enabled 时没人会显式设可见性，
        // 所以必须在这里推导。见 [ActionSlot.refresh]
        actionSlots.forEach { slot ->
            if (slot.members.any { it.first == id }) {
                slot.refresh { k -> actionEnabled[k] ?: false }
            }
        }
    }

    /**
     * 改按钮文字（比如「加载引擎」→「卸载引擎」）。不改启用态。
     *
     * ⚠ 这会**覆盖插件自定义的文案**。宿主只在状态语义变了的时候调它
     * （目前只有 engine：加载 ↔ 卸载），所以一般不会和插件打架。
     */
    fun setActionLabel(id: String, label: String) {
        actionLabels[id] = label
        actionButtons[id]?.forEach { it.view.text = label }
    }

    /**
     * 更新功能区的状态提示。
     *
     * 传空字符串就是隐藏。宿主在试听的各个阶段调它 ——
     * **状态必须留在这个页面上**，因为按钮也在这里：
     * 把进度显示在别处（比如卡片上），用户根本不会去看。
     */
    fun setActionHint(text: String) {
        actionHintText = text
        val v = actionHint ?: return
        v.text = text
        v.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 设置诊断区内容。传空字符串就隐藏整块。
     *
     * 会在结果/错误回来时被调用 —— 那时诊断区已经建好了（GONE 状态），
     * 所以这里是**真正把它显示出来**，不需要用户退出再进一次页面。
     */
    fun setDiagnostics(text: String) {
        diagText = text
        val head = diagHeadView ?: return
        val body = diagBodyView ?: return
        head.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        body.text = text
        body.visibility = if (text.isNotEmpty() && diagOpen) View.VISIBLE else View.GONE
    }

    private fun renderItem(item: UiItem): View? = when (item.type) {
        UiType.INFO -> hintText(item.label).also { tv ->
            applyHelp(tv, item)
            // 也允许被补丁原地改。注意补丁走的是 `text = 原串`，
            // **不解析** `**粗体**` / `` `代码` `` —— 需要格式就别用补丁
            if (item.key.isNotBlank()) patchableViews[item.key] = tv
        }
        UiType.SECTION -> sectionTitle(item.label)
        UiType.SWITCH -> switchRow(item)
        UiType.SLIDER -> sliderRow(item)
        UiType.CHOICE -> choiceBlock(item)
        UiType.TEXT -> textRow(item, multiLine = false)
        UiType.TEXTAREA -> textRow(item, multiLine = true)
        UiType.ACTION -> actionRow(item)
        UiType.PROGRESS -> progressRow(item)
        UiType.STATUS -> statusRow(item)
        // ---- v4：看得见的过程与产物 ----
        UiType.LOG -> logBlock(item)
        UiType.IMAGE -> imageBlock(item)
        UiType.AUDIO -> audioBar(item)
        // ---- v6：模型文件槽位 ----
        UiType.MODEL_SLOT -> modelSlotRow(item)
        // ---- v10：纯排版 ----
        UiType.DIVIDER -> dividerView()
        UiType.SPACER -> spacerView(item)
        // 插件用了宿主还不认识的控件。
        //
        // **这是跨版本兼容的关键分支**：绝不能崩、也不能整页不显示 ——
        // 降级成一个可读的文字行，用户至少知道"这里本来有东西，需要更新 Ponko"。
        // 插件作者也可以据此提示用户升级。
        else -> unknownItem(item)
    }

    // ==================== v2 新增控件 ====================

    /**
     * 只读进度条。[UiItem.value] 是 0–100 的百分比。
     *
     * 为什么值得单独一个控件：下载 2.8GB 模型时如果界面一动不动，
     * 用户会以为卡死并强杀进程 —— 那是最糟的结局。
     */
    private fun progressRow(item: UiItem): View {
        val pct = item.value.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
            alpha = if (item.enabled) 1f else 0.45f
        }
        val labelRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(TextView(act).apply {
            text = item.label
            textSize = 13f
            setTextColor(pal.text)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        labelRow.addView(TextView(act).apply {
            text = "${pct.toInt()}%"
            textSize = 12f
            setTextColor(pal.primary)
            // 供 onUiPatch 原地改（进度是最高频的补丁，必须便宜）
            if (item.key.isNotBlank()) patchableViews[item.key] = this
        }, wrapWrap())
        box.addView(labelRow, matchWrap())

        // 用 ProgressBar 但去掉自带背景 —— 默认样式是 Material 的圆形/彩色，
        // 和 Ponko 的扁平风格不搭，所以自己设 progressTint
        val bar = android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = pct.toInt()
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                progressTintList = android.content.res.ColorStateList.valueOf(pal.primary)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(pal.divider)
            }
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(bar, matchWrap())
        if (item.key.isNotBlank()) patchableBars[item.key] = bar
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        return box
    }

    /** 只读状态行：左 label、右 value、灰底 —— 「键 → 值」一眼能扫 */
    private fun statusRow(item: UiItem): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(pal.fieldBg, 9)
            alpha = if (item.enabled) 1f else 0.55f
        }
        row.addView(TextView(act).apply {
            text = item.label
            textSize = 12f
            setTextColor(pal.subText)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(act).apply {
            text = item.value
            textSize = 12f
            setTextColor(pal.text)
            // 供 onUiPatch 原地改（「模型：已加载」这类状态会被插件频繁更新）
            if (item.key.isNotBlank()) patchableViews[item.key] = this
        }, wrapWrap())
        // 状态行自带上下间距；help 放在外面会挤，所以拼进同一个容器
        if (item.help.isBlank()) return row
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(2))
            addView(row, matchWrap())
            addView(smallHint(item.help), matchWrap())
        }
    }

    /**
     * 可折叠分组。返回 (标题行, 内容容器)。
     *
     * 折叠状态按键存在内存里：切 tab、重新拉 schema 都不会丢。
     * **默认展开** —— 用户点进来就该看到内容。
     */
    private fun buildGroup(item: UiItem): Pair<View, LinearLayout> {
        val key = item.key.ifBlank { item.label }
        // 默认展开，但插件可以说"这一组默认收起"：GROUP_START 的 value 写 "collapsed"。
        //
        // 之前 `value` 对 GROUP_START **完全没被读取**，插件没有任何表达方式 ——
        // 参数十几个的插件只能把用户丢进一屏长滚动里。
        //
        // 用户手动折过之后以 `groupOpen` 为准：那是他的选择，不该被下一次
        // schema 刷新顶回默认值。
        val open = groupOpen[key] ?: !item.value.trim().equals("collapsed", ignoreCase = true)

        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (open) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(4))
        }

        val arrow = TextView(act).apply {
            text = if (open) "▾" else "▸"
            textSize = 12f
            setTextColor(pal.subText)
            setPadding(dp(6), 0, 0, 0)
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            setOnClickListener {
                val wasOpen = body.visibility == View.VISIBLE
                groupOpen[key] = !wasOpen
                arrow.text = if (wasOpen) "▸" else "▾"
                animateGroup(body, expand = !wasOpen)
            }
        }
        head.addView(TextView(act).apply {
            text = item.label
            textSize = 13f
            setTextColor(pal.subText)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow, wrapWrap())

        // 标题和内容一起返回，调用方依次加进父容器 —— 这样折叠的是 body 一块
        val wrapper = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(head, matchWrap())
            addView(body, matchWrap())
        }
        return wrapper to body
    }

    /**
     * 折叠/展开的**高度动画**。
     *
     * ## 为什么要动高度而不是只 swap visibility
     *
     * 只切 `visibility` 的话，下面所有内容会瞬间跳一整块高度 ——
     * 眼睛跟不上，用户会怀疑自己点错了东西。关于页那个弹层用的是
     * `alpha + scaleY`，但那是**浮层**，可以靠缩放糊过去；
     * 分组展开是**撑开文档流**的，位置必须真的从 0 长到真实高度。
     *
     * ## 实现要点
     *
     * · 展开：`height 0 → measuredHeight`。先把 body 设成可测（VISIBLE +
     *   height=0），量完再交给 ValueAnimator 逐帧改 `layoutParams.height`
     * · 收起：`measuredHeight → 0`，动画结束才 `GONE`（并清掉固定高度，
     *   让它回到 wrap_content —— 不清的话下次内容变长还是老高度）
     * · 用 `height = 0` 而不是 `alpha`，动画期间内容会被裁切，那正是要的效果
     */
    private fun animateGroup(body: View, expand: Boolean) {
        val lp = body.layoutParams ?: return
        val from: Int
        val to: Int
        if (expand) {
            // 先量真实高度：临时放开高度约束测一次
            body.visibility = View.VISIBLE
            lp.height = 0
            body.layoutParams = lp
            body.measure(
                View.MeasureSpec.makeMeasureSpec(
                    (body.parent as? View)?.width ?: act.resources.displayMetrics.widthPixels,
                    View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            from = 0
            to = body.measuredHeight
            if (to <= 0) {
                // 量不出高度（父容器还没布局）→ 直接显出来，别卡在 0
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                body.layoutParams = lp
                return
            }
        } else {
            from = body.height
            to = 0
        }

        val anim = android.animation.ValueAnimator.ofInt(from, to)
        anim.duration = if (expand) 180L else 140L
        // 展开用减速（内容"落下"）、收起用加速（"被吸走"），方向感和关于页一致
        anim.interpolator = if (expand)
            android.view.animation.DecelerateInterpolator()
        else
            android.view.animation.AccelerateInterpolator()
        anim.addUpdateListener { a ->
            val l = body.layoutParams ?: return@addUpdateListener
            l.height = a.animatedValue as Int
            body.layoutParams = l
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                val l = body.layoutParams ?: return
                if (expand) {
                    // 展开完还要能跟着内容长（日志、图片之类会变高）
                    l.height = ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    l.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    body.visibility = View.GONE
                }
                body.layoutParams = l
            }
        })
        anim.start()
    }

    /**
     * 宿主不认识的控件类型的降级显示。
     *
     * 这条分支决定了**插件的向前兼容**：新插件配老宿主时，
     * 不能让整页崩掉或留白，要让人看懂"这里本来有个东西"。
     */
    private fun unknownItem(item: UiItem): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(pal.fieldBg, 9)
            alpha = 0.75f
        }
        box.addView(TextView(act).apply {
            text = item.label.ifBlank { "（未命名项）" }
            textSize = 12f
            setTextColor(pal.subText)
        }, matchWrap())
        if (item.value.isNotBlank()) {
            box.addView(TextView(act).apply {
                text = item.value
                textSize = 12f
                setTextColor(pal.text)
                setPadding(0, dp(3), 0, 0)
            }, matchWrap())
        }
        box.addView(smallHint("这个控件需要更新 Ponko 才能显示（类型 ${item.type}）"), matchWrap())
        return box
    }

    // ==================== v6：模型文件槽位 ====================

    /**
     * 一个模型槽位：「槽位名 · 当前状态 · [导入] [清除]」。
     *
     * ## 为什么选择器必须宿主弹
     *
     * 插件是 bound Service（另一个进程）：
     * · `startActivityForResult` 是 Activity 的 API，Service 根本没有
     * · Android 10+ 禁止后台启动 Activity —— 用户点「导入」那一刻插件不在前台，
     *   真发起了也会被系统拦掉
     *
     * 所以这里是宿主画按钮、宿主弹 SAF、宿主把 FD 转给插件。
     *
     * ## 「已导入」的状态谁说了算
     *
     * **插件。** [UiItem.value] 是插件报告的状态文字（如 `"2.83 GB"`）——
     * 宿主不去查插件目录（那是别的包，本来就读不到）。
     * 导入完成后插件调 `notifySchemaChanged()`，这个值就变了。
     *
     * ## 什么时候不画按钮
     *
     * 插件 SDK 低于 v6 时根本不该有这个类型（老插件不认识它）；
     * 万一出现了，也只画一行文字、不画按钮 —— 按了也没人接。
     */
    private fun modelSlotRow(item: UiItem): View {
        val role = item.key
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            alpha = if (item.enabled) 1f else 0.5f
        }

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(pal.fieldBg, 9)
        }

        // 左边：槽位名 + 状态
        val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(act).apply {
            text = item.label.ifBlank { role }
            textSize = 13f
            setTextColor(pal.text)
        }, matchWrap())
        val stateTv = TextView(act).apply {
            text = item.value.ifBlank { "未导入" }
            textSize = 11f
            setTextColor(if (item.value.isBlank()) pal.hint else pal.primary)
            setPadding(0, dp(2), 0, 0)
        }
        left.addView(stateTv, matchWrap())
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 只有插件真的能接 FD 时才画按钮 —— 画了没人接比不画更糟
        if (role.isBlank() || !session.supportsModelImport(pkg)) {
            box.addView(row, matchWrap())
            if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
            return box
        }

        val importBtn = slotButton("导入", primary = item.value.isBlank()) {
            modelPicker?.invoke(role)
        }
        row.addView(importBtn, wrapWrap())

        // 已经导入了才显示「清除」—— 空槽位上摆个删除按钮没有意义，
        // 而且容易误点（用户会以为那个按钮是"导入"）
        if (item.value.isNotBlank()) {
            val clearBtn = slotButton("清除", primary = false) {
                confirmDeleteModel(role, item.label.ifBlank { role })
            }
            row.addView(clearBtn, wrapWrap().apply { leftMargin = dp(8) })
        }

        // 引擎运行中（插件把 enabled 设成 false）→ 两个按钮都不可点
        if (!item.enabled) {
            importBtn.isEnabled = false
            importBtn.alpha = 0.5f
        }

        box.addView(row, matchWrap())
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        return box
    }

    private fun slotButton(text: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(act).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            if (primary) {
                setTextColor(android.graphics.Color.WHITE)
                background = rounded(pal.primary, 9)
            } else {
                setTextColor(pal.primary)
                background = rounded(pal.primarySoft, 9)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 删之前问一句。
     *
     * 模型动辄几个 GB，删掉要重新导（还得再传一遍文件）。而且这个文件在
     * **插件自己的私有目录**里，用户在系统文件管理器里根本看不到、也删不了 ——
     * 误删之后他没有任何办法自己恢复，只能重新走一遍导入。
     */
    private fun confirmDeleteModel(role: String, name: String) {
        android.app.AlertDialog.Builder(act)
            .setTitle("清除模型文件")
            .setMessage("「$name」的文件会被删掉，下次要重新导入。")
            .setPositiveButton("清除") { _, _ -> modelDeleter?.invoke(role) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== v5：标签页（页中页）====================

    /**
     * 渲染栈的一层。见 [render] 里的说明：分组只有一条内容流，
     * 标签页有多个页、要往当前页里加，所以得分两种形状。
     */
    private sealed class Slot {
        /** 往哪加内容 */
        abstract fun content(): LinearLayout

        class Box(val body: LinearLayout) : Slot() {
            override fun content(): LinearLayout = body
        }

        class Tab(val group: TabGroup) : Slot() {
            // ⚠ 是 **fillTarget**，不是 currentPage —— 这两者不一样，见 fillTarget 的说明
            override fun content(): LinearLayout = group.fillTarget()
        }

        /**
         * 横向容器（v10）。
         *
         * 子项按 [weights] 分配宽度（`weight=1` 那种），不是 `MATCH_PARENT` ——
         * 否则后加的会把先加的挤成 0 宽。
         *
         * @param gapPx 子项间距。第 0 个不加左边距
         */
        class Row(
            val body: LinearLayout,
            private val weights: List<Float>,
            private val gapPx: Int,
        ) : Slot() {
            private var count = 0
            override fun content(): LinearLayout = body

            /** 下一个子项加到哪、用什么参数。份数不够时按 1 补 */
            fun next(): Pair<LinearLayout, LinearLayout.LayoutParams> {
                val w = weights.getOrElse(count) { 1f }
                val p = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, w
                )
                if (count > 0) p.leftMargin = gapPx
                count++
                return body to p
            }
        }

        /**
         * 等宽网格（v10）。
         *
         * 实现是"外层竖向 + 每满 [cols] 个就新开一行横向"，而不是
         * `android.widget.GridLayout`：
         * · GridLayout 的 `columnWeight` 只在 API 21+ 行为稳定
         * · 它按"最大子项"定列宽，几个长度不同的按钮会出现参差的格子
         *
         * 自己数行虽然土，但每个格子宽度**一定相等**，而且和 [Row] 共用
         * 同一套 `weight` 逻辑。
         */
        class Grid(
            val body: LinearLayout,
            private val cols: Int,
            private val gapPx: Int,
        ) : Slot() {
            private var row: LinearLayout? = null
            private var inRow = 0

            override fun content(): LinearLayout = body

            fun next(): Pair<LinearLayout, LinearLayout.LayoutParams> {
                if (row == null || inRow >= cols) {
                    val r = LinearLayout(body.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    body.addView(r, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        // 第二行起才有上边距。body.childCount 在 addView 前是几就代表
                        // 已有几行 —— 这里刚 new 一个 r，所以 >0 表示不是第一行
                        if (body.childCount > 0) topMargin = gapPx
                    })
                    row = r
                    inRow = 0
                }
                val p = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                if (inRow > 0) p.leftMargin = gapPx
                inRow++
                return (row ?: body) to p
            }

            /**
             * 收尾：最后一行不满时补**透明占位**，把已有的格子撑回 1/[cols] 宽。
             *
             * ## 为什么必须补
             *
             * 不补的话，最后一行只有 1 个子项时它是唯一的 `weight=1`，
             * 会被拉满整行 —— 上面几行是三列、最后一行全宽，看起来像布局坏了。
             *
             * ## 为什么占位而不是别的
             *
             * 占位是 `View`，没有背景、没有内容，视觉上就是空白，
             * 等于"剩下的格子不占位"；但它参与了 weight 计算，
             * 所以已有格子宽度正确。两者只能这样兼得。
             */
            fun finish() {
                val r = row ?: return
                if (inRow <= 0) return
                while (inRow < cols) {
                    r.addView(View(body.context), LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { if (inRow > 0) leftMargin = gapPx })
                    inRow++
                }
            }
        }
    }

    /**
     * 解析 `ROW_START` 的 `value` → 各子项的宽度份数。
     *
     * | 输入 | 结果 |
     * |---|---|
     * | `""` | 空列表 → 所有子项按 1 份（等分） |
     * | `"1,2"` | `[1f, 2f]` |
     * | `"1, 2 ,3"` | `[1f, 2f, 3f]`（容忍空格） |
     * | `"1,x,3"` | `[1f, 1f, 3f]`（坏的那一项按 1 补，不整行作废） |
     * | `"0"` / 负数 | 按 1 处理 —— weight=0 会让那个子项宽度变 0，等于藏起来 |
     *
     * 为什么要容忍坏值而不是报错：布局参数写错是插件侧最容易出的问题，
     * 而"整行按默认等分"比"整行消失"有用得多。
     */
    private fun parseWeights(value: String): List<Float> {
        val t = value.trim()
        if (t.isEmpty()) return emptyList()
        return t.split(',')
            .map { it.trim().toFloatOrNull()?.takeIf { f -> f > 0f } ?: 1f }
    }

    /**
     * 把一个渲染好的子项放进 [slot]。
     *
     * 统一入口的理由：[Slot.Box] / [Slot.Tab] 用 `MATCH_PARENT`，
     * [Slot.Row] / [Slot.Grid] 用 `weight`。如果各处自己 `addView`，
     * 加一种容器就要改一遍所有调用点 —— 那正是"每加一种布局都要动宿主"
     * 的老毛病。
     *
     * @param keepSize true = 保留控件**自己设的** layoutParams。
     *   [UiType.DIVIDER] / [UiType.SPACER] 需要它：`addView(v, params)` 会
     *   无条件替换掉 v 原有的 params，不加这个开关，spacer 设的
     *   `height = dp(n)` 会被 `WRAP_CONTENT` 冲掉、高度变成 0 ——
     *   表现是"写了 spacer 但没空隙"，而且完全看不出原因。
     */
    private fun place(slot: Slot, v: View, keepSize: Boolean = false) {
        // Row / Grid 永远用容器给的 weight，keepSize 在那里没有意义
        // （高度由内容决定、宽度由 weight 决定）
        when (slot) {
            is Slot.Box -> slot.body.addView(
                v, if (keepSize && v.layoutParams != null) v.layoutParams else matchWrap()
            )
            is Slot.Tab -> slot.content().addView(
                v, if (keepSize && v.layoutParams != null) v.layoutParams else matchWrap()
            )
            is Slot.Row -> slot.next().let { (c, lp) -> c.addView(v, lp) }
            is Slot.Grid -> slot.next().let { (c, lp) -> c.addView(v, lp) }
        }
    }

    /**
     * 各标签页组"上次停在哪一页"。
     *
     * key = `TABS_START` 的 key，value = 选中那个 `TAB` 的 key。
     * **存 key 不存索引**：插件加一页/删一页，索引就错位了，
     * 存 key 才能"哪怕顺序变了也回到同一个页"。
     *
     * 和 [groupOpen] 一样只放内存 —— 重启 App 回到第一页是对的。
     */
    private val tabSelected = HashMap<String, String>()

    /**
     * 本轮渲染出来、**能被外部定位**的标签页组（v8）。
     *
     * key = `TABS_START` 的 key。只有 key 非空的组会进来 —— 空 key 的组
     * 收到 `selectTab` 时无从指认，放进来只会让"多个空 key 争第一个"这种行为
     * 变得莫名其妙。
     *
     * 每次 [render] 重建，**不做增删维护** —— 视图都换了，旧实例必须一起丢。
     */
    private val tabGroups = LinkedHashMap<String, TabGroup>()

    /**
     * 切到某一页（v8，插件 `selectTab` 的落点）。
     *
     * ## 找不到怎么办：记进 [tabSelected]，不报错
     *
     * 两种"找不到"都是正常会发生的情况：
     *
     * | 情况 | 为什么会发生 |
     * |---|---|
     * | 组还没建出来 | 插件在 `onResult` 里调它，宿主可能正在 `refresh()` 途中 |
     * | 那个 tab 是插件**新加的** | 插件先发跳转、再 `notifySchemaChanged()` 加页 |
     *
     * 两种的正确处理都不是"报错给用户"，而是**记住意图**，等下一次渲染按 key
     * 恢复 —— 这正好是 [tabSelected] 本来的语义（"这一组该停在哪页"），
     * 所以不需要额外的 pending 队列。
     *
     * @return 这一次有没有真的切过去（false = 已记账，等下次渲染）
     */
    fun selectTab(groupKey: String, tabKey: String): Boolean {
        if (groupKey.isBlank() || tabKey.isBlank()) {
            android.util.Log.w(
                "PonkoPluginUi",
                "selectTab 参数为空（group='$groupKey' tab='$tabKey'），已忽略"
            )
            return false
        }
        val g = tabGroups[groupKey]
        if (g == null) {
            // 组不存在：可能是 key 拼错，也可能还没渲染完。记账，下次渲染生效
            tabSelected[groupKey] = tabKey
            android.util.Log.w(
                "PonkoPluginUi",
                "selectTab 没有匹配的组：'$groupKey'（已记账，下次重画时生效）。" +
                    "现有组：${tabGroups.keys}"
            )
            return false
        }
        val ok = g.selectByKey(tabKey, smooth = true)
        if (!ok) {
            tabSelected[groupKey] = tabKey
            android.util.Log.w(
                "PonkoPluginUi",
                "selectTab 组 '$groupKey' 里没有 tab '$tabKey'（已记账）"
            )
        }
        return ok
    }

    /**
     * 一组标签页。
     *
     * ## 一次建全，切换只改 visibility
     *
     * 插件给的 schema 里本来就有**所有页**的内容，所以渲染时全部建好、
     * 隐藏非当前页。切页只是 `visibility = GONE/VISIBLE`：
     * 输入框内容、滚动位置、选中的滑块值全都还在。
     *
     * 反过来做（切页时重建）要付三笔账：滚动位置复位、输入框失焦、
     * 正在编辑的 TEXTAREA 内容被覆盖。代价只是首次渲染多建几十个 View，
     * 比"每次切页抖一下"划算得多。
     */
    private inner class TabGroup(
        /** 记住选中状态的键。空 = 不记忆，重建后回到第一页 */
        private val stateKey: String,
        /** 嵌套深度，0 = 最外层。只影响标签条的字号和底色 */
        val depth: Int,
        /** 是否做成可左右滑动（只有最外层、且在最顶层时才给 true） */
        private val swipeable: Boolean = false,
    ) {
        val view: LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        /**
         * 标签条。
         *
         * 外层用主色浅底、内层用字段底：三层都一样的话，看不出
         * 哪个标签条属于哪一层。字号也差一档（13sp / 12sp）。
         */
        private val bar: LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(
                if (depth == 0) pal.primarySoft else pal.fieldBg,
                if (depth == 0) 10 else 8
            )
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        /**
         * 所有页叠在这里。
         *
         * 两种形态：
         * · **可滑动**（最外层那组）→ [androidx.viewpager.widget.ViewPager]，
         *   跟手左右翻页，和绘图页的「参数 / 结果」一个手感
         * · 其它（内层嵌套）→ FrameLayout + visibility
         *
         * 内层不能也做成 pager：横向手势会和外层 pager 互相抢
         * （绘图页 `DrawPage` 也是这个取舍，见它「模式容器用 visibility」那条注释）。
         */
        private val pagesHost: ViewGroup

        /** 只有 [swipeable] 时才非空 */
        private val pager: androidx.viewpager.widget.ViewPager?

        private val pages = ArrayList<LinearLayout>()
        private val chips = ArrayList<TextView>()

        /** 每页的 TAB key，和 [pages] 同下标 */
        private val keys = ArrayList<String>()

        var current = 0
            private set

        /**
         * 隐式页。
         *
         * 插件可能写 `TABS_START` 之后**直接给内容**（忘了写 TAB），
         * 那样内容也得有地方放。第一个 `TAB` 到来时会"认领"这一页。
         */
        private val implicitPage = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
        }

        init {
            view.addView(bar, matchWrap())
            if (swipeable) {
                val vp = androidx.viewpager.widget.ViewPager(act).apply {
                    adapter = PageAdapter(pages)
                    addOnPageChangeListener(object :
                        androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
                        override fun onPageSelected(position: Int) {
                            if (position == current) return
                            current = position
                            // 滑动切页也要记账，否则重画后回到旧页
                            keys.getOrNull(position)?.takeIf { it.isNotBlank() }
                                ?.let { tabSelected[stateKey] = it }
                            styleChips()
                        }
                    })
                }
                pager = vp
                pagesHost = vp
            } else {
                pager = null
                pagesHost = android.widget.FrameLayout(act)
            }
            view.addView(pagesHost, matchWrap())
            pages.add(implicitPage)
            keys.add("")
            if (pager == null) {
                pagesHost.addView(implicitPage, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            }
        }

        /**
         * 把 [pagesHost] 的高度定成**所有页里最高的那个**。
         *
         * ## 为什么必须手动定高
         *
         * pager 在整页 ScrollView 里拿到的是 UNSPECIFIED 高度。不给定高的话，
         * 要么塌成 0、要么靠 ViewPager 自己按"最大子页"去猜 —— 后者行为随版本变化，
         * 而这里明确算一遍更可靠，也顺便解释了"为什么短页会留白"。
         *
         * ## 为什么是"最高"而不是"当前页"
         *
         * 按当前页定高的话，从长页滑到短页、或反过来，中间那一帧会被裁掉半截，
         * 看起来像闪了一下。定成最高就永远裁不到。
         * 代价是短页下方有空白 —— 这是可滑动分页的固有取舍，
         * 想要"页页紧凑"就得像绘图页那样让 pager 占满整屏，那要重排整页结构。
         */
        private fun stretchToTallestPage() {
            val vp = pager ?: return
            val w = vp.width.takeIf { it > 0 } ?: act.resources.displayMetrics.widthPixels
            var h = 0
            pages.forEach { p ->
                p.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                if (p.measuredHeight > h) h = p.measuredHeight
            }
            if (h <= 0) return
            vp.layoutParams = (vp.layoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h)).apply { height = h }
            // 短页下方会留白，但**不给它加底色** —— 留白就是页面底色（pal.bg）的延续，
            // 读起来是"这页就这么多"；加个 fieldBg 反而会让所有卡片外面多出一层灰块
        }

        /**
         * 开一个新页并加上标签。
         *
         * 第一个 `TAB` **认领隐式页**（只改名，不新建），所以正常的
         * `TABS_START / TAB / …` 写法不会多出一个空页。
         */
        fun beginTab(item: com.litertchat.plugin.UiItem) {
            val idx: Int
            if (chips.isEmpty()) {
                idx = 0
                keys[0] = item.key
            } else {
                val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                pages.add(page)
                keys.add(item.key)
                if (pager == null) {
                    pagesHost.addView(page, android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
                } else {
                    // pager 里的页由 adapter 负责挂载，这里只通知它"多了一页"
                    pager.adapter?.notifyDataSetChanged()
                }
                idx = pages.size - 1
            }

            val chip = buildChip(item.label.ifBlank { "页 " + (idx + 1) }, idx)
            // 权重相同 → 均分。标签多了会自动挤窄，配合单行省略号
            bar.addView(chip, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            chips.add(chip)

            // ⚠ 这里**不选页**，也不碰页的可见性，只刷新 chip 的样式。
            //
            // 三个原因：
            // 1. 此刻还没收集完所有 tab 的 key，`restoreIndex()` 找不全
            //    （上次停在第 2 页的话，第 2 个 TAB 还没建出来）
            // 2. 用户已经翻到第 3 页时，插件重发 schema 会重建整页 ——
            //    在 beginTab 里 select 会把选中项顶回第 1 页
            // 3. 渲染期 [current] 一直是 0，若在这里 applySelection()，
            //    会把每页的 visibility 按"第 1 页"重刷一遍（还会重复
            //    setCurrentItem）。显示哪一页该由 [finish] 决定
            //
            // 真正的"恢复到上次那页"放在 [finish]（TABS_END 时）统一做一次。
            styleChips()
        }

        /** 重建后恢复哪一页：按 **key** 找，找不到就第一页 */
        private fun restoreIndex(): Int {
            val want = tabSelected[stateKey]?.takeIf { it.isNotBlank() } ?: return 0
            val i = keys.indexOf(want)
            return if (i >= 0) i else 0
        }

        /**
         * 一组标签页收集完毕（`TABS_END`）时调一次。
         *
         * 干两件事：
         * 1. **一个 TAB 都没有**时把标签条藏掉。插件可能只写了
         *    `TABS_START … TABS_END` 而没写 TAB，内容全落进隐式页 ——
         *    那时显示一个 3dp 的空条只会让人以为界面坏了
         * 2. 按 key 恢复到上次停的那一页。必须等到这里，因为要等
         *    所有 tab 的 key 都齐了才找得准
         */
        fun finish() {
            if (chips.isEmpty()) {
                // 插件只写了 TABS_START/TABS_END 没写 TAB：标签条没意义，藏掉。
                // 内容全在隐式页里，仍然正常显示。
                bar.visibility = View.GONE
                view.setPadding(0, 0, 0, 0)
                // ⚠ pager 的高度还是要算 —— 这种情况下 pagesHost 仍然是 pager，
                // 不给定高它就是 0dp，用户看到一整块空白
                //
                // 用 afterLayout 而不是 post：要给各页 measure 定高，
                // 必须先有真实宽度（post 时 pager.width 可能还是 0）
                pager?.let { afterLayout(it) { stretchToTallestPage() } }
                return
            }
            // restoreIndex 用 keys 查，此时已是完整列表
            val want = restoreIndex()
            if (want != current) select(want)
            // pager 的高度要在所有页都建完之后算（此时才知道哪页最高），
            // 而且要有真实宽度才能 measure —— 见 afterLayout 的说明
            pager?.let { afterLayout(it) { stretchToTallestPage() } }
        }

        private fun buildChip(label: String, idx: Int): TextView = TextView(act).apply {
            text = label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = if (depth == 0) 13f else 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isClickable = true
            setOnClickListener { select(idx) }
        }

        private fun select(idx: Int) {
            if (idx !in pages.indices) return
            current = idx
            applySelection()
            keys.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let { tabSelected[stateKey] = it }
        }

        /**
         * 按 `TAB` 的 key 选页（v8，给 `selectTab` 用）。
         *
         * @param tabKey 目标页的 key
         * @param smooth true = pager 用滑动动画过去。插件触发的跳转用它，
         *               用户点标签条时不用（点了立刻响应更跟手）
         * @return 有没有找到这一页
         *
         * **找不到就返回 false，不改任何状态** —— 由调用方决定怎么兜底
         * （[PluginUiPage.selectTab] 会写进 [tabSelected] 让下次渲染生效）。
         */
        fun selectByKey(tabKey: String, smooth: Boolean): Boolean {
            val i = keys.indexOf(tabKey)
            if (i < 0) return false
            if (i == current) return true          // 已经在那一页，什么都不用做
            current = i
            applySelection(smooth)
            tabSelected[stateKey] = tabKey
            return true
        }

        /**
         * 只刷样式，不动 [current]。
         *
         * 单独拆出来是因为 [beginTab] 里新加一个标签之后也要刷一遍样式
         * （新 chip 还是默认色），但**不能**顺手改选中项。
         */
        private fun applySelection(smooth: Boolean = false) {
            if (pager != null) {
                // pager 自己管显示哪一页；这里只同步选中项和标签样式。
                // **不能**再改 page.visibility —— pager 会把非当前页也 attach 住，
                // 设成 GONE 会让它布局错乱（滑过去是空白）
                if (pager.currentItem != current) pager.setCurrentItem(current, smooth)
                styleChips()
                return
            }
            pages.forEachIndexed { i, p ->
                val show = i == current
                val was = p.visibility == View.VISIBLE
                p.visibility = if (show) View.VISIBLE else View.GONE
                // 切进来时淡入一下。只在新显示的那一页上做，不碰其它页 ——
                // 一页一个 alpha 动画，页面多了会同时跑一堆没必要的动画
                if (show && !was) {
                    p.alpha = 0f
                    p.animate().alpha(1f).setDuration(130L).start()
                } else if (show) {
                    // 首次构建时（本来就是 VISIBLE）直接给满，避免"进页面先闪一下"
                    p.alpha = 1f
                }
            }
            styleChips()
        }

        /** 只刷标签条的选中样式（pager 滑动时也要调它） */
        private fun styleChips() {
            chips.forEachIndexed { i, c ->
                val sel = i == current
                c.setTextColor(if (sel) pal.primary else pal.subText)
                c.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // 选中项垫一块 surface 底：光变色在浅色主题下不够明显
                c.background = if (sel) rounded(pal.surface, 8) else null
            }
        }

        // 这里**不**保存/恢复日志框的滚动位置（2026-10-23 删）。
        //
        // 曾经有两版实现：
        //   v1  切页后无条件把所有日志框滚到底
        //   v2  记 scrollY + 是否在底部，切回来放回去
        //
        // 两版都是宿主替用户决定"该停在哪儿"。而且 v2 的 `post` 时机不可靠
        // （只保证下一帧消息，不保证 layout 完成），反而会造成跳位。
        //
        // 现在交给 Android 自己的行为：`GONE` 期间没测量，切回来时
        // ScrollView 会 clamp 到有效范围 —— 这是**框架的默认语义**，
        // 不是宿主额外加的东西。插件若要控制，用 `notifyScrollTo(key)`（v12）。

        /**
         * 渲染期间往哪一页加内容 = **最后开始的那一页**。
         *
         * ## 为什么不能用 [current]（这里踩过）
         *
         * `TAB` 的顺序语义是"从这里开始属于这一页，直到下一个 TAB 或 TABS_END"。
         * 渲染时只用 `pages.last()` 才能表达这个意思。
         *
         * 而 [current] 是**用户正在看哪一页**，它的取值要等 [finish]（TABS_END）
         * 才能按 key 恢复 —— 在那之前它一直是 0。所以如果渲染时往 [current] 加内容，
         * `TAB(a) 内容 TAB(b) 内容 TAB(c) 内容` 的结果是**三页的内容全堆进 a 页**，
         * b、c 两页全是空的。插件侧看到的现象就是"没法把控件分配到指定页"。
         *
         * 两个概念必须分开：**填充目标**（本轮渲染写到哪）和 **选中页**（显示哪一页）。
         * [current] / [currentPage] 只管后者。
         */
        fun fillTarget(): LinearLayout = pages.last()

        /** 用户当前看到的那一页。只用于滚动、日志粘底这类"显示状态"的事 */
        fun currentPage(): LinearLayout = pages.getOrNull(current) ?: implicitPage
    }

    /**
     * pager 用的最小 adapter：**直接复用已建好的页 View**，不重建。
     *
     * 和绘图页 `DrawPage.PaneAdapter` 同一个做法，也同一个理由：
     * 页是我们在渲染时按 schema 一次性建好的（只有这样 `patches` 才能按 key
     * 找到对应控件）。走 RecyclerView 那套（ViewPager2）会重复 attach 同一批
     * View，直接崩。
     *
     * `pages` 是**活的 list**（[TabGroup] 边渲染边往里加），所以
     * `getCount()` 每次现读，`beginTab` 里再 `notifyDataSetChanged()`。
     */
    private class PageAdapter(
        private val pages: List<LinearLayout>,
    ) : androidx.viewpager.widget.PagerAdapter() {
        override fun getCount(): Int = pages.size
        override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val v = pages[position]
            (v.parent as? ViewGroup)?.removeView(v)
            container.addView(v, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            return v
        }

        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            (obj as? View)?.let { container.removeView(it) }
        }
    }

    /** 给日志框的 ScrollView 打的标记 */
    private val LOG_SCROLL_TAG = "ponko:log"

    /**
     * 建一组标签页。
     *
     * @param depth     嵌套深度（外层 0）。由调用方数**当前栈里已有几层标签页**得出，
     *                  不自增计数器 —— 那种计数器一旦遇到插件写错的
     *                  `TABS_END`（少写/多写）就会永久偏掉。
     * @param swipeable 是否做成可左右滑动。**只有最外层且在最顶层**才给 true：
     *                  · 内层做成 pager 会和外层 pager 抢横向手势
     *                  · 嵌在可折叠分组里的 pager 横向手势也容易和纵向滚动打架，
     *                    而且"分组里再分页"本身读起来就绕
     */
    private fun buildTabGroup(
        item: com.litertchat.plugin.UiItem,
        depth: Int,
        swipeable: Boolean,
    ): TabGroup {
        val g = TabGroup(item.key, depth, swipeable)
        // 只有 key 非空的组能被 selectTab 指认（见 tabGroups 的说明）
        if (item.key.isNotBlank()) {
            if (tabGroups.containsKey(item.key)) {
                // 同名组：只认第一个。后出现的仍然正常渲染（用户能点），
                // 只是 selectTab 不会命中它 —— 插件写重了 key 应该被提醒
                android.util.Log.w(
                    "PonkoPluginUi",
                    "TABS_START 的 key 重复：'${item.key}'，selectTab 只会命中第一个"
                )
            } else {
                tabGroups[item.key] = g
            }
        }
        return g
    }

    /**
     * 只读日志框。固定高度 + 内部独立滚动。
     *
     * ## 为什么高度固定、内部自己滚
     *
     * 日志会长到几百行。如果让它跟 [UiType.INFO] 一样"随内容撑高"，
     * 整页会被顶得很长，下面的进度条、按钮全被推走 —— 用户得一直滚。
     * 固定 160dp，日志自己滚，页面结构就稳定了。
     *
     * ## 为什么能长按复制
     *
     * 这东西的价值就在"出问题时能贴给别人看"。不可选中的话，
     * 用户只能截图，而正文里的报错栈常比屏幕长。
     */
    private fun logBlock(item: UiItem): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        if (item.label.isNotBlank()) {
            box.addView(TextView(act).apply {
                text = item.label
                textSize = 13f
                setTextColor(pal.text)
                setPadding(0, 0, 0, dp(5))
            }, matchWrap())
        }

        val body = TextView(act).apply {
            text = item.value
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(pal.subText)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
            // ⚠ `setTextIsSelectable(true)` 会顺手把 isFocusable 也设成 true。
            // 一个 focusable 的子 View 在内容变化时会触发父容器的
            // `requestChildRectangleOnScreen`，表现是**整页跳到日志这里**。
            // 触摸模式下仍能长按选中/复制（靠 isFocusableInTouchMode），
            // 但不再参与方向键/焦点的自动可见性机制。
            isFocusable = false
            isFocusableInTouchMode = true
            // 横向也能滚：原生库的报错行经常超宽，换行会把本来就难读的
            // 十六进制/路径切得七零八落
            setHorizontallyScrolling(false)
        }
        val scroll = android.widget.ScrollView(act).apply {
            background = rounded(pal.fieldBg, 9)
            // 日志框自己不该抢焦点，理由同上
            isFocusable = false
            isFocusableInTouchMode = false
            // 给 [scrollLogsToBottomIn] 认出来用（切标签页后要重新粘底）
            tag = LOG_SCROLL_TAG
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(scroll, matchWrap().apply { height = dp(LOG_HEIGHT_DP) })

        if (item.key.isNotBlank()) {
            logBodies[item.key] = body
            logScrolls[item.key] = scroll
        }
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        // ⚠ 这里**不**自动滚到底（2026-10-23 删）。
        //
        // 原来有一行 `scroll.post { scrollToBottom(scroll) }`，理由是"用户想看的
        // 通常是最后几行"。但那是宿主**替用户判断**，而且是个副作用：
        // 插件送一段长日志进来，用户刚想从头看第一行，就被拽到末尾了。
        //
        // 跟随新内容这件事已经有正确的做法：`appendLog` 里先判断
        // **用户是不是本来就在底部**（`isAtBottom`），在底部才跟随。
        // 那个逻辑既满足流式日志的需求，又不打断正在翻历史的用户 ——
        // 初始这次没有"上一次位置"可判断，所以最保守的做法是**不动**。
        return box
    }

    /**
     * 把日志框滚到最底部，**只滚它自己**。
     *
     * ## 为什么不能用 `ScrollView.fullScroll(View.FOCUS_DOWN)`
     *
     * `fullScroll` 内部走的是 `scrollAndFocus()` → `requestChildRectangleOnScreen()`，
     * 而后者会**沿 View 树向上冒泡**：它请求父容器把这块矩形变可见 ——
     * 于是外层那个整页 ScrollView 也跟着滚，用户看到的就是"日志一有内容，
     * 整个页面唰地跳到日志那里"。
     *
     * `scrollTo` 只改自己的 `mScrollY`，对父容器一无影响。这就是这一行
     * 看起来"更笨"却必须这么写的原因。
     */
    private fun scrollToBottom(scroll: android.widget.ScrollView) {
        val child = scroll.getChildAt(0) ?: return
        val target = (child.height - scroll.height).coerceAtLeast(0)
        scroll.scrollTo(0, target)
    }

    /**
     * **等这一帧布局完成**再执行 —— 需要"最新尺寸/坐标"的地方必须用它。
     *
     * ## 为什么 `post` 不够（踩过两次）
     *
     * `View.post` 只保证"在下一轮消息队列里执行"，**不保证 measure/layout 跑过**。
     * 那一刻 `height` 还是 0、子 View 的 `top` 还没算出来。依赖这些的计算会拿到
     * 错值，而且**看起来能用**（不报错、不崩），只是位置不对。
     *
     * 实际踩到的两处：
     * · 恢复外层滚动位置 → `scrollTo` 被 clamp 到 0，用户看到页面跳走
     * · 日志滚到底 → `child.height` 是 0，滚不动
     *
     * 都表现为"偶尔不生效"，极难复现。
     *
     * ## 为什么不用 `OnGlobalLayoutListener`
     *
     * 也想过用它，但它的语义是"**发生了一次**全局布局"。同一帧里可能触发多次，
     * 也可能在内容变化前就已经跑过（`isLaidOut` 为 true 但高度是旧值）——
     * 用它判断"尺寸是否最新"会得到错误结论。
     *
     * `OnPreDrawListener` 的时机是**布局完成、绘制之前**，此时所有 View 的
     * 尺寸和位置都已更新，是唯一能保证"读到的是最新值"的点。
     *
     * ## `invalidate()` 为什么必要
     *
     * `onPreDraw` 只在真的要绘制时触发。如果内容没变、没有新帧要画，
     * 挂上去就永远等不到 —— 那就成了静默不执行。主动 `invalidate()`
     * 制造一次绘制，保证回调必来。
     *
     * 视图不可见（`GONE`）时不会有绘制，那时退回 `post`：至少不会留下
     * 一个永远不摘的 listener。
     */
    private fun afterLayout(v: View, block: () -> Unit) {
        // ① 还没 attach 到窗口（render 在 build() 里跑，这时整个页面都还没挂上去）
        //    → 等 attach 再重新进来。**不能**在这里用 post：
        //    post 会跑在 attach 之前，那时尺寸全是 0
        if (!v.isAttachedToWindow) {
            v.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        view.removeOnAttachStateChangeListener(this)
                        afterLayout(view, block)
                    }

                    override fun onViewDetachedFromWindow(view: View) = Unit
                }
            )
            return
        }
        // ② attach 了但不可见（GONE）→ 不会有绘制，挂 preDraw 会永远等不到。
        //    退回 post：至少不会留下一个永不摘掉的 listener
        if (!v.isShown) {
            v.post(block)
            return
        }
        // ③ 正常情况：等这一帧布局完成、绘制之前
        val vt = v.viewTreeObserver
        if (!vt.isAlive) {
            block()
            return
        }
        vt.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    // 先摘再执行：留着会在后续每一帧都跑，
                    // 反复设置滚动位置会让画面抖
                    if (v.viewTreeObserver.isAlive) {
                        v.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    block()
                    return true
                }
            }
        )
        // 保证一定会有一次 draw → 一定会走到 onPreDraw
        v.invalidate()
    }

    /**
     * 滚到某个 item（v12，插件通过 `notifyScrollTo(key)` 调用）。
     *
     * ## 为什么由插件调，而不是宿主自动做
     *
     * 2026-10-23 之前宿主自己"记住重画前的滚动位置再放回去"。那是宿主
     * 替页面作者决定停在哪 —— 而重画后停在哪本该是插件的事：
     * 它可能想让用户看到新出现的结果、也可能想留在参数区。
     *
     * 所以：**宿主不自动滚动**，要滚就显式说。
     * 不调这个方法的插件，重画后位置由 Android 自己保持（同一帧内重建，
     * `mScrollY` 不会被重置），也就是"什么都不发生"。
     *
     * ## 滚动目标的算法
     *
     * 用"目标在当前窗口的 y" 减 "ScrollView 在窗口的 y" 再加上当前 scrollY，
     * 得到目标在**内容坐标系**里的偏移。不能直接用 `target.top` ——
     * 那是相对父容器的，中间隔了几层容器就对不上。
     *
     * ## 为什么必须等布局
     *
     * 刚 `render()` 完时子 View 还没测量，`getLocationInWindow` 拿到的是
     * 旧坐标（或者 0）。这就是之前"恢复滚动位置"执行太早、反而跳位的根因。
     * 见 [afterLayout]。
     *
     * @param key 目标 item 的 key。找不到就打日志（不猜）
     * @param smooth true = 平滑滚动；false = 直接跳
     */
    fun scrollTo(key: String, smooth: Boolean = true) {
        val target = keyedViews[key]
        if (target == null) {
            android.util.Log.w(
                "PonkoPluginUi",
                "scrollTo: 找不到 key='$key' 的控件（已有：${keyedViews.keys}）"
            )
            return
        }
        val sv = self ?: return
        afterLayout(sv) {
            val t = IntArray(2)
            target.getLocationInWindow(t)
            val s = IntArray(2)
            sv.getLocationInWindow(s)
            // 目标在内容坐标系里的 y
            val y = (t[1] - s[1] + sv.scrollY).coerceAtLeast(0)
            if (smooth) sv.smoothScrollTo(0, y) else sv.scrollTo(0, y)
        }
    }

    /**
     * 日志框是不是已经停在底部（容忍一点误差）。
     *
     * 用来决定**要不要跟着新内容走**：用户往上翻在看历史时，
     * 新日志不该把他拽回底部 —— 那正是日志框最烦人的行为。
     */
    private fun isAtBottom(scroll: android.widget.ScrollView): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        val max = (child.height - scroll.height).coerceAtLeast(0)
        // 内容还没填满一屏时 max=0，任何位置都算"在底部"
        return max - scroll.scrollY <= dp(8)
    }

    /**
     * 结果图。[UiItem.value] 是**还没有图时**的占位文字。
     *
     * 图从 [setImage] 进来（对应插件的 `onImage`）。已经有图的话直接贴上去 ——
     * 否则用户切走再切回来，图就没了。
     */
    private fun imageBlock(item: UiItem): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        if (item.label.isNotBlank()) {
            box.addView(TextView(act).apply {
                text = item.label
                textSize = 13f
                setTextColor(pal.text)
                setPadding(0, 0, 0, dp(5))
            }, matchWrap())
        }

        val holder = android.widget.FrameLayout(act).apply {
            background = rounded(pal.fieldBg, 9)
        }
        val iv = android.widget.ImageView(act).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                val d = drawable as? android.graphics.drawable.BitmapDrawable
                d?.bitmap?.let { bmp -> zoomable?.invoke(bmp) }
            }
        }
        val ph = TextView(act).apply {
            text = item.value.ifBlank { "还没有图片" }
            textSize = 12f
            setTextColor(pal.hint)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(28), dp(12), dp(28))
        }
        holder.addView(iv, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        holder.addView(ph, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        box.addView(holder, matchWrap())

        if (item.key.isNotBlank()) {
            imageViews[item.key] = iv
            imagePlaceholders[item.key] = ph
        }
        // 已经有图（之前收到过 / 用户从别处切回来）→ 直接显示
        lastBitmap?.let { bindImageTo(iv, ph, it) }
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        return box
    }

    /**
     * 音频播放条：播放/暂停 + 可拖进度 + 时间。
     *
     * 播的是**宿主**手里那份产物（最近一次合成结果），插件不传路径。
     * 详见 [UiType.AUDIO] 的说明。
     */
    private fun audioBar(item: UiItem): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        if (item.label.isNotBlank()) {
            box.addView(TextView(act).apply {
                text = item.label
                textSize = 13f
                setTextColor(pal.text)
                setPadding(0, 0, 0, dp(5))
            }, matchWrap())
        }

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(pal.fieldBg, 9)
        }
        val btn = TextView(act).apply {
            text = "▶"
            textSize = 16f
            setTextColor(pal.primary)
            setPadding(dp(6), dp(6), dp(12), dp(6))
            isClickable = true
        }
        val seek = SeekBar(act).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(pal.primary)
            thumbTintList = android.content.res.ColorStateList.valueOf(pal.primary)
        }
        val time = TextView(act).apply {
            text = "0:00 / 0:00"
            textSize = 11f
            setTextColor(pal.hint)
            setPadding(dp(10), 0, dp(4), 0)
        }
        val empty = TextView(act).apply {
            text = "还没有可播放的音频"
            textSize = 12f
            setTextColor(pal.hint)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            visibility = View.GONE
        }

        val key = item.key.ifBlank { "audio#${audioBars.size}" }

        // v7：逐条保存。只有宿主注入了 saver 才画这个按钮 ——
        // 画一个点了没反应的按钮，比不画更让人困惑
        val save: TextView? = audioSaver?.let {
            TextView(act).apply {
                text = "保存"
                textSize = 11f
                setTextColor(pal.primary)
                setPadding(dp(10), dp(6), dp(2), dp(6))
                isClickable = true
                setOnClickListener { audioSaver?.invoke(key) }
            }
        }

        row.addView(btn, wrapWrap())
        row.addView(seek, LinearLayout.LayoutParams(0, dp(32), 1f))
        row.addView(time, wrapWrap())
        save?.let { row.addView(it, wrapWrap()) }
        box.addView(row, matchWrap())
        box.addView(empty, matchWrap())

        val bar = AudioBar(btn, seek, time, empty, key, save = save)
        audioBars[key] = bar

        btn.setOnClickListener {
            val h = audioHost ?: return@setOnClickListener
            if (!h.hasAudio(key)) return@setOnClickListener
            if (h.isPlaying(key)) h.pause(key) else h.play(key)
            refreshAudioBars()
            startAudioTicker()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {
                bar.dragging = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                bar.dragging = false
                val h = audioHost ?: return
                val dur = h.durationMs(key)
                if (dur > 0) h.seekTo(key, dur * (sb?.progress ?: 0) / 1000)
                refreshAudioBars()
            }
        })

        refreshAudioBars()
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        return box
    }

    private fun bindImageTo(iv: android.widget.ImageView, ph: TextView, bmp: android.graphics.Bitmap) {
        iv.setImageBitmap(bmp)
        iv.visibility = View.VISIBLE
        ph.visibility = View.GONE
    }

    /**
     * 收到一张结果图（插件 `onImage`）→ 贴到 [UiType.IMAGE] 控件上。
     *
     * @param key 目标 item 的 key。传了就**只**贴那个控件。
     *            没传时：页面上**恰好一个**图片控件才贴它，多个则丢弃 + 日志。
     *
     * ## 为什么"多个就丢"而不是"贴第一个"（2026-10-23 改）
     *
     * `onImage` 的 AIDL 签名里**没有 key**（v4 定的，四个回调里只有
     * `onAudioFile` 在 v7 补了 key）。所以插件送图时根本没机会指明目标。
     *
     * 原来宿主兜底成"贴第一个" —— 那是**替插件做决定**：页面上有两张图
     * （比如"原图 / 结果"对比）时，插件想更新右边那张，宿主把左边那张覆盖了，
     * 而屏幕上看起来就像是插件自己贴错的。
     *
     * 现在只在**无歧义**时（只有一个图片控件）才自动贴。有歧义就丢，
     * 由插件改用带 key 的回调 —— 见 [IPonkoCallback.onImageWithKey]。
     */
    fun setImage(bmp: android.graphics.Bitmap, key: String? = null) {
        lastBitmap = bmp

        // 先定出目标控件的 key —— 后面取 placeholder 也要用同一个 key
        val targetKey: String = if (!key.isNullOrBlank()) {
            if (key !in imageViews) {
                android.util.Log.w(
                    "PonkoPluginUi",
                    "收到的图指定 key='$key'，但页面里没有这个 IMAGE 控件" +
                        "（可用：${imageViews.keys}）"
                )
                return
            }
            key
        } else {
            when (imageViews.size) {
                0 -> {
                    // 插件送了图但没声明 IMAGE 项，属于插件侧写错。
                    // 不弹错误（用户看不懂），只留日志
                    android.util.Log.w("PonkoPluginUi", "收到结果图，但当前页面没有 IMAGE 控件")
                    return
                }
                1 -> imageViews.keys.first()
                else -> {
                    // 多张图：宿主**不猜**该贴哪张，直接说明该用带 key 的回调
                    android.util.Log.w(
                        "PonkoPluginUi",
                        "收到结果图但没带 key，页面上有 ${imageViews.size} 个 IMAGE 控件" +
                            "（${imageViews.keys}），无法确定目标，已丢弃。" +
                            "请改用带 key 的回调"
                    )
                    return
                }
            }
        }

        val target = imageViews[targetKey] ?: return
        val ph = imagePlaceholders[targetKey]
        if (ph != null) bindImageTo(target, ph, bmp) else {
            target.setImageBitmap(bmp)
            target.visibility = View.VISIBLE
        }
    }

    /**
     * 原地更新一个控件（插件 `onUiPatch`）。**不重建整页。**
     *
     * 支持的类型：
     * - [UiType.LOG]：`append=true` 往末尾追加，`false` 整段替换
     * - [UiType.PROGRESS]：value 是 0–100，文字和进度条一起改
     * - [UiType.STATUS] / [UiType.INFO]：整段替换文字（**不解析**行内格式）
     * - [UiType.IMAGE]：忽略 —— 真图请走 [setImage]
     *
     * 其它类型（开关、滑块、输入框…）**忽略** —— 那些是用户输入的东西，
     * 插件单方面改会和用户正在操作的状态打架。
     */
    fun patch(key: String, value: String?, append: Boolean) {
        if (value == null) return

        // 1) 日志：按 key 找正文
        logBodies[key]?.let { body ->
            appendLog(key, body, value, append)
            return
        }
        // 2) 进度条：文字和 bar 一起改
        patchableBars[key]?.let { bar ->
            val pct = value.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: return
            bar.progress = pct.toInt()
            patchableViews[key]?.text = "${pct.toInt()}%"
            return
        }
        // 3) 状态行 / 说明：整段替换
        patchableViews[key]?.let { tv ->
            tv.text = value
            return
        }
        // 4) key 对不上就**什么都不做**（2026-10-23 改）。
        //
        // 原来这里有个兜底：页面上只有一个日志框时，把值塞给它 ——
        // 理由是"插件常不写 key，而整页通常只有一个日志框"。
        //
        // 但那是宿主**猜**插件想改哪个控件。猜对的收益是省一个 key，
        // 猜错的代价是往一个不相关的地方写字、而且看起来像是插件自己写的 ——
        // 那种错最难归因。插件写 key 的成本只是一个字符串，
        // 而 UI 改错地方的成本是排查半天。
        //
        // 现在：key 对不上就丢，并留一行日志告诉插件哪个 key 没找到。
        android.util.Log.w(
            "PonkoPluginUi",
            "patch 的 key='$key' 在当前页面没有对应控件，已丢弃" +
                "（可用：${logBodies.keys + patchableViews.keys + patchableBars.keys}）"
        )
    }

    private fun appendLog(key: String, body: TextView, value: String, append: Boolean) {
        val scroll = logScrolls[key]
        // 先记账：**追加之前**是不是停在底部。
        // 判断必须放在 setText 之前 —— 设完文本内容变长，原来的位置就不在底部了，
        // 再判断会永远得到 false，于是日志再也不跟随。
        val follow = scroll != null && isAtBottom(scroll)
        // 外面那个整页 ScrollView 的位置也要留住。
        // 光标/选中区的自动可见性在个别机型上仍会把父级带走，这是第二道保险。
        val outer = self
        val outerY = outer?.scrollY ?: 0

        body.text = if (append) body.text.toString() + value else value

        // 只有原本就在底部才跟随。用户翻上去看历史时把他拽回底部，
        // 是日志框最招人烦的行为之一。
        if (follow && scroll != null) {
            // afterLayout 而不是 post：`scrollToBottom` 要读 child.height，
            // 刚 setText 完那一刻还没重排，读到的是旧高度（可能滚不到底）。
            // 这就是"日志偶尔不跟随"的原因 —— 时好时坏，极难复现
            afterLayout(scroll) { scrollToBottom(scroll) }
        }
        // 锁外层位置**只在 follow 时**做（2026-10-23 收窄）。
        //
        // 原来是无条件执行 —— 用户在翻整页、插件恰好在推日志，就会被拽回原位。
        // 而需要这道保险的只有一种情况：日志框自己在跟着滚到底，
        // 那一次 `requestChildRectangleOnScreen` 可能把父级一并带走。
        // 用户没在跟随时，我们本来就不碰任何滚动，没有要保险的东西。
        if (follow && outer != null) {
            // 同上：要在布局之后才知道滚动范围变没变
            afterLayout(outer) { if (outer.scrollY != outerY) outer.scrollTo(0, outerY) }
        }
    }

    /** PROGRESS 的进度条，按 key 索引，供 [patch] 原地改 */
    private val patchableBars = HashMap<String, android.widget.ProgressBar>()

    // ==================== v4：播放条刷新 ====================

    /**
     * 把 [audioHost] 的当前状态推到所有播放条上。
     *
     * 为什么是"推"而不是每个播放条自己轮询：一页可能有多个音频项
     * （比如"原声/合成"对比），各起一个 Runnable 会重复查同一个播放器。
     */
    private fun refreshAudioBars() {
        val h = audioHost
        audioBars.values.forEach { bar ->
            // 每条**各自问一遍**：v7 起不同 key 可能是不同文件、不同播放器，
            // 甚至只有其中几条有音频
            val has = h?.hasAudio(bar.key) == true
            bar.btn.visibility = if (has) View.VISIBLE else View.GONE
            bar.seek.visibility = if (has) View.VISIBLE else View.GONE
            bar.time.visibility = if (has) View.VISIBLE else View.GONE
            bar.save?.visibility = if (has) View.VISIBLE else View.GONE
            bar.empty.visibility = if (has) View.GONE else View.VISIBLE
            if (!has) return@forEach

            val dur = h?.durationMs(bar.key) ?: 0
            val pos = h?.positionMs(bar.key) ?: 0
            val playing = h?.isPlaying(bar.key) == true
            bar.btn.text = if (playing) "⏸" else "▶"
            bar.time.text = "${fmtMs(pos)} / ${fmtMs(dur)}"
            // 用户正按着滑块时不能改它的位置 —— 手指和控件会打架
            if (!bar.dragging) {
                bar.seek.progress = if (dur > 0) (pos.toLong() * 1000 / dur).toInt() else 0
            }
        }
    }

    /** 有没有任意一条在响。ticker 靠它决定要不要继续跑 */
    private fun anyPlaying(): Boolean {
        val h = audioHost ?: return false
        return audioBars.values.any { h.isPlaying(it.key) }
    }

    /** 让某个播放条跟着播放器走。播放中每 200ms 刷一次，暂停就停 */
    private fun startAudioTicker() {
        if (!anyPlaying()) {
            audioTicker?.let { handler.removeCallbacks(it) }
            audioTicker = null
            return
        }
        if (audioTicker != null) return
        val r = object : Runnable {
            override fun run() {
                if (!anyPlaying()) {
                    refreshAudioBars()
                    audioTicker = null
                    return
                }
                refreshAudioBars()
                handler.postDelayed(this, 200)
            }
        }
        audioTicker = r
        handler.postDelayed(r, 200)
    }

    /** 页面销毁时必须停 —— 否则 handler 会一直持着已销毁的 view */
    fun stopAudioTicker() {
        audioTicker?.let { handler.removeCallbacks(it) }
        audioTicker = null
    }

    /**
     * 外部（宿主）换了音频 / 播放状态变了 → 刷一次播放条。
     *
     * 合成完成、点了「播放」按钮、新文件落盘，都要调它 ——
     * 否则播放条还停在"还没有可播放的音频"。
     */
    fun refreshAudio() {
        refreshAudioBars()
        startAudioTicker()
    }

    /** 页面重建后重新绑定播放条（[host] 里的控件全换了） */
    private fun rebuildAudioBars() {
        stopAudioTicker()
        refreshAudioBars()
        startAudioTicker()
    }

    private fun fmtMs(ms: Int): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())


    /** PROGRESS / STATUS 的文字控件，按 key 索引，供 [patch] 原地改 */
    private val patchableViews = HashMap<String, TextView>()

    /** 开关：整行可点，右侧显示 开 / 关 */
    private fun switchRow(item: UiItem): View {
        val on = item.value.equals("true", ignoreCase = true)
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isEnabled = item.enabled
            alpha = if (item.enabled) 1f else 0.45f
        }
        row.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply {
                text = item.label
                textSize = 13f
                setTextColor(pal.text)
            }, matchWrap())
            if (item.help.isNotBlank()) addView(smallHint(item.help), matchWrap())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val state = TextView(act).apply {
            text = if (on) "开" else "关"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (on) pal.primary else pal.subText)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = rounded(if (on) pal.primarySoft else pal.fieldBg, 9)
        }
        row.addView(state, wrapWrap())

        if (item.enabled) {
            row.setOnClickListener {
                val next = !(state.text == "开")
                // 立刻改本地显示，不等插件确认 —— 开关必须跟手
                state.text = if (next) "开" else "关"
                state.setTextColor(if (next) pal.primary else pal.subText)
                state.background = rounded(if (next) pal.primarySoft else pal.fieldBg, 9)
                session.setSetting(pkg, item.key, if (next) "true" else "false")
            }
        }
        return row
    }

    /** 滑块：SeekBar，拖动中不上报，松手才 setSetting */
    private fun sliderRow(item: UiItem): View {
        val span = (item.max - item.min).let { if (it <= 0.0) 1.0 else it }
        val stepPct = if (item.step > 0) (item.step / span * 100.0).toInt().coerceAtLeast(1) else 1
        val cur = item.value.toDoubleOrNull() ?: item.min
        val progress = (((cur - item.min) / span) * 100.0).toInt().coerceIn(0, 100)

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
            alpha = if (item.enabled) 1f else 0.45f
        }
        // 标签行：左边名字，右边当前值
        val labelRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(TextView(act).apply {
            text = item.label
            textSize = 13f
            setTextColor(pal.text)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueTv = TextView(act).apply {
            text = fmt(cur, span)
            textSize = 12f
            setTextColor(pal.primary)
        }
        labelRow.addView(valueTv, wrapWrap())
        box.addView(labelRow, matchWrap())

        val bar = SeekBar(act).apply {
            max = 100
            this.progress = progress
            isEnabled = item.enabled
            setPadding(0, dp(6), 0, 0)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                // 显示按 step 取整后的值，别显示 0.3333333
                val real = item.min + span * (p / 100.0)
                valueTv.text = fmt(real, span)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                draggingKey = item.key
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                draggingKey = null
                val p = sb?.progress ?: return
                // 松手才上报：拖动过程每帧都发 Binder 会刷爆配额
                val raw = item.min + span * (p / 100.0)
                val snapped = if (item.step > 0) {
                    (Math.round((raw - item.min) / item.step) * item.step) + item.min
                } else raw
                session.setSetting(pkg, item.key, trimNum(snapped))
            }
        })
        box.addView(bar, matchWrap())
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        return box
    }

    /** 单选：竖排 chip，选中高亮 */
    private fun choiceBlock(item: UiItem): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
            alpha = if (item.enabled) 1f else 0.45f
        }
        box.addView(TextView(act).apply {
            text = item.label
            textSize = 13f
            setTextColor(pal.text)
        }, matchWrap())

        val chips = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        item.options.forEach { opt ->
            val selected = opt.value == item.value
            val chip = TextView(act).apply {
                text = opt.label
                textSize = 12f
                setTextColor(if (selected) pal.primary else pal.text)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (selected) pal.primarySoft else pal.fieldBg, 9)
                if (item.enabled) {
                    isClickable = true
                    setOnClickListener {
                        session.setSetting(pkg, item.key, opt.value)
                        // 选中的视觉要立刻变，不等插件回调
                        renderWithSelected(item, opt.value)
                    }
                }
            }
            chips.addView(chip, matchWrap().apply { topMargin = dp(6) })
        }
        box.addView(chips, matchWrap())
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        return box
    }

    /**
     * 只重画同一组的选中态。
     *
     * 不做整页重画：整页重画会让下面的滚动位置跳动，用户刚点完就"被滚走了"。
     */
    private fun renderWithSelected(item: UiItem, value: String) {
        val host = host ?: return
        // 控件可能被包在可折叠分组里，所以必须**递归**找，不能只看直接子项
        val chips = findChips(host, item.label)
        if (chips == null) {
            // 找不到就退回整页重画（结构变了的情况）
            render()
            return
        }
        for (j in 0 until chips.childCount) {
            val chip = chips.getChildAt(j) as? TextView ?: continue
            val opt = item.options.getOrNull(j) ?: continue
            val sel = opt.value == value
            chip.setTextColor(if (sel) pal.primary else pal.text)
            chip.background = rounded(if (sel) pal.primarySoft else pal.fieldBg, 9)
        }
    }

    /**
     * 递归找回某一组单选对应的 chip 容器。
     *
     * 结构是：`box(LinearLayout) [ label(TextView), chips(LinearLayout) ]`，
     * 但 box 可能嵌在分组容器里任意深度。
     */
    private fun findChips(parent: android.view.ViewGroup, label: String): LinearLayout? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as? android.view.ViewGroup ?: continue
            // 先看当前这层是不是目标组
            val head = child.getChildAt(0) as? TextView
            val second = child.getChildAt(1) as? LinearLayout
            if (head?.text?.toString() == label && second != null &&
                second.childCount > 0 && second.getChildAt(0) is TextView
            ) {
                return second
            }
            // 不是就往下找
            findChips(child, label)?.let { return it }
        }
        return null
    }

    /** 单行 / 多行输入 */
    private fun textRow(item: UiItem, multiLine: Boolean): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
            alpha = if (item.enabled) 1f else 0.45f
        }
        box.addView(TextView(act).apply {
            text = item.label
            textSize = 13f
            setTextColor(pal.text)
        }, matchWrap())

        // 多行输入：保留换行，minLines 给个下限，否则空内容时只有一条细缝
        val input = EditText(act).apply {
            setText(item.value)
            textSize = 13f
            setTextColor(pal.text)
            hint = item.help.ifBlank { null }
            setHintTextColor(pal.hint)
            isEnabled = item.enabled
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(pal.fieldBg, 9)
            if (multiLine) {
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setSingleLine(false)
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                setLineSpacing(dp(3).toFloat(), 1f)
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
            }
            // 失焦才提交：边打字边发 Binder 会在输入法预测时刷很多次
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) session.setSetting(pkg, item.key, text.toString())
            }
        }
        box.addView(input, matchWrap().apply { topMargin = dp(6) })
        return box
    }

    /**
     * 按钮（ACTION）。
     *
     * ## 两种按钮，靠 [UiItem.value] 区分（v9）
     *
     * | `value` | 含义 | 点了发生什么 |
     * |---|---|---|
     * | 空 | 插件自己的按钮 | `invokeAction(key)` 回调插件 |
     * | 宿主动作 id（`"reasoning"` 等） | **宿主动作**，插件只决定放哪、叫什么 | 宿主执行（合成 / 播放 / 保存…） |
     *
     * 为什么要分两种：这些动作背后是宿主的状态机（引擎加载了几成、是否在合成、
     * 参考音频 FD、播放器、音乐库）。插件在另一个进程里，这些它一样都拿不到。
     * 所以能放开的只有**放哪些、放哪里、叫什么**，执行仍然归宿主。
     *
     * 认不出来的 `value`（拼错、或来自更新的 SDK）一律当插件自己的按钮 ——
     * 宁可回调插件，也不要静默变成一个点了没反应的死按钮。
     */
    private fun actionRow(item: UiItem): View {
        val hostAction = item.value.trim().takeIf { it.isNotEmpty() }?.let { hostActionMap[it] }

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        if (hostAction != null) {
            // 宿主动作：登记到"这一帧被接管了"，顶部那排会跳过它
            schemaDeclaredActions.add(hostAction.id)
            // enabled 由宿主实时状态说了算（见 makeHostActionButton）；
            // 文案插件写了就用它 —— 那是它接管这个按钮的主要理由之一
            box.addView(
                makeHostActionButton(
                    action = hostAction,
                    label = item.label.ifBlank { hostAction.label },
                    compact = false,
                    primary = item.primary,
                    useGivenLabel = item.label.isNotBlank(),
                ),
                matchWrap()
            )
            if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
            return box
        }

        if (item.value.trim().isNotEmpty()) {
            // 有 value 但不是宿主动作 —— 大概率是 id 拼错。降级成插件按钮，
            // 但要留一条日志，否则"点了没合成"会查很久
            android.util.Log.w(
                "PonkoPluginUi",
                "ACTION 的 value='${item.value}' 不是已知宿主动作，按插件按钮处理" +
                    "（已知：${hostActionMap.keys}）"
            )
        }

        box.addView(TextView(act).apply {
            text = item.label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (item.enabled) if (item.primary) pal.surface else pal.primary
            else pal.hint)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(
                if (item.primary) pal.primary else pal.primarySoft, 10)
            alpha = if (item.enabled) 1f else 0.6f
            if (item.enabled) {
                isClickable = true
                setOnClickListener {
                    session.invokeAction(pkg, item.key)
                    // 插件做完会调 onSchemaChanged 让宿主重画；这里不猜结果
                }
            }
        }, matchWrap())
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        return box
    }

    // ==================== v10：纯排版控件 ====================

    /**
     * 分割线（v10）。
     *
     * 颜色用 [PonkoTheme.divider]（半透明），**由宿主决定** —— 插件只说
     * "这里要一条线"，不说它什么颜色。这就是"结构归插件、样式归宿主"。
     *
     * 在 [Slot.Row] 里它会变成一条细竖线（宽度由 weight 决定、高度 1dp）——
     * 同一个类型在横向容器里自然成为竖分隔，不需要再定义一个"竖分割线"。
     */
    private fun dividerView(): View {
        val v = View(act)
        v.setBackgroundColor(pal.divider)
        v.minimumHeight = 1
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        return v
    }

    /**
     * 空白占位（v10）。
     *
     * `value` = 高度（dp），空 = 8。
     *
     * **放进 Row / Grid 时高度被忽略**，宽度改由容器的 weight 决定 ——
     * 那里它的作用是"把两边推开"。上层 [place] 会用 weight 参数覆盖这里设的
     * layoutParams，所以这段高度只在纵向容器里生效。
     *
     * 这是 SPACER 最关键的价值：没有它，[Slot.Row] 只能等分，
     * 做不出"左边一组、右边一组"这种对齐。
     */
    private fun spacerView(item: UiItem): View {
        val h = item.value.trim().toIntOrNull()?.takeIf { it >= 0 } ?: 8
        val v = View(act)
        v.minimumHeight = dp(h)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(h)
        )
        return v
    }

    // ==================== 小工具 ====================

    private fun applyHelp(v: TextView, item: UiItem) {
        if (item.help.isNotBlank()) v.append("\n${item.help}")
    }

    private fun sectionTitle(t: String) = TextView(act).apply {
        text = t
        textSize = 13f
        setTextColor(pal.subText)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(2))
    }

    /**
     * 说明段落。支持**极少量**行内格式：
     *
     * - `**粗体**`
     * - `` `等宽` ``
     *
     * ## 为什么只做这两个
     *
     * 手册里写了 intro 可以用粗体和代码，但这里原来只是 `text = t` ——
     * 照着写的插件会在界面上看到一堆星号和反引号。
     * 承诺了就要兑现，否则文档比没有更糟。
     *
     * 但**不是**做完整 markdown 解析：标题、列表、链接、表格一律按原文显示。
     * 理由有两个：
     *
     * 1. 每种多解析一个语法，就多一处"插件以为是这样、界面是那样"的错位
     * 2. 文本里可能合法地出现星号（比如文件类型 MIME 里那个通配符），
     *    语法越多越容易误伤。所以这里**只认双星**，单个星号永远原样输出
     *
     * 手册里也写明了这个子集，两边对齐。
     *
     * ⚠ 改这段注释时注意：Kotlin 的块注释**支持嵌套**，所以注释正文里不能出现
     * 「斜杠 + 星号」这个组合 —— 它会再开一层注释，把后面的代码全吞掉，
     * 报错却是"Unclosed comment"指向文件末尾，极难定位。（这次就踩了）
     */
    private fun hintText(t: String) = TextView(act).apply {
        text = inlineFormat(t)
        textSize = 12f
        setTextColor(pal.subText)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(6), 0, dp(4))
    }

    /**
     * 把 `**粗体**` 和 `` `代码` `` 转成 Spannable。解析不出来就原样返回。
     *
     * ## 为什么是"重建字符串"而不是"在原串上打补丁"
     *
     * 第一版想的是"找到标记 → 加 span → 删掉标记字符"。这条路有两个坑：
     *
     * 1. 删字符会让**后面所有下标失效**（`**` 是两字符、`` ` `` 是一字符，
     *    删完长度都不一样，前面的 span 位置全得重算）
     * 2. `setSpan` 之后再 `delete`，Span 会自动跟着移位，很容易差一两个字符 ——
     *    表现是粗体范围偏了，比如把后面的标点也吞进粗体里
     *
     * 所以改成单遍扫描：边扫边往新串里 append，**顺手记下每段的起止**，
     * 扫完再统一 setSpan。下标从头到尾都只有"新串里的位置"这一种含义。
     *
     * ## 宁可少格式化，不能改内容
     *
     * 只有**成对**的标记才处理。落单的 `**` 或 `` ` `` 原样保留 ——
     * 用户看到多余的星号总比看到内容被吞掉好。
     */
    private fun inlineFormat(src: String): CharSequence {
        if (!src.contains("**") && !src.contains('`')) return src
        return try {
            // 交替匹配：组 1 = 粗体内容，组 2 = 代码内容
            val re = Regex("\\*\\*(\\S(?:[\\s\\S]*?\\S)?)\\*\\*|`([^`\\n]+)`")
            val out = android.text.SpannableStringBuilder()
            val spans = ArrayList<Triple<Int, Int, Boolean>>()   // start, end, isBold
            var last = 0
            for (m in re.findAll(src)) {
                out.append(src, last, m.range.first)
                val bold = m.groups[1]
                val code = m.groups[2]
                val g = bold ?: code ?: continue
                val start = out.length
                out.append(g.value)
                spans.add(Triple(start, out.length, bold != null))
                last = m.range.last + 1
            }
            out.append(src, last, src.length)
            for ((s, e, isBold) in spans) {
                if (s >= e) continue
                if (isBold) {
                    out.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    out.setSpan(
                        android.text.style.TypefaceSpan("monospace"),
                        s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            out
        } catch (t: Throwable) {
            // 格式化失败不该让整页崩 —— 原样显示就够
            android.util.Log.w("PonkoPluginUi", "行内格式解析失败", t)
            src
        }
    }

    private fun smallHint(t: String) = TextView(act).apply {
        text = t
        textSize = 11f
        setTextColor(pal.hint)
        setPadding(0, dp(3), 0, 0)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun emptyBlock(title: String, body: String) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(20))
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
            gravity = Gravity.CENTER
        }, matchWrap())
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** 按量程决定小数位：0-1 的 cfg 要 2 位，0-100 的步数要 0 位 */
    private fun fmt(v: Double, span: Double): String = when {
        span <= 2.0 -> "%.2f".format(v)
        span <= 20.0 -> "%.1f".format(v)
        else -> Math.round(v).toString()
    }

    /** 去掉末尾多余的 0："2.00" → "2"，"0.70" → "0.7" */
    private fun trimNum(v: Double): String {
        val s = "%.4f".format(v).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            act.resources.displayMetrics).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun wrapWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
