// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 按模块分开的诊断日志。
 *
 * ## 为什么要分模块，不做一份大的
 *
 * 各模块的**隐私敏感度完全不同**：绘图 prompt 是「一只猫坐在桌上」，
 * 对话内容是用户自己在聊什么。混在一份文件里，就只能取最严的标准 ——
 * 结果是需要复现的绘图问题记不下来。
 *
 * 分开之后可以分别定规则：
 *
 * | 模块 | 内容 | prompt |
 * |---|---|---|
 * | [DRAW] | 模型加载、槽位、内存、生成参数、耗时 | 前 80 字 + 总长 |
 * | [CHAT] | 后端、上下文长度、耗时、错误 | **只记长度，不记内容** |
 * | [TAGGER] | 模型、阈值、耗时 | — |
 * | [PLUGIN] | 连接、握手、失败原因 | — |
 *
 * ## 为什么每行都要落盘
 *
 * 要抓的是 **native 崩溃**（C++ `abort`、被 low-memory-killer 杀掉）。
 * 那类崩溃走不到 Java 的 `UncaughtExceptionHandler`，进程直接消失 ——
 * 缓冲区里没写出的一律丢失。
 *
 * 所以每次 [w] 都是「打开 → 写 → 关闭」，靠 close 强制落进内核页缓存。
 * 不用 `fsync`：进程被 SIGABRT 杀掉时页缓存仍然会落盘，只有断电/kernel panic
 * 才需要 fsync，而那两种情况用户也报不了 issue。
 *
 * 开销上不必担心：sd.cpp 一步推理要几秒，写一行日志不到 1ms。
 *
 * ## 滚动
 *
 * 单文件超过 [MAX_BYTES] 就**保留后半段**再写。不按行切是为了省事，
 * 代价是丢掉的那次会话第一行可能是半句 —— 日志不是审计记录，可以接受。
 */
object LogBook {

    // ---- 模块名。UI 里按这个顺序列 ----
    const val DRAW = "draw"
    const val CHAT = "chat"
    const val TAGGER = "tagger"
    const val PLUGIN = "plugin"

    /** UI 展示顺序 */
    val MODULES = listOf(
        DRAW to "绘图", CHAT to "对话", TAGGER to "打标", PLUGIN to "插件",
    )

    /** 单模块日志上限。超过就裁掉前半，别让它无限长。 */
    private const val MAX_BYTES = 512 * 1024

    /** prompt 这类可能含私人内容的，最多留这么多字 */
    private const val PROMPT_HEAD = 80

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 进程级 Context。设了之后 [w] 可以不带 Context。
     *
     * 为什么需要：`ChatCore`、`DrawCore` 这些是 object 单例，手里没有 Context，
     * 而给每个方法塞一个 Context 参数会污染它们的签名（它们本来只关心模型）。
     * 由 `MainActivity` 启动时调一次 [init] 即可。
     */
    @Volatile private var app: Context? = null

    /** 在 Activity/Application 启动时调一次。用 applicationContext，避免泄漏 Activity。 */
    fun init(c: Context) {
        app = c.applicationContext
    }

    /** 目录：filesDir/logs */
    fun dir(c: Context): File = File(c.filesDir, "logs").apply { mkdirs() }

    fun file(c: Context, module: String): File = File(dir(c), "$module.log")

    /**
     * 写一行（不带 Context，用 [init] 注入的那个）。
     * 还没 init 就静默丢弃 —— 日志不能成为启动顺序的依赖。
     */
    fun w(module: String, msg: String) {
        app?.let { w(it, module, msg) }
    }

    /**
     * 写一行。**失败静默** —— 日志本身不能把 App 弄崩。
     */
    fun w(c: Context, module: String, msg: String) {
        val f = file(c, module)
        val line = "${stamp.format(Date())} ${msg.replace("\u0000", "")}\n"
        try {
            if (f.length() > MAX_BYTES) {
                val keep = f.readBytes().let { it.copyOfRange(it.size / 2, it.size) }
                f.writeBytes(keep)
            }
            f.appendText(line)
        } catch (_: Throwable) {
            // 磁盘满、权限异常……都不该影响主流程
        }
    }

    /** 读全文（UI 用）。不存在返回空串。 */
    fun read(c: Context, module: String): String =
        try {
            file(c, module).takeIf { it.exists() }?.readText().orEmpty()
        } catch (_: Throwable) {
            ""
        }

    fun clear(c: Context, module: String) {
        try { file(c, module).delete() } catch (_: Throwable) {}
    }

    fun sizeOf(c: Context, module: String): Long =
        try { file(c, module).takeIf { it.exists() }?.length() ?: 0L } catch (_: Throwable) { 0L }

    // ---- 以下是给调用方用的格式化助手，保证各模块写法一致 ----

    /**
     * prompt 摘要。**绘图**用这个：留前 [PROMPT_HEAD] 字，够看出问题类型
     * （比如是不是超长、是不是含特殊字符），又不会把整段提示词抄进日志。
     *
     * 对话**不要**用这个 —— 那里只记 [lenOnly]。
     */
    fun briefPrompt(p: String): String {
        val one = p.replace(Regex("\\s+"), " ").trim()
        val head = if (one.length <= PROMPT_HEAD) one else one.take(PROMPT_HEAD) + "…"
        return "${one.length} chars \"$head\""
    }

    /** 只记长度，不记内容。对话用这个。 */
    fun lenOnly(p: String): String = "${p.length} chars"

    /** MB 显示，日志里到处都是，统一一下 */
    fun mb(bytes: Long): String = "${bytes / 1048576} MB"
}
