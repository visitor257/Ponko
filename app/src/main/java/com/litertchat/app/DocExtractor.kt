package com.litertchat.app

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * 文档正文抽取：把 docx / pptx / xlsx / odt / ods / odp / epub / rtf / html / pdf 里的**文字**抠出来，
 * 交给对话当上下文用（图片、版式、批注、修订记录一律不管）。
 *
 * 两条实现路线：
 * - **zip 家族**（OOXML / ODF / EPUB）：JDK 自带 zip + XmlPullParser 逐标签读文本，零依赖；
 * - **PDF**：交给 pdfbox-android（用户发 PDF 的场景最多，自己写解析器不划算，字体编码 / CMap 太容易踩坑）；
 * - **97-2003 老格式**（.doc / .xls / .ppt）是 OLE 复合二进制，解析成本极高、收益极低 → 只提示另存为 docx / pdf。
 *
 * 所有解析都包在 runCatching 里：坏文件返回 null 由调用方给文案，绝不冒泡崩到界面。
 */
object DocExtractor {

    /** zip 家族：解压后读 XML 就行 */
    val ZIP_DOCS = setOf("docx", "pptx", "xlsx", "odt", "ods", "odp", "epub")

    /** 单文件文本型（rtf / html） */
    val TEXT_DOCS = setOf("rtf", "html", "htm", "xhtml")

    /** 97-2003 老二进制 Office：只能提示另存 */
    val LEGACY_DOCS = setOf("doc", "xls", "ppt")

    /** 本类认识的扩展名（不含 .txt/.md/.csv 这类，那些走原来的纯文本路径） */
    val ALL: Set<String> = ZIP_DOCS + TEXT_DOCS + LEGACY_DOCS + setOf("pdf")

    /** zip / pdf 允许的原始字节上限 */
    const val MAX_DOC_BYTES = 48L * 1024 * 1024

    /** rtf / html 没压缩，别让用户塞进几十 MB */
    private const val MAX_TEXT_DOC_BYTES = 8L * 1024 * 1024

    /** PDF 最多解析前多少页（再多抽出来也远超本地模型上下文） */
    private const val MAX_PDF_PAGES = 60

    /**
     * 抽取结果。
     * @param units 结构单位数量（页 / 幻灯片 / 工作表），0 = 没有这个概念
     * @param unitRes 单位文案的资源 id（R.string.s_386 页 / s_387 幻灯片 / s_388 工作表）
     */
    class Ok(val text: String, val units: Int = 0, val unitRes: Int = 0)

    fun extract(ctx: Context, file: File, ext: String): Ok? = runCatching {
        when (ext.lowercase()) {
            "docx" -> readWord(file)
            "pptx" -> readPptx(file)
            "xlsx" -> readXlsx(file)
            "odt", "ods", "odp" -> readOdf(file)
            "epub" -> readEpub(file)
            "rtf" -> if (file.length() <= MAX_TEXT_DOC_BYTES)
                Ok(clean(stripRtf(String(file.readBytes(), Charsets.ISO_8859_1)))) else null
            "html", "htm", "xhtml" -> if (file.length() <= MAX_TEXT_DOC_BYTES)
                Ok(clean(htmlToText(String(file.readBytes(), Charsets.UTF_8)))) else null
            "pdf" -> readPdf(ctx, file)
            else -> null
        }
    }.getOrNull()?.takeIf { it.text.isNotBlank() }

    // ==================== zip 家族 ====================

    private fun readWord(file: File): Ok? = withZip(file) { z ->
        val entry = z.getEntry("word/document.xml") ?: return@withZip null
        val sb = StringBuilder()
        z.getInputStream(entry).use { ins ->
            walkXml(ins) { ev, name, text, _ ->
                when (ev) {
                    XmlPullParser.TEXT -> if (name == "t") sb.append(text)
                    XmlPullParser.START_TAG -> if (name == "tab") sb.append('\t')
                    XmlPullParser.END_TAG -> if (name == "p" || name == "br" || name == "cr") sb.append('\n')
                }
            }
        }
        Ok(clean(sb.toString()))
    }

    private fun readPptx(file: File): Ok? = withZip(file) { z ->
        // slide1, slide2 … slide10 要按数字排，不能按字符串排
        val slides = z.entries().toList()
            .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
            .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return@withZip null
        val sb = StringBuilder()
        slides.forEachIndexed { idx, entry ->
            if (idx > 0) sb.append("\n\n")
            z.getInputStream(entry).use { ins ->
                walkXml(ins) { ev, name, text, _ ->
                    when (ev) {
                        XmlPullParser.TEXT -> if (name == "t") sb.append(text)
                        XmlPullParser.END_TAG -> if (name == "p") sb.append('\n')
                    }
                }
            }
        }
        Ok(clean(sb.toString()), slides.size, R.string.s_387)
    }

