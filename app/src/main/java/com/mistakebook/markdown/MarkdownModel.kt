package com.mistakebook.markdown

/**
 * 轻量 Markdown / MinerU 输出解析：标题、段落、列表、引用、分割线、代码块、
 * HTML 表格与 Markdown 管道表格、图片、行内与独立公式。
 */
object MarkdownParser {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class ListItem(val marker: String, val text: String) : Block
        data class Quote(val text: String) : Block
        data class Table(val rows: List<List<String>>) : Block
        data class Image(val path: String, val alt: String) : Block
        data class Math(val latex: String, val display: Boolean) : Block
        data class Code(val text: String) : Block
        data object Rule : Block
    }

    private val tableTag = Regex("<table[^>]*>(.*?)</table>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val rowTag = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val cellTag = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val htmlTag = Regex("<[^>]+>")

    fun parse(input: String): List<Block> {
        if (input.isBlank()) return emptyList()
        val blocks = mutableListOf<Block>()
        // 先把 HTML 表格抠出来单独成块，其余按行处理
        val segments = splitByTables(input)
        segments.forEach { segment ->
            if (segment.startsWith("\u0000TABLE\u0000")) {
                parseHtmlTable(segment.removePrefix("\u0000TABLE\u0000"))?.let { blocks += it }
            } else {
                parsePlain(segment.trimEnd(), blocks)
            }
        }
        return blocks
    }

    private fun splitByTables(input: String): List<String> {
        val result = mutableListOf<String>()
        var last = 0
        tableTag.findAll(input).forEach { match ->
            if (match.range.first > last) {
                result += input.substring(last, match.range.first)
            }
            result += "\u0000TABLE\u0000" + match.groupValues[1]
            last = match.range.last + 1
        }
        if (last < input.length) result += input.substring(last)
        return result
    }

    private fun parseHtmlTable(html: String): Block.Table? {
        val rows = rowTag.findAll(html).map { rowMatch ->
            cellTag.findAll(rowMatch.groupValues[1])
                .map { cell -> cleanCell(cell.groupValues[1]) }
                .toList()
        }.filter { it.isNotEmpty() }.toList()
        return if (rows.isEmpty()) null else Block.Table(rows)
    }

    private fun cleanCell(raw: String): String =
        htmlTag.replace(raw, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()

    private fun parsePlain(text: String, blocks: MutableList<Block>) {
        if (text.isBlank()) return
        val lines = text.split('\n')
        val paragraph = StringBuilder()
        var inCode = false
        val code = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += paragraphBlock(paragraph.toString().trim())
            }
            paragraph.clear()
        }

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.trimStart().startsWith("```") -> {
                    if (inCode) {
                        blocks += Block.Code(code.toString().trimEnd())
                        code.clear()
                        inCode = false
                    } else {
                        flushParagraph()
                        inCode = true
                    }
                }

                inCode -> code.appendLine(line)

                line.isBlank() -> flushParagraph()

                line.trimStart().startsWith("$$") -> {
                    flushParagraph()
                    val latex = line.trim().removePrefix("$$").removeSuffix("$$").trim()
                    if (latex.isNotEmpty()) blocks += Block.Math(latex, true)
                }

                line.matches(Regex("^\\s*[-*_]{3,}\\s*$")) -> {
                    flushParagraph()
                    blocks += Block.Rule
                }

                line.trimStart().startsWith("#") -> {
                    flushParagraph()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks += Block.Heading(level, line.trimStart().drop(level).trim())
                }

                line.trimStart().startsWith(">") -> {
                    flushParagraph()
                    blocks += Block.Quote(line.trimStart().removePrefix(">").trim())
                }

                isPipeTableRow(line) -> {
                    flushParagraph()
                    parsePipeTable(lines, line, blocks)
                }

                isImageLine(line) -> {
                    flushParagraph()
                    val match = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)").find(line)
                    if (match != null) {
                        blocks += Block.Image(match.groupValues[2].trim(), match.groupValues[1])
                    }
                }

                isListItem(line) -> {
                    flushParagraph()
                    val marker = line.trimStart().takeWhile { !it.isWhitespace() }
                    blocks += Block.ListItem(
                        marker = marker,
                        text = line.trimStart().drop(marker.length).trim()
                    )
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line.trim())
                }
            }
        }
        flushParagraph()
        if (inCode && code.isNotBlank()) blocks += Block.Code(code.toString().trimEnd())
    }

    private fun paragraphBlock(text: String): Block {
        // 整段只是一个公式时按独立公式渲染
        if (text.startsWith("$$") && text.endsWith("$$") && text.length > 4) {
            return Block.Math(text.removePrefix("$$").removeSuffix("$$").trim(), true)
        }
        if (text.startsWith("$") && text.endsWith("$") && text.length > 2 &&
            text.count { it == '$' } == 2
        ) {
            return Block.Math(text.removePrefix("$").removeSuffix("$").trim(), false)
        }
        return Block.Paragraph(text)
    }

    private fun isListItem(line: String): Boolean =
        Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(line)

    private fun isImageLine(line: String): Boolean =
        Regex("^\\s*!\\[[^\\]]*\\]\\([^)]+\\)\\s*$").containsMatchIn(line)

    private fun isPipeTableRow(line: String): Boolean =
        line.trimStart().startsWith("|") && line.trimEnd().endsWith("|") &&
            line.count { it == '|' } >= 2

    private fun parsePipeTable(allLines: List<String>, header: String, blocks: MutableList<Block>) {
        val rows = mutableListOf<List<String>>()
        val headerCells = header.trim().trim('|').split('|').map { it.trim() }
        if (headerCells.any { it.isNotBlank() }) rows += headerCells
        val index = allLines.indexOf(header)
        for (i in (index + 1) until allLines.size) {
            val line = allLines[i]
            if (!isPipeTableRow(line)) break
            if (line.trim().trim('|').all { it == '-' || it == ':' || it == ' ' }) continue
            rows += line.trim().trim('|').split('|').map { it.trim() }
        }
        if (rows.isNotEmpty()) blocks += Block.Table(rows)
    }
}
