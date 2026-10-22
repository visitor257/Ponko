// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 插件设置页的**整页数据**。宿主拿它渲染出界面，插件自己不画 UI。
 *
 * ## 为什么是「宿主渲染」
 *
 * 插件**读不到宿主的 `PonkoTheme`**（它在 `app` module，不在 SDK 里）。插件要自己画，
 * 就得重写一套配色，而且 Ponko 的浅色/深色两套都得各写一遍，主题切换还跟不上。
 * 所以插件只提供一份「字段清单」，样式由宿主决定。
 *
 * ## 值归插件管
 *
 * 宿主**不持久化**任何设置值。每次要看设置就调 `getSettingsSchema()` 拿最新状态。
 * 插件最清楚自己的默认值、模型是否就绪、哪些项互斥 —— 宿主存一份必然出现
 * 「宿主以为是这样、插件实际是那样」，这类 bug 极难查。
 *
 * @param intro 整页顶部的说明段落，可为空
 * @param items 按顺序渲染的设置项；**空列表是合法的**（表示"这个插件没有可配置项"），
 *              宿主应显示成空页面而不是报错
 */
@Parcelize
data class UiSchema(
    val intro: String = "",
    val items: List<UiItem> = emptyList(),
) : Parcelable {

    /** 没有可渲染的项 —— 宿主据此显示"该插件没有界面" */
    val isEmpty: Boolean get() = intro.isBlank() && items.isEmpty()
}

/**
 * 一个设置项。宿主按 [type] 决定用什么控件画。
 *
 * @param key 稳定键。插件用它存值；宿主只原样带回，**不解释内容**
 * @param type 见 [UiType]
 * @param label 主文字：开关/滑块的标题、「按钮的文案」、`INFO` 的正文
 * @param help 次要说明，显示在控件下方，可为空
 * @param value 当前值（**字符串化**）：开关 `"true"`/`"false"`，滑块 `"2.0"`，
 *              单选是选中的 `UiOption.value`，输入框是原文
 * @param min 仅 [UiType.SLIDER]
 * @param max 仅 [UiType.SLIDER]
 * @param step 仅 [UiType.SLIDER]
 * @param options 仅 [UiType.CHOICE]
 * @param enabled `false` = 灰显且不可点。**不要隐藏** —— 用户要能看到「为什么这个不能点」
 * @param primary `true` = 视觉强调（如「下载模型」这种关键动作）
 */
@Parcelize
data class UiItem(
    val key: String = "",
    val type: Int,
    val label: String = "",
    val help: String = "",
    val value: String = "",
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 0.1,
    val options: List<UiOption> = emptyList(),
    val enabled: Boolean = true,
    val primary: Boolean = false,
) : Parcelable

/** [UiType.CHOICE] 的一个选项 */
@Parcelize
data class UiOption(
    val value: String,
    val label: String,
) : Parcelable

