package com.litertchat.app

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 极简 HTTP/1.1 服务器：把手机上的本地模型开放成 **OpenAI 兼容**接口。
 *
 * 只用 JDK 自带能力（ServerSocket + 线程池），不引第三方 HTTP 库：
 * 需要的就几条路由 + SSE，自己写反而更可控（尤其是流式与客户端断连的处理）。
 *
 * **一个实例只服务一类接口**（[Role]）：
 * - [Role.CHAT]：`/v1/chat/completions` + `/v1/models`（对话模型）
 * - [Role.DRAW]：`/v1/images/generations` + `/v1/models`（绘图模型）
 *
 * 两类接口是**互相独立**的：各有各的端口、key、开关与统计数据（见 [ApiService]）。
 * 之所以不像最初那样挤在一个端口上：它们的服务对象、资源占用、安全暴露面都不一样——
 * 出图一张要几十秒且吃满 CPU、只该在可信网络里开；而对话接口是给人天天连的。
 * 想让两者分开配端口 / key 是很自然的需求，硬绑在一起等于剥夺这个选择。
 *
 * `/health` 与 `/v1/models` 两个实例都有，各自报告**自己那类**模型的状态。
 *
 * 生成全部交给 [ChatCore] / [DrawCore]，这里只做协议转换。一次只允许一个请求
 * （本地模型跑不了并发），忙时返回 429 —— 客户端看到的是标准的 rate-limit 语义。
 */
