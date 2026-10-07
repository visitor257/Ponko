package com.litertchat.app

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.value.Pair as LlamaPair

/**
 * 对话引擎的**共享入口**：App 界面与「对话 API」服务都从这里拿模型、跑生成。
 *
 * 为什么要有这一层：模型原本是 MainActivity 的私有字段，activity 一销毁就被 close；
 * 而 API 服务要在 App 退到后台后继续服务，所以模型必须挂在进程级单例上。
 * MainActivity 加载/卸载模型时只是把引用登记进来（[attachLiteRt] / [attachGguf] / [release]），
 * 其他（视图重建、主题切换）都不影响这里。
 *
 * 串行化：本地模型一次只能跑一个请求（llama.cpp 的 KV slot、LiteRT 的会话都不是线程安全的），
 * 所有生成走 [lock]，并且对外暴露 [busy]，让界面和 API 互相避让。
 */
object ChatCore {

    /** OpenAI 风格的一条消息（role: system / user / assistant） */
    class Msg(val role: String, val text: String)

    @Volatile private var engine: Engine? = null
    @Volatile private var llama: LlamaModel? = null

    /** 当前已加载的模型名（API 的 /v1/models 用它当 id） */
    @Volatile var modelName: String? = null
        private set

    /** 模型是否支持图像输入（目前 API 只走纯文本，留着给以后的多模态接口用） */
    @Volatile var visionOk = false
        private set

    /** 是否正在为一个请求生成（界面据此避让，别和 API 抢同一个 KV slot） */
    @Volatile var busy = false
        private set

    /** 用户/客户端要求中断当前生成 */
    @Volatile private var cancelFlag = false

    private val lock = Mutex()

    /** LiteRT 会话缓存：客户端每轮会把整段历史重发，能对上就复用会话，省掉重复 prefill */
    private class Cached(val prior: List<Msg>, val conv: Conversation)
    private var cache: Cached? = null

    fun attachLiteRt(eng: Engine, name: String, vision: Boolean) {
        clearCache()
        engine = eng
        llama = null
        modelName = name
        visionOk = vision
    }

    fun attachGguf(model: LlamaModel, name: String, vision: Boolean) {
        clearCache()
        llama = model
        engine = null
        modelName = name
        visionOk = vision
    }

    /** 模型被卸载（用户在界面上卸载了，或进程要退出）——只放引用，不负责 close（由持有者 close） */
    fun release() {
        clearCache()
        engine = null
        llama = null
        modelName = null
        visionOk = false
    }

    fun isReady(): Boolean = engine != null || llama != null

    fun requestCancel() {
        cancelFlag = true
    }

    private fun clearCache() {
        cache?.let { c -> runCatching { c.conv.close() } }
        cache = null
    }

    /**
     * 生成一次。messages 为 OpenAI 风格；system 会单独抽出（LiteRT 走 systemInstruction，
     * gguf 没有 system 角色，就并进第一条 user 消息）。
     * 返回完整答复；流式片段同时通过 [onDelta] 回调（已经去掉思考内容）。
     */
    suspend fun generate(
        messages: List<Msg>,
        temperature: Float?,
        maxTokens: Int?,
        onDelta: (String) -> Unit,
    ): String = lock.withLock {
        busy = true
        cancelFlag = false
        try {
            withContext(Dispatchers.IO) {
                val eng = engine
                if (eng != null) runLiteRt(eng, messages, temperature, maxTokens, onDelta)
                else {
                    val lm = llama ?: throw IllegalStateException("未加载对话模型")
                    runGguf(lm, messages, temperature, maxTokens, onDelta)
                }
            }
        } finally {
            busy = false
        }
    }

    // ==================== LiteRT-LM 路径 ====================