/**
 * 控件类型。
 *
 * ## 加一种类型 = 主 app 必须更新
 *
 * 这一点必须提前讲清楚：**控件是宿主画的，所以宿主不认识一个新类型，就画不出来。**
 * 插件单方面用了新类型，老宿主只能降级成纯文本（不会崩，但那个控件没了）。
 *
 * 所以这个集合要**一次放够**。判断标准是：
 * 「一个配置型插件，在不能写代码的前提下，最可能需要哪些控件？」
 *
 * ## 为什么是这 12 种
 *
 * 前 7 种覆盖「配置项」本身（说明、分组、布尔、数值、单选、输入、按钮）。
 * 后 5 种（[PROGRESS] / [STATUS] / [TEXTAREA] / [GROUP_START] / [GROUP_END]）
 * 是 2026-10-21 补的，全部来自「做 VoxCPM2 时一定会撞上」的四个具体场景：
 *
 * | 场景 | 缺了会怎样 |
 * |---|---|
 * | 下载 2.8GB 模型要看进度 | 没有 [PROGRESS]，用户盯着不动的界面以为卡死 |
 * | 「模型：已加载 2.8GB」这种只读信息 | 用 [INFO] 也能写，但和正文混在一起看不出来 |
 * | 音色描述要写一段话 | [TEXT] 是单行，长文本根本没法输入 |
 * | 参数十来个、一屏铺满 | 没有折叠，用户要滚很久才能看到下面 |
 *
 * ## v4 又加了 3 种（2026-10-22）
 *
 * | 场景 | 缺了会怎样 |
 * |---|---|
 * | 模型加载/推理过程想给用户看 | 没有 [LOG]，只能塞 `debugInfo`，用户要开 adb 才看得到 |
 * | 图像生成、修图要显示结果 | 没有 [IMAGE]，插件根本送不出图 |
 * | 合成完想试听、想拖到某一句 | 没有 [AUDIO]，只有宿主自己的「播放」按钮，不能 seek |
 *
 * 这三种和前 12 种有一个本质区别：**它们不是"配置项"，是"过程和产物"**。
 * 所以它们不参与 `setSetting`（用户不能改它们的值），
 * 内容靠 [IPonkoCallback.onUiPatch] / [IPonkoCallback.onImage] 单独送。
 *
 * ## 仍然刻意不做的
 *
 * 嵌套布局（一行放两个控件）、表格、自定义绘制（波形预览）、
 * **多图网格**（一次显示结果 A/B 两组对比）。
 * 这些要么让渲染代码爆炸，要么必须由插件自己画。
 * 多图确实有需求，但正确的形态是先想清楚"对比"这个交互（哪张是原图、
 * 点一下能不能互换），而不是先开放一个 `n 列` 的格子。
 *
 * ## 插件能不能自己布控件位置
 *
 * **现在不能。** 插件只决定「有哪些项、什么顺序」（`items` 是一个有序列表），
 * 每一项**纵向排列**，位置由宿主决定。
 *
 * 这是刻意的取舍，不是没做完：
 * · 布局代码一旦放开，插件就得自己处理深浅色、字号缩放、横竖屏、RTL ——
 *   而它读不到 `PonkoTheme`，两套主题各写一遍，主题切换还跟不上
 * · 有序列表已经能表达"哪个在上、哪个在一起"，缺的只是**并列**和**分栏**
 *
 * 如果确实需要并排（比如「语速」和「音高」两个滑块放一行），
 * 正确的做法是**在宿主侧加一种布局容器**（`ROW_START` / `ROW_END`，类似
 * 已有的 `GROUP_START` / `GROUP_END`），而不是把布局能力交给插件。
 * 代价是宿主多一份渲染代码，好处是所有插件自动拿到同一套主题适配。
 *
 * > 注：之前这里写过"走 `advancedActivity` 逃生口"，**那个东西从来没有实现过** ——
 * > 字段和宿主代码都不存在，只是文档里的一句臆想。已删掉，别照着它设计。
 */
object UiType {
    /** 纯文本段落（[UiItem.label] 即正文） */
    const val INFO = 0
    /** 分组标题 */
    const val SECTION = 1
    /** 开关。value 是 "true"/"false" */
    const val SWITCH = 2
    /** 滑块。用 min/max/step，value 是数字字符串 */
    const val SLIDER = 3
    /** 单选。使用 options + value */
    const val CHOICE = 4
    /** 单行文本输入 */
    const val TEXT = 5
    /**
     * 按钮。点了走 `invokeAction(key)`。
     *
     * 注意：按钮**没有值**，插件在 `invokeAction` 里做完事后调
     * `onSchemaChanged()` 让宿主重画（比如"下载模型"完成后把按钮变成"已下载"）。
     */
    const val ACTION = 6

    // ==================== v2 补充 ====================

    /**
     * 只读进度条。
     *
     * [UiItem.value] 是**百分比 0–100 的字符串**（如 `"37.5"`）。
     * 不要用 min/max 表达真实数值范围 —— 那些留给 [SLIDER]，
     * 真实范围写在 [UiItem.help] 里（如 `"1.05 / 2.83 GB"`）。
     *
     * 用途：模型下载、长任务进度。**这个控件是只读的**，用户点不动它。
     */
    const val PROGRESS = 7

    /**
     * 只读状态行：左边 [UiItem.label]，右边 [UiItem.value]，灰底。
     *
     * 用于「模型：已加载」「设备：8 核 / 可用 3.2GB」这类**只需要看**的信息。
     * 和 [INFO] 的区别：状态行是「键 → 值」的对齐结构，一眼能扫；
     * [INFO] 是一段话，适合解释。
     */
    const val STATUS = 8

    /**
     * 多行文本输入。用法同 [TEXT]，但可以换行。
     *
     * 用于音色描述、提示词、歌词这类长文本。宿主显示成 3–4 行高、
     * 高度随内容增长的输入框。
     */
    const val TEXTAREA = 9

