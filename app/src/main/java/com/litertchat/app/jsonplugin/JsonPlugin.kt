// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.jsonplugin

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.litertchat.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一份 **JSON 插件**：只有数据，没有代码。
 *
 * 它和 Ponko-EX 的插件是两个物种：
 *   · Ponko-EX 是独立 APK + AIDL Service，能跑自己的推理
 *   · 这一份只是一个 `.json` 文件，描述「页面上有哪些控件」和「按钮按下时调谁」
 *
 * 执行永远是宿主（或别的插件）的事 —— JSON 写完之后就退场，不持有任何数据流。
 * 这条边界是有意的：数据文件一旦能"做事"，它就要有自己的生命周期、错误处理和权限模型，
 * 那时它已经不是配置而是程序了，用 JSON 表达反而更难维护。
 *
 * 格式（formatVersion = 1）见工作区 `_json-plugin-spec-draft.md`：
 * ```
 * { "formatVersion": 1, "id": "...", "name": "...",
 *   "ui": { "intro": "...", "items": [ ... ] },
 *   "actions": { "run:go": { "call": "host.video", "args": { "prompt": "$ui.prompt" } } } }
 * ```
 */
class JsonPlugin(
    val file: File,
    val id: String,
    val name: String,
    val author: String,
    val intro: String,
    val items: List<Item>,
    val actions: Map<String, Action>,
    /** 这一页要用到哪些 Ponko-EX 插件（只用于提示，宿主不据此拦截） */
    val requiresPlugins: List<String>,
    /** 这一页会用到的宿主动作。超出白名单的动作会被拒绝执行 */
    val requiresHostActions: List<String>,
) {

    /** 一个控件。字段名对齐 `com.litertchat.plugin.UiItem`，type 的编号也一样。 */
    class Item(
        val key: String,
        val type: Int,
        val label: String,
        val help: String,
        val value: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val options: List<Option>,
        val enabled: Boolean,
        val primary: Boolean,
    )

    class Option(val value: String, val label: String)

    /**
     * 一条动作 = **一个调用**。
     *
     * 没有 steps、没有中间产物命名 —— 那是执行者的事。这里只回答两个问题：
     * 调谁（[call]）、带什么参数（[args]，值可以是 `$ui.<key>`）。
     */
    class Action(
        val id: String,
        val call: String,
        val plugin: String?,
        val args: Map<String, String>,
        val confirm: Boolean,
    )

    companion object {
        const val FORMAT_VERSION = 1

        /** 支持的控件编号。超出这个范围的在页面上显示成一条"更新版本"的灰提示。 */
        const val MAX_KNOWN_TYPE = 27

        /** 解析失败时抛这个，带一句能直接显示给用户的原因。 */
        class BadFormat(message: String) : Exception(message)

        fun parse(c: Context, file: File): JsonPlugin {
            val text = runCatching { file.readText() }.getOrElse {
                throw BadFormat(c.getString(R.string.s_826, it.message ?: it.javaClass.simpleName))
            }
            val o = runCatching { JSONObject(text) }.getOrElse {
                throw BadFormat(c.getString(R.string.s_827, it.message ?: ""))
            }
            val ver = o.optInt("formatVersion", 0)
            if (ver != FORMAT_VERSION) {
                throw BadFormat(c.getString(R.string.s_828, ver, FORMAT_VERSION))
            }
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) throw BadFormat(c.getString(R.string.s_829))
            val ui = o.optJSONObject("ui") ?: throw BadFormat(c.getString(R.string.s_830))
            val arr = ui.optJSONArray("items") ?: throw BadFormat(c.getString(R.string.s_831))
            val items = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                items.add(parseItem(it))
            }
            if (items.isEmpty()) throw BadFormat(c.getString(R.string.s_832))

            val acts = LinkedHashMap<String, Action>()
            o.optJSONObject("actions")?.let { ao ->
                val keys = ao.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val a = ao.optJSONObject(k) ?: continue
                    val call = a.optString("call", "").trim()
                    if (call.isEmpty()) continue
                    val args = LinkedHashMap<String, String>()
                    a.optJSONObject("args")?.let { ag ->
                        val ak = ag.keys()
                        while (ak.hasNext()) {
                            val key = ak.next()
                            args[key] = ag.optString(key, "")
                        }
                    }
                    acts[k] = Action(
                        id = k,
                        call = call,
                        plugin = a.optString("plugin", "").ifEmpty { null },
                        args = args,
                        confirm = a.optBoolean("confirm", false),
                    )
                }
            }

            val req = o.optJSONObject("requires")
            return JsonPlugin(
                file = file,
                id = id,
                name = o.optString("name", id),
                author = o.optString("author", ""),
                intro = ui.optString("intro", ""),
                items = items,
                actions = acts,
                requiresPlugins = req?.optJSONArray("plugins").strings(),
                requiresHostActions = req?.optJSONArray("hostActions").strings(),
            )
        }

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) optString(i, "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
            return out
        }

        private fun parseItem(o: JSONObject): Item {
            val opts = ArrayList<Option>()
            o.optJSONArray("options")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    opts.add(Option(x.optString("value", ""), x.optString("label", x.optString("value", ""))))
                }
            }
            return Item(
                key = o.optString("key", ""),
                type = o.optInt("type", 0),
                label = o.optString("label", ""),
                help = o.optString("help", ""),
                value = o.optString("value", ""),
                min = o.optDouble("min", 0.0),
                max = o.optDouble("max", 100.0),
                step = o.optDouble("step", 1.0),
                options = opts,
                enabled = o.optBoolean("enabled", true),
                primary = o.optBoolean("primary", false),
            )
        }
    }
}

