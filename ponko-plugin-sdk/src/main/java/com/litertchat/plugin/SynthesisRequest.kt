// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 一次合成请求。
 *
 * 字段对齐 VoxCPM2 的 `nativeGenerate` 入参（见 `voxcpm2_jni.cpp:211`），
 * 但用「角色 + 语义」命名，避免插件作者去猜 `mode=2` 是什么意思。
 */
@Parcelize
data class SynthesisRequest(
    /**
     * 宿主生成的请求 ID。插件必须把它原样带回每个回调。
     *
     * 用途：用户取消后插件可能还在吐最后几块，宿主靠这个 ID **丢弃过期回调**。
     */
    val requestId: String,

    val text: String,

    /** 见 [PluginConst.MODE_PLAIN] / `MODE_CLONE` / `MODE_CONTINUATION` */
    val mode: Int,

    /**
     * 参考音频（`MODE_CLONE` / `MODE_CONTINUATION` 需要）。
     *
     * ⚠ 插件**读不到宿主的目录**，所以必须通过这个 FD 读。
     * 建议做成：`fdopen` → 复制到自己的 `cacheDir` → 后续全用自己路径。
     * 参考音频通常只有几 MB，复制一次的开销可以忽略。
     *
     * 格式要求 **16-bit PCM WAV**（单声道最好；多声道插件自己混）。
     */
    val referenceAudio: ParcelFileDescriptor? = null,

    /** 参考音频采样率；**0 = 插件从 WAV 头读** */
    val referenceSampleRate: Int = 0,

    /** `MODE_CONTINUATION` 必需：参考音频对应的文字转写 */
    val referenceTranscript: String = "",

    /**
     * 语音设计描述，如 `"年轻女性，温和"`。
     *
     * ⚠ **这不是一个独立的模型参数**。VoxCPM2 的语音设计靠**文本前缀**实现：
     * `"(年轻女性，温和)要说的内容"`。插件内部负责拼接。
     *
     * 两个细节：
     * 1. 描述用**模型训练时见过的语言**效果更好（英文描述通常更稳）
     * 2. 若 [text] 本身已以 `(` 开头，**不要再加前缀**，否则双前缀
     */
    val voiceDescription: String = "",

    /** CFG 引导强度；VoxCPM2 建议 2.0 左右。<0 = 用插件默认 */
    val cfg: Float = -1f,

    /** 扩散步数；10 起步，15-20 更细腻但更慢。<=0 = 用插件默认 */
    val timesteps: Int = 0,

    /** 随机种子；0 = 随机。固定种子可复现同一"演绎版本" */
    val seed: Int = 0,

    /** 自回归步数上限，防跑飞。<=0 = 用插件默认 */
    val maxSteps: Int = 0,

    /**
     * `true` → 走 [IPonkoCallback.onAudioChunk] 边生成边给；
     * `false` → 全部生成完在 [ResultBundle] 一次性交付。
     *
     * ⚠ **v1 建议宿主传 `false`**：VoxCPM2 的 RTF 是 10-30，
     * 流式只能降低首音延迟，播十几秒就会追上生成进度导致断续卡顿，
     * 体验反而比"等完再播"更差。流式留到 v2。
     */
    val streamAudio: Boolean = false,
) : Parcelable
