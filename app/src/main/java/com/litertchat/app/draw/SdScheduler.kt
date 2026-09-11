package com.litertchat.app.draw

import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * DDIM 采样调度器。
 *
 * SD1.5 训练时的噪声表是 1000 步，推理时我们可以用更少的步数（默认 20），
 * 所以要把 [0, steps) 映射到训练时间轴上取子序列。
 *
 * - 优先读 diffusers 导出的 scheduler/scheduler_config.json 里的 alphas_cumprod；
 * - 没有就按 SD1.5 的 scaled_linear 配置自己算（beta 0.00085 → 0.012）。
 */
class SdScheduler(schedulerConfig: File?) {

    companion object {
        const val TRAIN_STEPS = 1000      // SD1.5 训练时的扩散步数
        const val BETA_START = 0.00085
        const val BETA_END = 0.012
    }

    /** 长度为 TRAIN_STEPS 的累积 alpha 表 */
    private val alphasCumprod: DoubleArray = loadAlphas(schedulerConfig)

    private fun loadAlphas(cfg: File?): DoubleArray {
        if (cfg != null && cfg.exists()) {
            runCatching {
                val o = JSONObject(cfg.readText(Charsets.UTF_8))
                val arr = o.optJSONArray("alphas_cumprod")
                if (arr != null && arr.length() > 0) {
                    return DoubleArray(arr.length()) { arr.getDouble(it) }
                }
            }
        }
        // 回退：按 SD1.5 的 scaled_linear 噪声表计算
        val out = DoubleArray(TRAIN_STEPS)
        var acc = 1.0
        for (t in 0 until TRAIN_STEPS) {
            val f = t.toDouble() / (TRAIN_STEPS - 1)
            val beta = (sqrt(BETA_START) + f * (sqrt(BETA_END) - sqrt(BETA_START)))
            val b = beta * beta
            acc *= (1.0 - b)
            out[t] = acc
        }
        return out
    }

    /** 把推理步数映射成训练时间轴上的时间步（从大到小） */
    fun timesteps(steps: Int): IntArray =
        IntArray(steps) { i -> ((TRAIN_STEPS.toDouble() - 1) * (1.0 - i.toDouble() / steps)).toInt() }

    fun alpha(t: Int): Double = alphasCumprod[t.coerceIn(0, alphasCumprod.size - 1)]

    /**
     * 单步去噪。
     *
     * @param sample  当前 latent x_t（原地返回新数组）
     * @param eps     模型预测的噪声
     * @param t       当前时间步
     * @param tPrev   下一时间步（< 0 表示最后一步）
     * @param clipSample 是否把预测的 x0 截断到 [-1,1]（SD1.5 默认 true）
     */
    fun step(sample: FloatArray, eps: FloatArray, t: Int, tPrev: Int, clipSample: Boolean = true): FloatArray {
        val aT = alpha(t)
        val sqA = sqrt(aT)
        val sqOneMinusA = sqrt(1.0 - aT)
        val out = FloatArray(sample.size)

        if (tPrev < 0) {
            // 最后一步：直接输出预测的 x0
            for (i in sample.indices) {
                var x0: Double = (sample[i].toDouble() - sqOneMinusA * eps[i]) / sqA
                if (clipSample) x0 = x0.coerceIn(-1.0, 1.0)
                out[i] = x0.toFloat()
            }
            return out
        }

        val aPrev = alpha(tPrev)
        val sqPrev = sqrt(aPrev)
        val sqOneMinusPrev = sqrt(1.0 - aPrev)
        for (i in sample.indices) {
            var x0: Double = (sample[i].toDouble() - sqOneMinusA * eps[i]) / sqA
            if (clipSample) x0 = x0.coerceIn(-1.0, 1.0)
            // DDIM(eta=0)：确定性更新
            out[i] = (sqPrev * x0 + sqOneMinusPrev * eps[i]).toFloat()
        }
        return out
    }
}