/**
 * JSON 插件的磁盘位置与扫描。
 *
 * 目录：`<filesDir>/json-plugins/`（每份插件一个 .json）。选私有目录而不是公共目录，是因为
 * Android 11+ 对 `Android/data` 和 `Download` 的访问都受限，一个文件管理器能不能看见
 * 取决于机型和厂商；配上"导入"按钮（走系统文件选择器）反而对每种机型都一致。
 */
object JsonPlugins {

    private const val DIR = "json-plugins"

    fun dir(c: Context): File = File(c.filesDir, DIR).apply { mkdirs() }

    class Scan(val plugins: List<JsonPlugin>, val errors: List<Pair<String, String>>)

    /**
     * 第一次运行时把随包带的示例页（视频生成）放进目录。
     *
     * 为什么要有：JSON 插件是新机制，用户打开插件页看到一片空白不会知道该写什么。
     * 放一份能跑的在里面，既是示例也是"视频生成"的正式入口。
     *
     * **删了不会再回来**：每个文件按名字记一个"已投放过"的标记，用户删掉是明确的意图，
     * 下次启动又自动长出来只会让人以为删除没生效。
     */
    fun seedBuiltins(c: Context) {
        val marker = c.getSharedPreferences("json-plugins-seed", Context.MODE_PRIVATE)
        val names = runCatching { c.assets.list("json-plugins") }.getOrNull() ?: return
        for (n in names) {
            if (!n.endsWith(".json", true)) continue
            if (marker.getBoolean(n, false)) continue
            val dest = File(dir(c), n)
            if (!dest.exists()) {
                runCatching {
                    c.assets.open("json-plugins/$n").use { i ->
                        dest.outputStream().use { o -> i.copyTo(o) }
                    }
                }
            }
            marker.edit().putBoolean(n, true).apply()
        }
    }

    /**
     * 扫一遍。坏文件不静默跳过 —— 每个都带一句原因，界面上能看出"我放进去的那份为什么没出现"。
     */
    fun scan(c: Context): Scan {
        seedBuiltins(c)
        val out = ArrayList<JsonPlugin>()
        val errs = ArrayList<Pair<String, String>>()
        val ids = HashSet<String>()
        val files = dir(c).listFiles { f -> f.isFile && f.name.endsWith(".json", true) }
            ?.sortedBy { it.name } ?: emptyList()
        for (f in files) {
            try {
                val p = JsonPlugin.parse(c, f)
                if (!ids.add(p.id)) {
                    errs.add(f.name to c.getString(R.string.s_833, p.id))
                    continue
                }
                out.add(p)
            } catch (t: Throwable) {
                errs.add(f.name to (t.message ?: t.javaClass.simpleName))
            }
        }
        return Scan(out, errs)
    }

    /** 导入一份 JSON；返回 null = 成功，否则是给用户看的原因。 */
    fun importFrom(c: Context, uri: Uri): String? {
        val bytes = runCatching {
            c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return c.getString(R.string.s_834)
        if (bytes.size > 512 * 1024) return c.getString(R.string.s_835)
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?: return c.getString(R.string.s_836)
        // 先落到临时文件解析一遍，别把坏文件留在目录里污染列表
        val tmp = File(dir(c), ".import.json")
        runCatching { tmp.writeText(text) }
        val parsed = runCatching { JsonPlugin.parse(c, tmp) }.getOrElse {
            runCatching { tmp.delete() }
            return c.getString(R.string.s_837, it.message ?: it.javaClass.simpleName)
        }
        val dest = File(dir(c), "${parsed.id}.json")
        return runCatching {
            tmp.renameTo(dest).let { if (!it) { tmp.copyTo(dest, overwrite = true); tmp.delete() } }
            null
        }.getOrElse {
            runCatching { tmp.delete() }
            c.getString(R.string.s_838, it.message ?: it.javaClass.simpleName)
        }
    }

    /** 控件值按插件 id 各存一份偏好；同一个 key 在不同插件里互不干扰。 */
    fun prefs(c: Context, id: String): SharedPreferences =
        c.getSharedPreferences("json-plugin-$id", Context.MODE_PRIVATE)

    fun delete(c: Context, id: String) {
        dir(c).listFiles { f -> f.isFile && f.name.endsWith(".json", true) }?.forEach { f ->
            val pid = runCatching { JsonPlugin.parse(c, f).id }.getOrNull()
            if (pid == id) f.delete()
        }
    }
}

/**
 * 宿主动作的白名单。
 *
 * 这一栏是"JSON 能调的东西"的边界：JSON 里写了一个这里没有的 `call`，
 * 页面会拒绝执行并说明原因，而不是静默失败 —— 作者要能看出是自己写错了还是宿主没实现。
 */
object HostActions {
    /** 跑一次视频生成。参数见 sd_vid_gen_params_t（prompt / video_frames / fps / …） */
    const val VIDEO = "host.video"

    /** 取消当前长任务（视频动辄几十分钟，没有取消等于不可用） */
    const val CANCEL = "host.cancel"

    const val VIDEO_LOAD = "host.video.load"
    const val VIDEO_UNLOAD = "host.video.unload"

    val ALL = setOf(VIDEO, CANCEL, VIDEO_LOAD, VIDEO_UNLOAD)
}
