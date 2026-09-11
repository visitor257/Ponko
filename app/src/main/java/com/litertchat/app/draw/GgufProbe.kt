package com.litertchat.app.draw

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * 轻量 GGUF 头部探测：判断一个 .gguf 是语言模型还是画图模型。
 *
 * 依据 GGUF 的元数据键 `general.architecture`：
 *  - llama / qwen2 / gemma / gpt2 / ...  → 语言模型
 *  - sd1 / sd2 / sdxl / sd3 / flux / aura / ltxv / hyvid / ... → 画图（扩散）模型
 *
 * 老版 stable-diffusion.cpp 导出的 GGUF 没有 `general.architecture`，
 * 这时退回扫描张量名：出现 `model.diffusion_model.` / `first_stage_model` / `cond_stage_model`
 * 视为画图模型，出现 `blk.` / `token_embd` / `output.weight` 视为语言模型。
 */
object GgufProbe {

    enum class Kind { LLM, IMAGE, UNKNOWN }

    private val IMAGE_ARCH = setOf(
        "sd1", "sd2", "sdxl", "sd3", "flux", "aura", "ltxv", "hyvid",
        "hidream", "cosmos", "wan", "lumina2", "qwen_image", "chroma", "kontext"
    )

    /** 画图相关张量名前缀（老版 sd.cpp 导出无 architecture 时的兜底） */
    private val IMAGE_TENSOR_HINTS = listOf(
        "model.diffusion_model.", "first_stage_model", "cond_stage_model",
        "vae.", "unet.", "diffusion_model."
    )

    /** 语言模型张量名特征 */
    private val LLM_TENSOR_HINTS = listOf(
        "token_embd", "blk.0.attn_q", "blk.0.attn_norm", "output_norm"
    )

    private const val MAX_HEADER_BYTES = 16L * 1024 * 1024   // 头部上限，防异常文件
    private const val MAX_KV = 4096
    private const val MAX_TENSORS_SCAN = 600

