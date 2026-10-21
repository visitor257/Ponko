// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// 插件回调宿主。所有方法都是 oneway（不等返回），
// 因为插件在推理过程中会频繁回调，同步调用会占死宿主的 Binder 线程。
//
// ⚠ oneway 没有背压：插件发得快、宿主处理慢，消息会在宿主侧排队，
// 极端情况吃满宿主那 1MB Binder 配额、连累其它 IPC。
// 所以插件侧必须守规矩：
//   · onStage 最小间隔 PluginConst.STAGE_MIN_INTERVAL_MS（500ms）
//   · onAudioChunk 只在 SynthesisRequest.streamAudio == true 时发，每块 ≤ 64KB
//   · onResult / onError 只发一次（内部用 AtomicBoolean 保证）
package com.litertchat.plugin;

import android.os.ParcelFileDescriptor;
import com.litertchat.plugin.EngineInfo;
import com.litertchat.plugin.ResultBundle;
import com.litertchat.plugin.PluginError;

oneway interface IPonkoCallback {

    // ---------------- 引擎生命周期 ----------------

    /** 引擎加载成功。回报采样率、声道、占用字节数 */
    void onEngineLoaded(in EngineInfo info);

    /** 引擎加载失败（含内存不足、文件损坏、ABI 不符） */
    void onEngineError(in PluginError err);

    // ---------------- 单次合成 ----------------

    /**
     * 阶段变化，见 Stage。
     *
     * @param stage  当前阶段
     * @param label  可读文案，可为 null；宿主直接显示，例如「已生成 3.2 秒」
     */
    void onStage(int stage, in @nullable String label);

    /**
     * 流式音频块。**只有 streamAudio=true 时才会收到**。
     *
     * @param pcm           PCM 16-bit 小端交织；单块 ≤ 64KB
     * @param sampleRate    采样率（VoxCPM2 为 48000）
     * @param channelCount  声道数（通常 1）
     * @param isFinal       true = 最后一块，之后不再有 chunk
     */
    void onAudioChunk(in byte[] pcm, int sampleRate, int channelCount, boolean isFinal);

    /**
     * 最终结果。**只发一次**。
     *
     * streamAudio=false 时音频在 ResultBundle 里；true 时这里只带时长等元信息。
     */
    void onResult(in ResultBundle result);

    /** 出错。**只发一次**。用户主动取消也会走这里（ErrCode.CANCELLED） */
    void onError(in PluginError err);

    // ---------------- 插件界面 ----------------

    /**
     * 插件自己改动了界面数据（不是用户点的）。
     *
     * 典型场景：
     *   · 用户点了「下载模型」按钮 → 插件在后台下载 → 进度变化 → 通知宿主重画
     *   · 模型下载完成 → 原本灰掉的「试听」要变成可点
     *   · 引擎加载完成 → 某个滑块终于可用
     *
     * 宿主收到后**重新调用 getSettingsSchema()** 拿最新数据重画整页。
     * 所以插件不需要（也无法）直接推控件状态 —— 只有一个"请重画"的信号。
     *
     * ⚠ 不要高频调（比如每 1% 下载进度调一次）—— 每次都会触发一次跨进程调用
     * 加整页重绘。节流到 500ms 以上，或者只在阶段变化时调。
     */
    void onSchemaChanged();
}
