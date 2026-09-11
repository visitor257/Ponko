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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 绘图页（文生图）。
 *
 * 模型：stable-diffusion.cpp 的 **GGUF** 绘图模型（Anything V5 / SD1.5 等），
 * 由 llmedge 内置的 libsdcpp.so 直接推理（无需 NDK 自行编译）。
 *
 * 模型统一在「模型」页选择：选择目录后把 .gguf（+ 可选的 vae/分离 clip）复制到应用私有目录。
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
    }

    private val c: Context get() = act

    private var client: ImageClient? = null
    private var mainModel: File? = null
    private var vaeModel: File? = null

    /** 外部（MainActivity）通知：当前已加载语言模型。用于“生成”按钮给出更准确的提示。 */
    var llmLoaded: Boolean = false

    /** 运行方式：true = 尝试 GPU（Vulkan），false = 纯 CPU。由模型页的“运行方式”决定。 */
    var useGpu: Boolean = false

    /** SD 管线就绪时回调（MainActivity 借此切到绘图模式） */
    var onPipelineReady: (() -> Unit)? = null

    /** 是否已就绪（可生成） */
    fun isReady(): Boolean = client != null

    // 控件
    private lateinit var statusText: TextView
    private lateinit var promptEdit: EditText
    private lateinit var negEdit: EditText
    private lateinit var stepsEdit: EditText
    private lateinit var cfgEdit: EditText
    private lateinit var seedEdit: EditText
    private lateinit var genBtn: Button
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
        paramCard.addView(paramRow("采样步数", stepsEdit, "越大越精细，也越慢（20 起步）"))
        paramCard.addView(paramRow("CFG 引导", cfgEdit, "贴合提示词的程度，7 左右常用"))
        paramCard.addView(paramRow("随机种子", seedEdit, "-1 = 每次随机；固定值可复现同一张图"))
        paramCard.addView(smallLabel("尺寸固定 512×512（SD1.5 原生分辨率）"))
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

        return root
    }

    // ================= 模型（由「模型」页驱动） =================

    /** 从 SAF 目录导入绘图模型：递归收集 .gguf，复制到私有目录后加载。返回 null 表示成功，否则为错误文案。 */
    suspend fun prepareFromTree(treeUri: Uri, onStage: (String) -> Unit): String? {
        return withContext(Dispatchers.IO) {
            try {
                onStage("正在扫描所选文件夹…")
                val root = File(c.filesDir, DIR_NAME).apply { mkdirs() }
                val found = ArrayList<Pair<String, Uri>>() // name -> uri

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
                        if (name.endsWith(".gguf", true)) {
                            found.add(name to android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId))
                        } else if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(childId, depth + 1)
                        }
                    }
                }

                walk(android.provider.DocumentsContract.getTreeDocumentId(treeUri), 0)

                if (found.isEmpty()) return@withContext "所选文件夹里没找到 .gguf 绘图模型"

                var copied = 0
                for ((name, uri) in found) {
                    onStage("正在复制 $name…")
                    try {
                        c.contentResolver.openInputStream(uri)?.use { ins ->
                            File(root, name).outputStream().use { outs -> ins.copyTo(outs, 1 shl 20) }
                        }
                        copied++
                    } catch (_: Throwable) {
                        // 单个文件失败不阻断（可能是已存在的同名文件被占用）
                    }
                }
                if (copied == 0) return@withContext "复制模型失败（0 个文件）"

                // 只复制、不在这里加载：native 加载可能崩（实测），
                // 留给用户在「模型」页手动点「加载绘图模型」，崩了也不会连累启动。
                val names = found.joinToString("、") { it.first }
                statusText.post { statusText.text = "已复制：$names（点「模型」页的「加载绘图模型」开始）" }
                "已复制 $copied 个模型文件，请点「加载绘图模型」"
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

    /** 删除单个已复制的模型文件 */
    fun deleteModel(f: File): Boolean = runCatching { f.delete() }.getOrDefault(false)

    private suspend fun loadFromPrivateDir(preferred: File? = null): String? {
        val root = File(c.filesDir, DIR_NAME)
        val ggufs = root.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }?.toList().orEmpty()
        if (ggufs.isEmpty()) {
            statusText.post { statusText.text = "未加载 —— 私有目录里没有 .gguf 绘图模型" }
            return "私有目录里没有 .gguf 绘图模型"
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
        stage("0 选中模型：${main.name}（${main.length() / 1048576} MB）")
        if (vae != null) stage("0 VAE：${vae.name}（${vae.length() / 1048576} MB）")
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

            val summary = buildString {
                append("已就绪：").append(main.name)
                if (vae != null) append("  +  ").append(vae.name)
                append(if (useGpu) "（GPU · 独立进程）" else "（CPU · 独立进程）")
            }
            statusText.post { statusText.text = summary }
            runCatching { stageFile.delete() }
            onPipelineReady?.invoke()
            null
        } catch (e: Throwable) {
            stage("7 创建失败：${e.message ?: e.javaClass.simpleName}")
            "加载失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun unloadModel() {
        runCatching { client?.close() }
        client = null
        try {
            statusText.text = "未加载 —— 请到「模型」页的「绘图模型」里选择模型文件夹"
        } catch (_: Throwable) {}
    }

    fun hasModel(): Boolean {
        val root = File(c.filesDir, DIR_NAME)
        return root.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }?.isNotEmpty() == true
    }

    fun modelSummary(): String = when {
        client != null -> "已就绪：" + (mainModel?.name ?: "绘图模型") +
            (if (useGpu) "（GPU · 独立进程）" else "（CPU · 独立进程）")
        hasModel() -> "已复制模型，点「加载绘图模型」开始"
        else -> "未加载 —— 请到「模型」页的「绘图模型」里选择模型文件夹"
    }

    fun refreshStatus() {
        try { statusText.text = modelSummary() } catch (_: Throwable) {}
    }

    // ================= 生成 =================

    private fun generateFromUi() {
        val dpg = this
        if (client == null) {
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
        progressText.text = "正在生成…（纯 CPU，512×512 可能要几分钟）"
        scope.launch {
            try {
                val img = generateImage(prompt) { cur, total ->
                    progressText.post { progressText.text = "正在生成… $cur/$total" }
                }
                val bmp = toBitmap(img)
                resultImg.post {
                    resultImg.setImageBitmap(bmp)
                    resultImg.visibility = View.VISIBLE
                }
                progressText.post { progressText.text = "完成：${img.width}×${img.height}，seed=${img.seed}" }
            } catch (e: Throwable) {
                progressText.post { progressText.text = "生成失败：${e.message ?: e.javaClass.simpleName}" }
            } finally {
                genBtn.post { genBtn.isEnabled = true }
            }
        }
        // 让编译器闭嘴（dpg 未使用）
        if (false) println(dpg)
    }

    /** 供对话页复用的生成入口：把输入当正面提示词，其余参数取绘图页当前设置。 */
    suspend fun generateImage(
        prompt: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImageData {
        // 不包 withContext：llmedge 内部会自己切线程（且需要 Looper 的地方它用主线程），
        // 我们包 IO 反而会让它内部的 ValueAnimator 报“Animators may only be run on Looper threads”。
        val cli = client ?: throw IllegalStateException("绘图模型未加载")
        val main = mainModel ?: throw IllegalStateException("未选择绘图模型")
        val steps = stepsEdit.text.toString().toIntOrNull()?.coerceIn(1, 150) ?: 20
        val cfg = cfgEdit.text.toString().toFloatOrNull()?.coerceIn(1f, 30f) ?: 7.0f
        val seed = seedEdit.text.toString().toLongOrNull() ?: -1L
        val useSeed = if (seed < 0) System.currentTimeMillis() else seed

        onProgress(0, steps)
        val bmp = cli.generate(
            ImageGenerationRequest(
                prompt = prompt,
                negative = negEdit.text.toString(),
                width = 512,
                height = 512,
                steps = steps,
                cfgScale = cfg,
                seed = useSeed,
                flashAttention = false,      // 华为/Mali 上 FlashAttention 常出问题
                model = ModelSpec.localFile(main),
                vae = vaeModel?.let { ModelSpec.localFile(it) },
            )
        )
        onProgress(steps, steps)
        return ImageData(bmp, useSeed)
    }

    fun toBitmap(img: ImageData): Bitmap = img.bitmap

    fun cancel() {
        runCatching { client?.cancelGeneration() }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean = false

    fun release() {
        runCatching { client?.close() }
        client = null
    }

    // ================= UI 小工具 =================

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

    private fun paramRow(label: String, input: EditText, hint: String): View {
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
