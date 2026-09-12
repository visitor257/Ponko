package com.litertchat.app.draw

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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 用户主动中断生成时抛出（与真正的失败区分开）。 */
class GenerationCancelledException : RuntimeException("已中断")

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
        private const val REQ_IMAGE = 0x5D02

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

    // 结果面板
    private lateinit var resultImg: ImageView
    private lateinit var resultInfo: TextView
    private lateinit var saveBtn: Button
    private lateinit var historyRow: LinearLayout
    private lateinit var histEmpty: TextView

    /** 最近一次生成的图（结果页「保存到相册」用） */
    private var lastImage: Bitmap? = null

    /** 保存图片到相册：MainActivity 注入实现（复用它的 MediaStore 逻辑）。参数：图 + 文件名 */
    var onSaveImage: ((Bitmap, String) -> Unit)? = null

    fun build(): View {
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFF5F6F8.toInt())
        }

        // ---- 顶部菜单：参数 / 结果 ----
        val tabBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        tabParams = tabItem("⚙️ 参数")
        tabResult = tabItem("🖼️ 结果")
        tabParams.setOnClickListener { switchPane(toResult = false) }
        tabResult.setOnClickListener { switchPane(toResult = true) }
        tabBar.addView(tabParams, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(tabResult, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabBar)

        // ---- 参数面板 ----
        paramPane = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        root.addView(paramPane, matchWrap())

        // ---- 模型状态（模型统一在「模型」页选择并加载） ----
        val modelCard = card()
        modelCard.addView(title("绘图模型（stable-diffusion.cpp · GGUF）"))
        statusText = body("未加载 —— 请到「模型」页的「绘图模型」里选择模型文件夹")
        modelCard.addView(statusText)
        quantText = TextView(c).apply {
            textSize = 11.5f
            setTextColor(subText)
            setPadding(0, dp(6), 0, 0)
        }
        modelCard.addView(quantText)
        paramPane.addView(modelCard)

        // ---- 提示词 ----
        val promptCard = card()
        promptCard.addView(title("提示词"))
        promptEdit = labeledEdit("一个可爱的动漫女孩，细节丰富", singleLine = false, minLines = 3)
        promptCard.addView(promptEdit, matchWrap(top = 6))
        promptCard.addView(smallLabel("负向提示词（不想出现的内容）"))
        negEdit = labeledEdit("lowres, bad anatomy, bad hands, text, error, worst quality", singleLine = false, minLines = 2)
        promptCard.addView(negEdit, matchWrap(top = 4))
        paramPane.addView(promptCard)

        // ---- 生成 / 中断（同一个按钮），放在提示词与参数之间，方便盯着进度 ----
        genBtn = Button(c).apply {
            text = "开始生成"
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { if (generating) doCancel() else generateFromUi() }
        }
        paramPane.addView(genBtn, matchWrap(top = 2))

        progressText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        paramPane.addView(progressText)

        progressBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        paramPane.addView(progressBar, matchWrap(top = 4))

        // ---- 参数 ----
        val paramCard = card()
        paramCard.addView(title("参数"))

        // 尺寸：宽 × 高，自由填（sd.cpp 要求 64 的倍数）——从上次的值恢复
        widthEdit = smallNumber(loadParam(KEY_W, "512"))
        heightEdit = smallNumber(loadParam(KEY_H, "512"))
        paramCard.addView(whRow("图片尺寸（宽 × 高）",
            "可自由填，但必须是 64 的倍数（会自动向下取整）。512×512 是 SD1.5 原生分辨率；256 出图快很多，适合先验证"))

        // 采样器 / 调度器
        samplerSpinner = choiceSpinner(
            listOf("自动（勾 LoRA 时用 LCM，否则 Euler a）") + SdCppEngine.Sampler.entries.map { it.label }
        )
        schedulerSpinner = choiceSpinner(
            listOf("自动（勾 LoRA 时用 LCM，否则 Discrete）") + SdCppEngine.Scheduler.entries.map { it.label }
        )
        samplerSpinner.setSelection(loadInt(KEY_SAMPLER, 0).coerceIn(0, samplerSpinner.adapter.count - 1), false)
        schedulerSpinner.setSelection(loadInt(KEY_SCHEDULER, 0).coerceIn(0, schedulerSpinner.adapter.count - 1), false)
        // 先恢复再挂监听，免得 setSelection 把默认值又写回去
        samplerSpinner.onItemSelectedListener = persistSpinner(KEY_SAMPLER)
        schedulerSpinner.onItemSelectedListener = persistSpinner(KEY_SCHEDULER)
        paramCard.addView(paramRow("采样器", samplerSpinner, "Euler a 通用最稳；LCM / TCD 才是配 LoRA 少步加速的；DPM++ 2M 细节更好但更慢"))
        paramCard.addView(paramRow("调度器", schedulerSpinner, "Discrete 是标准选择；Karras 常配 DPM++ 系；LCM 配 LCM 采样器"))

        stepsEdit = smallNumber(loadParam(KEY_STEPS, "20"))
        cfgEdit = smallNumber(loadParam(KEY_CFG, "7.0"))
        seedEdit = smallNumber(loadParam(KEY_SEED, "-1"))
        bindParam(widthEdit, KEY_W)
        bindParam(heightEdit, KEY_H)
        bindParam(stepsEdit, KEY_STEPS)
        bindParam(cfgEdit, KEY_CFG)
        bindParam(seedEdit, KEY_SEED)
        paramCard.addView(paramRow("采样步数", stepsEdit, "越大越精细，也越慢（标准 20 步；用 LoRA 加速时 4~8 步即可）"))
        paramCard.addView(paramRow("CFG 引导", cfgEdit, "贴合提示词的程度，标准 7 左右；LCM-LoRA 建议 1.5~2"))
        paramCard.addView(paramRow("随机种子", seedEdit, "-1 = 每次随机；固定值可复现同一张图"))

        // ---- LoRA 加速开关 ----
        loraCheck = CheckBox(c).apply {
            text = "LoRA 加速（LCM-LoRA · 少步出图）"
            textSize = 13f
            setTextColor(textColor)
            isChecked = useLora
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                useLora = checked
                if (checked) {
                    // 打开就顺手把参数带到 LCM 的推荐档位（用户仍可手动改回去）
                    if (activeLora() == null) {
                        toast("还没装 LoRA —— 请到「模型」页的「LoRA 加速」里下载")
                    } else {
                        stepsEdit.setText("6")
                        cfgEdit.setText("1.8")
                    }
                }
                refreshLoraHint()
            }
        }
        paramCard.addView(loraCheck)
        loraScaleEdit = smallNumber(loadParam(KEY_LORA_SCALE, "1.0"))
        bindParam(loraScaleEdit, KEY_LORA_SCALE)
        paramCard.addView(paramRow("LoRA 权重", loraScaleEdit, "一般 1.0；画风太浓可降到 0.6~0.8，加强可到 1.2（0 = 不挂）"))
        loraHint = TextView(c).apply {
            textSize = 11f
            setTextColor(subText)
            setPadding(0, dp(2), 0, 0)
        }
        paramCard.addView(loraHint)
        paramPane.addView(paramCard)

        // ---- 结果面板 ----
        resultPane = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(resultPane, matchWrap())

        val resultCard = card()
        resultCard.addView(title("生成结果"))
        resultImg = ImageView(c).apply {
            adjustViewBounds = true
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        resultCard.addView(resultImg, matchWrap())
        resultInfo = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            text = "还没有生成图片 —— 到「参数」页写完提示词后点「开始生成」"
        }
        resultCard.addView(resultInfo)
        saveBtn = Button(c).apply {
            text = "保存到相册"
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                val b = lastImage
                if (b == null) toast("还没有生成图片") else onSaveImage?.invoke(b, saveName())
            }
        }
        resultCard.addView(saveBtn, matchWrap(top = 10))
        resultPane.addView(resultCard)

        // ---- 生成历史（只活在内存里，App 进程结束即清空） ----
        val histCard = card()
        histCard.addView(title("生成历史"))
        histCard.addView(TextView(c).apply {
            text = "只留在内存里 —— 关掉 App 就清空。点缩略图切换大图，长按删除。"
            textSize = 11f
            setTextColor(subText)
            setPadding(0, dp(4), 0, 0)
        })
        histEmpty = TextView(c).apply {
            text = "还没有历史"
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
            text = "清空历史"
            textSize = 12f
            setTextColor(subText)
            setOnClickListener {
                if (DrawHistory.size() == 0) {
                    toast("历史已经是空的")
                } else {
                    android.app.AlertDialog.Builder(act)
                        .setMessage("清空全部生成历史？（已保存到相册的不受影响）")
                        .setPositiveButton("清空") { _, _ -> DrawHistory.clear(); refreshHistory() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
        histCard.addView(clearHistBtn, matchWrap(top = 10))
        resultPane.addView(histCard)
        refreshHistory()

        refreshLoraHint()
        refreshQuantText()
        switchPane(toResult = false)
        return root
    }

    // ================= LoRA =================

    /** LoRA 目录：filesDir/draw/lora */
    private fun loraDir(): File = File(File(c.filesDir, DIR_NAME), "lora").apply { mkdirs() }

    /** 已下载的 LoRA 列表（.safetensors） */
    fun listLoras(): List<File> =
        loraDir().listFiles { f -> f.isFile && f.name.endsWith(".safetensors", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()

    fun hasLora(): Boolean = listLoras().isNotEmpty()

    /** 当前生效的 LoRA（选中的；没选就取第一个） */
    fun activeLora(): File? {
        val all = listLoras()
        if (all.isEmpty()) return null
        val sel = loraFile
        if (sel != null && sel.isFile && all.any { it.absolutePath == sel.absolutePath }) return sel
        return all.first()
    }

    /** 供「模型」页展示的 LoRA 状态 */
    fun loraSummary(): String {
        val a = activeLora() ?: return "未安装"
        val size = "%.1f".format(a.length() / 1048576.0)
        return if (useLora) "已启用：${a.nameWithoutExtension}（$size MB）"
        else "已安装（未启用）：${a.nameWithoutExtension}（$size MB）"
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
                a == null -> "未安装 LoRA —— 到「模型」页的「LoRA 加速」里下载（约 135MB）"
                useLora -> "已启用 ${a.nameWithoutExtension}：按少步出图。若画面发灰/失真，把步数调到 4~8、CFG 调到 1.5~2"
                else -> "已安装 ${a.nameWithoutExtension}，勾选后启用（约 5 倍加速）"
            }
        } catch (_: Throwable) {}
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
            if (code !in 200..299) return@withContext "下载失败：HTTP $code（可换另一个源试试）"
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
                return@withContext "下载失败：文件不完整（${tmp.length()} 字节）"
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
            "下载失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ================= 模型（由「模型」页驱动） =================

    /** 从 SAF 目录导入绘图模型：递归收集 .gguf 与 LoRA（.safetensors），复制到私有目录。返回 null 表示成功，否则为错误文案。 */
    suspend fun prepareFromTree(treeUri: Uri, onStage: (String) -> Unit): String? {
        return withContext(Dispatchers.IO) {
            try {
                onStage("正在扫描所选文件夹…")
                val root = File(c.filesDir, DIR_NAME).apply { mkdirs() }
                val ggufs = ArrayList<Pair<String, Uri>>()   // 主模型
                val loras = ArrayList<Pair<String, Uri>>()   // LoRA

                // SAF 坑：tree/document URI 不能直接 query，必须用 buildChildDocumentsUriUsingTree + docId
                fun listChildren(docId: String): List<Triple<String, String, String>> {
                    val childUri = android.provider.DocumentsContract
                        .buildChildDocumentsUriUsingTree(treeUri, docId)
                    return c.contentResolver.query(childUri, null, null, null, null)?.use { cur ->
                        val out = ArrayList<Triple<String, String, String>>()
                        val idIdx = cur.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val mimeIdx = cur.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                        while (cur.moveToNext()) {
                            val id = if (idIdx >= 0) cur.getString(idIdx) else null
                            val name = if (nameIdx >= 0) cur.getString(nameIdx) else null
                            val mime = if (mimeIdx >= 0) cur.getString(mimeIdx) else null
                            if (id != null && name != null) out.add(Triple(name, mime ?: "", id))
                        }
                        out
                    } ?: emptyList()
                }

                fun walk(docId: String, depth: Int) {
                    if (depth > 3) return
                    for ((name, mime, childId) in listChildren(docId)) {
                        val lower = name.lowercase()
                        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        when {
                            lower.endsWith(".gguf") -> ggufs.add(name to uri)
                            lower.endsWith(".safetensors") -> loras.add(name to uri)
                            mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR -> walk(childId, depth + 1)
                        }
                    }
                }

                walk(android.provider.DocumentsContract.getTreeDocumentId(treeUri), 0)

                if (ggufs.isEmpty() && loras.isEmpty()) {
                    return@withContext "所选文件夹里没找到绘图模型（.gguf）或 LoRA（.safetensors）"
                }

                var copied = 0
                for ((name, uri) in ggufs) {
                    onStage("正在复制 $name…")
                    try {
                        c.contentResolver.openInputStream(uri)?.use { ins ->
                            File(root, name).outputStream().use { outs -> ins.copyTo(outs, 1 shl 20) }
                        }
                        copied++
                    } catch (_: Throwable) {
                        // 单个文件失败不阻断
                    }
                }

                var loraCopied = 0
                val loraDest = loraDir()
                for ((name, uri) in loras) {
                    onStage("正在复制 LoRA $name…")
                    try {
                        c.contentResolver.openInputStream(uri)?.use { ins ->
                            File(loraDest, name).outputStream().use { outs -> ins.copyTo(outs, 1 shl 20) }
                        }
                        loraCopied++
                    } catch (_: Throwable) {
                    }
                }
                if (loraCopied > 0) refreshLoraHint()

                if (copied == 0 && loraCopied == 0) return@withContext "复制失败（0 个文件）"

                // 只复制、不在导入时加载：native 加载可能崩（实测过），
                // 留给用户在「模型」页手动点「加载绘图模型」，崩了也不会连累启动。
                val parts = buildList {
                    if (copied > 0) add("$copied 个模型文件")
                    if (loraCopied > 0) add("$loraCopied 个 LoRA")
                }.joinToString("、")
                val names = ggufs.joinToString("、") { it.first }
                statusText.post {
                    statusText.text = "已复制 $parts：$names" +
                        (if (loraCopied > 0) "（含 LoRA）" else "") +
                        "\n点「模型」页的「加载绘图模型」开始"
                }
                // 约定：返回 null = 成功（调用方据此刷新列表并提示）；失败才返回错误文案
                null
            } catch (e: Throwable) {
                "导入失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** 扫描私有目录里已复制的 gguf 并加载（main=null 时自动挑体积最大的） */
    suspend fun loadExisting(main: File? = null): String? = withContext(Dispatchers.IO) {
        try {
            loadFromPrivateDir(main)
        } catch (e: Throwable) {
            runCatching {
                File(File(c.filesDir, DIR_NAME), ".loadstage")
                    .appendText("E 顶层异常：${e.javaClass.name}: ${e.message}\n" +
                        android.util.Log.getStackTraceString(e) + "\n")
            }
            "加载失败：${e.message ?: e.javaClass.simpleName}"
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
            statusText.post { statusText.text = "未加载 —— 私有目录里没有绘图模型" }
            return "未加载 —— 私有目录里没有绘图模型"
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
        stage("0 选中模型：${main.name}（${main.length() / 1048576} MB，量化=${GgufProbe.quantType(main) ?: "未知"}）")
        if (vae != null) stage("0 VAE：${vae.name}（${vae.length() / 1048576} MB）")
        activeLora()?.let { stage("0 LoRA：${it.name}（${it.length() / 1048576} MB）") }
        stage("1 设备：abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()} sdk=${android.os.Build.VERSION.SDK_INT} 厂商=${android.os.Build.MANUFACTURER} 机型=${android.os.Build.MODEL}")
        stage("1 内存：maxHeap=${Runtime.getRuntime().maxMemory() / 1048576}MB freeDisk=${root.usableSpace / 1048576}MB")

        // 分步探测：每步都先落盘，后执行
        stage("2 加载 native 库（libponko_sd.so）")
        try {
            SdCppEngine.info().let { stage("3 native OK：$it") }
        } catch (t: Throwable) {
            stage("3 native 加载失败：${t.message ?: t.javaClass.simpleName}")
            runCatching { stageFile.delete() }
            return "native 库加载失败：${t.message ?: t.javaClass.simpleName}"
        }

        stage("4 创建 sd.cpp 上下文（CPU · ${nThreads} 线程）")
        return try {
            if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
            sdHandle = 0L
            val h = SdCppEngine.create(
                modelPath = main.absolutePath,
                vaePath = vae?.absolutePath,
                nThreads = nThreads,
                wtype = SdCppEngine.WTYPE_KEEP,
                flashAttn = flashAttn,
            )
            if (h == 0L) {
                stage("5 创建失败：new_sd_ctx 返回 0")
                runCatching { stage("4 参数：" + SdCppEngine.lastParams().replace("\n", "  |  ")) }
                runCatching { stageFile.delete() }
                return "加载失败：无法创建推理上下文（模型格式可能不受支持）"
            }
            sdHandle = h
            stage("5 上下文创建 OK")
            runCatching {
                stage("4 参数：" + SdCppEngine.lastParams().replace("\n", "  |  "))
            }

            val q = GgufProbe.quantType(main)
            val summary = buildString {
                append("已就绪：").append(main.name)
                if (q != null) append("（").append(q).append("）")
                if (vae != null) append("  +  ").append(vae.name)
                append(" · CPU · ").append(nThreads).append(" 线程 · ").append(if (flashAttn) "FA" else "无FA").append(" · sd.cpp")
            }
            statusText.post { statusText.text = summary }
            statusText.post { refreshQuantText() }
            runCatching { stageFile.delete() }
            onPipelineReady?.invoke()
            null
        } catch (e: Throwable) {
            stage("5 创建失败：${e.javaClass.name}: ${e.message}")
            runCatching { stageFile.appendText(android.util.Log.getStackTraceString(e) + "\n") }
            "加载失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun unloadModel() {
        if (sdHandle != 0L) runCatching { SdCppEngine.nativeFree(sdHandle) }
        sdHandle = 0L
        try {
            statusText.text = "未加载 —— 请到「模型」页的「绘图模型」里选择模型文件夹"
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
            "已就绪：" + (mainModel?.name ?: "绘图模型") +
                (if (q != null) "（$q）" else "") +
                " · CPU · " + nThreads + " 线程 · sd.cpp"
        }
        hasModel() -> "已复制模型，点「加载绘图模型」开始"
        else -> "未加载 —— 请到「模型」页的「绘图模型」里选择模型文件夹"
    }

    fun refreshStatus() {
        try { statusText.text = modelSummary() } catch (_: Throwable) {}
        refreshQuantText()
    }

    /** 当前绘图模型的量化等级（始终可见）。
     *  量化等级是烧在模型文件里的（Q4_0 的 gguf 就是 Q4_0），App 无法凭空转换，只能读出来展示。 */
    private fun refreshQuantText() {
        try {
            val m = mainModel ?: listModels().maxByOrNull { it.length() }
            quantText.text = if (m == null) {
                "当前模型量化：未导入模型　·　量化等级写死在模型文件里，App 只负责读出来显示"
            } else {
                val q = GgufProbe.quantType(m) ?: "未知"
                "当前模型量化：$q（${m.name}）　·　Q4_0 每步比 Q8_0 快，质量略降"
            }
        } catch (_: Throwable) {}
    }

    // ================= 生成 =================

    private fun generateFromUi() {
        if (!isReady()) {
            val msg = if (llmLoaded) "当前加载的是语言模型，不能绘图。请到「模型」页加载绘图模型（.gguf）。"
            else "请先到「模型」页的「绘图模型」里选择并加载模型"
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            return
        }
        val prompt = promptEdit.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(c, "先写点提示词吧", Toast.LENGTH_SHORT).show()
            return
        }
        setGenerating(true)
        progressText.visibility = View.VISIBLE
        progressText.text = "正在准备…（首次会先加载模型，1GB+ 可能要几分钟）"
        curStep = 0
        totalStep = 0
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        onStatus?.invoke("绘图生成中…", false)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            // 逐秒报“已耗时”，否则底层不报中间进度，看着像卡死
            val ticker = launch {
                while (true) {
                    delay(1000)
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    progressText.post {
                        val phase = if (sec < 8) "正在加载模型…" else "正在去噪采样…"
                        val stepInfo = if (totalStep > 0) " · 第 $curStep/$totalStep 步" else ""
                        progressText.text = "$phase$stepInfo · 已 ${sec} 秒"
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
                    resultInfo.text = "${img.width}×${img.height} · seed ${img.seed} · 耗时 ${sec} 秒"
                    refreshHistory()
                    switchPane(toResult = true)   // 出图后自动跳到结果页
                }
                progressText.post { progressText.text = "完成：${img.width}×${img.height}，seed=${img.seed}，耗时 ${sec} 秒" }
                onStatus?.invoke("绘图完成（${sec} 秒）", false)
            } catch (e: Throwable) {
                ticker.cancel()
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                if (cancelRequested) {
                    progressText.post { progressText.text = "已中断（${sec} 秒）" }
                    onStatus?.invoke("绘图已中断", false)
                } else {
                    progressText.post { progressText.text = "生成失败（${sec} 秒）：${e.message ?: e.javaClass.simpleName}\n${"完整堆栈见「查看加载日志」"}" }
                    onStatus?.invoke("绘图失败：${e.message ?: e.javaClass.simpleName}", true)
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
        if (h == 0L) throw IllegalStateException("绘图模型未加载")
        if (mainModel == null) throw IllegalStateException("未选择绘图模型")

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
        } ?: throw IllegalStateException("生成失败（sd.cpp 返回空）")
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

    fun toBitmap(img: ImageData): Bitmap = img.bitmap

    /** 点「中断生成」：先给个即时反馈，再请求 native 中断 */
    private fun doCancel() {
        cancel()
        progressText.text = "正在中断…（当前采样步结束后生效）"
    }

    /** 统一切换按钮的「开始生成 / 中断生成」外观与行为 */
    private fun setGenerating(g: Boolean) {
        generating = g
        genBtn.post {
            genBtn.isEnabled = true
            genBtn.text = if (g) "中断生成" else "开始生成"
            genBtn.setBackgroundColor(if (g) 0xFFD9534F.toInt() else primary)
        }
    }

    fun cancel() {
        cancelRequested = true
        if (sdHandle != 0L) runCatching { SdCppEngine.nativeCancel(sdHandle) }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean = false

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

    /** 切换「参数 / 结果」面板，并高亮当前项 */
    private fun switchPane(toResult: Boolean) {
        if (!::paramPane.isInitialized) return
        paramPane.visibility = if (toResult) View.GONE else View.VISIBLE
        resultPane.visibility = if (toResult) View.VISIBLE else View.GONE
        for ((tv, sel) in listOf(tabParams to !toResult, tabResult to toResult)) {
            tv.setTextColor(if (sel) primary else subText)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) 0xFFEDF1FF.toInt() else Color.WHITE)
        }
    }

    /** 宽 × 高 输入行 */
    private fun whRow(label: String, hint: String): View {
        val wrap = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(smallLabel(label))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(widthEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(c).apply {
            text = " × "
            textSize = 14f
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(heightEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
     * 参数改动即时落盘。只在输入框真正获得焦点时保存，
     * 避免 build() 初始化时把恢复出来的值又写一遍。
     */
    private fun bindParam(edit: EditText, key: String) {
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (edit.hasFocus()) prefs().edit().putString(key, s?.toString() ?: "").apply()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
        })
    }

    /** Spinner 选择落盘 */
    private fun persistSpinner(key: String) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
            prefs().edit().putInt(key, pos).apply()
        }
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
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
                        .setMessage("删除这一张历史？（已保存到相册的不受影响）")
                        .setPositiveButton("删除") { _, _ -> DrawHistory.remove(img); refreshHistory() }
                        .setNegativeButton("取消", null)
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
