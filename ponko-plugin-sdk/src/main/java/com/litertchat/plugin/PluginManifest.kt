// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 插件自述。宿主绑定成功后第一件事就调 [IPonkoPlugin.getManifest] 拿它，
 * 用来决定「要不要显示、显示成什么、挂到哪个界面」。
 *
 * 全字段都是**自述**，宿主不校验真伪（安全性靠 signature 权限在系统层兜）；
 * 只用来做 UI 呈现和能力路由。
 */
@Parcelize
data class PluginManifest(
    /**
     * 稳定唯一标识，如 `"voxcpm2"`。
     *
     * ⚠ **不要用包名** —— 包名会改（比如换品牌），一改用户就"丢了"插件。
     * 宿主用 `pluginId` 做偏好持久化，所以它必须跨版本不变。
     */
    val pluginId: String,

    /** 给人看的名字，如 `"VoxCPM2 语音合成"` */
    val displayName: String,

    /** 插件自己的版本（与宿主 API 版本无关） */
    val versionName: String,

    val author: String,

    /** 能力列表，见 [Capability]。**未声明能力 = 不显示** */
    val capabilities: List<String>,

    /** 支持哪些模型家族，如 `["voxcpm2"]`；仅用于 UI 提示 */
    val modelFamilies: List<String>,

    /**
     * `true` = 需要宿主通过 [ModelBundle] 把模型文件传进来（用宿主已导入的模型）；
     * `false` = 插件自己下载/导入模型到自己私有目录。
     *
     * **v1 一律 `false`**（插件自管）。字段留着是为了 v2 宿主共享模型时不用改 AIDL。
     */
    val needsHostModels: Boolean,

    /** 给人看的机型建议，如 `"建议 8GB 内存"`，可为空 */
    val minSdkHint: String? = null,
) : Parcelable
