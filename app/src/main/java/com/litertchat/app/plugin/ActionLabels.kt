// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import com.litertchat.app.R
import com.litertchat.plugin.ActionLabel

/**
 * 宿主按钮的默认文案。
 *
 * ## 为什么这张表在宿主、不在 SDK
 *
 * 插件只需要 [ActionLabel] 里那几个 **key**（`engine.load` 之类），
 * 它不知道也不该知道宿主把这些按钮叫成什么。
 *
 * 把默认文案放进 SDK 的后果：每个插件 APK 都打包一份别的 App 的字符串
 * （全世界只有这个 App 会用到它们），而且以后做多语言时要改两处、
 * 还得让所有插件重编才能跟上。
 *
 * ## 文案走资源，不走字面量
 *
 * 2026-10-23 起这六条在 `strings.xml`（英文）/ `values-zh`（中文）里。
 * 之前是硬编码中文，注释里当时写着"等插件页整体抽文案时，这张表跟着一起走" ——
 * 插件页现在接完 i18n 了，所以一起走了。
 *
 * 判据很直接：宿主自己按系统语言显示（非中文用户看英文），
 * 那么宿主画出来的按钮也必须跟着，否则英文用户会看到英文界面配中文按钮。
 *
 * ## 什么时候插件会覆盖它们
 *
 * 见 [ActionLabel] 的注释：同一件事在不同插件里叫法不同。
 * 插件覆盖过的走插件的字，没覆盖的走这里。
 */
object ActionLabels {

    /**
     * key → 默认文案的**资源 id**。
     *
     * ⚠ 这里存 id 而不是字符串（2026-10-23 改）。
     *
     * 原来是硬编码中文字面量，注释里也写了"整个插件页都还没做 i18n…
     * 等插件页整体抽文案时，这张表跟着一起走"。现在插件页整体接了 i18n，
     * 所以这张表也一起走 —— 英文系统上这六个按钮必须显示英文，
     * 否则英文用户会看到"Load engine"配一个中文的"播放"。
     *
     * key 全是 [ActionLabel] 里的常量。
     */
    private val DEFAULT_RES = mapOf(
        ActionLabel.ENGINE_LOAD to R.string.s_468,
        ActionLabel.ENGINE_UNLOAD to R.string.s_469,
        // 「合成」而不是「试听」：这个按钮只跑推理、不播放。
        // 叫「试听」的话用户会等着一句声音，等不到就以为坏了 ——
        // 而音频其实已经生成好，就等「播放」。
        ActionLabel.REASONING to R.string.s_470,
        ActionLabel.PLAY to R.string.s_471,
        ActionLabel.STOP to R.string.s_472,
        ActionLabel.SAVE to R.string.s_473,
    )

    /**
     * 取默认文字。认不出的 key 返回 null（调用方自己决定兜底）。
     *
     * 返回 null 而不是抛异常：新增 key 时若只改了 SDK 忘了这张表，
     * 界面应该退化成别的显示，而不是在用户手里崩掉。
     *
     * @param context 用来取本地化文案。**必须传 Activity/Application 的 context**，
     *   它会跟着系统语言走；不要传 createConfigurationContext 那种固定了语言的。
     */
    fun defaultFor(context: android.content.Context, key: String): String? =
        DEFAULT_RES[key]?.let { context.getString(it) }

    /**
     * 最终显示的文字：插件覆盖 → 默认 → 原样吐 key。
     *
     * 最后一档是调试用的兜底 —— 真走到那里说明 key 写错了，
     * 与其显示空白按钮（用户完全不知道能点），不如把 key 显示出来，
     * 截图反馈时也能一眼看出问题。
     */
    fun resolve(
        context: android.content.Context,
        key: String,
        overrides: Map<String, String>,
    ): String = overrides[key] ?: defaultFor(context, key) ?: key
}
