// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// hw 分支（华为上架版）专用：首次启动的声明弹窗逻辑。
//
// 这里只放**纯文本拼装**（不碰 Context / View），原因是末尾那句
// 「使用本应用即代表知晓并同意此声明」需要单独加粗，而加粗靠的是下标范围 ——
// 下标算错是**静默**的（少加粗一个字、或把上一段末尾也加粗），
// 抽成纯函数后可以在本机用 JVM 直接跑真实代码验证。
package com.litertchat.app

object HwFirstRun {

    /**
     * 把声明正文与末尾的「已同意」句拼成弹窗全文。
     *
     * @param bodyParts 声明的各段落，依次用空行分隔
     * @param agree     末尾那句「使用本应用即代表知晓并同意此声明」
     * @return [0] 完整文本；[1] 「已同意」句在完整文本中的**起始下标**（给 Spannable 加粗用）
     */
    fun build(bodyParts: List<String>, agree: String): Pair<String, Int> {
        val body = bodyParts.joinToString("\n\n")
        val full = body + "\n\n" + agree
        return full to (body.length + 2)
    }
}
