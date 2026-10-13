// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app

import android.content.Context

/**
 * 推理线程数 —— **全局设置**，不只管绘图。
 *
 * 原先这个值只存在绘图页（偏好键 `drawThreads`），于是「线程数」被当成了绘图独有的开关。
 * 实际上它是 CPU 推理的通用参数，三条路径都吃它：
 *   1. 绘图（stable-diffusion.cpp）—— `sd_ctx_params_t.n_threads`
 *   2. 对话 GGUF（llama.cpp）—— `ModelParameters.setThreads / setThreadsBatch`
 *   3. 打标（ONNX Runtime）—— `OrtSession.SessionOptions.setIntraOpNumThreads`
 *
 * ⚠ 例外：对话的 **`.litertlm`（Google LiteRT-LM）没有线程数参数**。
 * `EngineConfig` 的字段只有 modelPath / backend / visionBackend / audioBackend /
 * maxNumTokens / maxNumImages / cacheDir（v0.17.0 实测反编译确认），所以那一条路径
 * 不受这里控制 —— 是库的限制，不是漏接。
 *
 * 改完**下次加载模型才生效**（三条路径都是在加载时把线程数交给 native）。
 */
object InferConfig {

    private const val PREFS = "ponko"
    private const val KEY_THREADS = "inferThreads"

    /** 旧键：1.7.2 测试期误用绘图专属的名字，只读来兼容，不再写 */
    private const val KEY_LEGACY_DRAW = "drawThreads"

    /** 默认 4：多数手机的大核数。开太多会跑到小核上，反而更慢。 */
    const val DEFAULT = 4

    /**
     * 上限 = 设备**真实可用**核数，不做人为封顶（用户 2026-10-13 指定）。
     *
     * 注意 `availableProcessors()` 返回的是**当前可用**核数（会被 cpuset / 省电策略实时限制），
     * 不一定等于物理核数 —— 但对"能开几个推理线程"这个问题来说，这恰好是想要的语义：
     * 拿当前可用数当上限，才不会出现"界面允许 8、实际只有 4 核能用"的错配。
     * 下限 1：任何情况下至少给一个线程。
     */
    fun maxThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** 当前线程数（带兼容读取旧键；越界值夹回合法区间） */
    fun threads(c: Context): Int {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = when {
            p.contains(KEY_THREADS) -> p.getInt(KEY_THREADS, DEFAULT)
            p.contains(KEY_LEGACY_DRAW) -> p.getInt(KEY_LEGACY_DRAW, DEFAULT)
            else -> DEFAULT
        }
        return raw.coerceIn(1, maxThreads())
    }

    fun setThreads(c: Context, value: Int) {
        val v = value.coerceIn(1, maxThreads())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_THREADS, v)
            .remove(KEY_LEGACY_DRAW)
            .apply()
    }
}
