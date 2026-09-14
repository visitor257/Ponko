package com.litertchat.app.draw

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.nio.FloatBuffer

/**
 * 图像打标（Tagger）引擎。
 *
 * 用 **ONNX Runtime** 跑用户自己导入的 Danbooru 系 `.onnx` 打标模型（如 WD14，配 `selected_tags.csv`），
 * 把一张图反推成 Danbooru 风格的 tag（`1girl, solo, ...`），正好是 SD1.5 / SDXL 动漫模型吃的提示词。
 *
 * 与 sd.cpp 无关：模型不内置、不下载，只读用户导入的本地文件。
 * 运行后端跟随「模型」页的运行方式：CPU = XNNPACK/CPU EP；GPU = NNAPI。
 */
object TaggerEngine {

    /** 标签表里的一行：名字 + 类别（0=general，4=character，9=rating，打标结果里跳过 9） */
    data class Tag(val name: String, val category: Int)

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tags: List<Tag> = emptyList()
    private var inputName: String = ""
    private var inSize: Int = 448
    private var nhwc: Boolean = true
    private var modelFile: File? = null
    private var csvFile: File? = null
    private var usingGpu: Boolean = false

    fun isLoaded(): Boolean = session != null
    fun loadedWithGpu(): Boolean = usingGpu
    fun tagCount(): Int = tags.size
    fun fileNames(): Pair<String?, String?> = modelFile?.name to csvFile?.name
    fun backendLabel(): String = if (usingGpu) "NNAPI" else "CPU"

    /** 解析 selected_tags.csv：`tag_id,name,category,count`，行序 = 模型输出下标 */
    fun parseCsv(file: File): List<Tag> {
        val out = ArrayList<Tag>()
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                val parts = line.split(',')
                if (parts.size < 3) return@forEachLine
                val name = parts[1].trim()
                val cat = parts[2].trim().toIntOrNull() ?: 0
                if (name.isEmpty() || name.equals("name", true)) return@forEachLine
                out.add(Tag(name, cat))
            }
        }
        return out
    }

    /**
     * 加载模型 + 标签表。返回 null = 成功，否则为错误文案。
     * 可能耗时（几秒），务必在 IO 线程调用。
     */
    fun load(model: File, csv: File, useGpu: Boolean, threads: Int): String? {
        return try {
            unload()
            val tagList = parseCsv(csv)
            if (tagList.isEmpty()) return "标签表为空或格式不对"

            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            runCatching { opts.setIntraOpNumThreads(threads.coerceIn(1, 8)) }
            var gpuOk = false
            if (useGpu) {
                gpuOk = runCatching { opts.addNnapi(); true }.getOrDefault(false)
            }

            val s = e.createSession(model.absolutePath, opts)
            val inputEntry = s.inputInfo.entries.firstOrNull()
            if (inputEntry == null) {
                runCatching { s.close() }
                return "模型没有输入节点"
            }
            inputName = inputEntry.key
            val shape = (inputEntry.value.info as? TensorInfo)?.shape
            if (shape != null && shape.size == 4) {
                nhwc = shape[3] == 3L
                inSize = when {
                    nhwc -> shape[1].toInt()
                    shape[1] == 3L -> shape[2].toInt()
                    else -> shape[1].toInt()
                }
            }
            if (inSize <= 0) inSize = 448

            env = e
            session = s
            tags = tagList
            modelFile = model
            csvFile = csv
            usingGpu = gpuOk
            null
        } catch (t: Throwable) {
            unload()
            t.message ?: t.javaClass.simpleName
        }
    }

    fun unload() {
        runCatching { session?.close() }
        session = null
        modelFile = null
        csvFile = null
        tags = emptyList()
        usingGpu = false
    }

    /**
     * 打标。返回按分数降序的 (tag, 分数) 列表；threshold 以下被丢弃，最多 topK 个（<=0 表示不限）。
     *
     * rgbOrder = false 用 BGR（WD 系官方预处理，默认），true 用 RGB（少数重导出模型可能已经
     * 把 BGR 转换烘进模型，那种情况需切成 RGB）。
     */
    fun run(bitmap: Bitmap, threshold: Float, topK: Int, rgbOrder: Boolean = false): List<Pair<String, Float>> {
        val s = session ?: return emptyList()
        val e = env ?: return emptyList()
        val n = inSize
        val buf = preprocess(bitmap, n, rgbOrder)
        val shape = if (nhwc) {
            longArrayOf(1, n.toLong(), n.toLong(), 3)
        } else {
            longArrayOf(1, 3, n.toLong(), n.toLong())
        }
        val tensor = OnnxTensor.createTensor(e, buf, shape)
        val result = s.run(mapOf(inputName to tensor))
        try {
            val out = result.get(0) as OnnxTensor
            val fb = out.floatBuffer
            val raw = FloatArray(fb.remaining())
            fb.get(raw)

            // 输出可能是原始 logits（有负值/大于 1），也可能已经是概率 —— 自动判断
            val needSigmoid = raw.any { it < 0f || it > 1f }
            val limit = minOf(raw.size, tags.size)
            val list = ArrayList<Pair<String, Float>>()
            for (i in 0 until limit) {
                val t = tags[i]
                if (t.category == 9) continue // 跳过分级（rating）标签
                val v = if (needSigmoid) sigmoid(raw[i]) else raw[i]
                if (v >= threshold) list.add(t.name to v)
            }
            list.sortByDescending { it.second }
            return if (topK in 1 until list.size) list.subList(0, topK).toList() else list
        } finally {
            runCatching { result.close() }
            runCatching { tensor.close() }
        }
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + kotlin.math.exp(-x))

    /**
     * 预处理：白底居中 letterbox → n×n → **BGR、0~255**。
     *
     * 关键：WD 系模型（WD14 等）吃的是 **0~255 的原始像素值**，绝不能除以 255 —— 除了之后模型会把
     * 彩色图当成低对比度的怪图，稳定输出 monochrome / greyscale / no_humans 这类错误标签。
     * 颜色通道按 BGR 排列（与训练一致）。nhwc=true 输出交错排列，否则按平面（NCHW）排列。
     */
    private fun preprocess(src: Bitmap, n: Int, rgbOrder: Boolean): FloatBuffer {
        val scale = n.toFloat() / maxOf(src.width, src.height)
        val dw = maxOf(1, (src.width * scale).toInt())
        val dh = maxOf(1, (src.height * scale).toInt())
        val scaled = if (dw == src.width && dh == src.height) src
                     else Bitmap.createScaledBitmap(src, dw, dh, true)
        val square = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, (n - dw) / 2f, (n - dh) / 2f, null)

        val px = IntArray(n * n)
        square.getPixels(px, 0, n, 0, 0, n, n)
        val buf = FloatBuffer.allocate(n * n * 3)
        // BGR（默认）：B,G,R；RGB：R,G,B。shift 依次对应当前顺序的三个通道
        val shifts = if (rgbOrder) intArrayOf(16, 8, 0) else intArrayOf(0, 8, 16)
        if (nhwc) {
            for (p in px) {
                for (sh in shifts) buf.put(((p shr sh) and 0xFF).toFloat())
            }
        } else {
            for (sh in shifts) {                              // 三个平面
                for (p in px) buf.put(((p shr sh) and 0xFF).toFloat())
            }
        }
        buf.rewind()
        if (scaled !== src) runCatching { scaled.recycle() }
        runCatching { square.recycle() }
        return buf
    }
}
