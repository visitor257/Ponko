package com.litertchat.app.draw

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 绘图页（文生图 / 图生图）。
 *
 * 模型要求：SD1.5 的 ONNX 导出（text_encoder / unet / vae_decoder [/ vae_encoder] + tokenizer）。
 * 与聊天模型一样，ONNX Runtime 需要真实文件路径，所以选择目录后会整体复制到应用私有目录。
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
        private const val REQ_TREE = 0x5D01
        private const val REQ_IMAGE = 0x5D02
    }

    private val c: Context get() = act

    private var pipeline: SdPipeline? = null
    private var modelSet: SdModelSet? = null
    private var img2imgMode = false
    private var inputBitmap: Bitmap? = null

    // 控件
    private lateinit var statusText: TextView
    private lateinit var modelBtn: Button
    private lateinit var copyBar: ProgressBar
    private lateinit var modeTxt2Img: TextView
    private lateinit var modeImg2Img: TextView
    private lateinit var pickImgBtn: Button
    private lateinit var imgThumb: ImageView
    private lateinit var promptEdit: EditText
    private lateinit var negEdit: EditText
    private lateinit var stepsEdit: EditText
    private lateinit var cfgEdit: EditText
    private lateinit var sizeRow: LinearLayout
    private lateinit var seedEdit: EditText
    private lateinit var strengthEdit: EditText
    private lateinit var strengthLabel: LinearLayout
    private lateinit var genBtn: Button
    private lateinit var progressText: TextView
    private lateinit var resultImg: ImageView

    private var size = 512

    fun build(): View {
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFF5F6F8.toInt())
        }

        // ---- 模型卡片 ----
        val modelCard = card()
        modelCard.addView(title("绘图模型（SD 1.5 · ONNX）"))
        statusText = body("未选择模型 —— 点下面按钮选择包含 ONNX 模型的文件夹")
        modelCard.addView(statusText)
        modelBtn = Button(c).apply {
            text = "选择模型文件夹"
            setOnClickListener { pickModelTree() }
        }
        modelCard.addView(modelBtn, matchWrap(top = 8))
        copyBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        modelCard.addView(copyBar, matchWrap(top = 8))
        root.addView(modelCard)

        // ---- 模式 ----
        val modeCard = card()
        modeCard.addView(title("模式"))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        modeTxt2Img = modeChip("文生图", true)
        modeImg2Img = modeChip("图生图", false)
        modeTxt2Img.setOnClickListener { setMode(false) }
        modeImg2Img.setOnClickListener { setMode(true) }
        row.addView(modeTxt2Img, weight())
        row.addView(modeImg2Img, weight())
        modeCard.addView(row, matchWrap(top = 6))

        pickImgBtn = Button(c).apply {
            text = "选择参考图片"
            visibility = View.GONE
            setOnClickListener { pickInputImage() }
        }
        imgThumb = ImageView(c).apply {
            adjustViewBounds = true
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        modeCard.addView(pickImgBtn, matchWrap(top = 8))
        modeCard.addView(imgThumb, matchWrap())
        root.addView(modeCard)

        // ---- 提示词 ----
        val promptCard = card()
        promptCard.addView(title("提示词"))
        promptEdit = edit("a cute anime girl, masterpiece, best quality", 3)
        negEdit = edit("lowres, bad anatomy, extra fingers, watermark", 2)
        promptCard.addView(label("正向"))
        promptCard.addView(promptEdit)
        promptCard.addView(label("负向"))
        promptCard.addView(negEdit)
        root.addView(promptCard)

        // ---- 参数 ----
        val paramCard = card()
        paramCard.addView(title("参数"))
        sizeRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in intArrayOf(256, 384, 512)) {
            val chip = modeChip("${s}×${s}", s == size)
            chip.setOnClickListener { size = s; refreshSizeChips() }
            sizeRow.addView(chip, weight())
        }
        paramCard.addView(label("尺寸"))
        paramCard.addView(sizeRow, matchWrap(top = 4))

        val p2 = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        stepsEdit = numEdit("20")
        cfgEdit = numEdit("7.5")
        p2.addView(labeledEdit("步数", stepsEdit), weight())
        p2.addView(labeledEdit("CFG", cfgEdit), weight())
        paramCard.addView(p2, matchWrap(top = 6))

        val p3 = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        seedEdit = numEdit("-1")
        strengthEdit = numEdit("0.75")
        strengthLabel = labeledEdit("重绘强度", strengthEdit)
        strengthLabel.visibility = View.GONE
        p3.addView(labeledEdit("种子(-1随机)", seedEdit), weight())
        p3.addView(strengthLabel, weight())
        paramCard.addView(p3, matchWrap(top = 6))
        root.addView(paramCard)

        // ---- 生成 ----
        genBtn = Button(c).apply {
            text = "生成"
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(primary)
            setTextColor(Color.WHITE)
            setOnClickListener { generate() }
        }
        root.addView(genBtn, matchWrap(top = 4))
        progressText = body("")
        root.addView(progressText)
        resultImg = ImageView(c).apply {
            adjustViewBounds = true
            setPadding(0, dp(10), 0, dp(24))
        }
        root.addView(resultImg, matchWrap())

        // 已有可用模型则直接加载
        scope.launch { tryLoadExisting() }
        return root
    }

    fun release() {
        runCatching { pipeline?.close() }
        pipeline = null
    }

    // ---------- 模型选择与复制 ----------

    private fun pickModelTree() {
        val it = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        act.startActivityForResult(it, REQ_TREE)
    }

    private fun pickInputImage() {
        val it = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        act.startActivityForResult(it, REQ_IMAGE)
    }

    /** 由 Activity 转发 onActivityResult */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) return false
        when (requestCode) {
            REQ_TREE -> {
                val uri = data?.data ?: return true
                importModelTree(uri)
                return true
            }
            REQ_IMAGE -> {
                val uri = data?.data ?: return true
                loadInputImage(uri)
                return true
            }
        }
        return false
    }

    /** 把 SAF 目录整体复制到 filesDir/draw/ ，然后加载 */
    private fun importModelTree(treeUri: Uri) {
        scope.launch {
            copyBar.visibility = View.VISIBLE
            progressText.text = "正在复制模型文件…"
            try {
                val dest = File(c.filesDir, DIR_NAME)
                withContext(Dispatchers.IO) { copyTree(treeUri, dest) }
                progressText.text = "复制完成，正在查找模型…"
                tryLoadExisting()
            } catch (e: Throwable) {
                progressText.text = ""
                toast("复制失败：${e.message}")
            } finally {
                copyBar.visibility = View.GONE
            }
        }
    }

    private fun copyTree(src: Uri, dest: File) {
        // 只复制可能用到的文件，避免把整目录的无用内容也搬进来
        val wanted = setOf(
            "model.onnx", "vocab.json", "merges.txt", "scheduler_config.json", "config.json"
        )
        val name = queryName(src) ?: "model"
        val base = File(dest, sanitize(name))
        base.mkdirs()
        copyTreeRecursive(src, base, wanted, 0)
    }

    private fun copyTreeRecursive(dirUri: Uri, dest: File, wanted: Set<String>, depth: Int) {
        if (depth > 3) return
        val children = listChildren(dirUri)
        for (child in children) {
            val name = queryName(child) ?: continue
            if (isDirectory(child)) {
                val sub = File(dest, sanitize(name))
                sub.mkdirs()
                copyTreeRecursive(child, sub, wanted, depth + 1)
            } else if (name in wanted) {
                val out = File(dest, sanitize(name))
                if (out.exists() && out.length() > 0) continue      // 幂等
                c.contentResolver.openInputStream(child)?.use { input ->
                    out.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
        }
    }

    private fun listChildren(treeUri: Uri): List<Uri> {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<Uri>()
        c.contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0)
                out.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            }
        }
        return out
    }

    private fun isDirectory(uri: Uri): Boolean =
        c.contentResolver.getType(uri) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR

    private fun queryName(uri: Uri): String? =
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun sanitize(n: String): String = n.replace(Regex("[^A-Za-z0-9._-]"), "_")

    // ---------- 加载模型 ----------

    private suspend fun tryLoadExisting() {
        val root = File(c.filesDir, DIR_NAME)
        val found = withContext(Dispatchers.IO) { SdModelSet.find(root) }
        if (found == null) {
            statusText.text = "未找到可用模型（需要 text_encoder/unet/vae_decoder + tokenizer）"
            return
        }
        val err = found.validate()
        if (err != null) {
            statusText.text = "模型不完整：$err"
            return
        }
        statusText.text = "正在加载模型…"
        try {
            val p = withContext(Dispatchers.IO) {
                val np = SdPipeline(found)
                np
            }
            pipeline?.let { runCatching { it.close() } }
            pipeline = p
            modelSet = found
            statusText.text = "已就绪：SD 1.5" + if (found.canImg2Img) "（支持图生图）" else "（仅文生图，缺 vae_encoder）"
        } catch (e: Throwable) {
            statusText.text = "加载失败：${e.message}"
        }
    }

    // ---------- 生成 ----------

    private fun generate() {
        val pipe = pipeline ?: run { toast("请先选择并加载绘图模型"); return }
        val prompt = promptEdit.text.toString().trim()
        if (prompt.isEmpty()) { toast("请输入提示词"); return }
        if (img2imgMode && inputBitmap == null) { toast("图生图需要先选择参考图片"); return }

        val steps = stepsEdit.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 20
        val cfg = cfgEdit.text.toString().toFloatOrNull()?.coerceIn(1f, 20f) ?: 7.5f
        val seed = seedEdit.text.toString().toLongOrNull() ?: -1L
        val strength = strengthEdit.text.toString().toFloatOrNull()?.coerceIn(0.05f, 1f) ?: 0.75f

        val params = SdPipeline.Params(
            prompt = prompt,
            negative = negEdit.text.toString().trim(),
            steps = steps,
            cfgScale = cfg,
            width = size,
            height = size,
            seed = seed,
            strength = strength,
        )

        genBtn.isEnabled = false
        genBtn.text = "生成中…"
        progressText.text = "准备中…"
        val start = System.currentTimeMillis()

        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val cb: (Int, Int) -> Unit = { cur, total ->
                        act.runOnUiThread { progressText.text = "去噪 $cur/$total" }
                    }
                    if (img2imgMode) {
                        val bmp = inputBitmap!!
                        val scaled = Bitmap.createScaledBitmap(bmp, size, size, true)
                        val px = IntArray(size * size)
                        scaled.getPixels(px, 0, size, 0, 0, size, size)
                        val rgb = ByteArray(size * size * 3)
                        for (i in px.indices) {
                            rgb[i * 3] = ((px[i] shr 16) and 0xFF).toByte()
                            rgb[i * 3 + 1] = ((px[i] shr 8) and 0xFF).toByte()
                            rgb[i * 3 + 2] = (px[i] and 0xFF).toByte()
                        }
                        pipe.img2img(params, ImageData(rgb, size, size), cb)
                    } else {
                        pipe.txt2img(params, cb)
                    }
                }
                val bmp = withContext(Dispatchers.Default) { toBitmap(result) }
                resultImg.setImageBitmap(bmp)
                val sec = (System.currentTimeMillis() - start) / 1000.0
                progressText.text = "完成 · %.1f 秒 · seed=%d".format(sec, result.seed)
            } catch (e: Throwable) {
                progressText.text = "生成失败：${e.message}"
                toast("生成失败：${e.message}")
            } finally {
                genBtn.isEnabled = true
                genBtn.text = "生成"
            }
        }
    }

    private fun toBitmap(img: ImageData): Bitmap {
        val px = IntArray(img.width * img.height)
        for (i in px.indices) {
            val r = img.data[i * 3].toInt() and 0xFF
            val g = img.data[i * 3 + 1].toInt() and 0xFF
            val b = img.data[i * 3 + 2].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
    }

    private fun loadInputImage(uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            if (bmp == null) { toast("读取图片失败"); return@launch }
            inputBitmap = bmp
            imgThumb.setImageBitmap(bmp)
            imgThumb.visibility = View.VISIBLE
        }
    }

    // ---------- 小控件 ----------

    private fun setMode(img2img: Boolean) {
        img2imgMode = img2img
        modeTxt2Img.setTextColor(if (img2img) subText else primary)
        modeImg2Img.setTextColor(if (img2img) primary else subText)
        modeTxt2Img.setBackgroundColor(if (img2img) Color.WHITE else 0x14000000)
        modeImg2Img.setBackgroundColor(if (img2img) 0x14000000 else Color.WHITE)
        pickImgBtn.visibility = if (img2img) View.VISIBLE else View.GONE
        imgThumb.visibility = if (img2img && inputBitmap != null) View.VISIBLE else View.GONE
        strengthLabel.visibility = if (img2img) View.VISIBLE else View.GONE
    }

    private fun refreshSizeChips() {
        for (i in 0 until sizeRow.childCount) {
            val v = sizeRow.getChildAt(i) as TextView
            val s = intArrayOf(256, 384, 512)[i]
            v.setTextColor(if (s == size) primary else subText)
        }
    }

    private fun modeChip(text: String, active: Boolean): TextView = TextView(c).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 13.5f
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setTextColor(if (active) primary else subText)
        setBackgroundColor(if (active) Color.WHITE else 0x14000000)
    }

    private fun card(): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        layoutParams = lp
    }

    private fun title(t: String): TextView = TextView(c).apply {
        text = t
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor)
    }

    private fun body(t: String): TextView = TextView(c).apply {
        text = t
        textSize = 12.5f
        setTextColor(subText)
        setPadding(0, dp(4), 0, 0)
    }

    private fun label(t: String): TextView = TextView(c).apply {
        text = t
        textSize = 12f
        setTextColor(subText)
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun edit(hintText: String, lines: Int): EditText = EditText(c).apply {
        hint = hintText
        textSize = 13.5f
        setTextColor(textColor)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(0x0A000000)
        minLines = lines
    }

    private fun numEdit(v: String): EditText = EditText(c).apply {
        setText(v)
        textSize = 13.5f
        setTextColor(textColor)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(0x0A000000)
    }

    private fun labeledEdit(name: String, e: EditText): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(label(name))
            addView(e)
        }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    private fun toast(m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()
}
