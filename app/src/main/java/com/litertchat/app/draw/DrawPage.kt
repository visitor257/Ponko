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
import android.widget.TextView
import android.widget.Toast
import io.aatricks.llmedge.image.ImageClient
import io.aatricks.llmedge.image.ImageGenerationRequest
import io.aatricks.llmedge.model.ModelSpec
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
 * 引擎固定为 **stable-diffusion.cpp**（llmedge 内置 libsdcpp.so），直接读 `.gguf` 绘图模型
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
    }

    private val c: Context get() = act

    private var client: ImageClient? = null
    private var mainModel: File? = null
    private var vaeModel: File? = null

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
    fun isReady(): Boolean = client != null

    // 控件
    private lateinit var statusText: TextView
    private lateinit var quantText: TextView
    private lateinit var promptEdit: EditText
    private lateinit var negEdit: EditText
    private lateinit var stepsEdit: EditText
    private lateinit var cfgEdit: EditText
    private lateinit var seedEdit: EditText
    private lateinit var sizeSpinner: android.widget.Spinner
    private lateinit var loraCheck: CheckBox
    private lateinit var loraHint: TextView
    private lateinit var genBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var progressText: TextView
    private lateinit var resultImg: ImageView

    fun build(): View {
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFF5F6F8.toInt())
        }

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
        root.addView(modelCard)

        // ---- 提示词 ----
        val promptCard = card()
        promptCard.addView(title("提示词"))
        promptEdit = labeledEdit("一个可爱的动漫女孩，细节丰富", singleLine = false, minLines = 3)
        promptCard.addView(promptEdit, matchWrap(top = 6))
        promptCard.addView(smallLabel("负向提示词（不想出现的内容）"))
        negEdit = labeledEdit("lowres, bad anatomy, bad hands, text, error, worst quality", singleLine = false, minLines = 2)
        promptCard.addView(negEdit, matchWrap(top = 4))
        root.addView(promptCard)

        // ---- 参数 ----
        val paramCard = card()
        paramCard.addView(title("参数"))
        stepsEdit = smallNumber("20")
        cfgEdit = smallNumber("7.0")
        seedEdit = smallNumber("-1")
        sizeSpinner = android.widget.Spinner(c).apply {
            adapter = android.widget.ArrayAdapter(
                c, android.R.layout.simple_spinner_dropdown_item,
                listOf("512×512（标准）", "384×384", "256×256（快速验证）")
            )
        }
        paramCard.addView(paramRow("图片尺寸", sizeSpinner, "SD1.5 训练分辨率是 512；256 出图快很多，适合先验证能不能跑通"))
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
        loraHint = TextView(c).apply {
            textSize = 11f
            setTextColor(subText)
            setPadding(0, dp(2), 0, 0)
        }
        paramCard.addView(loraHint)
        root.addView(paramCard)

        // ---- 生成 ----
        genBtn = Button(c).apply {
            text = "开始生成"
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { generateFromUi() }
        }
        root.addView(genBtn, matchWrap(top = 4))

        cancelBtn = Button(c).apply {
            text = "取消生成"
            setTextColor(subText)
            visibility = View.GONE
            setOnClickListener {
                cancel()
                progressText.text = "正在中断…（当前采样步结束后生效）"
            }
        }
        root.addView(cancelBtn, matchWrap(top = 4))

        progressText = TextView(c).apply {
            textSize = 12f
            setTextColor(subText)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(progressText)

        resultImg = ImageView(c).apply {
            adjustViewBounds = true
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(resultImg)

        refreshLoraHint()
        refreshQuantText()
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
        stage("2 准备 loadLibrary(omp)")
        try {
            System.loadLibrary("omp")
            stage("3 loadLibrary(omp) OK")
        } catch (t: Throwable) {
            stage("3 loadLibrary(omp) 失败：${t.message ?: t.javaClass.simpleName}")
            runCatching { stageFile.delete() }
            return "native 库 libomp.so 加载失败：${t.message ?: t.javaClass.simpleName}"
        }
        stage("4 准备 loadLibrary(sdcpp)")
        try {
            System.loadLibrary("sdcpp")
            stage("5 loadLibrary(sdcpp) OK")
        } catch (t: Throwable) {
            stage("5 loadLibrary(sdcpp) 失败：${t.message ?: t.javaClass.simpleName}")
            runCatching { stageFile.delete() }
            return "native 库 libsdcpp.so 加载失败：${t.message ?: t.javaClass.simpleName}"
        }
        stage("6 创建 ImageClient（隔离子进程 · " + (if (useGpu) "GPU" else "CPU") + "）")
        return try {
            client?.let { runCatching { it.close() } }
            // 注意：必须在主线程创建。llmedge 内部会启动 ValueAnimator，
            // 在无 Looper 的后台线程会抛 “Animators may only be run on Looper threads”。
            client = withContext(Dispatchers.Main) {
                LlmedgeConfigFactory.cpuIsolatedClient(c.applicationContext, scope, useGpu)
            }
            stage("7 ImageClient 创建 OK")

            val q = GgufProbe.quantType(main)
            val summary = buildString {
                append("已就绪：").append(main.name)
                if (q != null) append("（").append(q).append("）")
                if (vae != null) append("  +  ").append(vae.name)
                append(if (useGpu) " · GPU" else " · CPU")
                append(" · 独立进程")
            }
            statusText.post { statusText.text = summary }
            statusText.post { refreshQuantText() }
            runCatching { stageFile.delete() }
            onPipelineReady?.invoke()
            null
        } catch (e: Throwable) {
            stage("7 创建失败：${e.javaClass.name}: ${e.message}")
            // 把完整堆栈落盘，方便定位（尤其是第三方库内部的问题）
            runCatching { stageFile.appendText(android.util.Log.getStackTraceString(e) + "\n") }
            "加载失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun unloadModel() {
        runCatching { client?.close() }
        client = null
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
        client != null -> {
            val q = mainModel?.let { GgufProbe.quantType(it) }
            "已就绪：" + (mainModel?.name ?: "绘图模型") +
                (if (q != null) "（$q）" else "") +
                (if (useGpu) " · GPU" else " · CPU") + " · 独立进程"
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
        genBtn.isEnabled = false
        progressText.visibility = View.VISIBLE
        progressText.text = "正在准备…（首次会先加载模型，1GB+ 可能要几分钟）"
        cancelBtn.visibility = View.VISIBLE
        onStatus?.invoke("绘图生成中…", false)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            // 逐秒报“已耗时”，否则 llmedge 不报中间进度，看着像卡死
            val ticker = launch {
                while (true) {
                    delay(1000)
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    progressText.post {
                        val phase = if (sec < 8) "正在加载模型…" else "正在去噪采样…"
                        progressText.text = "$phase 已 ${sec} 秒\n" +
                            "纯 CPU 推理很慢，请保持前台。若长时间没反应可点「取消」"
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
                resultImg.post {
                    resultImg.setImageBitmap(bmp)
                    resultImg.visibility = View.VISIBLE
                }
                val sec = (System.currentTimeMillis() - startedAt) / 1000
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
                genBtn.post { genBtn.isEnabled = true }
                cancelBtn.post { cancelBtn.visibility = View.GONE }
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
        val dim = run {
            val s = sizeSpinner.selectedItem?.toString() ?: ""
            when {
                s.startsWith("256") -> 256
                s.startsWith("384") -> 384
                else -> 512
            }
        }
        onProgress(0, steps)
        cancelRequested = false

        val cli = client ?: throw IllegalStateException("绘图模型未加载")
        val main = mainModel ?: throw IllegalStateException("未选择绘图模型")

        // LoRA：sd.cpp 约定 —— 在 prompt 里写 `lora:文件名(不含扩展名):权重`
        val lora = if (useLora) activeLora() else null
        val finalPrompt = if (lora != null) "$prompt lora:${lora.nameWithoutExtension}:1" else prompt

        val bmp = cli.generate(
            ImageGenerationRequest(
                prompt = finalPrompt,
                negative = negEdit.text.toString(),
                width = dim,
                height = dim,
                steps = steps,
                cfgScale = cfg,
                seed = useSeed,
                flashAttention = true,       // C 档：打开 FlashAttention（华为/Mali 上可能不稳，失败只报错不闪退）
                model = ModelSpec.localFile(main),
                vae = vaeModel?.let { ModelSpec.localFile(it) },
                loraModelDir = if (lora != null) lora.parentFile?.absolutePath else null,
            )
        )
        onProgress(steps, steps)
        return ImageData(bmp, useSeed)
    }

    fun toBitmap(img: ImageData): Bitmap = img.bitmap

    fun cancel() {
        cancelRequested = true
        runCatching { client?.cancelGeneration() }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean = false

    fun release() {
        runCatching { client?.close() }
        client = null
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
}


/** 生成结果：Bitmap + 尺寸 + 实际使用的种子 */
class ImageData(val bitmap: Bitmap, val seed: Long) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}
