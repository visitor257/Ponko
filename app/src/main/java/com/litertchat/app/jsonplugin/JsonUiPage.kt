// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.jsonplugin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.litertchat.app.PonkoTheme
import com.litertchat.app.R
import com.litertchat.app.draw.SdCppEngine
import com.litertchat.app.plugin.PluginSession
import com.litertchat.app.video.FramesView
import com.litertchat.app.video.VideoRunner
import java.io.File

/**
 * **JSON 插件的页面** —— 数据来自一份 `.json`，执行全部归宿主。
 *
 * 和 `PluginUiPage`（Ponko-EX 插件的界面）的关系：同一个思路（插件只描述，宿主渲染），
 * 但来源不同 —— 那边是跨进程 `getSettingsSchema()`，这边是本地文件。所以没有复用
 * 那个 3600 行的类：它绑着 `PluginSession`（拿 schema、写值、回调插件），
 * 为 JSON 拆出一层接口会把那条稳定链路改出风险，而这里需要的东西少得多。
 *
 * ## 值存在哪
 *
 * 按插件 id 各存一份 SharedPreferences。JSON **自己不存任何状态** ——
 * 它是只读的数据文件，用户改了控件值不该回写文件（那会让"我这份 JSON 有没有被改过"
 * 变得不可判断）。
 *
 * ## 动作怎么执行
 *
 * `actions` 里一条动作 = 一个调用。唯一的变量是 `$ui.<key>`，取页面上那个控件的值。
 * 产物、进度、状态都归执行者 —— JSON 只写"调谁、带什么"。
 */