class ApiServer(
    /** 监听端口（[ApiService] 用它判断配置有没有变、要不要重启） */
    val port: Int,
    /** 为空 = 不校验（用户明确要求「可以有 key 也可以没有」） */
    val apiKey: String?,
    /** 这个实例服务哪一类接口 */
    private val role: Role,
    private val log: (String) -> Unit,
) {

    enum class Role { CHAT, DRAW }

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile var running = false
        private set

    /** 处理过的请求数（界面显示用） */
    @Volatile var served = 0
        private set

    /** 最近一次请求的摘要（界面显示用） */
    @Volatile var lastRequest: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            serverSocket = ss
            running = true
            acceptThread = Thread({
                while (running) {
                    val s = try { ss.accept() } catch (_: Throwable) { break }
                    pool.execute { runCatching { handle(s) } }
                }
            }, "ponko-api-accept").apply { isDaemon = true; start() }
            log("已启动，监听 0.0.0.0:$port")
            true
        } catch (e: Throwable) {
            log("启动失败：${e.message ?: e.javaClass.simpleName}（端口被占用？）")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        log("已停止")
    }

    // ==================== 连接处理 ====================

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 20000
            val ins = BufferedInputStream(s.getInputStream())
            val head = readLine(ins) ?: return
            val parts = head.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (len in 1..(32 shl 20)) readBytes(ins, len) else ByteArray(0)
            route(s, method, path, headers, body)
        }
    }

    private fun route(s: Socket, method: String, path: String, headers: Map<String, String>, body: ByteArray) {
        if (method == "OPTIONS") { respond(s, 204, null, ByteArray(0)); return }
        // 校验：配了 key 才校验（静态页 /health 也放行，方便用户先确认服务活着）
        if (!apiKey.isNullOrEmpty() && path != "/health") {
            val auth = headers["authorization"] ?: ""
            val given = auth.removePrefix("Bearer ").trim()
            if (given != apiKey) {
                respondJson(s, 401, JSONObject().put("error", JSONObject()
                    .put("message", "Invalid API key").put("type", "invalid_request_error")))
                return
            }
        }
        when {
            method == "GET" && (path == "/health" || path == "/") -> respondJson(s, 200, healthJson())

            method == "GET" && path == "/v1/models" -> respondJson(s, 200, modelsJson())

            method == "POST" && path == "/v1/chat/completions" ->
                if (role == Role.CHAT) chatCompletions(s, body) else wrongRole(s, "/v1/chat/completions", "chat")

            method == "POST" && path == "/v1/images/generations" ->
                if (role == Role.DRAW) imagesGenerations(s, body) else wrongRole(s, "/v1/images/generations", "draw")

            else -> respondJson(s, 404, JSONObject().put("error", JSONObject()
                .put("message", "Not found: $method $path").put("type", "invalid_request_error")))
        }
    }

    /** 请求打到了「另一类接口」的端口上——明确告诉客户端该去哪，别让它自己猜 */
    private fun wrongRole(s: Socket, path: String, wanted: String) {
        val where = if (wanted == "chat") "Chat model" else "Draw model"
        respondJson(s, 404, errJson(
            "$path is not served on this port. The $wanted API runs on its own port - " +
                "open Ponko, go to Models/API -> $where -> API tab to see its address.",
            "invalid_request_error"))
    }

    /** 本实例的状态。两个 role 各自报告自己那类模型的就绪情况。 */
    private fun healthJson(): JSONObject {
        val o = JSONObject()
            .put("status", "ok")
            .put("role", if (role == Role.CHAT) "chat" else "draw")
        if (role == Role.CHAT) {
            o.put("model", ChatCore.modelName ?: JSONObject.NULL)
                .put("ready", ChatCore.isReady())
                .put("busy", ChatCore.busy)
        } else {
            o.put("model", DrawCore.modelName ?: JSONObject.NULL)
                .put("ready", DrawCore.isReady())
                .put("busy", DrawCore.busy || DrawCore.uiBusy)
        }
        return o
    }

    private fun modelsJson(): JSONObject {
        val arr = JSONArray()
        val name = if (role == Role.CHAT) ChatCore.modelName else DrawCore.modelName
        name?.let {
            arr.put(JSONObject()
                .put("id", it)
                .put("object", "model")
                .put("created", System.currentTimeMillis() / 1000)
                .put("owned_by", "ponko"))
        }
        return JSONObject().put("object", "list").put("data", arr)
    }

    private fun errJson(message: String, type: String): JSONObject =
        JSONObject().put("error", JSONObject().put("message", message).put("type", type))

    // ==================== /v1/images/generations ====================

    /**
     * OpenAI 兼容的文生图。参数没给的沿用 App 里绘图页的当前设置
     * （步数 / CFG / 尺寸 / 负向提示词 / 采样器 / 调度器 / LoRA）。
     *
     * 只支持 `response_format=b64_json`：本机没有公网可访问的 URL 可以当图床，
     * 所以 `url` 这种返回形式做不到（给了会明确报错，而不是悄悄返回错格式）。
     */
    private fun imagesGenerations(s: Socket, body: ByteArray) {
        val req = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        if (req == null) {
            respondJson(s, 400, errJson("Invalid JSON body", "invalid_request_error"))
            return
        }
        val prompt = req.optString("prompt", "").trim()
        if (prompt.isEmpty()) {
            respondJson(s, 400, errJson("prompt is required", "invalid_request_error"))
            return
        }
        if (!DrawCore.isReady()) {
            respondJson(s, 503, errJson(
                "No drawing model loaded in Ponko. Open the app and load one first.",
                "service_unavailable"))
            return
        }
        if (DrawCore.busy || DrawCore.uiBusy) {
            respondJson(s, 429, errJson(
                "The drawing engine is busy with another request. Retry shortly.",
                "rate_limit_error"))
            return
        }
        val fmt = req.optString("response_format", "b64_json").lowercase()
        if (fmt != "b64_json") {
            respondJson(s, 400, errJson(
                "Only response_format=b64_json is supported (this server has no public URL to serve images from). " +
                    "Pass response_format=\"b64_json\" in your request.",
                "invalid_request_error"))
            return
        }

        val n = req.optInt("n", 1).coerceIn(1, 4)
        // size: "512x512" / "1024x1024"；认不出来就交给 App 当前设置
        var w: Int? = null
        var hgt: Int? = null
        val sizeStr = req.optString("size", "").trim()
        if (sizeStr.isNotEmpty()) {
            val m = Regex("^(\\d+)\\s*[xX×]\\s*(\\d+)$").find(sizeStr)
            if (m == null) {
                respondJson(s, 400, errJson(
                    "size must look like \"512x512\"", "invalid_request_error"))
                return
            }
            w = m.groupValues[1].toIntOrNull()
            hgt = m.groupValues[2].toIntOrNull()
        }
        val steps = when {
            req.has("steps") -> req.optInt("steps")
            req.has("num_inference_steps") -> req.optInt("num_inference_steps")
            else -> null
        }
        val cfg = when {
            req.has("cfg_scale") -> req.optDouble("cfg_scale").toFloat()
            req.has("guidance_scale") -> req.optDouble("guidance_scale").toFloat()
            else -> null
        }
        val negative = if (req.has("negative_prompt")) req.optString("negative_prompt") else null
        val seed0 = if (req.has("seed")) req.optLong("seed") else -1L

        served++
        lastRequest = "#$served draw n=$n · " + prompt.take(40).replace("\n", " ")

        val arr = JSONArray()
        try {
            // n 张一起交给 DrawCore（同一次持锁连着出），避免两张之间被界面插队
            val results = runBlocking {
                DrawCore.generate(prompt, n, seed0, steps, cfg, w, hgt, negative)
            }
            for (r in results) {
                val baos = java.io.ByteArrayOutputStream()
                r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                arr.put(JSONObject().put("b64_json",
                    android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)))
            }
        } catch (e: Throwable) {
            // 走到这里多半是客户端中途断开（写不出去）或引擎出错
            runCatching {
                respondJson(s, 500, errJson(e.message ?: e.javaClass.simpleName, "server_error"))
            }
            return
        }
        respondJson(s, 200, JSONObject()
            .put("created", System.currentTimeMillis() / 1000)
            .put("data", arr))
    }

    // ==================== /v1/chat/completions ====================

    private fun chatCompletions(s: Socket, body: ByteArray) {
        val req = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        if (req == null) {
            respondJson(s, 400, JSONObject().put("error", JSONObject()
                .put("message", "Invalid JSON body").put("type", "invalid_request_error")))
            return
        }
        val messages = ArrayList<ChatCore.Msg>()
        req.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                messages.add(ChatCore.Msg(o.optString("role", "user"), contentToText(o.opt("content"))))
            }
        }
        if (messages.none { it.role != "system" && it.text.isNotEmpty() }) {
            respondJson(s, 400, JSONObject().put("error", JSONObject()
                .put("message", "messages must contain a non-empty user message").put("type", "invalid_request_error")))
            return
        }
        if (!ChatCore.isReady()) {
            respondJson(s, 503, JSONObject().put("error", JSONObject()
                .put("message", "No chat model loaded in Ponko. Open the app and load a model first.")
                .put("type", "service_unavailable")))
            return
        }
        if (ChatCore.busy) {
            respondJson(s, 429, JSONObject().put("error", JSONObject()
                .put("message", "The model is busy with another request. Retry shortly.")
                .put("type", "rate_limit_error")))
            return
        }

        val stream = req.optBoolean("stream", false)
        val temperature = if (req.has("temperature")) req.optDouble("temperature").toFloat() else null
        val maxTokens = if (req.has("max_tokens")) req.optInt("max_tokens") else
            if (req.has("max_completion_tokens")) req.optInt("max_completion_tokens") else null
        val model = ChatCore.modelName ?: "ponko"
        val id = "chatcmpl-" + System.nanoTime().toString(36)
        val created = System.currentTimeMillis() / 1000
        served++
        val preview = messages.lastOrNull { it.role == "user" }?.text?.take(40)?.replace("\n", " ") ?: ""
        lastRequest = "#$served ${if (stream) "stream" else "once"} · $preview"

        if (!stream) {
            val answer = runCatching {
                runBlocking { ChatCore.generate(messages, temperature, maxTokens) { } }
            }.getOrElse { e ->
                respondJson(s, 500, JSONObject().put("error", JSONObject()
                    .put("message", e.message ?: e.javaClass.simpleName).put("type", "server_error")))
                return
            }
            val usage = JSONObject()
                .put("prompt_tokens", estimateTokens(messages.sumOf { it.text.length }))
                .put("completion_tokens", estimateTokens(answer.length))
                .put("total_tokens", estimateTokens(messages.sumOf { it.text.length } + answer.length))
            respondJson(s, 200, JSONObject()
                .put("id", id).put("object", "chat.completion").put("created", created).put("model", model)
                .put("choices", JSONArray().put(JSONObject()
                    .put("index", 0)
                    .put("message", JSONObject().put("role", "assistant").put("content", answer))
                    .put("finish_reason", "stop")))
                .put("usage", usage))
            return
        }

        // ---- SSE 流式 ----
        val out = s.getOutputStream()
        writeHead(out, 200, "text/event-stream")
        var broke = false
        fun event(obj: JSONObject) {
            if (broke) return
            try { writeChunk(out, "data: $obj\n\n") } catch (_: Throwable) {
                broke = true
                ChatCore.requestCancel()   // 客户端断开就别再算了
            }
        }
        fun deltaEvent(content: String, role: String? = null) {
            val d = JSONObject()
            if (role != null) d.put("role", role)
            d.put("content", content)
            event(JSONObject()
                .put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model)
                .put("choices", JSONArray().put(JSONObject()
                    .put("index", 0).put("delta", d)
                    .put("finish_reason", JSONObject.NULL))))
        }
        try {
            deltaEvent("", role = "assistant")
            runBlocking {
                ChatCore.generate(messages, temperature, maxTokens) { piece -> deltaEvent(piece) }
            }
            if (!broke) {
                event(JSONObject()
                    .put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model)
                    .put("choices", JSONArray().put(JSONObject()
                        .put("index", 0).put("delta", JSONObject()).put("finish_reason", "stop"))))
                writeChunk(out, "data: [DONE]\n\n")
            }
        } catch (e: Throwable) {
            if (!broke) {
                event(JSONObject().put("error", JSONObject()
                    .put("message", e.message ?: e.javaClass.simpleName).put("type", "server_error")))
            }
        } finally {
            runCatching { out.write("0\r\n\r\n".toByteArray()); out.flush() }
        }
    }

    /** OpenAI 的 content 可能是字符串，也可能是 [{type:"text",text:"..."}] 这类数组 */
    private fun contentToText(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                val type = part.optString("type", "text")
                if (type == "text" || type == "input_text") sb.append(part.optString("text"))
                else if (type == "image_url") sb.append("[image]")
            }
            sb.toString()
        }
        else -> content.toString()
    }

    /** 粗估 token（本地模型没有分词器接口，按 4 字符 ≈ 1 token）——只用于 usage 字段 */
    private fun estimateTokens(chars: Int): Int = (chars / 4).coerceAtLeast(1)

    // ==================== HTTP 输出 ====================

    private fun respond(s: Socket, code: Int, contentType: String?, body: ByteArray) {
        val out = s.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun respondJson(s: Socket, code: Int, obj: JSONObject) =
        respond(s, code, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

    /** SSE 头：分块传输，先不给 Content-Length，边算边发 */
    private fun writeHead(out: OutputStream, code: Int, contentType: String) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(" OK\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun writeChunk(out: OutputStream, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        out.write(Integer.toHexString(b.size).toByteArray())
        out.write("\r\n".toByteArray())
        out.write(b)
        out.write("\r\n".toByteArray())
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"
        503 -> "Service Unavailable"; else -> "OK"
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readBytes(ins: InputStream, len: Int): ByteArray {
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = ins.read(b, off, len - off)
            if (n <= 0) break
            off += n
        }
        return if (off == len) b else b.copyOf(off)
    }
}
