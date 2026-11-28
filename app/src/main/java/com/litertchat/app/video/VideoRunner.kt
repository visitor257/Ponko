// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.video

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.litertchat.app.InferConfig
import com.litertchat.app.R
import com.litertchat.app.draw.SdCppEngine
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宿主侧的视频生成服务。
 *
 * 为什么不是"视频页里的一段代码"：视频的入口是 **JSON 插件**（一份只描述界面的数据文件），
 * 页面自己不会推理。所以「加载哪个模型、怎么跑、跑到哪一步、怎么取消」必须提出来变成一个
 * 和 UI 无关的服务 —— JSON 页只是它的第一个调用者。以后内置视频页、API 也都接这里。
 *
 * ## 和绘图页的关系
 *
 * 两条管道各自持有自己的 sd.cpp context：绘图页的 handle 在 `DrawPage`/`DrawCore` 里，
 * 这里另开一个。**不能共用**：draw 的 handle 带着该模型集的槽位，视频模型集是另一套文件。
 *
 * 代价是同时只该有一条管道活着 —— 8 GB 机器上两个 context 会直接把进程打死。
 * 所以这里的 handle 在「换模型集 / 卸载 / 页面关闭」时释放，而不是永久缓存。
 *
 * ## 帧为什么不回内存
 *
 * `sd_video_generation_result_t` 本来就一次性持有全部帧（33 帧 832×480 = 40 MB 起），
 * 再复制一份过 JNI 就是 80 MB。所以 native 直接把每帧写成 PNG，这里只拿路径，
 * 播放时按需解码 —— 峰值内存只有一帧。
 */
object VideoRunner {

    /** 一个已导入的模型集（读 `draw/sets/<id>/ponko-set.json`）。 */
    class ModelSet(
        val id: String,
        val name: String,
        val family: String,
        val dir: File?,
        val files: Map<String, File>,
    ) {
        /** 主模型：单文件集的 model，或多文件集的 diffusion */
        fun mainFile(): File? = files["model"] ?: files["diffusion"]
            ?: files.entries.firstOrNull { it.key != "taesd" }?.value

        fun hasVae(): Boolean = files.containsKey("vae")
    }

    /** 一次生成请求。字段名对齐 sd_vid_gen_params_t，方便对着源码核。 */
    class Req(
        val setId: String,
        val prompt: String,
        val negative: String = "",
        val width: Int = 832,
        val height: Int = 480,
        val steps: Int = 20,
        val cfg: Float = 6.0f,
        val seed: Long = -1L,
        val sampler: SdCppEngine.Sampler = SdCppEngine.Sampler.EULER,
        val scheduler: SdCppEngine.Scheduler = SdCppEngine.Scheduler.DISCRETE,
        val frames: Int = 33,
        val fps: Int = 16,
    )

    interface Listener {
        /** 权重加载：sd.cpp 的进度回调在采样开始前报的就是这个（张量数）。 */
        fun onLoading(step: Int, total: Int) {}

        /** 采样：已完成 [step] / [total] 步，附带本机实测的耗时与剩余估计（秒）。 */
        fun onSampling(step: Int, total: Int, elapsedSec: Int, etaSec: Int) {}

        /** 预览帧（低分辨率投影图）。 */
        fun onPreview(bmp: Bitmap) {}

        /** 一段给人看的日志（写进 JSON 页的日志控件）。 */
        fun onLog(line: String) {}

        /** 成功：帧路径（按序）+ 实际 fps。 */
        fun onDone(frames: List<File>, fps: Int) {}

        /** 预热完成（只加载、没出片）。 */
        fun onLoaded() {}

        /** 失败；[reason] 是 sd.cpp 的最后一条 error/warn，可能为 null。 */
        fun onFailed(reason: String?) {}

        fun onCancelled() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ponko-video").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val running = AtomicBoolean(false)
    private var cancelFlag = false

