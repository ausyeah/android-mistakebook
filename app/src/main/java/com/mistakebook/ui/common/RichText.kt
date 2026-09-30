package com.mistakebook.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mistakebook.R
import com.mistakebook.math.MathRenderer
import com.mistakebook.markdown.MarkdownParser

/**
 * Markdown / MinerU 文本渲染：支持标题、加粗斜体、列表、引用、代码、表格、图片与 LaTeX 公式。
 *
 * 公式相关的三条硬规则（都踩过）：
 * 1. **所有公式先渲染完再交给 Text 排版**。InlineTextContent 的 lambda 是在 Text 的布局过程中
 *    执行的，在里面挂 produceState 会导致位图异步到位后占位框不重新测量，公式永远空白。
 * 2. **按字号等比缩放**（目标字号 / 渲染字号），而不是按包围盒高度。这样公式里的字母数字与
 *    正文中文同号，公式之间大小关系也一致。
 * 3. **超过行高的公式单独占一行**。行内内容撑破行框会与上下行重叠；过高的分式必须提成块级。
 */
@Composable
fun RichText(
    text: String,
    mathRenderer: MathRenderer,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val density = LocalDensity.current
    val targetFontPx = with(density) { style.fontSize.toPx() }

    // 整篇一次性把所有公式渲染完（含 $$ 独立公式与行内公式），供各区块直接取用。
    // 走 renderAll 批量接口：一次 WebView 往返出全部公式，
    // 逐条 render 是每个公式 2 次帧等待 + 2 次全画布 draw，长解析会卡到无法滑动。
    val mathKeys = remember(blocks) { collectMathKeys(blocks) }
    val mathCache by produceState<Map<String, com.mistakebook.math.RenderedMath?>>(
        initialValue = emptyMap(),
        mathRenderer,
        mathKeys
    ) {
        if (mathKeys.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        val requests = mathKeys.map { key ->
            val display = key.startsWith("d:")
            val run = key.startsWith("r:")
            val raw = key.substring(2)
            val target = if (run) "\\begin{aligned}$raw\\end{aligned}" else raw
            // key 用原始的，latex 用包过 aligned 的：
            // 缓存键必须是原 key，否则命中不了单条路径写入的同一份缓存
            Triple(key, target, !run && display)
        }
        value = mathRenderer.renderAll(requests)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownParser.Block.Heading -> Text(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                    modifier = Modifier.padding(top = 4.dp)
                )

                is MarkdownParser.Block.Paragraph -> InlineText(
                    text = block.text,
                    mathCache = mathCache,
                    targetFontPx = targetFontPx,
                    style = style,
                    color = color
                )

                is MarkdownParser.Block.ListItem -> Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = block.marker,
                        style = style,
                        color = color,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    InlineText(
                        text = block.text,
                        mathCache = mathCache,
                        targetFontPx = targetFontPx,
                        style = style,
                        color = color,
                        modifier = Modifier.weight(1f)
                    )
                }

                is MarkdownParser.Block.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(Modifier.width(8.dp))
                    InlineText(
                        text = block.text,
                        mathCache = mathCache,
                        targetFontPx = targetFontPx,
                        style = style.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }

                is MarkdownParser.Block.Code -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(8.dp)
                )

                is MarkdownParser.Block.Table -> TableBlock(block)

                is MarkdownParser.Block.Image -> AsyncImage(
                    model = block.path,
                    contentDescription = block.alt,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                is MarkdownParser.Block.Math -> MathImage(
                    latex = block.latex,
                    rendered = mathCache["d:${block.latex}"],
                    targetFontPx = targetFontPx,
                    display = true,
                    centered = true,
                    color = color
                )

                MarkdownParser.Block.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

private fun collectMathKeys(blocks: List<MarkdownParser.Block>): List<String> {
    val keys = linkedSetOf<String>()
    blocks.forEach { block ->
        when (block) {
            is MarkdownParser.Block.Math -> keys += "d:${block.latex}"
            is MarkdownParser.Block.Paragraph -> keys += segmentMathKeys(block.text)
            is MarkdownParser.Block.ListItem -> keys += segmentMathKeys(block.text)
            is MarkdownParser.Block.Quote -> keys += segmentMathKeys(block.text)
            else -> Unit
        }
    }
    return keys.toList()
}

/** 与 InlineRow 保持一致：逐个行内公式单独作为 key。 */
private fun segmentMathKeys(text: String): List<String> =
    parseInline(text).filterIsInstance<InlineSpan.Math>()
        .map { "i:${it.latex}" }

@Composable
private fun TableBlock(block: MarkdownParser.Block.Table) {
    val columnCount = block.rows.maxOf { it.size }.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        block.rows.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until columnCount) {
                    Text(
                        text = row.getOrElse(column) { "" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (rowIndex == 0) FontWeight.Medium else null,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    )
                }
            }
            if (rowIndex < block.rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 一张公式图（独立成行）。display=true 用于 $$...$$，false 用于被提成的过高行内公式。 */
@Composable
private fun MathImage(
    latex: String,
    rendered: com.mistakebook.math.RenderedMath?,
    targetFontPx: Float,
    display: Boolean,
    centered: Boolean,
    color: Color
) {
    val image = rendered?.bitmap
    if (image != null) {
        val density = LocalDensity.current
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxWidthPx = constraints.maxWidth.toFloat()
                val ratio = if (display) DISPLAY_MATH_FONT_RATIO else INLINE_MATH_FONT_RATIO
                var scale = targetFontPx * ratio / rendered.fontPx.coerceAtLeast(1f)
                // 放不下只做等比缩小，绝不裁剪（裁剪会把公式切掉一半）
                val naturalW = image.width * scale
                if (naturalW > maxWidthPx && naturalW > 0f) scale *= maxWidthPx / naturalW
                val w = (image.width * scale).coerceAtLeast(1f)
                val h = (image.height * scale).coerceAtLeast(1f)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart
                ) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = latex,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.size(
                            with(density) { w.toDp() },
                            with(density) { h.toDp() }
                        )
                    )
                }
            }
        }
    } else {
        Text(
            text = latex,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}


/**
 * 备注编辑器：看题时直接记思路。
 * 有内容时直接显示（走 RichText，公式也能渲染），点一下展开编辑；收起或确认时落库。
 */
@Composable
fun NoteEditor(
    note: String,
    mathRenderer: MathRenderer,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(note.isBlank()) }
    var draft by remember(note) { mutableStateOf(note) }

    if (!editing && note.isNotBlank()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { editing = true; draft = note }
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(12.dp)
        ) {
            RichText(
                text = note,
                mathRenderer = mathRenderer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                onChange(it)
            },
            placeholder = { Text(stringResource(R.string.edit_note_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 10
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (note.isNotBlank()) {
                TextButton(onClick = {
                    draft = note
                    onChange(note)
                    onCommit()
                    editing = false
                }) { Text(stringResource(R.string.action_cancel)) }
            }
            TextButton(onClick = {
                onCommit()
                editing = false
            }) { Text(stringResource(R.string.action_confirm)) }
        }
    }
}

/**
 * 行内排版：文字与公式在同一个文本流里。
 *
 * 行高统一放大到能容下最常见的分式（约 2 倍字号），这样带公式的行不会和上下行重叠；
 * 更高的公式（长积分、大分式）按行高等比缩小，宁可小一点也不重叠。
 *
 * **宽度必须约束**：行内公式的占位框宽度一旦超过屏幕，Compose 不会裁剪也不会换行，
 * 公式直接被切掉右半截（用户看到「公式跑到屏幕外」）。
 * 所以每个公式都按可用宽度等比压缩——宁可变小，也不能缺笔画。
 */
@Composable
private fun InlineRow(
    spans: List<InlineSpan>,
    mathCache: Map<String, com.mistakebook.math.RenderedMath?>,
    targetFontPx: Float,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val lineHeightPx = targetFontPx * INLINE_LINE_BOX_RATIO

    BoxWithConstraints(modifier = modifier) {
        // 可用宽度。留 2dp 余量，避免正好贴边时被父容器裁掉 1px。
        val availableWidthPx = (constraints.maxWidth - with(density) { 2.dp.toPx() })
            .coerceAtLeast(targetFontPx * 2f)

        val inlineContent = remember(spans, mathCache, lineHeightPx, availableWidthPx, targetFontPx) {
            buildMap<String, InlineTextContent> {
                spans.forEachIndexed { index, span ->
                    when (span) {
                        is InlineSpan.Math -> {
                            val rendered = mathCache["i:${span.latex}"]
                            val image = rendered?.bitmap
                            val scale = if (rendered == null) {
                                1f
                            } else {
                                targetFontPx / rendered.fontPx.coerceAtLeast(1f)
                            }
                            val bw = image?.width?.toFloat() ?: targetFontPx
                            val bh = image?.height?.toFloat() ?: targetFontPx
                            var w = bw * scale
                            var h = bh * scale
                            // 先按宽度压：超宽公式等比缩到刚好放得下
                            if (w > availableWidthPx && w > 0f) {
                                val k = availableWidthPx / w
                                w *= k
                                h *= k
                            }
                            // 再按行高压：不让公式高过行框，避免和上下行文字重叠
                            if (h > lineHeightPx) {
                                val k = lineHeightPx / h
                                w *= k
                                h *= k
                            }
                            w = w.coerceAtLeast(targetFontPx * 0.2f)
                            h = h.coerceIn(targetFontPx * 0.6f, lineHeightPx)
                            put(
                                "math$index",
                                InlineTextContent(
                                    placeholder = Placeholder(
                                        width = with(density) { w.toSp() },
                                        height = with(density) { h.toSp() },
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom
                                    )
                                ) { _ ->
                                    if (image != null) {
                                        Image(
                                            bitmap = image.asImageBitmap(),
                                            contentDescription = span.latex,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(
                                                with(density) { w.toDp() },
                                                with(density) { h.toDp() }
                                            )
                                        )
                                    } else {
                                        Text(
                                            text = span.latex,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1
                                        )
                                    }
                                }
                            )
                        }

                        is InlineSpan.Image -> put(
                            "img$index",
                            InlineTextContent(
                                placeholder = Placeholder(
                                    width = 120.sp,
                                    height = 60.sp,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom
                                )
                            ) { _ ->
                                AsyncImage(
                                    model = span.path,
                                    contentDescription = span.alt,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        )

                        is InlineSpan.Text -> Unit
                    }
                }
            }
        }
        val annotated = remember(spans) {
            buildAnnotatedString {
                spans.forEachIndexed { index, span ->
                    when (span) {
                        is InlineSpan.Text -> withStyle(
                            SpanStyle(
                                fontWeight = if (span.bold) FontWeight.Bold else null,
                                fontStyle = if (span.italic) FontStyle.Italic else null,
                                fontFamily = if (span.code) FontFamily.Monospace else null
                            )
                        ) { append(span.text) }

                        is InlineSpan.Math -> appendInlineContent("math$index", "公式")

                        is InlineSpan.Image -> appendInlineContent("img$index", "图片")
                    }
                }
            }
        }
        Text(
            text = annotated,
            // 行高放大到能容下行内公式，否则带公式的行会压到上下行
            style = style.copy(lineHeight = with(density) { lineHeightPx.toSp() }),
            color = color,
            modifier = Modifier.fillMaxWidth(),
            inlineContent = inlineContent
        )
    }
}

/** 段落入口：文字与公式在同一个文本流里排版，不拆块、不逐公式占行。 */
@Composable
private fun InlineText(
    text: String,
    mathCache: Map<String, com.mistakebook.math.RenderedMath?>,
    targetFontPx: Float,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val spans = remember(text) { parseInline(text) }
    InlineRow(
        spans = spans,
        mathCache = mathCache,
        targetFontPx = targetFontPx,
        style = style,
        color = color,
        modifier = modifier
    )
}

private sealed interface InlineSpan {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    ) : InlineSpan

    data class Math(val latex: String) : InlineSpan
    data class Image(val path: String, val alt: String) : InlineSpan
}

private val inlineMath = Regex("\\$\\$([^$]+)\\$\\$|\\$([^$]+)\\$")

/**
 * 行内公式高度上限（相对字号倍数）。
 * 超过这个高度就提成独立行：Compose 的 inline content 不会撑高行框，
 * 公式一旦高过行框就会和上下行文字叠在一起。1.5 左右是行高的安全边界。
 */
private const val INLINE_MATH_MAX_HEIGHT_RATIO = 1.5f

/** 独立行公式相对正文的字号倍数。 */
private const val DISPLAY_MATH_FONT_RATIO = 0.92f

/** 被提成独立行的行内公式，相对正文的字号倍数。 */
private const val INLINE_MATH_FONT_RATIO = 0.95f

/** 行高（相对字号倍数）。要能容下常见分式，否则带公式的行会和上下行文字重叠。 */
private const val INLINE_LINE_BOX_RATIO = 1.9f

private fun parseInline(input: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    var cursor = 0
    var bold = false
    var italic = false
    var code = false
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isNotEmpty()) {
            spans += InlineSpan.Text(buffer.toString(), bold, italic, code)
            buffer.clear()
        }
    }

    while (cursor < input.length) {
        val rest = input.substring(cursor)
        when {
            rest.startsWith("**") -> {
                flush()
                bold = !bold
                cursor += 2
            }

            rest.startsWith("`") -> {
                flush()
                code = !code
                cursor += 1
            }

            rest.startsWith("$$") -> {
                val end = rest.indexOf("$$", 2)
                if (end > 2) {
                    flush()
                    spans += InlineSpan.Math(rest.substring(2, end).trim())
                    cursor += end + 2
                } else {
                    buffer.append('$')
                    cursor++
                }
            }

            rest.startsWith("$") -> {
                val end = rest.indexOf('$', 1)
                if (end > 1) {
                    flush()
                    spans += InlineSpan.Math(rest.substring(1, end).trim())
                    cursor += end + 1
                } else {
                    buffer.append('$')
                    cursor++
                }
            }

            rest.startsWith("![") -> {
                val match = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)").find(rest)
                if (match != null) {
                    flush()
                    spans += InlineSpan.Image(match.groupValues[2].trim(), match.groupValues[1])
                    cursor += match.value.length
                } else {
                    buffer.append(input[cursor])
                    cursor++
                }
            }

            rest.startsWith("*") && !rest.startsWith("**") -> {
                flush()
                italic = !italic
                cursor += 1
            }

            else -> {
                buffer.append(input[cursor])
                cursor++
            }
        }
    }
    flush()
    return spans
}
