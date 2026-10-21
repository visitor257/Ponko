// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import android.content.Context
import android.net.Uri
import com.litertchat.plugin.PluginConst

/**
 * 一次试听的参数。
 *
 * ## 为什么这些归宿主，而不归插件
 *
 * 插件界面（`UiSchema`）里放的是**插件自己的配置** —— VoxCPM2 的模型路径、
 * 采样参数之类，插件重启后必须还在。
 *
 * 而这里的字段是「**这一次调用**怎么发」：用什么文本、哪个模式、哪段参考音频。
 * 它们是宿主发起 `synthesize` 时的入参，插件只是被动接受，
 * 不该、也不需要知道"用户上次试听用了哪句话"。
 *
 * 这也解释了为什么它能在宿主侧持久化（`SharedPreferences`）——
 * 而 `UiSchema` 的值我们坚持不存（见 [com.litertchat.app.plugin.PluginUiPage] 的说明）。
 * 两者是不同性质的数据，不要混。
 *
 * ## 覆盖 [com.litertchat.plugin.SynthesisRequest] 的哪些字段
 *
 * | 本类字段 | SynthesisRequest |
 * |---|---|
 * | [text] | `text` |
 * | [mode] | `mode`（0/1/2 原样） |
 * | [refUri] | 打开成 FD 后填 `referenceAudio` |
 * | [transcript] | `referenceTranscript` |
 * | [seed] | `seed`（0 = 随机） |
 *
 * `cfg` / `timesteps` / `maxSteps` 暂不暴露：它们的合理范围强依赖具体模型
 * （VoxCPM2 是 cfg≈2.0 / steps 10-20），宿主猜不出默认值。插件应把
 * `<=0 / <0` 当作"用我的默认"，等插件自己在界面里暴露更合适。
 */
data class PreviewParams(
    /** 要合成/试听的文本 */
    val text: String = DEFAULT_TEXT,

    /** 见 [PluginConst.MODE_PLAIN] / `MODE_CLONE` / `MODE_CONTINUATION` */
    val mode: Int = PluginConst.MODE_PLAIN,

    /** 极致克隆模式的逐字稿（参考音频里说了什么） */
    val transcript: String = "",

    /** 随机种子；0 = 随机。固定它才能复现同一段"演绎版本" */
    val seed: Int = 0,

    /** 推理线程数；0 = 插件自定。改了下次加载引擎生效 */
    val threads: Int = 0,

    /** 参考音频的 SAF Uri（字符串形式，便于持久化）；空 = 没选 */
    val refUri: String = "",

    /** 参考音频的显示名，只用于界面文字 */
    val refName: String = "",
) {
    val hasReference: Boolean get() = refUri.isNotEmpty()

    /** 这个模式需要参考音频 */
    val needsReference: Boolean
        get() = mode == PluginConst.MODE_CLONE || mode == PluginConst.MODE_CONTINUATION

    /** 这个模式必须给逐字稿 */
    val needsTranscript: Boolean
        get() = mode == PluginConst.MODE_CONTINUATION

    fun refUriOrNull(): Uri? = refUri.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    companion object {
        /** 默认试听文本 */
        const val DEFAULT_TEXT = "你好，我是 Ponko。这是一段试听音频。"

        /**
         * 试听文本**没有长度上限**。
         *
         * ## 为什么不设
         *
         * 想清楚一件事：**UI 归 UI，推理归推理。**
         *
         * 用户愿意合成多长的文本、要等多久，是他自己的判断。宿主既不知道
         * 插件的 RTF，也不该替插件决定"多长算太长" —— 那是拿推理侧的性能问题
         * 去限制 UI，位置就错了。真要限制，也是插件在自己界面里提示，或者
         * 插件返回 `INVALID_REQUEST` 并说明原因，宿主照实转达。
         *
         * 而且这个值是**宿主侧全局**的：一旦设死，所有插件都被同样拘束。
         * 轻量模型（Piper / Kokoro 之类）40 字可能 1 秒就跑完，
         * 对它们来说那个上限纯属添乱。
         *
         * 现在的做法是把耗时信息**告诉**用户（输入框下方一行实时估算），
         * 让用户自己决定要不要缩短。不拦、不截断。
         */

        /**
         * 线程数选项：0 = 自动
         *
         * ⚠ 注意这还是宿主在替插件选。合理选项取决于插件用什么运行时
         * （CPU 推理、GPU delegate、固定单线程的 native 库……）。
         * 真正通用的是让插件在 `UiSchema` 里自己给一个 SLIDER / CHOICE，
         * 宿主只负责渲染 —— 见 `PluginUiPage` 里对这件事的说明。
         */
        val THREAD_CHOICES = listOf(0, 2, 4, 6)

        private const val PREF = "ponko_plugin_preview"

        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        /** 可用核数减一，夹在 1..6 —— 全占满会把系统 UI 饿死 */
        fun defaultThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)

        fun load(ctx: Context, pkg: String): PreviewParams {
            val g = prefs(ctx)
            return PreviewParams(
                text = g.getString("$pkg.text", DEFAULT_TEXT) ?: DEFAULT_TEXT,
                mode = g.getInt("$pkg.mode", PluginConst.MODE_PLAIN),
                transcript = g.getString("$pkg.transcript", "") ?: "",
                seed = g.getInt("$pkg.seed", 0),
                threads = g.getInt("$pkg.threads", defaultThreads()),
                refUri = g.getString("$pkg.refUri", "") ?: "",
                refName = g.getString("$pkg.refName", "") ?: "",
            )
        }

        fun save(ctx: Context, pkg: String, p: PreviewParams) {
            prefs(ctx).edit()
                .putString("$pkg.text", p.text)
                .putInt("$pkg.mode", p.mode)
                .putString("$pkg.transcript", p.transcript)
                .putInt("$pkg.seed", p.seed)
                .putInt("$pkg.threads", p.threads)
                .putString("$pkg.refUri", p.refUri)
                .putString("$pkg.refName", p.refName)
                .apply()
        }
    }
}
