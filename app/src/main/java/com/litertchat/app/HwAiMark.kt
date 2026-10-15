// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// 华为上架版专用：AI 生成内容的「显式标识」与「隐式标识」。
//
//  显式标识 —— 肉眼可见：
//    · 对话页右上角常驻一行提示（界面里加，不在这里）
//    · 绘图产出在图片**底部接一条标识带**（见 stamp()）
//  隐式标识 —— 肉眼不可见、机器可读、裁图裁不掉：
//    · 落盘时往 PNG 里写入 EXIF 注释字段与 PNG 文本块（见 withExif()）
//
// 为什么元数据是自己按规范拼字节，而不是用 ExifInterface：
//   实测 androidx.exifinterface 1.3.7 的 setAttribute() 对非 ASCII 一律写成 '?'，
//   中文根本存不进去（JPEG / PNG 都一样，已在本机用 JVM 逐字段验证）。
//   所以这里直接按 EXIF / PNG 规范生成字节，并用第三方解析器读回校验过：
//   ImageDescription 放 UTF-8 中文、UserComment 放「UNICODE\0 + UTF-16BE」中文，
//   两种编码同时写入，任何按其中一种解读的工具都能取到。
package com.litertchat.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.roundToInt

object HwAiMark {

    /** 标识带高宽比：20 / 256。256 宽的图 → 带高 20；512 → 40；1024 → 80。 */
    private const val BAND_RATIO = 20f / 256f

    /** 带底色（近黑，略透明）与文字色 */
    private const val BAND_BG = 0xF01A1A1A.toInt()
    private const val BAND_FG = 0xFFEDEDED.toInt()

    /** 带内左右留白：带宽的 1.5%，至少 2px */
    private fun pad(w: Int): Int = max(2, (w * 0.015f).roundToInt())

    /** 标识带高度。stamp 与 strip 必须用同一算法，否则裁不准。 */
    fun bandHeightFor(width: Int): Int = max(1, (width * BAND_RATIO).roundToInt())

    /**
     * 在 [src] 底部接一条同宽的标识带，带内居中写 [label]。返回新图，原图不动。
     * 失败时返回原图（宁可少一条带，也不能让出图流程挂掉）。
     */
    fun stamp(src: Bitmap, label: String): Bitmap = try {
        val w = src.width
        val h = src.height
        val band = bandHeightFor(w)
        val out = Bitmap.createBitmap(w, h + band, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawBitmap(src, 0f, 0f, null)

        val bg = Paint().apply { color = BAND_BG }
        cv.drawRect(0f, h.toFloat(), w.toFloat(), (h + band).toFloat(), bg)

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BAND_FG
            textSize = band * 0.62f
        }
        // 窄图上按需要缩字，保证不溢出
        val maxW = (w - pad(w) * 2).coerceAtLeast(8)
        var size = p.textSize
        while (size > 3f && p.measureText(label) > maxW) {
            size -= 0.5f
            p.textSize = size
        }
        val fm = p.fontMetrics
        val baseline = h + band / 2f - (fm.ascent + fm.descent) / 2f
        cv.drawText(label, pad(w).toFloat(), baseline, p)
        out
    } catch (_: Throwable) {
        src
    }

