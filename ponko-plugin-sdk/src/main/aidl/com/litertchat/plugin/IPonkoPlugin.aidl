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
// v6：importModel 要传文件描述符
import android.os.ParcelFileDescriptor;
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

    // ==================== 宿主按钮的文案（v3）====================

    /**
     * 插件希望宿主的按钮叫什么。每项形如 `"engine.load=加载模型"`。
     *
     * **同步方法**：插件界面打开时就要用，走回调会让按钮先用默认字再跳一次。
     * 实现里必须 O(1)（读一个常量表即可）。
     *
     * 返回空 list 是正常的 —— 表示用宿主默认文案。
     * 认不出的 key、空值、过长的值都会被宿主忽略（逐项跳过，不影响其它项）。
     *
     * 可用的 key 见 [ActionLabel]：`engine.load` / `engine.unload` /
     * `reasoning` / `play` / `stop` / `save`。
     *
     * ⚠ 只改**文字**，改不了按钮的位置、个数、颜色和行为 ——
     * 按钮是宿主的界面，插件只借它的名字。
     *
     * 旧插件（SDK v2 及更早）没有这个方法，宿主按
     * `getApiVersion() < PluginConst.API_WITH_ACTION_LABELS` 跳过调用。
     */
    List<String> getActionLabels();

    // ==================== 模型文件导入（v6）====================

    /**
     * 宿主替用户选好了一个模型文件，把只读 FD 交给插件。
     *
     * 对应 [UiType.MODEL_SLOT] 那个槽位的「导入」按钮。
     *
     * ## 为什么是宿主 → 插件，而不是插件自己弹选择器
     *
     * 插件是 bound Service，在另一个进程：`startActivityForResult` 是 Activity
     * 的 API，Service 没有；而且 Android 10+ 禁止后台启动 Activity ——
     * 用户点「导入」那一刻插件不在前台，真发起了也会被拦。
     * 所以选择器只能宿主弹，插件只负责"收下这个 FD"。
     *
     * ## 插件必须做到的几件事
     *
     * 1. **在 worker 线程读**。这是 `oneway` 但读 2.8GB 会占死 Binder 线程池，
     *    之后所有回调都进不来（包括 `cancel`）
     * 2. **读完 close 掉 [fd]**。它是 Binder dup 过的一份，关它不影响宿主；
     *    不关就是 FD 泄漏（每进程默认 1024 个）
     * 3. 落盘到**自己的私有目录**（`filesDir` / `getExternalFilesDir`）。
     *    宿主目录你读不到，给了路径也没用
     * 4. 完事调 `notifySchemaChanged()`，新状态从 `buildSettingsSchema()` 的
     *    `UiItem.value` 里报出来
     *
     * ## 宿主那边落盘了吗
     *
     * **没有。** 宿主只是把 SAF 给出的 FD 转发过来，自己不留副本。
     * 所以磁盘上最终只有你这一份 —— 和用户在宿主模型页导入的代价相同。
     *
     * @param role        [UiType.MODEL_SLOT] 的 key，插件用它决定放哪、算什么角色
     * @param fd          只读 FD。**所有权归插件**，读完自己 close
     * @param displayName 用户看到的原始文件名，仅用于日志/显示，**不要拿它拼路径**
     * @param sizeBytes   文件大小；SAF 给不出时是 -1
     *
     * 旧插件（SDK v5 及更早）没有这个方法，宿主按
     * `getApiVersion() < PluginConst.API_WITH_MODEL_IMPORT` 跳过调用。
     */
    oneway void importModel(in String role, in ParcelFileDescriptor fd,
                            in String displayName, long sizeBytes);

    /**
     * 删除某个槽位已经导入的模型文件。
     *
     * 对应 [UiType.MODEL_SLOT] 那行的「清除」按钮。插件自己删自己私有目录里的文件
     * —— 宿主删不了（那是别的包的目录）。
     *
     * 删完同样调 `notifySchemaChanged()` 刷新状态。
     *
     * 引擎正在用这个模型时该不该允许删，由插件自己判断：
     * 合理的做法是先把 `UiItem.enabled` 设成 `false` 灰掉按钮，
     * 而不是等宿主点了再报错。
     */
    oneway void deleteModel(in String role);
}
