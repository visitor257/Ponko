// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// hw 分支（华为上架版）：在应用内展示隐私政策全文。
//
// 为什么用 WebView 加载 assets 里的同一份 HTML，而不是把文案抄成字符串：
//   华为第 4 条要求「应用内的隐私政策」与「AGC 提交的隐私政策链接」内容一致。
//   两处各存一份文本，改一处忘一处就会不一致 —— 这是最容易踩的坑。
//   这里把 privacy.html **原样**打进 assets（构建时由同一文件生成），
//   网页版与内置版天然一致。
//
// 安全方面的取舍：
//   · 关闭 JavaScript（本页不含脚本，也不需要）
//   · 关闭 content access，只允许读 assets
//   · 页面内除 assets 之外的链接（如 GitHub 仓库）交给外部浏览器打开，
//     否则会在 WebView 里跳走，而且需要联网才回得来
//
// ⚠ **为什么要有纯文本兜底**（2026-10-18 加）：
//   审核环境包含 EMUI 10（Android 10，Mate 20 Pro）。华为机型上
//   Android System WebView 存在被停用、版本过旧或被替换的可能。
//   原先整个弹窗包在 runCatching 里 —— 一旦 WebView 构造失败就**静默返回**，
//   用户点《隐私政策》没有任何反应，审核会判定「隐私政策无法访问」，
//   第 5 条（首启须明确提示隐私政策）会被再次驳回。
//   现在：WebView 不可用、或主文档加载失败（onReceivedError）
//        → 自动回退到「从同一份 assets 读出、转成纯文本」的滚动弹窗。
//   两条路读的是同一个文件，内容一致性不受影响。
//
// 顺带说明这个页面对旧 WebView 为何安全：
//   · 零 <script>，正文全部在 HTML 里（不靠 JS 生成）
//   · 语言切换是「锚点滚动」而非「显示/隐藏」，不点也不影响内容呈现
//   · 用到的 CSS（sticky / scroll-behavior / prefers-color-scheme）都是渐进增强，
//     不支持只是样式退化，不会读不到内容
package com.litertchat.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView

object HwPrivacy {

    /** assets 里的内置副本（与网页版 privacy.html 内容相同） */
    private const val ASSET = "privacy.html"
    private const val ASSET_URL = "file:///android_asset/$ASSET"

    /**
     * 弹窗展示隐私政策全文。
     *
     * @param act     宿主 Activity
     * @param title   弹窗标题
     * @param okText  关闭按钮文字
     */
    fun show(act: Activity, title: String, okText: String) {
        // 先试 WebView（排版好、可点语言跳转）；构造失败就立刻走纯文本。
        val started = runCatching { showWeb(act, title, okText) }.getOrDefault(false)
        if (!started) showPlain(act, title, okText)
    }

    /** 返回 true 表示 WebView 路径已启动（后续异步失败由回调自行回退）。 */
    private fun showWeb(act: Activity, title: String, okText: String): Boolean {
        val wv = WebView(act)
        var dialog: AlertDialog? = null
        var fellBack = false

        fun fallback() {
            if (fellBack) return
            fellBack = true
            runCatching { dialog?.dismiss() }
            runCatching { wv.destroy() }
            showPlain(act, title, okText)
        }

        wv.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            domStorageEnabled = false
            loadsImagesAutomatically = true
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // 只有主文档失败才回退（子资源失败不影响正文）
                if (request.isForMainFrame) fallback()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val u = request.url ?: return false
                if (u.toString().startsWith("file:///android_asset/")) return false
                // 外部链接（GitHub 仓库等）交给系统浏览器，别在弹窗里跳走
                runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }
        }

        // AlertDialog 里的 WebView 没有固有高度，不显式给就会塌成 0dp
        val h = (act.resources.displayMetrics.heightPixels * 0.68f).toInt()
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        wv.loadUrl(ASSET_URL)

        dialog = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(wv)
            .setPositiveButton(okText, null)
            .create()
        dialog.setOnDismissListener {
            runCatching { wv.destroy() }
        }
        dialog.show()
        return true
    }

    /**
     * 纯文本兜底：从**同一份** assets 读出 HTML，去掉样式与标签后按段落排版。
     * 内容与 WebView 版完全同源，只是失去排版与语言跳转。
     */
    private fun showPlain(act: Activity, title: String, okText: String) {
        val body = runCatching {
            act.assets.open(ASSET).bufferedReader().use { htmlToPlain(it.readText()) }
        }.getOrElse { act.getString(R.string.s_466) }

        val pad = (act.resources.displayMetrics.density * 18).toInt()
        val tv = TextView(act).apply {
            text = body
            textSize = 14f
            setLineSpacing(0f, 1.3f)
            setPadding(pad, pad, pad, pad)
            // 不指定颜色，跟随系统主题（深色模式下也不会看不见）
        }
        val sv = ScrollView(act).apply { addView(tv) }

        runCatching {
            val h = (act.resources.displayMetrics.heightPixels * 0.68f).toInt()
            sv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            AlertDialog.Builder(act)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(okText, null)
                .show()
        }
    }

    /**
     * 极简 HTML → 纯文本。只做本页需要的那几件事：
     * 去掉 style/script/head、块级标签换行、li 加圆点、td/th 用竖线分隔、解常见实体。
     */
    private fun htmlToPlain(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<style.*?</style>"), "")
        s = s.replace(Regex("(?is)<script.*?</script>"), "")
        s = s.replace(Regex("(?is)<head.*?</head>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "· ")
        s = s.replace(Regex("(?i)</t[dh]>"), " | ")
        s = s.replace(Regex("(?i)</(h1|h2|h3|h4|p|div|tr|li|table|ul|ol)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        s = s.replace(Regex("[ \t]+\n"), "\n")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }
}
