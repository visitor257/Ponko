package com.litertchat.app.draw

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.sqrt

/**
 * Stable Diffusion 1.5 的 ONNX 推理管线（CPU）。
 *
 * 文生图与图生图共用同一条去噪循环，区别只在起点：
 *   - txt2img：latents = 纯随机噪声，从第 0 步开始
 *   - img2img：latents = VAE 编码输入图后按 strength 加噪，从中间某步开始
 *
 * ONNX 模型按 diffusers 的标准导出命名：
 *   text_encoder : input_ids                     -> last_hidden_state (1,77,768)
 *   unet         : sample, timestep, encoder_hidden_states -> out_sample (1,4,H/8,W/8)
 *   vae_decoder  : latent_sample                 -> sample (1,3,H,W)
 *   vae_encoder  : sample                        -> latent_sample (1,4,H/8,W/8)
 */
class SdPipeline(private val models: SdModelSet) {

    companion object {
        /** SD1.5 的 VAE 缩放系数 */
        const val VAE_SCALE = 0.18215f
        const val LATENT_CH = 4
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val tokenizer = SdClipTokenizer(models.vocab, models.merges)
    private val scheduler = SdScheduler(models.schedulerConfig)

    private val textEncoder: OrtSession
    private val unet: OrtSession
    private val vaeDecoder: OrtSession
    private val vaeEncoder: OrtSession?

    init {
        val opt = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        textEncoder = env.createSession(models.textEncoder.absolutePath, opt)
        unet = env.createSession(models.unet.absolutePath, opt)
        vaeDecoder = env.createSession(models.vaeDecoder.absolutePath, opt)
        vaeEncoder = models.vaeEncoder?.let { env.createSession(it.absolutePath, opt) }
    }

    fun close() {
        runCatching { textEncoder.close() }
        runCatching { unet.close() }
        runCatching { vaeDecoder.close() }
        runCatching { vaeEncoder?.close() }
    }

    /** 推理参数 */
    data class Params(
        val prompt: String,
        val negative: String = "",
        val steps: Int = 20,
        val cfgScale: Float = 7.5f,
        val width: Int = 512,
        val height: Int = 512,
        val seed: Long = -1L,
        /** 仅图生图使用：0~1，越大改动越多 */
        val strength: Float = 0.75f,
    )

    /**
     * 文生图。
     * @param onProgress 回调 (当前步, 总步数)
     * @return RGB 像素数组（宽*高*3，0..255）
     */
    fun txt2img(p: Params, onProgress: ((Int, Int) -> Unit)? = null): ImageData {
        val lw = p.width / 8
        val lh = p.height / 8
        val seed = if (p.seed < 0) System.nanoTime() else p.seed
        val rnd = Random(seed)
        val latents = FloatArray(LATENT_CH * lw * lh) { gauss(rnd) }
        return run(p, latents, lw, lh, 0, seed, onProgress)
    }

    /**
     * 图生图。
     * @param inputRgb 输入图的 RGB 像素（会先缩放到目标尺寸）
     */
    fun img2img(p: Params, inputRgb: ImageData, onProgress: ((Int, Int) -> Unit)? = null): ImageData {
        val enc = vaeEncoder ?: throw IllegalStateException("模型缺少 vae_encoder，无法图生图")
        val lw = p.width / 8
        val lh = p.height / 8
        val seed = if (p.seed < 0) System.nanoTime() else p.seed
        val rnd = Random(seed)

        // 1) 输入图归一化到 [-1,1]，编码成 latent
        val imgInput = FloatArray(3 * p.width * p.height)
        for (i in 0 until p.width * p.height) {
            imgInput[i] = inputRgb.data[i * 3] / 127.5f - 1f
            imgInput[p.width * p.height + i] = inputRgb.data[i * 3 + 1] / 127.5f - 1f
            imgInput[2 * p.width * p.height + i] = inputRgb.data[i * 3 + 2] / 127.5f - 1f
        }
        val encoded = FloatArray(LATENT_CH * lw * lh)
        runSession(
            enc,
            mapOf("sample" to floatTensor(imgInput, longArrayOf(1, 3, p.height.toLong(), p.width.toLong())))
        ).use { res -> readFloats(res[0] as OnnxTensor, encoded) }
        for (i in encoded.indices) encoded[i] *= VAE_SCALE

        // 2) 按 strength 加噪到中间时刻
        val steps = p.steps
        val tSteps = scheduler.timesteps(steps)
        val startIdx = ((1f - p.strength) * steps).toInt().coerceIn(0, steps - 1)
        val tStart = tSteps[startIdx]
        val aStart = scheduler.alpha(tStart)
        val latents = FloatArray(encoded.size)
        for (i in encoded.indices) {
            latents[i] = (sqrt(aStart) * encoded[i] + sqrt(1.0 - aStart) * gauss(rnd)).toFloat()
        }
        return run(p, latents, lw, lh, startIdx, seed, onProgress)
    }

    /** 共用去噪循环（startIdx 之后的时间步） */
    private fun run(
        p: Params,
        latentsIn: FloatArray,
        lw: Int,
        lh: Int,
        startIdx: Int,
        seed: Long,
        onProgress: ((Int, Int) -> Unit)?,
    ): ImageData {
        // 1) 文本编码（条件 + 无条件）
        val condEmbed = textEncode(p.prompt)
        val uncondEmbed = textEncode(p.negative.ifBlank { "" })

        val steps = p.steps
        val tSteps = scheduler.timesteps(steps)
        val latents = latentsIn.copyOf()
        val latentCount = latents.size

        // CFG 用 batch=2：前一半无条件、后一半条件
        val sampleBuf = FloatArray(2 * latentCount)
        val embedBuf = FloatArray(2 * 77 * condEmbed.size / 77)   // 2 * 77 * 768

        for (si in startIdx until steps) {
            val t = tSteps[si]
            val tPrev = if (si + 1 < steps) tSteps[si + 1] else -1

            System.arraycopy(latents, 0, sampleBuf, 0, latentCount)
            System.arraycopy(latents, 0, sampleBuf, latentCount, latentCount)
            System.arraycopy(uncondEmbed, 0, embedBuf, 0, uncondEmbed.size)
            System.arraycopy(condEmbed, 0, embedBuf, uncondEmbed.size, condEmbed.size)

            val epsAll = FloatArray(2 * latentCount)
            runSession(
                unet,
                mapOf(
                    "sample" to floatTensor(sampleBuf, longArrayOf(2, LATENT_CH.toLong(), lh.toLong(), lw.toLong())),
                    "timestep" to longTensor(longArrayOf(t.toLong()), longArrayOf(1)),
                    "encoder_hidden_states" to floatTensor(embedBuf, longArrayOf(2, 77, 768)),
                )
            ).use { res -> readFloats(res[0] as OnnxTensor, epsAll) }

            // CFG 合成：eps = uncond + scale * (cond - uncond)
            val eps = FloatArray(latentCount)
            for (i in 0 until latentCount) {
                val u = epsAll[i]
                val c = epsAll[latentCount + i]
                eps[i] = u + p.cfgScale * (c - u)
            }

            val next = scheduler.step(latents, eps, t, tPrev)
            System.arraycopy(next, 0, latents, 0, latentCount)

            onProgress?.invoke(si - startIdx + 1, steps - startIdx)
        }

        // 2) VAE 解码
        val scaled = FloatArray(latentCount) { latents[it] / VAE_SCALE }
        val px = FloatArray(3 * p.width * p.height)
        runSession(
            vaeDecoder,
            mapOf("latent_sample" to floatTensor(scaled, longArrayOf(1, LATENT_CH.toLong(), lh.toLong(), lw.toLong())))
        ).use { res -> readFloats(res[0] as OnnxTensor, px) }
        val rgb = ByteArray(p.width * p.height * 3)
        val plane = p.width * p.height
        for (i in 0 until plane) {
            for (c in 0..2) {
                var v = px[c * plane + i] * 0.5f + 0.5f        // [-1,1] -> [0,1]
                v = v.coerceIn(0f, 1f)
                rgb[i * 3 + c] = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return ImageData(rgb, p.width, p.height, seed)
    }

    /** 文本编码：返回扁平化的 (1,77,768) 浮点数组 */
    private fun textEncode(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        val emb = FloatArray(77 * 768)
        runSession(
            textEncoder,
            mapOf("input_ids" to longTensor(longIds, longArrayOf(1, 77)))
        ).use { res -> readFloats(res[0] as OnnxTensor, emb) }
        return emb
    }

    // ---- ONNX 小工具 ----

    private fun runSession(s: OrtSession, inputs: Map<String, OnnxTensor>): OrtSession.Result =
        s.run(inputs)

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun readFloats(t: OnnxTensor, dst: FloatArray) {
        val fb = t.floatBuffer
        fb.rewind()
        val n = minOf(dst.size, fb.remaining())
        fb.get(dst, 0, n)
    }

    /** Box-Muller 生成标准正态随机数 */
    private fun gauss(r: Random): Float {
        var u1: Double
        do { u1 = r.nextDouble() } while (u1 <= 1e-12)
        val u2 = r.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
    }
}

/** 简单 RGB 图像数据（0..255） */
class ImageData(val data: ByteArray, val width: Int, val height: Int, val seed: Long = -1L)
