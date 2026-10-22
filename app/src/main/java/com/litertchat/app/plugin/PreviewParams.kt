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
    /** 要合成/试听的文本。**默认空** —— 见 [TEXT_NONE] 的说明 */
    val text: String = TEXT_NONE,

    /** 见 [PluginConst.MODE_PLAIN] / `MODE_CLONE` / `MODE_CONTINUATION` */
    val mode: Int = PluginConst.MODE_PLAIN,

    /** 极致克隆模式的逐字稿（参考音频里说了什么） */
    val transcript: String = "",

    /** 随机种子；0 = 随机。固定它才能复现同一段"演绎版本" */
    val seed: Int = 0,

    /** 推理线程数；0 = 插件自定。改了下次加载引擎生效 */
    val threads: Int = 0,

    /**
     * 是否希望插件用 GPU/NPU 后端。改了**下次加载引擎才生效**。
     *
     * ## 为什么是真的传下去，而不是只做个开关
     *
     * 这个值一路传到 `EngineConfig.useGpu`。之前那条链路断了一截：
     * `PluginSession` 调 `loadEngine` 时只传了 `threads`，`useGpu` 永远走默认
     * `false` —— 用户在外面切了 GPU，插件收到的仍是 CPU。
     *
     * ## 插件不支持怎么办
     *
     * 看 manifest 的 `capabilities` 里有没有 `Capability.GPU`（`"gpu.inference"`）：
     * · 有 → 才显示这个开关
     * · 没有 → 不显示，并说明"该插件没有声明 GPU 推理能力"
     *
     * **不能只画开关不管真假** —— 用户切到 GPU、插件悄悄用 CPU，
     * 那个界面就是在撒谎，比没有这个开关更糟。
     */
    val useGpu: Boolean = false,

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
        /**
         * 文本的初始值：**空**。
         *
         * ## 为什么是空，而不是一句示例话
         *
         * 这里曾经是「你好，我是 Ponko。这是一段试听音频。」—— 想的是"开箱
         * 点一下就能听到声音"。实际效果是反的：输入框里永远躺着一句现成的话，
         * 用户以为要合成的内容是**写死的**，而不是自己填的。
         *
         * 空着 + 占位提示（「要合成的内容」）反而更清楚：这是个输入框，等你填。
         * 点合成时文本为空会被明确拦下（"合成文本不能为空"），不会静默用一句
         * 别人的话生成一段音频。
         */
        const val TEXT_NONE = ""

        /**
         * 旧版本的默认示例文本。
         *
         * 只用于**迁移**：老用户 prefs 里可能存着这句（他进过插件页、没改过文本，
         * `closePluginUi` 就把默认值存下去了）。读出来时把它当成空 ——
         * 否则改了这个常量对老用户毫无作用，他照样一进去就看到"固定文字"。
         *
         * 判断依据是"和旧默认值逐字符相同"，所以真需要合成这句话的人
         * 手动输入一遍即可，代价可以忽略。
         */
        private const val LEGACY_DEFAULT_TEXT = "你好，我是 Ponko。这是一段试听音频。"

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
                // 老用户 prefs 里可能存着旧的示例句（进过插件页、没改文本就被
                // closePluginUi 存下去了）→ 读成空，否则改成空默认值对他是无效的
                text = (g.getString("$pkg.text", TEXT_NONE) ?: TEXT_NONE)
                    .let { if (it == LEGACY_DEFAULT_TEXT) TEXT_NONE else it },
                mode = g.getInt("$pkg.mode", PluginConst.MODE_PLAIN),
                transcript = g.getString("$pkg.transcript", "") ?: "",
                seed = g.getInt("$pkg.seed", 0),
                threads = g.getInt("$pkg.threads", defaultThreads()),
                useGpu = g.getBoolean("$pkg.useGpu", false),
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
                .putBoolean("$pkg.useGpu", p.useGpu)
                .putString("$pkg.refUri", p.refUri)
                .putString("$pkg.refName", p.refName)
                .apply()
        }
    }
}
