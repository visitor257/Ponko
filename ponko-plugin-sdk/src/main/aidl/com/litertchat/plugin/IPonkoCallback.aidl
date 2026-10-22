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
     *
     * **v4 起：能用 [onUiPatch] 就别用它。** 这个是"整页重画"，
     * 会清空所有控件再重建 —— 用户滚到一半被拉回顶部、输入框失焦、
     * 正在编辑的 TEXTAREA 内容被覆盖。只想改一个字/一行日志的话，
     * 用 onUiPatch 只动那一个控件。
     */
    void onSchemaChanged();

    // ==================== v4：过程与产物 ====================

    /**
     * 送一张结果图。对应 [UiType.IMAGE]。
     *
     * ## 为什么单独开一个方法，而不是往 ResultBundle 加字段
     *
     * 加字段会改 Parcelable 的二进制结构：新宿主读老插件写的数据时字段数对不上，
     * 轻则读到垃圾值、重则 `BadParcelableException`。而独立方法对两头都安全 ——
     * 老插件不会调它（新宿主收不到就是收不到），新插件调老宿主时
     * oneway 静默丢弃、不崩。
     *
     * ## FD 所有权
     *
     * **宿主读完负责 close**。插件把文件 open 后交出去，不要自己先关。
     *
     * @param fd     PNG/JPEG 等常见格式即可，宿主自己按魔数判断
     * @param width  像素宽；不确定就传 0，宿主按实际解码结果
     * @param height 像素高；同上
     * @param mime   形如 `image/png`，可为 null（宿主按魔数判断）
     */
    void onImage(in ParcelFileDescriptor fd, int width, int height, in @nullable String mime);

    /**
     * **原地**改一个控件的内容，不重建整页。
     *
     * 这是 [onSchemaChanged] 的轻量版：[UiType.LOG] 追加日志、
     * [UiType.PROGRESS] 推进度、[UiType.STATUS] 改一行值，都走这里。
     *
     * @param key    目标 [UiItem.key]。**必须和 schema 里那个 item 的 key 完全一致**，
     *               对不上就静默忽略（找不到控件，宿主不会去猜）
     * @param value  新内容。[UiType.LOG] 是**要追加的那一段**（append=true），
     *               其它是**整段替换**
     * @param append true = 追加到末尾；false = 替换
     *
     * ## 宿主不认识的情况一律忽略，不报错
     *
     * key 对不上、那个控件当前不在页面上（用户切走了）、文案和当前类型不匹配
     * （比如给 [UiType.SWITCH] 补丁）—— 全都静默跳过。
     * 插件不该依赖补丁"一定生效"，重要状态仍应体现在 `getSettingsSchema()` 里。
     *
     * ⚠ 和 [onSchemaChanged] 一样是 oneway、没有背压。
     * 刷日志要节流（建议 ≥100ms 合并一次），别每读一行就发一次。
     */
    void onUiPatch(in String key, in @nullable String value, boolean append);
}
