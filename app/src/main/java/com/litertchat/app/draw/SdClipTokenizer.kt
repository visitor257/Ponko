package com.litertchat.app.draw

import org.json.JSONObject
import java.io.File

/**
 * CLIP BPE 分词器 —— 与 HuggingFace `CLIPTokenizer` 行为对齐。
 *
 * SD1.5 的文本编码器只吃 token id，而分词一旦和目标实现有偏差，出图就会明显跑偏，
 * 所以这里严格照搬 openai/clip 的 tokenization_clip.py 流程：
 *   1. whitespace_clean + lowercase
 *   2. 用 CLIP 的正则切词（含 's / 't 这类缩写的特殊处理）
 *   3. 每个词先转成 UTF-8 字节，再映射到 "byte→unicode" 字符表（bytes_to_unicode）
 *   4. 在这串字符上跑 BPE 合并
 *   5. 查 vocab 得到 id，前后包 BOS/EOS，补齐到 77
 *
 * 需要模型目录下的 tokenizer/vocab.json 与 tokenizer/merges.txt。
 */
class SdClipTokenizer(vocabFile: File, mergesFile: File) {

    companion object {
        const val BOS = 49406   // <|startoftext|>
        const val EOS = 49407   // <|endoftext|>
        const val MAX_LEN = 77

        /** CLIP 的切词正则（与 openai/clip 保持一致） */
        private val PAT = Regex(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            RegexOption.IGNORE_CASE
        )

        /** bytes_to_unicode：0..255 字节 → 可打印 unicode 字符（避免空白/控制符被吞） */
        private val BYTE_ENCODER: Map<Int, String> by lazy {
            val bs = ArrayList<Int>()
            for (c in '!'.code..'~'.code) bs.add(c)
            for (c in 0xA1..0xAC) bs.add(c)
            for (c in 0xAE..0xFF) bs.add(c)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            bs.indices.associate { bs[it] to cs[it].toChar().toString() }
        }

        /** 文本预处理：合并空白并转小写 */
        fun clean(text: String): String = text.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private val encoder = HashMap<String, Int>()
    private val bpeRanks = HashMap<Pair<String, String>, Int>()
    private val cache = HashMap<String, List<String>>()

    init {
        val v = JSONObject(vocabFile.readText(Charsets.UTF_8))
        for (k in v.keys()) encoder[k] = v.optInt(k)
        var rank = 0
        for (line in mergesFile.readLines(Charsets.UTF_8)) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue          // 跳过版本头
            val p = t.split(" ")
            if (p.size == 2) bpeRanks[Pair(p[0], p[1])] = rank++
        }
        require(encoder.isNotEmpty()) { "vocab.json 为空：$vocabFile" }
    }

    /** 编码为固定长度 77 的 id 数组（BOS + 正文 + EOS + 0 填充），可直接喂 text_encoder */
    fun encode(text: String): IntArray {
        val out = IntArray(MAX_LEN)
        out[0] = BOS
        var pos = 1
        for (m in PAT.findAll(clean(text))) {
            for (piece in bpe(byteEncode(m.value))) {
                val id = encoder[piece] ?: continue
                if (pos >= MAX_LEN - 1) break
                out[pos++] = id
            }
            if (pos >= MAX_LEN - 1) break
        }
        out[pos] = EOS
        return out
    }

    /** 把一段文本转成 byte→unicode 字符序列 */
    private fun byteEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            sb.append(BYTE_ENCODER[b.toInt() and 0xFF] ?: "?")
        }
        return sb.toString()
    }

    /** 对 byte→unicode 字符序列做 BPE 合并，返回合并后的符号列表 */
    private fun bpe(token: String): List<String> = cache.getOrPut(token) {
        var word = token.map { it.toString() }.toMutableList()
        if (word.size <= 1) return@getOrPut word
        while (true) {
            var bestRank = Int.MAX_VALUE
            var best: Pair<String, String>? = null
            for (i in 0 until word.size - 1) {
                val r = bpeRanks[Pair(word[i], word[i + 1])] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    best = Pair(word[i], word[i + 1])
                }
            }
            val bp = best ?: break
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == bp.first && word[i + 1] == bp.second) {
                    merged.add(bp.first + bp.second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i++
                }
            }
            word = merged
            if (word.size == 1) break
        }
        word
    }
}
