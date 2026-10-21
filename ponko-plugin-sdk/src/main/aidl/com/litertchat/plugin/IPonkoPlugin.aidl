// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// 插件主接口。宿主 bindService 成功后通过 asInterface(binder) 拿到它。
//
// 设计取舍（详见 docs/plugin-sdk.md §3.1）：
//   · loadEngine / synthesize / cancel 全用 oneway —— 加载 2.83GB 要几十秒，
//     同步调用会占死宿主的 Binder 线程（每进程只有 16 个），期间其它 IPC 都排队。
//     oneway 让调用立刻返回，结果全靠回调。
//   · 不做独立的 IPonkoEngine 对象 —— 一个插件 = 一个模型家族 = 同时只有一个引擎，
//     多一层对象只会给插件作者加样板。
//   · getApiVersion / isEngineReady / getStatus 是同步的，因为它们必须立即返回；
//     插件实现必须 O(1)，只读 volatile 字段，不加锁、不调原生。
package com.litertchat.plugin;

import com.litertchat.plugin.IPonkoCallback;
import com.litertchat.plugin.PluginManifest;
import com.litertchat.plugin.ModelBundle;
import com.litertchat.plugin.EngineConfig;
import com.litertchat.plugin.SynthesisRequest;
import com.litertchat.plugin.EngineStatus;
import com.litertchat.plugin.PluginError;
import com.litertchat.plugin.UiSchema;

interface IPonkoPlugin {

    /**
     * 插件编译时用的 SDK 版本（PluginConst.API_VERSION）。
     *
     * 宿主会校验它落在 [API_MIN_SUPPORTED, API_MAX_SUPPORTED] 区间内；
     * 不在就**不绑定**，在插件列表里显示「需要更新」。
     */
    int getApiVersion();

    /** 插件自述：id、名称、版本、能力、支持的模型家族 */
    PluginManifest getManifest();

    /**
     * 注册回调。宿主绑定成功后第一件事就调它。
     * 重复调用以**最后一次**为准（旧引用由插件自行丢弃，不要持有多个）。
     */
    void registerCallback(in IPonkoCallback cb);

    /**
     * 加载引擎（模型）。异步：结果走 onEngineLoaded / onEngineError。
     *
     * 幂等：已在加载中或已加载时重复调用**直接忽略**，不报错。
     *
     * @param models 宿主传入的模型；**v1 传 null**（插件自管模型，见设计文档 §4）
     */
    oneway void loadEngine(in @nullable ModelBundle models, in EngineConfig config);

    /**
     * 卸载引擎，释放模型内存。异步完成，完成后 isEngineReady() 变 false。
     *
     * 宿主在**退出、用户手动卸载、或内存不足要腾空间**时调它。
     * 推理进行中调用 = 先取消再卸载。
     */
    oneway void closeEngine();

    /** 引擎是否已就绪。**必须 O(1)**（只读 volatile 字段） */
    boolean isEngineReady();

    /** 引擎状态快照。**必须 O(1)** */
    EngineStatus getStatus();

    /**
     * 执行一次合成。异步：进度走 onStage / onAudioChunk，结果走 onResult / onError。
     *
     * 同一时刻只允许一个任务；已有任务在跑时立刻回 onError(ErrCode.BUSY) ——
     * **不要排队**，排队会让用户以为点了没反应。
     * 引擎未就绪时立刻回 onError(ErrCode.NOT_READY)。
     */
    oneway void synthesize(in SynthesisRequest req);

    /**
     * 取消当前任务。没有任务时是空操作。
     *
     * 取消后插件**必须**回一次 onError(ErrCode.CANCELLED) —— 宿主靠它收尾
     * （撤掉进度条、释放唤醒锁）。**取消不是失败**，宿主应静默处理。
     */
    oneway void cancel();

    // ==================== 插件界面（宿主渲染）====================

    /**
     * 返回插件的界面数据，宿主用它渲染出一整页。
     *
     * **同步方法**，因为它要在打开页面的瞬间拿到内容 —— 走回调会让页面先空一下。
     * 所以实现里**必须是 O(1) 或很快**：只读已缓存的状态，不做 IO、不加载模型。
     * 需要慢操作（如下载模型）时，让那一项是 `TYPE_ACTION` 按钮，点了走 [invokeAction]。
     *
     * 返回**空 `UiSchema`（items 为空）是合法的** —— 表示"这个插件没有可配置项"，
     * 宿主会显示一个空页面而不是报错。
     *
     * 旧插件（没有实现这个方法）会让宿主的调用抛异常，宿主已做兜底显示。
     */
    UiSchema getSettingsSchema();

    /**
     * 用户改了某一项的值。
     *
     * 宿主**只负责调用，不负责持久化** —— 值归插件管。
     * 插件存好后如果要改别的项（比如选了模式 A 就让某个滑块灰掉），
     * 调 [IPonkoCallback.onSchemaChanged] 让宿主重新拉一遍。
     *
     * @param key [UiItem.key]
     * @param value 字符串化的新值
     */
    oneway void setSetting(in String key, in String value);

    /**
     * 用户点了某个 `TYPE_ACTION` 的项。
     *
     * @param key [UiItem.key]
     */
    oneway void invokeAction(in String key);
}
