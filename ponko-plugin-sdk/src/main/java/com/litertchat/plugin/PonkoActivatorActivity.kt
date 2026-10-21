// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.app.Activity
import android.os.Bundle

/**
 * **无界面的激活组件。** 插件继承它并在 Manifest 里声明，就同时得到：
 *
 * - ✅ 能被宿主启动 → **解除系统的 stopped 状态**（否则宿主永远 bind 不上）
 * - ✅ 桌面**不显示图标**（只要不加 `CATEGORY_LAUNCHER`）
 * - ✅ 启动时**什么都不画**，连闪一下都没有
 *
 * ## 为什么需要它
 *
 * 新安装的包处于系统 stopped 状态，其他应用 `bindService` 到它的服务会
 * **静默返回 false**。解除方式只有一个：包自己的 Activity 被启动一次。
 * 广播、`startService`、`pm enable` 全都不行（Android 15 起官方更明确：
 * 只能由用户的直接或间接操作解除）。
 *
 * 但「必须有 Activity」不等于「必须有桌面图标」——
 * 清 stopped 只要求**某个组件被启动**，不要求那个组件对用户可见。
 * 这个类就是这个区别的具体实现。
 *
 * ## 用法（两步）
 *
 * **1. Manifest**（注意：**不要**加 `CATEGORY_LAUNCHER`）
 *
 * ```xml
 * <activity
 *     android:name=".MyActivator"
 *     android:exported="true"
 *     android:excludeFromRecents="true"
 *     android:noHistory="true"
 *     android:taskAffinity=""
 *     android:theme="@android:style/Theme.NoDisplay">
 *     <intent-filter>
 *         <action android:name="com.litertchat.ponko.ACTIVATOR" />
 *     </intent-filter>
 * </activity>
 * ```
 *
 * **2. Kotlin**
 *
 * ```kotlin
 * class MyActivator : PonkoActivatorActivity()
 * ```
 *
 * 就这两处，不用写任何界面代码。
 *
 * ## 桌面上完全看不到它
 *
 * 没有 `CATEGORY_LAUNCHER`，所以启动器不会列出它。
 * 用户要找到插件，只能在「系统设置 → 应用」里看到（和系统组件一样）。
 *
 * ## 想加个说明页怎么办
 *
 * 另开一个 Activity 并加 `CATEGORY_LAUNCHER`，但要知道：
 * **那会让桌面上出现一个图标**。如果用户要求插件"隐藏"，就别加。
 * 插件的名称、版本、能力在 Ponko 的插件列表里都能看到，不需要额外说明页。
 */
abstract class PonkoActivatorActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme.NoDisplay 的硬性要求：必须在 onResume 之前 finish，
        // 否则系统会抛 "did not call finish() prior to onResume() completing"。
        // 这里不 setContentView、不做任何事，直接关掉。
        finish()
    }

    /**
     * 不参与任何转场动画。
     *
     * 默认的启动/退出动画会让人看到"一闪"，而这一页本来就没有内容，
     * 闪一下只会像出了 bug。
     */
    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
    }
}
