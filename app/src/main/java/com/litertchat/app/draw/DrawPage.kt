package com.litertchat.app.draw

import com.litertchat.app.R

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 用户主动中断生成时抛出（与真正的失败区分开）。消息不面向用户，用固定英文。 */
class GenerationCancelledException : RuntimeException("Generation cancelled")

/**
 * 绘图页（文生图）。
 *
 * 引擎为 **自编的 stable-diffusion.cpp**（libstable-diffusion.so + libponko_sd.so JNI 桥），直接读 `.gguf` 绘图模型
 * （Anything V5 / ChilloutMix / Pony 等 SD1.5 系；CivitAI 上几乎都有 GGUF 版）。
 *
 * 加速有两条**互相独立**的线，可以叠加：
 *  - **量化**：由模型文件本身决定（Q4_0 每步比 Q8_0 便宜不少）。App 只读取并展示，不能凭空转换。
 *  - **LoRA**：挂 LCM-LoRA 这类「少步蒸馏补丁」，把 20 步压到 4~8 步（约 5 倍）。
 *
 * LoRA 文件放在 filesDir/draw/lora/，通过 prompt 里的 `lora:名字:权重` 语法激活。
 */
class DrawPage(
    private val act: Activity,
    private val scope: CoroutineScope,
    private val primary: Int,
    private val textColor: Int,
    private val subText: Int,
) {

    companion object {
        const val DIR_NAME = "draw"
        /** 打标模型/标签表目录：filesDir/tagger */
        const val TAGGER_DIR = "tagger"
        private const val REQ_IMAGE = 0x5D02
        private const val REQ_TAGGER_IMAGE = 0x5D03

        /** LCM-LoRA：HuggingFace 官方仓库路径（文件实名为 pytorch_lora_weights.safetensors） */
        private const val LORA_HF_PATH =
            "latent-consistency/lcm-lora-sdv1-5/resolve/main/pytorch_lora_weights.safetensors"

        /** 下载后重命名成这个，prompt 里就用 `lora:lcm-lora-sdv1-5:1` 引用 */
        const val LORA_NAME = "lcm-lora-sdv1-5"

        /** SharedPreferences 文件名与键（只存 UI 偏好） */
        private const val PREFS_NAME = "ponko"
        private const val KEY_USE_LORA = "useLora"

        // 绘图参数持久化（提示词按用户要求不保存，每次重来）
        private const val KEY_W = "drawW"
        private const val KEY_H = "drawH"
        private const val KEY_STEPS = "drawSteps"
        private const val KEY_CFG = "drawCfg"
        private const val KEY_SEED = "drawSeed"
        private const val KEY_LORA_SCALE = "drawLoraScale"
        private const val KEY_SAMPLER = "drawSampler"
        private const val KEY_SCHEDULER = "drawScheduler"

        // 图生图参数（与文生图分开存）
        private const val KEY_I2I_W = "i2iW"
        private const val KEY_I2I_H = "i2iH"
        private const val KEY_I2I_STEPS = "i2iSteps"
        private const val KEY_I2I_CFG = "i2iCfg"
        private const val KEY_I2I_SEED = "i2iSeed"
        private const val KEY_I2I_STRENGTH = "i2iStrength"
        private const val KEY_I2I_LORA_SCALE = "i2iLoraScale"
        private const val KEY_I2I_SAMPLER = "i2iSampler"
        private const val KEY_I2I_SCHEDULER = "i2iScheduler"

        // 打标（Tagger）
        private const val KEY_TAG_THRESHOLD = "tagThreshold"
        private const val KEY_TAG_TOPK = "tagTopK"
        private const val KEY_TAG_CHAN = "tagChan"   // 0 = BGR（WD 系默认），1 = RGB
    }

    private val c: Context get() = act

    /** 自编 sd.cpp 的上下文句柄（0 = 未加载） */
    private var sdHandle: Long = 0L
    private var mainModel: File? = null
    private var vaeModel: File? = null

    /** 推理线程数：默认 4（多数手机的大核数；开太多会跑到小核上，反而变慢） */
    private var nThreads: Int = 4

    /** FlashAttention（CLIP + UNet）。实测不开每步慢约 28%（6 步 125s vs 98s）。 */
    private var flashAttn: Boolean = true

    /** 外部（MainActivity）通知：当前已加载语言模型。用于「生成」按钮给出更准确的提示。 */
    var llmLoaded: Boolean = false

    /** 状态回调：把绘图页的进度/结果同步到主界面顶栏。(文本, 是否出错) */
    var onStatus: ((String, Boolean) -> Unit)? = null

    /** 运行方式：true = 尝试 GPU（Vulkan），false = 纯 CPU。由模型页的「运行方式」决定。 */
    var useGpu: Boolean = false

    /** 当前绘图实际用的后端（"CPU" / "Vulkan0"），显示在状态行 */
    private var activeBackend: String = "CPU"

    /**
     * 是否启用 LoRA 加速（挂 LCM-LoRA，压低步数）。
     * 状态持久化到 SharedPreferences —— 否则每次启动 App 都要重新勾。
     */
    var useLora: Boolean = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_LORA, false)
        set(value) {
            field = value
            c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_LORA, value).apply()
        }

    /** 当前选中的 LoRA（null = 自动取目录里第一个） */
    private var loraFile: File? = null

    /** SD 管线就绪时回调（MainActivity 借此切到绘图模式） */
    var onPipelineReady: (() -> Unit)? = null

    /** 是否已就绪（可生成） */
    fun isReady(): Boolean = sdHandle != 0L

    // 控件
    private lateinit var statusText: TextView
    private lateinit var quantText: TextView
    private lateinit var modelCardTitle: TextView
    private lateinit var promptEdit: EditText
    private lateinit var negEdit: EditText
    private lateinit var stepsEdit: EditText
    private lateinit var cfgEdit: EditText
    private lateinit var seedEdit: EditText
    private lateinit var loraCheck: CheckBox
    private lateinit var loraHint: TextView
    private lateinit var genBtn: Button

    /** 是否正在生成（同一个按钮在「开始生成 / 中断生成」之间切换） */
    private var generating = false
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    // 宽 / 高（可自由填，sd.cpp 要求 64 的倍数）
    private lateinit var widthEdit: EditText
    private lateinit var heightEdit: EditText

    // 采样器 / 调度器（第 0 项 = 自动）
    private lateinit var samplerSpinner: android.widget.Spinner
    private lateinit var schedulerSpinner: android.widget.Spinner

    /** LoRA 权重 */
    private lateinit var loraScaleEdit: EditText

    // 顶部菜单：参数 / 结果
    private lateinit var tabParams: TextView
    private lateinit var tabResult: TextView
    private lateinit var paramPane: LinearLayout
    private lateinit var resultPane: LinearLayout
    private lateinit var pager: ViewPager

    // 结果面板
    private lateinit var resultImg: ImageView
    private lateinit var resultInfo: TextView
    private lateinit var saveBtn: Button
    private lateinit var historyRow: LinearLayout
    private lateinit var histEmpty: TextView

    /** 最近一次生成的图（结果页「保存到相册」用） */
    private var lastImage: Bitmap? = null

    // ---- 模式：文生图 / 图生图 ----
    private lateinit var tabT2i: TextView
    private lateinit var tabI2i: TextView
    private lateinit var t2iContent: LinearLayout
    private lateinit var i2iContent: LinearLayout

    /** 当前参数页模式：0=文生图 1=图生图 2=Tagger */
    private var mode = 0

    // ---- 图生图专属控件 ----
    private lateinit var i2iPromptEdit: EditText
    private lateinit var i2iNegEdit: EditText
    private lateinit var i2iWidthEdit: EditText
    private lateinit var i2iHeightEdit: EditText
    private lateinit var i2iStepsEdit: EditText
    private lateinit var i2iCfgEdit: EditText
    private lateinit var i2iSeedEdit: EditText
    private lateinit var i2iStrengthEdit: EditText
    private lateinit var i2iSamplerSpinner: android.widget.Spinner
    private lateinit var i2iSchedulerSpinner: android.widget.Spinner
    private lateinit var i2iLoraCheck: CheckBox
    private lateinit var i2iLoraScaleEdit: EditText
    private lateinit var i2iLoraHint: TextView
    private lateinit var i2iGenBtn: Button
    private lateinit var i2iProgressText: TextView
    private lateinit var i2iProgressBar: ProgressBar
    private lateinit var i2iPreview: ImageView
    private lateinit var i2iPickBtn: Button
    private lateinit var i2iImgHint: TextView

    /** 图生图选中的参考图（仅内存，进程结束即失效） */
    private var i2iBitmap: Bitmap? = null

    // ---- Tagger 专属控件 ----
    private lateinit var tabTagger: TextView
    private lateinit var taggerContent: LinearLayout
    private lateinit var taggerPreview: ImageView
    private lateinit var taggerPickBtn: Button
    private lateinit var taggerImgHint: TextView
    private lateinit var taggerRunBtn: Button
    private lateinit var taggerProgressText: TextView
    private lateinit var taggerProgressBar: ProgressBar
    private lateinit var taggerThresholdEdit: EditText
    private lateinit var taggerTopKEdit: EditText
    private lateinit var taggerChanSpinner: android.widget.Spinner

    /** 参数 / 结果两个面板的 ScrollView（跳转后用来回到顶部） */
    private lateinit var paramScroll: ScrollView
    private lateinit var resultScroll: ScrollView
    private lateinit var taggerOut: TextView
    private lateinit var taggerSendT2iBtn: Button
    private lateinit var taggerSendI2iBtn: Button

    /** Tagger 选中的输入图（仅内存） */
    private var taggerBitmap: Bitmap? = null

    /** 最近一次打标结果（逗号分隔的 tag 文本） */
    private var taggerTags: String = ""

    /** 结果页的「发送至图生图 / 发送至 Tagger」按钮 */
    private lateinit var resultToI2iBtn: Button
    private lateinit var resultToTaggerBtn: Button

    /** 保存图片到相册：MainActivity 注入实现（复用它的 MediaStore 逻辑）。参数：图 + 文件名 */
    var onSaveImage: ((Bitmap, String) -> Unit)? = null

    fun build(): View {
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F6F8.toInt())
        }

        // ---- 顶部菜单：参数 / 结果（点击或左右翻页都能切） ----
        val tabBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        tabParams = tabItem(c.getString(R.string.s_034))
        tabResult = tabItem(c.getString(R.string.s_203))
        tabParams.setOnClickListener { switchPane(toResult = false) }
        tabResult.setOnClickListener { switchPane(toResult = true) }
        tabBar.addView(tabParams, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(tabResult, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabBar)

        // ---- 参数面板 ----
        // 内容容器；外面会各自包一层 ScrollView，再交给 ViewPager2 做左右翻页
        paramPane = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        // 文生图内容容器：下面的提示词/按钮/参数都先进这里，
        // 再和「图生图」页面一起放进模式容器，由子标签切换。
        t2iContent = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        // ---- 模型状态（模型统一在「模型」页选择并加载） ----
        val modelCard = card()
        modelCardTitle = title(c.getString(R.string.s_164))
        modelCard.addView(modelCardTitle)
        statusText = body(c.getString(R.string.s_115))
        modelCard.addView(statusText)
        quantText = TextView(c).apply {
            textSize = 11.5f
            setTextColor(subText)
            setPadding(0, dp(6), 0, 0)
        }
        modelCard.addView(quantText)
        paramPane.addView(modelCard)

        // ---- 模式子标签：文生图 / 图生图 ----
        val modeBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        tabT2i = modeTab(c.getString(R.string.s_207))
        tabI2i = modeTab(c.getString(R.string.s_208))
        tabTagger = modeTab(c.getString(R.string.s_221))
        tabT2i.setOnClickListener { switchMode(0) }
        tabI2i.setOnClickListener { switchMode(1) }
        tabTagger.setOnClickListener { switchMode(2) }
        modeBar.addView(tabT2i, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeBar.addView(tabI2i, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeBar.addView(tabTagger, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        paramPane.addView(modeBar, matchWrap())

        // ---- 提示词 ----
        val promptCard = card()
        promptCard.addView(title(c.getString(R.string.s_108)))
        promptEdit = labeledEdit(c.getString(R.string.s_036), singleLine = false, minLines = 3)
        promptCard.addView(promptEdit, matchWrap(top = 6))
        promptCard.addView(smallLabel(c.getString(R.string.s_177)))
        negEdit = labeledEdit(c.getString(R.string.s_216), singleLine = false, minLines = 2)
        promptCard.addView(negEdit, matchWrap(top = 4))
        t2iContent.addView(promptCard)

        // ---- 生成 / 中断（同一个按钮），放在提示词与参数之间，方便盯着进度 ----
        genBtn = Button(c).apply {
            text = c.getString(R.string.s_098)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { if (generating) doCancel() else generateFromUi() }
        }
        t2iContent.addView(genBtn, matchWrap(top = 2))

        progressText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        t2iContent.addView(progressText)

        progressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        t2iContent.addView(progressBar, matchWrap(top = 4))

        // ---- 参数 ----
        val paramCard = card()
        paramCard.addView(title(c.getString(R.string.s_065)))

        // 尺寸：宽 × 高，自由填（sd.cpp 要求 64 的倍数）——从上次的值恢复
        widthEdit = smallNumber(loadParam(KEY_W, defOf("t2i", "w", "512")))
        heightEdit = smallNumber(loadParam(KEY_H, defOf("t2i", "h", "512")))
        paramCard.addView(whRow(c.getString(R.string.s_070),
            c.getString(R.string.s_068), widthEdit, heightEdit))

        // 采样器 / 调度器
        samplerSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_169)) + SdCppEngine.Sampler.entries.map { it.label }
        )
        schedulerSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_168)) + SdCppEngine.Scheduler.entries.map { it.label }
        )
        samplerSpinner.setSelection(loadInt(KEY_SAMPLER, defOfInt("t2i", "sampler", 0)).coerceIn(0, samplerSpinner.adapter.count - 1), false)
        schedulerSpinner.setSelection(loadInt(KEY_SCHEDULER, defOfInt("t2i", "scheduler", 0)).coerceIn(0, schedulerSpinner.adapter.count - 1), false)
        // 先恢复再挂监听，免得 setSelection 把默认值又写回去
        samplerSpinner.onItemSelectedListener = persistSpinner(KEY_SAMPLER)
        schedulerSpinner.onItemSelectedListener = persistSpinner(KEY_SCHEDULER)
        paramCard.addView(paramRow(c.getString(R.string.s_191), samplerSpinner, c.getString(R.string.s_015)))
        paramCard.addView(paramRow(c.getString(R.string.s_176), schedulerSpinner, c.getString(R.string.s_014)))

        stepsEdit = smallNumber(loadParam(KEY_STEPS, defOf("t2i", "steps", builtinSteps())))
        cfgEdit = smallNumber(loadParam(KEY_CFG, defOf("t2i", "cfg", builtinCfg())))
        seedEdit = smallNumber(loadParam(KEY_SEED, defOf("t2i", "seed", "-1")))
        bindParam(widthEdit, KEY_W)
        bindParam(heightEdit, KEY_H)
        bindParam(stepsEdit, KEY_STEPS)
        bindParam(cfgEdit, KEY_CFG)
        bindParam(seedEdit, KEY_SEED)
        paramCard.addView(paramRow(c.getString(R.string.s_192), stepsEdit, c.getString(R.string.s_179)))
        paramCard.addView(paramRow(c.getString(R.string.s_013), cfgEdit, c.getString(R.string.s_178)))
        paramCard.addView(paramRow(c.getString(R.string.s_193), seedEdit, c.getString(R.string.s_008)))

        // ---- LoRA 加速开关 ----
        loraCheck = CheckBox(c).apply {
            text = c.getString(R.string.s_022)
            textSize = 13f
            setTextColor(textColor)
            isChecked = useLora
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                useLora = checked
                // 切换 LoRA = 套用该状态的那套默认值（开/关各一套，可用「设为默认值」覆盖）
                if (checked && activeLora() == null) toast(c.getString(R.string.s_186))
                if (::i2iLoraCheck.isInitialized) i2iLoraCheck.isChecked = checked
                refreshLoraHint()
                if (::i2iContent.isInitialized) {
                    applyDefaults("t2i")
                    applyDefaults("i2i")
                }
            }
        }
        paramCard.addView(loraCheck)
        loraScaleEdit = smallNumber(loadParam(KEY_LORA_SCALE, defOf("t2i", "loraScale", "1.0")))
        bindParam(loraScaleEdit, KEY_LORA_SCALE)
        paramCard.addView(paramRow(c.getString(R.string.s_024), loraScaleEdit, c.getString(R.string.s_037)))
        loraHint = TextView(c).apply {
            textSize = 11f
            setTextColor(subText)
            setPadding(0, dp(2), 0, 0)
        }
        paramCard.addView(loraHint)
        t2iContent.addView(paramCard)
        t2iContent.addView(defaultButtonsRow("t2i"), matchWrap(top = 10))

        // ---- 图生图页面 ----
        i2iContent = buildI2iContent()

        // ---- Tagger 页面 ----
        taggerContent = buildTaggerContent()

        // 模式容器：用 visibility 切换，不用嵌套 ViewPager，
        // 否则会和外层「参数/结果」的左右翻页抢手势。
        val paramHost = FrameLayout(c)
        paramHost.addView(t2iContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paramHost.addView(i2iContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paramHost.addView(taggerContent, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paramPane.addView(paramHost, matchWrap())

        // ---- 结果面板 ----
        resultPane = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }

        val resultCard = card()
        resultCard.addView(title(c.getString(R.string.s_148)))
        resultImg = ImageView(c).apply {
            adjustViewBounds = true
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        resultCard.addView(resultImg, matchWrap())
        resultInfo = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            text = c.getString(R.string.s_184)
        }
        resultCard.addView(resultInfo)
        saveBtn = Button(c).apply {
            text = c.getString(R.string.s_045)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                val b = lastImage
                if (b == null) toast(c.getString(R.string.s_183)) else onSaveImage?.invoke(b, saveName())
            }
        }
        resultCard.addView(saveBtn, matchWrap(top = 10))

        // 结果图一键送去「图生图」当参考图，或送去「Tagger」当输入图
        resultToI2iBtn = Button(c).apply {
            text = c.getString(R.string.s_225)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(0xFFEDF1FF.toInt())
            setOnClickListener { sendResultToI2i() }
        }
        resultToTaggerBtn = Button(c).apply {
            text = c.getString(R.string.s_238)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(0xFFEDF1FF.toInt())
            setOnClickListener { sendResultToTagger() }
        }
        val sendRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        sendRow.addView(resultToI2iBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sendRow.addView(resultToTaggerBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) })
        resultCard.addView(sendRow, matchWrap(top = 8))
        resultPane.addView(resultCard)

        // ---- 生成历史（只活在内存里，App 进程结束即清空） ----
        val histCard = card()
        histCard.addView(title(c.getString(R.string.s_146)))
        histCard.addView(TextView(c).apply {
            text = c.getString(R.string.s_067)
            textSize = 11f
            setTextColor(subText)
            setPadding(0, dp(4), 0, 0)
        })
        histEmpty = TextView(c).apply {
            text = c.getString(R.string.s_182)
            textSize = 12f
            setTextColor(subText)
            setPadding(0, dp(10), 0, 0)
        }
        histCard.addView(histEmpty)
        historyRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val hsv = android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(historyRow)
        }
        histCard.addView(hsv, matchWrap(top = 8))
        val clearHistBtn = Button(c).apply {
            text = c.getString(R.string.s_138)
            textSize = 12f
            setTextColor(subText)
            setOnClickListener {
                if (DrawHistory.size() == 0) {
                    toast(c.getString(R.string.s_064))
                } else {
                    android.app.AlertDialog.Builder(act)
                        .setMessage(c.getString(R.string.s_137))
                        .setPositiveButton(c.getString(R.string.s_136)) { _, _ -> DrawHistory.clear(); refreshHistory() }
                        .setNegativeButton(c.getString(R.string.s_066), null)
                        .show()
                }
            }
        }
        histCard.addView(clearHistBtn, matchWrap(top = 10))
        resultPane.addView(histCard)
        refreshHistory()

        // ---- 参数 / 结果：ViewPager 跟手翻页（同手机桌面） ----
        // 用老版 ViewPager 而不是 ViewPager2：它的 PagerAdapter 原生支持直接复用
        // 已有的 View；ViewPager2 内部是 RecyclerView，重复 attach 同一个 View 会崩。
        pager = ViewPager(c).apply {
            paramScroll = scrollWrap(paramPane)
            resultScroll = scrollWrap(resultPane)
            adapter = PaneAdapter(listOf(paramScroll, resultScroll))
            addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    highlightTabs(toResult = position == 1)
                }
            })
        }
        root.addView(pager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshLoraHint()
        refreshQuantText()
        switchMode(0)
        switchPane(toResult = false)
        return root
    }

    // ================= 图生图页面 =================

    /** 构建「图生图」参数页（独立的一套控件，持久化键也独立） */
    private fun buildI2iContent(): LinearLayout {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        // ---- 参考图 ----
        val imgCard = card()
        imgCard.addView(title(c.getString(R.string.s_209)))
        i2iPreview = ImageView(c).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFF2F3F5.toInt())
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        imgCard.addView(i2iPreview, matchWrap(top = 8))
        i2iPickBtn = Button(c).apply {
            text = c.getString(R.string.s_210)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { pickImage() }
        }
        imgCard.addView(i2iPickBtn, matchWrap(top = 10))
        i2iImgHint = TextView(c).apply {
            text = c.getString(R.string.s_211)
            textSize = 11.5f
            setTextColor(subText)
            setPadding(0, dp(6), 0, 0)
        }
        imgCard.addView(i2iImgHint)
        box.addView(imgCard)

        // ---- 提示词 ----
        val promptCard = card()
        promptCard.addView(title(c.getString(R.string.s_108)))
        i2iPromptEdit = labeledEdit(c.getString(R.string.s_036), singleLine = false, minLines = 3)
        promptCard.addView(i2iPromptEdit, matchWrap(top = 6))
        promptCard.addView(smallLabel(c.getString(R.string.s_177)))
        i2iNegEdit = labeledEdit(c.getString(R.string.s_216), singleLine = false, minLines = 2)
        promptCard.addView(i2iNegEdit, matchWrap(top = 4))
        box.addView(promptCard)

        // ---- 生成 / 中断 ----
        i2iGenBtn = Button(c).apply {
            text = c.getString(R.string.s_098)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { if (generating) doCancel() else generateFromUiI2i() }
        }
        box.addView(i2iGenBtn, matchWrap(top = 2))

        i2iProgressText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(i2iProgressText)
        i2iProgressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        box.addView(i2iProgressBar, matchWrap(top = 4))

        // ---- 参数 ----
        val paramCard = card()
        paramCard.addView(title(c.getString(R.string.s_065)))

        // 重绘强度：越小越接近原图
        i2iStrengthEdit = smallNumber(loadParam(KEY_I2I_STRENGTH, defOf("i2i", "strength", "0.75")))
        bindParam(i2iStrengthEdit, KEY_I2I_STRENGTH)
        paramCard.addView(paramRow(c.getString(R.string.s_212), i2iStrengthEdit, c.getString(R.string.s_213)))

        // 尺寸：默认选图时会同步成参考图尺寸（64 倍数）
        i2iWidthEdit = smallNumber(loadParam(KEY_I2I_W, defOf("i2i", "w", "512")))
        i2iHeightEdit = smallNumber(loadParam(KEY_I2I_H, defOf("i2i", "h", "512")))
        bindParam(i2iWidthEdit, KEY_I2I_W)
        bindParam(i2iHeightEdit, KEY_I2I_H)
        paramCard.addView(whRow(c.getString(R.string.s_070), c.getString(R.string.s_068), i2iWidthEdit, i2iHeightEdit))

        i2iSamplerSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_169)) + SdCppEngine.Sampler.entries.map { it.label }
        )
        i2iSchedulerSpinner = choiceSpinner(
            listOf(c.getString(R.string.s_168)) + SdCppEngine.Scheduler.entries.map { it.label }
        )
        i2iSamplerSpinner.setSelection(loadInt(KEY_I2I_SAMPLER, defOfInt("i2i", "sampler", 0)).coerceIn(0, i2iSamplerSpinner.adapter.count - 1), false)
        i2iSchedulerSpinner.setSelection(loadInt(KEY_I2I_SCHEDULER, defOfInt("i2i", "scheduler", 0)).coerceIn(0, i2iSchedulerSpinner.adapter.count - 1), false)
        i2iSamplerSpinner.onItemSelectedListener = persistSpinner(KEY_I2I_SAMPLER)
        i2iSchedulerSpinner.onItemSelectedListener = persistSpinner(KEY_I2I_SCHEDULER)
        paramCard.addView(paramRow(c.getString(R.string.s_191), i2iSamplerSpinner, c.getString(R.string.s_015)))
        paramCard.addView(paramRow(c.getString(R.string.s_176), i2iSchedulerSpinner, c.getString(R.string.s_014)))

        i2iStepsEdit = smallNumber(loadParam(KEY_I2I_STEPS, defOf("i2i", "steps", builtinSteps())))
        i2iCfgEdit = smallNumber(loadParam(KEY_I2I_CFG, defOf("i2i", "cfg", builtinCfg())))
        i2iSeedEdit = smallNumber(loadParam(KEY_I2I_SEED, defOf("i2i", "seed", "-1")))
        bindParam(i2iStepsEdit, KEY_I2I_STEPS)
        bindParam(i2iCfgEdit, KEY_I2I_CFG)
        bindParam(i2iSeedEdit, KEY_I2I_SEED)
        paramCard.addView(paramRow(c.getString(R.string.s_192), i2iStepsEdit, c.getString(R.string.s_179)))
        paramCard.addView(paramRow(c.getString(R.string.s_013), i2iCfgEdit, c.getString(R.string.s_178)))
        paramCard.addView(paramRow(c.getString(R.string.s_193), i2iSeedEdit, c.getString(R.string.s_008)))

        // LoRA（与文生图共用同一个开关，两边 checkbox 相互同步）
        i2iLoraCheck = CheckBox(c).apply {
            text = c.getString(R.string.s_022)
            textSize = 13f
            setTextColor(textColor)
            isChecked = useLora
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                useLora = checked
                if (checked && activeLora() == null) toast(c.getString(R.string.s_186))
                if (::loraCheck.isInitialized) loraCheck.isChecked = checked
                refreshLoraHint()
                if (::t2iContent.isInitialized) {
                    applyDefaults("t2i")
                    applyDefaults("i2i")
                }
            }
        }
        paramCard.addView(i2iLoraCheck)
        i2iLoraScaleEdit = smallNumber(loadParam(KEY_I2I_LORA_SCALE, defOf("i2i", "loraScale", "1.0")))
        bindParam(i2iLoraScaleEdit, KEY_I2I_LORA_SCALE)
        paramCard.addView(paramRow(c.getString(R.string.s_024), i2iLoraScaleEdit, c.getString(R.string.s_037)))
        i2iLoraHint = TextView(c).apply {
            textSize = 11f
            setTextColor(subText)
            setPadding(0, dp(2), 0, 0)
        }
        paramCard.addView(i2iLoraHint)
        paramCard.addView(defaultButtonsRow("i2i"), matchWrap(top = 10))
        box.addView(paramCard)

        return box
    }

    /** 切换参数页：0=文生图 1=图生图 2=Tagger */
    private fun switchMode(m: Int) {
        if (!::t2iContent.isInitialized) return
        mode = m
        t2iContent.visibility = if (m == 0) View.VISIBLE else View.GONE
        i2iContent.visibility = if (m == 1) View.VISIBLE else View.GONE
        if (::taggerContent.isInitialized) {
            taggerContent.visibility = if (m == 2) View.VISIBLE else View.GONE
        }
        for ((tv, sel) in listOf(
            tabT2i to (m == 0), tabI2i to (m == 1), tabTagger to (m == 2)
        )) {
            tv.setTextColor(if (sel) primary else subText)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) 0xFFEDF1FF.toInt() else Color.WHITE)
        }
        refreshModelCard()
    }

    /** 模式子标签样式（比顶部页签略小） */
    private fun modeTab(t: String) = TextView(c).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, dp(9), 0, dp(9))
    }

    // ================= Tagger 页面 =================

    /** 构建「Tagger」参数页：选图 → 打标 → 结果可发送到文生图/图生图 */
    private fun buildTaggerContent(): LinearLayout {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        // ---- 输入图 ----
        val imgCard = card()
        imgCard.addView(title(c.getString(R.string.s_209)))
        taggerPreview = ImageView(c).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFF2F3F5.toInt())
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        imgCard.addView(taggerPreview, matchWrap(top = 8))
        taggerPickBtn = Button(c).apply {
            text = c.getString(R.string.s_210)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { pickTaggerImage() }
        }
        imgCard.addView(taggerPickBtn, matchWrap(top = 10))
        taggerImgHint = TextView(c).apply {
            text = c.getString(R.string.s_211)
            textSize = 11.5f
            setTextColor(subText)
            setPadding(0, dp(6), 0, 0)
        }
        imgCard.addView(taggerImgHint)
        box.addView(imgCard)

        // ---- 参数：阈值 / 最多标签数 ----
        val paramCard = card()
        paramCard.addView(title(c.getString(R.string.s_065)))
        taggerThresholdEdit = smallNumber(loadParam(KEY_TAG_THRESHOLD, prefs().getString("def_tag_threshold", null) ?: "0.35"))
        bindParam(taggerThresholdEdit, KEY_TAG_THRESHOLD)
        paramCard.addView(paramRow(c.getString(R.string.s_226), taggerThresholdEdit, "0 ~ 1"))
        taggerTopKEdit = smallNumber(loadParam(KEY_TAG_TOPK, prefs().getString("def_tag_topk", null) ?: "40"))
        bindParam(taggerTopKEdit, KEY_TAG_TOPK)
        paramCard.addView(paramRow(c.getString(R.string.s_227), taggerTopKEdit, "1 ~ 300"))
        taggerChanSpinner = choiceSpinner(listOf("BGR", "RGB"))
        taggerChanSpinner.setSelection(loadInt(KEY_TAG_CHAN, 0).coerceIn(0, 1), false)
        taggerChanSpinner.onItemSelectedListener = persistSpinner(KEY_TAG_CHAN)
        paramCard.addView(paramRow(c.getString(R.string.s_260), taggerChanSpinner, c.getString(R.string.s_261)))
        paramCard.addView(defaultButtonsRow("tag"), matchWrap(top = 10))
        box.addView(paramCard)

        // ---- 开始打标 ----
        taggerRunBtn = Button(c).apply {
            text = c.getString(R.string.s_222)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { runTagger() }
        }
        box.addView(taggerRunBtn, matchWrap(top = 2))

        taggerProgressText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(taggerProgressText)
        taggerProgressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        box.addView(taggerProgressBar, matchWrap(top = 4))

        // ---- 结果 ----
        val outCard = card()
        outCard.addView(title(c.getString(R.string.s_223)))
        taggerOut = TextView(c).apply {
            textSize = 13f
            setTextColor(textColor)
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, 0)
        }
        outCard.addView(taggerOut)
        taggerSendT2iBtn = Button(c).apply {
            text = c.getString(R.string.s_224)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(0xFFEDF1FF.toInt())
            setOnClickListener { sendTags(0) }
        }
        taggerSendI2iBtn = Button(c).apply {
            text = c.getString(R.string.s_225)
            textSize = 13f
            setTextColor(primary)
            setBackgroundColor(0xFFEDF1FF.toInt())
            setOnClickListener { sendTags(1) }
        }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(taggerSendT2iBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(taggerSendI2iBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) })
        outCard.addView(row, matchWrap(top = 10))
        box.addView(outCard)

        return box
    }

    /** 从相册给 Tagger 选输入图 */
    private fun pickTaggerImage() {
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        act.startActivityForResult(i, REQ_TAGGER_IMAGE)
    }

    /** 把某张图设为 Tagger 的输入图 */
    private fun applyTaggerImage(bmp: Bitmap) {
        taggerBitmap = bmp
        taggerPreview.setImageBitmap(bmp)
        taggerPreview.visibility = View.VISIBLE
        taggerImgHint.text = c.getString(R.string.v_061, bmp.width, bmp.height)
    }

    /** 执行打标（IO 线程推理，回主线程更新 UI） */
    private fun runTagger() {
        val bmp = taggerBitmap
        if (bmp == null) {
            toast(c.getString(R.string.s_214))
            return
        }
        val threshold = taggerThresholdEdit.text.toString().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.35f
        val topK = taggerTopKEdit.text.toString().toIntOrNull()?.coerceIn(1, 300) ?: 40
        taggerRunBtn.isEnabled = false
        taggerProgressText.visibility = View.VISIBLE
        taggerProgressText.text = c.getString(R.string.s_229)
        taggerProgressBar.visibility = View.VISIBLE
        taggerProgressBar.isIndeterminate = true
        scope.launch {
            var err: String? = null
            var list: List<Pair<String, Float>>? = null
            withContext(Dispatchers.IO) {
                err = taggerReadyError()
                if (err == null) {
                    list = try {
                        TaggerEngine.run(bmp, threshold, topK, rgbOrder = taggerChanSpinner.selectedItemPosition == 1)
                    } catch (t: Throwable) {
                        err = t.message ?: t.javaClass.simpleName
                        null
                    }
                }
            }
            taggerRunBtn.isEnabled = true
            taggerProgressBar.visibility = View.GONE
            taggerProgressText.visibility = View.GONE
            when {
                err != null -> toast(c.getString(R.string.s_236, err))
                list.isNullOrEmpty() -> {
                    taggerTags = ""
                    taggerOut.text = c.getString(R.string.s_230)
                    toast(c.getString(R.string.s_230))
                }
                else -> {
                    val text = list!!.joinToString(", ") { it.first.replace('_', ' ') }
                    taggerTags = text
                    taggerOut.text = text
                    toast(c.getString(R.string.s_237, list!!.size))
                }
            }
        }
    }

    /** 按需加载打标模型（模型名 / 标签表 / 后端变了就重载）；返回 null = 就绪 */
    private fun ensureTaggerLoaded(): String? {
        val model = taggerModelFile() ?: return c.getString(R.string.s_228)
        val csv = taggerCsvFile() ?: return c.getString(R.string.s_228)
        val (mName, cName) = TaggerEngine.fileNames()
        if (TaggerEngine.isLoaded() && mName == model.name && cName == csv.name &&
            TaggerEngine.loadedWithGpu() == useGpu) return null
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        return TaggerEngine.load(model, csv, useGpu, threads)
    }

    /**
     * 手动模式：不自动加载。已加载且模型/标签表/后端都与当前选择一致时才放行，
     * 否则提示用户到「模型」页点「加载打标模型」。
     */
    private fun taggerReadyError(): String? {
        val model = taggerModelFile() ?: return c.getString(R.string.s_228)
        val csv = taggerCsvFile() ?: return c.getString(R.string.s_228)
        if (!TaggerEngine.isLoaded()) return c.getString(R.string.s_259)
        val (mName, cName) = TaggerEngine.fileNames()
        if (mName != model.name || cName != csv.name || TaggerEngine.loadedWithGpu() != useGpu)
            return c.getString(R.string.s_259)
        return null
    }

    /** 把打标结果发送到目标页（0=文生图 1=图生图），发送前弹确认 */
    private fun sendTags(target: Int) {
        val text = taggerTags.ifBlank { taggerOut.text?.toString().orEmpty() }
        if (text.isBlank()) {
            toast(c.getString(R.string.s_230))
            return
        }
        val pageName = c.getString(if (target == 0) R.string.s_207 else R.string.s_208)
        android.app.AlertDialog.Builder(act)
            .setTitle(c.getString(R.string.s_233))
            .setMessage(c.getString(R.string.s_234, pageName))
            .setPositiveButton(c.getString(R.string.s_235)) { _, _ ->
                if (target == 0) {
                    promptEdit.setText(text)
                    switchMode(0)
                    scrollTop(paramScroll)
                    toast(c.getString(R.string.s_231))
                } else {
                    i2iPromptEdit.setText(text)
                    switchMode(1)
                    scrollTop(paramScroll)
                    toast(c.getString(R.string.s_232))
                }
            }
            .setNegativeButton(c.getString(R.string.s_049), null)
            .show()
    }

    /** 结果图 → 图生图参考图（并切换过去） */
    private fun sendResultToI2i() {
        val b = lastImage
        if (b == null) {
            toast(c.getString(R.string.s_183))
            return
        }
        applyPickedImage(b)
        switchMode(1)
        switchPane(toResult = false)
        scrollTop(paramScroll)
        toast(c.getString(R.string.s_239))
    }

    /** 结果图 → Tagger 输入图（并切换过去） */
    private fun sendResultToTagger() {
        val b = lastImage
        if (b == null) {
            toast(c.getString(R.string.s_183))
            return
        }
        applyTaggerImage(b)
        switchMode(2)
        switchPane(toResult = false)
        scrollTop(paramScroll)
        toast(c.getString(R.string.s_240))
    }

    /** 外部图片 → 图生图参考图（并切到图生图页）。供对话里的图片长按菜单调用。 */
    fun sendImageToI2i(b: Bitmap) {
        applyPickedImage(b)
        switchMode(1)
        switchPane(toResult = false)
        scrollTop(paramScroll)
        toast(c.getString(R.string.s_239))
    }

    /** 外部图片 → Tagger 输入图（并切到 Tagger 页）。供对话里的图片长按菜单调用。 */
    fun sendImageToTagger(b: Bitmap) {
        applyTaggerImage(b)
        switchMode(2)
        switchPane(toResult = false)
        scrollTop(paramScroll)
        toast(c.getString(R.string.s_240))
    }

    // ---- 打标模型文件（模型页导入，这里只读） ----

    /** 打标目录：filesDir/tagger */
    private fun taggerDir(): File = File(c.filesDir, TAGGER_DIR).apply { mkdirs() }

    /** 已导入的打标模型（.onnx），按名字排序 */
    fun listTaggerModels(): List<File> =
        taggerDir().listFiles { f -> f.isFile && f.name.endsWith(".onnx", true) }?.sortedBy { it.name } ?: emptyList()

    /** 已导入的标签表（.csv） */
    fun listTaggerCsvs(): List<File> =
        taggerDir().listFiles { f -> f.isFile && f.name.endsWith(".csv", true) }?.sortedBy { it.name } ?: emptyList()

    /** 当前选中的打标模型（优先上次选择的，否则第一个） */
    fun taggerModelFile(): File? = pickSelected(listTaggerModels(), "selTaggerModel")

    /** 当前选中的标签表 */
    fun taggerCsvFile(): File? = pickSelected(listTaggerCsvs(), "selTaggerCsv")

    private fun pickSelected(all: List<File>, key: String): File? {
        if (all.isEmpty()) return null
        val saved = prefs().getString(key, null)
        return all.firstOrNull { it.name == saved } ?: all.first()
    }

    fun currentTaggerModelName(): String? = taggerModelFile()?.name
    fun currentTaggerCsvName(): String? = taggerCsvFile()?.name

    /** 选择打标模型（换模型就卸载旧的，下次打标时重新加载） */
    fun selectTaggerModel(f: File) {
        prefs().edit().putString("selTaggerModel", f.name).apply()
        TaggerEngine.unload()
    }

    /** 选择标签表 */
    fun selectTaggerCsv(f: File) {
        prefs().edit().putString("selTaggerCsv", f.name).apply()
        TaggerEngine.unload()
    }

    fun taggerLoaded(): Boolean = TaggerEngine.isLoaded()

    /** Tagger 界面选的通道顺序（false = BGR，true = RGB）；对话页借打标看图时沿用这个设置。 */
    fun taggerRgbOrder(): Boolean =
        ::taggerChanSpinner.isInitialized && taggerChanSpinner.selectedItemPosition == 1

    /** 卸载打标模型，释放内存 */
    fun unloadTagger() = TaggerEngine.unload()

    /** 模型页「加载打标模型」：按当前选择的模型/标签表加载，返回 null=成功 */
    suspend fun loadTaggerFromUi(onStage: (String) -> Unit = {}): String? {
        val model = taggerModelFile()
        val csv = taggerCsvFile()
        if (model == null || csv == null) return c.getString(R.string.s_228)
        onStage(c.getString(R.string.s_245))
        return withContext(Dispatchers.IO) { ensureTaggerLoaded() }
    }

    /** 供「模型」页展示的打标状态 */
    fun taggerSummary(): String {
        val m = taggerModelFile() ?: return c.getString(R.string.s_245)
        val csv = taggerCsvFile() ?: return c.getString(R.string.s_245)
        val n = TaggerEngine.parseCsv(csv).size
        return c.getString(R.string.v_062, m.name, csv.name, n)
    }

    /** 导入 ONNX 打标模型（单文件） */
    suspend fun importTaggerModel(
        uri: Uri,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String? = copyToTagger(uri, ".onnx", R.string.s_246, onStage, onProgress)

    /** 导入标签表 CSV（单文件） */
    suspend fun importTaggerCsv(
        uri: Uri,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String? = copyToTagger(uri, ".csv", R.string.s_247, onStage, onProgress)

    private suspend fun copyToTagger(
        uri: Uri,
        ext: String,
        extErr: Int,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri)
            if (name == null || !name.endsWith(ext, ignoreCase = true)) {
                return@withContext c.getString(extErr)
            }
            withContext(Dispatchers.Main) { onStage(c.getString(R.string.v_033, name)) }
            val dest = File(taggerDir(), name)
            val total = runCatching {
                c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            withContext(Dispatchers.Main) { onProgress?.invoke(0L, total) }
            c.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var tick = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (done - tick >= (1L shl 20)) {
                            tick = done
                            val d = done
                            withContext(Dispatchers.Main) { onProgress?.invoke(d, total) }
                        }
                    }
                }
            } ?: return@withContext c.getString(R.string.s_106)
            TaggerEngine.unload() // 文件变了，下次重新加载
            null
        } catch (e: Throwable) {
            c.getString(R.string.v_038, (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 从相册/文件选择参考图 */
    private fun pickImage() {
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        act.startActivityForResult(i, REQ_IMAGE)
    }

    /** 解码选中的图片；超过 2048 像素则按比例下采样，避免 OOM */
    private fun decodePickedImage(uri: Uri): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > 2048) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        c.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    /** 选定参考图后的处理：预览 + 同步输出尺寸 */
    private fun applyPickedImage(bmp: Bitmap) {
        i2iBitmap = bmp
        i2iPreview.setImageBitmap(bmp)
        i2iPreview.visibility = View.VISIBLE
        val w = ((bmp.width / 64) * 64).coerceIn(64, 2048)
        val h = ((bmp.height / 64) * 64).coerceIn(64, 2048)
        i2iWidthEdit.setText(w.toString())
        i2iHeightEdit.setText(h.toString())
        i2iImgHint.text = c.getString(R.string.v_061, bmp.width, bmp.height)
    }

    private fun refreshI2iLoraHint() {
        if (!::i2iLoraHint.isInitialized) return
        try {
            val a = activeLora()
            i2iLoraHint.text = when {
                a == null -> c.getString(R.string.s_118)
                useLora -> c.getString(R.string.v_028, (a.nameWithoutExtension))
                else -> c.getString(R.string.v_029, (a.nameWithoutExtension))
            }
        } catch (_: Throwable) {}
    }

    // ================= LoRA =================

    /** LoRA 目录：filesDir/draw/lora */
    private fun loraDir(): File = File(File(c.filesDir, DIR_NAME), "lora").apply { mkdirs() }

    /** 已下载的 LoRA 列表（.safetensors） */
    fun listLoras(): List<File> =
        loraDir().listFiles { f -> f.isFile && f.name.endsWith(".safetensors", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()

    fun hasLora(): Boolean = listLoras().isNotEmpty()

    /** 当前生效的 LoRA（优先用户选中的，其次上次选中的，最后取第一个） */
    fun activeLora(): File? {
        val all = listLoras()
        if (all.isEmpty()) return null
        val sel = loraFile
        if (sel != null && sel.isFile && all.any { it.absolutePath == sel.absolutePath }) return sel
        val saved = prefs().getString("selLora", null)
        if (saved != null) all.firstOrNull { it.name == saved }?.let { loraFile = it; return it }
        return all.first()
    }

    /** UI 高亮用：当前生效的 LoRA 名 */
    fun currentLoraName(): String? = activeLora()?.name

    /** 选中某个 LoRA（持久化选择） */
    fun selectLora(f: File) {
        loraFile = f
        prefs().edit().putString("selLora", f.name).apply()
        refreshLoraHint()
    }

    /** 供「模型」页展示的 LoRA 状态 */
    fun loraSummary(): String {
        val a = activeLora() ?: return c.getString(R.string.s_117)
        val size = "%.1f".format(a.length() / 1048576.0)
        return if (useLora) c.getString(R.string.v_026, (a.nameWithoutExtension), (size))
        else c.getString(R.string.v_027, (a.nameWithoutExtension), (size))
    }

    fun deleteLora(f: File): Boolean = runCatching {
        if (loraFile?.absolutePath == f.absolutePath) loraFile = null
        val ok = f.delete()
        refreshLoraHint()
        ok
    }.getOrDefault(false)

    private fun refreshLoraHint() {
        try {
            val a = activeLora()
            loraHint.text = when {
                a == null -> c.getString(R.string.s_118)
                useLora -> c.getString(R.string.v_028, (a.nameWithoutExtension))
                else -> c.getString(R.string.v_029, (a.nameWithoutExtension))
            }
        } catch (_: Throwable) {}
        refreshI2iLoraHint()
    }

    /**
     * 下载 LCM-LoRA。useMirror=true 走 hf-mirror.com（国内可用）。
     * 返回 null 表示成功；否则为错误文案。onProgress(已下载字节, 总字节[未知为 -1])
     */
    suspend fun downloadLora(useMirror: Boolean, onProgress: (Long, Long) -> Unit): String? {
        val host = if (useMirror) "https://hf-mirror.com" else "https://huggingface.co"
        val dest = File(loraDir(), "$LORA_NAME.safetensors")
        val err = downloadToFile("$host/$LORA_HF_PATH", dest, 100L * 1024, onProgress)
        if (err == null) {
            loraFile = dest
            refreshLoraHint()
        }
        return err
    }

    /** 通用下载：边下边写 .part，完成后改名为目标文件。返回 null = 成功。 */
    private suspend fun downloadToFile(
        url: String,
        dest: File,
        minBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Ponko/1.0 (Android)")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) return@withContext c.getString(R.string.v_030, (code))
            val total = conn.contentLengthLong
            conn.inputStream.use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastTick = 0L
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        outs.write(buf, 0, r)
                        done += r
                        if (done - lastTick > (1 shl 20)) {
                            lastTick = done
                            onProgress(done, total)
                        }
                    }
                    outs.flush()
                }
            }
            if (tmp.length() < minBytes) {
                runCatching { tmp.delete() }
                return@withContext c.getString(R.string.v_031, (tmp.length()))
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                runCatching { tmp.delete() }
            }
            onProgress(dest.length(), dest.length())
            null
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            c.getString(R.string.v_032, (e.message ?: e.javaClass.simpleName))
        }
    }

    // ================= 模型（由「模型」页驱动） =================

    /** 从单文件导入绘图模型（.gguf）到私有目录。返回 null 表示成功，否则为错误文案。
     *
     *  设计决定：Ponko 不支持多文件模型，所以逐个选文件导入（不再整目录导入）。 */
    suspend fun prepareFromFile(
        uri: Uri,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri)
            if (name == null || !name.endsWith(".gguf", ignoreCase = true)) {
                return@withContext c.getString(R.string.s_217)
            }
            withContext(Dispatchers.Main) { onStage(c.getString(R.string.v_033, name)) }
            val root = File(c.filesDir, DIR_NAME).apply { mkdirs() }
            val dest = File(root, name)
            val total = runCatching {
                c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            withContext(Dispatchers.Main) { onProgress?.invoke(0L, total) }
            c.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var tick = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (done - tick >= (1L shl 20)) {
                            tick = done
                            val d = done
                            withContext(Dispatchers.Main) { onProgress?.invoke(d, total) }
                        }
                    }
                    val d = done
                    withContext(Dispatchers.Main) { onProgress?.invoke(d, total) }
                }
            } ?: return@withContext c.getString(R.string.s_106)
            statusText.post {
                statusText.text = c.getString(R.string.s_161) + c.getString(R.string.s_027)
            }
            null
        } catch (e: Throwable) {
            c.getString(R.string.v_038, (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 从单文件导入本地 LoRA（.safetensors）。返回 null 表示成功。 */
    suspend fun importLoraFile(
        uri: Uri,
        onStage: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri)
            if (name == null || !name.endsWith(".safetensors", ignoreCase = true)) {
                return@withContext c.getString(R.string.s_218)
            }
            withContext(Dispatchers.Main) { onStage(c.getString(R.string.v_034, name)) }
            val dest = File(loraDir(), name)
            val total = runCatching {
                c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            withContext(Dispatchers.Main) { onProgress?.invoke(0L, total) }
            c.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var tick = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (done - tick >= (1L shl 20)) {
                            tick = done
                            val d = done
                            withContext(Dispatchers.Main) { onProgress?.invoke(d, total) }
                        }
                    }
                }
            } ?: return@withContext c.getString(R.string.s_106)
            withContext(Dispatchers.Main) {
                loraFile = dest
                refreshLoraHint()
            }
            null
        } catch (e: Throwable) {
            c.getString(R.string.v_038, (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 从 content:// URI 取显示文件名 */
    private fun queryDisplayName(uri: Uri): String? =
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
        }

    /** 扫描私有目录里已复制的 gguf 并加载（main=null 时自动挑体积最大的） */
    suspend fun loadExisting(main: File? = null): String? = withContext(Dispatchers.IO) {
        try {
            loadFromPrivateDir(main)
        } catch (e: Throwable) {
            runCatching {
                File(File(c.filesDir, DIR_NAME), ".loadstage")
                    .appendText(c.getString(R.string.v_039, (e.javaClass.name), (e.message)) +
                        android.util.Log.getStackTraceString(e) + "\n")
            }
            c.getString(R.string.v_040, (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 私有目录里已复制的绘图模型文件（.gguf） */
    fun listModels(): List<File> =
        File(c.filesDir, DIR_NAME)
            .listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 当前作为主模型的文件名（未指定时取体积最大的） */
    fun currentMainName(): String? = mainModel?.name

    /** 某个 gguf 的量化等级（Q4_0 / Q8_0 / …），读不出来返回 null */
    fun quantOf(f: File): String? = GgufProbe.quantType(f)

    /** 删除单个已复制的模型文件 */
    fun deleteModel(f: File): Boolean = runCatching { f.delete() }.getOrDefault(false)

    private suspend fun loadFromPrivateDir(preferred: File? = null): String? {
        val root = File(c.filesDir, DIR_NAME)
        val ggufs = root.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }?.toList().orEmpty()
        if (ggufs.isEmpty()) {
            statusText.post { statusText.text = c.getString(R.string.s_114) }
            return c.getString(R.string.s_114)
        }

        // 主模型：优先用指定的，否则取体积最大的 gguf
        val main = preferred?.takeIf { it.isFile } ?: ggufs.maxByOrNull { it.length() }!!
        val others = ggufs.filter { it !== main }
        val vae = others.firstOrNull { it.name.contains("vae", true) }

        mainModel = main
        vaeModel = vae

        // 崩溃追踪：逐步把阶段写进文件。native 段错误会直接杀死进程，
        // 但只要事先写进了磁盘，下次启动就能看到崩在哪一步。
        val stageFile = File(root, ".loadstage")
        fun stage(s: String) {
            runCatching { stageFile.appendText("$s\n") }
        }

        runCatching { stageFile.writeText("") }
        stage(c.getString(R.string.v_056, (main.name), (main.length() / 1048576), (GgufProbe.quantType(main) ?: c.getString(R.string.s_119))))
        if (vae != null) stage(c.getString(R.string.v_057, (vae.name), (vae.length() / 1048576)))
        activeLora()?.let { stage(c.getString(R.string.v_058, (it.name), (it.length() / 1048576))) }
        stage(c.getString(R.string.v_041, (android.os.Build.SUPPORTED_ABIS.firstOrNull()), (android.os.Build.VERSION.SDK_INT), (android.os.Build.MANUFACTURER), (android.os.Build.MODEL)))
        stage(c.getString(R.string.v_042, (Runtime.getRuntime().maxMemory() / 1048576), (root.usableSpace / 1048576)))

        // 分步探测：每步都先落盘，后执行
        stage(c.getString(R.string.s_009))
        try {
            SdCppEngine.info().let { stage("3 native OK：$it") }
        } catch (t: Throwable) {
            stage(c.getString(R.string.v_043, (t.message ?: t.javaClass.simpleName)))
            runCatching { stageFile.delete() }
            return c.getString(R.string.v_044, (t.message ?: t.javaClass.simpleName))
        }

        stage(c.getString(R.string.v_045, (nThreads)))
        return try {
            if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
            sdHandle = 0L
            // 后端：选了 GPU 且设备有 Vulkan 就用它，否则 CPU。
            // 显式传后端名会关掉 sd.cpp 的 auto_fit，避免「选 CPU 却悄悄跑 GPU」。
            val gpuDev = if (useGpu) SdCppEngine.vulkanDevice() else null
            if (useGpu && gpuDev == null) {
                stage("4a no vulkan device -> CPU")
                toast(c.getString(R.string.s_332))
            }
            fun createWith(b: String) = SdCppEngine.create(
                modelPath = main.absolutePath,
                vaePath = vae?.absolutePath,
                nThreads = nThreads,
                wtype = SdCppEngine.WTYPE_KEEP,
                flashAttn = flashAttn,
                backend = b,
            )
            var used = gpuDev ?: "CPU"
            var h = createWith(used)
            if (h == 0L && used != "CPU") {
                // Vulkan 初始化失败（驱动/显存）→ 退回 CPU，别让用户因此用不了绘图
                stage("4b vulkan init failed -> CPU")
                used = "CPU"
                h = createWith(used)
                if (h != 0L) toast(c.getString(R.string.s_333))
            }
            activeBackend = used
            if (h == 0L) {
                stage(c.getString(R.string.s_012))
                runCatching { stage(c.getString(R.string.s_010) + SdCppEngine.lastParams().replace("\n", "  |  ")) }
                runCatching { stageFile.delete() }
                return c.getString(R.string.s_059)
            }
            sdHandle = h
            stage(c.getString(R.string.s_011))
            runCatching {
                stage(c.getString(R.string.s_010) + SdCppEngine.lastParams().replace("\n", "  |  "))
            }

            val q = GgufProbe.quantType(main)
            val summary = buildString {
                append(c.getString(R.string.s_094)).append(main.name)
                if (q != null) append("（").append(q).append("）")
                if (vae != null) append("  +  ").append(vae.name)
                append(" · ").append(activeBackend).append(" · ").append(nThreads).append(c.getString(R.string.s_001)).append(if (flashAttn) "FA" else c.getString(R.string.s_110)).append(" · sd.cpp")
            }
            statusText.post { statusText.text = summary }
            statusText.post { refreshQuantText() }
            runCatching { stageFile.delete() }
            onPipelineReady?.invoke()
            null
        } catch (e: Throwable) {
            stage(c.getString(R.string.v_046, (e.javaClass.name), (e.message)))
            runCatching { stageFile.appendText(android.util.Log.getStackTraceString(e) + "\n") }
            c.getString(R.string.v_047, (e.message ?: e.javaClass.simpleName))
        }
    }

    fun unloadModel() {
        if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
        sdHandle = 0L
        activeBackend = "CPU"
        try {
            statusText.text = c.getString(R.string.s_115)
        } catch (_: Throwable) {}
        refreshQuantText()
    }

    fun hasModel(): Boolean {
        val root = File(c.filesDir, DIR_NAME)
        return root.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }?.isNotEmpty() == true
    }

    fun modelSummary(): String = when {
        sdHandle != 0L -> {
            val q = mainModel?.let { GgufProbe.quantType(it) }
            c.getString(R.string.s_094) + (mainModel?.name ?: c.getString(R.string.s_155)) +
                (if (q != null) "（$q）" else "") +
                " · CPU · " + nThreads + c.getString(R.string.s_002)
        }
        hasModel() -> c.getString(R.string.s_088)
        else -> c.getString(R.string.s_115)
    }

    fun refreshStatus() {
        refreshModelCard()
    }

    /** 顶部模型卡片跟随子页切换：文生图/图生图 显示绘图模型，Tagger 显示打标模型 */
    private fun refreshModelCard() {
        if (!::modelCardTitle.isInitialized) return
        if (mode == 2) {
            modelCardTitle.text = c.getString(R.string.s_262)
            statusText.text = taggerSummary()
            quantText.text = if (taggerLoaded()) {
                c.getString(R.string.s_263) + " · " + TaggerEngine.backendLabel()
            } else {
                c.getString(R.string.s_264)
            }
        } else {
            modelCardTitle.text = c.getString(R.string.s_164)
            try { statusText.text = modelSummary() } catch (_: Throwable) {}
            refreshQuantText()
        }
    }

    /** 当前绘图模型的量化等级（始终可见）。
     *  量化等级是烧在模型文件里的（Q4_0 的 gguf 就是 Q4_0），App 无法凭空转换，只能读出来展示。 */
    private fun refreshQuantText() {
        try {
            val m = mainModel ?: listModels().maxByOrNull { it.length() }
            quantText.text = if (m == null) {
                c.getString(R.string.s_103)
            } else {
                val q = GgufProbe.quantType(m) ?: c.getString(R.string.s_119)
                c.getString(R.string.v_048, (q), (m.name))
            }
        } catch (_: Throwable) {}
    }

    // ================= 生成 =================

    private fun generateFromUi() {
        persistAll()   // 生成前先把参数落盘，保证「改完就生成」也被记住
        if (!isReady()) {
            val msg = if (llmLoaded) c.getString(R.string.s_100)
            else c.getString(R.string.s_173)
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            return
        }
        val prompt = promptEdit.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(c, c.getString(R.string.s_047), Toast.LENGTH_SHORT).show()
            return
        }
        setGenerating(true)
        progressText.visibility = View.VISIBLE
        progressText.text = c.getString(R.string.s_126)
        curStep = 0
        totalStep = 0
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        onStatus?.invoke(c.getString(R.string.s_165), false)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            // 逐秒报“已耗时”，否则底层不报中间进度，看着像卡死
            val ticker = launch {
                while (true) {
                    delay(1000)
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    progressText.post {
                        val phase = if (sec < 8) c.getString(R.string.s_127) else c.getString(R.string.s_129)
                        val stepInfo = if (totalStep > 0) c.getString(R.string.v_049, (curStep), (totalStep)) else ""
                        progressText.text = c.getString(R.string.v_050, (phase), (stepInfo), (sec))
                        if (totalStep > 0) {
                            progressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                        }
                    }
                }
            }
            try {
                val img = generateImage(prompt) { cur, total ->
                    curStep = cur
                    totalStep = total
                }
                ticker.cancel()
                val bmp = toBitmap(img)
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                resultImg.post {
                    DrawHistory.add(img)          // 先进历史（内存）
                    lastImage = bmp
                    resultImg.setImageBitmap(bmp)
                    resultInfo.text = c.getString(R.string.v_051, (img.width), (img.height), (img.seed), (sec))
                    refreshHistory()
                    switchPane(toResult = true)   // 出图后自动跳到结果页
                }
                progressText.post { progressText.text = c.getString(R.string.v_052, (img.width), (img.height), (img.seed), (sec)) }
                onStatus?.invoke(c.getString(R.string.v_053, (sec)), false)
            } catch (e: Throwable) {
                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                if (cancelRequested) {
                    progressText.post { progressText.text = c.getString(R.string.v_054, (sec)) }
                    onStatus?.invoke(c.getString(R.string.s_154), false)
                } else {
                    progressText.post { progressText.text = c.getString(R.string.v_059, (sec), (e.message ?: e.javaClass.simpleName)) + "\n" + c.getString(R.string.v_060) }
                    onStatus?.invoke(c.getString(R.string.v_055, (e.message ?: e.javaClass.simpleName)), true)
                }
            } finally {
                setGenerating(false)
                progressBar.post { progressBar.visibility = View.GONE }
            }
        }
    }

    private var curStep = 0
    private var totalStep = 0

    /** 用户是否已请求中断当前生成（用于把“取消”与“真失败”区分开）。 */
    @Volatile private var cancelRequested = false

    /** 供对话页复用的生成入口：把输入当正面提示词，其余参数取绘图页当前设置。 */
    suspend fun generateImage(
        prompt: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImageData {
        val steps = stepsEdit.text.toString().toIntOrNull()?.coerceIn(1, 150) ?: 20
        val cfg = cfgEdit.text.toString().toFloatOrNull()?.coerceIn(1f, 30f) ?: 7.0f
        val seed = seedEdit.text.toString().toLongOrNull() ?: -1L
        val useSeed = if (seed < 0) System.currentTimeMillis() else seed
        // 宽高：sd.cpp 要求 64 的倍数，向下取整；夹到 64~2048 防止手滑
        val w = (widthEdit.text.toString().toIntOrNull() ?: 512).let { (it / 64) * 64 }.coerceIn(64, 2048)
        val hgt = (heightEdit.text.toString().toIntOrNull() ?: 512).let { (it / 64) * 64 }.coerceIn(64, 2048)
        onProgress(0, steps)
        cancelRequested = false

        val h = sdHandle
        if (h == 0L) throw IllegalStateException(c.getString(R.string.s_163))
        if (mainModel == null) throw IllegalStateException(c.getString(R.string.s_121))

        // LoRA：走结构化参数（sd.cpp 原生接口，不再拼 prompt 里的 lora: 语法）
        val lora = if (useLora) activeLora() else null
        // EditText 必须在主线程读，先取出来再进 IO
        val negative = negEdit.text.toString()

        // 采样器/调度器：默认「自动」——勾了 LoRA 就走 LCM（否则 LCM-LoRA 效果大打折扣），否则 Euler a + Discrete
        val sampler = pickSampler(lora != null)
        val scheduler = pickScheduler(lora != null)
        // LoRA 权重：0 = 不挂，1.0 是标准
        val loraScale = loraScaleEdit.text.toString().toFloatOrNull()?.coerceIn(0f, 2f) ?: 1.0f

        // 推理是同步阻塞的 native 调用（一张几百秒），绝不能跟调用方同线程——
        // 调用方基本都在 Dispatchers.Main，否则整个 UI 会卡死到出图为止。
        val bmp = withContext(Dispatchers.IO) {
            SdCppEngine.render(
                handle = h,
                prompt = prompt,
                negative = negative,
                loraPath = lora?.absolutePath,
                loraScale = loraScale,
                width = w,
                height = hgt,
                steps = steps,
                cfg = cfg,
                seed = useSeed,
                sampler = sampler,
                scheduler = scheduler,
                cb = { cur, total -> onProgress(cur, total) },
            )
        } ?: throw IllegalStateException(c.getString(R.string.s_147))
        onProgress(steps, steps)
        return ImageData(bmp, useSeed)
    }

    /** 采样器选择：索引 0 = 自动；其余按 SdCppEngine.Sampler 顺序 */
    private fun pickSampler(hasLora: Boolean): SdCppEngine.Sampler {
        val idx = samplerSpinner.selectedItemPosition
        if (idx <= 0) return if (hasLora) SdCppEngine.Sampler.LCM else SdCppEngine.Sampler.EULER_A
        return SdCppEngine.Sampler.entries.getOrElse(idx - 1) { SdCppEngine.Sampler.EULER_A }
    }

    /** 调度器选择：索引 0 = 自动 */
    private fun pickScheduler(hasLora: Boolean): SdCppEngine.Scheduler {
        val idx = schedulerSpinner.selectedItemPosition
        if (idx <= 0) return if (hasLora) SdCppEngine.Scheduler.LCM else SdCppEngine.Scheduler.DISCRETE
        return SdCppEngine.Scheduler.entries.getOrElse(idx - 1) { SdCppEngine.Scheduler.DISCRETE }
    }

    // ================= 图生图生成 =================

    /** 图生图采样器（对应图生图页的 spinner） */
    private fun pickI2iSampler(hasLora: Boolean): SdCppEngine.Sampler {
        val idx = i2iSamplerSpinner.selectedItemPosition
        if (idx <= 0) return if (hasLora) SdCppEngine.Sampler.LCM else SdCppEngine.Sampler.EULER_A
        return SdCppEngine.Sampler.entries.getOrElse(idx - 1) { SdCppEngine.Sampler.EULER_A }
    }

    /** 图生图调度器 */
    private fun pickI2iScheduler(hasLora: Boolean): SdCppEngine.Scheduler {
        val idx = i2iSchedulerSpinner.selectedItemPosition
        if (idx <= 0) return if (hasLora) SdCppEngine.Scheduler.LCM else SdCppEngine.Scheduler.DISCRETE
        return SdCppEngine.Scheduler.entries.getOrElse(idx - 1) { SdCppEngine.Scheduler.DISCRETE }
    }

    /** 点图生图页的「生成」按钮 */
    private fun generateFromUiI2i() {
        persistAll()
        if (!isReady()) {
            val msg = if (llmLoaded) c.getString(R.string.s_100)
            else c.getString(R.string.s_173)
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            return
        }
        if (i2iBitmap == null) {
            Toast.makeText(c, c.getString(R.string.s_214), Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = i2iPromptEdit.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(c, c.getString(R.string.s_047), Toast.LENGTH_SHORT).show()
            return
        }
        setGenerating(true)
        i2iProgressText.visibility = View.VISIBLE
        i2iProgressText.text = c.getString(R.string.s_126)
        curStep = 0
        totalStep = 0
        i2iProgressBar.progress = 0
        i2iProgressBar.visibility = View.VISIBLE
        onStatus?.invoke(c.getString(R.string.s_165), false)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(1000)
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    i2iProgressText.post {
                        val phase = if (sec < 8) c.getString(R.string.s_127) else c.getString(R.string.s_129)
                        val stepInfo = if (totalStep > 0) c.getString(R.string.v_049, (curStep), (totalStep)) else ""
                        i2iProgressText.text = c.getString(R.string.v_050, (phase), (stepInfo), (sec))
                        if (totalStep > 0) {
                            i2iProgressBar.progress = (curStep * 100 / totalStep).coerceIn(0, 100)
                        }
                    }
                }
            }
            try {
                val img = generateImageI2i(prompt) { cur, total ->
                    curStep = cur
                    totalStep = total
                }
                ticker.cancel()
                val bmp = toBitmap(img)
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                resultImg.post {
                    DrawHistory.add(img)
                    lastImage = bmp
                    resultImg.setImageBitmap(bmp)
                    resultInfo.text = c.getString(R.string.v_051, (img.width), (img.height), (img.seed), (sec))
                    refreshHistory()
                    switchPane(toResult = true)
                }
                i2iProgressText.post { i2iProgressText.text = c.getString(R.string.v_052, (img.width), (img.height), (img.seed), (sec)) }
                onStatus?.invoke(c.getString(R.string.v_053, (sec)), false)
            } catch (e: Throwable) {
                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                if (cancelRequested) {
                    i2iProgressText.post { i2iProgressText.text = c.getString(R.string.v_054, (sec)) }
                    onStatus?.invoke(c.getString(R.string.s_154), false)
                } else {
                    i2iProgressText.post { i2iProgressText.text = c.getString(R.string.v_059, (sec), (e.message ?: e.javaClass.simpleName)) + "\n" + c.getString(R.string.v_060) }
                    onStatus?.invoke(c.getString(R.string.v_055, (e.message ?: e.javaClass.simpleName)), true)
                }
            } finally {
                setGenerating(false)
                i2iProgressBar.post { i2iProgressBar.visibility = View.GONE }
            }
        }
    }

    /** 图生图实际推理：把选中的参考图编码成 RGB888 传给 native，strength 控制重绘幅度 */
    private suspend fun generateImageI2i(
        prompt: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImageData {
        val steps = i2iStepsEdit.text.toString().toIntOrNull()?.coerceIn(1, 150) ?: 20
        val cfg = i2iCfgEdit.text.toString().toFloatOrNull()?.coerceIn(1f, 30f) ?: 7.0f
        val seed = i2iSeedEdit.text.toString().toLongOrNull() ?: -1L
        val useSeed = if (seed < 0) System.currentTimeMillis() else seed
        val w = (i2iWidthEdit.text.toString().toIntOrNull() ?: 512).let { (it / 64) * 64 }.coerceIn(64, 2048)
        val hgt = (i2iHeightEdit.text.toString().toIntOrNull() ?: 512).let { (it / 64) * 64 }.coerceIn(64, 2048)
        val strength = i2iStrengthEdit.text.toString().toFloatOrNull()?.coerceIn(0.05f, 0.99f) ?: 0.75f
        onProgress(0, steps)
        cancelRequested = false

        val h = sdHandle
        if (h == 0L) throw IllegalStateException(c.getString(R.string.s_163))
        if (mainModel == null) throw IllegalStateException(c.getString(R.string.s_121))
        val src = i2iBitmap ?: throw IllegalStateException(c.getString(R.string.s_214))

        val lora = if (useLora) activeLora() else null
        val negative = i2iNegEdit.text.toString()
        val sampler = pickI2iSampler(lora != null)
        val scheduler = pickI2iScheduler(lora != null)
        val loraScale = i2iLoraScaleEdit.text.toString().toFloatOrNull()?.coerceIn(0f, 2f) ?: 1.0f

        val bmp = withContext(Dispatchers.IO) {
            val rgb = SdCppEngine.bitmapToRgb888(src)
            SdCppEngine.render(
                handle = h,
                prompt = prompt,
                negative = negative,
                loraPath = lora?.absolutePath,
                loraScale = loraScale,
                width = w,
                height = hgt,
                steps = steps,
                cfg = cfg,
                seed = useSeed,
                sampler = sampler,
                scheduler = scheduler,
                cb = { cur, total -> onProgress(cur, total) },
                initImage = rgb,
                initWidth = src.width,
                initHeight = src.height,
                strength = strength,
            )
        } ?: throw IllegalStateException(c.getString(R.string.s_147))
        onProgress(steps, steps)
        return ImageData(bmp, useSeed)
    }

    fun toBitmap(img: ImageData): Bitmap = img.bitmap

    /** 点「中断生成」：先给个即时反馈，再请求 native 中断 */
    private fun doCancel() {
        cancel()
        progressText.text = c.getString(R.string.s_125)
    }

    /** 统一切换按钮的「开始生成 / 中断生成」外观与行为 */
    private fun setGenerating(g: Boolean) {
        generating = g
        genBtn.post {
            genBtn.isEnabled = true
            genBtn.text = if (g) c.getString(R.string.s_040) else c.getString(R.string.s_098)
            genBtn.setBackgroundColor(if (g) 0xFFD9534F.toInt() else primary)
        }
        if (::i2iGenBtn.isInitialized) i2iGenBtn.post {
            i2iGenBtn.isEnabled = true
            i2iGenBtn.text = if (g) c.getString(R.string.s_040) else c.getString(R.string.s_098)
            i2iGenBtn.setBackgroundColor(if (g) 0xFFD9534F.toInt() else primary)
        }
    }

    fun cancel() {
        cancelRequested = true
        if (sdHandle != 0L) runCatching { SdCppEngine.nativeCancel(sdHandle) }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
        if (requestCode != REQ_IMAGE && requestCode != REQ_TAGGER_IMAGE) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decodePickedImage(uri) }
            if (bmp == null) {
                toast(c.getString(R.string.s_215))
            } else if (requestCode == REQ_IMAGE) {
                applyPickedImage(bmp)
            } else {
                applyTaggerImage(bmp)
            }
        }
        return true
    }

    fun release() {
        if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
        sdHandle = 0L
    }

    // ================= UI 小工具 =================

    private fun toast(t: String) = Toast.makeText(c, t, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics
    ).toInt()

    private fun card() = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun title(t: String) = TextView(c).apply {
        text = t
        textSize = 14.5f
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun body(t: String) = TextView(c).apply {
        text = t
        textSize = 12.5f
        setTextColor(subText)
        setPadding(0, dp(6), 0, 0)
    }

    private fun smallLabel(t: String) = TextView(c).apply {
        text = t
        textSize = 11.5f
        setTextColor(subText)
        setPadding(0, dp(8), 0, 0)
    }

    private fun labeledEdit(hint: String, singleLine: Boolean, minLines: Int = 1) = EditText(c).apply {
        this.hint = hint
        textSize = 13f
        setTextColor(textColor)
        setHintTextColor(0xFFAAAAAA.toInt())
        setBackgroundColor(0xFFF2F3F5.toInt())
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isSingleLine = singleLine
        if (!singleLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            this.minLines = minLines
        }
    }

    private fun smallNumber(def: String) = EditText(c).apply {
        setText(def)
        textSize = 13f
        setTextColor(textColor)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setBackgroundColor(0xFFF2F3F5.toInt())
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun paramRow(label: String, input: View, hint: String): View {
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(smallLabel(label))
        wrap.addView(input, matchWrap(top = 4))
        wrap.addView(TextView(c).apply {
            text = hint
            textSize = 10.5f
            setTextColor(subText)
            setPadding(0, dp(3), 0, 0)
        })
        return wrap
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    /** 顶部菜单的一项 */
    private fun tabItem(t: String) = TextView(c).apply {
        text = t
        textSize = 13.5f
        gravity = Gravity.CENTER
        setPadding(0, dp(11), 0, dp(11))
    }

    /** 切换「参数 / 结果」面板（带动画翻页），并高亮当前项 */
    private fun switchPane(toResult: Boolean) {
        if (!::paramPane.isInitialized) return
        if (::pager.isInitialized) pager.setCurrentItem(if (toResult) 1 else 0, true)
        highlightTabs(toResult)
    }

    /** 同步顶部菜单的高亮；手势翻页时也由它更新 */
    private fun highlightTabs(toResult: Boolean) {
        if (!::tabParams.isInitialized) return
        for ((tv, sel) in listOf(tabParams to !toResult, tabResult to toResult)) {
            tv.setTextColor(if (sel) primary else subText)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) 0xFFEDF1FF.toInt() else Color.WHITE)
        }
    }

    /** 让某个面板回到最顶处（等布局完成后执行，避免刚切页时高度还是 0） */
    private fun scrollTop(s: ScrollView?) {
        s ?: return
        s.post { s.smoothScrollTo(0, 0) }
    }

    /** 给面板包一层可纵向滚动的 ScrollView（翻页控件需要一个确定高度的子项） */
    private fun scrollWrap(inner: View) = ScrollView(c).apply {
        isFillViewport = true
        addView(inner, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** ViewPager 用的最小 PagerAdapter：直接复用已建好的两个面板，不重建 */
    private class PaneAdapter(private val panes: List<View>) : PagerAdapter() {
        override fun getCount() = panes.size
        override fun isViewFromObject(view: View, obj: Any) = view === obj
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val v = panes[position]
            (v.parent as? ViewGroup)?.removeView(v)
            container.addView(v)
            return v
        }
        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            (obj as? View)?.let { container.removeView(it) }
        }
    }

    /** 宽 × 高 输入行 */
    private fun whRow(label: String, hint: String, wEdit: EditText, hEdit: EditText): View {
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(smallLabel(label))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(wEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(c).apply {
            text = " × "
            textSize = 14f
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(hEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(row, matchWrap(top = 4))
        wrap.addView(TextView(c).apply {
            text = hint
            textSize = 10.5f
            setTextColor(subText)
            setPadding(0, dp(3), 0, 0)
        })
        return wrap
    }

    private fun choiceSpinner(items: List<String>) = android.widget.Spinner(c).apply {
        adapter = android.widget.ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, items)
    }

    // ---- 参数持久化（提示词不保存，每次重来） ----

    private fun prefs() = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadParam(key: String, def: String): String = prefs().getString(key, def) ?: def

    private fun loadInt(key: String, def: Int): Int = prefs().getInt(key, def)

    /**
     * 参数改动即时落盘。绑定时机在 setText(初值) 之后，
     * 所以初始化不会把恢复出来的值又写一遍。
     */
    private fun bindParam(edit: EditText, key: String) {
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString().orEmpty()
                if (v.isNotBlank()) prefs().edit().putString(key, v).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
        })
    }

    /**
     * 把当前所有参数写盘 —— 由 MainActivity.onPause() 与「开始生成」兜底调用。
     * 不能只靠输入框的 TextWatcher：切 tab、直接杀进程等场景可能漏掉。
     */
    fun persistAll() {
        if (!::stepsEdit.isInitialized) return
        val e = prefs().edit()
        fun put(k: String, ed: EditText) {
            val v = ed.text.toString()
            if (v.isNotBlank()) e.putString(k, v)
        }
        put(KEY_W, widthEdit)
        put(KEY_H, heightEdit)
        put(KEY_STEPS, stepsEdit)
        put(KEY_CFG, cfgEdit)
        put(KEY_SEED, seedEdit)
        put(KEY_LORA_SCALE, loraScaleEdit)
        e.putInt(KEY_SAMPLER, samplerSpinner.selectedItemPosition)
        e.putInt(KEY_SCHEDULER, schedulerSpinner.selectedItemPosition)
        if (::i2iStepsEdit.isInitialized) {
            put(KEY_I2I_W, i2iWidthEdit)
            put(KEY_I2I_H, i2iHeightEdit)
            put(KEY_I2I_STEPS, i2iStepsEdit)
            put(KEY_I2I_CFG, i2iCfgEdit)
            put(KEY_I2I_SEED, i2iSeedEdit)
            put(KEY_I2I_STRENGTH, i2iStrengthEdit)
            put(KEY_I2I_LORA_SCALE, i2iLoraScaleEdit)
            e.putInt(KEY_I2I_SAMPLER, i2iSamplerSpinner.selectedItemPosition)
            e.putInt(KEY_I2I_SCHEDULER, i2iSchedulerSpinner.selectedItemPosition)
        }
        if (::taggerChanSpinner.isInitialized) {
            e.putInt(KEY_TAG_CHAN, taggerChanSpinner.selectedItemPosition)
        }
        e.apply()
    }

    /** Spinner 选择落盘 */
    private fun persistSpinner(key: String) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
            prefs().edit().putInt(key, pos).apply()
        }
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
    }

    // ---- 默认值（文生图 / 图生图按 LoRA 开/关各一套；Tagger 不分） ----

    /** 当前 LoRA 状态（0=关 1=开），作默认值键后缀 */
    private fun loraTag(): String = if (useLora) "1" else "0"

    private fun builtinSteps(): String = if (useLora) "6" else "20"
    private fun builtinCfg(): String = if (useLora) "1.8" else "7.0"

    /** 取某页参数的默认值：优先用户保存的默认，否则用内置 */
    private fun defOf(page: String, name: String, builtin: String): String =
        prefs().getString("def_${page}_${name}_${loraTag()}", null) ?: builtin

    private fun defOfInt(page: String, name: String, builtin: Int): Int =
        prefs().getInt("def_${page}_${name}_${loraTag()}", builtin)

    /** 把当前页当前参数存为该页（当前 LoRA 状态）的默认值 */
    fun saveDefaults(page: String) {
        val lg = loraTag()
        val e = prefs().edit()
        fun put(n: String, ed: EditText) { e.putString("def_${page}_${n}_$lg", ed.text.toString()) }
        when (page) {
            "t2i" -> {
                put("w", widthEdit); put("h", heightEdit); put("steps", stepsEdit)
                put("cfg", cfgEdit); put("seed", seedEdit); put("loraScale", loraScaleEdit)
                e.putInt("def_t2i_sampler_$lg", samplerSpinner.selectedItemPosition)
                e.putInt("def_t2i_scheduler_$lg", schedulerSpinner.selectedItemPosition)
            }
            "i2i" -> {
                put("strength", i2iStrengthEdit)
                put("w", i2iWidthEdit); put("h", i2iHeightEdit); put("steps", i2iStepsEdit)
                put("cfg", i2iCfgEdit); put("seed", i2iSeedEdit); put("loraScale", i2iLoraScaleEdit)
                e.putInt("def_i2i_sampler_$lg", i2iSamplerSpinner.selectedItemPosition)
                e.putInt("def_i2i_scheduler_$lg", i2iSchedulerSpinner.selectedItemPosition)
            }
            "tag" -> {
                e.putString("def_tag_threshold", taggerThresholdEdit.text.toString())
                e.putString("def_tag_topk", taggerTopKEdit.text.toString())
                e.putInt("def_tag_chan", taggerChanSpinner.selectedItemPosition)
            }
        }
        e.apply()
        toast(c.getString(R.string.s_250))
    }

    /** 把默认值套用到指定页（当前 LoRA 状态） */
    fun applyDefaults(page: String) {
        when (page) {
            "t2i" -> {
                widthEdit.setText(defOf("t2i", "w", "512"))
                heightEdit.setText(defOf("t2i", "h", "512"))
                stepsEdit.setText(defOf("t2i", "steps", builtinSteps()))
                cfgEdit.setText(defOf("t2i", "cfg", builtinCfg()))
                seedEdit.setText(defOf("t2i", "seed", "-1"))
                loraScaleEdit.setText(defOf("t2i", "loraScale", "1.0"))
                samplerSpinner.setSelection(defOfInt("t2i", "sampler", 0), false)
                schedulerSpinner.setSelection(defOfInt("t2i", "scheduler", 0), false)
            }
            "i2i" -> {
                i2iStrengthEdit.setText(defOf("i2i", "strength", "0.75"))
                i2iWidthEdit.setText(defOf("i2i", "w", "512"))
                i2iHeightEdit.setText(defOf("i2i", "h", "512"))
                i2iStepsEdit.setText(defOf("i2i", "steps", builtinSteps()))
                i2iCfgEdit.setText(defOf("i2i", "cfg", builtinCfg()))
                i2iSeedEdit.setText(defOf("i2i", "seed", "-1"))
                i2iLoraScaleEdit.setText(defOf("i2i", "loraScale", "1.0"))
                i2iSamplerSpinner.setSelection(defOfInt("i2i", "sampler", 0), false)
                i2iSchedulerSpinner.setSelection(defOfInt("i2i", "scheduler", 0), false)
            }
            "tag" -> {
                taggerThresholdEdit.setText(prefs().getString("def_tag_threshold", null) ?: "0.35")
                taggerTopKEdit.setText(prefs().getString("def_tag_topk", null) ?: "40")
                taggerChanSpinner.setSelection(prefs().getInt("def_tag_chan", 0).coerceIn(0, 1), false)
            }
        }
    }

    /** 参数卡底部的「设为默认值 / 恢复默认」一行（点击后需确认） */
    private fun defaultButtonsRow(page: String): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ghostButton(c.getString(R.string.s_248)) { confirmDefaults(page, true) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ghostButton(c.getString(R.string.s_249)) { confirmDefaults(page, false) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) })
        return row
    }

    /** 点击后先弹确认框，确认才真正生效（save=true 存为默认，false 恢复默认） */
    private fun confirmDefaults(page: String, save: Boolean) {
        val pageName = c.getString(when (page) {
            "t2i" -> R.string.s_207
            "i2i" -> R.string.s_208
            else -> R.string.s_221
        })
        android.app.AlertDialog.Builder(act)
            .setTitle(c.getString(if (save) R.string.s_248 else R.string.s_249))
            .setMessage(c.getString(if (save) R.string.s_256 else R.string.s_257, pageName))
            .setPositiveButton(c.getString(R.string.s_258)) { _, _ ->
                if (save) saveDefaults(page) else applyDefaults(page)
            }
            .setNegativeButton(c.getString(R.string.s_066), null)
            .show()
    }

    private fun ghostButton(t: String, onClick: () -> Unit) = Button(c).apply {
        text = t
        textSize = 12.5f
        setTextColor(primary)
        setBackgroundColor(0xFFEDF1FF.toInt())
        setOnClickListener { onClick() }
    }

    /** 结果图保存用的文件名 */
    private fun saveName(): String = "ponko_${System.currentTimeMillis()}.png"

    // ---- 生成历史（只活在内存里，进程结束即清空） ----

    /** 点缩略图：把大图切到这一张 */
    private fun showHistoryImage(img: ImageData) {
        lastImage = img.bitmap
        resultImg.setImageBitmap(img.bitmap)
        resultInfo.text = "${img.width}×${img.height} · seed ${img.seed}"
    }

    /** 重建缩略图列表 */
    private fun refreshHistory() {
        if (!::historyRow.isInitialized) return
        val items = DrawHistory.list()
        histEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        historyRow.removeAllViews()
        for (img in items) {
            val iv = ImageView(c).apply {
                setImageBitmap(img.bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFEDEDED.toInt())
                setOnClickListener { showHistoryImage(img) }
                setOnLongClickListener {
                    android.app.AlertDialog.Builder(act)
                        .setMessage(c.getString(R.string.s_057))
                        .setPositiveButton(c.getString(R.string.s_050)) { _, _ -> DrawHistory.remove(img); refreshHistory() }
                        .setNegativeButton(c.getString(R.string.s_066), null)
                        .show()
                    true
                }
            }
            historyRow.addView(iv, LinearLayout.LayoutParams(dp(76), dp(76)).apply { rightMargin = dp(8) })
        }
    }
}


/** 生成结果：Bitmap + 尺寸 + 实际使用的种子 */
class ImageData(val bitmap: Bitmap, val seed: Long) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/**
 * 绘图结果历史 —— 纯内存，App 进程结束即清空（用户要求「保留至程序被杀」）。
 * 放在 object 里而不是 DrawPage 字段里，这样 Activity 重建（旋转屏幕、
 * 内存回收后恢复）也能看到同一份历史。
 */
object DrawHistory {
    /** 最多留这么多张，防止一直生成把内存撑爆（512×512 一张约 1MB） */
    private const val MAX = 30
    private val items = mutableListOf<ImageData>()

    /** 新的排在最前 */
    fun add(img: ImageData) {
        items.add(0, img)
        while (items.size > MAX) items.removeAt(items.size - 1)
    }

    fun list(): List<ImageData> = items

    fun remove(img: ImageData) {
        items.remove(img)
    }

    fun clear() = items.clear()

    fun size() = items.size
}
