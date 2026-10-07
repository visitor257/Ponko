package com.litertchat.app

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
 * 需要的就三条路由 + SSE，自己写反而更可控（尤其是流式与客户端断连的处理）。
 *
 * 已实现：
 * - `GET  /health`                 存活 + 当前模型 + 是否忙
 * - `GET  /v1/models`              模型列表（OpenAI 格式）
 * - `POST /v1/chat/completions`    对话（`stream: true` 走 SSE）
 * - `OPTIONS *`                    CORS 预检（浏览器里的客户端要）
 *
 * 生成全部交给 [ChatCore]，这里只做协议转换。一次只允许一个请求（本地模型跑不了并发），
 * 忙时返回 429 —— 客户端看到的是标准的 rate-limit 语义。
 */
class ApiServer(
    private val port: Int,
    /** 为空 = 不校验（用户明确要求「可以有 key 也可以没有」） */
    private val apiKey: String?,
    private val log: (String) -> Unit,
) {

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
            method == "GET" && (path == "/health" || path == "/") -> respondJson(s, 200, JSONObject()
                .put("status", "ok")
                .put("model", ChatCore.modelName ?: JSONObject.NULL)
                .put("ready", ChatCore.isReady())
                .put("busy", ChatCore.busy))

            method == "GET" && path == "/v1/models" -> respondJson(s, 200, modelsJson())

            method == "POST" && path == "/v1/chat/completions" -> chatCompletions(s, body)

            else -> respondJson(s, 404, JSONObject().put("error", JSONObject()
                .put("message", "Not found: $method $path").put("type", "invalid_request_error")))
        }
    }

    private fun modelsJson(): JSONObject {
        val arr = JSONArray()
        ChatCore.modelName?.let { name ->
            arr.put(JSONObject()
                .put("id", name)
                .put("object", "model")
                .put("created", System.currentTimeMillis() / 1000)
                .put("owned_by", "ponko"))
        }
        return JSONObject().put("object", "list").put("data", arr)
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
        404 -> "Not Found"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"
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
