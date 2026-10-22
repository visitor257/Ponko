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
     * 宿主侧的功能动作。
     *
     * 「试听」「朗读」这类不是插件自己实现的功能 —— 它们是**宿主**发起一次合成
     * 再播放结果。所以按钮由宿主画，插件只提供配置项（schema）。
     * 这解释了为什么卡片上不再有「试听」：功能按钮属于插件界面。
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
     * @param id 用于回传状态（同一个页面可能有多个动作）
     * @param label 按钮文字
     * @param enabled 初始是否可点。运行中要改就用 [setActionEnabled]
     */
    data class HostAction(
        val id: String,
        val label: String,
        val help: String = "",
        val enabled: Boolean = true,
        val run: () -> Unit,
    )

    /** 动作 id → 按钮，用于运行期启用/禁用（比如"正在生成时不能点试听"） */
    private val actionButtons = HashMap<String, TextView>()

    /**
     * 动作 id → **当前**标签。
     *
     * 有些按钮的文字会随状态变（「加载引擎」↔「卸载引擎」），
     * 所以文字单独存一份：只改样式时（[setActionEnabled]）不会把文字冲回初始值。
     */
    private val actionLabels = HashMap<String, String>()

    /** 内容容器。重画时清空它再填 */
    private var host: LinearLayout? = null

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

    /** 还没有 key 的日志按顺序堆在这里，[patch] 在找不到 key 时兜底用第一个 */
    private var firstLogKey: String? = null

    /**
     * 结果图：key → ImageView + 占位文字。
     *
     * 图走 [setImage] 单独送，不经过 schema —— 和音频同一个理由（Binder 配额）。
     */
    private val imageViews = HashMap<String, android.widget.ImageView>()
    private val imagePlaceholders = HashMap<String, TextView>()
    private var firstImageKey: String? = null

    /** 最近一张结果图。重建页面后要重新贴回去，否则切走再回来图就没了 */
    private var lastBitmap: android.graphics.Bitmap? = null

    /**
     * 点结果图时全屏查看。由 [PluginPage] 注入（复用它已有的 ZoomImageView）。
     *
     * 不在这里自己写一套缩放逻辑：宿主已经有一份（全屏看图），
     * 两份手势代码必然会走偏（一个支持双击、一个不支持，用户会觉得"有时候能用"）。
     */
    var zoomable: ((android.graphics.Bitmap) -> Unit)? = null

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
     * 宿主的音频播放能力。由 [PluginPage] 实现，包着它那个 MediaPlayer。
     *
     * ## 为什么不让插件自己播
     *
     * 插件在另一个进程里，宿主看不见也停不了它的播放器 ——
     * 用户退出页面后声音还在响，找不到地方关。
     */
    interface AudioHost {
        /** 有没有可播的音频（宿主最近一次合成的产物） */
        fun hasAudio(): Boolean
        fun isPlaying(): Boolean
        /** 当前位置（毫秒） */
        fun positionMs(): Int
        /** 总长（毫秒）；没有音频返回 0 */
        fun durationMs(): Int
        fun play()
        fun pause()
        fun seekTo(ms: Int)
    }

    /** 一个播放条的所有控件。[AudioHost] 变化时刷它 */
    private class AudioBar(
        val btn: TextView,
        val seek: SeekBar,
        val time: TextView,
        val empty: TextView,
        /** 用户正按着滑块时不能被 tick 覆盖 */
        var dragging: Boolean = false,
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
        // 放在插件配置上面：用户点进这一页通常是要"做点什么"，
        // 先给动作、再给参数。参数是给动作调味的，不是主角。
        if (hostActions.isNotEmpty()) {
            root.addView(buildActionBlock(), matchWrap().apply { topMargin = dp(10) })
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
        audioBars.clear()
        firstLogKey = null
        firstImageKey = null
        stopAudioTicker()
        host.removeAllViews()

        val schema = try {
            session.schemaOf(pkg)
        } catch (t: Throwable) {
            UiSchema(intro = "读取界面失败：${t.javaClass.simpleName}")
        }

        if (schema.intro.isNotBlank()) {
            host.addView(hintText(schema.intro))
        }

        if (schema.items.isEmpty()) {
            // 空 schema 是合法的 —— 这个插件没提供任何可配置项。
            // 不要显示成错误：用户点进来看到空页面，本来就是这个插件本来的样子
            host.addView(emptyBlock(
                "这个插件没有可配置项",
                "插件作者还没有为它提供界面内容。\n功能仍然可以通过上面的按钮直接使用。"
            ), matchWrap().apply { topMargin = dp(12) })
            return
        }

        // 分组 / 标签页要按顺序累积，所以不能简单 forEach —— 需要维护「当前往哪个容器里加」。
        //
        // ## 为什么是栈，且元素有两种形状
        //
        // `GROUP_START` / `TABS_START` 都会开一个新区块、`*_END` 关掉它，层数不固定
        // （用户要的「页中页中页」就是三层标签页）。用一个栈最省事。
        //
        // 但两种区块**往里加东西的方式不同**：
        // · 分组（[Slot.Box]）只有一条内容流，直接往 body 里加
        // · 标签页（[Slot.Tab]）有多个页，要往**当前选中那一页**里加
        // 所以栈元素不能只是一个 `LinearLayout`。
        val target = ArrayDeque<Slot>()
        target.addLast(Slot.Box(host))

        schema.items.forEach { item ->
            when (item.type) {
                UiType.GROUP_START -> {
                    val group = buildGroup(item)
                    target.last().content().addView(group.first, matchWrap())
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
                    val tabs = buildTabGroup(item, depth)
                    target.last().content().addView(tabs.view, matchWrap())
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
                else -> {
                    val v = renderItem(item) ?: return@forEach
                    target.last().content().addView(v, matchWrap())
                }
            }
        }

        // 插件可能漏写 TABS_END —— 那些组在 TABS_END 分支里`finish`不到，
        // 这里补一次，保证空标签条一定被藏掉、恢复上次那页也一定发生。
        // 从内到外弹：内层先收尾，再收外层（外层的高度依赖内层）
        while (target.size > 1) {
            (target.removeLast() as? Slot.Tab)?.group?.finish()
        }

        // 重画完了：播放条要立刻反映当前播放状态（否则切回页面时显示成「▶」却在响）
        refreshAudioBars()
        startAudioTicker()
    }

    // ==================== 功能区（宿主提供）====================

    /**
     * 「功能」区：宿主自己的动作按钮 + 可选的参数区 + 一行状态提示 + 诊断区。
     *
     * 为什么这块由宿主画、而不让插件在 schema 里声明一个 ACTION：
     * 这些动作的执行者是**宿主**（发起合成、播放音频、路由到别的页面），
     * 插件只是被动接受一次 `synthesize` 调用。
     * 让插件声明它，插件就得知道"宿主要怎么展示结果"，那是把宿主的事推给插件。
     *
     * 分工：**插件管"能做什么"（能力），宿主管"怎么触发"（按钮 + 参数）。**
     */
    private fun buildActionBlock(): View {
        val block = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
            background = rounded(pal.surface, 12)
        }
        block.addView(TextView(act).apply {
            text = "功能"
            textSize = 13f
            setTextColor(pal.subText)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(2))
        }, matchWrap())

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        hostActions.forEachIndexed { i, action ->
            val btn = TextView(act).apply {
                setPadding(dp(16), dp(9), dp(16), dp(9))
                applyActionText(this, action.id, action.label)
                applyActionStyle(this, action.enabled)
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
            actionButtons[action.id] = btn
            row.addView(btn, wrapWrap().apply { if (i > 0) leftMargin = dp(8) })
        }
        block.addView(row, matchWrap())

        // ---- 宿主自己的参数区（试听文本 / 模式 / 参考音频 …）----
        hostExtra?.let { builder ->
            val v = try {
                builder()
            } catch (t: Throwable) {
                // 参数区画不出来不该让整页空白 —— 页面本身还得能用
                android.util.Log.e("PonkoPluginUi", "参数区构建失败", t)
                null
            }
            if (v != null) block.addView(v, matchWrap().apply { topMargin = dp(10) })
        }

        val helpText = hostActions.firstOrNull { it.help.isNotBlank() }?.help
        if (!helpText.isNullOrBlank()) {
            block.addView(TextView(act).apply {
                text = helpText
                textSize = 11f
                setTextColor(pal.hint)
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            }, matchWrap())
        }

        val hint = TextView(act).apply {
            text = actionHintText
            textSize = 12f
            setTextColor(pal.subText)
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = if (actionHintText.isEmpty()) View.GONE else View.VISIBLE
        }
        actionHint = hint
        block.addView(hint, matchWrap())

        // ---- 诊断（默认折叠）----
        //
        // 内容来自 ResultBundle.debugInfo 和最后一条 PluginError.detail。
        // 默认收起、且**先建成 GONE** —— 结果回来的时候要用 [setDiagnostics]
        // 直接把它显示出来，不能等下次进页面才出现（那时候用户已经拿不到上下文了）。
        // 这些信息以前只进 logcat，而普通用户装不了 adb。
        val diagHead = TextView(act).apply {
            text = if (diagOpen) "诊断信息 ▾" else "诊断信息 ▸"
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
            diagHead.text = if (diagOpen) "诊断信息 ▾" else "诊断信息 ▸"
            diagBody.visibility = if (diagOpen) View.VISIBLE else View.GONE
        }
        diagHeadView = diagHead
        diagBodyView = diagBody
        block.addView(diagHead, matchWrap())
        block.addView(diagBody, matchWrap().apply { topMargin = dp(4) })
        return block
    }

    /**
     * 按钮**样式**（只改配色和可点性，不动文字）。
     *
     * 刻意和文字分开：有的按钮文字会随状态变（「加载引擎」↔「卸载引擎」），
     * 如果把 `text = ...` 也塞进来，那么一次 [setActionEnabled] 就会把
     * 已经改好的文字冲回初始值 —— 那种 bug 表现为"点了按钮，字又变回去了"。
     */
    private fun applyActionStyle(tv: TextView, enabled: Boolean) {
        tv.isEnabled = enabled
        tv.isClickable = enabled
        tv.gravity = Gravity.CENTER
        tv.textSize = 13f
        if (enabled) {
            tv.setTextColor(pal.surface)
            tv.background = rounded(pal.primary, 10)
            tv.alpha = 1f
        } else {
            tv.setTextColor(pal.hint)
            tv.background = rounded(pal.fieldBg, 10)
            tv.alpha = 0.9f
        }
    }

    /** 按钮**文字**。记进 [actionLabels]，后续只改样式时不会丢 */
    private fun applyActionText(tv: TextView, id: String, label: String) {
        actionLabels[id] = label
        tv.text = label
    }

    /**
     * 运行期启用/禁用某个功能按钮。
     *
     * 典型用途：「试听」在生成中要灰掉（否则用户会连点，插件回 BUSY），
     * 「停止」反过来只在生成中可点（平时点了没意义）。
     */
    fun setActionEnabled(id: String, enabled: Boolean) {
        val tv = actionButtons[id] ?: return
        applyActionStyle(tv, enabled)
    }

    /** 改按钮文字（比如「加载引擎」→「卸载引擎」）。不改启用态 */
    fun setActionLabel(id: String, label: String) {
        val tv = actionButtons[id] ?: return
        applyActionText(tv, id, label)
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
        val open = groupOpen[key] ?: true

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
                body.visibility = if (wasOpen) View.GONE else View.VISIBLE
                arrow.text = if (wasOpen) "▸" else "▾"
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
            override fun content(): LinearLayout = group.currentPage()
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

        /** 所有页叠在这里，靠 visibility 切换 */
        private val pagesHost = android.widget.FrameLayout(act)

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
            view.addView(pagesHost, matchWrap())
            pages.add(implicitPage)
            keys.add("")
            pagesHost.addView(implicitPage, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
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
                pagesHost.addView(page, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
                idx = pages.size - 1
            }

            val chip = buildChip(item.label.ifBlank { "页 " + (idx + 1) }, idx)
            // 权重相同 → 均分。标签多了会自动挤窄，配合单行省略号
            bar.addView(chip, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            chips.add(chip)

            // ⚠ 这里**不选页**，只刷样式。
            //
            // 两个原因：
            // 1. 此刻还没收集完所有 tab 的 key，`restoreIndex()` 找不全
            //    （上次停在第 2 页的话，第 2 个 TAB 还没建出来）
            // 2. 用户已经翻到第 3 页时，插件重发 schema 会重建整页 ——
            //    在 beginTab 里 select 会把选中项顶回第 1 页
            //
            // 真正的"恢复到上次那页"放在 [finish]（TABS_END 时）统一做一次。
            applySelection()
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
                bar.visibility = View.GONE
                view.setPadding(0, 0, 0, 0)
                return
            }
            // restoreIndex 用 keys 查，此时已是完整列表
            val want = restoreIndex()
            if (want != current) select(want) else settleCurrentPage()
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
            settleCurrentPage()
        }

        /**
         * 只刷样式，不动 [current]。
         *
         * 单独拆出来是因为 [beginTab] 里新加一个标签之后也要刷一遍样式
         * （新 chip 还是默认色），但**不能**顺手改选中项。
         */
        private fun applySelection() {
            pages.forEachIndexed { i, p ->
                p.visibility = if (i == current) View.VISIBLE else View.GONE
            }
            chips.forEachIndexed { i, c ->
                val sel = i == current
                c.setTextColor(if (sel) pal.primary else pal.subText)
                c.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // 选中项垫一块 surface 底：光变色在浅色主题下不够明显
                c.background = if (sel) rounded(pal.surface, 8) else null
            }
        }

        /**
         * 切页后的收尾：把当前页里的日志框重新粘到底。
         *
         * 隐藏期间 View 没被测量，`isAtBottom` 读到的高度不可靠，
         * 不管的话切回来会停在半路。等这一帧布局完成再滚。
         */
        private fun settleCurrentPage() {
            val page = pages.getOrNull(current) ?: return
            page.post { scrollLogsToBottomIn(page) }
        }

        fun currentPage(): LinearLayout = pages.getOrNull(current) ?: implicitPage
    }

    /** 在一棵子树里把所有日志框滚到底 */
    private fun scrollLogsToBottomIn(root: View) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            when (val v = stack.removeLast()) {
                is android.widget.ScrollView ->
                    if (v.tag == LOG_SCROLL_TAG) scrollToBottom(v)
                is ViewGroup ->
                    for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            }
        }
    }

    /** 给日志框的 ScrollView 打的标记，[scrollLogsToBottomIn] 靠它认出来 */
    private val LOG_SCROLL_TAG = "ponko:log"

    /**
     * 建一组标签页。
     *
     * @param depth 嵌套深度（外层 0）。由调用方数**当前栈里已有几层标签页**得出，
     *              不自增计数器 —— 那种计数器一旦遇到插件写错的
     *              `TABS_END`（少写/多写）就会永久偏掉。
     */
    private fun buildTabGroup(item: com.litertchat.plugin.UiItem, depth: Int): TabGroup =
        TabGroup(item.key, depth)

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
            if (firstLogKey == null) firstLogKey = item.key
        }
        if (item.help.isNotBlank()) box.addView(smallHint(item.help), matchWrap())
        // 初始内容就已经很长时，先滚到底 —— 用户想看的通常是最后几行
        scroll.post { scrollToBottom(scroll) }
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
            if (firstImageKey == null) firstImageKey = item.key
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

        row.addView(btn, wrapWrap())
        row.addView(seek, LinearLayout.LayoutParams(0, dp(32), 1f))
        row.addView(time, wrapWrap())
        box.addView(row, matchWrap())
        box.addView(empty, matchWrap())

        val bar = AudioBar(btn, seek, time, empty)
        val key = item.key.ifBlank { "audio#${audioBars.size}" }
        audioBars[key] = bar

        btn.setOnClickListener {
            val h = audioHost ?: return@setOnClickListener
            if (!h.hasAudio()) return@setOnClickListener
            if (h.isPlaying()) h.pause() else h.play()
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
                val dur = h.durationMs()
                if (dur > 0) h.seekTo(dur * (sb?.progress ?: 0) / 1000)
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
     * @param key 目标 item 的 key。空或对不上时贴到页面里**第一个**图片控件 ——
     *            只有一个图片控件是最常见的情况，这样插件不写 key 也能用。
     */
    fun setImage(bmp: android.graphics.Bitmap, key: String? = null) {
        lastBitmap = bmp
        val target = imageViews[key ?: ""] ?: imageViews[firstImageKey] ?: run {
            // 页面上没有图片控件：插件送了图但没声明 IMAGE 项，属于插件侧写错。
            // 不弹错误（用户看不懂），只留日志
            android.util.Log.w("PonkoPluginUi", "收到结果图，但当前页面没有 IMAGE 控件")
            return
        }
        val ph = imagePlaceholders[key ?: ""] ?: imagePlaceholders[firstImageKey]
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
        // 4) key 对不上 —— 插件常不写 key，而整页通常只有一个日志框。
        //    只有这种情况下才兜底，绝不能"猜"到别的控件上去。
        if (logBodies.size == 1) {
            val k = firstLogKey ?: return
            val body = logBodies[k] ?: return
            appendLog(k, body, value, append)
        }
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
            scroll.post { scrollToBottom(scroll) }
        }
        if (outer != null) {
            outer.post { if (outer.scrollY != outerY) outer.scrollTo(0, outerY) }
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
        // 宿主没提供播放能力时，所有播放条都显示成"没有可播放的音频"
        val has = h?.hasAudio() == true
        val dur = if (has) h?.durationMs() ?: 0 else 0
        val pos = if (has) h?.positionMs() ?: 0 else 0
        val playing = has && h?.isPlaying() == true

        audioBars.values.forEach { bar ->
            bar.btn.visibility = if (has) View.VISIBLE else View.GONE
            bar.seek.visibility = if (has) View.VISIBLE else View.GONE
            bar.time.visibility = if (has) View.VISIBLE else View.GONE
            bar.empty.visibility = if (has) View.GONE else View.VISIBLE
            if (!has) return@forEach

            bar.btn.text = if (playing) "⏸" else "▶"
            bar.time.text = "${fmtMs(pos)} / ${fmtMs(dur)}"
            // 用户正按着滑块时不能改它的位置 —— 手指和控件会打架
            if (!bar.dragging) {
                bar.seek.progress = if (dur > 0) (pos.toLong() * 1000 / dur).toInt() else 0
            }
        }
    }

    /** 让某个播放条跟着播放器走。播放中每 200ms 刷一次，暂停就停 */
    private fun startAudioTicker() {
        val running = audioHost?.isPlaying() == true
        if (!running) {
            audioTicker?.let { handler.removeCallbacks(it) }
            audioTicker = null
            return
        }
        if (audioTicker != null) return
        val r = object : Runnable {
            override fun run() {
                if (audioHost?.isPlaying() != true) {
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

    /** 按钮（ACTION） */
    private fun actionRow(item: UiItem): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
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
