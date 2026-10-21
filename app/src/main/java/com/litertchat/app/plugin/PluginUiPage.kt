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

    /** 外层滚动容器（build 时创建） */
    private var self: View? = null

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

        // 分组要按顺序累积，所以不能简单 forEach —— 需要维护「当前往哪个容器里加」
        // 用一个栈：GROUP_START 压、GROUP_END 弹。只有一层实际使用，但栈更安全
        val target = ArrayDeque<LinearLayout>()
        target.addLast(host)

        schema.items.forEach { item ->
            when (item.type) {
                UiType.GROUP_START -> {
                    val group = buildGroup(item)
                    target.last().addView(group.first, matchWrap())
                    target.addLast(group.second)
                }
                UiType.GROUP_END -> {
                    // 只有一个元素时弹出会把根也弹掉 —— 插件写错了也不能崩
                    if (target.size > 1) target.removeLast()
                }
                else -> {
                    val v = renderItem(item) ?: return@forEach
                    target.last().addView(v, matchWrap())
                }
            }
        }
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
        UiType.INFO -> hintText(item.label).also { applyHelp(it, item) }
        UiType.SECTION -> sectionTitle(item.label)
        UiType.SWITCH -> switchRow(item)
        UiType.SLIDER -> sliderRow(item)
        UiType.CHOICE -> choiceBlock(item)
        UiType.TEXT -> textRow(item, multiLine = false)
        UiType.TEXTAREA -> textRow(item, multiLine = true)
        UiType.ACTION -> actionRow(item)
        UiType.PROGRESS -> progressRow(item)
        UiType.STATUS -> statusRow(item)
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

    private fun hintText(t: String) = TextView(act).apply {
        text = t
        textSize = 12f
        setTextColor(pal.subText)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(6), 0, dp(4))
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
