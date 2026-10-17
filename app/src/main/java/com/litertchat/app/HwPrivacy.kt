// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// hw 分支（华为上架版）：在应用内展示隐私政策全文。
//
// 为什么用 WebView 加载 assets 里的同一份 HTML，而不是把文案抄成字符串：
//   华为第 4 条要求「应用内的隐私政策」与「AGC 提交的隐私政策链接」内容一致。
//   两处各存一份文本，改一处忘一处就会不一致 —— 这是最容易踩的坑。
//   这里把 privacy.html **原样**打进 assets（构建前由同一文件复制），
//   网页版与内置版天然一致，改的时候只需改源文件再复制一次。
//
// 安全方面的取舍：
//   · 关闭 JavaScript（本页不含脚本，也不需要）
//   · 关闭 content access，只允许读 assets
//   · 不外链、不拦截（页面里只有 GitHub 的普通文本链接）
package com.litertchat.app

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

object HwPrivacy {

    /** assets 里的内置副本（与网页版 privacy.html 内容相同） */
    private const val ASSET_URL = "file:///android_asset/privacy.html"

    /**
     * 弹窗展示隐私政策全文。
     *
     * @param act     宿主 Activity
     * @param title   弹窗标题
     * @param okText  关闭按钮文字
     */
    fun show(act: Activity, title: String, okText: String) {
        // 整个弹窗包容错：隐私政策打不开也不能把调用方（首启流程）带崩
        runCatching {
            val wv = WebView(act).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.loadsImagesAutomatically = true
                webViewClient = WebViewClient()
                loadUrl(ASSET_URL)
            }

            // AlertDialog 里的 WebView 没有固有高度，不显式给就会塌成 0dp
            val h = (act.resources.displayMetrics.heightPixels * 0.68f).toInt()
            wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

            AlertDialog.Builder(act)
                .setTitle(title)
                .setView(wv)
                .setPositiveButton(okText, null)
                .show()
        }
    }
}