    /**
     * 可折叠分组的**开始**。[UiItem.label] 是分组标题，
     * [UiItem.key] 用来记住折叠状态（重启 App 后不忘）。
     *
     * 和 [GROUP_END] 成对使用，**不支持嵌套**（嵌套会让渲染和缩进都变复杂，
     * 而实际配置页很少需要超过一层）。
     *
     * 分组默认**展开** —— 用户点进来是要看内容的，默认收起等于藏起来。
     */
    const val GROUP_START = 10

    /** 可折叠分组的**结束**。没有其他字段。 */
    const val GROUP_END = 11

    // ==================== v4 补充：看得见的过程与产物 ====================

    /**
     * **只读日志框**（v4）。固定高度、自己内部滚动、可长按复制。
     *
     * 用途：模型加载日志、推理过程、原生库的 stderr —— 那些"用户不一定要看，
     * 但出了问题必须能拿出来"的内容。
     *
     * ## 字段用法
     *
     * | 字段 | 含义 |
     * |---|---|
     * | [UiItem.label] | 标题，空 = 不画标题行 |
     * | [UiItem.value] | 初始内容（可多行）。**只用于首次/整页刷新** |
     * | [UiItem.help] | 底部说明，空 = 不显示 |
     *
     * ## 怎么追加（不要靠刷新 schema）
     *
     * 用 [IPonkoCallback.onUiPatch]，`append = true`：
     * 宿主只往那一个 TextView 末尾加字，**不重建整页** ——
     * 滚动位置、输入框焦点、正在编辑的 [TEXTAREA] 都不会被打断。
     *
     * 走 `onSchemaChanged()` 全量重画的话，每来一行日志整页都要重建一次：
     * 用户滚到一半被拉回顶部，输入框失焦，很卡。
     *
     * ⚠ 宿主内部按 [`UiItem.key`] 找控件，所以**同一页里的 key 必须唯一**。
     * key 空的话也能显示，但无法被 [IPonkoCallback.onUiPatch] 更新。
     */
    const val LOG = 12

    /**
     * **结果图**（v4）。等宽、等比、可点击放大。
     *
     * 图片来源是 [IPonkoCallback.onImage]，**不是** [UiItem.value] ——
     * 图片走文件描述符，不挤 Binder 那 1MB 配额（和音频同样的理由）。
     *
     * ## 字段用法
     *
     * | 字段 | 含义 |
     * |---|---|
     * | [UiItem.label] | 标题，空 = 不画 |
     * | [UiItem.value] | **还没图时**显示的占位文字，如「点上面的按钮生成图片」 |
     * | [UiItem.help] | 底部说明 |
     *
     * 收到图之前显示占位文字，收到后替换成图片。再收到一张就换掉旧的
     * （只保留最新一张 —— 多图排列是另一种控件，见 [UiType] 的说明）。
     */
    const val IMAGE = 13

    /**
     * **音频播放条**（v4）：播放/暂停 + 可拖动进度 + 「0:03 / 0:12」。
     *
     * ## 播的是哪段音频
     *
     * **宿主持有的那份产物**，也就是最近一次由宿主发起的合成结果
     * （用户点「合成」「试听」拿到的那个文件）。插件不传路径、也传不了 ——
     * 播放器是宿主的，插件只是要求"在这一页放一个播放器"。
     *
     * · 还没有任何产物时 → 显示"还没有可播放的音频"，按钮灰掉
     * · 产物换了一份 → 进度条自动归零
     *
     * ## 为什么不做成插件自己播
     *
     * 插件在**另一个进程**里。它自己播的话，退出这个页面声音还在响，
     * 用户找不到地方停 —— 宿主看不见也管不了别人的 MediaPlayer。
     * 而且"播放"这件事宿主已经有实现（音频焦点、来电打断），不该有第二份。
     *
     * ## 字段用法
     *
     * | 字段 | 含义 |
     * |---|---|
     * | [UiItem.label] | 标题，空 = 不画 |
     * | [UiItem.help] | 说明，空 = 不显示 |
     */
    const val AUDIO = 14

    /**
     * 宿主认识的**最大**类型编号。
     *
     * 给宿主做兜底用：`type > MAX_KNOWN` 说明插件用了更新的控件。
     * 也给插件作者参考 —— 你用的类型超过这个值，就得要求用户更新 Ponko。
     */
    const val MAX_KNOWN = 14
}
