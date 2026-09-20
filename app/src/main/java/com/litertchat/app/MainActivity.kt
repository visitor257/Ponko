package com.litertchat.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.litertchat.app.draw.DrawPage
import com.litertchat.app.draw.GgufProbe
import com.litertchat.app.draw.TaggerEngine
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.channelFlow
import net.ladenthin.llama.LlamaIterator
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.parameters.ModelParameters
import net.ladenthin.llama.value.ChatMessage
import net.ladenthin.llama.value.ContentPart
import net.ladenthin.llama.value.Pair
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * On-device chat app that loads a Google LiteRT-LM (.litertlm) model from local
 * storage and runs it via com.google.ai.edge.litertlm.
 *
 * UI: flat modern look — blue app bar, card-style settings panel, chat bubbles,
 * bottom input bar, collapsible "thinking" section, and Markdown-rendered replies.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK_MODEL = 1001
    private const val REQ_PICK_DRAW_MODEL = 1002
    private const val REQ_PICK_LORA = 1003
    private const val REQ_TAGGER_ONNX = 1004
    private const val REQ_TAGGER_CSV = 1005
        private const val REQ_TAKE_PHOTO = 1006
        private const val REQ_PICK_CHAT_IMAGE = 1007
        private const val MAX_ATTACH = 4
        private const val REQ_PICK_MMPROJ = 1008
        private const val REQ_PERM_STORAGE = 1009
        private const val REQ_PICK_FILE = 1010
        /** 一轮对话里最多让打标模型「看图」几次（agent 循环上限） */
        private const val MAX_TAG_ROUNDS = 2
        /** 单条消息最多能带的文件数（本地模型上下文有限，文件比图片更吃 token） */
        private const val MAX_FILES = 2
        /** 超过这个字节数就不当文本文件读，避免把内存吃爆 */
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024

        // ---- 对话模型参数（模型页 · 对话模型 里可调，这里只是内置默认值）----
        private const val KEY_CHAT_CTX = "chatCtx"
        private const val KEY_CHAT_MAXOUT = "chatMaxOut"
        private const val KEY_CHAT_TEMP = "chatTemp"
        private const val KEY_CHAT_TOPK = "chatTopK"
        private const val KEY_CHAT_TOPP = "chatTopP"
        private const val KEY_CHAT_REP = "chatRep"
        private const val KEY_CHAT_BUDGET = "chatBudget"
        private const val KEY_CHAT_SEED = "chatSeed"
        private const val DEF_CHAT_CTX = 4096
        private const val DEF_CHAT_MAXOUT = 2048
        private const val DEF_CHAT_TEMP = 0.7f
        private const val DEF_CHAT_TOPK = 40
        private const val DEF_CHAT_TOPP = 0.9f
        private const val DEF_CHAT_REP = 1.1f
        private const val DEF_CHAT_BUDGET = 2048
        private const val DEF_CHAT_SEED = -1
    }

    // ---- palette ----
    private val C_PRIMARY = Color.rgb(47, 107, 255)
    private val C_PRIMARY_SOFT = Color.rgb(233, 239, 255)
    private val C_BG = Color.rgb(242, 244, 248)
    private val C_CARD = Color.WHITE
    private val C_TEXT = Color.rgb(28, 32, 42)
    private val C_SUBTEXT = Color.rgb(122, 131, 148)
    private val C_THOUGHT_BG = Color.rgb(245, 247, 251)
    private val C_THOUGHT_TEXT = Color.rgb(110, 118, 134)
    private val C_OK = Color.rgb(34, 178, 110)
    private val C_WARN = Color.rgb(245, 158, 11)
    private val C_ERR = Color.rgb(229, 82, 82)
    private val C_IDLE = Color.rgb(154, 164, 181)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelPath: String? = null
    /** 多模态 gguf 需要的视觉投影文件（mmproj），在加载模型时挂上；null = 纯文字。 */
    private var mmprojPath: String? = null
    private var mmprojStatusTv: TextView? = null
    private var mmprojContainer: LinearLayout? = null

    // ---- 对话参数（模型页 · 对话模型）----
    private var chatParamsDirty = false
    /** 回填参数到输入框时临时屏蔽 TextWatcher，免得多字段被互相覆盖 */
    private var chatUiLoading = false
    /** 当前真正加载进内存的模型路径（判断参数改动要不要重建会话） */
    private var loadedModelPath: String? = null
    private var chatParamsOwnerTv: TextView? = null
    private var chatCtxEdit: EditText? = null
    private var chatMaxOutEdit: EditText? = null
    private var chatTempEdit: EditText? = null
    private var chatTopKEdit: EditText? = null
    private var chatTopPEdit: EditText? = null
    private var chatRepEdit: EditText? = null
    private var chatBudgetEdit: EditText? = null
    private var chatSeedEdit: EditText? = null
    private var busy = false

    /** 当前加载的 .litertlm 模型是否支持图像输入（读模型自带 Capabilities，失败按不支持处理）。 */
    private var visionOk = false
    /** 「借打标看图」时工具结果用哪个角色候选回传（0=首选，试错后记住可用的那个） */
    private var tagReturnIdx = 0
    /** 输入栏「＋」附件按钮；待发送图片的预览条与缩略图 */
    private lateinit var attachButton: TextView
    private lateinit var attachPreviewStrip: HorizontalScrollView
    private lateinit var attachPreviewBox: LinearLayout
    /** 已选好、等待随下一条消息发出的图片（原图 + 预压好的 JPEG 字节），最多 MAX_ATTACH 张 */
    private val pendingImages = ArrayList<kotlin.Pair<Bitmap, ByteArray>>()

    /** 待发送的文本文件（已解析出文字），最多 MAX_FILES 个 */
    private val pendingFiles = ArrayList<PendingFile>()

    /** 用户选的文件：文件名 + 原始字节数 + 解析出的正文（可能已截断） */
    private class PendingFile(
        val name: String,
        val bytes: Long,
        var text: String,
        var truncated: Boolean,
    )

    /** 文件读取结果：成功给 file，失败给 error（可直接显示的文案） */
    private class FileRead(val file: PendingFile?, val error: String?)
    /** 拍照时预创建的 MediaStore URI */
    private var pendingCameraUri: Uri? = null
    /** 安卓 9 及以下等拿到存储权限后再执行的动作（仅这些机型用得到） */
    private var pendingStorageAction: (() -> Unit)? = null

    /** 当前处于绘图模式：对话页的输入会被当作正面提示词去生成图片 */
    /** 让语言模型「调用」绘图模型时使用的命令标记。 */
    private val drawCmdRegex = Regex("<draw>(.*?)</draw>", RegexOption.DOT_MATCHES_ALL)

    /** 语言模型可用的绘图命令说明（仅当绘图模型与语言模型同时就绪时注入）。 */
    private val drawToolPrompt = """
        你已接入一个本地绘图模型，可以把画面交给它去画。
        只有在用户**明确要求生成/画一张图片**时才使用；其他任何情况（闲聊、问答、写代码、翻译等）
        都正常回答，不要主动画图，也不要展示这个命令。

        需要画图时，不要声明自己无法画图、不要推辞、也不要建议对方改用别的工具——
        直接用一两句话回应，然后在回复正文的最后另起一行输出：
        <draw>画面描述</draw>

        画面描述用英文、逗号分隔的关键词（例如 1girl, silver hair, school uniform, cherry blossoms），
        只写画面本身，不要写参数、编号或解释。
        这条命令必须出现于你的最终回答正文中，不要写在思考过程里，否则系统收不到。
    """.trimIndent()

    /** 语言模型与绘图模型同时就绪 → 语言模型可以用 <draw> 命令调绘图模型。 */
    private val canDrawFromChat: Boolean
        get() = drawPage?.isReady() == true && (engine != null || llamaModel != null)

    /** 从回答里取出 <draw>…</draw> 命令：返回提示词与命中位置（不改内容，由调用方替换） */
    private class DrawHit(val prompt: String, val inAnswer: Boolean, val start: Int, val end: Int)

    private fun scanDrawCommand(answer: String, thought: String): DrawHit? {
        if (!canDrawFromChat) return null
        // 先扫正文，再扫思考过程 —— 思考模式下模型常把「决定画图」写在 thought 里
        drawCmdRegex.find(answer)?.let { m ->
            val prompt = m.groupValues[1].trim()
            if (prompt.isNotEmpty()) return DrawHit(prompt, true, m.range.first, m.range.last + 1)
        }
        drawCmdRegex.find(thought)?.let { m ->
            val prompt = m.groupValues[1].trim()
            if (prompt.isNotEmpty()) return DrawHit(prompt, false, m.range.first, m.range.last + 1)
        }
        return null
    }

    /** 让语言模型「看图」用的打标命令，例如 <tag threshold="0.35" topk="40"></tag> */
    private val tagCmdRegex =
        Regex("<tag\\b([^>]*)>(.*?)(?:</tag>|$)", RegexOption.DOT_MATCHES_ALL)

    /** 打标命令的参数（阈值 / 最多标签数），由语言模型自己给，可省略 */
    private class TagReq(val threshold: Float, val topK: Int)

    /** 语言模型看不见图、但打标模型已加载 → 可以用 <tag> 命令让系统去识别图片。 */
    private val canTagFromChat: Boolean
        get() = drawPage?.taggerLoaded() == true && (engine != null || llamaModel != null) && !visionOk

    /** 这一轮带图、模型自己看不见、且还没识别过 → 本轮就把打标命令说明给模型 */
    private fun canTagTurn(t: QaTurn?): Boolean =
        canTagFromChat && t?.userImage != null

    /** 看不见图的模型：告诉它可以用 <tag> 命令让打标模型去看用户发的图。 */
    private val tagToolPrompt = """
        【图片工具】你自己看不到图片内容，但可以调用本机的打标模型，把用户刚发来的图片转成英文标签。
        用户消息里说明了「带了图片」时就是真的有图：此时必须调用下面的图片工具，
        绝对不要回复「请提供图片」或「我看不到图片」之类的推辞。
        需要知道图里有什么时：不要向用户解释这个工具，不要复述这条规则，不要猜图。
        这一轮你的输出里只写下面这一行命令，不要有任何解释、计划或别的话（参数可省；threshold 是阈值 0.05~0.99，topk 是最多几个标签 1~80）：
        <tag threshold="0.35" topk="40"></tag>
        命令写在正文的最后一行，不要写进思考过程。系统随后会把识别结果发给你，你再根据这些标签回答用户的问题。
    """.trimIndent()

    /** 看不见图的模型：本轮带图时给它的提示（先告知「带了图」，再给工具命令格式） */
    private fun tagHint(t: QaTurn?): String {
        val n = t?.userImage?.split("|")?.count { it.isNotEmpty() } ?: 0
        return getString(R.string.s_329, n) + "\n" + tagToolPrompt
    }

    /** 回复里像在推辞「我看不到图、请提供图片」的判定（小模型经常无视图片工具） */
    private fun looksLikeBlindRefusal(s: String): Boolean {
        if (s.isEmpty()) return false
        val keys = listOf(
            "提供图片", "提供一张图", "发送图片", "发一张图", "发个图", "发张图", "上传图", "看不到图", "无法查看",
            "无法看到", "没有图片", "没收到图", "图片吗", "请提供", "重新发送图",
            "provide an image", "send an image", "send me the image", "no image", "attach", "can't see",
            "cannot see", "unable to see", "please upload", "please provide", "i don't see",
        )
        return keys.any { s.contains(it, ignoreCase = true) }
    }

    /** 模型把工具返回当成新输入、反过来问用户「请提出您的问题」的判定 */
    private fun looksLikeQuestionAsk(s: String): Boolean {
        if (s.isEmpty()) return false
        val keys = listOf(
            "请提出您的问题", "请您提出", "请提出你的问题", "请向我提问", "请告诉我您想问", "请告诉我你的问题",
            "您想问什么", "你想问什么", "您的问题是什么", "你的问题是什么", "等待您的提问", "等待你的提问",
            "请提供您的问题", "请提供你的问题", "有什么可以帮",
            "ask your question", "what would you like to ask", "what is your question", "please ask",
            "let me know your question", "how can i help", "what can i help",
        )
        return keys.any { s.contains(it, ignoreCase = true) }
    }

    /** 不做工具调用时，把回答里可能残留的 <tag …></tag> 命令抹掉（别把命令原文当正文显示给用户） */
    private val tagStripRegex = Regex("<tag\\b[^>]*>(?:.*?</tag>)?", RegexOption.DOT_MATCHES_ALL)

    private fun stripTagCommand(text: String): String =
        text.replace(tagStripRegex, "").trim()

    /** <tag …></tag> 命令的命中结果：参数 + 命中位置 */
    private class TagHit(val req: TagReq, val inAnswer: Boolean, val start: Int, val end: Int)

    /** 从回答里取出 <tag …></tag> 命令：返回参数与命中位置（不改内容，由调用方替换） */
    private fun scanTagCommand(answer: String, thought: String): TagHit? {
        if (!canTagFromChat) return null

        fun parse(text: String, inAnswer: Boolean): TagHit? {
            val m = tagCmdRegex.find(text) ?: return null
            // 参数可能写在标签属性里，也可能写在标签内容里，两处都找一下
            val attrs = m.groupValues[1] + " " + m.groupValues[2]
            val th = Regex("threshold\\s*=\\s*[\"']?([0-9]*\\.?[0-9]+)")
                .find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
            val tk = Regex("(topk|top_k|topK|max)\\s*=\\s*[\"']?([0-9]+)", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(2)?.toIntOrNull()
            return TagHit(
                TagReq((th ?: 0.35f).coerceIn(0.05f, 0.99f), (tk ?: 40).coerceIn(1, 80)),
                inAnswer, m.range.first, m.range.last + 1,
            )
        }
        return parse(answer, true) ?: parse(thought, false)
    }

    /** 把这一轮用户发的图交给打标模型识别：返回标签文本（多张图各一行），失败抛异常 */
    private fun runTaggerOnImages(names: List<String>, req: TagReq): String {
        val rgbOrder = drawPage?.taggerRgbOrder() == true
        val sb = StringBuilder()
        for ((i, n) in names.withIndex()) {
            val bmp = readChatImage(n) ?: continue
            val tags = TaggerEngine.run(bmp, req.threshold, req.topK, rgbOrder = rgbOrder)
            if (tags.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n")
            if (names.size > 1) sb.append("#${i + 1} ")
            sb.append(tags.joinToString(", ") { it.first })
        }
        return sb.toString()
    }

    /** 一轮生成的产物（正文 / 思考） */
    private class RoundOut(val answer: String, val thought: String)

    /**
     * 「工具返回」轮要回传给对话模型的东西：
     *  - modelText：模型这一轮的原始输出（作为 assistant 消息回填，让它看到自己的调用意图）
     *  - text：打标模型的结果（按 system / tool 角色回传，不伪装成用户消息）
     */
    private class ToolReturn(val modelText: String, val text: String)

    /** gguf 回传工具结果的角色候选：模板不认哪个就换下一个，user 只是兜底 */
    private val tagRolesGguf = listOf("system", "tool", "user")

    /**
     * 跑一轮生成（gguf / litertlm 两条路径），把输出流式渲染进 ai。
     * displayPrefix、thoughtPrefix 是前面几轮已经落在气泡里的内容，这样工具调用可以
     * 在同一个气泡里接着往下写，而不是把整轮重来一遍。
     */
    private suspend fun runRound(
        turn: QaTurn,
        ai: AiArea,
        gguf: Boolean,
        jpegs: List<ByteArray>,
        overText: String,
        displayPrefix: String,
        thoughtPrefix: String,
        toolReturn: ToolReturn?,
        dropLastForBudget: Boolean,
        hideStream: Boolean,
    ): RoundOut {
        val answerBuf = StringBuilder()
        val thoughtBuf = StringBuilder()
        var lastRender = 0L

        fun renderAnswer() {
            // 第一轮可能先藏着不显示（等确认要不要调工具），见 hideStream
            if (hideStream) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRender < 120) return
            lastRender = now
            val md = displayPrefix + answerBuf
            if (md.isNotEmpty()) {
                // 流式重渲染可能把焦点从输入框抢走：用户正在打字时把焦点还回去
                val hadFocus = inputEdit.hasFocus()
                markwonStream.setMarkdown(ai.answer, md)
                if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
            }
        }

        fun renderThought() {
            val now = SystemClock.uptimeMillis()
            if (now - lastRender < 90) return
            val hadFocus = inputEdit.hasFocus()
            ai.thoughtBody.text = thoughtPrefix + thoughtBuf
            ai.thoughtHeader.text = if (ai.thoughtBody.visibility == View.VISIBLE)
                getString(R.string.s_206) else getString(R.string.s_205)
            if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
        }

        // 「工具返回」轮：模板/引擎可能不认 system、tool 角色（会直接抛错），
        // 那就换个候选角色重发一次；一但试出能用的就记住，后面不再试错。
        var attempt = if (toolReturn == null) 0 else tagReturnIdx.coerceIn(0, 2)
        while (true) {
            answerBuf.setLength(0)
            thoughtBuf.setLength(0)
            try {
                if (gguf) {
                    val lm = llamaModel ?: return RoundOut("", "")
                    val extra: List<kotlin.Pair<String, String>> = if (toolReturn == null) {
                        emptyList()
                    } else {
                        listOf(
                            "assistant" to toolReturn.modelText,
                            tagRolesGguf[attempt] to toolReturn.text,
                        )
                    }
                    // generateChat 会套用模型自带的对话模板；cache_prompt=true + 固定 slot
                    // 让 llama.cpp 复用上一轮已算好的 KV 前缀，长对话不再重复 prefill。
                    // 本回合带图且模型能看图 → 走多模态消息（ContentPart）；否则沿用纯文字路径
                    val params = if (visionOk && jpegs.isNotEmpty()) {
                        buildMultimodalParams(current, jpegs, extra)
                    } else {
                        buildInferenceParams(current, extra)
                    }

                    // GGUF 模型的思考内容混在正文流里（<|channel>thought…<channel|> 或 …），
                    // 用状态机把两路分开：思考进折叠区，正文走 Markdown 渲染。
                    fun onThoughtDelta(d: String) {
                        if (d.isEmpty()) return
                        thoughtBuf.append(d)
                        turn.thought = thoughtPrefix + thoughtBuf
                        if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                        renderThought()
                    }

                    fun onAnswerDelta(d: String) {
                        if (d.isEmpty()) return
                        answerBuf.append(d)
                        turn.answer = displayPrefix + answerBuf
                        renderAnswer()
                    }

                    // 开了思考模式就默认从思考区开始：Gemma 这类模型的 `<|channel>thought`
                    // 前缀是写在 prompt 模板里的，不会出现在输出流中，只能靠结束标记分界。
                    val splitter = ReasoningSplitter(
                        ::onThoughtDelta,
                        ::onAnswerDelta,
                        assumeThinking = thinkCheck.isChecked,
                    )

                    val flow = channelFlow {
                        withContext(Dispatchers.IO) {
                            val iterable = lm.generateChat(params)
                            val it = iterable.iterator()
                            ggufIterator = it
                            try {
                                // 用户中断后 native 迭代器会抛异常（如 task not found），
                                // 此时正常收尾，不当成错误
                                val next = {
                                    try {
                                        if (it.hasNext()) it.next() else null
                                    } catch (e: Throwable) {
                                        if (!stopRequested) throw e
                                        null
                                    }
                                }
                                while (true) {
                                    val piece = next() ?: break
                                    if (piece.text.isNotEmpty()) send(piece.text)
                                }
                            } finally {
                                runCatching { it.close() }
                                ggufIterator = null
                            }
                        }
                    }
                    flow.collect { piece ->
                        splitter.feed(piece)
                        scrollToBottom()
                    }
                    splitter.finish()
                    renderThought()
                } else {
                    // Flow emits INCREMENTAL chunks (not snapshots): accumulate.
                    // Reasoning text arrives on channels["thought"], answer text in contents.
                    val items = ArrayList<Content>()
                    if (overText.isNotEmpty()) items.add(Content.Text(overText))
                    // 模型自己看不见图时不要把图片塞给它（图片只交给打标模型用）
                    if (visionOk) jpegs.forEach { items.add(Content.ImageBytes(it)) }
                    // LiteRT 的会话是【增量累积】的：历史 + 本轮一旦超过模型上下文（4096），
                    // native 直接抛 "Input token ids too long"，这一轮就废了。
                    // 所以发送前自己估一把：超预算就重建会话——configFor 会按预算裁掉最老的对话。
                    if (chatParamsDirty ||
                        estimateConversationTokens(current, overText, dropLast = dropLastForBudget) > inputTokenBudget()
                    ) {
                        rebuildConversation(silent = true, dropLast = dropLastForBudget)
                        chatParamsDirty = false
                    }
                    val conv = conversation ?: throw IllegalStateException(getString(R.string.s_042))
                    val tc = ThinkingConfig(
                        enableThinking = thinkCheck.isChecked,
                        thinkingTokenBudget = thinkBudgetOrUnlimited(),
                    )
                    val flow = when {
                        toolReturn == null -> {
                            val ask: Contents = if (items.size == 1 && items[0] is Content.Text) {
                                Contents.of(overText)
                            } else {
                                Contents.of(items)
                            }
                            conv.sendMessageAsync(ask, thinkingConfig = tc)
                        }
                        // 工具结果优先按「工具返回」回传（LiteRT-LM 原生帧），
                        // 模板不认再退回 system 消息，最后才退回普通文本消息。
                        attempt == 0 -> conv.sendMessageAsync(
                            Message.tool(Contents.of(Content.ToolResponse("tagger", toolReturn.text))),
                            thinkingConfig = tc,
                        )
                        attempt == 1 -> conv.sendMessageAsync(
                            Message.system(toolReturn.text),
                            thinkingConfig = tc,
                        )
                        else -> conv.sendMessageAsync(Message.user(toolReturn.text), thinkingConfig = tc)
                    }
                    flow.collect { msg ->
                        val delta = extractText(msg)
                        val thoughtDelta = msg.channels["thought"]
                        if (!thoughtDelta.isNullOrEmpty()) {
                            val cur = thoughtBuf.toString()
                            val merged = mergeStreamDelta(cur, thoughtDelta)
                            if (merged != cur) {
                                thoughtBuf.setLength(0)
                                thoughtBuf.append(merged)
                                turn.thought = thoughtPrefix + merged
                                if (ai.thoughtBox.visibility != View.VISIBLE) ai.thoughtBox.visibility = View.VISIBLE
                                renderThought()
                            }
                        }
                        if (delta.isNotEmpty()) {
                            val cur = answerBuf.toString()
                            val merged = mergeStreamDelta(cur, delta)
                            if (merged != cur) {
                                answerBuf.setLength(0)
                                answerBuf.append(merged)
                                turn.answer = displayPrefix + merged
                                renderAnswer()
                            }
                        }
                        scrollToBottom()
                    }
                    renderThought()
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 只在「一个字都还没吐出来」时换角色重试，避免把半截输出丢了
                val retry = toolReturn != null && !stopRequested && attempt < 2 &&
                    answerBuf.isEmpty() && thoughtBuf.isEmpty() && !isContextOverflow(e)
                if (!retry) throw e
                attempt++
                tagReturnIdx = attempt
                // 这一条消息可能已经被引擎吃进会话，重建一次把这轮的脏状态丢掉
                if (!gguf) runCatching { rebuildConversation(silent = true) }
            }
        }

        // 本轮收尾：用完整渲染器渲染一次（含表格），并把本轮内容并进这一轮的回答
        val ans = answerBuf.toString()
        val th = thoughtBuf.toString()
        val full = displayPrefix + ans
        turn.answer = full
        turn.thought = thoughtPrefix + th
        if (!hideStream) when {
            ans.isEmpty() && th.isEmpty() && displayPrefix.isEmpty() -> {
                ai.answer.text = getString(R.string.s_007)
                ai.answer.setTextColor(C_SUBTEXT)
            }
            ans.isEmpty() && displayPrefix.isEmpty() && th.isNotEmpty() -> {
                ai.answer.text = getString(R.string.s_006)
                ai.answer.setTextColor(C_SUBTEXT)
            }
            full.isNotEmpty() -> markwonFull.setMarkdown(ai.answer, full)
        }
        ai.thoughtBody.text = thoughtPrefix + th
        scrollToBottom()
        return RoundOut(ans, th)
    }

    /** 对话页当前是否「出图模式」。
     *  规则：语言模型在场时（包括两种模型同时加载），对话页一律走聊天；
     *  只有「绘图模型已就绪、且没有加载语言模型」时，对话页输入才当成绘图提示词。 */
    private val drawMode: Boolean
        get() = drawPage?.isReady() == true && engine == null && llamaModel == null

    /** .gguf 架构探测结果缓存，避免重复读头部 */
    private val ggufKindCache = HashMap<String, GgufProbe.Kind>()
    private var genJob: Job? = null

    /** 一次问答（用于会话重建/持久化）。 */
    private class QaTurn(
        val user: String,
        var answer: String = "",
        var thought: String = "",
        /** 绘图轮次：生成图在本地的文件名（filesDir/chatimg/ 下），非绘图轮为 null */
        var image: String? = null,
        /** 用户这一轮发出的图片文件名（filesDir/chatimg/ 下），无图片为 null */
        var userImage: String? = null,
        /** 用户这一轮发出的文件名（| 分隔），无文件为 null */
        var userFile: String? = null,
    ) {
        /** 本轮文件的正文块（含表头与结尾指令）：进本次推理、不进对话历史 */
        var fileText: String? = null
        /** 本轮文件的原始内容（JSON：文件名/字节数/正文/是否截断），用于「重新生成」还原附件 */
        var fileStore: String? = null
    }

    /** 一个独立对话，拥有自己的完整历史。 */
    private class ChatSession(var id: Long, var title: String) {
        val turns = mutableListOf<QaTurn>()
    }

    private val sessions = mutableListOf<ChatSession>()
    private lateinit var current: ChatSession

    /** 当前会话创建时的思考开关状态；与复选框不一致时需重建会话。 */
    private var convThinking: Boolean? = null

    /** 中断后 LiteRT 会话可能已不可用：标记下一条消息发送前静默重建。 */
    private var convNeedsRebuild = false

    /** 创建 LiteRT 会话时是否注入了 <draw> 绘图命令提示。
     *  加载/卸载绘图模型会改变这个能力，而 LiteRT 的 system 提示只在建会话时写入，
     *  所以能力变化后必须重建会话，否则模型根本不知道自己能画图。 */
    private var convDrawCapable = false

    /** GGUF（llama.cpp）后端实例；非空表示当前加载的是 .gguf 模型。 */
    private var llamaModel: LlamaModel? = null
    private var ggufIterator: LlamaIterator? = null

    /** 用户点了「停止」。native 中断后往往抛异常（如 llama.cpp 的 task not found），
     *  用它把「主动中断」和「真的出错」分开，避免已输出的内容被错误信息覆盖。 */
    @Volatile
    private var stopRequested = false

    private lateinit var markwonFull: Markwon
    private lateinit var markwonStream: Markwon

    // ---- views ----
    private lateinit var statusTv: TextView
    private lateinit var statusDot: View
    private lateinit var tabChat: View
    private lateinit var tabModels: View

    // 模型页：顶部页签 + 左右翻页
    private lateinit var modelsPager: ViewPager
    private lateinit var modelsTabChat: TextView
    private lateinit var modelsTabDraw: TextView
    private lateinit var modelsTabTag: TextView
    private lateinit var tabSettings: View
    private lateinit var tabDraw: View
    private lateinit var inputBar: View

    /** 绘图页（文生图/图生图），懒加载 */
    private var drawPage: DrawPage? = null
    private lateinit var appBarMenu: View
    private lateinit var appBarPlus: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerMask: View
    private lateinit var drawerBody: LinearLayout
    private val navIcons = mutableListOf<TextView>()
    private val navLabels = mutableListOf<TextView>()
    private lateinit var savedContainer: LinearLayout
    private lateinit var modelInfoText: TextView

    /** 模型页里的绘图模型状态文本（模型统一在模型页加载） */
    private var drawModelStatus: TextView? = null
    /** 已复制的绘图模型列表容器 */
    private var drawSavedContainer: LinearLayout? = null
    /** 绘图模型的「加载/卸载」二合一按钮 */
    private var drawToggleBtn: TextView? = null
    /** 绘图模型的「导入」按钮（复制期间禁用） */
    private var drawImportBtn: TextView? = null
    /** 绘图模型导入进度条（本地复制时显示） */
    private var drawProgress: ProgressBar? = null
    /** 打标（Tagger）状态文本（模型页） */
    private var taggerStatusTv: TextView? = null
    /** 打标模型 / 标签表导入按钮 */
    private var taggerOnnxBtn: TextView? = null
    private var taggerCsvBtn: TextView? = null
    /** 已导入的打标模型 / 标签表列表容器 */
    private var taggerBox: LinearLayout? = null
    /** 打标模型 加载/卸载 按钮 */
    private var taggerLoadBtn: TextView? = null
    /** 当前选定的绘图主模型（绝对路径） */
    private var drawMainPath: String? = null
    /** LoRA 状态文本（模型页） */
    private var loraStatusTv: TextView? = null
    /** 已安装 LoRA 列表容器（模型页） */
    private var loraBox: LinearLayout? = null
    /** 上次启动时发现「加载绘图模型」中途崩了（native 崩溃，Java 层捕不到） */
    private var pendingDrawCrash = false
    /** 上次崩溃时已执行的阶段（来自 draw/.loadstage） */
    private var pendingDrawStage: String? = null
    private lateinit var backendSpinner: Spinner
    private lateinit var loadButton: TextView
    private lateinit var thinkCheck: CheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputEdit: EditText
    private lateinit var sendButton: TextView
    private lateinit var jumpButton: TextView

    // ---- state ----
    private var currentTab = 0
    private var drawerOpen = false
    private val drawerWidthDp = 280
    private var scrollPending = false
    /** 程序自身贴底滚动期间为 true，避免被当成「用户划走」而关掉跟随。 */
    private var programmaticScroll = false
    /** Auto-scroll only while locked to the bottom; scrolling up unlocks it. */
    private var autoFollow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markwonFull = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        // 流式渲染器故意不装表格插件：表格是两遍异步布局（先测列宽再重排），
        // 边生成边重渲染会让气泡高度反复跳变（上下抽搯）。表格放到生成结束后一次渲染。
        markwonStream = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        loadSessions()
        installCrashHandler()
        buildUi()
    }

    /** 崩溃自捕获：Java/ART 层异常（含 UnsatisfiedLinkError）写进文件，下次启动可见。 */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(
                    "${java.util.Date()}\nthread=${t.name}\n" + android.util.Log.getStackTraceString(e)
                )
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        // 上次加载绘图模型中途崩了？阶段文件还在 → 说明 native 崩（Java 层捕不到），
        // 但里面已经记录了崩到哪一步。
        val st = File(filesDir, "draw/.loadstage")
        if (st.exists()) {
            pendingDrawStage = runCatching { st.readText() }.getOrNull()
            runCatching { st.delete() }
            pendingDrawCrash = true
        }
    }

    /** 读并清空崩溃日志（供模型页展示） */
    private fun takeCrashLog(): String? {
        val f = File(filesDir, "crash.txt")
        if (!f.exists()) return null
        val s = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return s
    }

    override fun onPause() {
        super.onPause()
        saveSessions()
        drawPage?.persistAll()   // 绘图参数兜底落盘
    }

    override fun onDestroy() {
        super.onDestroy()
        try { saveSessions() } catch (_: Throwable) {}
        scope.cancel()
        try { drawPage?.release() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        try { llamaModel?.close() } catch (_: Throwable) {}
        engine = null
        llamaModel = null
        conversation = null
    }

    // ================= UI construction =================

    private fun buildUi() {
        val rootFrame = FrameLayout(this).apply { setBackgroundColor(C_BG) }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }

        main.addView(buildAppBar(), matchWrap())

        // 四页内容：对话 / 模型 / 绘图 / 关于（底部菜单切换）
        tabChat = buildChatPage()
        // 注意顺序：DrawPage 必须先建好并赋给 drawPage，再建「模型」页。
        // 「模型」页构建时就地读取 drawPage 的状态（LoRA 列表 / 已复制绘图模型清单），
        // 顺序反了会拿到 null，每次启动都显示成「未安装」。
        val dpg = DrawPage(this, scope, C_PRIMARY, C_TEXT, C_SUBTEXT)
        drawPage = dpg
        tabModels = buildModelsPage()
        tabSettings = buildSettingsPage()
        // 绘图模型就绪 → 进入绘图模式（对话页输入即正面提示词）
        dpg.onPipelineReady = {
            // 回调可能来自 IO 线程，UI 操作统一回主线程
            runOnUiThread {
                dpg.llmLoaded = false
                updateThinkEnabled()
                drawModelStatus?.text = dpg.modelSummary()
                refreshDrawModels()
            }
        }
        dpg.onSaveImage = { bmp, name -> saveImageToGallery(bmp, name) }
        dpg.onStatus = { text, isError ->
            runOnUiThread { setStatus(text, if (isError) C_ERR else C_WARN) }
        }
        // 注意：不在启动时自动加载绘图模型——native 加载失败会直接崩掉启动流程。
        // 改为在「模型」页手动点「加载绘图模型」（见下方按钮）。
        // 绘图页内部用 ViewPager 做左右翻页，外层不能再套 ScrollView（横竖滑动会打架）
        tabDraw = runCatching { dpg.build() }.getOrElse { e ->
            // 兜底：绘图页构建失败也不能让整个 App 挂掉，把原因显示出来便于定位
            ScrollView(this).apply {
                setBackgroundColor(C_BG)
                addView(TextView(this@MainActivity).apply {
                    text = "绘图页初始化失败：\n" + android.util.Log.getStackTraceString(e)
                    textSize = 12f
                    setTextColor(0xFFCC3333.toInt())
                    setPadding(36, 36, 36, 36)
                    setTextIsSelectable(true)
                })
            }
        }
        val contentFrame = FrameLayout(this)
        contentFrame.addView(tabChat, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabModels, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabSettings, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(tabDraw, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        main.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        inputBar = buildInputBar()
        main.addView(inputBar, matchWrap())
        main.addView(buildBottomNav(), matchWrap())

        rootFrame.addView(main, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildDrawer(rootFrame)
        setContentView(rootFrame)

        refreshSavedModels()
        restoreSession()
        switchTab(0)
    }

    /** 对话页：消息列表 + 回到底部按钮 + 智能跟随滚动。 */
    private fun buildChatPage(): View {
        val chatWrap = FrameLayout(this)
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        chatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(chatContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // 用户上滑阅读时暂停自动跟随；此按钮一键回到底部
        jumpButton = TextView(this).apply {
            text = "↓"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY, 24)
            elevation = dp(4).toFloat()
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                autoFollow = true
                jumpButton.visibility = View.GONE
                scrollView.post {
                    val child = scrollView.getChildAt(0)
                    if (child != null) scrollView.smoothScrollTo(0, child.bottom)
                }
            }
        }
        chatWrap.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chatWrap.addView(jumpButton, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(16), dp(16))
        })

        // 滚动规则：只看「距离底部的距离」，不看滚动方向（方向判在可选中文本上是不可靠的）。
        //   距底在阈值（约 15 行正文）以内 → 视为已在底部：隐藏「回到底部」按钮，并恢复自动跟随；
        //   超过阈值 → 停止自动跟随，显示「回到底部」按钮。
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            // 自己主动贴底的滚动不算「用户往上划」
            if (!programmaticScroll) refreshFollowState()
        }
        return chatWrap
    }

    private fun buildAppBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_PRIMARY)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(3).toFloat()
        }

        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appBarMenu = iconButton("☰") { openDrawer() }
        r1.addView(appBarMenu, wrapWrap())

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        titleBox.addView(TextView(this).apply {
            text = "Ponko"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        statusDot = View(this).apply { background = rounded(Color.WHITE, 99) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) })
        statusTv = TextView(this).apply {
            text = getString(R.string.s_116)
            textSize = 11.5f
            setTextColor(Color.parseColor("#DCE5FF"))
        }
        statusRow.addView(statusTv, wrapWrap())
        titleBox.addView(statusRow, matchWrap())

        r1.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        appBarPlus = iconButton("＋") { newSession() }
        r1.addView(appBarPlus, wrapWrap())
        bar.addView(r1, matchWrap())
        return bar
    }

    /** 模型页：选择/加载模型 + 后端选择。 */
    private fun buildModelsPage(): View {
        // 结构对齐绘图页：顶部常驻「运行方式」，下面页签 + ViewPager 左右翻页。
        // 仍用老版 ViewPager：PagerAdapter 能直接复用已建好的 View，
        // ViewPager2 内部是 RecyclerView，重复 attach 同一个 View 会崩。
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F6F8.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
        }

        // ================= 运行方式（对话 / 绘图共用） =================
        val card = sectionCard()
        card.addView(pageTitle(getString(R.string.s_181)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.s_069)
            textSize = 13f
            setTextColor(C_TEXT)
        }, wrapWrap())
        backendSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("CPU", "GPU")
            )
            setSelection(0)
        }
        row.addView(backendSpinner, wrapWrap().apply { leftMargin = dp(6) })
        backendSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 运行方式实时同步给绘图页（Ponko 的 sd.cpp 用 Vulkan，Tagger 用 NNAPI）
                drawPage?.useGpu = (position == 1)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        card.addView(row, matchWrap().apply { topMargin = dp(10) })
        card.addView(
            hintText(getString(R.string.s_075)),
            matchWrap().apply { topMargin = dp(4) }
        )

        // ================= 对话模型 =================
        val chatCard = sectionCard()
        chatCard.addView(pageTitle(getString(R.string.s_076)))
        chatCard.addView(hintText(getString(R.string.s_122)))

        modelInfoText = TextView(this).apply {
            text = getString(R.string.s_120)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        chatCard.addView(modelInfoText, matchWrap().apply { topMargin = dp(6) })

        chatCard.addView(actionButton(getString(R.string.s_189)) { pickModelFile() },
            matchWrap().apply { topMargin = dp(8) })

        savedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chatCard.addView(savedContainer, matchWrap().apply { topMargin = dp(6) })

        // ---- gguf 多模态：视觉投影文件（mmproj）----
        chatCard.addView(divider(), matchWrap().apply { topMargin = dp(10) })
        chatCard.addView(subTitle(getString(R.string.s_280)), matchWrap().apply { topMargin = dp(10) })
        chatCard.addView(hintText(getString(R.string.s_281)), matchWrap().apply { topMargin = dp(4) })
        mmprojStatusTv = TextView(this).apply {
            text = getString(R.string.s_283)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        chatCard.addView(mmprojStatusTv, matchWrap().apply { topMargin = dp(6) })
        mmprojContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chatCard.addView(mmprojContainer, matchWrap().apply { topMargin = dp(6) })
        chatCard.addView(actionButton(getString(R.string.s_282)) { pickMmprojFile() },
            matchWrap().apply { topMargin = dp(8) })
        refreshMmprojUi()

        // 加载按钮放在对话参数之上（参数按模型分别保存，先选/先加载模型更顺手）
        loadButton = actionButton(getString(R.string.s_060)) { toggleLoad() }
        chatCard.addView(loadButton, matchWrap().apply { topMargin = dp(8) })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            indeterminateTintList = ColorStateList.valueOf(C_PRIMARY)
            progressTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        chatCard.addView(progressBar, matchWrap().apply { topMargin = dp(8) })

        // ---- 对话参数：上下文/采样等，模型页直接可调 ----
        chatCard.addView(divider(), matchWrap().apply { topMargin = dp(12) })
        chatCard.addView(subTitle(getString(R.string.s_303)), matchWrap().apply { topMargin = dp(10) })
        chatCard.addView(hintText(getString(R.string.s_304)), matchWrap().apply { topMargin = dp(4) })
        chatParamsOwnerTv = TextView(this).apply {
            textSize = 11.5f
            setTextColor(C_PRIMARY)
        }
        chatCard.addView(chatParamsOwnerTv, matchWrap().apply { topMargin = dp(4) })
        chatCard.addView(
            twoCols(
                numField(getString(R.string.s_305), chatCtx().toString(), getString(R.string.s_313)) { chatCtxEdit = it },
                numField(getString(R.string.s_306), chatMaxOut().toString(), getString(R.string.s_315)) { chatMaxOutEdit = it },
            ),
            matchWrap().apply { topMargin = dp(8) })
        chatCard.addView(
            twoCols(
                numField(getString(R.string.s_307), chatTemp().toString(), "") { chatTempEdit = it },
                numField(getString(R.string.s_308), chatTopK().toString(), "") { chatTopKEdit = it },
            ),
            matchWrap().apply { topMargin = dp(8) })
        chatCard.addView(
            twoCols(
                numField(getString(R.string.s_309), chatTopP().toString(), "") { chatTopPEdit = it },
                numField(getString(R.string.s_310), chatRep().toString(), getString(R.string.s_315)) { chatRepEdit = it },
            ),
            matchWrap().apply { topMargin = dp(8) })
        chatCard.addView(
            twoCols(
                numField(getString(R.string.s_311), chatBudget().toString(), getString(R.string.s_316)) { chatBudgetEdit = it },
                numField(getString(R.string.s_312), chatSeed().toString(), getString(R.string.s_314)) { chatSeedEdit = it },
            ),
            matchWrap().apply { topMargin = dp(8) })
        chatCard.addView(smallButton(getString(R.string.s_249)) { resetChatParams() },
            matchWrap().apply { topMargin = dp(8) })
        // 建完界面后按当前选中的模型回填一次参数
        loadChatParamsIntoUi()

        // ================= 绘图模型 =================
        val drawCard = sectionCard()
        drawCard.addView(pageTitle(getString(R.string.s_155)))
        drawCard.addView(
            hintText(getString(R.string.s_030)),
            matchWrap().apply { topMargin = dp(4) }
        )
        val dStatus = TextView(this).apply {
            text = getString(R.string.s_113)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        drawModelStatus = dStatus
        drawCard.addView(dStatus, matchWrap().apply { topMargin = dp(6) })
        // 上次崩溃信息（自捕获，供排查）
        if (pendingDrawCrash) {
            drawCard.addView(TextView(this).apply {
                text = getString(R.string.s_035) + (pendingDrawStage ?: getString(R.string.s_197))
                textSize = 11f
                setTextColor(C_ERR)
                setPadding(0, dp(6), 0, 0)
            }, matchWrap())
        }
        takeCrashLog()?.let { log ->
            drawCard.addView(TextView(this).apply {
                text = getString(R.string.s_038) + log.takeLast(1200)
                textSize = 10.5f
                setTextColor(C_ERR)
                setPadding(0, dp(6), 0, 0)
            }, matchWrap())
        }

        val importBtn = actionButton(getString(R.string.s_190)) { pickDrawModelFile() }
        drawImportBtn = importBtn
        drawCard.addView(importBtn, matchWrap().apply { topMargin = dp(8) })

        drawProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            progressTintList = ColorStateList.valueOf(C_PRIMARY)
            indeterminateTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        drawCard.addView(drawProgress, matchWrap().apply { topMargin = dp(8) })

        drawSavedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        drawCard.addView(drawSavedContainer, matchWrap().apply { topMargin = dp(6) })
        // 加载 / 卸载合成一个按钮：未加载时点击 = 加载，已加载时点击 = 卸载
        drawToggleBtn = actionButton(getString(R.string.s_061)) { onDrawToggleClick() }
        drawCard.addView(drawToggleBtn, matchWrap().apply { topMargin = dp(8) })
        drawCard.addView(
            smallButton(getString(R.string.s_123)) {
                val f = File(filesDir, "draw/.loadstage")
                val log = if (f.exists()) runCatching { f.readText() }.getOrDefault(getString(R.string.s_198)) else getString(R.string.s_196)
                val body = log.takeLast(8000)
                val tv = TextView(this).apply {
                    text = body
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                val sc = ScrollView(this).apply { addView(tv) }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.s_157))
                    .setView(sc)
                    .setPositiveButton(getString(R.string.s_071)) { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ponko-draw-log", body))
                        toast(getString(R.string.s_087))
                    }
                    .setNeutralButton(getString(R.string.s_136)) { _, _ ->
                        runCatching { f.delete() }
                        toast(getString(R.string.s_112))
                    }
                    .setNegativeButton(getString(R.string.s_049), null)
                    .show()
            },
            matchWrap().apply { topMargin = dp(10) }
        )

        // ---- 子区块：LoRA 加速（隶属「绘图模型」） ----
        drawCard.addView(divider(), matchWrap().apply { topMargin = dp(14) })
        drawCard.addView(subTitle(getString(R.string.s_021)), matchWrap().apply { topMargin = dp(12) })
        drawCard.addView(
            hintText(getString(R.string.s_017)),
            matchWrap().apply { topMargin = dp(4) }
        )
        val lStatus = TextView(this).apply {
            text = getString(R.string.s_117)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        loraStatusTv = lStatus
        drawCard.addView(lStatus, matchWrap().apply { topMargin = dp(6) })

        loraBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        drawCard.addView(loraBox, matchWrap().apply { topMargin = dp(6) })

        drawCard.addView(
            actionButton(getString(R.string.s_039)) { pickLoraSource() },
            matchWrap().apply { topMargin = dp(8) }
        )
        drawCard.addView(
            actionButton(getString(R.string.s_219)) { pickLoraFile() },
            matchWrap().apply { topMargin = dp(8) }
        )
        drawCard.addView(
            smallButton(getString(R.string.s_051)) { confirmDeleteLora() },
            matchWrap().apply { topMargin = dp(10) }
        )

        // ================= 打标模型（Tagger）：独立成页 =================
        val taggerCard = sectionCard()
        taggerCard.addView(pageTitle(getString(R.string.s_241)))
        taggerCard.addView(
            hintText(getString(R.string.s_242)),
            matchWrap().apply { topMargin = dp(4) }
        )
        val tStatus = TextView(this).apply {
            text = getString(R.string.s_245)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }
        taggerStatusTv = tStatus
        taggerCard.addView(tStatus, matchWrap().apply { topMargin = dp(6) })

        taggerOnnxBtn = actionButton(getString(R.string.s_243)) { pickTaggerOnnx() }
        taggerCard.addView(taggerOnnxBtn, matchWrap().apply { topMargin = dp(8) })
        taggerCsvBtn = actionButton(getString(R.string.s_244)) { pickTaggerCsv() }
        taggerCard.addView(taggerCsvBtn, matchWrap().apply { topMargin = dp(8) })

        taggerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        taggerCard.addView(taggerBox, matchWrap().apply { topMargin = dp(6) })

        taggerLoadBtn = actionButton(getString(R.string.s_254)) { onTaggerToggleClick() }
        taggerCard.addView(taggerLoadBtn, matchWrap().apply { topMargin = dp(8) })

        // ---- 顶部常驻「运行方式」+ 页签 + 左右翻页 ----
        header.addView(card)
        root.addView(header)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        modelsTabChat = tabItem(getString(R.string.s_076))
        modelsTabDraw = tabItem(getString(R.string.s_155))
        modelsTabTag = tabItem(getString(R.string.s_241))
        modelsTabChat.setOnClickListener { switchModelsTab(0) }
        modelsTabDraw.setOnClickListener { switchModelsTab(1) }
        modelsTabTag.setOnClickListener { switchModelsTab(2) }
        for (t in listOf(modelsTabChat, modelsTabDraw, modelsTabTag)) {
            tabBar.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabBar)

        modelsPager = ViewPager(this).apply {
            adapter = ModelsPaneAdapter(
                listOf(modelsPane(chatCard), modelsPane(drawCard), modelsPane(taggerCard))
            )
            addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    highlightModelsTabs(position)
                }
            })
        }
        root.addView(modelsPager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshLoraUi()
        refreshDrawModels()
        refreshTaggerUi()
        switchModelsTab(0)
        return root
    }

    /** 模型页：单个面板包一层可纵向滚动的 ScrollView */
    private fun modelsPane(inner: View) = ScrollView(this).apply {
        clipToPadding = false
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(inner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    /** 模型页顶部页签样式（与绘图页一致） */
    private fun tabItem(t: String) = TextView(this).apply {
        text = t
        textSize = 13.5f
        gravity = Gravity.CENTER
        setPadding(0, dp(11), 0, dp(11))
    }

    /** 切换模型页的「对话模型 / 绘图模型 / 打标模型」 */
    private fun switchModelsTab(index: Int) {
        if (::modelsPager.isInitialized) modelsPager.setCurrentItem(index, true)
        highlightModelsTabs(index)
        when (index) {
            0 -> { refreshSavedModels(); refreshMmprojUi() }
            1 -> { refreshDrawModels(); refreshLoraUi() }
            2 -> refreshTaggerUi()
        }
    }

    /** 同步模型页页签高亮；手势翻页时也由它更新 */
    private fun highlightModelsTabs(selected: Int) {
        if (!::modelsTabChat.isInitialized) return
        for ((i, tv) in listOf(modelsTabChat, modelsTabDraw, modelsTabTag).withIndex()) {
            val sel = i == selected
            tv.setTextColor(if (sel) C_PRIMARY else C_SUBTEXT)
            tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setBackgroundColor(if (sel) 0xFFEDF1FF.toInt() else Color.WHITE)
        }
    }

    /** 模型页的翻页适配器：直接复用已建好的三个面板，不重建 */
    private class ModelsPaneAdapter(private val panes: List<View>) : PagerAdapter() {
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

    /** 关于页：角色原图 + 说明。 */
    private fun buildSettingsPage(): View {
        val sv = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val card = card()

        card.addView(pageTitle(getString(R.string.s_048)))
        card.addView(hintText(getString(R.string.s_142)))
        card.addView(hintText(getString(R.string.s_322, installedAt())))
        val portrait = ImageView(this).apply {
            setImageResource(R.drawable.about_portrait)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(portrait, matchWrap())
        card.addView(hintText(getString(R.string.s_025)))

        card.addView(pageTitle(getString(R.string.s_104)), matchWrap().apply { topMargin = dp(16) })
        card.addView(hintText(getString(R.string.s_097)))

        card.addView(pageTitle(getString(R.string.s_171)), matchWrap().apply { topMargin = dp(16) })
        card.addView(hintText(getString(R.string.s_140)))

        card.addView(pageTitle(getString(R.string.s_043)), matchWrap().apply { topMargin = dp(16) })
        card.addView(hintText("visitor257"))

        card.addView(pageTitle(getString(R.string.s_194)), matchWrap().apply { topMargin = dp(16) })
        card.addView(linkText(getString(R.string.s_107), "https://github.com/visitor257/Ponko"))

        sv.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_CARD, 16)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        elevation = dp(1).toFloat()
    }

    /** 模型页的分区卡片：每块内容一张，底部留 10dp 间距，避免全塞进一张大卡里。 */
    private fun sectionCard(): LinearLayout = card().apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun pageTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(C_TEXT)
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 分区内的子标题（比 pageTitle 小一号），用于「绘图模型」下的 LoRA / tagger 等子块。 */
    private fun subTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(C_TEXT)
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 卡片内的细分隔线 */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(0x1F000000)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        )
    }

    private fun hintText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(C_SUBTEXT)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(5), 0, 0)
    }

    /** 关于页的可点击链接（点开系统浏览器） */
    private fun linkText(label: String, url: String): TextView =
        hintText(label).apply {
            isClickable = true
            setTextColor(C_PRIMARY)
            setOnClickListener {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }.onFailure { toast(getString(R.string.s_134)) }
            }
        }

    // ---- 底部导航 ----

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(6).toFloat()
        }
        val items = listOf(
            Triple("💬", getString(R.string.s_074), 0),
            Triple("🧠", getString(R.string.s_124), 1),
            Triple("🎨", getString(R.string.s_150), 2),
            Triple("ℹ️", getString(R.string.s_048), 3),
        )
        for ((icon, label, idx) in items) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(7), 0, dp(7))
                setOnClickListener { switchTab(idx) }
            }
            val iconTv = TextView(this).apply { text = icon; textSize = 17f; gravity = Gravity.CENTER }
            val labelTv = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(1), 0, 0)
            }
            item.addView(iconTv, matchWrap())
            item.addView(labelTv, matchWrap())
            navIcons += iconTv
            navLabels += labelTv
            nav.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return nav
    }

    private fun switchTab(index: Int) {
        currentTab = index
        tabChat.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabModels.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabDraw.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 3) View.VISIBLE else View.GONE
        // 切到「模型」页时刷新绘图模型 / LoRA 列表，避免导入后状态滞后
        if (index == 1) { refreshDrawModels(); refreshLoraUi(); refreshTaggerUi() }
        inputBar.visibility = if (index == 0) View.VISIBLE else View.GONE
        val isChat = index == 0
        appBarMenu.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
        appBarPlus.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
        for (i in navIcons.indices) {
            val c = if (i == currentTab) C_PRIMARY else C_SUBTEXT
            navIcons[i].setTextColor(c)
            navLabels[i].setTextColor(c)
        }
        if (index == 0) scrollToBottom()
    }

    // ---- 左侧对话抽屉 ----

    private fun buildDrawer(rootFrame: FrameLayout) {
        drawerMask = View(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            visibility = View.GONE
            alpha = 0f
            setOnClickListener { closeDrawer() }
        }
        rootFrame.addView(drawerMask, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        drawerPanel.addView(TextView(this).apply {
            text = getString(R.string.s_074)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(C_PRIMARY)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }, matchWrap())

        val sv = ScrollView(this)
        drawerBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        sv.addView(drawerBody, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        drawerPanel.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        drawerPanel.addView(TextView(this).apply {
            text = "Ponko"
            textSize = 11f
            setTextColor(C_SUBTEXT)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(14))
        }, matchWrap())

        val w = dp(drawerWidthDp)
        rootFrame.addView(drawerPanel, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })
        drawerPanel.translationX = -w.toFloat()
    }

    private fun refreshDrawer() {
        drawerBody.removeAllViews()
        drawerBody.addView(actionButton(getString(R.string.s_201)) { closeDrawer(); newSession() },
            matchWrap().apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })

        for (s in sessions.sortedByDescending { it.id }) {
            val isCur = s === current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
                isClickable = true
                if (isCur) setBackgroundColor(C_PRIMARY_SOFT)
            }
            row.addView(TextView(this).apply {
                text = s.title
                textSize = 14f
                setTextColor(if (isCur) C_PRIMARY else C_TEXT)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = getString(R.string.v_001, (s.turns.size))
                textSize = 11f
                setTextColor(C_SUBTEXT)
                setPadding(0, 0, dp(6), 0)
            }, wrapWrap())
            row.addView(TextView(this).apply {
                text = "🗑"
                textSize = 14f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isClickable = true
                setOnClickListener { confirmDeleteSession(s) }
            }, wrapWrap())
            row.setOnClickListener { closeDrawer(); switchTo(s) }
            drawerBody.addView(row, matchWrap())
            drawerBody.addView(View(this).apply { setBackgroundColor(Color.rgb(238, 240, 245)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(dp(16), 0, 0, 0)
                })
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        refreshDrawer()
        drawerMask.visibility = View.VISIBLE
        drawerMask.alpha = 0f
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.translationX = -dp(drawerWidthDp).toFloat()
        drawerPanel.animate().translationX(0f).setDuration(220).start()
        drawerMask.animate().alpha(1f).setDuration(220).start()
        drawerOpen = true
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerPanel.animate().translationX(-dp(drawerWidthDp).toFloat()).setDuration(180)
            .withEndAction { drawerPanel.visibility = View.GONE }.start()
        drawerMask.animate().alpha(0f).setDuration(180)
            .withEndAction { drawerMask.visibility = View.GONE }.start()
        drawerOpen = false
    }

    private fun buildInputBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(8))
            elevation = dp(6).toFloat()
        }

        thinkCheck = CheckBox(this).apply {
            text = getString(R.string.s_105)
            textSize = 12.5f
            setTextColor(C_TEXT)
            isChecked = true
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        bar.addView(thinkCheck, matchWrap())

        // 待发送图片预览条（横向可滚动，每张一个小缩略图 + 右上角 ×）
        attachPreviewStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(6))
        }
        attachPreviewBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        attachPreviewStrip.addView(attachPreviewBox)
        bar.addView(attachPreviewStrip, matchWrap())

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        inputEdit = EditText(this).apply {
            hint = getString(R.string.s_180)
            textSize = 15f
            setTextColor(C_TEXT)
            setHintTextColor(C_SUBTEXT)
            background = rounded(Color.rgb(243, 245, 249), 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
        row.addView(inputEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        attachButton = TextView(this).apply {
            text = "＋"
            textSize = 20f
            setTextColor(C_PRIMARY)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY_SOFT, 22)
            isClickable = true
            setOnClickListener { onAttachClick() }
        }
        row.addView(attachButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })

        sendButton = TextView(this).apply {
            text = "↑"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(C_PRIMARY, 22)
            isClickable = true
            setOnClickListener { onSendOrStop() }
        }
        row.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        bar.addView(row, matchWrap())
        return bar
    }

    // ================= 聊天发图（.litertlm 多模态） =================

    /** 探测 .litertlm 模型是否支持图像输入；任何异常都按「不支持」处理。 */
    private fun probeVision(path: String): Boolean = try {
        Capabilities(path).use { it.inputModalities().vision }
    } catch (_: Throwable) {
        false
    }

    private fun onAttachClick() {
        // 生成过程中也允许先把附件备好（发送仍会被 busy 拦住）
        // 注意：这里只管「模型加载了没」——文本文件任何模型都能读，不该被「必须多模态」挡住
        if (engine == null && llamaModel == null) { toast(getString(R.string.s_273)); return }
        showAttachDrawer()
    }

    /** 「＋」抽屉：贴着输入栏向上展开，可选拍照 / 图库。 */
    private fun showAttachDrawer() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 14)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // 外面再套一层留 8dp 余量：给阴影和缩放动画留空间，避免被弹窗边界裁掉
        val outer = FrameLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(box, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        fun mkItem(icon: String, label: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = "$icon  $label"
                textSize = 14.5f
                setTextColor(C_TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                setOnClickListener { onClick() }
            }

        val popup = PopupWindow(outer, dp(150) + dp(16), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            // 透明背景：真正的外观画在 box 上，这样手动缩放动画才不会被窗口背景挡住
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        // 收起：先播完动画再 dismiss
        fun close(action: () -> Unit) {
            box.animate().alpha(0f).scaleY(0.75f)
                .setDuration(120)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    popup.dismiss()
                    action()
                }
                .start()
        }

        box.addView(mkItem("📷", getString(R.string.s_270)) { close { takePhoto() } }, matchWrap())
        box.addView(mkItem("🖼", getString(R.string.s_271)) { close { pickChatImage() } }, matchWrap())
        box.addView(mkItem("📄", getString(R.string.s_295)) { close { pickChatFile() } }, matchWrap())

        // 先手动量一次，在 show 之前就把动画初值设好（否则会闪一帧全尺寸）
        box.measure(
            View.MeasureSpec.makeMeasureSpec(dp(150), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        box.pivotY = box.measuredHeight.toFloat()
        box.alpha = 0f
        box.scaleY = 0.72f

        // 向上展开：弹窗底边贴在输入栏上沿
        val loc = IntArray(2)
        inputBar.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        popup.showAtLocation(
            inputBar, Gravity.BOTTOM or Gravity.END, dp(10) - dp(8), screenH - loc[1] + dp(6)
        )

        // 入场动画：从底部往上长出来 + 淡入（不依赖 PopupWindow 的系统窗口动画）
        box.animate().alpha(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 安卓 9 及以下写相册/拍照需要运行时 WRITE_EXTERNAL_STORAGE；安卓 10+ 走 MediaStore，免权限，直接执行。 */
    private fun withLegacyStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingStorageAction = action
        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM_STORAGE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM_STORAGE) return
        val act = pendingStorageAction
        pendingStorageAction = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) act?.invoke()
        else toast(getString(R.string.s_294))
    }

    private fun takePhoto() = withLegacyStorage { doTakePhoto() }

    private fun doTakePhoto() {
        val uri = try {
            contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ponko_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
            )
        } catch (_: Throwable) {
            null
        }
        if (uri == null) { toast(getString(R.string.s_277)); return }
        pendingCameraUri = uri
        try {
            startActivityForResult(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                },
                REQ_TAKE_PHOTO
            )
        } catch (_: Throwable) {
            toast(getString(R.string.s_274))
        }
    }

    private fun pickChatImage() {
        startActivityForResult(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQ_PICK_CHAT_IMAGE
        )
    }

    /** 选本地文件（文本类），支持多选；解析出的文字随下一条消息发给模型。 */
    private fun pickChatFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQ_PICK_FILE
        )
    }

    /** 最多读 MAX_FILE_BYTES 字节；超了返回 null（不当文本文件处理）。 */
    private fun readAllCapped(ins: java.io.InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = ins.read(chunk)
            if (n <= 0) break
            total += n
            if (total > MAX_FILE_BYTES) return null
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /**
     * 把一个文件读成文字：二进制文件直接拒；UTF-8 解不出来就按 GBK 再试（中文 txt 常见）；
     * 超过 MAX_FILE_CHARS 就按行截断——本地模型只有 4096 上下文，整篇塞进去会把对话挤没。
     */
    private fun readTextFile(uri: Uri): FileRead {
        val raw = try {
            contentResolver.openInputStream(uri)?.use { ins -> readAllCapped(ins) }
        } catch (_: Throwable) {
            null
        } ?: return FileRead(null, getString(R.string.s_296))
        if (raw.isEmpty()) return FileRead(null, getString(R.string.s_296))
        // PDF：先把话说清楚，别让用户以为 App 坏了
        if (raw.size >= 4 && raw[0] == '%'.code.toByte() && raw[1] == 'P'.code.toByte() &&
            raw[2] == 'D'.code.toByte() && raw[3] == 'F'.code.toByte()
        ) {
            return FileRead(null, getString(R.string.s_301))
        }
        // 前 4KB 里出现 NUL 就当成二进制（zip/apk/exe/图片都过不了这一关）
        for (i in 0 until minOf(raw.size, 4096)) {
            if (raw[i] == 0.toByte()) return FileRead(null, getString(R.string.s_296))
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw)).toString()
        } catch (_: Throwable) {
            try { String(raw, charset("GBK")) } catch (_: Throwable) { return FileRead(null, getString(R.string.s_296)) }
        }
        val clean = text.replace("\u0000", "")
        val body = fitTokens(clean, maxFileTokens())
        val truncated = body.length < clean.length
        return FileRead(PendingFile(queryDisplayName(uri) ?: "file.txt", raw.size.toLong(), body, truncated), null)
    }

    /** 解码图片并按最长边下采样，避免大图直接吃内存。 */
    private fun decodeChatImage(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / sample > 1280) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Throwable) {
        null
    }

    private fun addPendingImage(bmp: Bitmap, jpeg: ByteArray) {
        if (pendingImages.size >= MAX_ATTACH) {
            toast(getString(R.string.s_279))
            return
        }
        pendingImages.add(kotlin.Pair(bmp, jpeg))
        rebuildAttachPreview()
    }

    private fun removePendingImage(index: Int) {
        if (index in pendingImages.indices) pendingImages.removeAt(index)
        rebuildAttachPreview()
    }

    private fun clearPendingImages() {
        pendingImages.clear()
        rebuildAttachPreview()
    }

    private fun addPendingFile(f: PendingFile) {
        if (pendingFiles.size >= MAX_FILES) {
            toast(getString(R.string.s_297))
            return
        }
        pendingFiles.add(f)
        rebuildAttachPreview()
    }

    private fun removePendingFile(index: Int) {
        if (index in pendingFiles.indices) pendingFiles.removeAt(index)
        rebuildAttachPreview()
    }

    private fun clearPendingFiles() {
        pendingFiles.clear()
        rebuildAttachPreview()
    }

    /** 重建预览条：横向一排缩略图，每张右上角一个 × 撤掉。 */
    private fun rebuildAttachPreview() {
        attachPreviewBox.removeAllViews()
        for (i in pendingImages.indices) {
            val chip = FrameLayout(this)
            chip.addView(ImageView(this).apply {
                setImageBitmap(pendingImages[i].first)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.rgb(243, 245, 249), 10)
            }, FrameLayout.LayoutParams(dp(56), dp(56)))
            chip.addView(TextView(this).apply {
                text = "×"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(C_ERR, 10)
                isClickable = true
                setOnClickListener { removePendingImage(i) }
            }, FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = Gravity.TOP or Gravity.END
            })
            attachPreviewBox.addView(chip, LinearLayout.LayoutParams(dp(60), dp(56)).apply {
                rightMargin = dp(6)
            })
        }
        // 文件 chip：文件名 + 字数（截断时标注），右上角 × 撤掉
        for (i in pendingFiles.indices) {
            val f = pendingFiles[i]
            val chip = FrameLayout(this)
            chip.addView(TextView(this).apply {
                text = "📄 ${f.name}\n${f.text.length} ${getString(R.string.s_298)}" +
                    if (f.truncated) " · ${getString(R.string.s_299)}" else ""
                textSize = 11f
                setTextColor(C_TEXT)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = rounded(Color.rgb(243, 245, 249), 10)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, FrameLayout.LayoutParams(dp(124), dp(56)))
            chip.addView(TextView(this).apply {
                text = "×"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(C_ERR, 10)
                isClickable = true
                setOnClickListener { removePendingFile(i) }
            }, FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = Gravity.TOP or Gravity.END
            })
            attachPreviewBox.addView(chip, LinearLayout.LayoutParams(dp(128), dp(56)).apply {
                rightMargin = dp(6)
            })
        }
        attachPreviewStrip.visibility =
            if (pendingImages.isEmpty() && pendingFiles.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Bitmap → JPEG 字节（作为 Content.ImageBytes 送给 LiteRT-LM）。 */
    private fun bitmapToJpeg(bmp: Bitmap, quality: Int = 88): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** 用户发的图片也落盘（JPEG），随会话一起恢复。 */
    private fun writeChatJpeg(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * 把待发文件拼成一段文字，随本轮消息一起送给模型。
     * 只发本轮、不进历史：本地模型 4096 上下文，文件留在历史里几轮就把对话挤没了。
     */
    private fun buildFileBlock(files: List<PendingFile>): String {
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        for (f in files) {
            sb.append("【文件：").append(f.name)
            if (f.truncated) sb.append("（已截断，以下为前 ").append(f.text.length).append(" 字）")
            sb.append("】\n")
            sb.append(f.text.trim())
            sb.append("\n【文件结束】\n")
        }
        sb.append(getString(R.string.s_300))
        return sb.toString().trim()
    }

    /** 把待发文件的原始内容编码成 JSON 存进轮次，供「重新生成」还原附件 */
    private fun encodeFiles(files: List<PendingFile>): String? {
        if (files.isEmpty()) return null
        return try {
            val arr = JSONArray()
            for (f in files) {
                arr.put(
                    JSONObject().put("n", f.name).put("b", f.bytes)
                        .put("t", f.text).put("tr", f.truncated)
                )
            }
            arr.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /** 把轮次里存的文件还原到附件条，返回还原成功的个数 */
    private fun restoreFiles(store: String?): Int {
        if (store.isNullOrEmpty()) return 0
        var n = 0
        try {
            val arr = JSONArray(store)
            for (i in 0 until arr.length()) {
                if (pendingFiles.size >= MAX_FILES) break
                val o = arr.getJSONObject(i)
                val body = o.optString("t")
                if (body.isEmpty()) continue
                pendingFiles.add(
                    PendingFile(o.optString("n"), o.optLong("b"), body, o.optBoolean("tr"))
                )
                n++
            }
        } catch (_: Throwable) {
        }
        rebuildAttachPreview()
        return n
    }

    /** 安装/更新时间（用来确认装的是哪一版） */
    private fun installedAt(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(pi.lastUpdateTime))
    } catch (_: Throwable) {
        ""
    }

    /** 粗估 token 数：CJK/全角字符按 1，其余按 0.3。只用来做预算裁剪，不追求精确 */
    private fun estTokens(s: String): Float {
        var t = 0f
        for (c in s) t += if (c.code in 0x1100..0xFFFF) 1f else 0.3f
        return t
    }

    /** 把文本裁到估算 token 不超过 maxTokens（尽量切在行尾） */
    private fun fitTokens(text: String, maxTokens: Int): String {
        if (estTokens(text) <= maxTokens) return text
        var acc = 0f
        var cut = text.length
        for (i in text.indices) {
            acc += if (text[i].code in 0x1100..0xFFFF) 1f else 0.3f
            if (acc > maxTokens) {
                cut = i
                break
            }
        }
        var body = text.substring(0, cut)
        val nl = body.lastIndexOf('\n')
        if (nl > cut / 2) body = body.substring(0, nl)
        return body
    }

    /**
     * 估算 LiteRT 会话的输入 token：系统提示 + 历史 + 本轮要发的内容。
     * 历史里之前发过的文件正文也还留在引擎的上下文里，所以一并算上。
     */
    private fun estimateConversationTokens(s: ChatSession, extra: String, dropLast: Boolean): Float {
        var t = 0f
        if (canDrawFromChat) t += estTokens(drawToolPrompt) + 12f
        val upto = if (dropLast) s.turns.size - 1 else s.turns.size
        for (i in 0 until maxOf(upto, 0)) {
            val tt = s.turns[i]
            t += estTokens(tt.user) + estTokens(tt.answer) + 12f
            tt.fileText?.let { t += estTokens(it) }
        }
        t += estTokens(extra) + 12f
        return t
    }

    /** 判断是不是「上下文塞不下」这类错误（LiteRT Status Code 3 / llama.cpp 的 context 满） */
    private fun isContextOverflow(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("too long") || m.contains("maximum number of tokens") ||
            m.contains("out of context") ||
            (m.contains("context") && (m.contains("full") || m.contains("exceed")))
    }

    // ================= 对话模型参数（模型页可调） =================

    private fun chatPrefs() = getSharedPreferences("ponko", MODE_PRIVATE)

    /** 参数按「模型文件」分别保存；没选模型时用不带后缀的一套当默认 */
    private fun chatModelName(): String? = modelPath?.let { File(it).name }?.ifEmpty { null }

    private fun chatKey(base: String): String {
        val n = chatModelName()
        return if (n == null) base else "$base#$n"
    }

    /** 上下文长度（token）：LiteRT 建引擎 / llama.cpp 建模型时用，改了要重新加载模型 */
    private fun chatCtx(): Int = chatPrefs().getInt(chatKey(KEY_CHAT_CTX), DEF_CHAT_CTX).coerceIn(512, 262144)
    /** 单次最多生成多少 token（只对 gguf 生效，litertlm 没这个开关） */
    private fun chatMaxOut(): Int = chatPrefs().getInt(chatKey(KEY_CHAT_MAXOUT), DEF_CHAT_MAXOUT).coerceIn(64, 65536)
    private fun chatTemp(): Float = chatPrefs().getFloat(chatKey(KEY_CHAT_TEMP), DEF_CHAT_TEMP).coerceIn(0f, 2f)
    private fun chatTopK(): Int = chatPrefs().getInt(chatKey(KEY_CHAT_TOPK), DEF_CHAT_TOPK).coerceIn(0, 500)
    private fun chatTopP(): Float = chatPrefs().getFloat(chatKey(KEY_CHAT_TOPP), DEF_CHAT_TOPP).coerceIn(0.01f, 1f)
    private fun chatRep(): Float = chatPrefs().getFloat(chatKey(KEY_CHAT_REP), DEF_CHAT_REP).coerceIn(0.5f, 2f)
    /** 思考预算（token），0 = 不限 */
    private fun chatBudget(): Int = chatPrefs().getInt(chatKey(KEY_CHAT_BUDGET), DEF_CHAT_BUDGET).coerceIn(0, 65536)
    /** 随机种子，-1 = 每次都换 */
    private fun chatSeed(): Int = chatPrefs().getInt(chatKey(KEY_CHAT_SEED), DEF_CHAT_SEED)

    /** 输入侧 token 预算：上下文留 1/4 给输出 */
    private fun inputTokenBudget(): Int = chatCtx() - chatCtx() / 4

    /** 单个文件最多占的估算 token，跟着上下文缩放 */
    private fun maxFileTokens(): Int = (chatCtx() / 4).coerceIn(600, 8000)

    /** 思考预算：0 表示不限（两个后端都用 -1 表示不限） */
    private fun thinkBudgetOrUnlimited(): Int = if (chatBudget() > 0) chatBudget() else -1

    /** 模型页输入即存盘（存到当前模型名下）；空值/非法值回落到默认，范围裁剪在读取时做 */
    private fun saveChatParams() {
        if (chatUiLoading) return
        fun i(ed: EditText?, def: Int) = ed?.text?.toString()?.trim()?.toIntOrNull() ?: def
        fun f(ed: EditText?, def: Float) = ed?.text?.toString()?.trim()?.toFloatOrNull() ?: def
        chatPrefs().edit()
            .putInt(chatKey(KEY_CHAT_CTX), i(chatCtxEdit, DEF_CHAT_CTX))
            .putInt(chatKey(KEY_CHAT_MAXOUT), i(chatMaxOutEdit, DEF_CHAT_MAXOUT))
            .putFloat(chatKey(KEY_CHAT_TEMP), f(chatTempEdit, DEF_CHAT_TEMP))
            .putInt(chatKey(KEY_CHAT_TOPK), i(chatTopKEdit, DEF_CHAT_TOPK))
            .putFloat(chatKey(KEY_CHAT_TOPP), f(chatTopPEdit, DEF_CHAT_TOPP))
            .putFloat(chatKey(KEY_CHAT_REP), f(chatRepEdit, DEF_CHAT_REP))
            .putInt(chatKey(KEY_CHAT_BUDGET), i(chatBudgetEdit, DEF_CHAT_BUDGET))
            .putInt(chatKey(KEY_CHAT_SEED), i(chatSeedEdit, DEF_CHAT_SEED))
            .apply()
        // 采样参数要在下次发送前重建会话才生效；只有改的正是「已加载的那个模型」时才需要
        chatParamsDirty = modelPath != null && modelPath == loadedModelPath
    }

    /** 把当前模型的参数回填到模型页的输入框（建界面 / 切模型时调） */
    private fun loadChatParamsIntoUi() {
        chatUiLoading = true
        chatCtxEdit?.setText(chatCtx().toString())
        chatMaxOutEdit?.setText(chatMaxOut().toString())
        chatTempEdit?.setText(chatTemp().toString())
        chatTopKEdit?.setText(chatTopK().toString())
        chatTopPEdit?.setText(chatTopP().toString())
        chatRepEdit?.setText(chatRep().toString())
        chatBudgetEdit?.setText(chatBudget().toString())
        chatSeedEdit?.setText(chatSeed().toString())
        chatUiLoading = false
        val n = chatModelName()
        chatParamsOwnerTv?.text =
            if (n == null) getString(R.string.s_319) else getString(R.string.s_318, n)
    }

    /** 把当前模型的参数恢复成内置默认值（上下文等要重新加载模型才生效） */
    private fun resetChatParams() {
        chatUiLoading = true
        chatPrefs().edit()
            .putInt(chatKey(KEY_CHAT_CTX), DEF_CHAT_CTX)
            .putInt(chatKey(KEY_CHAT_MAXOUT), DEF_CHAT_MAXOUT)
            .putFloat(chatKey(KEY_CHAT_TEMP), DEF_CHAT_TEMP)
            .putInt(chatKey(KEY_CHAT_TOPK), DEF_CHAT_TOPK)
            .putFloat(chatKey(KEY_CHAT_TOPP), DEF_CHAT_TOPP)
            .putFloat(chatKey(KEY_CHAT_REP), DEF_CHAT_REP)
            .putInt(chatKey(KEY_CHAT_BUDGET), DEF_CHAT_BUDGET)
            .putInt(chatKey(KEY_CHAT_SEED), DEF_CHAT_SEED)
            .apply()
        chatUiLoading = false
        loadChatParamsIntoUi()
        chatParamsDirty = modelPath != null && modelPath == loadedModelPath
        toast(getString(R.string.s_317))
    }

    /** 参数格：小标题 + 数字输入 + 说明；keep 用来把输入框存进字段 */
    private fun numField(label: String, def: String, hint: String, keep: (EditText) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(C_SUBTEXT)
        }, matchWrap())
        val ed = EditText(this).apply {
            setText(def)
            textSize = 13f
            setTextColor(C_TEXT)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setBackgroundColor(0xFFF2F3F5.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isSingleLine = true
        }
        // 监听放在 setText 之后，避免建界面时误触发存盘
        ed.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = saveChatParams()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        keep(ed)
        box.addView(ed, matchWrap().apply { topMargin = dp(4) })
        box.addView(TextView(this).apply {
            text = hint
            textSize = 10.5f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(3), 0, 0)
        }, matchWrap())
        return box
    }

    /** 一行放两格参数 */
    private fun twoCols(a: View, b: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
    }

    // ================= small view factories =================

    private fun actionButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(C_PRIMARY, 12)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun smallButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(C_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(C_PRIMARY_SOFT, 10)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun iconButton(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(Color.parseColor("#33FFFFFF"), 12)
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ================= status =================

    private fun setStatus(text: String, color: Int) {
        statusTv.text = text
        statusDot.background = rounded(color, 99)
    }

    // ================= model loading =================

    /** 对话模型：选一个 .litertlm / .gguf 文件 */
    private fun pickModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_MODEL)
    }

    /** gguf 多模态：选一个 mmproj（视觉投影）文件，.gguf */
    private fun pickMmprojFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_MMPROJ)
    }

    /** 绘图模型：选一个 .gguf 文件（stable-diffusion.cpp 格式，单文件） */
    private fun pickDrawModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_DRAW_MODEL)
    }

    /** 本地 LoRA：选一个 .safetensors 文件 */
    private fun pickLoraFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_LORA)
    }

    /** 打标模型：选一个 .onnx 文件 */
    private fun pickTaggerOnnx() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_TAGGER_ONNX)
    }

    /** 标签表：选一个 .csv 文件 */
    private fun pickTaggerCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_TAGGER_CSV)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (drawPage?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_MODEL && resultCode == RESULT_OK) {
            data?.data?.let { copyModelToPrivate(it) }
        }
        if (requestCode == REQ_PICK_MMPROJ && resultCode == RESULT_OK) {
            data?.data?.let { copyMmprojToPrivate(it) }
        }
        if (requestCode == REQ_PICK_CHAT_IMAGE && resultCode == RESULT_OK) {
            val uris = ArrayList<Uri>()
            val clip = data?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            } else {
                data?.data?.let { uris.add(it) }
            }
            if (uris.isEmpty()) return
            scope.launch {
                var added = 0
                for (u in uris) {
                    if (pendingImages.size >= MAX_ATTACH) { toast(getString(R.string.s_279)); break }
                    val bmp = withContext(Dispatchers.IO) { decodeChatImage(u) } ?: continue
                    val jpeg = withContext(Dispatchers.IO) { bitmapToJpeg(bmp) }
                    addPendingImage(bmp, jpeg)
                    added++
                }
                if (added == 0 && pendingImages.isEmpty()) toast(getString(R.string.s_275))
            }
        }
        if (requestCode == REQ_TAKE_PHOTO) {
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (resultCode != RESULT_OK || uri == null) return
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { decodeChatImage(uri) }
                if (bmp == null) { toast(getString(R.string.s_275)); return@launch }
                val jpeg = withContext(Dispatchers.IO) { bitmapToJpeg(bmp) }
                addPendingImage(bmp, jpeg)
            }
        }
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            val uris = ArrayList<Uri>()
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            scope.launch {
                var err: String? = null
                for (u in uris) {
                    if (pendingFiles.size >= MAX_FILES) { toast(getString(R.string.s_297)); break }
                    val r = withContext(Dispatchers.IO) { readTextFile(u) }
                    val f = r.file
                    if (f == null) {
                        if (err == null) err = r.error
                        continue
                    }
                    addPendingFile(f)
                }
                if (err != null) toast(err)
            }
        }
        if (requestCode == REQ_PICK_DRAW_MODEL && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            setBusy(true)
            setDrawBusy(true)
            showDrawProgress(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.prepareFromFile(
                    uri,
                    onStage = { stage -> drawModelStatus?.text = stage },
                    onProgress = { done, total -> updateDrawProgress(done, total) }
                )
                setBusy(false)
                setDrawBusy(false)
                showDrawProgress(false)
                if (err == null) {
                    // 导入成功：把最新复制的那个默认标为选用
                    dpg.listModels().firstOrNull()?.let { drawMainPath = it.absolutePath }
                    drawModelStatus?.text = dpg.modelSummary()
                    setStatus(getString(R.string.s_161), C_OK)
                    toast(getString(R.string.s_091))
                } else {
                    drawModelStatus?.text = err
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
                refreshDrawModels()
            }
        }
        if (requestCode == REQ_PICK_LORA && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            setBusy(true)
            setDrawBusy(true)
            showDrawProgress(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.importLoraFile(
                    uri,
                    onStage = { stage -> loraStatusTv?.text = stage },
                    onProgress = { done, total -> updateDrawProgress(done, total) }
                )
                setBusy(false)
                setDrawBusy(false)
                showDrawProgress(false)
                if (err == null) {
                    setStatus(getString(R.string.s_220), C_OK)
                    toast(getString(R.string.s_220))
                } else {
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
                refreshLoraUi()
            }
        }
        if (requestCode == REQ_TAGGER_ONNX && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            setBusy(true)
            setDrawBusy(true)
            showDrawProgress(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.importTaggerModel(
                    uri,
                    onStage = { stage -> taggerStatusTv?.text = stage },
                    onProgress = { done, total -> updateDrawProgress(done, total) }
                )
                setBusy(false)
                setDrawBusy(false)
                showDrawProgress(false)
                if (err == null) {
                    setStatus(getString(R.string.s_267), C_OK)
                    toast(getString(R.string.s_269))
                } else {
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
                refreshTaggerUi()
            }
        }
        if (requestCode == REQ_TAGGER_CSV && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dpg = drawPage ?: return
            setBusy(true)
            setDrawBusy(true)
            showDrawProgress(true)
            setStatus(getString(R.string.s_131), C_WARN)
            scope.launch {
                val err = dpg.importTaggerCsv(
                    uri,
                    onStage = { stage -> taggerStatusTv?.text = stage },
                    onProgress = { done, total -> updateDrawProgress(done, total) }
                )
                setBusy(false)
                setDrawBusy(false)
                showDrawProgress(false)
                if (err == null) {
                    setStatus(getString(R.string.s_268), C_OK)
                    toast(getString(R.string.s_269))
                } else {
                    setStatus(getString(R.string.s_158), C_ERR)
                    toast(err)
                }
                refreshTaggerUi()
            }
        }
    }

    /** Models can't be loaded from a content:// URI, so copy them to a real file first. */
    private fun copyModelToPrivate(uri: Uri) {
        setBusy(true)
        setStatus(getString(R.string.s_130), C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                val name = queryDisplayName(uri) ?: ("model_${System.currentTimeMillis()}.litertlm")
                val dest = File(dir, name)
                if (!dest.exists()) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(getString(R.string.s_111))
                    val total = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                    input.use { ins ->
                        dest.outputStream().use { outs ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                outs.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = (copied * 100 / total).toInt()
                                    withContext(Dispatchers.Main) { progressBar.progress = pct }
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    modelPath = dest.absolutePath
                    modelInfoText.text = getString(R.string.v_002, (dest.name), (fmtSize(dest.length())))
                    setStatus(getString(R.string.s_093), C_WARN)
                    refreshSavedModels()
                    loadChatParamsIntoUi()
                    toast(getString(R.string.v_003, (dest.name)))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus(getString(R.string.s_072), C_ERR)
                    toast(getString(R.string.v_004, (e.message)))
                }
            } finally {
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE; setBusy(false) }
            }
        }
    }

    /** mmproj 同样只能读真实路径，先复制到私有目录。 */
    private fun copyMmprojToPrivate(uri: Uri) {
        val name = queryDisplayName(uri) ?: "mmproj_${System.currentTimeMillis()}.gguf"
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            toast(getString(R.string.s_285))
            return
        }
        setBusy(true)
        setStatus(getString(R.string.s_130), C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "mmproj").apply { mkdirs() }
                val dest = File(dir, name)
                if (!dest.exists()) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(getString(R.string.s_111))
                    val total = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                    input.use { ins ->
                        dest.outputStream().use { outs ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                outs.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = (copied * 100 / total).toInt()
                                    withContext(Dispatchers.Main) { progressBar.progress = pct }
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    mmprojPath = dest.absolutePath
                    refreshMmprojUi()
                    setStatus(getString(R.string.s_286, (dest.name)), C_OK)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus(getString(R.string.s_072), C_ERR)
                    toast(getString(R.string.v_004, (e.message)))
                }
            } finally {
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE; setBusy(false) }
            }
        }
    }

    /** 刷新 mmproj：顶部状态文案 + 已导入 mmproj 的可选列表（含「不使用」）。 */
    private fun refreshMmprojUi() {
        val f = mmprojPath?.let { File(it) }
        mmprojStatusTv?.text = if (f != null && f.isFile) {
            getString(R.string.s_284, (f.name))
        } else {
            getString(R.string.s_283)
        }
        val box = mmprojContainer ?: return
        box.removeAllViews()
        val dir = File(filesDir, "mmproj")
        val files = dir.listFiles { x -> x.isFile && x.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(TextView(this).apply {
            text = getString(R.string.s_289)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }, matchWrap())
        // 「不使用」项：让用户随时退回纯文字 gguf
        box.addView(mmprojChip(getString(R.string.s_287), mmprojPath == null, null))
        for (x in files) {
            box.addView(mmprojChip(x.name, x.absolutePath == mmprojPath, x))
        }
    }

    /** 一个 mmproj 选项：点击切换选择，长按删除（「不使用」项没有文件，不能删）。 */
    private fun mmprojChip(label: String, selected: Boolean, file: File?): TextView {
        val chip = TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(if (selected) C_PRIMARY else C_TEXT)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(if (selected) C_PRIMARY_SOFT else Color.rgb(247, 248, 251), 10,
                strokeDp = if (selected) 1 else 0, strokeColor = C_PRIMARY)
            isClickable = true
        }
        chip.setOnClickListener {
            val want = file?.absolutePath
            if (want == mmprojPath) return@setOnClickListener
            mmprojPath = want
            refreshMmprojUi()
            // mmproj 是加载模型时挂上去的，已经加载的模型要重新加载才生效
            if (llamaModel != null) toast(getString(R.string.s_290))
        }
        if (file != null) chip.setOnLongClickListener { confirmDeleteMmproj(file); true }
        return chip
    }

    /** 删除一个已导入的 mmproj；正在用的会同时取消选择。 */
    private fun confirmDeleteMmproj(f: File) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.s_288, (f.name)))
            .setPositiveButton(getString(R.string.s_258)) { _, _ ->
                runCatching { f.delete() }
                if (mmprojPath == f.absolutePath) mmprojPath = null
                refreshMmprojUi()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    // ================= saved models =================

    /** Scan filesDir/models and render a tappable list of already-copied models. */
    private fun refreshSavedModels() {
        savedContainer.removeAllViews()
        val dir = File(filesDir, "models")
        val files = dir.listFiles { f ->
            f.isFile && (
                f.name.endsWith(".litertlm", ignoreCase = true) ||
                    f.name.endsWith(".gguf", ignoreCase = true)
                )
        }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            savedContainer.visibility = View.GONE
            return
        }
        savedContainer.visibility = View.VISIBLE
        savedContainer.addView(TextView(this).apply {
            text = getString(R.string.s_089)
            textSize = 12f
            setTextColor(C_SUBTEXT)
        }, matchWrap())

        for (f in files) {
            val sel = f.absolutePath == modelPath
            val isGguf = f.name.endsWith(".gguf", ignoreCase = true)
            // .gguf 可能是语言模型也可能是画图模型，读头部元数据判断
            val kind = if (isGguf) ggufKindCache.getOrPut(f.absolutePath) { GgufProbe.probe(f) }
            else null
            val isImageModel = kind == GgufProbe.Kind.IMAGE
            val tag = when {
                isImageModel -> getString(R.string.s_151)
                isGguf -> "GGUF"
                else -> "LiteRT"
            }
            val chip = TextView(this).apply {
                text = "▶ ${f.name}　[$tag] ${fmtSize(f.length())}"
                textSize = 12.5f
                setTextColor(if (isImageModel) C_IDLE else if (sel) C_PRIMARY else C_TEXT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (sel) C_PRIMARY_SOFT else Color.rgb(247, 248, 251), 10,
                    strokeDp = if (sel) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener {
                if (isImageModel) {
                    toast(getString(R.string.s_188))
                } else {
                    selectSavedModel(f)
                }
            }
            chip.setOnLongClickListener { confirmDeleteModel(f); true }
            savedContainer.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
    }

    // ================= 已复制的绘图模型 =================

    /** 绘图模型的二合一按钮：未加载 → 加载；已加载 → 卸载。 */
    private fun onDrawToggleClick() {
        val dpg = drawPage
        if (dpg == null) {
            toast(getString(R.string.s_167))
            return
        }
        if (dpg.isReady()) {
            dpg.unloadModel()
            updateThinkEnabled()
            drawModelStatus?.text = dpg.modelSummary()
            setStatus(getString(R.string.s_116), C_IDLE)
            toast(getString(R.string.s_160))
            refreshDrawToggle()
            return
        }
        if (!dpg.hasModel()) {
            toast(getString(R.string.s_185))
            return
        }
        scope.launch {
            drawModelStatus?.text = getString(R.string.s_128)
            val picked = drawMainPath?.let { File(it) }
            dpg.useGpu = (backendSpinner.selectedItem.toString() == "GPU")
            val err = dpg.loadExisting(picked)
            drawModelStatus?.text = if (err == null) dpg.modelSummary() else err
            if (err == null) {
                drawMainPath = dpg.currentMainName()?.let { File(filesDir, "draw/$it").absolutePath }
                dpg.llmLoaded = false
                updateThinkEnabled()
                refreshDrawModels()
                setStatus(getString(R.string.s_162), C_OK)
                toast(getString(R.string.s_159))
            } else {
                setStatus(getString(R.string.s_156), C_ERR)
                toast(err)
            }
            refreshDrawToggle()
        }
    }

    /** 按「绘图模型是否已加载」更新二合一按钮的文案与配色。
     *  与对话模型保持一致：加载=蓝色实心；已加载=浅灰描边（避免误以为还是主操作）。 */
    private fun refreshDrawToggle() {
        val b = drawToggleBtn ?: return
        if (drawPage?.isReady() == true) {
            b.text = getString(R.string.s_063)
            b.background = rounded(Color.rgb(246, 247, 250), 12,
                strokeDp = 1, strokeColor = Color.rgb(219, 224, 234))
            b.setTextColor(C_TEXT)
        } else {
            b.text = getString(R.string.s_061)
            b.background = rounded(C_PRIMARY, 12)
            b.setTextColor(Color.WHITE)
        }
    }

    /** 列出私有目录里已复制的绘图模型（点击选用并加载 · 长按删除）。 */
    private fun refreshDrawModels() {
        refreshDrawToggle()
        val box = drawSavedContainer ?: return
        box.removeAllViews()
        val dpg = drawPage
        val files = dpg?.listModels().orEmpty()
        if (files.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(TextView(this).apply {
            text = getString(R.string.s_090)
            textSize = 12f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())

        val active = dpg?.currentMainName()
        for (f in files) {
            val isMain = active != null && f.name == active
            val isSel = f.absolutePath == drawMainPath
            val chip = TextView(this).apply {
                val role = if (isMain) getString(R.string.s_099) else if (files.size > 1) getString(R.string.s_149) else ""
                val suffix = if (role.isEmpty()) "" else "　[$role]"
                val q = dpg?.quantOf(f)
                val qTag = if (q != null) "　[$q]" else ""
                text = "▶ ${f.name}$suffix$qTag　${fmtSize(f.length())}"
                textSize = 12.5f
                setTextColor(if (isSel || isMain) C_PRIMARY else C_TEXT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(if (isSel || isMain) C_PRIMARY_SOFT else Color.rgb(247, 248, 251), 10,
                    strokeDp = if (isSel || isMain) 1 else 0, strokeColor = C_PRIMARY)
                isClickable = true
            }
            chip.setOnClickListener { selectDrawModel(f) }
            chip.setOnLongClickListener { confirmDeleteDrawModel(f); true }
            box.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
    }

    /** 点击某个绘图模型 → 只标记为选用，不加载（与对话模型一致，需再点「加载绘图模型」）。 */
    private fun selectDrawModel(f: File) {
        drawMainPath = f.absolutePath
        val cur = drawPage?.currentMainName()
        drawModelStatus?.text = if (cur != null && cur != f.name) {
            getString(R.string.v_005, (f.name), (cur))
        } else {
            getString(R.string.v_006, (f.name))
        }
        refreshDrawToggle()
        refreshDrawModels()
    }

    // ================= LoRA =================

    /** 刷新模型页的 LoRA 状态与列表 */
    private fun refreshLoraUi() {
        val dpg = drawPage
        loraStatusTv?.text = dpg?.loraSummary() ?: getString(R.string.s_117)
        val box = loraBox ?: return
        box.removeAllViews()
        val all = dpg?.listLoras().orEmpty()
        if (all.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(listHeader(getString(R.string.s_092)))
        val active = dpg?.currentLoraName()
        for (f in all) {
            box.addView(
                selectableChip(f, f.name == active) { dpg?.selectLora(f); refreshLoraUi() },
                matchWrap().apply { topMargin = dp(4) }
            )
        }
    }

    /** 选下载源：官方 / hf-mirror 镜像 */
    private fun pickLoraSource() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_041))
            .setItems(arrayOf(getString(R.string.s_028), getString(R.string.s_029))) { _, which ->
                startLoraDownload(useMirror = which == 0)
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun startLoraDownload(useMirror: Boolean) {
        val dpg = drawPage ?: return
        val src = if (useMirror) "hf-mirror.com" else "huggingface.co"
        loraStatusTv?.text = getString(R.string.v_007, (src))
        setStatus(getString(R.string.s_018), C_WARN)
        var lastPct = -2
        scope.launch {
            val err = dpg.downloadLora(useMirror) { done, total ->
                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                if (pct != lastPct) {
                    lastPct = pct
                    runOnUiThread {
                        loraStatusTv?.text = if (pct >= 0)
                            getString(R.string.v_008, (src), (pct), (fmtSize(done)), (fmtSize(total)))
                        else
                            getString(R.string.v_009, (src), (fmtSize(done)))
                    }
                }
            }
            runOnUiThread {
                if (err == null) {
                    toast(getString(R.string.s_020))
                    setStatus(getString(R.string.s_023), C_OK)
                } else {
                    toast(err)
                    setStatus(getString(R.string.s_019), C_ERR)
                }
                refreshLoraUi()
            }
        }
    }

    private fun confirmDeleteLora() {
        val dpg = drawPage ?: return
        val all = dpg.listLoras()
        if (all.isEmpty()) {
            toast(getString(R.string.s_135))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_052))
            .setMessage(all.joinToString("、") { it.name } + getString(R.string.s_026))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                var n = 0
                all.forEach { if (dpg.deleteLora(it)) n++ }
                toast(getString(R.string.v_010, (n)))
                refreshLoraUi()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 长按删除一个已复制的绘图模型文件。 */
    private fun confirmDeleteDrawModel(f: File) {
        val dpg = drawPage ?: return
        if (dpg.currentMainName() == f.name && dpg.isReady()) {
            toast(getString(R.string.s_172))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_056))
            .setMessage(getString(R.string.v_011, (f.name), (fmtSize(f.length()))))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val ok = dpg.deleteModel(f)
                if (drawMainPath == f.absolutePath) drawMainPath = null
                toast(if (ok) getString(R.string.v_012, (f.name)) else getString(R.string.s_053))
                drawModelStatus?.text = dpg.modelSummary()
                refreshDrawModels()
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun selectSavedModel(f: File) {
        if (busy) {
            toast(getString(R.string.s_102))
            return
        }
        if (busy) {
            toast(getString(R.string.s_102))
            return
        }
        modelPath = f.absolutePath
        modelInfoText.text = getString(R.string.v_013, (f.name), (fmtSize(f.length())))
        setStatus(getString(R.string.s_096), C_WARN)
        refreshSavedModels()
        // 参数是按模型分别保存的，切换模型时把该模型那套回填到输入框
        loadChatParamsIntoUi()
    }

    private fun confirmDeleteModel(f: File) {
        if (f.absolutePath == modelPath && (engine != null || llamaModel != null)) {
            toast(getString(R.string.s_172))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_055))
            .setMessage(getString(R.string.v_014, (f.name), (fmtSize(f.length()))))
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                f.delete()
                if (modelPath == f.absolutePath) {
                    modelPath = null
                    modelInfoText.text = getString(R.string.s_120)
                }
                refreshSavedModels()
                loadChatParamsIntoUi()
                toast(getString(R.string.s_086))
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    private fun toggleLoad() {
        if (engine != null || llamaModel != null) {
            unloadModel()
            return
        }
        val path = modelPath ?: run { toast(getString(R.string.s_175)); return }
        val isGguf = path.endsWith(".gguf", ignoreCase = true)
        val backendName = backendSpinner.selectedItem.toString()
        val thinking = thinkCheck.isChecked
        setBusy(true)
        setStatus(getString(R.string.s_127), C_WARN)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        scope.launch(Dispatchers.IO) {
            try {
                if (isGguf) {
                    // llama.cpp：单 slot + cache_prompt。会话内 KV 前缀会被复用，长对话只需计算新增 token。
                    val cpus = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
                    val params = ModelParameters()
                        .setModel(path)
                        // 上下文长度与最大输出都从模型页读（默认 4096 / 2048）
                        .setCtxSize(chatCtx())
                        .setPredict(chatMaxOut())
                        .setThreads(cpus)
                        .setThreadsBatch(cpus)
                        .setBatchSize(512)
                        .setParallel(1)
                        .setKeep(64)
                        .setGpuLayers(0)
                    // 多模态 gguf：挂上 mmproj（视觉投影）后模型才有图像输入能力
                    val mp = mmprojPath?.let { File(it) }?.takeIf { it.isFile }
                    if (mp != null) params.setMmproj(mp.absolutePath)
                    val m = LlamaModel(params)
                    val vok = try { m.supportsVision() } catch (_: Throwable) { false }
                    withContext(Dispatchers.Main) {
                        llamaModel = m
                        visionOk = vok
                        loadedModelPath = path
        tagReturnIdx = 0
                        engine = null
                        conversation = null
                        convThinking = null
                        setStatus(getString(R.string.s_078), C_OK)
                        if (backendName == "GPU") toast(getString(R.string.s_016))
                    }
                } else {
                    // 后端：选 GPU 时先试 LiteRT 的 GPU 后端；设备/驱动不支持就自动退回 CPU
                    val gpuWanted = backendName == "GPU"
                    var usedBackendName = backendName
                    // 先读模型自带的 Capabilities：是否为多模态（图文）模型
                    val vok = probeVision(path)
                    // 坑：EngineConfig.visionBackend 默认是 null，运行时就不会创建 vision executor，
                    // 发图时直接报 "Vision executor should not be null, please TryLoadingVisionExecutor() first."；
                    // 所以探测到多模态时必须显式传入 visionBackend（并同时放开 maxNumImages）。
                    val cfg = EngineConfig(
                        modelPath = path,
                        // 上下文长度（token）：模型页可调；调大能装更多历史，但更吃内存
                        maxNumTokens = chatCtx(),
                        backend = if (gpuWanted) Backend.GPU() else Backend.CPU(),
                        visionBackend = if (vok) (if (gpuWanted) Backend.GPU() else Backend.CPU()) else null,
                        maxNumImages = if (vok) MAX_ATTACH else null,
                        cacheDir = cacheDir.absolutePath,
                    )
                    val eng: Engine = try {
                        val g = Engine(cfg)
                        g.initialize()
                        g
                    } catch (e: Throwable) {
                        if (!gpuWanted) throw e
                        // GPU 后端在这台机器上用不了 → 退回 CPU，别让用户用不了模型
                        withContext(Dispatchers.Main) { toast(getString(R.string.s_335)) }
                        usedBackendName = "CPU"
                        val cpu = Engine(cfg.copy(backend = Backend.CPU(), visionBackend = if (vok) Backend.CPU() else null))
                        cpu.initialize()
                        cpu
                    }
                    val conv = eng.createConversation(configFor(current, thinking))
                    withContext(Dispatchers.Main) {
                        engine = eng
                        llamaModel = null
                        loadedModelPath = path
        tagReturnIdx = 0
                        conversation = conv
                        convThinking = thinking
                        visionOk = vok
                        convDrawCapable = canDrawFromChat
                        setStatus(getString(R.string.v_015, (usedBackendName)), C_OK)
                    }
                }
                withContext(Dispatchers.Main) {
                    loadButton.text = getString(R.string.s_062)
                    loadButton.background = rounded(Color.rgb(246, 247, 250), 12,
                        strokeDp = 1, strokeColor = Color.rgb(219, 224, 234))
                    loadButton.setTextColor(C_TEXT)
                    // 加载语言模型 → 对话页回到聊天（drawMode 由「有无语言模型」自动决定）
                    drawPage?.llmLoaded = true
                    updateThinkEnabled()
                    addSystemHint(getString(R.string.s_077))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus(getString(R.string.s_058), C_ERR)
                    toast(getString(R.string.v_016, (e.message)))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                    setBusy(false)
                }
            }
        }
    }

    private fun unloadModel() {
        try { engine?.close() } catch (_: Throwable) {}
        try { llamaModel?.close() } catch (_: Throwable) {}
        engine = null
        llamaModel = null
        loadedModelPath = null
        tagReturnIdx = 0
        conversation = null
        convThinking = null
        visionOk = false
        drawPage?.llmLoaded = false
        loadButton.text = getString(R.string.s_060)
        loadButton.background = rounded(C_PRIMARY, 12)
        loadButton.setTextColor(Color.WHITE)
        setStatus(getString(R.string.s_079), C_IDLE)
        addSystemHint(getString(R.string.s_080))
        // 语言模型卸载后，若绘图模型还在，对话页会自动变回出图模式
        updateThinkEnabled()
    }

    // ================= chat =================

    /** Send button doubles as a stop button: while generating it shows a square ■. */
    private fun onSendOrStop() {
        val job = genJob
        if (job != null && job.isActive) {
            // 关键：先让原生侧真正停下来。只取消协程 Flow 的话，LiteRT / llama.cpp 的
            // native 推理线程还在跑，下一次发送会撞上同一份上下文直接卡死。
            stopRequested = true
            runCatching { conversation?.cancelProcess() }
            runCatching { ggufIterator?.cancel() }
            // 绘图（MNN / sd.cpp）也要打断：否则 native 还在跑，界面已停
            drawPage?.cancel()
            job.cancel()
            // 不能只依赖协程的 finally 来复位：native 推理不响应协程取消时 finally 会迟迟不执行，
            // busy 一直卡在 true，之后每条消息都被「正在生成中」挡回来。这里主动复位。
            genJob = null
            if (busy) setBusy(false)
            setStoppingUi(false)
            // cancelProcess() 之后 LiteRT 的 Conversation 可能已不可用：
            // 标记下一条消息发送前用历史静默重建，否则中断后再也发不出内容。
            if (conversation != null) convNeedsRebuild = true
            toast(getString(R.string.s_083))
        } else {
            doSend()
        }
    }

    private fun setStoppingUi(stop: Boolean) {
        if (stop) {
            sendButton.text = "■"
            sendButton.textSize = 16f
            sendButton.background = rounded(C_ERR, 22)
        } else {
            sendButton.text = "↑"
            sendButton.textSize = 19f
            sendButton.background = rounded(C_PRIMARY, 22)
        }
        sendButton.isEnabled = true
        sendButton.alpha = 1f
    }

    // ---- 多对话管理 ----

    /** 新建一个对话（旧对话保留在列表中）。 */
    private fun newSession() {
        if (busy) { toast(getString(R.string.s_144)); return }
        saveSessions()
        val s = ChatSession(System.currentTimeMillis(), getString(R.string.s_109))
        sessions += s
        current = s
        restoreSession()
        saveSessions()
        toast(getString(R.string.s_095))
    }

    private fun switchTo(s: ChatSession) {
        if (s === current) return
        if (busy) { toast(getString(R.string.s_144)); return }
        saveSessions()
        current = s
        restoreSession()
        saveSessions()
    }

    private fun confirmDeleteSession(s: ChatSession) {
        if (sessions.size <= 1) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.s_139))
                .setMessage(getString(R.string.s_187))
                .setPositiveButton(getString(R.string.s_136)) { _, _ ->
                    s.turns.clear()
                    s.title = getString(R.string.s_109)
                    restoreSession()
                    saveSessions()
                    if (drawerOpen) refreshDrawer()
                    toast(getString(R.string.s_101))
                }
                .setNegativeButton(getString(R.string.s_066), null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_054))
            .setMessage(s.title)
            .setPositiveButton(getString(R.string.s_050)) { _, _ ->
                val wasCurrent = s === current
                sessions.remove(s)
                if (wasCurrent) { current = sessions.last(); restoreSession() }
                saveSessions()
                if (drawerOpen) refreshDrawer()
                toast(getString(R.string.s_086))
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    // ---- 会话切换/重建 ----

    /**
     * GGUF 推理参数：带上对话历史 + cache_prompt(true) + 固定 slot。
     * llama.cpp 会把本轮 prompt 与 slot 里已缓存的 KV 做前缀匹配，只计算新增部分 ——
     * 这是长对话不重复 prefill 的关键。
     */
    private fun buildInferenceParams(
        s: ChatSession,
        extraMsgs: List<kotlin.Pair<String, String>> = emptyList(),
        maxTurns: Int = 24,
    ): InferenceParameters {
        val msgs = mutableListOf<Pair<String, String>>()
        // 两种模型都就绪时，把绘图能力说明并进最后一条 user 消息：
        // llama.cpp 的对话模板只认 user/assistant，新增 system 角色会直接报
        // 「Invalid role: system」。这份 msgs 只用于本次推理，不落历史。
        val turns = s.turns.takeLast(maxTurns)
        for ((ti, t) in turns.withIndex()) {
            var userText = t.user
            if (ti == turns.size - 1) {
                // 本轮发送的文件正文只在这一轮出现（不进历史，免得撑爆 4096 上下文）
                t.fileText?.let { if (it.isNotEmpty()) userText += "\n\n" + it }
                if (canTagTurn(t)) userText += "\n\n" + tagHint(t)
                if (canDrawFromChat) userText += "\n\n" + drawToolPrompt
            }
            msgs += Pair("user", userText)
            // 「工具返回」轮：模型自己那句由 extraMsgs 以原始形态回填，这里别重复
            val isLast = ti == turns.size - 1
            if (t.answer.isNotEmpty() && !(isLast && extraMsgs.isNotEmpty())) msgs += Pair("assistant", t.answer)
        }
        // agent 循环里的中间交换（模型自己的调用意图 / 工具返回）接在最后，只影响本次推理
        extraMsgs.forEach { msgs += Pair(it.first, it.second) }
        if (canDrawFromChat && msgs.isEmpty()) msgs += Pair("user", drawToolPrompt)
        var p = InferenceParameters.empty()
            .withMessages(null, msgs)
            .withCachePrompt(true)
            .withSlotId(0)
            .withNPredict(chatMaxOut())
            .withTemperature(chatTemp())
            .withTopK(chatTopK())
            .withTopP(chatTopP())
            .withRepeatPenalty(chatRep())
            // 种子 -1 = 每次换随机，否则「重新生成」会得到一模一样的回答
            .withSeed(if (chatSeed() < 0) (System.nanoTime() and 0x7FFFFFFF).toInt() else chatSeed())
        // 思考开关：开 = 用模型页的思考预算（0=不限）；关 = 压到 0，并给模板传 enable_thinking=false
        p = if (thinkCheck.isChecked) {
            p.withReasoningBudgetTokens(thinkBudgetOrUnlimited())
        } else {
            p.withReasoningBudgetTokens(0)
                .withChatTemplateKwargs(mapOf("enable_thinking" to "false"))
        }
        return p
    }

    /**
     * gguf 多模态推理参数：最后一条 user 消息带图片（ContentPart.imageBytes）。
     * 只有本回合真的要发图才走这里；纯文字路径保持不变，避免影响 KV 前缀复用。
     */
    private fun buildMultimodalParams(
        s: ChatSession,
        images: List<ByteArray>,
        extraMsgs: List<kotlin.Pair<String, String>> = emptyList(),
        maxTurns: Int = 24,
    ): InferenceParameters {
        val turns = s.turns.takeLast(maxTurns)
        val msgs = mutableListOf<ChatMessage>()
        for ((ti, t) in turns.withIndex()) {
            if (ti == turns.size - 1) {
                val parts = ArrayList<ContentPart>()
                var body = t.user
                t.fileText?.let { if (it.isNotEmpty()) body += "\n\n" + it }
                if (canTagTurn(t)) body += "\n\n" + tagHint(t)
                if (canDrawFromChat) body += "\n\n" + drawToolPrompt
                if (body.isNotEmpty()) parts.add(ContentPart.text(body))
                images.forEach { parts.add(ContentPart.imageBytes(it, "image/jpeg")) }
                msgs += ChatMessage.userMultimodal(*parts.toTypedArray())
            } else {
                msgs += ChatMessage("user", t.user)
            }
            // 「工具返回」轮：模型自己那句由 extraMsgs 以原始形态回填，这里别重复
            val isLast = ti == turns.size - 1
            if (t.answer.isNotEmpty() && !(isLast && extraMsgs.isNotEmpty())) msgs += ChatMessage("assistant", t.answer)
        }
        // agent 循环里的「工具返回」接在最后（模型自己的话已经在本轮回答里）
        extraMsgs.forEach { msgs += ChatMessage(it.first, it.second) }
        var p = InferenceParameters.empty()
            .withMessages(msgs)
            .withCachePrompt(true)
            .withSlotId(0)
            .withNPredict(chatMaxOut())
            .withTemperature(chatTemp())
            .withTopK(chatTopK())
            .withTopP(chatTopP())
            .withRepeatPenalty(chatRep())
            .withSeed(if (chatSeed() < 0) (System.nanoTime() and 0x7FFFFFFF).toInt() else chatSeed())
        p = if (thinkCheck.isChecked) {
            p.withReasoningBudgetTokens(thinkBudgetOrUnlimited())
        } else {
            p.withReasoningBudgetTokens(0)
                .withChatTemplateKwargs(mapOf("enable_thinking" to "false"))
        }
        return p
    }

    /** 用某个对话的文本历史构建 LiteRT 会话配置。 */
    private fun configFor(
        s: ChatSession,
        thinking: Boolean,
        dropLast: Boolean = false,
        reserveTokens: Int = 900,
    ): ConversationConfig {
        val msgs = mutableListOf<Message>()
        // 两种模型都就绪时，告知语言模型它可以用 <draw> 命令调绘图模型
        if (canDrawFromChat) msgs += Message.system(drawToolPrompt)
        // 历史按预算裁剪：LiteRT 上下文只有 4096，全塞进去 native 会直接报输入过长。
        // 从最新一轮往前装，装不下的老对话丢掉（界面上还在，只是不再喂给模型）。
        var budget = inputTokenBudget() -
            (if (canDrawFromChat) estTokens(drawToolPrompt).toInt() else 0) - reserveTokens
        val upto = if (dropLast) s.turns.size - 1 else s.turns.size
        val keep = ArrayList<QaTurn>()
        for (i in upto - 1 downTo 0) {
            val t = s.turns[i]
            val cost = estTokens(t.user).toInt() + estTokens(t.answer).toInt() + 12
            // 至少保留最新一轮（哪怕它自己就超预算），否则模型会完全不看上下文
            if (cost > budget && keep.isNotEmpty()) break
            budget -= cost
            keep.add(0, t)
        }
        for (t in keep) {
            msgs += Message.user(t.user)
            if (t.answer.isNotEmpty()) msgs += Message.model(t.answer)
        }
        return ConversationConfig(
            initialMessages = msgs,
            // 每次重建会话都换随机种子，否则「重新生成」会得到一模一样的回答
            // 采样参数来自模型页（改参数后 doSend 会重建会话来生效）
            samplerConfig = SamplerConfig(
                // 种子 -1 = 每次换随机，否则「重新生成」会得到一模一样的回答
                seed = if (chatSeed() < 0) (System.nanoTime() and 0x7FFFFFFF).toInt() else chatSeed(),
                topP = chatTopP().toDouble(),
                temperature = chatTemp().toDouble(),
                topK = chatTopK(),
            ),
            thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = thinkBudgetOrUnlimited()),
        )
    }

    /**
     * 思考开关变化后重建会话。
     * LiteRT-LM 的思考标记是在会话创建时写入提示模板的，
     * 会话跑起来后仅改 per-call 的 ThinkingConfig 不会生效（尤其是 关→开），
     * 所以必须按新模式重建会话；历史通过 initialMessages 保留。
     */
    private fun rebuildConversation(silent: Boolean = false, dropLast: Boolean = false) {
        val eng = engine ?: return
        val thinking = thinkCheck.isChecked
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = try {
            eng.createConversation(configFor(current, thinking, dropLast = dropLast))
        } catch (e: Throwable) {
            toast(getString(R.string.v_017, (e.message)))
            eng.createConversation(
                ConversationConfig(
                    thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = thinkBudgetOrUnlimited())
                )
            )
        }
        convThinking = thinking
        convDrawCapable = canDrawFromChat
        if (!silent) {
            addSystemHint(
                if (thinking) getString(R.string.s_204)
                else getString(R.string.s_085)
            )
        }
    }

    /** 重建 LiteRT 会话（若模型已加载）并把聊天区重绘为当前对话的内容。 */
    private fun restoreSession(showHint: Boolean = true) {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        convThinking = null
        val eng = engine
        if (eng != null && llamaModel == null) {
            val thinking = thinkCheck.isChecked
            conversation = eng.createConversation(configFor(current, thinking))
            convThinking = thinking
            convDrawCapable = canDrawFromChat
        }
        chatContainer.removeAllViews()
        autoFollow = true
        jumpButton.visibility = View.GONE
        if (current.turns.isEmpty()) {
            if (showHint) {
                addSystemHint(getString(R.string.s_032) +
                    getString(R.string.s_033))
            }
        } else {
            for (t in current.turns) renderTurn(t)
        }
        scrollToBottom(force = true)
    }

    /**
     * 重新生成某一轮的回答。
     * 该轮之后的轮次依赖这轮的上下文，必须一并丢弃；然后把它重新送进去生成。
     */
    private fun regenerate(turn: QaTurn) {
        if (busy) {
            toast(getString(R.string.s_144))
            return
        }
        val idx = current.turns.indexOf(turn)
        if (idx < 0) return
        val text = turn.user
        // 忠实重发：这一轮带的图片与文件要一起还原，否则「重新生成」会退化成纯文字提问
        val imgNames = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val imgPairs = imgNames.mapNotNull { n ->
            readChatImage(n)?.let { bmp -> bmp to bitmapToJpeg(bmp) }
        }
        val fNames = turn.userFile?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val dropped = current.turns.size - idx - 1
        while (current.turns.size > idx) current.turns.removeAt(current.turns.size - 1)
        restoreSession(showHint = false)
        if (dropped > 0) toast(getString(R.string.v_018, (dropped)))
        clearPendingImages()
        clearPendingFiles()
        for (p in imgPairs) addPendingImage(p.first, p.second)
        // 文件：把上一轮的原始内容还原到附件条（走和正常发送完全一样的路径）
        val restored = restoreFiles(turn.fileStore)
        if (restored > 0) toast(getString(R.string.s_320, (restored)))
        else if (fNames.isNotEmpty()) toast(getString(R.string.s_321))
        inputEdit.setText(text)
        doSend()
    }

    /** 把一轮已有问答重绘到聊天区（历史回填）。 */
    /** 绘图模式下思考开关无意义（不经过对话模型），置灰禁用 */
    private fun updateThinkEnabled() {
        // 可能由 IO 线程上的回调触发；setEnabled 会驱动 Ripple 动画，必须回主线程
        runOnUiThread {
            val enabled = !drawMode
            thinkCheck.isEnabled = enabled
            thinkCheck.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun renderTurn(t: QaTurn) {
        val names = t.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        val files = t.userFile?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        if (names.isEmpty()) {
            addUserBubble(t.user, emptyList(), files)
        } else {
            addUserBubble(t.user, names.mapNotNull { readChatImage(it) }, files)
        }
        val ai = addAiArea { regenerate(t) }
        val imgName = t.image
        if (imgName != null) {
            val bmp = readChatImage(imgName)
            if (bmp != null) attachImageBubble(ai, bmp, imgName) else ai.answer.text = getString(R.string.s_003)
        }
        if (t.thought.isNotEmpty()) {
            ai.thoughtBox.visibility = View.VISIBLE
            ai.thoughtBody.text = t.thought
            ai.thoughtHeader.text = getString(R.string.s_205)
        }
        if (t.answer.isNotEmpty()) {
            markwonFull.setMarkdown(ai.answer, t.answer)
        } else {
            ai.answer.text = getString(R.string.s_005)
            ai.answer.setTextColor(C_SUBTEXT)
        }
    }

    // ---- 持久化 ----

    private val sessionsFile: File get() = File(filesDir, "sessions.json")

    private fun saveSessions() {
        try {
            val root = JSONObject()
            root.put("current", sessions.indexOf(current))
            val arr = JSONArray()
            for (s in sessions) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("title", s.title)
                val ts = JSONArray()
                for (t in s.turns) {
                    ts.put(
                        JSONObject().put("u", t.user).put("a", t.answer).put("th", t.thought)
                            .put("img", t.image ?: "").put("uimg", t.userImage ?: "")
                            .put("ufile", t.userFile ?: "").put("utext", t.fileText ?: "")
                            .put("fstore", t.fileStore ?: "")
                    )
                }
                o.put("turns", ts)
                arr.put(o)
            }
            root.put("sessions", arr)
            sessionsFile.writeText(root.toString())
        } catch (_: Throwable) {}
    }

    private fun loadSessions() {
        sessions.clear()
        var idx = 0
        try {
            if (sessionsFile.exists()) {
                val root = JSONObject(sessionsFile.readText())
                idx = root.optInt("current", 0)
                val arr = root.optJSONArray("sessions")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val s = ChatSession(
                            o.optLong("id", System.currentTimeMillis() + i),
                            o.optString("title", getString(R.string.s_109)),
                        )
                        val ts = o.optJSONArray("turns")
                        if (ts != null) {
                            for (j in 0 until ts.length()) {
                                val t = ts.getJSONObject(j)
                                val turn = QaTurn(
                                    t.optString("u"), t.optString("a"), t.optString("th"),
                                    t.optString("img").ifEmpty { null },
                                    t.optString("uimg").ifEmpty { null },
                                    t.optString("ufile").ifEmpty { null }
                                )
                                // 文件正文也读回来，这样重启后「重新生成」也能带上文件
                                turn.fileText = t.optString("utext").ifEmpty { null }
                                turn.fileStore = t.optString("fstore").ifEmpty { null }
                                s.turns += turn
                            }
                        }
                        sessions += s
                    }
                }
            }
        } catch (_: Throwable) {}
        if (sessions.isEmpty()) sessions += ChatSession(System.currentTimeMillis(), getString(R.string.s_109))
        current = sessions.getOrElse(idx) { sessions.first() }
    }

    // ================= 绘图模式（对话页直接出图） =================

    private fun doDrawFromChat(prompt: String, dpg: DrawPage) {
        if (busy) { toast(getString(R.string.s_133)); return }
        val turn = QaTurn(prompt)
        current.turns += turn
        if (current.title == getString(R.string.s_109)) current.title = prompt.take(18)

        inputEdit.setText("")
        addUserBubble(prompt)
        jumpToBottom()
        setBusy(true)
        setStoppingUi(true)
        setStatus(getString(R.string.s_166), C_WARN)

        val ai = addAiArea()
        ai.regenButton.visibility = View.GONE
        markwonStream.setMarkdown(ai.answer, getString(R.string.s_202))

        genJob = scope.launch {
            // 气泡里每秒刷新一次进度（sd.cpp 每步回调 + 本地计时），
            // 否则整段生成期间只能看到静止的「🎨 正在生成图片…」
            var curStep = 0
            var totalStep = 0
            val drawStartedAt = System.currentTimeMillis()
            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val sec = (System.currentTimeMillis() - drawStartedAt) / 1000
                    val stepInfo = if (totalStep > 0) getString(R.string.v_019, (curStep), (totalStep)) else ""
                    markwonStream.setMarkdown(ai.answer, getString(R.string.v_020, (stepInfo), (sec)))
                }
            }
            try {
                val img = dpg.generateImage(prompt) { cur, total ->
                    curStep = cur
                    totalStep = total
                    runOnUiThread {
                        if (cur == total || cur % 2 == 0) setStatus(getString(R.string.v_021, (cur), (total)), C_WARN)
                    }
                }
                ticker.cancel()
                val bmp = dpg.toBitmap(img)
                val name = "ponko_${System.currentTimeMillis()}.png"
                val saved = withContext(Dispatchers.IO) { writeChatImage(bmp, name) }
                turn.image = if (saved) name else null
                turn.answer = "🎨 seed=${img.seed}"
                markwonStream.setMarkdown(ai.answer, "")
                attachImageBubble(ai, bmp, name)
                setStatus(getString(R.string.s_153), C_OK)
            } catch (e: com.litertchat.app.draw.GenerationCancelledException) {
                ticker.cancel()
                turn.answer = getString(R.string.s_004)
                markwonStream.setMarkdown(ai.answer, getString(R.string.s_004))
                setStatus(getString(R.string.s_082), C_IDLE)
            } catch (e: CancellationException) {
                ticker.cancel()
                turn.answer = getString(R.string.s_004)
                markwonStream.setMarkdown(ai.answer, getString(R.string.s_004))
                setStatus(getString(R.string.s_082), C_IDLE)
                throw e
            } catch (e: Throwable) {
                ticker.cancel()
                turn.answer = getString(R.string.v_022, (e.message))
                markwonStream.setMarkdown(ai.answer, getString(R.string.v_023, (e.message)))
                setStatus(getString(R.string.s_152), C_ERR)
            } finally {
                ticker.cancel()
                ai.regenButton.visibility = View.VISIBLE
                saveSessions()
                setBusy(false)
                setStoppingUi(false)
                scrollToBottom()
            }
        }
    }

    /** 把图片写进应用私有目录，返回是否成功 */
    private fun writeChatImage(bmp: Bitmap, name: String): Boolean = try {
        val dir = File(filesDir, "chatimg").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Throwable) {
        false
    }

    private fun readChatImage(name: String): Bitmap? = try {
        BitmapFactory.decodeFile(File(File(filesDir, "chatimg"), name).absolutePath)
    } catch (_: Throwable) {
        null
    }

    /** 在 AI 气泡里插入图片；点图弹确认框再存相册（以前点一下就存，容易误触） */
    /** 全屏看图：双指缩放、拖动、双击放大；未放大时点图关闭，右上角 ✕ 随时关闭。 */
    private fun showImageFullscreen(bmp: Bitmap) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ZoomImageView(this).apply { setImageBitmap(bmp) }
        iv.onTapAtFit = { dlg.dismiss() }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(0x66000000, 20)
            isClickable = true
            setOnClickListener { dlg.dismiss() }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(close, FrameLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(36)
                rightMargin = dp(16)
            })
        }
        dlg.setContentView(root)
        dlg.show()
    }

    /** 图片长按菜单：保存到相册 / 引用 / 发送至图生图 / 发送至 Tagger。 */
    private fun showImageMenu(bmp: Bitmap, name: String) {
        val items = arrayOf(
            getString(R.string.s_045),
            getString(R.string.s_291),
            getString(R.string.s_225),
            getString(R.string.s_238)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_293))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveImageToGallery(bmp, name)
                    1 -> quoteImage(bmp)
                    2 -> { drawPage?.sendImageToI2i(bmp); switchTab(2) }
                    else -> { drawPage?.sendImageToTagger(bmp); switchTab(2) }
                }
            }
            .setNegativeButton(getString(R.string.s_066), null)
            .show()
    }

    /** 引用：把图片直接放进输入栏待发送附件（不落盘，等同于在输入栏选了这张图）。 */
    private fun quoteImage(bmp: Bitmap) {
        if (engine == null && llamaModel == null) { toast(getString(R.string.s_273)); return }
        if (!visionOk) { toast(getString(R.string.s_272)); return }
        if (pendingImages.size >= MAX_ATTACH) { toast(getString(R.string.s_279)); return }
        addPendingImage(bmp, bitmapToJpeg(bmp))
        switchTab(0)
        toast(getString(R.string.s_292))
    }

    /** 全屏看图用的 ImageView：双指缩放 + 拖动 + 双击放大，缩放范围 1x~8x。 */
    private class ZoomImageView(ctx: android.content.Context) : ImageView(ctx) {
        private val mtx = Matrix()
        private var base = 1f
        private var cur = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var down = false
        var onTapAtFit: (() -> Unit)? = null

        private val scaleDet = ScaleGestureDetector(ctx,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(det: ScaleGestureDetector): Boolean {
                    val want = (cur * det.scaleFactor).coerceIn(1f, 8f)
                    val r = want / cur
                    if (r == 1f) return true
                    cur = want
                    mtx.postScale(r, r, det.focusX, det.focusY)
                    clamp()
                    imageMatrix = mtx
                    return true
                }
            })

        private val tapDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (cur <= 1.01f) onTapAtFit?.invoke()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val want = if (cur > 1.05f) 1f else 2.5f
                val r = want / cur
                cur = want
                mtx.postScale(r, r, e.x, e.y)
                clamp()
                imageMatrix = mtx
                return true
            }
        })

        init { setScaleType(ImageView.ScaleType.MATRIX) }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            reset()
        }

        /** 回到「适配并居中」状态 */
        fun reset() {
            val d = drawable ?: return
            val vw = width.toFloat()
            val vh = height.toFloat()
            val dw = d.intrinsicWidth.toFloat()
            val dh = d.intrinsicHeight.toFloat()
            if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
            base = minOf(vw / dw, vh / dh)
            cur = 1f
            mtx.reset()
            mtx.postScale(base, base)
            mtx.postTranslate((vw - dw * base) / 2f, (vh - dh * base) / 2f)
            imageMatrix = mtx
        }

        /** 缩放后把图拉回可视范围，避免拖出去找不回来 */
        private fun clamp() {
            if (cur <= 1.01f) { reset(); return }
            val d = drawable ?: return
            val v = FloatArray(9)
            mtx.getValues(v)
            val sw = d.intrinsicWidth * base * cur
            val sh = d.intrinsicHeight * base * cur
            v[Matrix.MTRANS_X] = if (sw <= width) (width - sw) / 2f else v[Matrix.MTRANS_X].coerceIn(width - sw, 0f)
            v[Matrix.MTRANS_Y] = if (sh <= height) (height - sh) / 2f else v[Matrix.MTRANS_Y].coerceIn(height - sh, 0f)
            mtx.setValues(v)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaleDet.onTouchEvent(ev)
            tapDet.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = ev.x; lastY = ev.y; down = true }
                MotionEvent.ACTION_MOVE -> {
                    if (down && cur > 1.01f && !scaleDet.isInProgress) {
                        mtx.postTranslate(ev.x - lastX, ev.y - lastY)
                        clamp()
                        imageMatrix = mtx
                    }
                    lastX = ev.x
                    lastY = ev.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> down = false
            }
            return true
        }
    }

    private fun attachImageBubble(ai: AiArea, bmp: Bitmap, name: String) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            isClickable = true
            isFocusable = false
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { showImageFullscreen(bmp) }
            setOnLongClickListener { showImageMenu(bmp, name); true }
        }
        ai.root.addView(iv, matchWrap())
        ai.root.addView(TextView(this).apply {
            text = getString(R.string.s_141)
            textSize = 11f
            setTextColor(C_SUBTEXT)
            setPadding(0, dp(4), 0, 0)
        }, matchWrap())
    }

    private fun saveImageToGallery(bmp: Bitmap, name: String) = withLegacyStorage { doSaveImageToGallery(bmp, name) }

    private fun doSaveImageToGallery(bmp: Bitmap, name: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/Ponko"
                            )
                        }
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        true
                    } else false
                } catch (_: Throwable) {
                    false
                }
            }
            toast(if (ok) getString(R.string.s_084) else getString(R.string.s_046))
        }
    }

    private fun doSend() {
        val text = inputEdit.text.toString().trim()
        val attachBmps = pendingImages.map { it.first }
        val attachJpegs = pendingImages.map { it.second }
        // 用户这一轮带的文件：附件条里有就正常发（「重新生成」也会先把上一轮的文件还原回附件条）
        if (text.isEmpty() && attachJpegs.isEmpty() && pendingFiles.isEmpty()) return
        // 文件是「文字」，任何模型都能读，不受多模态限制
        val fileBlock = buildFileBlock(pendingFiles)
        val fileNames = pendingFiles.map { it.name }
        var sendText =
            if (fileBlock.isEmpty()) text
            else if (text.isEmpty()) fileBlock
            else text + "\n\n" + fileBlock
        // 生成过程中不允许发起新的一轮（选图/引用可以在生成中进行，发送不行）
        if (busy) { toast(getString(R.string.s_133)); return }

        // 带图发送：模型要么自己能看图（.litertlm 多模态 / 挂了 mmproj 的 gguf），
        // 要么能借打标模型看图（看不见图时用 <tag> 命令让打标模型识别）
        if (attachJpegs.isNotEmpty()) {
            if (engine == null && llamaModel == null) { toast(getString(R.string.s_273)); return }
            if (!visionOk && !canTagFromChat) { toast(getString(R.string.s_272)); return }
        }

        // 绘图模式：把输入当作正面提示词，按绘图页的参数（除正面提示词外）生成
        val dpg = drawPage
        if (drawMode && dpg != null) {
            doDrawFromChat(text, dpg)
            return
        }

        val isGgufRun = llamaModel != null
        if (conversation == null && !isGgufRun) { toast(getString(R.string.s_174)); return }
        if (busy) {
            toast(getString(R.string.s_133))
            return
        }
        var conv: Conversation? = null
        if (!isGgufRun) {
            val needThinkingRebuild = convThinking != thinkCheck.isChecked
            // 绘图能力开关变了（加载/卸载绘图模型）也要重建：
            // system 里的 <draw> 提示只在建会话时写入，不重建则模型不知道能画图
            val needCapRebuild = convDrawCapable != canDrawFromChat
            if (convNeedsRebuild || needThinkingRebuild || needCapRebuild) {
                // 上一轮被中断：cancelProcess() 之后会话可能已不可用，静默用历史重建
                convNeedsRebuild = false
                // 只有「思考开关变化」需要给用户可见提示，中断恢复/能力变化都静默重建
                rebuildConversation(silent = !needThinkingRebuild)
            }
            conv = conversation ?: run { toast(getString(R.string.s_042)); return }
        }

        val turn = QaTurn(text)
        current.turns += turn
        // 模型看不见图：把可用的打标命令说明附在本轮消息末尾（不进历史）
        // 注意：必须等 turn.userImage 写好后才能判断，否则提示根本不会被加上
        if (fileNames.isNotEmpty()) {
            turn.userFile = fileNames.joinToString("|")
            turn.fileText = fileBlock
            // 把文件原始内容存进这一轮，方便「重新生成」把附件还原回附件条
            turn.fileStore = encodeFiles(pendingFiles)
        }
        if (current.title == getString(R.string.s_109)) {
            val fallback = if (fileNames.isNotEmpty()) fileNames.first() else getString(R.string.s_278)
            current.title = (if (text.isNotEmpty()) text else fallback).take(18)
        }

        if (attachJpegs.isNotEmpty()) {
            val names = ArrayList<String>()
            for ((idx, pair) in pendingImages.withIndex()) {
                val nm = "u_${System.currentTimeMillis()}_$idx.jpg"
                if (writeChatJpeg(pair.first, nm)) names.add(nm)
            }
            if (names.isNotEmpty()) turn.userImage = names.joinToString("|")
            clearPendingImages()
        }
        // 模型看不见图：本轮带图时先告诉它「这条消息带了图片」，再给打标工具说明，
        // 否则它会直接反问「请提供图片」（这段只用于本轮，不进历史）
        if (canTagTurn(turn)) sendText += "\n\n" + tagHint(turn)
        clearPendingFiles()

        inputEdit.setText("")
        addUserBubble(text, attachBmps, fileNames)
        jumpToBottom()
        setBusy(true)
        setStoppingUi(true)
        setStatus(getString(R.string.s_143), C_WARN)

        val ai = addAiArea { regenerate(turn) }
        ai.regenButton.visibility = View.GONE   // 生成结束后再显示，避免与「停止」混淆
        var cancelled = false
        stopRequested = false

        genJob = scope.launch {
            var drawReq: String? = null
            var shown = ""          // 气泡里当前的正文（前几轮 + 本轮输出）
            var thoughtShown = ""   // 已经落在思考区里的内容
            try {
                // ===== agent 循环：生成 → 模型要调工具就执行 → 把结果补回上下文再生成 =====
                var overText = sendText   // 本轮要发的文本（第一轮＝输入＋文件正文＋工具说明）
                // 打标结果按「工具返回」回传（system / tool 角色，不冒充用户消息）
                var toolReturn: ToolReturn? = null
                var round = 0
                var autoTagged = false
                var tagRan = false      // 这一轮真的跑过打标（用于兜底纠偏）
                var nudgeUsed = false
                // 带图、模型又看不见图：第一轮先显示一句占位（这一轮的内容不显示，免得命令命中后整段消失重来）
                if (canTagTurn(turn)) markwonStream.setMarkdown(ai.answer, getString(R.string.s_323))
                while (true) {
                    val out = runRound(
                        turn = turn,
                        ai = ai,
                        gguf = isGgufRun,
                        jpegs = attachJpegs,
                        overText = overText,
                        displayPrefix = shown,
                        thoughtPrefix = thoughtShown,
                        toolReturn = toolReturn,
                        dropLastForBudget = round == 0,
                        // 带图、模型又看不见图时，第一轮先不显示：它可能先啰嗦一段再吐 <tag>，
                        // 直接显示的话命令命中后这段会消失重来
                        hideStream = round == 0 && canTagTurn(turn),
                    )
                    var roundText = out.answer

                    // ① 出图命令优先：交给绘图模型（真正出图放在 finally，必须等 busy 复位）
                    val drawHit = scanDrawCommand(roundText, out.thought)
                    if (drawHit != null) {
                        if (drawHit.inAnswer) {
                            roundText = roundText.replaceRange(
                                drawHit.start, drawHit.end, getString(R.string.s_200, drawHit.prompt)
                            )
                            thoughtShown += out.thought
                        } else {
                            thoughtShown += out.thought.replaceRange(
                                drawHit.start, drawHit.end, getString(R.string.s_199, drawHit.prompt)
                            )
                        }
                        shown += roundText
                        drawReq = drawHit.prompt
                        break
                    }

                    // ② 打标命令：模型自己看不见图时，让它借打标模型看图，拿到标签再回答
                    val tagHit = if (round < MAX_TAG_ROUNDS) scanTagCommand(roundText, out.thought) else null
                    // 兜底：小模型经常无视图片工具、直接反问「请提供图片」。这一轮带图、它又像在推辞时，
                    // App 替它调用一次打标模型（阈值/标签数用默认 0.35 / 40），结果照样按「工具返回」发回去。
                    val autoTagRes: String? = if (tagHit == null && round == 0 && !autoTagged && canTagFromChat &&
                        attachJpegs.isNotEmpty() && looksLikeBlindRefusal(roundText)
                    ) {
                        autoTagged = true
                        val autoNames = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
                        val r = if (autoNames.isEmpty()) "" else {
                            // 打标结果不展示：先把这一轮的推辞内容清掉
                            shown = ""
                            turn.answer = ""
                            markwonFull.setMarkdown(ai.answer, "")
                            withContext(Dispatchers.IO) {
                                runCatching { runTaggerOnImages(autoNames, TagReq(0.35f, 40)) }.getOrDefault("")
                            }
                        }
                        r.ifEmpty { null }
                    } else null
                    if (tagHit == null && autoTagRes == null) {
                        val clean = stripTagCommand(roundText)
                        // 模型把工具结果当成新的输入、反过来问用户「请提出您的问题」：明确提示后重来一轮（只一次）
                        if (tagRan && !nudgeUsed && looksLikeQuestionAsk(clean)) {
                            nudgeUsed = true
                            shown = ""
                            turn.answer = ""
                            markwonFull.setMarkdown(ai.answer, "")
                            thoughtShown += out.thought
                            overText = getString(R.string.s_331, turn.user.take(200))
                            round++
                            continue
                        }
                        // 没有可用的工具调用：把可能残留的 <tag> 命令抹掉（模型有读图能力、打标路径已关闭时）
                        shown += clean
                        thoughtShown += out.thought
                        break
                    }
                    if (tagHit == null) {
                        // 兜底路径：模型没调用工具，但打标结果已经有了，直接当工具返回发回去（不展示标签）
                        thoughtShown += out.thought
                        val autoBlock = getString(R.string.s_328, autoTagRes, turn.user.take(200))
                        overText = autoBlock
                        // gguf：用「模型只输出了 <tag> 命令」的干净形态回填，别把上一轮的推辞带进上下文
                        val autoCmd = "<tag threshold=\"0.35\" topk=\"40\"></tag>"
                        toolReturn = ToolReturn(if (isGgufRun) autoCmd else out.answer, autoBlock)
                        tagRan = true
                        round++
                        scrollToBottom()
                        continue
                    }
                    // 把命令换成一行说明：正文里的换在正文，思考里的换在思考区、正文补一行
                    fun swapLine(replacement: String): String = if (tagHit.inAnswer) {
                        thoughtShown += out.thought
                        roundText.replaceRange(tagHit.start, tagHit.end, replacement)
                    } else {
                        thoughtShown += out.thought.replaceRange(
                            tagHit.start, tagHit.end, getString(R.string.s_323)
                        )
                        (roundText + "\n\n" + replacement).trim()
                    }

                    val names = turn.userImage?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
                    // 打标结果不展示：先把气泡里这一轮的调用过程清掉，识别完由模型直接给最终回答
                    shown = ""
                    turn.answer = ""
                    markwonFull.setMarkdown(ai.answer, "")
                    val res = if (names.isEmpty()) {
                        "\u0000" + getString(R.string.s_326)
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching { runTaggerOnImages(names, tagHit.req) }.getOrElse { e ->
                                "\u0000" + (e.message ?: "")
                            }
                        }
                    }
                    if (res.startsWith("\u0000")) {
                        // 这轮没有图片、或打标失败：把命令换成原因，本轮就此结束
                        shown += swapLine(getString(R.string.s_325, res.removePrefix("\u0000")))
                        break
                    }
                    if (res.isEmpty()) {
                        shown += swapLine(getString(R.string.s_327))
                        break
                    }
                    // 成功：不展示标签，气泡留空，等模型基于标签给出最终回答
                    thoughtShown += if (tagHit.inAnswer) out.thought else {
                        out.thought.replaceRange(tagHit.start, tagHit.end, getString(R.string.s_323))
                    }
                    val block = getString(R.string.s_328, res, turn.user.take(200))
                    overText = block
                    // gguf：用「模型只输出了 <tag> 命令」的干净形态回填，别把它上一轮的碎碎念带进上下文
                    val cmdEcho = "<tag threshold=\"${tagHit.req.threshold}\" topk=\"${tagHit.req.topK}\"></tag>"
                    toolReturn = ToolReturn(if (isGgufRun) cmdEcho else out.answer, block)
                    tagRan = true
                    round++
                    scrollToBottom()
                }
                turn.answer = shown.trimEnd()
                if (turn.answer.isNotEmpty()) {
                    markwonFull.setMarkdown(ai.answer, turn.answer)
                } else if (turn.thought.isEmpty()) {
                    // 第一轮被隐藏、模型又什么都没说：给个提示，别留一个空气泡
                    ai.answer.text = getString(R.string.s_007)
                    ai.answer.setTextColor(C_SUBTEXT)
                }
                saveSessions()
                setStatus(getString(R.string.s_081), C_OK)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                if (stopRequested) {
                    // 主动中断：native 被打断后常抛异常（task not found 等），
                    // 按「已中断」处理，保留已经输出到界面上的内容
                    cancelled = true
                } else if (isContextOverflow(e)) {
                    // 上下文塞不下：给能操作的提示，别甩一串 Status Code 给用户
                    ai.answer.text = getString(R.string.s_302)
                    ai.answer.setTextColor(C_ERR)
                    setStatus(getString(R.string.s_145), C_ERR)
                    toast(getString(R.string.s_302))
                } else {
                    ai.answer.text = getString(R.string.v_024, (e.message))
                    ai.answer.setTextColor(C_ERR)
                    setStatus(getString(R.string.s_145), C_ERR)
                    toast(getString(R.string.v_025, (e.message)))
                }
            } finally {
                ai.regenButton.visibility = View.VISIBLE
                saveSessions()
                setBusy(false)
                setStoppingUi(false)
                if (cancelled) {
                    if (turn.answer.isNotEmpty()) {
                        markwonFull.setMarkdown(ai.answer, turn.answer)
                    } else if (turn.thought.isEmpty()) {
                        ai.answer.text = getString(R.string.s_004)
                        ai.answer.setTextColor(C_SUBTEXT)
                    }
                    setStatus(getString(R.string.s_082), C_IDLE)
                } else if (drawReq != null) {
                    // 交给绘图模型出图（必须等 busy 复位后再启动，否则会被当成「正在生成中」拦住）
                    val dpg = drawPage
                    if (dpg != null) doDrawFromChat(drawReq, dpg)
                }
                scrollToBottom()
            }
        }
    }

    private fun extractText(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /**
     * LiteRT 的流式片段语义在版本间不完全一致（可能是增量片段，也可能带累积内容）。
     * 统一兼容，避免出现「您好，有什么可以帮你？」被反复拼接的情况：
     *   - 与已累积内容完全相同     → 重复快照，丢弃
     *   - 新片段以已累积内容开头   → 全量快照，用新片段替换
     *   - 已累积内容以新片段结尾   → 尾部重复片段，丢弃（忽略首尾空白）
     *   - 其余                     → 按增量追加
     */
    private fun mergeStreamDelta(cur: String, delta: String): String {
        if (cur.isEmpty() || delta.isEmpty()) return cur + delta
        if (delta == cur) return cur
        if (delta.startsWith(cur)) return delta
        if (cur.endsWith(delta)) return cur
        val t = delta.trim()
        if (t.isNotEmpty() && cur.endsWith(t)) return cur
        return cur + delta
    }

    private class AiArea(
        val root: LinearLayout,
        val thoughtBox: LinearLayout,
        val thoughtHeader: TextView,
        val thoughtBody: TextView,
        val answer: TextView,
        val regenButton: TextView,
    )

    /** AI reply card: collapsible thinking section on top, Markdown answer below. */
    private fun addAiArea(onRegen: (() -> Unit)? = null): AiArea {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(C_CARD, 16)
            elevation = dp(1).toFloat()
        }

        val thoughtBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(C_THOUGHT_BG, 10)
            visibility = View.GONE
        }
        val thoughtHeader = TextView(this).apply {
            text = getString(R.string.s_205)
            textSize = 12.5f
            setTextColor(C_THOUGHT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
        }
        val thoughtBody = TextView(this).apply {
            textSize = 12.5f
            setTextColor(C_THOUGHT_TEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
        }
        thoughtHeader.setOnClickListener {
            val show = thoughtBody.visibility != View.VISIBLE
            thoughtBody.visibility = if (show) View.VISIBLE else View.GONE
            thoughtHeader.text = if (show) getString(R.string.s_206) else getString(R.string.s_205)
            if (show) scrollToBottom()
        }
        thoughtBox.addView(thoughtHeader, matchWrap())
        thoughtBox.addView(thoughtBody, matchWrap().apply { topMargin = dp(6) })
        wrap.addView(thoughtBox, matchWrap())

        val answer = TextView(this).apply {
            textSize = 16f
            setTextColor(C_TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            // 不给 isFocusable = false：那会把 setTextIsSelectable 设的焦点属性覆盖掉，
            // 导致长按无法选中复制。防键盘焦点抢夺由 renderAnswer/renderThought 里的焦点守卫负责。
        }
        wrap.addView(answer, matchWrap().apply { topMargin = dp(4) })

        // 「重新生成」：对回答不满意时，丢掉这一轮（及其后）的回答重问一次
        val regenButton = TextView(this).apply {
            text = getString(R.string.s_031)
            textSize = 12.5f
            setTextColor(C_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
            isClickable = true
            isFocusable = false
            visibility = if (onRegen != null) View.VISIBLE else View.GONE
            setOnClickListener { onRegen?.invoke() }
        }
        wrap.addView(regenButton, matchWrap())

        chatContainer.addView(wrap, matchWrap().apply {
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        scrollToBottom()
        return AiArea(wrap, thoughtBox, thoughtHeader, thoughtBody, answer, regenButton)
    }

    private fun addUserBubble(text: String, images: List<Bitmap> = emptyList(), files: List<String> = emptyList()) {
        if (files.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            for (nm in files) {
                strip.addView(TextView(this).apply {
                    // 注意要写 this.text：本函数的 text 参数会把 TextView.text 遮住
                    this.text = "📄 $nm"
                    textSize = 12f
                    setTextColor(C_TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    background = rounded(C_PRIMARY_SOFT, 12)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(6) })
            }
            chatContainer.addView(strip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        if (images.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val w = if (images.size == 1) dp(160) else dp(112)
            for (b in images) {
                strip.addView(ImageView(this).apply {
                    setImageBitmap(b)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    isClickable = true
                    setOnClickListener { showImageFullscreen(b) }
                    setOnLongClickListener {
                        showImageMenu(b, "ponko_${System.currentTimeMillis()}.png"); true
                    }
                }, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(6)
                })
            }
            chatContainer.addView(strip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        if (text.isEmpty()) {
            scrollToBottom()
            return
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(C_PRIMARY, 16)
            elevation = dp(1).toFloat()
        }
        chatContainer.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            leftMargin = dp(56)
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        scrollToBottom()
    }

    private fun addSystemHint(text: String) {
        chatContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(C_SUBTEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, matchWrap())
        scrollToBottom()
    }

    private fun scrollToBottom(force: Boolean = false) {
        if (!force && !autoFollow) {
            refreshFollowState()
            return
        }
        if (scrollPending) return
        scrollPending = true
        scrollView.post {
            scrollPending = false
            // 排队期间用户可能已经往上划了：再确认一次，别把他拽回去
            if (!force && !autoFollow) {
                refreshFollowState()
                return@post
            }
            // 滚动到底不应影响输入框焦点（用户可能正在打字）
            val hadFocus = inputEdit.hasFocus()
            programmaticScroll = true
            scrollView.getChildAt(0)?.let { scrollView.scrollTo(0, it.height) }
            programmaticScroll = false
            autoFollow = true
            refreshJumpButton()
            if (hadFocus && !inputEdit.hasFocus()) inputEdit.requestFocus()
        }
    }

    /**
     * 自动跟随阈值：只有基本贴底（十几像素内）才继续自动跟随。
     * 不能像以前那样用 15 行——用户往上划一点就被拽回底部，等于根本没法滚动。
     */
    private val followThresholdPx: Int by lazy { dp(12) }

    /** 「回到底部」按钮的显示阈值：离底部超过 15 行左右才出现。 */
    private val jumpThresholdPx: Int by lazy {
        val line = (16f * resources.displayMetrics.scaledDensity * 1.4f).toInt() + dp(3)
        line * 15
    }

    /** 距内容底部的像素距离（负数按 0 处理）。 */
    private fun distanceToBottom(): Int {
        val child = scrollView.getChildAt(0) ?: return 0
        return (child.height - (scrollView.scrollY + scrollView.height)).coerceAtLeast(0)
    }

    /** 按「距底部距离」更新自动跟随状态与「回到底部」按钮。 */
    private fun refreshFollowState() {
        // 用户往上划走后就停止自动跟随（读者优先），不再把他拽回底部；
        // 只有他自己划回到贴底附近，才恢复跟随。
        autoFollow = distanceToBottom() <= followThresholdPx
        refreshJumpButton()
    }

    private fun refreshJumpButton() {
        val child = scrollView.getChildAt(0) ?: return
        val scrollable = child.height > scrollView.height + dp(16)
        jumpButton.visibility =
            if (scrollable && distanceToBottom() > jumpThresholdPx) View.VISIBLE else View.GONE
    }

    /** 用户主动行为（发送消息等）：直接恢复跟随并滚到底。 */
    private fun jumpToBottom() {
        autoFollow = true
        jumpButton.visibility = View.GONE
        scrollToBottom(force = true)
    }

    // ================= helpers =================

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) { null }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    private fun setBusy(b: Boolean) {
        busy = b
        loadButton.isEnabled = !b
        loadButton.alpha = if (b) 0.5f else 1f
        // 生成过程中不禁用输入框：可以照常打字（此时发送键是「停止」，只是不发送）
    }

    /** 导入/复制期间禁用「绘图模型」区的按钮，避免重复触发。 */
    private fun setDrawBusy(b: Boolean) {
        drawToggleBtn?.isEnabled = !b
        drawToggleBtn?.alpha = if (b) 0.5f else 1f
        drawImportBtn?.isEnabled = !b
        drawImportBtn?.alpha = if (b) 0.5f else 1f
        taggerOnnxBtn?.isEnabled = !b
        taggerOnnxBtn?.alpha = if (b) 0.5f else 1f
        taggerCsvBtn?.isEnabled = !b
        taggerCsvBtn?.alpha = if (b) 0.5f else 1f
        taggerLoadBtn?.isEnabled = !b
        taggerLoadBtn?.alpha = if (b) 0.5f else 1f
    }

    /** 刷新模型页的打标（Tagger）状态：状态行 + 可选列表（模型 / 标签表） */
    private fun refreshTaggerUi() {
        val dpg = drawPage
        taggerStatusTv?.text = dpg?.taggerSummary() ?: getString(R.string.s_245)
        dpg?.refreshStatus()
        refreshTaggerToggle()
        val box = taggerBox ?: return
        box.removeAllViews()
        val models = dpg?.listTaggerModels().orEmpty()
        val csvs = dpg?.listTaggerCsvs().orEmpty()
        if (models.isEmpty() && csvs.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val curM = dpg?.currentTaggerModelName()
        val curC = dpg?.currentTaggerCsvName()
        if (models.isNotEmpty()) {
            box.addView(listHeader(getString(R.string.s_252)))
            for (f in models) {
                box.addView(
                    selectableChip(f, f.name == curM) { dpg?.selectTaggerModel(f); refreshTaggerUi() },
                    matchWrap().apply { topMargin = dp(4) }
                )
            }
        }
        if (csvs.isNotEmpty()) {
            box.addView(listHeader(getString(R.string.s_253)))
            for (f in csvs) {
                box.addView(
                    selectableChip(f, f.name == curC) { dpg?.selectTaggerCsv(f); refreshTaggerUi() },
                    matchWrap().apply { topMargin = dp(4) }
                )
            }
        }
    }

    /** 按当前是否已加载切换按钮文案与配色（与「对话/绘图」一致：未加载=蓝底，已加载=浅底） */
    private fun refreshTaggerToggle() {
        val b = taggerLoadBtn ?: return
        if (drawPage?.taggerLoaded() == true) {
            b.text = getString(R.string.s_255)
            b.background = rounded(Color.rgb(246, 247, 250), 12,
                strokeDp = 1, strokeColor = Color.rgb(219, 224, 234))
            b.setTextColor(C_TEXT)
        } else {
            b.text = getString(R.string.s_254)
            b.background = rounded(C_PRIMARY, 12)
            b.setTextColor(Color.WHITE)
        }
    }

    /** 加载 / 卸载打标模型（多个打标模型之间切换用） */
    private fun onTaggerToggleClick() {
        val dpg = drawPage ?: return
        if (dpg.taggerLoaded()) {
            dpg.unloadTagger()
            refreshTaggerUi()
            return
        }
        setBusy(true)
        setDrawBusy(true)
        showDrawProgress(true)
        setStatus(getString(R.string.s_131), C_WARN)
        scope.launch {
            val err = dpg.loadTaggerFromUi { stage -> taggerStatusTv?.text = stage }
            setBusy(false)
            setDrawBusy(false)
            showDrawProgress(false)
            if (err == null) {
                setStatus(getString(R.string.s_265), C_OK)
                toast(getString(R.string.s_266))
            } else {
                setStatus(getString(R.string.s_158), C_ERR)
                toast(err)
            }
            refreshTaggerUi()
        }
    }

    /** 列表小标题 */
    private fun listHeader(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(C_SUBTEXT)
        setPadding(0, dp(6), 0, 0)
    }

    /** 可点选的条目（高亮当前选中） */
    private fun selectableChip(f: File, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = "▶ ${f.name}　${fmtSize(f.length())}"
        textSize = 12.5f
        setTextColor(if (selected) C_PRIMARY else C_TEXT)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(if (selected) C_PRIMARY_SOFT else Color.rgb(247, 248, 251), 10,
            strokeDp = if (selected) 1 else 0, strokeColor = C_PRIMARY)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun showDrawProgress(show: Boolean) {
        val bar = drawProgress ?: return
        if (show) {
            bar.visibility = View.VISIBLE
            bar.progress = 0
            bar.isIndeterminate = false
        } else {
            bar.visibility = View.GONE
        }
    }

    private fun updateDrawProgress(done: Long, total: Long) {
        val bar = drawProgress ?: return
        if (total > 0) {
            bar.isIndeterminate = false
            bar.progress = ((done * 100 / total).toInt()).coerceIn(0, 100)
        } else {
            bar.isIndeterminate = true
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun wrapWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
