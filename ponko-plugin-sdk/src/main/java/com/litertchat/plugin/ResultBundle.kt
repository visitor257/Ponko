// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 一次合成的最终结果。
 *
 * **大小分两路**（这是 Binder 1MB 配额的正确应对方式）：
 * - 小结果（< [PluginConst.INLINE_AUDIO_MAX_BYTES]）→ [inlineAudio] 内联，省一次 FD
 * - 大结果 → [audio] 给 FD
 *
 * ⚠ 绝不能把整段音频塞进 `ByteArray` 直接过 Binder ——
 * 那 1MB 是**全进程所有并发 Binder 事务共享的配额**，
 * 单次 500KB 看着安全，累加照样 `TransactionTooLargeException`。
 */
@Parcelize
data class ResultBundle(
    /** 原样带回 [SynthesisRequest.requestId] */
    val requestId: String,

    val sampleRate: Int,
    val channelCount: Int,

    /** 音频时长 */
    val durationMs: Long,

    /** 实际耗时（含加载、解码），给 UI 显示"下次大概等多久" */
    val elapsedMs: Long,

    /** 见 [PluginConst.FMT_WAV] / `FMT_PCM16` */
    val audioFormat: Int,

    /**
     * 大结果走这里。**所有权归宿主**：宿主读完负责 close。
     * 插件把自己的临时文件 open 后交出去，不要自己先 close。
     */
    val audio: ParcelFileDescriptor? = null,

    /** 小结果内联（`FMT_PCM16` 的裸字节或完整 WAV 字节，看 [audioFormat]） */
    val inlineAudio: ByteArray? = null,

    /** 可选调试信息，仅写日志，不要显示给用户 */
    val debugInfo: String = "",
) : Parcelable {
    // @Parcelize 对 ByteArray 字段生成的 equals/hashCode 是按引用比较，
    // 这不是我们想要的语义（但本类只用于跨进程传输，不做集合键，故不覆写）。
}

/**
 * 统一错误对象。
 *
 * [message] **由插件本地化**（插件自己有中文/英文资源），宿主**直接显示**，
 * 不再二次加工 —— 否则会出现"插件说 A、宿主又包一层说 B"的混乱。
 */
@Parcelize
data class PluginError(
    /** 见 [ErrCode] */
    val code: Int,

    /** 已本地化，可直接显示给用户 */
    val message: String,

    /** 原始错误（异常栈、原生报错原文），**只进日志**，不要显示 */
    val detail: String = "",

    /** `true` = 宿主可以自动重试一次（建议最多一次，再失败就报给用户） */
    val retryable: Boolean = false,

    /** 关联的请求 ID；加载期错误为 `null` */
    val requestId: String? = null,
) : Parcelable
