package com.litertchat.app.draw

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MNN 版文生图 / 图生图引擎。
 *
 * 走 MNN 官方的 stable diffusion C++ 引擎（transformers/diffusion/engine），
 * 模型是 taobao-mnn 系列的 .mnn + .mnn.weight 格式。
 *
 * 与 sd.cpp（llmedge）并存：两者互不干扰，用户按模型格式选引擎。
 */
object MnnSdEngine {

    private const val TAG = "MnnSdEngine"

    /** 模型类型：对应 C++ 的 DiffusionModelType */
    const val MODEL_SD15 = 0
    const val MODEL_TAIYI_CN = 1

    /** 后端：对应 MNNForwardType */
    const val BACKEND_CPU = 0
    const val BACKEND_OPENCL = 3
    const val BACKEND_VULKAN = 7

    /** 内存模式：0 省内存 / 1 够内存(快) / 2 平衡 */
    const val MEM_LOW = 0
    const val MEM_HIGH = 1
    const val MEM_BALANCED = 2

    private var libLoaded = false
    private var loadError: String? = null

    /** 尝试加载 native 库；返回 null 表示成功，否则返回错误信息。 */
    @Synchronized
    fun ensureLoaded(): String? {
        if (libLoaded) return null
        loadError?.let { return it }
        return try {
            System.loadLibrary("mnn_sd")
            nativeInit()
            libLoaded = true
            Log.i(TAG, "libmnn_sd.so loaded")
            null
        } catch (t: Throwable) {
            val msg = "MNN native 库加载失败：${t.message}"
            loadError = msg
            Log.e(TAG, msg, t)
            msg
        }
    }

    // ---------------------------------------------------------------- 模型检查

    data class ModelInfo(val ok: Boolean, val desc: String, val files: List<File>)

    /**
     * 检查目录是否是完整的 MNN SD 模型集。
     * 需要：unet / text_encoder / vae_decoder（各自 .mnn 与 .mnn.weight），
     * 以及 vocab.json / merges.txt
     */
    fun inspect(dir: File): ModelInfo {
        if (!dir.isDirectory) return ModelInfo(false, "不是目录", emptyList())
        val files = dir.listFiles()?.filter { it.isFile } ?: return ModelInfo(false, "无法读取目录", emptyList())
        val names = files.map { it.name.lowercase() }
        val missing = mutableListOf<String>()

        fun has(prefix: String): Boolean = names.any { it.startsWith(prefix) }
        if (!has("unet")) missing.add("unet.mnn")
        if (!has("text_encoder")) missing.add("text_encoder.mnn")
        if (!has("vae_decoder")) missing.add("vae_decoder.mnn")
        if (!names.contains("vocab.json")) missing.add("vocab.json")
        if (!names.contains("merges.txt")) missing.add("merges.txt")

        val ok = missing.isEmpty()
        val desc = if (ok) {
            val total = files.sumOf { it.length() } / 1024 / 1024
            "MNN 模型（${total}MB）"
        } else {
            "缺少：" + missing.joinToString("、")
        }
        return ModelInfo(ok, desc, files)
    }

    // ---------------------------------------------------------------- 会话

    /**
     * 创建一个已加载的会话。失败抛异常（带可读信息）。
     * 注意：load 很慢（首次数十秒），调用方需在 IO 线程。
     */
    suspend fun create(dir: File, backend: Int = BACKEND_CPU, memoryMode: Int = MEM_LOW): MnnSdSession =
        withContext(Dispatchers.IO) {
            ensureLoaded()?.let { throw IllegalStateException(it) }
            val info = inspect(dir)
            if (!info.ok) throw IllegalStateException("模型不完整：${info.desc}")

            val handle = nativeCreate(dir.absolutePath, MODEL_SD15, backend, memoryMode)
            if (handle == 0L) throw IllegalStateException("创建 MNN 引擎失败（模型文件可能不兼容）")

            val loaded = try {
                nativeLoad(handle)
            } catch (t: Throwable) {
                nativeDestroy(handle)
                throw t
            }
            if (!loaded) {
                nativeDestroy(handle)
                throw IllegalStateException("模型加载失败（内存不足或文件损坏）")
            }
            MnnSdSession(handle)
        }

    // ---------------------------------------------------------------- native
    // 不用 private：同包的 MnnSdSession 需要调用

    @JvmStatic external fun nativeInit(): Int
    @JvmStatic external fun nativeCreate(path: String, modelType: Int, backend: Int, memoryMode: Int): Long
    @JvmStatic external fun nativeLoad(handle: Long): Boolean
    @JvmStatic external fun nativeRun(
        handle: Long, prompt: String, mode: String, inputImage: String?,
        outputPath: String, width: Int, height: Int, steps: Int, seed: Int,
        cfgScale: Float, progress: ((Int) -> Unit)?
    ): Boolean
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
}

/** 一个已加载的 MNN SD 会话。用 use{} 或手动 close() 释放。 */
class MnnSdSession internal constructor(private val handle: Long) : AutoCloseable {

    @Volatile private var closed = false

    /** 文生图 / 图生图。返回输出文件；失败抛异常。 */
    suspend fun generate(
        prompt: String,
        negative: String = "",
        output: File,
        width: Int = 512,
        height: Int = 512,
        steps: Int = 20,
        seed: Int = -1,
        cfgScale: Float = 7.5f,
        inputImage: File? = null,
        onProgress: ((Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        check(!closed) { "会话已关闭" }
        val mode = if (inputImage != null) "img2img" else "text2img"
        val ok = MnnSdEngine.nativeRun(
            handle, prompt, mode, inputImage?.absolutePath, output.absolutePath,
            width, height, steps, seed, cfgScale, onProgress
        )
        if (!ok) throw IllegalStateException("生成失败（引擎返回 false）")
        output
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { MnnSdEngine.nativeCancel(handle) }
        MnnSdEngine.nativeDestroy(handle)
    }

    /** 请求中断当前生成（可在另一线程调用）。已在采样循环中的话，会在下一步生效。 */
    fun cancel() {
        if (closed) return
        runCatching { MnnSdEngine.nativeCancel(handle) }
    }
}
