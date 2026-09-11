package com.litertchat.app.draw

import java.io.File

/**
 * Stable Diffusion ONNX 模型文件集。
 *
 * 兼容两种布局：
 *   A) diffusers 导出的目录树   <root>/text_encoder/model.onnx、unet/model.onnx、vae_decoder/model.onnx ...
 *   B) 扁平目录                 <root>/ 下直接放 text_encoder.onnx / unet.onnx / vae_decoder.onnx ...
 *
 * img2img 需要 vae_encoder，缺失时只有文生图可用。
 */
class SdModelSet(
    val dir: File,
    val textEncoder: File,
    val unet: File,
    val vaeDecoder: File,
    val vaeEncoder: File?,
    val vocab: File,
    val merges: File,
    val schedulerConfig: File?,
) {
    val canImg2Img: Boolean get() = vaeEncoder != null

    /** 简单校验：核心文件必须存在且有体积 */
    fun validate(): String? {
        val core = listOf(textEncoder, unet, vaeDecoder, vocab, merges)
        for (f in core) {
            if (!f.exists()) return "缺少文件：${f.name}"
            if (f.length() < 1024) return "文件异常（过小）：${f.name}"
        }
        return null
    }

    companion object {
        private val CORE_DIRS = listOf("text_encoder", "unet", "vae_decoder")

        /** 在 root 下（递归、最多 3 层）寻找一个可用的模型目录 */
        fun find(root: File, depth: Int = 0): SdModelSet? {
            parse(root)?.let { return it }
            if (depth >= 3) return null
            val subs = root.listFiles { f -> f.isDirectory } ?: return null
            for (d in subs) parse(d)?.let { return it }
            for (d in subs) find(d, depth + 1)?.let { return it }
            return null
        }

        /** 判断某个目录是否是模型根目录 */
        fun isModelDir(root: File): Boolean = parse(root) != null

        private fun parse(root: File): SdModelSet? {
            // A) 标准目录树
            val hasTree = CORE_DIRS.all { File(root, "$it/model.onnx").exists() }
            if (hasTree) {
                return SdModelSet(
                    dir = root,
                    textEncoder = File(root, "text_encoder/model.onnx"),
                    unet = File(root, "unet/model.onnx"),
                    vaeDecoder = File(root, "vae_decoder/model.onnx"),
                    vaeEncoder = File(root, "vae_encoder/model.onnx").takeIf { it.exists() },
                    vocab = firstExisting(root, "tokenizer/vocab.json", "vocab.json") ?: return null,
                    merges = firstExisting(root, "tokenizer/merges.txt", "merges.txt") ?: return null,
                    schedulerConfig = firstExisting(
                        root,
                        "scheduler/scheduler_config.json",
                        "scheduler_config.json"
                    ),
                )
            }
            // B) 扁平布局
            val te = firstExisting(root, "text_encoder.onnx")
            val un = firstExisting(root, "unet.onnx")
            val vd = firstExisting(root, "vae_decoder.onnx")
            if (te != null && un != null && vd != null) {
                return SdModelSet(
                    dir = root,
                    textEncoder = te,
                    unet = un,
                    vaeDecoder = vd,
                    vaeEncoder = firstExisting(root, "vae_encoder.onnx"),
                    vocab = firstExisting(root, "vocab.json", "tokenizer/vocab.json") ?: return null,
                    merges = firstExisting(root, "merges.txt", "tokenizer/merges.txt") ?: return null,
                    schedulerConfig = firstExisting(root, "scheduler_config.json"),
                )
            }
            return null
        }

        private fun firstExisting(root: File, vararg rel: String): File? {
            for (r in rel) {
                val f = File(root, r)
                if (f.exists()) return f
            }
            return null
        }
    }
}
