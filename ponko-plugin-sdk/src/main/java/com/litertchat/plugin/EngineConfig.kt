// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 加载引擎时的参数。
 *
 * 刻意保持极简：只有"怎么跑"的开关，不含任何模型路径 —— v1 模型归插件自己管。
 */
@Parcelize
data class EngineConfig(
    /** 推理线程数；**0 = 插件自定**（建议 `min(4, 设备物理大核数)`） */
    val threads: Int = 0,

    /** 是否尝试 GPU/NPU 后端。手机端一般不靠谱，默认关 */
    val useGpu: Boolean = false,

    /** 打开后插件可以往 logcat 打更详细的日志 */
    val logVerbose: Boolean = false,

    /**
     * 宿主给的内存上限（字节）。0 = 不限。
     *
     * 插件应在真正加载前用它和 `ActivityManager.MemoryInfo.availMem` 比对，
     * 不够就回 [ErrCode.OUT_OF_MEMORY] —— 这比让系统 OOM kill 体验好得多。
     */
    val maxMemoryBytes: Long = 0L,
) : Parcelable

/** 加载成功后回报的引擎信息。 */
@Parcelize
data class EngineInfo(
    /** 输出采样率，如 VoxCPM2 是 48000 */
    val sampleRate: Int,
    val channelCount: Int,
    /** 模型占用多少字节（含 KV cache 的估算），给 UI 显示 */
    val modelBytes: Long,
    /** 加载耗时，给 UI 显示"下次还要等这么久" */
    val loadElapsedMs: Long = 0L,
) : Parcelable

/**
 * 引擎状态快照，[IPonkoPlugin.getStatus] 返回。
 *
 * ⚠ 这个方法是**同步**的（不是 `oneway`），会在宿主的 Binder 线程上等返回。
 * 插件实现**必须 O(1)**：只读 `@Volatile` 字段，不加锁、不查文件、不调原生。
 */
@Parcelize
data class EngineStatus(
    /** 见 [EngineState] */
    val state: Int,
    val modelBytes: Long = 0L,
    /** 加载完成时刻（`SystemClock.elapsedRealtime()`）；0 = 未加载 */
    val loadedAtElapsedMs: Long = 0L,
) : Parcelable
