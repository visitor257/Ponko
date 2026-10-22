// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

/**
 * 宿主内置动作的 id。
 *
 * ## 用途
 *
 * 把 [UiType.ACTION] 的 `value` 写成这里的某个常量，那个按钮就**由宿主执行**，
 * 而不是回调插件：
 *
 * ```kotlin
 * UiItem(
 *     key = "btn_go",                 // 你自己的 key，宿主不用它
 *     type = UiType.ACTION,
 *     label = "开始推理",              // 文案你定
 *     value = HostActionId.REASONING,   // ← 这一行说明"这是宿主的合成动作"
 *     primary = true,
 * )
 * ```
 *
 * 于是同一条 schema 可以有：
 *
 * ```kotlin
 * TABS_START(key = "main")
 *   TAB(key = "param", label = "参数")
 *     UiItem(key = "txt", type = UiType.TEXTAREA, label = "文本")
 *     UiItem(key = "go",  type = UiType.ACTION, label = "开始推理",
 *            value = HostActionId.REASONING, primary = true)   // 按钮在参数页里
 *   TAB(key = "result", label = "结果")
 *     UiItem(key = "play", type = UiType.ACTION, label = "试听",
 *            value = HostActionId.PLAY)
 *     UiItem(key = "save", type = UiType.ACTION, label = "存到音乐库",
 *            value = HostActionId.SAVE)
 * TABS_END
 * ```
 *
 * ## 为什么要这么绕，而不是让插件自己实现按钮逻辑
 *
 * 这些动作背后是**宿主的状态机**：引擎加载了几成、当前是否在合成、
 * 参考音频的 FD 在哪、播放器是谁、怎么存进系统音乐库。
 * 插件在另一个进程里，这些它一样都拿不到。
 *
 * 所以能放开的只有**「放哪些、放哪里、叫什么」**，执行仍然归宿主。
 * 这个区分是有意的：它让"插件完全控制布局"和"逻辑只有一份"同时成立。
 *
 * ## 没声明的动作会怎样
 *
 * 照旧留在宿主顶部那排功能按钮里。所以你可以只接管其中一个
 * （比如把「合成」放进参数页），其余的不用管 —— **逐个覆盖**，不是全有全无。
 *
 * ## 老宿主不认识怎么办
 *
 * 老宿主会把 `value` 当成普通 ACTION 的值忽略掉，点下去回调
 * `onActionInvoked(key)`。所以不会崩，但也不会合成。
 * 想让老宿主上也正常，先判 `apiVersion >= 8`。
 *
 * ## 为什么叫 `HostActionId` 而不是 `HostAction`
 *
 * 宿主内部有一个 `PluginUiPage.HostAction`（一个按钮的 id + 文案 + 点击行为）。
 * 两个同名的东西同时出现在宿主代码里，裸写 `HostAction` 会解析到那个内部类 ——
 * 所以这里带上 `Id`，专指"动作的标识"这一层。
 *
 * @see UiType.ACTION
 */
object HostActionId {

    /** 加载 / 卸载引擎。文案会被宿主按状态改写（加载↔卸载），别和它抢 */
    const val ENGINE = "engine"

    /**
     * 合成一段音频。用当前参数（目标文本、模式、参考音频、逐字稿、种子）。
     *
     * 和宿主自己那个「合成」按钮完全同一条路径，包括参数校验 ——
     * 文本为空、缺参考音频时不会开始，宿主会把原因显示在状态行上。
     */
    const val REASONING = "reasoning"

    /** 播放上一次的结果。不重新推理 */
    const val PLAY = "play"

    /** 停止当前这次合成 */
    const val STOP = "stop"

    /** 把音频存进系统音乐库（`音乐/Ponko`） */
    const val SAVE = "save"
}