    /** GGUF general.file_type → 可读量化名 */
    private val FT_NAMES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0",
        8 to "Q5_0", 9 to "Q5_1", 10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M",
        13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S",
        17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 30 to "BF16",
    )

    /** 读取 GGUF 量化等级（元数据键 general.file_type）；拿不到返回 null。 */
    fun quantType(file: File): String? = try {
        FileInputStream(file).use { fis ->
            BufferedInputStream(fis, 1 shl 16).use { ins -> parseFileType(ins) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun parseFileType(ins: BufferedInputStream): String? {
        val magic = readBytes(ins, 4) ?: return null
        if (String(magic, Charsets.US_ASCII) != "GGUF") return null
        readU32(ins) ?: return null
        readU64(ins) ?: return null
        val kvCount = readU64(ins) ?: return null
        val kv = kvCount.coerceAtMost(MAX_KV.toLong()).toInt()
        for (i in 0 until kv) {
            val key = readString(ins) ?: return null
            val vt = readU32(ins) ?: return null
            if (key == "general.file_type") {
                val v = when (vt) {
                    4, 5 -> readU32(ins)?.toLong()
                    10 -> readU64(ins)
                    6 -> readU32(ins)?.toLong()   // 兼容故意写成 f32 的导出
                    else -> null
                }
                return FT_NAMES[(v ?: return null).toInt()] ?: "type=${v}"
            }
            if (skipValue(ins, vt, captureString = false) == null) return null
        }
        return null
    }

    /** 探测文件；只读元数据区，不加载张量数据。失败返回 UNKNOWN。 */
    fun probe(file: File): Kind {
        return try {
            FileInputStream(file).use { fis ->
                BufferedInputStream(fis, 1 shl 16).use { ins -> parse(ins) }
            }
        } catch (_: Throwable) {
            Kind.UNKNOWN
        }
    }

    private fun parse(ins: BufferedInputStream): Kind {
        val magic = readBytes(ins, 4)
        if (magic == null || String(magic, Charsets.US_ASCII) != "GGUF") return Kind.UNKNOWN

        val version = readU32(ins) ?: return Kind.UNKNOWN
        readU64(ins) ?: return Kind.UNKNOWN                 // tensor_count
        val kvCount = readU64(ins) ?: return Kind.UNKNOWN   // metadata_kv_count

        var arch: String? = null
        var consumed = 24L

        val kv = kvCount.coerceAtMost(MAX_KV.toLong()).toInt()
        for (i in 0 until kv) {
            val key = readString(ins) ?: return fallback(arch)
            consumed += key.length + 8
            val vt = readU32(ins) ?: return fallback(arch)
            consumed += 4
            val skip = skipValue(ins, vt, captureString = key == "general.architecture")
            if (skip == null) return fallback(arch)
            if (skip.second != null) arch = skip.second
            consumed += skip.first
            if (consumed > MAX_HEADER_BYTES) return fallback(arch)

            if (arch != null) return classify(arch)
        }

        if (arch != null) return classify(arch)

        // 没有 general.architecture：扫张量名兜底
        return scanTensors(ins)
    }

    private fun classify(arch: String): Kind {
        val a = arch.lowercase()
        if (a.isEmpty()) return Kind.UNKNOWN
        if (a in IMAGE_ARCH) return Kind.IMAGE
        if (a.contains("sd") || a.contains("flux") || a.contains("diffusion")) return Kind.IMAGE
        if (a == "clip" || a == "t5" || a == "t5encoder") return Kind.UNKNOWN // 文本编码器，非整模
        return Kind.LLM
    }

    private fun fallback(arch: String?): Kind = arch?.let { classify(it) } ?: Kind.UNKNOWN

    /** KV 读完后扫张量名 */
    private fun scanTensors(ins: BufferedInputStream): Kind {
        var image = 0
        var llm = 0
        for (i in 0 until MAX_TENSORS_SCAN) {
            val name = readString(ins) ?: break
            if (IMAGE_TENSOR_HINTS.any { name.startsWith(it) }) image++
            if (LLM_TENSOR_HINTS.any { name.startsWith(it) }) llm++
            if (image > 0) return Kind.IMAGE
            if (llm >= 3) return Kind.LLM
        }
        return when {
            image > llm -> Kind.IMAGE
            llm > image -> Kind.LLM
            else -> Kind.UNKNOWN
        }
    }

    /** 跳过 KV 值；返回 (消费字节数, 若为字符串则返回其内容) */
    private fun skipValue(ins: BufferedInputStream, vtype: Int, captureString: Boolean): Pair<Long, String?>? {
        return when (vtype) {
            0, 1, 7 -> { readBytes(ins, 1); 1L to null }
            2, 3 -> { readBytes(ins, 2); 2L to null }
            4, 5, 6 -> { readBytes(ins, 4); 4L to null }
            10, 11, 12 -> { readBytes(ins, 8); 8L to null }
            8 -> {
                val s = readString(ins) ?: return null
                (8L + s.length) to if (captureString) s else null
            }
            9 -> {
                val elemType = readU32(ins) ?: return null
                val count = readU64(ins) ?: return null
                val elemSize = when (elemType) {
                    0, 1, 7 -> 1L
                    2, 3 -> 2L
                    4, 5, 6 -> 4L
                    10, 11, 12 -> 8L
                    8 -> -1L      // 字符串数组，元素变长
                    else -> return null
                }
                var bytes = 4L + 8L
                if (elemSize > 0) {
                    val total = elemSize * count
                    if (total > MAX_HEADER_BYTES) return null
                    skipBytes(ins, total)
                    bytes += total
                } else {
                    for (k in 0 until count.coerceAtMost(4096L).toInt()) {
                        val s = readString(ins) ?: return null
                        bytes += 8L + s.length
                    }
                }
                bytes to null
            }
            else -> null
        }
    }

    // ---------- 基础读取（小端） ----------

    private fun readBytes(ins: BufferedInputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun skipBytes(ins: BufferedInputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 14)
        while (left > 0) {
            val want = left.coerceAtMost(buf.size.toLong()).toInt()
            val r = ins.read(buf, 0, want)
            if (r < 0) return
            left -= r
        }
    }

    private fun readU32(ins: BufferedInputStream): Int? {
        val b = readBytes(ins, 4) ?: return null
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readU64(ins: BufferedInputStream): Long? {
        val b = readBytes(ins, 8) ?: return null
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun readString(ins: BufferedInputStream): String? {
        val len = readU64(ins) ?: return null
        if (len < 0 || len > (1 shl 20)) return null
        val b = readBytes(ins, len.toInt()) ?: return null
        return String(b, Charsets.UTF_8)
    }
}