    /** 当前加载着的 context（0 = 没有）。换模型集 / 卸载 / 页面关掉时释放。 */
    @Volatile private var handle = 0L
    @Volatile private var loadedSetId: String? = null

    @Volatile private var curListener: Listener? = null

    fun isRunning(): Boolean = running.get()
    fun loadedSet(): String? = loadedSetId
    fun isLoaded(): Boolean = handle != 0L

    // ==================== 模型集 ====================

    private fun setsDir(c: Context) = File(File(c.filesDir, "draw"), "sets")

    /**
     * 列出已导入的模型集。
     *
     * 和 `DrawPage.listSets()` 是同一份磁盘布局（`draw/sets/<id>/ponko-set.json`），
     * 但故意不共用实现：DrawPage 那个方法带着"当前选中集"的界面状态，把它提出来
     * 要动绘图页一大片。这里只读文件，够用就好，重复的只是 30 行解析。
     */
    fun listSets(c: Context): List<ModelSet> {
        val out = ArrayList<ModelSet>()
        val looseDir = File(c.filesDir, "draw")
        setsDir(c).listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }
            ?.forEach { d ->
                val o = runCatching {
                    JSONObject(File(d, "ponko-set.json").readText())
                }.getOrNull() ?: return@forEach
                val files = LinkedHashMap<String, File>()
                o.optJSONObject("slots")?.let { a ->
                    val keys = a.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val fn = a.optString(k, "")
                        if (fn.isEmpty()) continue
                        val stored = File(fn)
                        val f = if (stored.isAbsolute) stored else File(d, fn)
                        if (f.isFile) files[k] = f
                    }
                }
                if (files.isEmpty()) return@forEach
                out.add(
                    ModelSet(
                        id = d.name,
                        name = o.optString("name", d.name),
                        family = o.optString("family", "custom"),
                        dir = d,
                        files = files,
                    )
                )
            }
        // 旧版散落的单文件模型（draw 目录下的 .gguf）：视频模型几乎不会这样导入，但既然
        // 绘图页能认，这里不认就会出现「列表里看得见、视频页选不到」的割裂
        val loose = looseDir.listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", true) && !f.name.startsWith(".")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val looseVae = loose.firstOrNull { it.name.contains("vae", true) }
        for (f in loose) {
            if (f === looseVae) continue
            val files = LinkedHashMap<String, File>()
            files["model"] = f
            if (looseVae != null) files["vae"] = looseVae
            out.add(ModelSet("legacy:${f.name}", f.nameWithoutExtension, "other", null, files))
        }
        return out
    }

    fun findSet(c: Context, id: String?): ModelSet? =
        listSets(c).firstOrNull { it.id == id }

    // ==================== 生命周期 ====================

    /**
     * 释放 context。**正在生成时不要调** —— 会打断 native 里的内存分配。
     * 页面关闭时调一次，把几十 GB 的映射还回去。
     */
    fun release() {
        if (running.get()) return
        val h = handle
        handle = 0L
        loadedSetId = null
        if (h != 0L) worker.execute { runCatching { SdCppEngine.nativeFree(h) } }
    }

    /** 请求取消。native 侧在下一个采样步之前就会退出。 */
    fun cancel() {
        cancelFlag = true
        val h = handle
        if (h != 0L) runCatching { SdCppEngine.nativeCancel(h) }
    }

    // ==================== 生成 ====================

    /**
     * 开始一次生成。同一时刻只允许一个任务；重复调用会被忽略并回一条日志。
     *
     * 回调全部切到主线程 —— 调用方（JSON 页）直接改 View，不需要再自己 post。
     */
    fun start(c: Context, req: Req, listener: Listener) {
        val app = c.applicationContext
        if (!running.compareAndSet(false, true)) {
            val msg = app.getString(R.string.s_799)
            main.post { listener.onLog(msg) }
            return
        }
        cancelFlag = false
        curListener = listener
        worker.execute { runBlockingJob(app, req, listener) }
    }

    private fun post(listener: Listener, block: Listener.() -> Unit) {
        main.post { runCatching { listener.block() } }
    }

    /**
     * 只加载、不出片。
     *
     * 值这个入口的原因很实际：视频模型动辄十几 GB，加载要几分钟。用户改一版提示词
     * 就要重新等一遍是没法用的，所以给他一个"先把模型装上"的按钮，之后每次生成
     * 直接开始采样。
     */
    fun preload(c: Context, setId: String, listener: Listener) {
        val app = c.applicationContext
        if (!running.compareAndSet(false, true)) {
            val msg = app.getString(R.string.s_799)
            main.post { listener.onLog(msg) }
            return
        }
        worker.execute {
            var reported = false
            try {
                val set = findSet(app, setId)
                if (set == null) {
                    reported = true
                    val msg = app.getString(R.string.s_820, setId)
                    post(listener) { onFailed(msg) }
                    return@execute
                }
                val h = ensureHandle(app, set, listener)
                if (h == null) {
                    reported = true
                    return@execute
                }
                if (!SdCppEngine.supportsVideo(h)) {
                    reported = true
                    val msg = app.getString(R.string.s_821)
                    post(listener) {
                        onLog(msg)
                        onFailed(null)
                    }
                    return@execute
                }
                reported = true
                val ready = app.getString(R.string.s_825)
                post(listener) {
                    onLoaded()
                    onLog(ready)
                }
            } catch (t: Throwable) {
                reported = true
                post(listener) { onFailed(t.message ?: t.javaClass.simpleName) }
            } finally {
                if (!reported) post(listener) { onFailed(null) }
                running.set(false)
            }
        }
    }

    private fun runBlockingJob(c: Context, req: Req, listener: Listener) {
        // 失败只报一次：这条路径上有六处出口，每处都自己 post 一条，
        // 再让 finally 补一条"兜底失败"就会出现"失败了，失败了"两行。
        var reported = false
        try {
            val set = findSet(c, req.setId)
            if (set == null) {
                reported = true
                val msg = c.getString(R.string.s_820, req.setId)
                post(listener) { onFailed(msg) }
                return
            }
            val h = ensureHandle(c, set, listener)
            if (h == null) {
                reported = true
                return
            }

            if (!SdCppEngine.supportsVideo(h)) {
                reported = true
                val msg = c.getString(R.string.s_821)
                post(listener) {
                    onLog(msg)
                    onFailed(null)
                }
                return
            }

            val outDir = File(File(c.filesDir, "draw"), "videos/${System.currentTimeMillis()}")
            outDir.mkdirs()

            var stepStartAt = 0L
            var runStartedAt = 0L
            var lastStep = 0
            val cb = SdCppEngine.StepCallback { step, steps, sampling ->
                if (cancelFlag) return@StepCallback
                if (!sampling) {
                    post(listener) { onLoading(step, steps) }
                    return@StepCallback
                }
                if (runStartedAt == 0L) runStartedAt = System.currentTimeMillis()
                if (step > lastStep) {
                    stepStartAt = System.currentTimeMillis()
                    lastStep = step
                }
                val now = System.currentTimeMillis()
                val elapsed = if (runStartedAt > 0) ((now - runStartedAt) / 1000).toInt() else 0
                // 每步耗时 × 剩余步数。第一步没有样本，按 0 处理（显示"估计中"）
                val per = if (step > 0 && stepStartAt > 0) (now - runStartedAt) / step else 0
                val eta = if (step > 0 && steps > step) ((per * (steps - step)) / 1000).toInt() else 0
                post(listener) { onSampling(step, steps, elapsed, eta) }
            }
            val pcb = SdCppEngine.PreviewCallback { _, w, hh, rgb ->
                if (cancelFlag) return@PreviewCallback
                val bmp = SdCppEngine.rgbToBitmap(w, hh, rgb) ?: return@PreviewCallback
                post(listener) { onPreview(bmp) }
            }

            val paths = SdCppEngine.renderVideo(
                handle = h,
                prompt = req.prompt,
                outDir = outDir.absolutePath,
                negative = req.negative,
                width = req.width,
                height = req.height,
                steps = req.steps,
                cfg = req.cfg,
                seed = req.seed,
                sampler = req.sampler,
                scheduler = req.scheduler,
                frames = req.frames,
                fps = req.fps,
                cb = cb,
                onPreview = pcb,
            )

            val warnings = runCatching { SdCppEngine.takeWarnings() }.getOrDefault("")
            if (warnings.isNotBlank()) {
                warnings.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    post(listener) { onLog(line) }
                }
            }

            if (cancelFlag) {
                reported = true
                post(listener) { onCancelled() }
                return
            }
            if (paths == null) {
                reported = true
                post(listener) { onFailed(pickReason(warnings)) }
                return
            }
            val files = paths.map { File(it) }.filter { it.isFile }
            if (files.isEmpty()) {
                reported = true
                post(listener) { onFailed(pickReason(warnings)) }
                return
            }
            reported = true
            post(listener) { onDone(files, req.fps) }
        } catch (t: Throwable) {
            reported = true
            post(listener) { onFailed(t.message ?: t.javaClass.simpleName) }
        } finally {
            if (!reported) post(listener) { onFailed(null) }
            curListener = null
            running.set(false)
        }
    }

    /**
     * 确保 context 就绪。换模型集时先释放旧的 —— 两个 context 同时活着会 OOM。
     */
    private fun ensureHandle(c: Context, set: ModelSet, listener: Listener): Long? {
        val cur = handle
        if (cur != 0L && loadedSetId == set.id) return cur
        if (cur != 0L) {
            val prev = c.getString(R.string.s_823, loadedSetId.orEmpty())
            post(listener) { onLog(prev) }
            runCatching { SdCppEngine.nativeFree(cur) }
            handle = 0L
            loadedSetId = null
        }
        val mainFile = set.mainFile() ?: run {
            val msg = c.getString(R.string.s_822)
            post(listener) { onFailed(msg) }
            return null
        }
        val loading = c.getString(R.string.s_824, set.name, mainFile.name)
        post(listener) { onLog(loading) }
        val slotMap = set.files.mapValues { it.value.absolutePath }
        val h = SdCppEngine.create(
            modelPath = slotMap["model"].orEmpty(),
            vaePath = slotMap["vae"],
            nThreads = InferConfig.threads(c),
            wtype = SdCppEngine.WTYPE_KEEP,
            flashAttn = true,
            // 视频模型在手机上没有能用的 Vulkan 路径（显存不够，且 H3/Hunyuan 的
            // 算子覆盖也不全），固定 CPU：显式传名字还能关掉 sd.cpp 的 auto_fit
            backend = "CPU",
            slots = SdCppEngine.buildSlots(slotMap),
        )
        if (h == 0L) {
            val why = runCatching { SdCppEngine.takeWarnings() }.getOrDefault("")
            if (why.isNotBlank()) {
                why.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    post(listener) { onLog(line) }
                }
            }
            post(listener) { onFailed(pickReason(why)) }
            return null
        }
        handle = h
        loadedSetId = set.id
        val ok = c.getString(R.string.s_825)
        post(listener) { onLog(ok) }
        return h
    }

    /** 从 sd.cpp 的告警里挑一句最能代表失败原因的（和 DrawPage 同一套判据）。 */
    private fun pickReason(warnings: String): String? {
        val lines = warnings.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val keys = listOf("error", "failed", "not found", "missing", "unsupported", "cannot")
        return lines.lastOrNull { l -> keys.any { l.contains(it, true) } } ?: lines.last()
    }
}
