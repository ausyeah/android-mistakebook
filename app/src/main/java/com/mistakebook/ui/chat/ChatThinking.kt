package com.mistakebook.ui.chat

/**
 * 思考过程与正文的分隔。
 *
 * ## 为什么用标记而不是两个数据库字段
 *
 * 1. **不用写迁移**。给 `chat_messages` 加字段要升 Room 版本号、写迁移 SQL，
 *    而迁移是本项目最容易翻车的一步（v0.0.x 那次「只加字段不改版本号」
 *    直接导致老用户一开库就 `IllegalStateException` 闪退）。
 * 2. **`<think>…</think>` 是通用约定**。DeepSeek / Qwen 都用它标记思考内容，
 *    导出的对话拿到别的前端里也能被正确识别。
 *
 * ## 边界情况
 *
 * - 模型可能**只思考、没回答**（例如拒答前的推理）——这时正文为空，
 *   仍然要显示思考，不能显示成空气泡。
 * - 标记**必须成对**。只出现 `\n<think>` 而没有 `\n</think>` 时，
 *   整段都当思考——宁可多显示，也不把思考混进正文里（正文是要复制的）。
 */
object ChatThinking {

    /**
     * 标记本身**不带前导换行**。
     *
     * 早先写成 `"\n<think>"` / `"\n</think>"`，于是模型输出的 `</think>`
     * 前面只要没有换行就匹配不上——表现是**整段正文被当成思考**，
     * 用户看到一个巨大的思考块、答案不见了，而且不报错。
     * 换行只影响显示，由界面在拆分后自己补。
     */
    const val OPEN = "<think>"
    const val CLOSE = "</think>"

    /** 内容里是否已经带了标记。 */
    fun isWrapped(text: String): Boolean = text.contains(OPEN)

    /**
     * 把一条消息拆成「思考 / 正文」。
     *
     * @param thinking 空串表示这条消息没有思考过程。
     * @param answer 去掉标记后的正文。
     */
    fun split(raw: String): Pair<String, String> {
        val open = raw.indexOf(OPEN)
        if (open < 0) return "" to raw.trim()
        val bodyStart = open + OPEN.length
        val close = raw.indexOf(CLOSE, bodyStart)
        // 没有闭合标记：整段都当思考。这比把思考混进正文安全——
        // 正文是可以被复制走的，混进去会让复制出来的东西莫名其妙。
        if (close < 0) return raw.substring(bodyStart).trim() to ""
        val thought = raw.substring(bodyStart, close).trim()
        val answer = raw.substring(close + CLOSE.length).trim()
        return thought to answer
    }

    /** 标记之间与标记之后各留一个换行，纯粹为了显示时各占一行。 */
    fun tags(text: String): String = if (isWrapped(text)) text else "\n$OPEN$text$CLOSE"

    /** 供界面显示的思考过程，纯文本。 */
    fun thinkingOf(raw: String): String = split(raw).first

    /** 供界面显示的正文，已剥掉标记。 */
    fun answerOf(raw: String): String = split(raw).second
}
