// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 「激活」插件的工具 —— 解决 Android 的 **stopped 状态** 问题。
 *
 * ## 为什么需要它
 *
 * 新安装（或刚被「强制停止」）的包处于 stopped 状态。此时**其他应用 `bindService`
 * 到它的服务会直接返回 false**，不报错、不抛异常，日志里只有一句
 * `Unable to start service ...: not found`。
 *
 * 解除方式只有一个：**包自己的 Activity 被启动一次**。
 *
 * 实测/文档确认**无效**的做法（别浪费时间）：
 * · 发广播 —— 系统默认排除 stopped 包（`FLAG_EXCLUDE_STOPPED_PACKAGES`）
 * · `startService` —— 不会清这个标记
 * · `pm enable/disable` —— 不动这个标记
 * · 重装同一个 APK —— 不动
 *
 * 从 Android 15 起官方态度更明确：**只能由用户的直接或间接操作解除**。
 *
 * ## 所以插件必须有一个能被启动的 Activity
 *
 * 两种写法，**推荐第一种**：
 *
 * ### ① 继承 [PonkoActivatorActivity]（推荐，桌面无图标）
 *
 * ```kotlin
 * class MyPluginActivator : PonkoActivatorActivity()
 * ```
 *
 * 就这一行。基类已经处理完"立刻 finish、不画界面"。
 * Manifest 里给它挂 [ACTION_ACTIVATOR]，**不要 `CATEGORY_LAUNCHER`** ——
 * 于是桌面上看不到图标，和系统组件一样，只能在「设置 → 应用」里找到。
 *
 * ### ② 自己写 Activity，在 `onCreate` 第一行调 [consumeIfActivationOnly]
 *
 * 适合插件本来就需要一个用户可见的页面（说明页、设置入口）。
 *
 * ```kotlin
 * class MyInfoActivity : Activity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         // 第一行。返回 true 表示"只为激活而来"，已经 finish 了，不用再画界面
 *         if (PonkoActivation.consumeIfActivationOnly(this)) return
 *         setContentView(myInfoPage())
 *     }
 * }
 * ```
 *
 * 这一步带来的体验差别很大：宿主为了清标记而启动它时，会带上
 * [EXTRA_ACTIVATE_ONLY]，于是它**立刻 `finish()`**，用户只看到一闪 ——
 * 而不是被扔进一个说明页、还得自己按返回。
 *
 * ## 两种写法的选择标准
 *
 * 「这个 Activity 对用户有没有别的用处？」没有 → 用 ①，别让桌面上多一个
 * 点开就退出图标的空壳。
 *
 * ## 这是永久的吗
 *
 * 不是。**一次就够**，之后一直能连。只有三种情况会再次变成 stopped：
 * 卸载重装 / 用户在设置里「强制停止」/ 部分国产 ROM 的"清理后台"。
 */
object PonkoActivation {

    /**
     * 宿主用来发现**激活组件**的 action。
     *
     * 插件给自己的 Activator Activity 挂这个 action，宿主就能找到它 ——
     * **不需要 `CATEGORY_LAUNCHER`**，所以桌面上不会出现图标。
     *
     * 这就是「桌面无图标 + 仍能解除 stopped」能同时成立的原因：
     * 清 stopped 只要求「某个组件被启动」，不要求「这个组件有桌面入口」。
     */
    const val ACTION_ACTIVATOR = "com.litertchat.ponko.ACTIVATOR"

    /**
     * 宿主专用：标这次启动"只为清 stopped 标记"，插件应立刻 finish。
     *
     * 用带包名的完整前缀，避免和插件自己的 extra 撞名。
     */
    const val EXTRA_ACTIVATE_ONLY = "com.litertchat.ponko.extra.ACTIVATE_ONLY"

    /** 本次启动是不是"只为激活"（插件可读它决定要不要画界面） */
    fun isActivationOnly(activity: Activity): Boolean =
        activity.intent?.getBooleanExtra(EXTRA_ACTIVATE_ONLY, false) == true

    /**
     * 插件的 Activity 在 `onCreate` **第一行**调用。
     *
     * @return true = 这次只为激活，已经 `finish()`，调用方应立刻 return，
     *         不要再 `setContentView`（画了也看不到，纯浪费）
     */
    fun consumeIfActivationOnly(activity: Activity): Boolean {
        if (!isActivationOnly(activity)) return false
        activity.finish()
        return true
    }

    /**
     * 构造"激活插件"的 Intent。
     *
     * @param targetActivity 目标 Activity 的完整类名。宿主从
     *        `ACTION_ACTIVATOR` 或 `CATEGORY_LAUNCHER` 查出来的那个类名。
     *        给了就用显式 ComponentName —— 显式启动**不检查 intent-filter**，
     *        所以目标即使只声明了自定义 action 也能起来。
     *        null 时退回 `getLaunchIntentForPackage`（极老的插件）。
     */
    fun buildIntent(
        c: Context,
        pkg: String,
        targetActivity: String? = null,
    ): Intent? = try {
        val base = if (!targetActivity.isNullOrEmpty()) {
            // 只用 ACTION_MAIN + 显式类名。
            // **故意不加 CATEGORY_LAUNCHER** —— 目标若是 ACTIVATOR（无桌面入口），
            // 带上这个 category 在部分 ROM 上会匹配失败。
            Intent(Intent.ACTION_MAIN).setClassName(pkg, targetActivity)
        } else {
            c.packageManager.getLaunchIntentForPackage(pkg)
        }
        base?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.putExtra(EXTRA_ACTIVATE_ONLY, true)
    } catch (t: Throwable) {
        null
    }
}
