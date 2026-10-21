// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin

/**
 * 插件里可以 throw 这个异常来表达"出了个可映射的错误"。
 *
 * 基类会把它转成 [PluginError] 回给宿主；其它异常一律归为
 * [ErrCode.PLUGIN_INTERNAL]（detail 里带栈）。
 *
 * @param message **已本地化**，宿主会直接显示给用户
 */
class PluginException(
    val code: Int,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause) {

    companion object {
        fun notReady(msg: String = "引擎尚未加载") =
            PluginException(ErrCode.NOT_READY, msg)

        fun modelInvalid(msg: String) =
            PluginException(ErrCode.MODEL_INVALID, msg)

        fun outOfMemory(msg: String = "可用内存不足，请先卸载其它模型或关闭其它应用") =
            PluginException(ErrCode.OUT_OF_MEMORY, msg, retryable = true)

        fun invalidRequest(msg: String) =
            PluginException(ErrCode.INVALID_REQUEST, msg)

        fun unsupported(msg: String) =
            PluginException(ErrCode.UNSUPPORTED, msg)
    }
}
