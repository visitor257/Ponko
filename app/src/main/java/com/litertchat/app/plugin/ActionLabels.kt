// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import com.litertchat.plugin.ActionLabel

/**
 * 宿主按钮的默认文案。
 *
 * ## 为什么这张表在宿主、不在 SDK
 *
 * 插件只需要 [ActionLabel] 里那几个 **key**（`engine.load` 之类），
 * 它不知道也不该知道宿主把这些按钮叫成什么。
 *
 * 把默认文案放进 SDK 的后果：每个插件 APK 都打包一份别的 App 的中文字符串
 * （全世界只有这个 App 会用到它们），而且以后做多语言时要改两处、
 * 还得让所有插件重编才能跟上。
 *
 * ## 为什么不直接用 strings.xml
 *
 * 这组文案目前和插件页里其它几百条文案一样是硬编码的 —— 整个插件页都还没做 i18n
 * （默认资源是英文，`values-zh` 才是中文，所以英文系统上这页本来就是中文）。
 * 等插件页整体抽文案时，这张表跟着一起走，不单独处理。
 *
 * ## 什么时候插件会覆盖它们
 *
 * 见 [ActionLabel] 的注释：同一件事在不同插件里叫法不同。
 * 插件覆盖过的走插件的字，没覆盖的走这里。
 */
object ActionLabels {

    /** key → 默认文字。key 全是 [ActionLabel] 里的常量 */
    private val DEFAULTS = mapOf(
        ActionLabel.ENGINE_LOAD to "加载引擎",
        ActionLabel.ENGINE_UNLOAD to "卸载引擎",
        // 「合成」而不是「试听」：这个按钮只跑推理、不播放。
        // 叫「试听」的话用户会等着一句声音，等不到就以为坏了 ——
        // 而音频其实已经生成好，就等「播放」。
        ActionLabel.REASONING to "合成",
        ActionLabel.PLAY to "播放",
        ActionLabel.STOP to "停止",
        ActionLabel.SAVE to "保存",
    )

    /**
     * 取默认文字。认不出的 key 返回 null（调用方自己决定兜底）。
     *
     * 返回 null 而不是抛异常：新增 key 时若只改了 SDK 忘了这张表，
     * 界面应该退化成别的显示，而不是在用户手里崩掉。
     */
    fun defaultFor(key: String): String? = DEFAULTS[key]

    /**
     * 最终显示的文字：插件覆盖 → 默认 → 原样吐 key。
     *
     * 最后一档是调试用的兜底 —— 真走到那里说明 key 写错了，
     * 与其显示空白按钮（用户完全不知道能点），不如把 key 显示出来，
     * 截图反馈时也能一眼看出问题。
     */
    fun resolve(key: String, overrides: Map<String, String>): String =
        overrides[key] ?: defaultFor(key) ?: key
}
