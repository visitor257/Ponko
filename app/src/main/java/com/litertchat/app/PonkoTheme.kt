package com.litertchat.app

import android.content.Context
import android.content.res.Configuration

/**
 * 应用配色表：浅色 / 深色两套。
 *
 * 界面里所有颜色都从这里取（MainActivity 的 C_* 与 DrawPage 的 pal），
 * 所以新增控件时不要再写死色值，否则深色模式下会留下白块。
 *
 * 主题模式存在 ponko 偏好里：0=跟随系统 1=浅色 2=深色（切换后 Activity 重建生效）。
 */
data class PonkoTheme(
    val bg: Int,             // 页面/滚动区背景
    val bgAlt: Int,          // 页面背景（略深一点的那种）
    val surface: Int,        // 卡片、顶栏、输入栏等「白面」
    val fieldBg: Int,        // 输入框、预览区等凹陷面
    val softBtn: Int,        // 次级按钮 / 缩略图底
    val softBtn2: Int,       // 次级按钮（描边款）
    val border: Int,         // 描边
    val chipBg: Int,         // 未选中的条目底
    val divider: Int,        // 卡片内细分隔线（半透明）
    val dividerLine: Int,    // 抽屉等处的实色 1px 分隔线
    val text: Int,           // 主文字
    val subText: Int,        // 次要文字
    val hint: Int,           // 输入框占位
    val primary: Int,        // 主色（按钮底 / 强调文字 / 顶栏）
    val primarySoft: Int,    // 主色的浅底（选中态）
    val onPrimary: Int,      // 主色上的文字
    val thoughtBg: Int,      // 思考过程气泡底
    val thoughtText: Int,    // 思考过程文字
    val ok: Int,
    val warn: Int,
    val err: Int,
    val idle: Int,
    val errText: Int,        // 错误正文（比 err 深一点）
    val stop: Int,           // 生成中「停止」按钮
    val mediaBg: Int,        // 图片缩略图底
    val scrim: Int,          // 抽屉遮罩
    val switchTrack: Int,    // 滑块开关：轨道底
    val switchThumb: Int,    // 滑块开关：滑块
    val statusBar: Int,      // 状态栏（顶栏同色）
    val navBar: Int,         // 导航栏
    val isDark: Boolean,
) {
    companion object {
        fun light() = PonkoTheme(
            bg = 0xFFF2F4F8.toInt(),
            bgAlt = 0xFFF5F6F8.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            fieldBg = 0xFFF2F3F5.toInt(),
            softBtn = 0xFFF3F5F9.toInt(),
            softBtn2 = 0xFFF6F7FA.toInt(),
            border = 0xFFDBE0EA.toInt(),
            chipBg = 0xFFF7F8FB.toInt(),
            divider = 0x1F000000,
            dividerLine = 0xFFEEF0F5.toInt(),
            text = 0xFF1C202A.toInt(),
            subText = 0xFF7A8394.toInt(),
            hint = 0xFFAAAAAA.toInt(),
            primary = 0xFF2F6BFF.toInt(),
            primarySoft = 0xFFEDF1FF.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            thoughtBg = 0xFFF5F7FB.toInt(),
            thoughtText = 0xFF6E7686.toInt(),
            ok = 0xFF22B26E.toInt(),
            warn = 0xFFF59E0B.toInt(),
            err = 0xFFE55252.toInt(),
            idle = 0xFF9AA4B5.toInt(),
            errText = 0xFFCC3333.toInt(),
            stop = 0xFFD9534F.toInt(),
            mediaBg = 0xFFEDEDED.toInt(),
            scrim = 0x66000000,
            switchTrack = 0xFFF1F3F7.toInt(),
            switchThumb = 0xFFFFFFFF.toInt(),
            statusBar = 0xFF2F6BFF.toInt(),
            navBar = 0xFFFFFFFF.toInt(),
            isDark = false,
        )

        fun dark() = PonkoTheme(
            bg = 0xFF15171C.toInt(),
            bgAlt = 0xFF15171C.toInt(),
            surface = 0xFF1E2127.toInt(),
            fieldBg = 0xFF262A32.toInt(),
            softBtn = 0xFF262A32.toInt(),
            softBtn2 = 0xFF24272E.toInt(),
            border = 0xFF343A45.toInt(),
            chipBg = 0xFF23262E.toInt(),
            divider = 0x2EFFFFFF,
            dividerLine = 0xFF2A2E36.toInt(),
            text = 0xFFE7E9EF.toInt(),
            subText = 0xFF99A2B1.toInt(),
            hint = 0xFF6E7683.toInt(),
            primary = 0xFF4C7DFF.toInt(),
            primarySoft = 0xFF223056.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            thoughtBg = 0xFF1B1E24.toInt(),
            thoughtText = 0xFF98A1B0.toInt(),
            ok = 0xFF35C57F.toInt(),
            warn = 0xFFF0A22E.toInt(),
            err = 0xFFFF6B6B.toInt(),
            idle = 0xFF7E8798.toInt(),
            errText = 0xFFFF8A8A.toInt(),
            stop = 0xFFD9534F.toInt(),
            mediaBg = 0xFF22252B.toInt(),
            scrim = 0x99000000.toInt(),
            switchTrack = 0xFF23262E.toInt(),
            switchThumb = 0xFF3B4351.toInt(),
            statusBar = 0xFF2F6BFF.toInt(),
            navBar = 0xFF15171C.toInt(),
            isDark = true,
        )

        /** 主题模式：0=跟随系统 1=浅色 2=深色 */
        fun resolve(ctx: Context, mode: Int): PonkoTheme = when (mode) {
            1 -> light()
            2 -> dark()
            else -> if (systemDark(ctx)) dark() else light()
        }

        fun systemDark(ctx: Context): Boolean =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