    private suspend fun runLiteRt(
        eng: Engine,
        messages: List<Msg>,
        temperature: Float?,
        maxTokens: Int?,
        onDelta: (String) -> Unit,
    ): String {
        val sys = messages.filter { it.role == "system" }.joinToString("\n") { it.text }.trim()
        val turns = messages.filter { it.role != "system" && it.text.isNotEmpty() }
        require(turns.isNotEmpty()) { "请求里没有 user 消息" }
        val last = turns.last()
        val prior = turns.dropLast(1)

        val conv = cache?.takeIf { it.prior == prior }?.conv ?: run {
            clearCache()
            val initial = ArrayList<Message>()
            prior.forEach { m ->
                initial.add(if (m.role == "assistant") Message.model(m.text) else Message.user(m.text))
            }
            eng.createConversation(
                ConversationConfig(
                    systemInstruction = if (sys.isEmpty()) null else Contents.of(sys),
                    initialMessages = initial,
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = (temperature ?: 0.7f).toDouble(),
                        seed = (System.nanoTime() and 0x7FFFFFFF).toInt(),
                    ),
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    maxOutputToken = maxTokens,
                )
            ).also { cache = Cached(prior, it) }
        }

        val sb = StringBuilder()
        val flow: Flow<Message> = conv.sendMessageAsync(
            last.text,
            thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
        )
        flow.collect { msg ->
            if (cancelFlag) throw InterruptedException("cancelled")
            val delta = mergeStream(sb.toString(), messageText(msg))
            if (delta.length > sb.length) {
                val add = delta.substring(sb.length)
                sb.setLength(0); sb.append(delta)
                onDelta(add)
            }
        }
        return sb.toString()
    }

    // ==================== gguf（llama.cpp）路径 ====================

    private fun runGguf(
        lm: LlamaModel,
        messages: List<Msg>,
        temperature: Float?,
        maxTokens: Int?,
        onDelta: (String) -> Unit,
    ): String {
        val sys = messages.filter { it.role == "system" }.joinToString("\n") { it.text }.trim()
        val turns = messages.filter { it.role != "system" && it.text.isNotEmpty() }
        require(turns.isNotEmpty()) { "请求里没有 user 消息" }
        // llama.cpp 的对话模板只认 user/assistant，system 并进第一条 user 消息
        val msgs = ArrayList<LlamaPair<String, String>>()
        turns.forEachIndexed { i, m ->
            val role = if (m.role == "assistant") "assistant" else "user"
            var text = m.text
            if (i == 0 && sys.isNotEmpty()) text = "$sys\n\n$text"
            msgs.add(LlamaPair(role, text))
        }
        val params = InferenceParameters.empty()
            .withMessages(null, msgs)
            // cache_prompt + 固定 slot：客户端每轮重发的历史前缀由 llama.cpp 复用，不必重复 prefill
            .withCachePrompt(true)
            .withSlotId(0)
            .withNPredict(maxTokens ?: 1024)
            .withTemperature(temperature ?: 0.7f)
            .withTopK(40)
            .withTopP(0.95f)
            .withRepeatPenalty(1.1f)
            .withSeed((System.nanoTime() and 0x7FFFFFFF).toInt())

        val sb = StringBuilder()
        // gguf 的思考内容混在正文流里，用和界面同款的状态机拆开，只把正文交给客户端
        val splitter = ReasoningSplitter(
            onThought = {},
            onAnswer = { d -> if (d.isNotEmpty()) { sb.append(d); onDelta(d) } },
        )
        val it = lm.generateChat(params).iterator()
        try {
            while (it.hasNext()) {
                if (cancelFlag) break
                val piece = it.next()
                if (piece.text.isNotEmpty()) splitter.feed(piece.text)
            }
        } finally {
            runCatching { it.close() }
        }
        splitter.finish()
        return sb.toString()
    }

    // ==================== 与界面共用的两个小工具 ====================

    /** 从一条 LiteRT 消息里取出文本 */
    fun messageText(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /**
     * 兼容 LiteRT 流式片段的两种语义（增量 / 累积快照），避免「您好，有什么可以帮你？」被反复拼接：
     *   与已累积完全相同 → 丢弃；新片段以已累积开头 → 全量快照，替换；
     *   已累积以新片段结尾 → 尾部重复，丢弃；其余 → 追加。
     */
    fun mergeStream(cur: String, delta: String): String {
        if (cur.isEmpty() || delta.isEmpty()) return cur + delta
        if (delta == cur) return cur
        if (delta.startsWith(cur)) return delta
        if (cur.endsWith(delta)) return cur
        val t = delta.trim()
        if (t.isNotEmpty() && cur.endsWith(t)) return cur
        return cur + delta
    }
}