    /** xlsx：先读共享字符串表，再把每张工作表的单元格按行拼出来（制表符分列） */
    private fun readXlsx(file: File): Ok? = withZip(file) { z ->
        val shared = ArrayList<String>()
        z.getEntry("xl/sharedStrings.xml")?.let { e ->
            z.getInputStream(e).use { ins ->
                val cur = StringBuilder()
                walkXml(ins) { ev, name, text, _ ->
                    when (ev) {
                        XmlPullParser.TEXT -> if (name == "t") cur.append(text)
                        XmlPullParser.END_TAG -> if (name == "si") {
                            shared.add(cur.toString()); cur.setLength(0)
                        }
                    }
                }
            }
        }
        val sheets = z.entries().toList()
            .filter { it.name.startsWith("xl/worksheets/") && it.name.endsWith(".xml") }
            .sortedBy { it.name }
        if (sheets.isEmpty()) return@withZip null

        val sb = StringBuilder()
        var rowBuf = StringBuilder()
        var cellBuf = StringBuilder()
        var cellType = ""
        var reading = false
        sheets.forEachIndexed { idx, entry ->
            if (idx > 0) sb.append("\n\n")
            if (sheets.size > 1) sb.append("Sheet ${idx + 1}\n")
            rowBuf = StringBuilder(); cellBuf = StringBuilder()
            z.getInputStream(entry).use { ins ->
                walkXml(ins) { ev, name, text, p ->
                    when (ev) {
                        XmlPullParser.START_TAG -> when (name) {
                            "c" -> cellType = p.getAttributeValue(null, "t") ?: ""
                            "v", "t" -> reading = true
                        }
                        XmlPullParser.TEXT -> if (reading) cellBuf.append(text)
                        XmlPullParser.END_TAG -> when (name) {
                            "v", "t" -> reading = false
                            "c" -> {
                                val raw = cellBuf.toString()
                                val shown = if (cellType == "s")
                                    shared.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: "" else raw
                                rowBuf.append(shown).append('\t')
                                cellBuf.setLength(0); cellType = ""
                            }
                            "row" -> {
                                sb.append(rowBuf.toString().trimEnd('\t')).append('\n')
                                rowBuf = StringBuilder()
                            }
                        }
                    }
                }
            }
        }
        Ok(clean(sb.toString()), sheets.size, R.string.s_388)
    }

    /** ODF（odt / ods / odp）：content.xml 里 text:p / text:h 就是段落 */
    private fun readOdf(file: File): Ok? = withZip(file) { z ->
        val entry = z.getEntry("content.xml") ?: return@withZip null
        val sb = StringBuilder()
        z.getInputStream(entry).use { ins ->
            walkXml(ins) { ev, name, text, _ ->
                when (ev) {
                    XmlPullParser.TEXT -> if (name == "p" || name == "h") sb.append(text)
                    XmlPullParser.START_TAG -> if (name == "table-cell") sb.append('\t')
                    XmlPullParser.END_TAG -> if (name == "p" || name == "h" || name == "table-row") sb.append('\n')
                }
            }
        }
        Ok(clean(sb.toString()))
    }

    private fun readEpub(file: File): Ok? = withZip(file) { z ->
        val pages = z.entries().toList()
            .filter { it.name.endsWith(".xhtml") || it.name.endsWith(".html") || it.name.endsWith(".htm") }
            .sortedBy { it.name }
        if (pages.isEmpty()) return@withZip null
        val sb = StringBuilder()
        pages.forEach { entry ->
            z.getInputStream(entry).use { ins ->
                sb.append(htmlToText(String(ins.readBytes(), Charsets.UTF_8))).append("\n\n")
            }
        }
        Ok(clean(sb.toString()), pages.size, R.string.s_386)
    }

    private inline fun <T> withZip(file: File, block: (ZipFile) -> T?): T? =
        runCatching { ZipFile(file).use { block(it) } }.getOrNull()

    // ==================== XML 遍历 ====================