class JsonUiPage(
    private val act: Activity,
    private val pal: PonkoTheme,
    private val plugin: JsonPlugin,
    private val onBack: () -> Unit,
) {

    // ==================== 页面模型 ====================

    private sealed class Node {
        class Field(val item: JsonPlugin.Item) : Node()
        class Row(val weights: List<Float>, val children: MutableList<Node>) : Node()
        class Group(val title: String, val children: MutableList<Node>) : Node()
        class Divider : Node()
        class Spacer : Node()
    }

    private class TabDef(val key: String, val label: String, val nodes: MutableList<Node>)

    private val prefs = JsonPlugins.prefs(act, plugin.id)
    private val values = HashMap<String, String>()
    private val keyed = HashMap<String, View>()
    private var session: PluginSession? = null

    /** 按"角色"找控件：进度 / 状态 / 日志 / 预览图 / 帧序列。取每类第一个。 */
    private var progressKey: String? = null
    private var statusKey: String? = null
    private var logKey: String? = null
    private var imageKey: String? = null
    private var framesKey: String? = null

    /** 按钮的调用名 -> 按钮集合，用来在任务进行中禁用「生成」、启用「停止」 */
    private val buttonsByCall = HashMap<String, MutableList<TextView>>()

    private var tabs: List<TabDef> = emptyList()
    private var tabPanels: List<View> = emptyList()
    private var tabIndex = 0
    private var tabChips: LinearLayout? = null

    private var framesView: FramesView? = null

    // ==================== 构建 ====================

    fun build(): View {
        // 先把各控件值从偏好读出来，缺失的用 JSON 里的默认值
        for (it in plugin.items) {
            if (it.key.isEmpty()) continue
            values[it.key] = prefs.getString(it.key, null) ?: it.value
        }

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        root.addView(buildHeader())

        val (pre, tabDefs) = parsePage()
        tabs = tabDefs

        // 标签页放页面顶部（不进滚动区）：页内页的信封结构，滚走就不好切了
        if (tabs.size > 1) root.addView(buildTabBar())

        val scroll = ScrollView(act)
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(28))
        }
        renderNodes(pre, content)
        if (tabs.size > 1) {
            // 每个标签页各建一份、只显示当前那个。
            //
            // 为什么不"切到哪个才建哪个"：结果页里的帧序列、预览图、日志都是
            // **按类型找第一个控件**来更新的。没建出来就收不到更新 —— 用户在参数页
            // 点生成、切到结果页才看到一片空白。全部建好、藏起来，就不存在这个时序问题。
            tabPanels = tabs.map { t ->
                val panel = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                renderNodes(t.nodes, panel)
                content.addView(panel, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
                panel
            }
            showPanel(0)
        } else if (tabs.size == 1) {
            renderNodes(tabs[0].nodes, content)
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildHeader(): View {
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
        }
        val back = TextView(act).apply {
            text = act.getString(R.string.s_785)
            textSize = 13f
            setTextColor(pal.primary)
            setPadding(0, dp(2), 0, dp(4))
            isClickable = true
            setOnClickListener { onBack() }
        }
        head.addView(back)
        head.addView(TextView(act).apply {
            text = plugin.name
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.text)
        })
        if (plugin.author.isNotEmpty()) {
            head.addView(TextView(act).apply {
                text = plugin.author
                textSize = 11f
                setTextColor(pal.hint)
            })
        }
        if (plugin.intro.isNotEmpty()) {
            head.addView(TextView(act).apply {
                text = plugin.intro
                textSize = 12f
                setTextColor(pal.subText)
                setPadding(0, dp(6), 0, dp(2))
            })
        }
        // 声明的权限：一眼能看出"这一页会调别的东西"
        if (plugin.requiresHostActions.isNotEmpty()) {
            head.addView(TextView(act).apply {
                text = act.getString(R.string.s_786, plugin.requiresHostActions.joinToString(" / "))
                textSize = 10.5f
                setTextColor(pal.hint)
                setPadding(0, dp(4), 0, 0)
            })
        }
        return head
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }
        tabChips = bar
        tabs.forEachIndexed { i, t ->
            val chip = TextView(act).apply {
                text = t.label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                setOnClickListener {
                    if (tabIndex == i) return@setOnClickListener
                    tabIndex = i
                    refreshTabChips()
                    showPanel(i)
                }
            }
            bar.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        refreshTabChips()
        return bar
    }

    private fun refreshTabChips() {
        val bar = tabChips ?: return
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i) as? TextView ?: continue
            val on = i == tabIndex
            v.setTextColor(if (on) pal.onPrimary else pal.text)
            v.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (on) setColor(pal.primary) else {
                    setColor(pal.chipBg)
                    setStroke(dp(1), pal.border)
                }
            }
        }
    }

    private fun showPanel(i: Int) {
        tabPanels.forEachIndexed { idx, v ->
            v.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
    }

    // ==================== 解析 ====================

    private inner class Cursor(val items: List<JsonPlugin.Item>) {
        var pos = 0

        fun parseUntil(endTypes: Set<Int>, out: MutableList<Node>) {
            while (pos < items.size) {
                val it = items[pos]
                if (it.type in endTypes) return
                pos++
                when (it.type) {
                    U_GROUP_START -> {
                        val kids = ArrayList<Node>()
                        parseUntil(setOf(U_GROUP_END), kids)
                        pos++    // 吃掉 GROUP_END
                        out.add(Node.Group(it.label, kids))
                    }
                    U_ROW_START, U_GRID_START -> {
                        val end = if (it.type == U_ROW_START) U_ROW_END else U_GRID_END
                        val kids = ArrayList<Node>()
                        parseUntil(setOf(end), kids)
                        pos++
                        val w = it.value.split(",").mapNotNull { s -> s.trim().toFloatOrNull() }
                        out.add(Node.Row(w, kids))
                    }
                    U_DIVIDER -> out.add(Node.Divider())
                    U_SPACER -> out.add(Node.Spacer())
                    U_TABS_START, U_TABS_END -> Unit   // 内层出现：忽略
                    else -> out.add(Node.Field(it))
                }
            }
        }
    }

    /** 返回 (标签页之外的节点, 标签页)。没有 TABS_START 时全部进第一项。 */
    private fun parsePage(): Pair<MutableList<Node>, List<TabDef>> {
        val cur = Cursor(plugin.items)
        val pre = ArrayList<Node>()
        cur.parseUntil(setOf(U_TABS_START), pre)
        if (cur.pos >= plugin.items.size || plugin.items[cur.pos].type != U_TABS_START) {
            return pre to listOf(TabDef("", "", pre))
        }
        cur.pos++    // 跳过 TABS_START
        val defs = ArrayList<TabDef>()
        while (cur.pos < plugin.items.size && plugin.items[cur.pos].type != U_TABS_END) {
            val head = plugin.items[cur.pos]
            if (head.type != U_TAB) {
                cur.pos++
                continue
            }
            cur.pos++
            val kids = ArrayList<Node>()
            cur.parseUntil(setOf(U_TAB, U_TABS_END), kids)
            defs.add(TabDef(head.key, head.label.ifEmpty { head.key }, kids))
        }
        if (defs.isEmpty()) return pre to listOf(TabDef("", "", pre))
        return pre to defs
    }

    // ==================== 渲染 ====================

    private fun renderNodes(nodes: List<Node>, parent: LinearLayout) {
        for (n in nodes) {
            when (n) {
                is Node.Field -> renderField(n.item, parent)
                is Node.Group -> {
                    parent.addView(sectionTitle(n.title))
                    val box = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(6), 0, 0, dp(4))
                    }
                    renderNodes(n.children, box)
                    parent.addView(box)
                }
                is Node.Row -> {
                    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                    n.children.forEachIndexed { idx, child ->
                        val cell = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                        renderNodes(listOf(child), cell)
                        val w = n.weights.getOrNull(idx) ?: 1f
                        row.addView(cell, LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, w.coerceAtLeast(0.01f)
                        ).apply { if (idx > 0) leftMargin = dp(6) })
                    }
                    parent.addView(row, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                }
                is Node.Divider -> parent.addView(View(act).apply {
                    setBackgroundColor(pal.divider)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    .apply { topMargin = dp(10); bottomMargin = dp(10) })
                is Node.Spacer -> parent.addView(View(act), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))
            }
        }
    }

    private fun renderField(item: JsonPlugin.Item, parent: LinearLayout) {
        when (item.type) {
            U_INFO -> {
                parent.addView(TextView(act).apply {
                    text = item.label
                    textSize = 12f
                    setTextColor(pal.subText)
                    setPadding(0, dp(4), 0, dp(2))
                })
                if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
            }
            U_SECTION -> parent.addView(sectionTitle(item.label))
            U_SWITCH -> {
                val cb = CheckBox(act).apply {
                    text = item.label
                    textSize = 13f
                    setTextColor(pal.text)
                    isChecked = valueOf(item).equals("true", true)
                    setOnCheckedChangeListener { _, v -> setValue(item.key, if (v) "true" else "false") }
                }
                parent.addView(cb)
                if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
            }
            U_SLIDER -> renderSlider(item, parent)
            U_CHOICE -> renderChoice(item, parent, null)
            U_MODEL_SET -> renderModelSet(item, parent)
            U_TEXT, U_TEXTAREA -> renderText(item, parent)
            U_ACTION -> renderAction(item, parent)
            U_PROGRESS -> renderProgress(item, parent)
            U_STATUS -> renderStatus(item, parent)
            U_LOG -> renderLog(item, parent)
            U_IMAGE -> renderImage(item, parent)
            U_FRAMES -> renderFrames(item, parent)
            U_AUDIO, U_MODEL_SLOT, U_HOST_SLOT ->
                parent.addView(helpText(act.getString(R.string.s_787, item.type)))
            else -> parent.addView(helpText(act.getString(R.string.s_788, item.type)))
        }
    }

    private fun renderSlider(item: JsonPlugin.Item, parent: LinearLayout) {
        val min = if (item.max > item.min) item.min else 0.0
        val max = if (item.max > item.min) item.max else 100.0
        val step = if (item.step > 0) item.step else 1.0
        val steps = ((max - min) / step).toInt().coerceIn(1, 1000)
        val decimals = if (step >= 1.0) 0 else if (step >= 0.1) 1 else 2

        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(act).apply {
            text = item.label
            textSize = 12.5f
            setTextColor(pal.text)
        }
        head.addView(title, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = TextView(act).apply {
            textSize = 12.5f
            setTextColor(pal.primary)
        }
        head.addView(valueText)
        parent.addView(head, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val bar = SeekBar(act).apply { this.max = steps }
        fun fmt(v: Double): String =
            String.format(java.util.Locale.US, "%.${decimals}f", v)
        fun paint(v: Double) {
            valueText.text = fmt(v)
        }
        fun current(): Double =
            (valueOf(item).toDoubleOrNull() ?: min).coerceIn(min, max)

        paint(current())
        bar.progress = (((current() - min) / step).toInt()).coerceIn(0, steps)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + progress * step
                paint(v)
                setValue(item.key, if (decimals == 0) v.toInt().toString() else fmt(v))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        parent.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
    }

    /**
     * 单选。用一排 chip 而不是下拉：选项少（4~6 个）时点一下就切完，
     * 而且当前值始终可见 —— 下拉要点开才知道选了什么。
     */
    private fun renderChoice(item: JsonPlugin.Item, parent: LinearLayout, optionsIn: List<JsonPlugin.Option>?) {
        val options = optionsIn ?: item.options
        parent.addView(TextView(act).apply {
            text = item.label
            textSize = 12.5f
            setTextColor(pal.text)
            setPadding(0, dp(6), 0, 0)
        })
        val chips = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val scroller = android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        fun paint() {
            val cur = valueOf(item)
            chips.removeAllViews()
            for (o in options) {
                val on = o.value == cur
                chips.addView(TextView(act).apply {
                    text = o.label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    setTextColor(if (on) pal.onPrimary else pal.text)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        if (on) setColor(pal.primary) else {
                            setColor(pal.chipBg)
                            setStroke(dp(1), pal.border)
                        }
                    }
                    isClickable = true
                    setOnClickListener {
                        setValue(item.key, o.value)
                        paint()
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
            }
        }
        paint()
        parent.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
    }

    /**
     * 模型集选择（宿主扩展，type 27）。
     *
     * 选项来自宿主的模型列表，而不是 JSON 里写死 —— 用户导入哪个模型集是运行时的事，
     * 写死在数据文件里等于逼他改文件。值存的是模型集 id。
     */
    private fun renderModelSet(item: JsonPlugin.Item, parent: LinearLayout) {
        val sets = VideoRunner.listSets(act)
        val opts = sets.map { JsonPlugin.Option(it.id, if (it.family == "video") it.name else "${it.name}") }
        if (opts.isEmpty()) {
            parent.addView(TextView(act).apply {
                text = item.label
                textSize = 12.5f
                setTextColor(pal.text)
                setPadding(0, dp(6), 0, 0)
            })
            parent.addView(helpText(act.getString(R.string.s_789)))
            return
        }
        // 存着的 id 已经不存在（用户删了那个集）→ 落到第一个
        val cur = valueOf(item)
        if (opts.none { it.value == cur }) {
            setValue(item.key, opts.first().value)
        }
        renderChoice(item, parent, opts)
    }

    private fun renderText(item: JsonPlugin.Item, parent: LinearLayout) {
        parent.addView(TextView(act).apply {
            text = item.label
            textSize = 12.5f
            setTextColor(pal.text)
            setPadding(0, dp(6), 0, dp(2))
        })
        val edit = EditText(act).apply {
            setText(valueOf(item))
            textSize = 13f
            setTextColor(pal.text)
            setHintTextColor(pal.hint)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(pal.fieldBg)
                setStroke(dp(1), pal.border)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            if (item.type == U_TEXTAREA) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    setValue(item.key, s?.toString().orEmpty())
                }
            })
        }
        parent.addView(edit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
    }

    private fun renderAction(item: JsonPlugin.Item, parent: LinearLayout) {
        val btn = actionButton(item.label.ifEmpty { item.key }, item.primary) { dispatch(item) }
        btn.isEnabled = item.enabled
        btn.alpha = if (item.enabled) 1f else 0.5f
        val action = actionOf(item)
        action?.let { a ->
            buttonsByCall.getOrPut(a.call) { ArrayList() }.add(btn)
            // 停止类按钮的初始可用状态由运行状态决定，JSON 里那个 enabled 只是"没跑时该不该亮"
            if (a.call == HostActions.CANCEL) {
                btn.isEnabled = false
                btn.alpha = 0.5f
            }
        }
        parent.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
    }

    private fun renderProgress(item: JsonPlugin.Item, parent: LinearLayout) {
        if (progressKey == null && item.key.isNotEmpty()) progressKey = item.key
        if (item.label.isNotEmpty()) {
            parent.addView(TextView(act).apply {
                text = item.label
                textSize = 12f
                setTextColor(pal.subText)
                setPadding(0, dp(6), 0, dp(2))
            })
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = (valueOf(item).toFloatOrNull() ?: 0f).toInt()
        }
        if (item.key.isNotEmpty()) keyed[item.key] = bar
        parent.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun renderStatus(item: JsonPlugin.Item, parent: LinearLayout) {
        if (statusKey == null && item.key.isNotEmpty()) statusKey = item.key
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(pal.fieldBg)
            }
        }
        row.addView(TextView(act).apply {
            text = item.label
            textSize = 12f
            setTextColor(pal.subText)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(act).apply {
            text = valueOf(item)
            textSize = 12f
            setTextColor(pal.text)
            gravity = Gravity.END
        }
        row.addView(value)
        if (item.key.isNotEmpty()) keyed[item.key] = value
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun renderLog(item: JsonPlugin.Item, parent: LinearLayout) {
        if (logKey == null && item.key.isNotEmpty()) logKey = item.key
        if (item.label.isNotEmpty()) {
            parent.addView(TextView(act).apply {
                text = item.label
                textSize = 12f
                setTextColor(pal.subText)
                setPadding(0, dp(8), 0, dp(2))
            })
        }
        val log = TextView(act).apply {
            textSize = 10.5f
            setTextColor(pal.subText)
            typeface = Typeface.MONOSPACE
            setText(valueOf(item))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(pal.fieldBg)
            }
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(act).apply {
            addView(log, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (item.key.isNotEmpty()) keyed[item.key] = log
        parent.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(2) })
        if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
    }

    private fun renderImage(item: JsonPlugin.Item, parent: LinearLayout) {
        if (imageKey == null && item.key.isNotEmpty()) imageKey = item.key
        if (item.label.isNotEmpty()) {
            parent.addView(TextView(act).apply {
                text = item.label
                textSize = 12f
                setTextColor(pal.subText)
                setPadding(0, dp(8), 0, dp(2))
            })
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(pal.fieldBg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val placeholder = TextView(act).apply {
            text = item.value
            textSize = 12f
            setTextColor(pal.hint)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
        }
        val img = ImageView(act).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        box.addView(placeholder)
        box.addView(img)
        if (item.key.isNotEmpty()) keyed[item.key] = img
        parent.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        // 占位文字由 ImageView 的 tag 带上，收到图后隐藏
        img.tag = placeholder
        if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
    }

    private fun renderFrames(item: JsonPlugin.Item, parent: LinearLayout) {
        if (framesKey == null && item.key.isNotEmpty()) framesKey = item.key
        if (item.label.isNotEmpty()) {
            parent.addView(TextView(act).apply {
                text = item.label
                textSize = 12f
                setTextColor(pal.subText)
                setPadding(0, dp(8), 0, dp(2))
            })
        }
        val fv = FramesView(act, pal)
        framesView = fv
        if (item.key.isNotEmpty()) keyed[item.key] = fv
        parent.addView(fv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        if (item.help.isNotEmpty()) parent.addView(helpText(item.help))
    }

    // ==================== 值 ====================

    private fun valueOf(item: JsonPlugin.Item): String =
        values[item.key] ?: item.value

    private fun setValue(key: String, v: String) {
        if (key.isEmpty()) return
        values[key] = v
        prefs.edit().putString(key, v).apply()
    }

    /** `$ui.key` → 控件值；其它字符串原样返回 */
    private fun resolve(s: String): String {
        val prefix = "\$ui."
        return if (s.startsWith(prefix)) values[s.substring(prefix.length)].orEmpty() else s
    }

    // ==================== 动作 ====================

    /**
     * 一个按钮该执行哪条动作。
     *
     * 三种写法（按优先级）：
     *   · `value = "run:generate"` → 查 actions["generate"]（`run:` 只是可读前缀）
     *   · `value = "host.video"`   → 直接点名宿主动作，参数取 actions[key] 里那份
     *   · 空                      → 查 actions[item.key]
     */
    private fun actionOf(item: JsonPlugin.Item): JsonPlugin.Action? {
        val v = item.value.trim()
        return when {
            v.startsWith("run:") -> plugin.actions[v.removePrefix("run:")]
            v.startsWith("host.") -> plugin.actions[item.key] ?: JsonPlugin.Action(
                id = item.key.ifEmpty { v }, call = v, plugin = null,
                args = emptyMap(), confirm = false,
            )
            else -> plugin.actions[item.key]
        }
    }

    private fun dispatch(item: JsonPlugin.Item) {
        val action = actionOf(item) ?: run {
            appendLog(act.getString(R.string.s_790, item.key))
            return
        }
        // 白名单：宿主只认自己声明过的动作，其余一律说清楚，别让它静默失败。
        // `host.*` 与 `plugin.*` 都放行到下一层判断 —— plugin.* 的执行者是别的插件，
        // 这里只能核"有没有写 plugin"（有没有这个插件由 PluginSession 判断）。
        if (!action.call.startsWith("host.") && !action.call.startsWith("plugin.")) {
            appendLog(act.getString(R.string.s_791, action.call))
            return
        }
        if (action.call.startsWith("host.") && action.call !in HostActions.ALL) {
            appendLog(act.getString(R.string.s_791, action.call))
            return
        }
        if (action.confirm) {
            AlertDialog.Builder(act)
                .setTitle(item.label.ifEmpty { action.call })
                .setMessage(act.getString(R.string.s_792, item.label.ifEmpty { action.call }))
                .setPositiveButton(R.string.s_793) { _, _ -> execute(action) }
                .setNegativeButton(R.string.s_794, null)
                .show()
        } else {
            execute(action)
        }
    }

    private fun execute(action: JsonPlugin.Action) {
        when {
            action.call == HostActions.VIDEO -> runVideo(action)
            action.call == HostActions.CANCEL -> {
                VideoRunner.cancel()
                setStatusText(act.getString(R.string.s_795))
            }
            action.call == HostActions.VIDEO_UNLOAD -> {
                VideoRunner.release()
                setStatusText(act.getString(R.string.s_796))
            }
            action.call == HostActions.VIDEO_LOAD -> {
                val args = action.args.mapValues { resolve(it.value) }
                val setId = args["set"].orEmpty()
                if (setId.isEmpty()) {
                    appendLog(act.getString(R.string.s_800))
                    return
                }
                setRunning(true)
                setProgress(0f)
                setStatusText(act.getString(R.string.s_801))
                VideoRunner.preload(act, setId, object : VideoRunner.Listener {
                    override fun onLoading(step: Int, total: Int) {
                        if (total > 0) setProgress(step * 100f / total)
                        setStatusText(act.getString(R.string.s_802, if (total > 0) step * 100 / total else 0))
                    }

                    override fun onLog(line: String) = appendLog(line)

                    override fun onLoaded() {
                        setRunning(false)
                        setProgress(100f)
                        setStatusText(act.getString(R.string.s_813))
                    }

                    override fun onFailed(reason: String?) {
                        setRunning(false)
                        setStatusText(
                            if (reason.isNullOrBlank()) act.getString(R.string.s_806)
                            else act.getString(R.string.s_807, reason)
                        )
                    }
                })
            }
            action.call.startsWith("plugin.") -> runPluginAction(action)
            else -> appendLog(act.getString(R.string.s_791, action.call))
        }
    }

    // ==================== host.video ====================

    private fun runVideo(action: JsonPlugin.Action) {
        val args = action.args.mapValues { resolve(it.value) }
        val prompt = args["prompt"].orEmpty().trim()
        if (prompt.isEmpty()) {
            appendLog(act.getString(R.string.s_798))
            return
        }
        if (VideoRunner.isRunning()) {
            appendLog(act.getString(R.string.s_799))
            return
        }
        val setId = args["set"].orEmpty()
        val req = VideoRunner.Req(
            setId = setId,
            prompt = prompt,
            negative = args["negative_prompt"] ?: args["negative"].orEmpty(),
            width = args["width"]?.toIntOrNull() ?: 832,
            height = args["height"]?.toIntOrNull() ?: 480,
            steps = args["sample_steps"]?.toIntOrNull() ?: args["steps"]?.toIntOrNull() ?: 20,
            cfg = args["txt_cfg"]?.toFloatOrNull() ?: args["cfg"]?.toFloatOrNull() ?: 6f,
            seed = args["seed"]?.toLongOrNull()?.takeIf { it > 0 } ?: -1L,
            sampler = samplerOf(args["sample_method"]),
            scheduler = schedulerOf(args["scheduler"]),
            frames = args["video_frames"]?.toIntOrNull() ?: args["frames"]?.toIntOrNull() ?: 33,
            fps = args["fps"]?.toIntOrNull() ?: 16,
        )
        if (setId.isEmpty()) {
            appendLog(act.getString(R.string.s_800))
            return
        }
        setRunning(true)
        setProgress(0f)
        setStatusText(act.getString(R.string.s_801))
        trimLog()

        VideoRunner.start(act, req, object : VideoRunner.Listener {
            override fun onLoading(step: Int, total: Int) {
                if (total > 0) setProgress(step * 100f / total)
                setStatusText(act.getString(R.string.s_802, if (total > 0) step * 100 / total else 0))
            }

            override fun onSampling(step: Int, total: Int, elapsedSec: Int, etaSec: Int) {
                if (total > 0) setProgress(step * 100f / total)
                setStatusText(act.getString(R.string.s_803, step, total, elapsedSec, etaSec))
            }

            override fun onPreview(bmp: android.graphics.Bitmap) {
                showBitmap(bmp)
            }

            override fun onLog(line: String) {
                appendLog(line)
            }

            override fun onDone(frames: List<File>, fps: Int) {
                setRunning(false)
                setProgress(100f)
                setStatusText(act.getString(R.string.s_804, frames.size))
                framesView?.setFrames(frames, fps)
                appendLog(act.getString(R.string.s_805, frames.firstOrNull()?.parent ?: ""))
            }

            override fun onFailed(reason: String?) {
                setRunning(false)
                setStatusText(
                    if (reason.isNullOrBlank()) act.getString(R.string.s_806)
                    else act.getString(R.string.s_807, reason)
                )
            }

            override fun onCancelled() {
                setRunning(false)
                setStatusText(act.getString(R.string.s_795))
            }
        })
    }

    // ==================== plugin.* ====================

    private fun ensureSession(): PluginSession {
        session?.let { return it }
        val s = PluginSession(act)
        s.listener = object : PluginSession.Listener {
            override fun onPluginsChanged(items: List<PluginSession.Item>) {}
            override fun onSpeakDone(pkg: String, result: com.litertchat.plugin.ResultBundle) {
                appendLog(act.getString(R.string.s_808, pkg, result.durationMs))
            }
            override fun onSpeakError(pkg: String, err: com.litertchat.plugin.PluginError) {
                appendLog("$pkg：${err.message}")
            }
        }
        s.refresh()
        session = s
        return s
    }

    private fun runPluginAction(action: JsonPlugin.Action) {
        val args = action.args.mapValues { resolve(it.value) }
        val pkg = action.plugin ?: args["plugin"].orEmpty()
        if (pkg.isEmpty()) {
            appendLog(act.getString(R.string.s_809))
            return
        }
        val s = ensureSession()
        when (action.call) {
            "plugin.set" -> {
                val k = args["key"].orEmpty().ifEmpty { args["setting"].orEmpty() }
                if (k.isEmpty()) return appendLog(act.getString(R.string.s_810))
                s.setSetting(pkg, k, args["value"].orEmpty())
                appendLog(act.getString(R.string.s_811, "$pkg.$k"))
            }
            "plugin.invoke" -> {
                val k = args["key"].orEmpty()
                if (k.isEmpty()) return appendLog(act.getString(R.string.s_810))
                s.invokeAction(pkg, k)
                appendLog(act.getString(R.string.s_811, "$pkg.$k"))
            }
            "plugin.engine" -> {
                val load = args["load"].orEmpty().equals("true", true)
                s.toggleEngine(pkg, load)
                appendLog(act.getString(R.string.s_811, "$pkg.engine=$load"))
            }
            "plugin.synthesize" -> {
                val text = args["text"].orEmpty()
                if (text.isEmpty()) return appendLog(act.getString(R.string.s_798))
                s.speakTo(
                    pkg = pkg,
                    text = text,
                    voiceDescription = args["voiceDescription"].orEmpty(),
                    seed = args["seed"]?.toIntOrNull() ?: 0,
                )
                appendLog(act.getString(R.string.s_812, pkg))
            }
            else -> appendLog(act.getString(R.string.s_791, action.call))
        }
    }

    // ==================== 控件更新 ====================

    private fun setProgress(pct: Float) {
        val key = progressKey ?: return
        (keyed[key] as? ProgressBar)?.progress = pct.coerceIn(0f, 100f).toInt()
    }

    private fun setStatusText(text: String) {
        val key = statusKey ?: return
        (keyed[key] as? TextView)?.text = text
    }

    private fun appendLog(line: String) {
        val key = logKey ?: return
        val tv = keyed[key] as? TextView ?: return
        val old = tv.text.toString()
        tv.text = if (old.isEmpty()) line else "$old\n$line"
    }

    private fun trimLog() {
        val key = logKey ?: return
        val tv = keyed[key] as? TextView ?: return
        tv.text = ""
    }

    private fun showBitmap(bmp: android.graphics.Bitmap) {
        val key = imageKey ?: return
        val iv = keyed[key] as? ImageView ?: return
        (iv.tag as? View)?.visibility = View.GONE
        iv.visibility = View.VISIBLE
        iv.setImageBitmap(bmp)
    }

    private fun setRunning(on: Boolean) {
        buttonsByCall[HostActions.VIDEO]?.forEach {
            it.isEnabled = !on
            it.alpha = if (on) 0.5f else 1f
        }
        buttonsByCall[HostActions.CANCEL]?.forEach {
            it.isEnabled = on
            it.alpha = if (on) 1f else 0.5f
        }
    }

    // ==================== 枚举解析 ====================

    /** sd.cpp 的采样器名字 → 枚举。认名字也认数字，写错了退回 Euler 并留一行日志。 */
    private fun samplerOf(name: String?): SdCppEngine.Sampler {
        val s = name?.trim().orEmpty()
        if (s.isEmpty()) return SdCppEngine.Sampler.EULER
        s.toIntOrNull()?.let { code ->
            SdCppEngine.Sampler.entries.firstOrNull { it.code == code }?.let { return it }
        }
        val key = s.lowercase().replace("-", "_").replace(" ", "_")
        val aliases = mapOf(
            "euler" to SdCppEngine.Sampler.EULER,
            "euler_a" to SdCppEngine.Sampler.EULER_A,
            "heun" to SdCppEngine.Sampler.HEUN,
            "dpm2" to SdCppEngine.Sampler.DPM2,
            "dpm++2s_a" to SdCppEngine.Sampler.DPMPP2S_A,
            "dpm++2m" to SdCppEngine.Sampler.DPMPP2M,
            "dpm++2m_v2" to SdCppEngine.Sampler.DPMPP2M_V2,
            "ipndm" to SdCppEngine.Sampler.IPNDM,
            "ipndm_v" to SdCppEngine.Sampler.IPNDM_V,
            "lcm" to SdCppEngine.Sampler.LCM,
            "ddim_trailing" to SdCppEngine.Sampler.DDIM_TRAILING,
            "ddim" to SdCppEngine.Sampler.DDIM_TRAILING,
            "tcd" to SdCppEngine.Sampler.TCD,
            "res_multistep" to SdCppEngine.Sampler.RES_MULTISTEP,
            "res_2s" to SdCppEngine.Sampler.RES_2S,
            "er_sde" to SdCppEngine.Sampler.ER_SDE,
            "euler_cfg_pp" to SdCppEngine.Sampler.EULER_CFG_PP,
            "euler_a_cfg_pp" to SdCppEngine.Sampler.EULER_A_CFG_PP,
            "euler_ge" to SdCppEngine.Sampler.EULER_GE,
            "dpm++2m_sde" to SdCppEngine.Sampler.DPMPP2M_SDE,
            "dpm++2m_sde_bt" to SdCppEngine.Sampler.DPMPP2M_SDE_BT,
            "lms" to SdCppEngine.Sampler.LMS,
        )
        aliases[key]?.let { return it }
        return SdCppEngine.Sampler.entries.firstOrNull { it.name.equals(key, true) }
            ?: SdCppEngine.Sampler.EULER
    }

    private fun schedulerOf(name: String?): SdCppEngine.Scheduler {
        val s = name?.trim().orEmpty()
        if (s.isEmpty()) return SdCppEngine.Scheduler.DISCRETE
        s.toIntOrNull()?.let { code ->
            SdCppEngine.Scheduler.entries.firstOrNull { it.code == code }?.let { return it }
        }
        val key = s.lowercase().replace("-", "_").replace(" ", "_")
        val aliases = mapOf(
            "discrete" to SdCppEngine.Scheduler.DISCRETE,
            "karras" to SdCppEngine.Scheduler.KARRAS,
            "exponential" to SdCppEngine.Scheduler.EXPONENTIAL,
            "exp" to SdCppEngine.Scheduler.EXPONENTIAL,
            "ays" to SdCppEngine.Scheduler.AYS,
            "sgm_uniform" to SdCppEngine.Scheduler.SGM_UNIFORM,
            "simple" to SdCppEngine.Scheduler.SIMPLE,
            "smoothstep" to SdCppEngine.Scheduler.SMOOTHSTEP,
            "kl_optimal" to SdCppEngine.Scheduler.KL_OPTIMAL,
            "lcm" to SdCppEngine.Scheduler.LCM,
            "beta" to SdCppEngine.Scheduler.BETA,
        )
        aliases[key]?.let { return it }
        return SdCppEngine.Scheduler.entries.firstOrNull { it.name.equals(key, true) }
            ?: SdCppEngine.Scheduler.DISCRETE
    }

    // ==================== 小工具 ====================

    private fun sectionTitle(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(pal.primary)
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun helpText(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(pal.hint)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun actionButton(text: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(act).apply {
            this.text = text
            textSize = 13.5f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextColor(if (primary) pal.onPrimary else pal.primary)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                if (primary) setColor(pal.primary) else {
                    setColor(pal.softBtn)
                    setStroke(dp(1), pal.border)
                }
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 页面关掉时调：停播放器、解绑插件、释放视频 context。 */
    fun release() {
        framesView?.release()
        session?.release()
        session = null
        if (VideoRunner.isRunning()) VideoRunner.cancel()
        VideoRunner.release()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    companion object {
        // UiType 编号（对齐 com.litertchat.plugin.UiType）
        private const val U_INFO = 0
        private const val U_SECTION = 1
        private const val U_SWITCH = 2
        private const val U_SLIDER = 3
        private const val U_CHOICE = 4
        private const val U_TEXT = 5
        private const val U_ACTION = 6
        private const val U_PROGRESS = 7
        private const val U_STATUS = 8
        private const val U_TEXTAREA = 9
        private const val U_GROUP_START = 10
        private const val U_GROUP_END = 11
        private const val U_LOG = 12
        private const val U_IMAGE = 13
        private const val U_AUDIO = 14
        private const val U_TABS_START = 15
        private const val U_TAB = 16
        private const val U_TABS_END = 17
        private const val U_MODEL_SLOT = 18
        private const val U_HOST_SLOT = 19
        private const val U_ROW_START = 20
        private const val U_ROW_END = 21
        private const val U_GRID_START = 22
        private const val U_GRID_END = 23
        private const val U_DIVIDER = 24
        private const val U_SPACER = 25
        private const val U_FRAMES = 26
        private const val U_MODEL_SET = 27
    }
}
