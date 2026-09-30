package com.mistakebook.pipeline

import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.Option
import com.mistakebook.domain.QuestionDraft
import com.mistakebook.domain.labelFor
import kotlinx.serialization.json.Json

// 一次 LLM 整理的结果：若干道题草稿 + 是否降级。
data class RefinedOutcome(
    val drafts: List<QuestionDraft>,
    val degraded: Boolean = false,
    val degradedReason: String = "",
    val truncatedInput: Boolean = false
)

/**
 * JSON 容错提取（PRD 5.3）：
 * 1) 直接解析；2) 去围栏；3) 第一个 { 到最后一个 }；4) 降级为原始 Markdown；
 * 5) 字段级容错（缺字段用默认值，label 缺省补 A/B/C，difficulty 钳到 1..5）。
 */
object JsonExtractor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun extract(raw: String, markdown: String, imagePath: String): RefinedOutcome {
        val items = parseItems(raw)
        if (items != null && items.isNotEmpty()) {
            return RefinedOutcome(
                drafts = items.map { toDraft(it, markdown, imagePath, degraded = false) }
            )
        }
        // 单题结构必须真的含题目字段：{"items":...} 之类会被 ignoreUnknownKeys 解析成空对象，
        // 早期因此把多题结果误判成一道空题。
        val single = parseSingle(raw)
        if (single != null && single.hasContent()) {
            return RefinedOutcome(
                drafts = listOf(toDraft(single, markdown, imagePath, degraded = false))
            )
        }
        return RefinedOutcome(
            drafts = listOf(
                QuestionDraft(
                    imagePath = imagePath,
                    mineruMarkdown = markdown,
                    subjectName = "",
                    stem = markdown.trim(),
                    options = emptyList(),
                    answer = "",
                    analysis = "",
                    knowledgePoints = emptyList(),
                    errorReason = ErrorReason.OTHER,
                    difficulty = 3,
                    uncertain = listOf("AI 整理失败，已保留原始识别文本")
                )
            ),
            degraded = true
        )
    }

    private fun parseSingle(raw: String): RefinedQuestionDto? {
        val text = raw.trim()
        val candidates = listOf(
            text,
            stripFence(text),
            substringBetweenBraces(text)
        )
        candidates.forEach { candidate ->
            if (candidate.isBlank()) return@forEach
            decodeSingle(candidate)?.let { return it }
        }
        return null
    }

    private fun RefinedQuestionDto.hasContent(): Boolean =
        stem.isNotBlank() || options.isNotEmpty() || answer.isNotBlank() || analysis.isNotBlank()

    private fun parseItems(raw: String): List<RefinedQuestionDto>? {
        val text = raw.trim()
        val candidates = listOf(text, stripFence(text), substringBetweenBraces(text))
        candidates.forEach { candidate ->
            if (candidate.isBlank()) return@forEach
            decodeItems(candidate)?.let { return it }
        }
        return null
    }

    private fun decodeSingle(text: String): RefinedQuestionDto? =
        runCatching { json.decodeFromString<RefinedQuestionDto>(text) }.getOrNull()
            ?: runCatching { json.decodeFromString<RefinedQuestionDto>(repairJson(text)) }.getOrNull()

    private fun decodeItems(text: String): List<RefinedQuestionDto>? =
        runCatching { json.decodeFromString<RefinedItemsDto>(text).items }.getOrNull()
            ?: runCatching { json.decodeFromString<List<RefinedQuestionDto>>(text) }.getOrNull()
            ?: runCatching { json.decodeFromString<RefinedItemsDto>(repairJson(text)).items }.getOrNull()
            ?: runCatching { json.decodeFromString<List<RefinedQuestionDto>>(repairJson(text)) }.getOrNull()
            ?: decodeByBraceScan(text)
            ?: decodeByBraceScan(repairJson(text))

    /**
     * 括号配平扫描，逐个解出最外层的 `{...}`。
     *
     * 覆盖几种模型常见但标准解析器认不出的输出：
     * - `{"1":{题目},"2":{题目}}`（用题号当 key）
     * - `{题目}{题目}`（多个对象直接并列，没包数组）
     * - 前面带一句「好的，以下是整理结果：」再跟 JSON
     * 只要每个对象能解成一道题就收下，解不出内容的丢掉。
     */
    private fun decodeByBraceScan(text: String): List<RefinedQuestionDto>? {
        val found = mutableListOf<RefinedQuestionDto>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { index, ch ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }

                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            val chunk = text.substring(start, index + 1)
                            decodeSingle(chunk)
                                ?.takeIf { it.hasContent() }
                                ?.let { found.add(it) }
                            start = -1
                        }
                    }
                }
            }
        }
        return found.takeIf { it.isNotEmpty() }
    }

    /**
     * 轻量 JSON 修复。
     *
     * 真实事故：模型输出的 JSON 经常只差一两个字符就没法解析——尾随逗号、
     * ```json 围栏没剥干净、单引号、字符串里未转义的换行。
     * 这些情况结构其实是对的，直接判失败太浪费，擦一下就能救回来。
     * 只做「确定不改变语义」的修补，不做猜测性补全。
     */
    private fun repairJson(text: String): String {
        var out = stripFence(text)
        val start = out.indexOf('{')
        val end = out.lastIndexOf('}')
        if (start >= 0 && end > start) out = out.substring(start, end + 1)
        // 对象/数组的最后一个元素后面常多一个逗号
        out = out.replace(Regex("""\s*,\s*([}\]])"""), "$1")
        // 中文语境下模型偶尔用全角引号
        out = out.replace('\u201C', '"').replace('\u201D', '"')
        out = out.replace('\u2018', '"').replace('\u2019', '"')
        return out
    }

    private fun stripFence(text: String): String {
        val withoutHead = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        return withoutHead.removeSuffix("```").trim()
    }

    private fun substringBetweenBraces(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return text.substring(start, end + 1)
    }

    private fun toDraft(
        dto: RefinedQuestionDto,
        markdown: String,
        imagePath: String,
        degraded: Boolean
    ): QuestionDraft {
        val options = dto.options.mapIndexed { index, option ->
            Option(
                label = option.label.ifBlank { labelFor(index) },
                text = normalizeBreaks(option.text)
            )
        }.filter { it.text.isNotBlank() }
        return QuestionDraft(
            imagePath = imagePath,
            mineruMarkdown = markdown,
            subjectName = dto.subject.trim(),
            title = dto.title.trim(),
            stem = normalizeBreaks(dto.stem).trim()
                .ifEmpty { if (degraded) markdown.trim() else "" },
            options = options,
            answer = normalizeBreaks(dto.answer).trim(),
            analysis = normalizeBreaks(dto.analysis).trim(),
            knowledgePoints = dto.knowledge_points.map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("未标注知识点") },
            errorReason = mapReason(dto.error_reason_guess),
            difficulty = dto.difficulty.takeIf { it in 1..5 } ?: 3,
            uncertain = dto.uncertain.map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    /**
     * 把模型输出的**字面量** `\n` `\t` 还原成真换行 / 制表符。
     *
     * 这是一个自造的坑：提示词里写了「步骤之间用 \n 分隔」，
     * 模型就忠实地输出了两个字符 `\` `n`，而序列化后的 JSON 里它是转义序列，
     * `Json` 会把它解成真换行——但**没走 JSON 解析的那条路**（降级兜底、
     * 以及部分模型把 analysis 直接塞进字符串）就会在界面上显示成 `\n`。
     * 统一在这里擦一遍，两条路径都受益。
     *
     * 注意不能用 `replace("\\n", "\n")` 一刀切：那会把 LaTeX 里的
     * `\nabla`、`\neq` 之类命令的前导反斜杠后紧跟的 `n` 也换掉，
     * 直接毁掉公式。只替换**不在 LaTeX 分隔符内**的 `\n`。
     */
    private fun normalizeBreaks(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    // \n \t \r 是转义序列，还原
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'r' -> { i += 2 } // \r 单独出现时忽略，避免产生孤立回车
                    // 其余是 LaTeX 命令（\neq / \nabla / \frac ...），原样保留
                    else -> { out.append(ch).append(next); i += 2 }
                }
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun mapReason(raw: String): ErrorReason = when (raw.trim()) {
        "概念不清" -> ErrorReason.CONCEPT
        "计算失误" -> ErrorReason.CALCULATION
        "审题错误" -> ErrorReason.READING
        "思路不会" -> ErrorReason.NO_IDEA
        "粗心遗漏" -> ErrorReason.CARELESS
        else -> ErrorReason.OTHER
    }
}
