// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 宿主把自己已导入的模型交给插件。
 *
 * **v1 传 `null`** —— 插件自管模型（见设计文档第四节）。接口先留好，
 * v2 需要共享时从 `null` 变成有值，**不用改 AIDL**。
 */
@Parcelize
data class ModelBundle(
    /** 模型家族，如 `"voxcpm2"`，对应 [PluginManifest.modelFamilies] */
    val family: String,
    val entries: List<ModelEntry>,
) : Parcelable

/**
 * 一个模型文件。
 *
 * ⚠ **FD 的所有权**：跨 Binder 传过来的 FD 是 dup 过的，两边各持一份。
 * 插件**读完自己 close，绝不能关宿主的**（关错会连累对面）。宿主同理。
 */
@Parcelize
data class ModelEntry(
    /** 角色，见 [PluginConst.ROLE_MAIN] / `ROLE_BASE_LM` 等 */
    val role: String,

    /** 只读文件描述符 */
    val fd: ParcelFileDescriptor,

    val sizeBytes: Long,

    /** 仅用于日志，不要拿它去拼路径 */
    val displayName: String,
) : Parcelable
