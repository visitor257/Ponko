package com.litertchat.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.channelFlow
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.parameters.ModelParameters
import net.ladenthin.llama.value.Pair
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device chat app that loads a Google LiteRT-LM (.litertlm) model from local
 * storage and runs it via com.google.ai.edge.litertlm.
 *
 * UI: flat modern look — blue app bar, card-style settings panel, chat bubbles,
 * bottom input bar, collapsible "thinking" section, and Markdown-rendered replies.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK_MODEL = 1001
    }

    // ---- palette ----
    private val C_PRIMARY = Color.rgb(47, 107, 255)
    private val C_PRIMARY_SOFT = Color.rgb(233, 239, 255)
    private val C_BG = Color.rgb(242, 244, 248)
    private val C_CARD = Color.WHITE
    private val C_TEXT = Color.rgb(28, 32, 42)
    private val C_SUBTEXT = Color.rgb(122, 131, 148)
    private val C_THOUGHT_BG = Color.rgb(245, 247, 251)
    private val C_THOUGHT_TEXT = Color.rgb(110, 118, 134)
    private val C_OK = Color.rgb(34, 178, 110)
    private val C_WARN = Color.rgb(245, 158, 11)
    private val C_ERR = Color.rgb(229, 82, 82)
    private val C_IDLE = Color.rgb(154, 164, 181)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelPath: String? = null
    private var busy = false
    private var genJob: Job? = null

    /** 一次问答（用于会话重建/持久化）。 */
    private class QaTurn(val user: String, var answer: String = "", var thought: String = "")

    /** 一个独立对话，拥有自己的完整历史。 */
    private class ChatSession(var id: Long, var title: String) {
        val turns = mutableListOf<QaTurn>()
    }

    private val sessions = mutableListOf<ChatSession>()
    private lateinit var current: ChatSession

    /** 当前会话创建时的思考开关状态；与复选框不一致时需重建会话。 */
    private var convThinking: Boolean? = null

    /** GGUF（llama.cpp）后端实例；非空表示当前加载的是 .gguf 模型。 */
    private var llamaModel: LlamaModel? = null

    private lateinit var markwonFull: Markwon
    private lateinit var markwonStream: Markwon

    // ---- views ----
    private lateinit var statusTv: TextView
    private lateinit var statusDot: View
    private lateinit var tabChat: View
    private lateinit var tabModels: View
    private lateinit var tabSettings: View
    private lateinit var inputBar: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerMask: View
    private lateinit var drawerBody: LinearLayout
    private val navIcons = mutableListOf<TextView>()
    private val navLabels = mutableListOf<TextView>()
    private lateinit var savedContainer: LinearLayout
    private lateinit var modelInfoText: TextView
    private lateinit var backendSpinner: Spinner
    private lateinit var loadButton: TextView
    private lateinit var thinkCheck: CheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputEdit: EditText
    private lateinit var sendButton: TextView
    private lateinit var jumpButton: TextView

    // ---- state ----
    private var currentTab = 0
    private var drawerOpen = false
    private val drawerWidthDp = 280
    private var scrollPending = false
    /** Auto-scroll only while locked to the bottom; scrolling up unlocks it. */
    private var autoFollow = true
    private var lastScrollY = 0
    private var touchStartY = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markwonFull = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        // 流式渲染器故意不装表格插件：表格是两遍异步布局（先测列宽再重排），
        // 边生成边重渲染会让气泡高度反复跳变（上下抽搯）。表格放到生成结束后一次渲染。
        markwonStream = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        loadSessions()
        buildUi()
    }

    override fun onPause() {
        super.onPause()
        saveSessions()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { saveSessions() } catch (_: Throwable) {}
        scope.cancel()
        try { engine?.close() } catch (_: Throwable) {}
        try { llamaModel?.close() } catch (_: Throwable) {}
        engine = null
        llamaModel = null
        conversation = null
    }

    // ================= UI construction =================

    private fun buildUi() {
        val rootFrame = FrameLayout(this).apply { setBackgroundColor(C_BG) }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }

        main.addView(buildAppBar(), matchWrap())

        // 三页内容：对话 / 模型 / 设置（底部菜单切换）
        tabChat = buildChatPage()
        tabModels = buildModelsPage()
        tabSettings = buildSettingsPage()
        val contentFrame = FrameLayout(this)
        contentFrame.addView(tabChat, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabModels, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabSettings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        main.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        inputBar = buildInputBar()
        main.addView(inputBar, matchWrap())
        main.addView(buildBottomNav(), matchWrap())

        rootFrame.addView(main, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildDrawer(rootFrame)
        setContentView(rootFrame)

        refreshSavedModels()
        restoreSession()
        switchTab(0)
    }

    /** 对话页：消息列表 + 回到底部按钮 + 智能跟随滚动。 */
    private fun buildChatPage(): View {
        val chatWrap = FrameLayout(this)
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        chatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(chatContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // 用户上滑阅读时暂停自动跟随；此按钮一键回到底部
        jumpButton = TextView(this).apply {
            text = "↓"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY, 24)
            elevation = dp(4).toFloat()
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                autoFollow = true
                jumpButton.visibility = View.GONE
                scrollView.post {
                    val child = scrollView.getChildAt(0)
                    if (child != null) scrollView.smoothScrollTo(0, child.bottom)
                }
            }
        }
        chatWrap.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chatWrap.addView(jumpButton, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(16), dp(16))
        })

        // 滚动规则（不依赖触摸事件，避免被气泡里的可选中 TextView 吃掉）：
        //   往上翻（看更早内容）→ 立刻解除底部锁定，不再自动跟随；
        //   往下翻并滚到最底 → 重新锁定，继续自动跟随。
        scrollView.setOnScrollChangeListener { _, _, y, _, _ ->
            val dy = y - lastScrollY
            lastScrollY = y
            if (dy < 0) {
                if (autoFollow) {
                    autoFollow = false
                    refreshJumpButton()
                }
            } else if (dy > 0) {
                refreshFollowState()
            }
        }
        // 兜底：已经滚到顶部时 scrollY 不再变化，用触摸方向补判
        scrollView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> touchStartY = e.y
                MotionEvent.ACTION_MOVE -> {
                    if (touchStartY < 0) {
                        touchStartY = e.y
                    } else if (e.y - touchStartY > dp(2) && autoFollow) {
                        autoFollow = false
                        refreshJumpButton()
                    }
                }
            }
            false
        }
        return chatWrap
    }

    private fun buildAppBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_PRIMARY)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(3).toFloat()
        }

        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r1.addView(iconButton("☰") { openDrawer() }, wrapWrap())

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        titleBox.addView(TextView(this).apply {
            text = "Ponko"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        statusDot = View(this).apply { background = rounded(Color.WHITE, 99) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) })
        statusTv = TextView(this).apply {
            text = "未加载模型"
            textSize = 11.5f
            setTextColor(Color.parseColor("#DCE5FF"))
        }
        statusRow.addView(statusTv, wrapWrap())
        titleBox.addView(statusRow, matchWrap())

        r1.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(iconButton("＋") { newSession() }, wrapWrap())
        bar.addView(r1, matchWrap())
        return bar
    }

    /** 模型页：选择/加载模型 + 后端选择。 */
    private fun buildModelsPage(): View {
        val sv = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val card = card()

        card.addView(pageTitle("模型"))
        card.addView(hintText("从本地选 .litertlm（LiteRT-LM）或 .gguf（llama.cpp）模型；首次会复制到 App 私有目录，之后可直接选用。GGUF 走 CPU 多线程，并在会话内复用 KV 前缀（长对话只需计算新增内容）。"))

        card.addView(actionButton("选择模型文件（.litertlm / .gguf）") { pickModelFile() },
            matchWrap().apply { topMargin = dp(12) })

        savedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        card.addView(savedContainer, matchWrap().apply { topMargin = dp(6) })

        modelInfoText = TextView(this).apply {
            text = "未选择模型文件"
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        card.addView(modelInfoText, matchWrap().apply { topMargin = dp(8) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = "运行方式"
            textSize = 13f
            setTextColor(C_TEXT)
        }, wrapWrap())
        backendSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("CPU", "GPU")
            )
            setSelection(0)
        }
        row.addView(backendSpinner, wrapWrap().apply { leftMargin = dp(6) })
        loadButton = actionButton("加载模型") { toggleLoad() }
        row.addView(loadButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(10) })
        card.addView(row, matchWrap().apply { topMargin = dp(12) })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            indeterminateTintList = ColorStateList.valueOf(C_PRIMARY)
            progressTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        card.addView(progressBar, matchWrap().apply { topMargin = dp(8) })

        sv.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return sv
    }

    /** 设置页：生成参数 + 对话管理 + 关于。 */
    private fun buildSettingsPage(): View {
        val sv = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val card = card()

        card.addView(pageTitle("生成"))
        thinkCheck = CheckBox(this).apply {
            text = "思考模式（先推理再回答，需模型支持）"
            textSize = 13f
            setTextColor(C_TEXT)
            isChecked = true
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        card.addView(thinkCheck, matchWrap().apply { topMargin = dp(6) })
        card.addView(hintText("开启后模型先输出推理过程再回答（更慢）。切换开关会在下一条消息生效（LiteRT 会话自动按新模式重建，历史保留）。GGUF 模型通过推理预算/模板参数控制，不支持的模型可能仍会思考。"))

        card.addView(pageTitle("对话"), matchWrap().apply { topMargin = dp(16) })
        card.addView(hintText("新建 / 切换对话：点左上角 ☰ 打开抽屉。"))
        card.addView(actionButton("删除当前对话") { confirmDeleteSession(current) },
            matchWrap().apply { topMargin = dp(10) })

        card.addView(pageTitle("关于"), matchWrap().apply { topMargin = dp(16) })
        val portrait = ImageView(this).apply {
            setImageResource(R.drawable.about_portrait)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(portrait, matchWrap())
        card.addView(hintText("Ponko · 名字来自日语「ポンコツ」（破铜烂铁）——脑子不中用，但可以随时换成最好的模型。\n模型与推理全部在本机离线运行，对话记录只保存在设备本地。"))

        sv.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_CARD, 16)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        elevation = dp(1).toFloat()
    }

    private fun pageTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(C_TEXT)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun hintText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(C_SUBTEXT)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(5), 0, 0)
    }

    // ---- 底部导航 ----

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(6).toFloat()
        }
        val items = listOf(
            Triple("💬", "对话", 0),
            Triple("🧠", "模型", 1),
            Triple("⚙", "设置", 2),
        )
        for ((icon, label, idx) in items) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(7), 0, dp(7))
                setOnClickListener { switchTab(idx) }
            }
            val iconTv = TextView(this).apply { text = icon; textSize = 17f; gravity = Gravity.CENTER }
            val labelTv = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(1), 0, 0)
            }
            item.addView(iconTv, matchWrap())
            item.addView(labelTv, matchWrap())
            navIcons += iconTv
            navLabels += labelTv
            nav.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return nav
    }

    private fun switchTab(index: Int) {
        currentTab = index
        tabChat.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabModels.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 2) View.VISIBLE else View.GONE
        inputBar.visibility = if (index == 0) View.VISIBLE else View.GONE
        for (i in navIcons.indices) {
            val c = if (i == currentTab) C_PRIMARY else C_SUBTEXT
            navIcons[i].setTextColor(c)
            navLabels[i].setTextColor(c)
        }
        if (index == 0) scrollToBottom()
    }

    // ---- 左侧对话抽屉 ----

    private fun buildDrawer(rootFrame: FrameLayout) {
        drawerMask = View(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            visibility = View.GONE
            alpha = 0f
            setOnClickListener { closeDrawer() }
        }
        rootFrame.addView(drawerMask, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        drawerPanel.addView(TextView(this).apply {
            text = "对话"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(C_PRIMARY)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }, matchWrap())

        val sv = ScrollView(this)
        drawerBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        sv.addView(drawerBody, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        drawerPanel.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        drawerPanel.addView(TextView(this).apply {
            text = "完全离线 · 记录只存在本机"
            textSize = 11f
            setTextColor(C_SUBTEXT)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(14))
        }, matchWrap())

        val w = dp(drawerWidthDp)
        rootFrame.addView(drawerPanel, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })
        drawerPanel.translationX = -w.toFloat()
    }

    private fun refreshDrawer() {
        drawerBody.removeAllViews()
        drawerBody.addView(actionButton("＋ 新建对话") { closeDrawer(); newSession() },
            matchWrap().apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })

        for (s in sessions.sortedByDescending { it.id }) {
            val isCur = s === current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
                isClickable = true
                if (isCur) setBackgroundColor(C_PRIMARY_SOFT)
            }
            row.addView(TextView(this).apply {
                text = s.title
                textSize = 14f
                setTextColor(if (isCur) C_PRIMARY else C_TEXT)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "${s.turns.size} 轮"
                textSize = 11f
                setTextColor(C_SUBTEXT)
                setPadding(0, 0, dp(6), 0)
            }, wrapWrap())
            row.addView(TextView(this).apply {
                text = "🗑"
                textSize = 14f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isClickable = true
                setOnClickListener { confirmDeleteSession(s) }
            }, wrapWrap())
            row.setOnClickListener { closeDrawer(); switchTo(s) }
            drawerBody.addView(row, matchWrap())
            drawerBody.addView(View(this).apply { setBackgroundColor(Color.rgb(238, 240, 245)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(dp(16), 0, 0, 0)
                })
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        refreshDrawer()
        drawerMask.visibility = View.VISIBLE
        drawerMask.alpha = 0f
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.translationX = -dp(drawerWidthDp).toFloat()
        drawerPanel.animate().translationX(0f).setDuration(220).start()
        drawerMask.animate().alpha(1f).setDuration(220).start()
        drawerOpen = true
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerPanel.animate().translationX(-dp(drawerWidthDp).toFloat()).setDuration(180)
            .withEndAction { drawerPanel.visibility = View.GONE }.start()
        drawerMask.animate().alpha(0f).setDuration(180)
            .withEndAction { drawerMask.visibility = View.GONE }.start()
        drawerOpen = false
    }

    private fun buildInputBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            elevation = dp(6).toFloat()
        }

        inputEdit = EditText(this).apply {
            hint = "输入消息…"
            textSize = 15f
            setTextColor(C_TEXT)
            setHintTextColor(C_SUBTEXT)
            background = rounded(Color.rgb(243, 245, 249), 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
        bar.addView(inputEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        sendButton = TextView(this).apply {
            text = "↑"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY, 22)
            isClickable = true
            setOnClickListener { onSendOrStop() }
        }
        bar.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        return bar
    }

    // ================= small view factories =================

    private fun actionButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(C_PRIMARY, 12)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun smallButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(C_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(C_PRIMARY_SOFT, 10)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun iconButton(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(Color.parseColor("#33FFFFFF"), 12)
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ================= status =================

    private fun setStatus(text: String, color: Int) {
        statusTv.text = text
        statusDot.background = rounded(color, 99)
    }

    // ================= model loading =================

    private fun pickModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_MODEL)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_MODEL && resultCode == RESULT_OK) {
            data?.data?.let { copyModelToPrivate(it) }
        }
    }

    /** Models can't be loaded from a content:// URI, so copy them to a real file first. */
    private fun copyModelToPrivate(uri: Uri) {
        setBusy(true)
        setStatus("正在复制模型文件…", C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                val name = queryDisplayName(uri) ?: ("model_${System.currentTimeMillis()}.litertlm")
                val dest = File(dir, name)
                if (!dest.exists()) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法打开所选文件")
                    val total = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                    input.use { ins ->
                        dest.outputStream().use { outs ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                outs.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = (copied * 100 / total).toInt()
                                    withContext(Dispatchers.Main) { progressBar.progress = pct }
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    modelPath = dest.absolutePath
                    modelInfoText.text = "模型：${dest.name}（${fmtSize(dest.length())}）"
                    setStatus("已就绪，可加载模型", C_WARN)
                    refreshSavedModels()
                    toast("模型文件已就绪：${dest.name}")
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus("复制失败", C_ERR)
                    toast("复制失败：${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE; setBusy(false) }
            }
        }
    }

    // ================= saved models =================

    /** Scan filesDir/models and render a tappable list of already-copied models. */
    private fun refreshSavedModels() {
        savedContainer.removeAllViews()
        val dir = File(filesDir, "models")
        val files = dir.listFiles { f ->
            f.isFile && (
                f.name.endsWith(".litertlm", ignoreCase = true) ||
                    f.name.endsWith(".gguf", ignoreCase = true)
                )
        }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            savedContainer.visibility = View.GONE
            return
        }
        savedContainer.visibility = View.VISIBLE
        savedContainer.addView(TextView(this).apply {
            text = "已复制的模型（点击选用 · 长按删除）"
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }, matchWrap())

        for (f in files) {
            val sel = f.absolutePath == modelPath
            val chip = TextView(this).apply {
                val tag = if (f.name.endsWith(".gguf", ignoreCase = true)) "GGUF" else "LiteRT"
                text = "▶ ${f.name}　[$tag] ${fmtSize(f.length())}"
                textSize = 12.5f
                setTextColor(if (sel) C_PRIMARY else C_TEXT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (sel) C_PRIMARY_SOFT else Color.rgb(247, 248, 251), 10,
                    strokeDp = if (sel) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener { selectSavedModel(f) }
            chip.setOnLongClickListener { confirmDeleteModel(f); true }
            savedContainer.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
    }

    private fun selectSavedModel(f: File) {
        if (busy) {
            toast("当前有任务进行中，请稍候")
            return
        }
        modelPath = f.absolutePath
        modelInfoText.text = "模型：${f.name}（${fmtSize(f.length())}）"
        setStatus("已选用模型，点「加载模型」开始", C_WARN)
        refreshSavedModels()
    }

    private fun confirmDeleteModel(f: File) {
        if (f.absolutePath == modelPath && (engine != null || llamaModel != null)) {
            toast("该模型正在使用，请先卸载再删除")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除已复制的模型？")
            .setMessage("${f.name}\n大小：${fmtSize(f.length())}\n删除后需重新选择原文件才会恢复。")
            .setPositiveButton("删除") { _, _ ->
                f.delete()
                if (modelPath == f.absolutePath) {
                    modelPath = null
                    modelInfoText.text = "未选择模型文件"
                }
                refreshSavedModels()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleLoad() {
        if (engine != null || llamaModel != null) {
            unloadModel()
            return
        }
        val path = modelPath ?: run { toast("请先选择模型文件"); return }
        val isGguf = path.endsWith(".gguf", ignoreCase = true)
        val backendName = backendSpinner.selectedItem.toString()
        val thinking = thinkCheck.isChecked
        setBusy(true)
        setStatus("正在加载模型…", C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        scope.launch(Dispatchers.IO) {
            try {
                if (isGguf) {
                    // llama.cpp：单 slot + cache_prompt。会话内 KV 前缀会被复用，长对话只需计算新增 token。
                    val cpus = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
                    val params = ModelParameters()
                        .setModel(path)
                        .setCtxSize(4096)
                        .setThreads(cpus)
                        .setThreadsBatch(cpus)
                        .setBatchSize(512)
                        .setParallel(1)
                        .setKeep(64)
                        .setGpuLayers(0)
                    val m = LlamaModel(params)
                    withContext(Dispatchers.Main) {
                        llamaModel = m
                        engine = null
                        conversation = null
                        convThinking = null
                        setStatus("模型已加载（llama.cpp · CPU · KV 复用）", C_OK)
                        if (backendName == "GPU") toast("GGUF 目前走 CPU（该运行库未含 GPU 后端）")
                    }
                } else {
                    val backend: Backend = if (backendName == "GPU") Backend.GPU() else Backend.CPU()
                    val cfg = EngineConfig(
                        modelPath = path,
                        backend = backend,
                        cacheDir = cacheDir.absolutePath,
                    )
                    val eng = Engine(cfg)
                    eng.initialize()
                    val conv = eng.createConversation(configFor(current, thinking))
                    withContext(Dispatchers.Main) {
                        engine = eng
                        llamaModel = null
                        conversation = conv
                        convThinking = thinking
                        setStatus("模型已加载（LiteRT · $backendName）", C_OK)
                    }
                }
                withContext(Dispatchers.Main) {
                    loadButton.text = "卸载模型"
                    loadButton.background = rounded(Color.rgb(246, 247, 250), 12,
                        strokeDp = 1, strokeColor = Color.rgb(219, 224, 234))
                    loadButton.setTextColor(C_TEXT)
                    switchTab(0)
                    addSystemHint("模型加载完成，开始对话吧。")
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus("加载失败", C_ERR)
                    toast("加载失败：${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                    setBusy(false)
                }
            }
        }
    }

    private fun unloadModel() {
        try { engine?.close() } catch (_: Throwable) {}
        try { llamaModel?.close() } catch (_: Throwable) {}
        engine = null
        llamaModel = null
        conversation = null
        convThinking = null
        loadButton.text = "加载模型"
        loadButton.background = rounded(C_PRIMARY, 12)
        loadButton.setTextColor(Color.WHITE)
        setStatus("已卸载", C_IDLE)
        addSystemHint("模型已卸载。")
    }

    // ================= chat =================

    /** Send button doubles as a stop button: while generating it shows a square ■. */
    private fun onSendOrStop() {
        val job = genJob
        if (job != null && job.isActive) {
            job.cancel()
            toast("已中断生成")
        } else {
            doSend()
        }
    }

    private fun setStoppingUi(stop: Boolean) {
        if (stop) {
            sendButton.text = "■"
            sendButton.textSize = 16f
            sendButton.background = rounded(C_ERR, 22)
        } else {
            sendButton.text = "↑"
            sendButton.textSize = 19f
            sendButton.background = rounded(C_PRIMARY, 22)
        }
        sendButton.isEnabled = true
        sendButton.alpha = 1f
    }

    // ---- 多对话管理 ----

    /** 新建一个对话（旧对话保留在列表中）。 */
    private fun newSession() {
        if (busy) { toast("生成中，请稍候"); return }
        saveSessions()
        val s = ChatSession(System.currentTimeMillis(), "新对话")
        sessions += s
        current = s
        restoreSession()
        saveSessions()
        toast("已新建对话（旧对话已保留）")
    }

    private fun switchTo(s: ChatSession) {
        if (s === current) return
        if (busy) { toast("生成中，请稍候"); return }
        saveSessions()
        current = s
        restoreSession()
        saveSessions()
    }

    private fun confirmDeleteSession(s: ChatSession) {
        if (sessions.size <= 1) {
            AlertDialog.Builder(this)
                .setTitle("清空当前对话？")
                .setMessage("这是最后一个对话，删除后会清空它的内容。")
                .setPositiveButton("清空") { _, _ ->
                    s.turns.clear()
                    s.title = "新对话"
                    restoreSession()
                    saveSessions()
                    if (drawerOpen) refreshDrawer()
                    toast("当前对话已清空")
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除对话？")
            .setMessage(s.title)
            .setPositiveButton("删除") { _, _ ->
                val wasCurrent = s === current
                sessions.remove(s)
                if (wasCurrent) { current = sessions.last(); restoreSession() }
                saveSessions()
                if (drawerOpen) refreshDrawer()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 会话切换/重建 ----

    /**
     * GGUF 推理参数：带上对话历史 + cache_prompt(true) + 固定 slot。
     * llama.cpp 会把本轮 prompt 与 slot 里已缓存的 KV 做前缀匹配，只计算新增部分 ——
     * 这是长对话不重复 prefill 的关键。
     */
    private fun buildInferenceParams(s: ChatSession, maxTurns: Int = 24): InferenceParameters {
        val msgs = mutableListOf<Pair<String, String>>()
        for (t in s.turns.takeLast(maxTurns)) {
            msgs += Pair("user", t.user)
            if (t.answer.isNotEmpty()) msgs += Pair("assistant", t.answer)
        }
        var p = InferenceParameters.empty()
            .withMessages(null, msgs)
            .withCachePrompt(true)
            .withSlotId(0)
            .withNPredict(2048)
            .withTemperature(0.7f)
            .withTopK(40)
            .withTopP(0.9f)
            .withRepeatPenalty(1.1f)
        // 思考开关：开 = 不限推理预算；关 = 压到 0，并给模板传 enable_thinking=false（哪个机制生效都行）
        p = if (thinkCheck.isChecked) {
            p.withReasoningBudgetTokens(-1)
        } else {
            p.withReasoningBudgetTokens(0)
                .withChatTemplateKwargs(mapOf("enable_thinking" to "false"))
        }
        return p
    }

    /** 用某个对话的文本历史构建 LiteRT 会话配置。 */
    private fun configFor(s: ChatSession, thinking: Boolean): ConversationConfig {
        val msgs = mutableListOf<Message>()
        for (t in s.turns) {
            msgs += Message.user(t.user)
            if (t.answer.isNotEmpty()) msgs += Message.model(t.answer)
        }
        return ConversationConfig(
            initialMessages = msgs,
            thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = 2048),
        )
    }

    /**
     * 思考开关变化后重建会话。
     * LiteRT-LM 的思考标记是在会话创建时写入提示模板的，
     * 会话跑起来后仅改 per-call 的 ThinkingConfig 不会生效（尤其是 关→开），
     * 所以必须按新模式重建会话；历史通过 initialMessages 保留。
     */
    private fun rebuildConversation() {
        val eng = engine ?: return
        val thinking = thinkCheck.isChecked
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = try {
            eng.createConversation(configFor(current, thinking))
        } catch (e: Throwable) {
            toast("按历史重建会话失败，已清空上下文：${e.message}")
            eng.createConversation(
                ConversationConfig(
                    thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = 2048)
                )
            )
        }
        convThinking = thinking
        addSystemHint(
            if (thinking) "🤔 已切换为思考模式（会话已按新模式重建，历史保留）"
            else "已切换为普通模式（会话已按新模式重建，历史保留）"
        )
    }

    /** 重建 LiteRT 会话（若模型已加载）并把聊天区重绘为当前对话的内容。 */
    private fun restoreSession() {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        convThinking = null
        val eng = engine
        if (eng != null && llamaModel == null) {
            val thinking = thinkCheck.isChecked
            conversation = eng.createConversation(configFor(current, thinking))
            convThinking = thinking
        }
        chatContainer.removeAllViews()
        autoFollow = true
        jumpButton.visibility = View.GONE
        if (current.turns.isEmpty()) {
            addSystemHint("① 点「选择模型」选 .litertlm / .gguf 文件（已复制过的可直接点列表选用）\n" +
                "② 点「加载模型」开始（首次初始化需几秒~几十秒）")
        } else {
            for (t in current.turns) renderTurn(t)
        }
        scrollToBottom(force = true)
    }

    /** 把一轮已有问答重绘到聊天区（历史回填）。 */
    private fun renderTurn(t: QaTurn) {
        addUserBubble(t.user)
        val ai = addAiArea()
        if (t.thought.isNotEmpty()) {
            ai.thoughtBox.visibility = View.VISIBLE
            ai.thoughtBody.text = t.thought
            ai.thoughtHeader.text = "🤔 思考过程（点击展开）"
        }
        if (t.answer.isNotEmpty()) {
            markwonFull.setMarkdown(ai.answer, t.answer)
        } else {
            ai.answer.text = "(无内容)"
            ai.answer.setTextColor(C_SUBTEXT)
        }
    }

    // ---- 持久化 ----

    private val sessionsFile: File get() = File(filesDir, "sessions.json")

    private fun saveSessions() {
        try {
            val root = JSONObject()
            root.put("current", sessions.indexOf(current))
            val arr = JSONArray()
            for (s in sessions) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("title", s.title)
                val ts = JSONArray()
                for (t in s.turns) {
                    ts.put(JSONObject().put("u", t.user).put("a", t.answer).put("th", t.thought))
                }
                o.put("turns", ts)
                arr.put(o)
            }
            root.put("sessions", arr)
            sessionsFile.writeText(root.toString())
        } catch (_: Throwable) {}
    }

    private fun loadSessions() {
        sessions.clear()
        var idx = 0
        try {
            if (sessionsFile.exists()) {
                val root = JSONObject(sessionsFile.readText())
                idx = root.optInt("current", 0)
                val arr = root.optJSONArray("sessions")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val s = ChatSession(
                            o.optLong("id", System.currentTimeMillis() + i),
                            o.optString("title", "新对话"),
                        )
                        val ts = o.optJSONArray("turns")
                        if (ts != null) {
                            for (j in 0 until ts.length()) {
                                val t = ts.getJSONObject(j)
                                s.turns += QaTurn(t.optString("u"), t.optString("a"), t.optString("th"))
                            }
                        }
                        sessions += s
                    }
                }
            }
        } catch (_: Throwable) {}
        if (sessions.isEmpty()) sessions += ChatSession(System.currentTimeMillis(), "新对话")
        current = sessions.getOrElse(idx) { sessions.first() }
    }

    private fun doSend() {
        val text = inputEdit.text.toString().trim()
        if (text.isEmpty()) return
        val isGgufRun = llamaModel != null
        if (conversation == null && !isGgufRun) { toast("请先加载模型"); return }
        if (busy) {
            toast("正在生成中，请稍候")
            return
        }
        var conv: Conversation? = null
        if (!isGgufRun) {
            // 思考开关变了 → 发送前重建会话，否则新设置不生效
            if (convThinking != thinkCheck.isChecked) rebuildConversation()
            conv = conversation ?: run { toast("会话不可用，请重新加载模型"); return }
        }

        val turn = QaTurn(text)
        current.turns += turn
        if (current.title == "新对话") current.title = text.take(18)

        inputEdit.setText("")
        addUserBubble(text)
        jumpToBottom()
        setBusy(true)
        setStoppingUi(true)
        setStatus("生成中（点 ■ 可中断）…", C_WARN)

        val ai = addAiArea()
        val answerBuf = StringBuilder()
        val thoughtBuf = StringBuilder()
        var lastRender = 0L
        var cancelled = false

        fun renderAnswer(force: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastRender < 120) return
            lastRender = now
            val md = answerBuf.toString()
            if (md.isNotEmpty()) markwonStream.setMarkdown(ai.answer, md)
        }

        fun renderThought(force: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastRender < 90) return
            ai.thoughtBody.text = thoughtBuf.toString()
            ai.thoughtHeader.text = if (ai.thoughtBody.visibility == View.VISIBLE)
                "🤔 思考过程（点击收起）" else "🤔 思考过程（点击展开）"
        }

        genJob = scope.launch {
            try {
                val lm = llamaModel
                if (isGgufRun && lm != null) {
                    // generateChat 会套用模型自带的对话模板；cache_prompt=true + 固定 slot
                    // 让 llama.cpp 复用上一轮已算好的 KV 前缀，长对话不再重复 prefill。
                    val params = buildInferenceParams(current)

                    // GGUF 模型的思考内容混在正文流里（<|channel>thought…<channel|> 或 … ），
                    // 用状态机把两路分开：思考进折叠区，正文走 Markdown 渲染。
                    fun onThoughtDelta(d: String) {
                        if (d.isEmpty()) return
                        thoughtBuf.append(d)
                        turn.thought = thoughtBuf.toString()
                        if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                        renderThought(false)
                    }

                    fun onAnswerDelta(d: String) {
                        if (d.isEmpty()) return
                        answerBuf.append(d)
                        turn.answer = answerBuf.toString()
                        renderAnswer(false)
                    }

                    // 开了思考模式就默认从思考区开始：Gemma 这类模型的 `<|channel>thought`
                    // 前缀是写在 prompt 模板里的，不会出现在输出流中，只能靠结束标记分界。
                    val splitter = ReasoningSplitter(
                        ::onThoughtDelta,
                        ::onAnswerDelta,
                        assumeThinking = thinkCheck.isChecked,
                    )

                    val flow = channelFlow {
                        withContext(Dispatchers.IO) {
                            val it = lm.generateChat(params)
                            try {
                                for (out in it) {
                                    if (out.text.isNotEmpty()) send(out.text)
                                }
                            } finally {
                                runCatching { it.close() }
                            }
                        }
                    }
                    flow.collect { piece ->
                        splitter.feed(piece)
                        scrollToBottom()
                    }
                    splitter.finish()
                    renderThought(true)
                    if (answerBuf.isEmpty() && thoughtBuf.isNotEmpty()) {
                        ai.answer.text = "(模型只返回了思考过程，没有正文回答)"
                        ai.answer.setTextColor(C_SUBTEXT)
                    } else if (answerBuf.isEmpty()) {
                        ai.answer.text = "(模型没有返回内容)"
                        ai.answer.setTextColor(C_SUBTEXT)
                    } else {
                        markwonFull.setMarkdown(ai.answer, answerBuf.toString())
                    }
                    saveSessions()
                    setStatus("就绪", C_OK)
                    return@launch
                }
                // Flow emits INCREMENTAL chunks (not snapshots): accumulate.
                // Reasoning text arrives on channels["thought"], answer text in contents.
                conv!!.sendMessageAsync(
                    text,
                    thinkingConfig = ThinkingConfig(
                        enableThinking = thinkCheck.isChecked,
                        thinkingTokenBudget = 2048,
                    ),
                ).collect { msg ->
                    val delta = extractText(msg)
                    val thoughtDelta = msg.channels["thought"]?.takeIf { it.isNotEmpty() }
                    if (thoughtDelta != null) {
                        thoughtBuf.append(thoughtDelta)
                        turn.thought = thoughtBuf.toString()
                        if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                        renderThought(false)
                    }
                    if (delta.isNotEmpty()) {
                        answerBuf.append(delta)
                        turn.answer = answerBuf.toString()
                        renderAnswer(false)
                    }
                    scrollToBottom()
                }

                if (answerBuf.isEmpty() && thoughtBuf.isNotEmpty()) {
                    ai.answer.text = "(模型只返回了思考过程，没有正文回答)"
                    ai.answer.setTextColor(C_SUBTEXT)
                } else if (answerBuf.isEmpty()) {
                    ai.answer.text = "(模型没有返回内容)"
                    ai.answer.setTextColor(C_SUBTEXT)
                } else {
                    // 生成结束：用完整渲染器一次性渲染（含表格）
                    markwonFull.setMarkdown(ai.answer, answerBuf.toString())
                }
                saveSessions()
                renderThought(true)
                setStatus("就绪", C_OK)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                ai.answer.text = "(出错) ${e.message}"
                ai.answer.setTextColor(C_ERR)
                setStatus("生成出错", C_ERR)
                toast("生成出错：${e.message}")
            } finally {
                saveSessions()
                setBusy(false)
                setStoppingUi(false)
                if (cancelled) {
                    if (answerBuf.isNotEmpty()) {
                        markwonFull.setMarkdown(ai.answer, answerBuf.toString())
                    } else if (thoughtBuf.isEmpty()) {
                        ai.answer.text = "(已中断)"
                        ai.answer.setTextColor(C_SUBTEXT)
                    }
                    setStatus("已中断", C_IDLE)
                }
                scrollToBottom()
            }
        }
    }

    private fun extractText(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private class AiArea(
        val root: LinearLayout,
        val thoughtBox: LinearLayout,
        val thoughtHeader: TextView,
        val thoughtBody: TextView,
        val answer: TextView,
    )

    /** AI reply card: collapsible thinking section on top, Markdown answer below. */
    private fun addAiArea(): AiArea {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(C_CARD, 16)
            elevation = dp(1).toFloat()
        }

        val thoughtBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(C_THOUGHT_BG, 10)
            visibility = View.GONE
        }
        val thoughtHeader = TextView(this).apply {
            text = "🤔 思考过程（点击展开）"
            textSize = 12.5f
            setTextColor(C_THOUGHT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
        }
        val thoughtBody = TextView(this).apply {
            textSize = 12.5f
            setTextColor(C_THOUGHT_TEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
        }
        thoughtHeader.setOnClickListener {
            val show = thoughtBody.visibility != View.VISIBLE
            thoughtBody.visibility = if (show) View.VISIBLE else View.GONE
            thoughtHeader.text = if (show) "🤔 思考过程（点击收起）" else "🤔 思考过程（点击展开）"
            if (show) scrollToBottom()
        }
        thoughtBox.addView(thoughtHeader, matchWrap())
        thoughtBox.addView(thoughtBody, matchWrap().apply { topMargin = dp(6) })
        wrap.addView(thoughtBox, matchWrap())

        val answer = TextView(this).apply {
            textSize = 16f
            setTextColor(C_TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        wrap.addView(answer, matchWrap().apply { topMargin = dp(4) })

        chatContainer.addView(wrap, matchWrap().apply {
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        scrollToBottom()
        return AiArea(wrap, thoughtBox, thoughtHeader, thoughtBody, answer)
    }

    private fun addUserBubble(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(C_PRIMARY, 16)
            elevation = dp(1).toFloat()
        }
        chatContainer.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            leftMargin = dp(56)
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        scrollToBottom()
    }

    private fun addSystemHint(text: String) {
        chatContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(C_SUBTEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, matchWrap())
        scrollToBottom()
    }

    private fun scrollToBottom(force: Boolean = false) {
        if (!force && !autoFollow) {
            refreshFollowState()
            return
        }
        if (scrollPending) return
        scrollPending = true
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
            scrollPending = false
            refreshFollowState()
        }
    }

    /** 滚到（或超过）底部时才恢复自动跟随；中途不断开，避免打断平滑回底动画。 */
    private fun refreshFollowState() {
        val child = scrollView.getChildAt(0) ?: return
        if (scrollView.scrollY + scrollView.height >= child.bottom - dp(4)) autoFollow = true
        refreshJumpButton()
    }

    private fun refreshJumpButton() {
        val child = scrollView.getChildAt(0) ?: return
        val scrollable = child.height > scrollView.height + dp(16)
        jumpButton.visibility = if (!autoFollow && scrollable) View.VISIBLE else View.GONE
    }

    /** 用户主动行为（发送消息等）：直接恢复跟随并滚到底。 */
    private fun jumpToBottom() {
        autoFollow = true
        jumpButton.visibility = View.GONE
        scrollToBottom(force = true)
    }

    // ================= helpers =================

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) { null }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    private fun setBusy(b: Boolean) {
        busy = b
        loadButton.isEnabled = !b
        loadButton.alpha = if (b) 0.5f else 1f
        inputEdit.isEnabled = !b
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun wrapWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
