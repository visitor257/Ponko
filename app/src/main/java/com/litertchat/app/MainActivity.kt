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
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import com.litertchat.app.draw.DrawPage
import com.litertchat.app.draw.GgufProbe
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.channelFlow
import net.ladenthin.llama.LlamaIterator
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
    private const val REQ_DRAW_TREE = 1002
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

    /** 当前处于绘图模式：对话页的输入会被当作正面提示词去生成图片 */
    /** 让语言模型「调用」绘图模型时使用的命令标记。 */
    private val drawCmdRegex = Regex("<draw>(.*?)</draw>", RegexOption.DOT_MATCHES_ALL)

    /** 语言模型可用的绘图命令说明（仅当绘图模型与语言模型同时就绪时注入）。 */
    private val drawToolPrompt = """
        你具备绘图能力，但只有在用户**明确要求生成/画一张图片**时才使用。
        其他任何情况（闲聊、问答、写代码、翻译等）都正常回答，不要主动画图，也不要展示这个命令。

        需要画图时：先用一两句话回应，然后在回复正文的最后另起一行输出：
        <draw>画面描述</draw>

        画面描述用英文、逗号分隔的关键词（例如 1girl, silver hair, school uniform, cherry blossoms），
        只写画面本身，不要写参数、编号或解释。
        这条命令必须出现于你的最终回答正文中，不要写在思考过程里，否则系统收不到。
    """.trimIndent()

    /** 语言模型与绘图模型同时就绪 → 语言模型可以用 <draw> 命令调绘图模型。 */
    private val canDrawFromChat: Boolean
        get() = drawPage?.isReady() == true && (engine != null || llamaModel != null)

    /**
     * 从回答里取出 <draw>…</draw> 命令：返回提示词（无则 null），并把标记从正文/思考里换掉。
     * 先扫正文，再扫思考过程 —— 思考模式下模型常把「决定画图」写在 thought 里。
     */
    private fun takeDrawCommand(
        answerBuf: StringBuilder,
        thoughtBuf: StringBuilder,
        ai: AiArea,
        turn: QaTurn,
    ): String? {
        if (!canDrawFromChat) return null
        // 1) 正文里的命令
        drawCmdRegex.find(answerBuf)?.let { m ->
            val prompt = m.groupValues[1].trim()
            // 去掉原始标记，换一行说明，避免把 <draw> 写进对话历史
            answerBuf.replace(m.range.first, m.range.last + 1, getString(R.string.s_200))
            turn.answer = answerBuf.toString()
            markwonFull.setMarkdown(ai.answer, answerBuf.toString())
            return prompt.ifEmpty { null }
        }
        // 2) 思考过程里的命令（思考模式）
        drawCmdRegex.find(thoughtBuf)?.let { m ->
            val prompt = m.groupValues[1].trim()
            thoughtBuf.replace(m.range.first, m.range.last + 1, getString(R.string.s_199))
            turn.thought = thoughtBuf.toString()
            ai.thoughtBody.text = thoughtBuf.toString()
            return prompt.ifEmpty { null }
        }
        return null
    }

    /** 对话页当前是否「出图模式」。
     *  规则：语言模型在场时（包括两种模型同时加载），对话页一律走聊天；
     *  只有「绘图模型已就绪、且没有加载语言模型」时，对话页输入才当成绘图提示词。 */
    private val drawMode: Boolean
        get() = drawPage?.isReady() == true && engine == null && llamaModel == null

    /** .gguf 架构探测结果缓存，避免重复读头部 */
    private val ggufKindCache = HashMap<String, GgufProbe.Kind>()
    private var genJob: Job? = null

    /** 一次问答（用于会话重建/持久化）。 */
    private class QaTurn(
        val user: String,
        var answer: String = "",
        var thought: String = "",
        /** 绘图轮次：生成图在本地的文件名（filesDir/chatimg/ 下），非绘图轮为 null */
        var image: String? = null,
    )

    /** 一个独立对话，拥有自己的完整历史。 */
    private class ChatSession(var id: Long, var title: String) {
        val turns = mutableListOf<QaTurn>()
    }

    private val sessions = mutableListOf<ChatSession>()
    private lateinit var current: ChatSession

    /** 当前会话创建时的思考开关状态；与复选框不一致时需重建会话。 */
    private var convThinking: Boolean? = null

    /** 中断后 LiteRT 会话可能已不可用：标记下一条消息发送前静默重建。 */
    private var convNeedsRebuild = false

    /** 创建 LiteRT 会话时是否注入了 <draw> 绘图命令提示。
     *  加载/卸载绘图模型会改变这个能力，而 LiteRT 的 system 提示只在建会话时写入，
     *  所以能力变化后必须重建会话，否则模型根本不知道自己能画图。 */
    private var convDrawCapable = false

    /** GGUF（llama.cpp）后端实例；非空表示当前加载的是 .gguf 模型。 */
    private var llamaModel: LlamaModel? = null
    private var ggufIterator: LlamaIterator? = null

    private lateinit var markwonFull: Markwon
    private lateinit var markwonStream: Markwon

    // ---- views ----
    private lateinit var statusTv: TextView
    private lateinit var statusDot: View
    private lateinit var tabChat: View
    private lateinit var tabModels: View
    private lateinit var tabSettings: View
    private lateinit var tabDraw: View
    private lateinit var inputBar: View

    /** 绘图页（文生图/图生图），懒加载 */
    private var drawPage: DrawPage? = null
    private lateinit var appBarMenu: View
    private lateinit var appBarPlus: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerMask: View
    private lateinit var drawerBody: LinearLayout
    private val navIcons = mutableListOf<TextView>()
    private val navLabels = mutableListOf<TextView>()
    private lateinit var savedContainer: LinearLayout
    private lateinit var modelInfoText: TextView

    /** 模型页里的绘图模型状态文本（模型统一在模型页加载） */
    private var drawModelStatus: TextView? = null
    /** 已复制的绘图模型列表容器 */
    private var drawSavedContainer: LinearLayout? = null
    /** 绘图模型的「加载/卸载」二合一按钮 */
    private var drawToggleBtn: TextView? = null
    /** 当前选定的绘图主模型（绝对路径） */
    private var drawMainPath: String? = null
    /** LoRA 状态文本（模型页） */
    private var loraStatusTv: TextView? = null
    /** 已安装 LoRA 列表容器（模型页） */
    private var loraBox: LinearLayout? = null
    /** 上次启动时发现「加载绘图模型」中途崩了（native 崩溃，Java 层捕不到） */
    private var pendingDrawCrash = false
    /** 上次崩溃时已执行的阶段（来自 draw/.loadstage） */
    private var pendingDrawStage: String? = null
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
        installCrashHandler()
        buildUi()
    }

    /** 崩溃自捕获：Java/ART 层异常（含 UnsatisfiedLinkError）写进文件，下次启动可见。 */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(
                    "${java.util.Date()}\nthread=${t.name}\n" + android.util.Log.getStackTraceString(e)
                )
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        // 上次加载绘图模型中途崩了？阶段文件还在 → 说明 native 崩（Java 层捕不到），
        // 但里面已经记录了崩到哪一步。
        val st = File(filesDir, "draw/.loadstage")
        if (st.exists()) {
            pendingDrawStage = runCatching { st.readText() }.getOrNull()
            runCatching { st.delete() }
            pendingDrawCrash = true
        }
    }

    /** 读并清空崩溃日志（供模型页展示） */
    private fun takeCrashLog(): String? {
        val f = File(filesDir, "crash.txt")
        if (!f.exists()) return null
        val s = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return s
    }

    override fun onPause() {
        super.onPause()
        saveSessions()
        drawPage?.persistAll()   // 绘图参数兜底落盘
    }

    override fun onDestroy() {
        super.onDestroy()
        try { saveSessions() } catch (_: Throwable) {}
        scope.cancel()
        try { drawPage?.release() } catch (_: Throwable) {}
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

        // 四页内容：对话 / 模型 / 绘图 / 关于（底部菜单切换）
        tabChat = buildChatPage()
        // 注意顺序：DrawPage 必须先建好并赋给 drawPage，再建「模型」页。
        // 「模型」页构建时就地读取 drawPage 的状态（LoRA 列表 / 已复制绘图模型清单），
        // 顺序反了会拿到 null，每次启动都显示成「未安装」。
        val dpg = DrawPage(this, scope, C_PRIMARY, C_TEXT, C_SUBTEXT)
        drawPage = dpg
        tabModels = buildModelsPage()
        tabSettings = buildSettingsPage()
        // 绘图模型就绪 → 进入绘图模式（对话页输入即正面提示词）
        dpg.onPipelineReady = {
            // 回调可能来自 IO 线程，UI 操作统一回主线程
            runOnUiThread {
                dpg.llmLoaded = false
                updateThinkEnabled()
                drawModelStatus?.text = dpg.modelSummary()
                refreshDrawModels()
            }
        }
        dpg.onSaveImage = { bmp, name -> saveImageToGallery(bmp, name) }
        dpg.onStatus = { text, isError ->
            runOnUiThread { setStatus(text, if (isError) C_ERR else C_WARN) }
        }
        // 注意：不在启动时自动加载绘图模型——native 加载失败会直接崩掉启动流程。
        // 改为在「模型」页手动点「加载绘图模型」（见下方按钮）。
        tabDraw = ScrollView(this).apply {
            setBackgroundColor(C_BG)
            addView(dpg.build())
        }
        val contentFrame = FrameLayout(this)
        contentFrame.addView(tabChat, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabModels, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabSettings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabDraw, FrameLayout.LayoutParams(
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

        // 滚动规则：只看「距离底部的距离」，不看滚动方向（方向判在可选中文本上是不可靠的）。
        //   距底在阈值（约 15 行正文）以内 → 视为已在底部：隐藏「回到底部」按钮，并恢复自动跟随；
        //   超过阈值 → 停止自动跟随，显示「回到底部」按钮。
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> refreshFollowState() }
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
        appBarMenu = iconButton("☰") { openDrawer() }
        r1.addView(appBarMenu, wrapWrap())

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
            text = getString(R.string.s_116)
            textSize = 11.5f
            setTextColor(Color.parseColor("#DCE5FF"))
        }
        statusRow.addView(statusTv, wrapWrap())
        titleBox.addView(statusRow, matchWrap())

        r1.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        appBarPlus = iconButton("＋") { newSession() }
        r1.addView(appBarPlus, wrapWrap())
        bar.addView(r1, matchWrap())
        return bar
    }

    /** 模型页：选择/加载模型 + 后端选择。 */
    private fun buildModelsPage(): View {
        val sv = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ================= 运行方式（对话 / 绘图共用） =================
        val card = sectionCard()
        card.addView(pageTitle(getString(R.string.s_181)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.s_069)
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
        card.addView(row, matchWrap().apply { topMargin = dp(10) })
        card.addView(
            hintText(getString(R.string.s_075)),
            matchWrap().apply { topMargin = dp(4) }
        )

        // ================= 对话模型 =================
        val chatCard = sectionCard()
        chatCard.addView(pageTitle(getString(R.string.s_076)))
        chatCard.addView(hintText(getString(R.string.s_122)))

        modelInfoText = TextView(this).apply {
            text = getString(R.string.s_120)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        chatCard.addView(modelInfoText, matchWrap().apply { topMargin = dp(6) })

        chatCard.addView(actionButton(getString(R.string.s_189)) { pickModelFile() },
            matchWrap().apply { topMargin = dp(8) })

        savedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chatCard.addView(savedContainer, matchWrap().apply { topMargin = dp(6) })

        loadButton = actionButton(getString(R.string.s_060)) { toggleLoad() }
        chatCard.addView(loadButton, matchWrap().apply { topMargin = dp(8) })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            indeterminateTintList = ColorStateList.valueOf(C_PRIMARY)
            progressTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        chatCard.addView(progressBar, matchWrap().apply { topMargin = dp(8) })

        // ================= 绘图模型 =================
        val drawCard = sectionCard()
        drawCard.addView(pageTitle(getString(R.string.s_155)))
        drawCard.addView(
            hintText(getString(R.string.s_030)),
            matchWrap().apply { topMargin = dp(4) }
        )
        val dStatus = TextView(this).apply {
            text = getString(R.string.s_113)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        drawModelStatus = dStatus
        drawCard.addView(dStatus, matchWrap().apply { topMargin = dp(6) })
        // 上次崩溃信息（自捕获，供排查）
        if (pendingDrawCrash) {
            drawCard.addView(TextView(this).apply {
                text = getString(R.string.s_035) + (pendingDrawStage ?: getString(R.string.s_197))
                textSize = 11f
                setTextColor(C_ERR)
                setPadding(0, dp(6), 0, 0)
            }, matchWrap())
        }
        takeCrashLog()?.let { log ->
            drawCard.addView(TextView(this).apply {
                text = getString(R.string.s_038) + log.takeLast(1200)
                textSize = 10.5f
                setTextColor(C_ERR)
                setPadding(0, dp(6), 0, 0)
            }, matchWrap())
        }

        drawCard.addView(
            actionButton(getString(R.string.s_190)) { pickDrawModelTree() },
            matchWrap().apply { topMargin = dp(8) }
        )

        drawSavedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        drawCard.addView(drawSavedContainer, matchWrap().apply { topMargin = dp(6) })
        // 加载 / 卸载合成一个按钮：未加载时点击 = 加载，已加载时点击 = 卸载
        drawToggleBtn = actionButton(getString(R.string.s_061)) { onDrawToggleClick() }
        drawCard.addView(drawToggleBtn, matchWrap().apply { topMargin = dp(8) })
        drawCard.addView(
            smallButton(getString(R.string.s_123)) {
                val f = File(filesDir, "draw/.loadstage")
                val log = if (f.exists()) runCatching { f.readText() }.getOrDefault(getString(R.string.s_198)) else getString(R.string.s_196)
                val body = log.takeLast(8000)
                val tv = TextView(this).apply {
                    text = body
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                val sc = ScrollView(this).apply { addView(tv) }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.s_157))
                    .setView(sc)
                    .setPositiveButton(getString(R.string.s_071)) { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ponko-draw-log", body))
                        toast(getString(R.string.s_087))
                    }
                    .setNeutralButton(getString(R.string.s_136)) { _, _ ->
                        runCatching { f.delete() }
                        toast(getString(R.string.s_112))
                    }
                    .setNegativeButton(getString(R.string.s_049), null)
                    .show()
            },
            matchWrap().apply { topMargin = dp(10) }
        )

        // ================= LoRA 加速 =================
        val loraCard = sectionCard()
        loraCard.addView(pageTitle(getString(R.string.s_021)))
        loraCard.addView(
            hintText(getString(R.string.s_017)),
            matchWrap().apply { topMargin = dp(4) }
        )
        val lStatus = TextView(this).apply {
            text = getString(R.string.s_117)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        loraStatusTv = lStatus
        loraCard.addView(lStatus, matchWrap().apply { topMargin = dp(6) })

        loraBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        loraCard.addView(loraBox, matchWrap().apply { topMargin = dp(6) })

        loraCard.addView(
            actionButton(getString(R.string.s_039)) { pickLoraSource() },
            matchWrap().apply { topMargin = dp(8) }
        )
        loraCard.addView(
            smallButton(getString(R.string.s_051)) { confirmDeleteLora() },
            matchWrap().apply { topMargin = dp(10) }
        )

        root.addView(card)
        root.addView(chatCard)
        root.addView(drawCard)
        root.addView(loraCard)

        refreshLoraUi()
        refreshDrawModels()

        sv.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return sv
    }

    /** 关于页：角色原图 + 说明。 */
    private fun buildSettingsPage(): View {
        val sv = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val card = card()

        card.addView(pageTitle(getString(R.string.s_048)))
        card.addView(hintText(getString(R.string.s_142)))
        val portrait = ImageView(this).apply {
            setImageResource(R.drawable.about_portrait)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(portrait, matchWrap())
        card.addView(hintText(getString(R.string.s_025)))

        card.addView(pageTitle(getString(R.string.s_104)), matchWrap().apply { topMargin = dp(16) })
        card.addView(hintText(getString(R.string.s_097)))

        card.addView(pageTitle(getString(R.string.s_171)), matchWrap().apply { topMargin = dp(16) })
        card.addView(hintText(getString(R.string.s_140)))

        card.addView(pageTitle(getString(R.string.s_043)), matchWrap().apply { topMargin = dp(16) })
        card.addView(hintText("visitor257"))

        card.addView(pageTitle(getString(R.string.s_194)), matchWrap().apply { topMargin = dp(16) })
        card.addView(linkText(getString(R.string.s_107), "https://github.com/visitor257/Ponko"))

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

    /** 模型页的分区卡片：每块内容一张，底部留 10dp 间距，避免全塞进一张大卡里。 */
    private fun sectionCard(): LinearLayout = card().apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
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

    /** 关于页的可点击链接（点开系统浏览器） */
    private fun linkText(label: String, url: String): TextView =
        hintText(label).apply {
            isClickable = true
            setTextColor(C_PRIMARY)
            setOnClickListener {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }.onFailure { toast(getString(R.string.s_134)) }
            }
        }

    // ---- 底部导航 ----

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(6).toFloat()
        }
        val items = listOf(
            Triple("💬", getString(R.string.s_074), 0),
            Triple("🧠", getString(R.string.s_124), 1),
            Triple("🎨", getString(R.string.s_150), 2),
            Triple("ℹ️", getString(R.string.s_048), 3),
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
        tabDraw.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 3) View.VISIBLE else View.GONE
        inputBar.visibility = if (index == 0) View.VISIBLE else View.GONE
        val isChat = index == 0
        appBarMenu.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
        appBarPlus.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
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
            text = getString(R.string.s_074)
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
            text = "Ponko"
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
        drawerBody.addView(actionButton(getString(R.string.s_201)) { closeDrawer(); newSession() },
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
                text = getString(R.string.v_001, (s.turns.size))
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
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(8))
            elevation = dp(6).toFloat()
        }

        thinkCheck = CheckBox(this).apply {
            text = getString(R.string.s_105)
            textSize = 12.5f
            setTextColor(C_TEXT)
            isChecked = true
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        bar.addView(thinkCheck, matchWrap())

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        inputEdit = EditText(this).apply {
            hint = getString(R.string.s_180)
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
        row.addView(inputEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        sendButton = TextView(this).apply {
            text = "↑"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY, 22)
            isClickable = true
            setOnClickListener { onSendOrStop() }
        }
        row.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        bar.addView(row, matchWrap())
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

    /** 对话模型：选一个 .litertlm / .gguf 文件 */
    private fun pickModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_MODEL)
    }

    /** 绘图模型：选一个包含 SD GGUF 绘图模型的文件夹（stable-diffusion.cpp 格式） */
    private fun pickDrawModelTree() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_DRAW_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (drawPage?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_MODEL && resultCode == RESULT_OK) {
            data?.data?.let { copyModelToPrivate(it) }
        }
        if (requestCode == REQ_DRAW_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            setBusy(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.prepareFromTree(uri) { stage -> drawModelStatus?.text = stage }
                setBusy(false)
                if (err == null) {
                    // 导入成功：把最新复制的那个默认标为选用
                    dpg.listModels().firstOrNull()?.let { drawMainPath = it.absolutePath }
                    drawModelStatus?.text = dpg.modelSummary()
                    setStatus(getString(R.string.s_161), C_OK)
                    toast(getString(R.string.s_091))
                    refreshDrawModels()
                } else {
                    drawModelStatus?.text = err
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
            }
        }
    }

    /** Models can't be loaded from a content:// URI, so copy them to a real file first. */
    private fun copyModelToPrivate(uri: Uri) {
        setBusy(true)
        setStatus(getString(R.string.s_130), C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                val name = queryDisplayName(uri) ?: ("model_${System.currentTimeMillis()}.litertlm")
                val dest = File(dir, name)
                if (!dest.exists()) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(getString(R.string.s_111))
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
                    modelInfoText.text = getString(R.string.v_002, (dest.name), (fmtSize(dest.length())))
                    setStatus(getString(R.string.s_093), C_WARN)
                    refreshSavedModels()
                    toast(getString(R.string.v_003, (dest.name)))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus(getString(R.string.s_072), C_ERR)
                    toast(getString(R.string.v_004, (e.message)))
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
            text = getString(R.string.s_089)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }, matchWrap())

        for (f in files) {
            val sel = f.absolutePath == modelPath
            val isGguf = f.name.endsWith(".gguf", ignoreCase = true)
            // .gguf 可能是语言模型也可能是画图模型，读头部元数据判断
            val kind = if (isGguf) ggufKindCache.getOrPut(f.absolutePath) { GgufProbe.probe(f) }
            else null
            val isImageModel = kind == GgufProbe.Kind.IMAGE
            val tag = when {
                isImageModel -> getString(R.string.s_151)
                isGguf -> "GGUF"
                else -> "LiteRT"
            }
            val chip = TextView(this).apply {
                text = "▶ ${f.name}　[$tag] ${fmtSize(f.length())}"
                textSize = 12.5f
                setTextColor(if (isImageModel) C_IDLE else if (sel) C_PRIMARY else C_TEXT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (sel) C_PRIMARY_SOFT else Color.rgb(247, 248, 251), 10,
                    strokeDp = if (sel) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener {
                if (isImageModel) {
                    toast(getString(R.string.s_188))
                } else {
                    selectSavedModel(f)
                }
            }
            chip.setOnLongClickListener { confirmDeleteModel(f); true }
            savedContainer.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
    }

    // ================= 已复制的绘图模型 =================

    /** 绘图模型的二合一按钮：未加载 → 加载；已加载 → 卸载。 */
    private fun onDrawToggleClick() {
        val dpg = drawPage
        if (dpg == null) {
            toast(getString(R.string.s_167))
            return
        }
        if (dpg.isReady()) {
            dpg.unloadModel()
            updateThinkEnabled()
            drawModelStatus?.text = dpg.modelSummary()
            setStatus(getString(R.string.s_116), C_IDLE)
            toast(getString(R.string.s_160))
            refreshDrawToggle()
            return
        }
        if (!dpg.hasModel()) {
            toast(getString(R.string.s_185))
            return
        }
        scope.launch {
            drawModelStatus?.text = getString(R.string.s_128)
            val picked = drawMainPath?.let { File(it) }
            dpg.useGpu = (backendSpinner.selectedItem.toString() == "GPU")
            val err = dpg.loadExisting(picked)
            drawModelStatus?.text = if (err == null) dpg.modelSummary() else err
            if (err == null) {
                drawMainPath = dpg.currentMainName()?.let { File(filesDir, "draw/$it").absolutePath }
                dpg.llmLoaded = false
                updateThinkEnabled()
                refreshDrawModels()
                setStatus(getString(R.string.s_162), C_OK)
                toast(getString(R.string.s_159))
            } else {
                setStatus(getString(R.string.s_156), C_ERR)
                toast(err)
            }
            refreshDrawToggle()
        }
    }

    /** 按「绘图模型是否已加载」更新二合一按钮的文案与配色。
     *  与对话模型保持一致：加载=蓝色实心；已加载=浅灰描边（避免误以为还是主操作）。 */
    private fun refreshDrawToggle() {
        val b = drawToggleBtn ?: return
        if (drawPage?.isReady() == true) {
            b.text = getString(R.string.s_063)
            b.background = rounded(Color.rgb(246, 247, 250), 12,
                strokeDp = 1, strokeColor = Color.rgb(219, 224, 234))
            b.setTextColor(C_TEXT)
        } else {
            b.text = getString(R.string.s_061)
            b.background = rounded(C_PRIMARY, 12)
            b.setTextColor(Color.WHITE)
        }
    }

    /** 列出私有目录里已复制的绘图模型（点击选用并加载 · 长按删除）。 */
    private fun refreshDrawModels() {
        refreshDrawToggle()
        val box = drawSavedContainer ?: return
        box.removeAllViews()
        val dpg = drawPage
        val files = dpg?.listModels().orEmpty()
        if (files.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(TextView(this).apply {
            text = getString(R.string.s_090)
            textSize = 12f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())

        val active = dpg?.currentMainName()
        for (f in files) {
            val isMain = active != null && f.name == active
            val isSel = f.absolutePath == drawMainPath
            val chip = TextView(this).apply {
                val role = if (isMain) getString(R.string.s_099) else if (files.size > 1) getString(R.string.s_149) else ""
                val suffix = if (role.isEmpty()) "" else "　[$role]"
                val q = dpg?.quantOf(f)
                val qTag = if (q != null) "　[$q]" else ""
                text = "▶ ${f.name}$suffix$qTag　${fmtSize(f.length())}"
                textSize = 12.5f
                setTextColor(if (isSel || isMain) C_PRIMARY else C_TEXT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (isSel || isMain) C_PRIMARY_SOFT else Color.rgb(247, 248, 251), 10,
                    strokeDp = if (isSel || isMain) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener { selectDrawModel(f) }
            chip.setOnLongClickListener { confirmDeleteDrawModel(f); true }
            box.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
    }

    /** 点击某个绘图模型 → 只标记为选用，不加载（与对话模型一致，需再点「加载绘图模型」）。 */
    private fun selectDrawModel(f: File) {
        drawMainPath = f.absolutePath
        val cur = drawPage?.currentMainName()
        drawModelStatus?.text = if (cur != null && cur != f.name) {
            getString(R.string.v_005, (f.name), (cur))
        } else {
            getString(R.string.v_006, (f.name))
        }
        refreshDrawToggle()
        refreshDrawModels()
    }

    // ================= LoRA =================

    /** 刷新模型页的 LoRA 状态与列表 */
    private fun refreshLoraUi() {
        val dpg = drawPage
        loraStatusTv?.text = dpg?.loraSummary() ?: getString(R.string.s_117)
        val box = loraBox ?: return
        box.removeAllViews()
        val all = dpg?.listLoras().orEmpty()
        if (all.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(TextView(this).apply {
            text = getString(R.string.s_092)
            textSize = 12f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())
        for (f in all) {
            box.addView(TextView(this).apply {
                text = "• ${f.name}　${fmtSize(f.length())}"
                textSize = 12.5f
                setTextColor(C_TEXT)
                setPadding(dp(4), dp(4), 0, 0)
            }, matchWrap())
        }
    }

    /** 选下载源：官方 / hf-mirror 镜像 */
    private fun pickLoraSource() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_041))
            .setItems(arrayOf(getString(R.string.s_028), getString(R.string.s_029))) { _, which ->
                startLoraDownload(useMirror = which == 0)
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun startLoraDownload(useMirror: Boolean) {
        val dpg = drawPage ?: return
        val src = if (useMirror) "hf-mirror.com" else "huggingface.co"
        loraStatusTv?.text = getString(R.string.v_007, (src))
        setStatus(getString(R.string.s_018), C_WARN)
        var lastPct = -2
        scope.launch {
            val err = dpg.downloadLora(useMirror) { done, total ->
                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                if (pct != lastPct) {
                    lastPct = pct
                    runOnUiThread {
                        loraStatusTv?.text = if (pct >= 0)
                            getString(R.string.v_008, (src), (pct), (fmtSize(done)), (fmtSize(total)))
                        else
                            getString(R.string.v_009, (src), (fmtSize(done)))
                    }
                }
            }
            runOnUiThread {
                if (err == null) {
                    toast(getString(R.string.s_020))
                    setStatus(getString(R.string.s_023), C_OK)
                } else {
                    toast(err)
                    setStatus(getString(R.string.s_019), C_ERR)
                }
                refreshLoraUi()
            }
        }
    }

    private fun confirmDeleteLora() {
        val dpg = drawPage ?: return
        val all = dpg.listLoras()
        if (all.isEmpty()) {
            toast(getString(R.string.s_135))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_052))
            .setMessage(all.joinToString("、") { it.name } + getString(R.string.s_026))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                var n = 0
                all.forEach { if (dpg.deleteLora(it)) n++ }
                toast(getString(R.string.v_010, (n)))
                refreshLoraUi()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 长按删除一个已复制的绘图模型文件。 */
    private fun confirmDeleteDrawModel(f: File) {
        val dpg = drawPage ?: return
        if (dpg.currentMainName() == f.name && dpg.isReady()) {
            toast(getString(R.string.s_172))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_056))
            .setMessage(getString(R.string.v_011, (f.name), (fmtSize(f.length()))))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val ok = dpg.deleteModel(f)
                if (drawMainPath == f.absolutePath) drawMainPath = null
                toast(if (ok) getString(R.string.v_012, (f.name)) else getString(R.string.s_053))
                drawModelStatus?.text = dpg.modelSummary()
                refreshDrawModels()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun selectSavedModel(f: File) {
        if (busy) {
            toast(getString(R.string.s_102))
            return
        }
        if (busy) {
            toast(getString(R.string.s_102))
            return
        }
        modelPath = f.absolutePath
        modelInfoText.text = getString(R.string.v_013, (f.name), (fmtSize(f.length())))
        setStatus(getString(R.string.s_096), C_WARN)
        refreshSavedModels()
    }

    private fun confirmDeleteModel(f: File) {
        if (f.absolutePath == modelPath && (engine != null || llamaModel != null)) {
            toast(getString(R.string.s_172))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_055))
            .setMessage(getString(R.string.v_014, (f.name), (fmtSize(f.length()))))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                f.delete()
                if (modelPath == f.absolutePath) {
                    modelPath = null
                    modelInfoText.text = getString(R.string.s_120)
                }
                refreshSavedModels()
                toast(getString(R.string.s_086))
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun toggleLoad() {
        if (engine != null || llamaModel != null) {
            unloadModel()
            return
        }
        val path = modelPath ?: run { toast(getString(R.string.s_175)); return }
        val isGguf = path.endsWith(".gguf", ignoreCase = true)
        val backendName = backendSpinner.selectedItem.toString()
        val thinking = thinkCheck.isChecked
        setBusy(true)
        setStatus(getString(R.string.s_127), C_WARN)
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
                        setStatus(getString(R.string.s_078), C_OK)
                        if (backendName == "GPU") toast(getString(R.string.s_016))
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
                        convDrawCapable = canDrawFromChat
                        setStatus(getString(R.string.v_015, (backendName)), C_OK)
                    }
                }
                withContext(Dispatchers.Main) {
                    loadButton.text = getString(R.string.s_062)
                    loadButton.background = rounded(Color.rgb(246, 247, 250), 12,
                        strokeDp = 1, strokeColor = Color.rgb(219, 224, 234))
                    loadButton.setTextColor(C_TEXT)
                    // 加载语言模型 → 对话页回到聊天（drawMode 由「有无语言模型」自动决定）
                    drawPage?.llmLoaded = true
                    updateThinkEnabled()
                    addSystemHint(getString(R.string.s_077))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus(getString(R.string.s_058), C_ERR)
                    toast(getString(R.string.v_016, (e.message)))
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
        drawPage?.llmLoaded = false
        loadButton.text = getString(R.string.s_060)
        loadButton.background = rounded(C_PRIMARY, 12)
        loadButton.setTextColor(Color.WHITE)
        setStatus(getString(R.string.s_079), C_IDLE)
        addSystemHint(getString(R.string.s_080))
        // 语言模型卸载后，若绘图模型还在，对话页会自动变回出图模式
        updateThinkEnabled()
    }

    // ================= chat =================

    /** Send button doubles as a stop button: while generating it shows a square ■. */
    private fun onSendOrStop() {
        val job = genJob
        if (job != null && job.isActive) {
            // 关键：先让原生侧真正停下来。只取消协程 Flow 的话，LiteRT / llama.cpp 的
            // native 推理线程还在跑，下一次发送会撞上同一份上下文直接卡死。
            runCatching { conversation?.cancelProcess() }
            runCatching { ggufIterator?.cancel() }
            // 绘图（MNN / sd.cpp）也要打断：否则 native 还在跑，界面已停
            drawPage?.cancel()
            job.cancel()
            // 不能只依赖协程的 finally 来复位：native 推理不响应协程取消时 finally 会迟迟不执行，
            // busy 一直卡在 true，之后每条消息都被「正在生成中」挡回来。这里主动复位。
            genJob = null
            if (busy) setBusy(false)
            setStoppingUi(false)
            // cancelProcess() 之后 LiteRT 的 Conversation 可能已不可用：
            // 标记下一条消息发送前用历史静默重建，否则中断后再也发不出内容。
            if (conversation != null) convNeedsRebuild = true
            toast(getString(R.string.s_083))
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
        if (busy) { toast(getString(R.string.s_144)); return }
        saveSessions()
        val s = ChatSession(System.currentTimeMillis(), getString(R.string.s_109))
        sessions += s
        current = s
        restoreSession()
        saveSessions()
        toast(getString(R.string.s_095))
    }

    private fun switchTo(s: ChatSession) {
        if (s === current) return
        if (busy) { toast(getString(R.string.s_144)); return }
        saveSessions()
        current = s
        restoreSession()
        saveSessions()
    }

    private fun confirmDeleteSession(s: ChatSession) {
        if (sessions.size <= 1) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.s_139))
                .setMessage(getString(R.string.s_187))
                .setPositiveButton(getString(R.string.s_136)) { _, _ ->
                    s.turns.clear()
                    s.title = getString(R.string.s_109)
                    restoreSession()
                    saveSessions()
                    if (drawerOpen) refreshDrawer()
                    toast(getString(R.string.s_101))
                }
                .setNegativeButton(getString(R.string.s_066), null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_054))
            .setMessage(s.title)
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val wasCurrent = s === current
                sessions.remove(s)
                if (wasCurrent) { current = sessions.last(); restoreSession() }
                saveSessions()
                if (drawerOpen) refreshDrawer()
                toast(getString(R.string.s_086))
            }
            .setNegativeButton(getString(R.string.s_066), null)
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
        // 两种模型都就绪时，告知语言模型它可以用 <draw> 命令调绘图模型
        if (canDrawFromChat) msgs += Pair("system", drawToolPrompt)
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
            // 每次生成都换随机种子，否则「重新生成」会得到一模一样的回答
            .withSeed((System.nanoTime() and 0x7FFFFFFF).toInt())
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
        // 两种模型都就绪时，告知语言模型它可以用 <draw> 命令调绘图模型
        if (canDrawFromChat) msgs += Message.system(drawToolPrompt)
        for (t in s.turns) {
            msgs += Message.user(t.user)
            if (t.answer.isNotEmpty()) msgs += Message.model(t.answer)
        }
        return ConversationConfig(
            initialMessages = msgs,
            // 每次重建会话都换随机种子，否则「重新生成」会得到一模一样的回答
            samplerConfig = SamplerConfig(
                seed = (System.nanoTime() and 0x7FFFFFFF).toInt(),
                topP = 0.9,
                temperature = 0.7,
                topK = 40,
            ),
            thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = 2048),
        )
    }

    /**
     * 思考开关变化后重建会话。
     * LiteRT-LM 的思考标记是在会话创建时写入提示模板的，
     * 会话跑起来后仅改 per-call 的 ThinkingConfig 不会生效（尤其是 关→开），
     * 所以必须按新模式重建会话；历史通过 initialMessages 保留。
     */
    private fun rebuildConversation(silent: Boolean = false) {
        val eng = engine ?: return
        val thinking = thinkCheck.isChecked
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = try {
            eng.createConversation(configFor(current, thinking))
        } catch (e: Throwable) {
            toast(getString(R.string.v_017, (e.message)))
            eng.createConversation(
                ConversationConfig(
                    thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = 2048)
                )
            )
        }
        convThinking = thinking
        convDrawCapable = canDrawFromChat
        if (!silent) {
            addSystemHint(
                if (thinking) getString(R.string.s_204)
                else getString(R.string.s_085)
            )
        }
    }

    /** 重建 LiteRT 会话（若模型已加载）并把聊天区重绘为当前对话的内容。 */
    private fun restoreSession(showHint: Boolean = true) {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        convThinking = null
        val eng = engine
        if (eng != null && llamaModel == null) {
            val thinking = thinkCheck.isChecked
            conversation = eng.createConversation(configFor(current, thinking))
            convThinking = thinking
            convDrawCapable = canDrawFromChat
        }
        chatContainer.removeAllViews()
        autoFollow = true
        jumpButton.visibility = View.GONE
        if (current.turns.isEmpty()) {
            if (showHint) {
                addSystemHint(getString(R.string.s_032) +
                    getString(R.string.s_033))
            }
        } else {
            for (t in current.turns) renderTurn(t)
        }
        scrollToBottom(force = true)
    }

    /**
     * 重新生成某一轮的回答。
     * 该轮之后的轮次依赖这轮的上下文，必须一并丢弃；然后把它重新送进去生成。
     */
    private fun regenerate(turn: QaTurn) {
        if (busy) {
            toast(getString(R.string.s_144))
            return
        }
        val idx = current.turns.indexOf(turn)
        if (idx < 0) return
        val text = turn.user
        val dropped = current.turns.size - idx - 1
        while (current.turns.size > idx) current.turns.removeAt(current.turns.size - 1)
        restoreSession(showHint = false)
        if (dropped > 0) toast(getString(R.string.v_018, (dropped)))
        inputEdit.setText(text)
        doSend()
    }

    /** 把一轮已有问答重绘到聊天区（历史回填）。 */
    /** 绘图模式下思考开关无意义（不经过对话模型），置灰禁用 */
    private fun updateThinkEnabled() {
        // 可能由 IO 线程上的回调触发；setEnabled 会驱动 Ripple 动画，必须回主线程
        runOnUiThread {
            val enabled = !drawMode
            thinkCheck.isEnabled = enabled
            thinkCheck.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun renderTurn(t: QaTurn) {
        addUserBubble(t.user)
        val ai = addAiArea { regenerate(t) }
        val imgName = t.image
        if (imgName != null) {
            val bmp = readChatImage(imgName)
            if (bmp != null) attachImageBubble(ai, bmp, imgName) else ai.answer.text = getString(R.string.s_003)
        }
        if (t.thought.isNotEmpty()) {
            ai.thoughtBox.visibility = View.VISIBLE
            ai.thoughtBody.text = t.thought
            ai.thoughtHeader.text = getString(R.string.s_205)
        }
        if (t.answer.isNotEmpty()) {
            markwonFull.setMarkdown(ai.answer, t.answer)
        } else {
            ai.answer.text = getString(R.string.s_005)
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
                    ts.put(
                        JSONObject().put("u", t.user).put("a", t.answer).put("th", t.thought)
                            .put("img", t.image ?: "")
                    )
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
                            o.optString("title", getString(R.string.s_109)),
                        )
                        val ts = o.optJSONArray("turns")
                        if (ts != null) {
                            for (j in 0 until ts.length()) {
                                val t = ts.getJSONObject(j)
                                s.turns += QaTurn(
                                    t.optString("u"), t.optString("a"), t.optString("th"),
                                    t.optString("img").ifEmpty { null }
                                )
                            }
                        }
                        sessions += s
                    }
                }
            }
        } catch (_: Throwable) {}
        if (sessions.isEmpty()) sessions += ChatSession(System.currentTimeMillis(), getString(R.string.s_109))
        current = sessions.getOrElse(idx) { sessions.first() }
    }

    // ================= 绘图模式（对话页直接出图） =================

    private fun doDrawFromChat(prompt: String, dpg: DrawPage) {
        if (busy) { toast(getString(R.string.s_133)); return }
        val turn = QaTurn(prompt)
        current.turns += turn
        if (current.title == getString(R.string.s_109)) current.title = prompt.take(18)

        inputEdit.setText("")
        addUserBubble(prompt)
        jumpToBottom()
        setBusy(true)
        setStoppingUi(true)
        setStatus(getString(R.string.s_166), C_WARN)

        val ai = addAiArea()
        ai.regenButton.visibility = View.GONE
        markwonStream.setMarkdown(ai.answer, getString(R.string.s_202))

        genJob = scope.launch {
            // 气泡里每秒刷新一次进度（sd.cpp 每步回调 + 本地计时），
            // 否则整段生成期间只能看到静止的「🎨 正在生成图片…」
            var curStep = 0
            var totalStep = 0
            val drawStartedAt = System.currentTimeMillis()
            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val sec = (System.currentTimeMillis() - drawStartedAt) / 1000
                    val stepInfo = if (totalStep > 0) getString(R.string.v_019, (curStep), (totalStep)) else ""
                    markwonStream.setMarkdown(ai.answer, getString(R.string.v_020, (stepInfo), (sec)))
                }
            }
            try {
                val img = dpg.generateImage(prompt) { cur, total ->
                    curStep = cur
                    totalStep = total
                    runOnUiThread {
                        if (cur == total || cur % 2 == 0) setStatus(getString(R.string.v_021, (cur), (total)), C_WARN)
                    }
                }
                ticker.cancel()
                val bmp = dpg.toBitmap(img)
                val name = "ponko_${System.currentTimeMillis()}.png"
                val saved = withContext(Dispatchers.IO) { writeChatImage(bmp, name) }
                turn.image = if (saved) name else null
                turn.answer = "🎨 seed=${img.seed}"
                markwonStream.setMarkdown(ai.answer, "")
                attachImageBubble(ai, bmp, name)
                setStatus(getString(R.string.s_153), C_OK)
            } catch (e: com.litertchat.app.draw.GenerationCancelledException) {
                ticker.cancel()
                turn.answer = getString(R.string.s_004)
                markwonStream.setMarkdown(ai.answer, getString(R.string.s_004))
                setStatus(getString(R.string.s_082), C_IDLE)
            } catch (e: CancellationException) {
                ticker.cancel()
                turn.answer = getString(R.string.s_004)
                markwonStream.setMarkdown(ai.answer, getString(R.string.s_004))
                setStatus(getString(R.string.s_082), C_IDLE)
                throw e
            } catch (e: Throwable) {
                ticker.cancel()
                turn.answer = getString(R.string.v_022, (e.message))
                markwonStream.setMarkdown(ai.answer, getString(R.string.v_023, (e.message)))
                setStatus(getString(R.string.s_152), C_ERR)
            } finally {
                ticker.cancel()
                ai.regenButton.visibility = View.VISIBLE
                saveSessions()
                setBusy(false)
                setStoppingUi(false)
                scrollToBottom()
            }
        }
    }

    /** 把图片写进应用私有目录，返回是否成功 */
    private fun writeChatImage(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Throwable) {
        false
    }

    private fun readChatImage(name: String): Bitmap? = try {
        BitmapFactory.decodeFile(File(File(filesDir, "chatimg"), name).absolutePath)
    } catch (_: Throwable) {
        null
    }

    /** 在 AI 气泡里插入图片；点图弹确认框再存相册（以前点一下就存，容易误触） */
    private fun attachImageBubble(ai: AiArea, bmp: Bitmap, name: String) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            isClickable = true
            isFocusable = false
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(getString(R.string.s_170))
                    .setPositiveButton(getString(R.string.s_044)) { _, _ -> saveImageToGallery(bmp, name) }
                    .setNegativeButton(getString(R.string.s_066), null)
                    .show()
            }
        }
        ai.root.addView(iv, matchWrap())
        ai.root.addView(TextView(this).apply {
            text = getString(R.string.s_141)
            textSize = 11f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())
    }

    private fun saveImageToGallery(bmp: Bitmap, name: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/Ponko"
                            )
                        }
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        true
                    } else false
                } catch (_: Throwable) {
                    false
                }
            }
            toast(if (ok) getString(R.string.s_084) else getString(R.string.s_046))
        }
    }

    private fun doSend() {
        val text = inputEdit.text.toString().trim()
        if (text.isEmpty()) return

        // 绘图模式：把输入当作正面提示词，按绘图页的参数（除正面提示词外）生成
        val dpg = drawPage
        if (drawMode && dpg != null) {
            doDrawFromChat(text, dpg)
            return
        }

        val isGgufRun = llamaModel != null
        if (conversation == null && !isGgufRun) { toast(getString(R.string.s_174)); return }
        if (busy) {
            toast(getString(R.string.s_133))
            return
        }
        var conv: Conversation? = null
        if (!isGgufRun) {
            val needThinkingRebuild = convThinking != thinkCheck.isChecked
            // 绘图能力开关变了（加载/卸载绘图模型）也要重建：
            // system 里的 <draw> 提示只在建会话时写入，不重建则模型不知道能画图
            val needCapRebuild = convDrawCapable != canDrawFromChat
            if (convNeedsRebuild || needThinkingRebuild || needCapRebuild) {
                // 上一轮被中断：cancelProcess() 之后会话可能已不可用，静默用历史重建
                convNeedsRebuild = false
                // 只有「思考开关变化」需要给用户可见提示，中断恢复/能力变化都静默重建
                rebuildConversation(silent = !needThinkingRebuild)
            }
            conv = conversation ?: run { toast(getString(R.string.s_042)); return }
        }

        val turn = QaTurn(text)
        current.turns += turn
        if (current.title == getString(R.string.s_109)) current.title = text.take(18)

        inputEdit.setText("")
        addUserBubble(text)
        jumpToBottom()
        setBusy(true)
        setStoppingUi(true)
        setStatus(getString(R.string.s_143), C_WARN)

        val ai = addAiArea { regenerate(turn) }
        ai.regenButton.visibility = View.GONE   // 生成结束后再显示，避免与「停止」混淆
        val answerBuf = StringBuilder()
        val thoughtBuf = StringBuilder()
        var lastRender = 0L
        var cancelled = false

        fun renderAnswer(force: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastRender < 120) return
            lastRender = now
            val md = answerBuf.toString()
            if (md.isNotEmpty()) {
                // 流式重渲染可能把焦点从输入框抢走：用户正在打字时把焦点还回去
                val hadFocus = inputEdit.hasFocus()
                markwonStream.setMarkdown(ai.answer, md)
                if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
            }
        }

        fun renderThought(force: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastRender < 90) return
            val hadFocus = inputEdit.hasFocus()
            ai.thoughtBody.text = thoughtBuf.toString()
            ai.thoughtHeader.text = if (ai.thoughtBody.visibility == View.VISIBLE)
                getString(R.string.s_206) else getString(R.string.s_205)
            if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
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
                            val iterable = lm.generateChat(params)
                            val it = iterable.iterator()
                            ggufIterator = it
                            try {
                                while (it.hasNext()) {
                                    val out = it.next()
                                    if (out.text.isNotEmpty()) send(out.text)
                                }
                            } finally {
                                runCatching { it.close() }
                                ggufIterator = null
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
                        ai.answer.text = getString(R.string.s_006)
                        ai.answer.setTextColor(C_SUBTEXT)
                    } else if (answerBuf.isEmpty()) {
                        ai.answer.text = getString(R.string.s_007)
                        ai.answer.setTextColor(C_SUBTEXT)
                    } else {
                        markwonFull.setMarkdown(ai.answer, answerBuf.toString())
                    }
                    saveSessions()
                    setStatus(getString(R.string.s_081), C_OK)
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
                    val thoughtDelta = msg.channels["thought"]
                    if (!thoughtDelta.isNullOrEmpty()) {
                        val cur = thoughtBuf.toString()
                        val merged = mergeStreamDelta(cur, thoughtDelta)
                        if (merged != cur) {
                            thoughtBuf.setLength(0)
                            thoughtBuf.append(merged)
                            turn.thought = merged
                            if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                            renderThought(false)
                        }
                    }
                    if (delta.isNotEmpty()) {
                        val cur = answerBuf.toString()
                        val merged = mergeStreamDelta(cur, delta)
                        if (merged != cur) {
                            answerBuf.setLength(0)
                            answerBuf.append(merged)
                            turn.answer = merged
                            renderAnswer(false)
                        }
                    }
                    scrollToBottom()
                }

                if (answerBuf.isEmpty() && thoughtBuf.isNotEmpty()) {
                    ai.answer.text = getString(R.string.s_006)
                    ai.answer.setTextColor(C_SUBTEXT)
                } else if (answerBuf.isEmpty()) {
                    ai.answer.text = getString(R.string.s_007)
                    ai.answer.setTextColor(C_SUBTEXT)
                } else {
                    // 生成结束：用完整渲染器一次性渲染（含表格）
                    markwonFull.setMarkdown(ai.answer, answerBuf.toString())
                }
                saveSessions()
                renderThought(true)
                setStatus(getString(R.string.s_081), C_OK)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                ai.answer.text = getString(R.string.v_024, (e.message))
                ai.answer.setTextColor(C_ERR)
                setStatus(getString(R.string.s_145), C_ERR)
                toast(getString(R.string.v_025, (e.message)))
            } finally {
                ai.regenButton.visibility = View.VISIBLE
                // 语言模型可能输出了 <draw>…</draw>：先把正文里的标记换掉再存历史
                val drawReq = if (!cancelled) takeDrawCommand(answerBuf, thoughtBuf, ai, turn) else null
                saveSessions()
                setBusy(false)
                setStoppingUi(false)
                if (cancelled) {
                    if (answerBuf.isNotEmpty()) {
                        markwonFull.setMarkdown(ai.answer, answerBuf.toString())
                    } else if (thoughtBuf.isEmpty()) {
                        ai.answer.text = getString(R.string.s_004)
                        ai.answer.setTextColor(C_SUBTEXT)
                    }
                    setStatus(getString(R.string.s_082), C_IDLE)
                } else if (drawReq != null) {
                    // 交给绘图模型出图（必须等 busy 复位后再启动，否则会被当成「正在生成中」拦住）
                    val dpg = drawPage
                    if (dpg != null) doDrawFromChat(drawReq, dpg)
                }
                scrollToBottom()
            }
        }
    }

    private fun extractText(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /**
     * LiteRT 的流式片段语义在版本间不完全一致（可能是增量片段，也可能带累积内容）。
     * 统一兼容，避免出现「您好，有什么可以帮你？」被反复拼接的情况：
     *   - 与已累积内容完全相同     → 重复快照，丢弃
     *   - 新片段以已累积内容开头   → 全量快照，用新片段替换
     *   - 已累积内容以新片段结尾   → 尾部重复片段，丢弃（忽略首尾空白）
     *   - 其余                     → 按增量追加
     */
    private fun mergeStreamDelta(cur: String, delta: String): String {
        if (cur.isEmpty() || delta.isEmpty()) return cur + delta
        if (delta == cur) return cur
        if (delta.startsWith(cur)) return delta
        if (cur.endsWith(delta)) return cur
        val t = delta.trim()
        if (t.isNotEmpty() && cur.endsWith(t)) return cur
        return cur + delta
    }

    private class AiArea(
        val root: LinearLayout,
        val thoughtBox: LinearLayout,
        val thoughtHeader: TextView,
        val thoughtBody: TextView,
        val answer: TextView,
        val regenButton: TextView,
    )

    /** AI reply card: collapsible thinking section on top, Markdown answer below. */
    private fun addAiArea(onRegen: (() -> Unit)? = null): AiArea {
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
            text = getString(R.string.s_205)
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
            thoughtHeader.text = if (show) getString(R.string.s_206) else getString(R.string.s_205)
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
            // 不参与键盘焦点争夺：否则流式重渲染时会把焦点从输入框抢走，导致打不了字
            isFocusable = false
        }
        wrap.addView(answer, matchWrap().apply { topMargin = dp(4) })

        // 「重新生成」：对回答不满意时，丢掉这一轮（及其后）的回答重问一次
        val regenButton = TextView(this).apply {
            text = getString(R.string.s_031)
            textSize = 12.5f
            setTextColor(C_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
            isClickable = true
            isFocusable = false
            visibility = if (onRegen != null) View.VISIBLE else View.GONE
            setOnClickListener { onRegen?.invoke() }
        }
        wrap.addView(regenButton, matchWrap())

        chatContainer.addView(wrap, matchWrap().apply {
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        scrollToBottom()
        return AiArea(wrap, thoughtBox, thoughtHeader, thoughtBody, answer, regenButton)
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
            // 滚动到底不应影响输入框焦点（用户可能正在打字）
            val hadFocus = inputEdit.hasFocus()
            scrollView.fullScroll(View.FOCUS_DOWN)
            scrollPending = false
            refreshFollowState()
            if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
        }
    }

    /** 「回到底部」判定阈值：正文 15 行的高度左右。 */
    private val jumpThresholdPx: Int by lazy {
        val line = (16f * resources.displayMetrics.scaledDensity * 1.4f).toInt() + dp(3)
        line * 15
    }

    /** 距内容底部的像素距离（负数按 0 处理）。 */
    private fun distanceToBottom(): Int {
        val child = scrollView.getChildAt(0) ?: return 0
        return (child.height - (scrollView.scrollY + scrollView.height)).coerceAtLeast(0)
    }

    /** 按「距底部距离」统一更新自动跟随状态与「回到底部」按钮。 */
    private fun refreshFollowState() {
        autoFollow = distanceToBottom() <= jumpThresholdPx
        refreshJumpButton()
    }

    private fun refreshJumpButton() {
        val child = scrollView.getChildAt(0) ?: return
        val scrollable = child.height > scrollView.height + dp(16)
        jumpButton.visibility =
            if (scrollable && distanceToBottom() > jumpThresholdPx) View.VISIBLE else View.GONE
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
        // 生成过程中不禁用输入框：可以照常打字（此时发送键是「停止」，只是不发送）
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