    /**
     * 极简 XML 遍历：每个事件回调一次。`name` 是当前标签名，`p` 是解析器（要读属性时用）。
     *
     * TEXT 事件里 XmlPullParser 不告诉你所属标签，所以这里维护一个「最近的未闭合开始标签」，
     * 遇到 TEXT 时把它当父标签用——对本项目要读的这几种 XML 足够准。
     */
    private inline fun walkXml(ins: InputStream, crossinline onEvent: (Int, String, String, XmlPullParser) -> Unit) {
        val p = Xml.newPullParser()
        // 必须开命名空间处理：开了之后 name 才是「本地名」（w:t -> t、table:table-cell -> table-cell），
        // 关掉的话拿到的是带前缀的限定名，下面所有按标签名判断的分支都会失效。
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ins, null)
        var ev = p.eventType
        var cur = ""
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    cur = p.name ?: ""
                    onEvent(ev, cur, "", p)
                }
                XmlPullParser.TEXT -> onEvent(ev, cur, p.text ?: "", p)
                XmlPullParser.END_TAG -> {
                    val n = p.name ?: ""
                    onEvent(ev, n, "", p)
                    cur = ""
                }
            }
            ev = p.next()
        }
    }

    // ==================== RTF / HTML ====================

    /** RTF：只保留可见文本，跳过字体表 / 颜色表等控制组；按 \ansicpgN 决定 \'hh 的编码 */
    private fun stripRtf(src: String): String {
        val out = StringBuilder()
        var i = 0
        var depth = 0
        var skipDepth = -1
        var cp = "windows-1252"
        val pending = java.io.ByteArrayOutputStream()

        fun flushPending() {
            if (pending.size() == 0) return
            out.append(runCatching { String(pending.toByteArray(), charset(cp)) }.getOrDefault(""))
            pending.reset()
        }

        while (i < src.length) {
            val ch = src[i]
            when {
                ch == '{' -> { depth++; i++ }
                ch == '}' -> {
                    flushPending()
                    if (skipDepth >= 0 && depth <= skipDepth) skipDepth = -1
                    depth--; i++
                }
                ch == '\\' -> {
                    if (i + 1 >= src.length) { i++; continue }
                    val n = src[i + 1]
                    when {
                        n == '\\' || n == '{' || n == '}' -> { flushPending(); out.append(n); i += 2 }
                        n == '\'' && i + 3 < src.length -> {
                            val b = src.substring(i + 2, i + 4).toIntOrNull(16)
                            if (b == null) i += 2 else { pending.write(b); i += 4 }
                        }
                        n == '*' -> { if (skipDepth < 0) skipDepth = depth; i += 2 }
                        n.isLetter() -> {
                            var j = i + 1
                            while (j < src.length && src[j].isLetter()) j++
                            val word = src.substring(i + 1, j)
                            var k = j
                            if (k < src.length && (src[k] == '-' || src[k].isDigit())) {
                                while (k < src.length && (src[k] == '-' || src[k].isDigit())) k++
                            }
                            val param = if (k > j) src.substring(j, k).toIntOrNull() else null
                            if (k < src.length && src[k] == ' ') k++   // 控制字后的单个空格是分隔符
                            if (skipDepth < 0) {
                                when (word) {
                                    "par", "line", "sect", "page" -> { flushPending(); out.append('\n') }
                                    "tab" -> { flushPending(); out.append('\t') }
                                    "ansicpg" -> if (param != null) cp = "windows-$param"
                                    "u" -> if (param != null) {
                                        flushPending()
                                        val code = if (param < 0) param + 65536 else param
                                        out.append(code.toChar())
                                        // \uN 后面那个字符是「不支持时的替代」，吃掉
                                        if (k < src.length && src[k] != '\\' && src[k] != '{' && src[k] != '}') k++
                                    }
                                    "fonttbl", "colortbl", "stylesheet", "info", "generator", "listtable",
                                    "listoverridetable", "rsidtbl", "xmlnstbl", "pgptbl", "pict" ->
                                        if (skipDepth < 0) skipDepth = depth
                                }
                            }
                            i = k
                        }
                        else -> i += 2
                    }
                }
                skipDepth >= 0 -> i++
                ch == '\r' || ch == '\n' -> i++
                else -> { flushPending(); out.append(ch); i++ }
            }
        }
        flushPending()
        return out.toString()
    }

    /** HTML/XHTML：去掉脚本样式与标签，实体做基本还原 */
    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?is)</(p|div|li|tr|h[1-6]|blockquote|section|article|pre)>"), "\n")
        s = s.replace(Regex("(?is)</?(td|th)[^>]*>"), "\t")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        s = s.replace(Regex("&#(x?)([0-9A-Fa-f]+);")) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else ""
        }
        return s
    }

    // ==================== PDF ====================

    private fun readPdf(ctx: Context, file: File): Ok? = runCatching {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
            val pages = doc.numberOfPages
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                sortByPosition = true
                startPage = 1
                endPage = min(pages, MAX_PDF_PAGES)
            }
            val text = stripper.getText(doc)
            if (text.isBlank()) null else Ok(clean(text), pages, R.string.s_386)
        }
    }.getOrNull()

    // ==================== 清理 ====================

    /** 去掉零宽字符与多余空行，行尾空格也清掉——本地模型对这些噪声很敏感 */
    private fun clean(s: String): String = s
        .replace('\u0000', ' ')
        .replace("\uFEFF", "")
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
