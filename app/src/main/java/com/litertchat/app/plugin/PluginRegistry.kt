// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import android.content.Context
import android.content.SharedPreferences

/**
 * 记住「哪些插件被用户启用了」。
 *
 * 键用**包名**而不是 `pluginId`：包名是发现阶段唯一能拿到的稳定标识，
 * 而 `pluginId` 要 bind 上之后才能问出来。两者用途不同 ——
 * 包名决定「要不要去 bind」，`pluginId` 决定「UI 上算不算同一个插件」。
 */
object PluginRegistry {

    private const val PREFS = "ponko_plugins"
    private const val KEY_ENABLED = "enabled_packages"

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户启用过的插件包名集合 */
    fun enabledPackages(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()

    fun isEnabled(c: Context, pkg: String): Boolean = pkg in enabledPackages(c)

    fun setEnabled(c: Context, pkg: String, enabled: Boolean) {
        // getStringSet 返回的集合**不能直接改**（SharedPreferences 的已知陷阱：
        // 直接改会抛 UnsupportedOperationException，或改了不落盘），必须复制一份
        val next = enabledPackages(c).toMutableSet()
        if (enabled) next.add(pkg) else next.remove(pkg)
        prefs(c).edit().putStringSet(KEY_ENABLED, next).apply()
    }

    /** 每个插件单独存「上次用的那个」之类的键，前缀避免和主偏好撞名 */
    fun putString(c: Context, pkg: String, key: String, value: String) {
        prefs(c).edit().putString("$pkg/$key", value).apply()
    }

    fun getString(c: Context, pkg: String, key: String, def: String = ""): String =
        prefs(c).getString("$pkg/$key", def) ?: def

    /** 插件被卸载后清掉它的残留键，避免偏好无限增长 */
    fun forget(c: Context, pkg: String) {
        val edits = prefs(c).edit()
        prefs(c).all.keys
            .filter { it.startsWith("$pkg/") }
            .forEach { edits.remove(it) }
        edits.apply()
        setEnabled(c, pkg, false)
    }
}
