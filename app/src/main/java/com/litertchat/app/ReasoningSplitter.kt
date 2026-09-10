package com.litertchat.app

/**
 * 把 GGUF 模型的流式输出拆成「思考内容」和「正文」两路。
 *
 * 支持的思考标记：
 *  - Gemma 3n / 4：`<|channel>thought` … `<channel|>`
 *  - DeepSeek / Qwen / GLM：` thinking` … `<｜end▁of▁thinking｜>`
 *
 * 标记可能被切分到相邻的 chunk 里（例如 `<chan` + `nel|>`），所以内部保留一个缓冲：
 * 当尾巴看起来像某个标记的前缀时先扣住不发，等下一个 chunk 拼上再判定。
 * 思考标记只可能出现在输出开头（允许前面有空白），开头不是标记就一律按正文处理。
 */
class ReasoningSplitter(
    private val onThought: (String) -> Unit,
    private val onAnswer: (String) -> Unit,
    assumeThinking: Boolean = false,
) {
    private val starts = listOf("<|channel>thought", " thinking")
    private val ends = listOf("<channel|>", "<|channel|>", "<｜end▁of▁thinking｜>")

    /** 0 = 还没确定（等开头的思考标记）；1 = 思考中；2 = 正文中 */
    private var state = if (assumeThinking) 1 else 0
    private val buf = StringBuilder()
    private var totalThought = 0

    private companion object {
        /** 开了思考模式但一直没碰到结束标记时，超过这个长度就当作普通正文，避免误吞。 */
        const val ASSUMED_THINK_LIMIT = 4000
    }

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        buf.append(chunk)
        var again = true
        while (again) {
            again = false
            val s = buf.toString()
            if (s.isEmpty()) return
            when (state) {
                0 -> {
                    val lead = s.length - s.trimStart().length
                    val core = s.substring(lead)
                    val hit = findFirst(core, starts)
                    if (hit != null) {
                        // 标记之前的内容（含前导空白）按正文输出
                        if (lead > 0) onAnswer(s.substring(0, lead))
                        buf.delete(0, lead + hit.first + hit.second.length)
                        state = 1
                        again = true
                    } else if (core.isNotEmpty() && pendingTail(core, starts) == core.length) {
                        // 整段都可能是标记的前缀 → 等更多数据
                    } else {
                        // 开头不是思考标记：以后都按正文
                        onAnswer(s)
                        buf.setLength(0)
                        state = 2
                    }
                }
                1 -> {
                    val hit = findFirst(s, ends)
                    if (hit != null) {
                        if (hit.first > 0) onThought(s.substring(0, hit.first))
                        buf.delete(0, hit.first + hit.second.length)
                        state = 2
                        again = true
                    } else {
                        // 预设的“思考中”一直没有遇到结束标记：超长就当普通正文，避免误吞
                        if (totalThought + s.length > ASSUMED_THINK_LIMIT) {
                            onAnswer(s)
                            buf.setLength(0)
                            state = 2
                        } else {
                            val keep = pendingTail(s, ends)
                            val emit = s.length - keep
                            if (emit > 0) {
                                onThought(s.substring(0, emit))
                                totalThought += emit
                                buf.delete(0, emit)
                            }
                        }
                    }
                }
                else -> {
                    onAnswer(s)
                    buf.setLength(0)
                }
            }
        }
    }

    /** 流结束时把缓冲里剩下的内容冲刷出去（思考段未闭合时算思考）。 */
    fun finish() {
        val s = buf.toString()
        if (s.isEmpty()) return
        if (state == 1) onThought(s) else onAnswer(s)
        buf.setLength(0)
    }

    private fun findFirst(s: String, markers: List<String>): Pair<Int, String>? {
        var bestIndex = -1
        var bestMarker: String? = null
        for (m in markers) {
            val i = s.indexOf(m)
            if (i >= 0 && (bestIndex < 0 || i < bestIndex)) {
                bestIndex = i
                bestMarker = m
            }
        }
        return if (bestMarker != null) bestIndex to bestMarker else null
    }

    /** s 的尾部有多少个字符可能是某个标记的开头（需要先扣住等后续 chunk）。 */
    private fun pendingTail(s: String, markers: List<String>): Int {
        var best = 0
        for (m in markers) {
            var k = minOf(m.length - 1, s.length)
            while (k > best) {
                if (s.regionMatches(s.length - k, m, 0, k)) {
                    best = k
                    break
                }
                k--
            }
        }
        return best
    }
}