    /**
     * 去掉标识带 —— 给「发送至图生图 / 打标」用：
     * 标识带是给人和审核看的，不该被当成画面内容再喂回模型。
     */
    fun strip(src: Bitmap): Bitmap = try {
        val band = bandHeightFor(src.width)
        if (src.height <= band * 2) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height - band)
    } catch (_: Throwable) {
        src
    }

    // ------------------------------------------------------------------
    // 隐式标识：写进 PNG 的元数据
    // ------------------------------------------------------------------

    /** EXIF 注释字段用的中文标记（如「本图片由人工智能（AI）生成」） */
    private const val TIFF_MAGIC = 0x002A

    /**
     * 往 PNG 字节流里插入两类机器可读的 AI 生成标记，都在 IHDR 之后：
     *
     *  1. `eXIf` 块（EXIF 标准）：
     *     · ImageDescription(0x010E) = UTF-8 的 [marker]，按 UTF-8 解读的工具能直接读到
     *     · UserComment(0x9286)      = "UNICODE\0" + UTF-16BE 的 [marker]（EXIF 规范的 Unicode 注释）
     *     · Software(0x0131)         = [ascii]，纯 ASCII，任何工具都读得到
     *  2. `iTXt` 块（PNG 规范里唯一原文支持 UTF-8 的文本块），keyword = Description
     *
     * 任何一步出错都返回原字节，绝不因为写元数据把图写坏。
     */
    fun withExif(png: ByteArray, marker: String, ascii: String): ByteArray = try {
        if (png.size < 16 || !looksLikePng(png)) {
            png
        } else {
            val out = ByteArrayOutputStream(png.size + 1024)
            out.write(png, 0, 8)                      // PNG 签名
            var pos = 8
            var inserted = false
            while (pos + 8 <= png.size) {
                val len = readUInt(png, pos)
                val type = String(png, pos + 4, 4, Charsets.US_ASCII)
                val total = 12 + len
                val avail = minOf(total, png.size - pos)
                if (!inserted && type == "IHDR") {
                    out.write(png, pos, avail)
                    out.write(makeChunk("eXIf", buildTiff(marker, ascii)))
                    out.write(makeChunk("iTXt", buildItxt("Description", marker)))
                    inserted = true
                } else {
                    out.write(png, pos, avail)
                }
                pos += total
                if (type == "IEND") break
            }
            if (inserted) out.toByteArray() else png
        }
    } catch (_: Throwable) {
        png
    }

    private fun looksLikePng(b: ByteArray): Boolean =
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() &&
                b[3] == 'G'.code.toByte()

    /** PNG 块的长度字段是 4 字节大端无符号。 */
    private fun readUInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** 组装一个标准 PNG 块：长度 + 类型 + 数据 + CRC32(类型+数据)。 */
    private fun makeChunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(12 + data.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(data.size)
        bb.put(t)
        bb.put(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        bb.putInt(crc.value.toInt())
        return out
    }

    /**
     * 最小可用的 EXIF（TIFF）结构，小端：
     *   header(8) | IFD0(3 项) | Exif 子 IFD(1 项) | 数据区
     * UserComment 必须放 Exif 子 IFD（0x8769 指过去），放 IFD0 里解析器取不到。
     */
    private fun buildTiff(marker: String, ascii: String): ByteArray {
        val desc = marker.toByteArray(Charsets.UTF_8) + 0            // ASCII 类型也以 NUL 结尾
        val soft = ascii.toByteArray(Charsets.US_ASCII) + 0
        // EXIF 规范：UserComment 前 8 字节是字符码前缀，UNICODE 用 UTF-16
        val ucmt = "UNICODE\u0000".toByteArray(Charsets.US_ASCII) + marker.toByteArray(Charsets.UTF_16BE)

        val ifd0Off = 8
        val ifd0Size = 2 + 12 * 3 + 4
        val exifOff = ifd0Off + ifd0Size
        val exifSize = 2 + 12 * 1 + 4
        val dataOff = exifOff + exifSize
        val oDesc = dataOff
        val oSoft = oDesc + desc.size
        val oUcmt = oSoft + soft.size
        val total = oUcmt + ucmt.size

        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()); b.put('I'.code.toByte())
        b.putShort(TIFF_MAGIC.toShort())
        b.putInt(ifd0Off)

        b.putShort(3)
        putEntry(b, 0x010E, 2, desc.size, oDesc)      // ImageDescription
        putEntry(b, 0x0131, 2, soft.size, oSoft)      // Software
        putEntry(b, 0x8769, 4, 1, exifOff)            // ExifIFDPointer
        b.putInt(0)                                   // 无下一个 IFD

        b.putShort(1)
        putEntry(b, 0x9286, 7, ucmt.size, oUcmt)      // UserComment
        b.putInt(0)

        b.put(desc); b.put(soft); b.put(ucmt)
        return b.array()
    }

    private fun putEntry(b: ByteBuffer, tag: Int, type: Int, count: Int, value: Int) {
        b.putShort(tag.toShort())
        b.putShort(type.toShort())
        b.putInt(count)
        // 数据 <= 4 字节时内联，这里用到的三个字段都超过 4 字节，统一写偏移
        b.putInt(value)
    }

    /** PNG iTXt：keyword\0 压缩标志 压缩方法 语言标签\0 翻译关键字\0 UTF-8 文本 */
    private fun buildItxt(keyword: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(keyword.toByteArray(Charsets.UTF_8))
        out.write(0)                       // keyword 结束
        out.write(0)                       // 未压缩
        out.write(0)                       // 压缩方法（无）
        out.write(0)                       // 语言标签为空
        out.write(0)                       // 翻译关键字为空
        out.write(text.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }
}
