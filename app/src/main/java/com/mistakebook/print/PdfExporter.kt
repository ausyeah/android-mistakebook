package com.mistakebook.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.displayTitle
import com.mistakebook.data.local.figurePaths
import com.mistakebook.data.local.knowledgePoints
import com.mistakebook.data.local.options
import com.mistakebook.data.local.printImagePath
import com.mistakebook.math.RenderedMath
import com.mistakebook.ui.common.SubjectPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 含行内公式的一行，至少要多高才装得下。
 *
 * ## 为什么要单独抽出来
 * 这个算式写错过一次，而且错得很隐蔽：原来按 `mathHeight * 1.25` 给行高，
 * 那是**从行顶**量的。公式实际画在
 * `[baseline - h*0.18, baseline + h*0.82]`，基线又在 `lineTop + ascentUp`，
 * 所以公式底边落在 `lineTop + ascentUp + 0.82*h`——**差了一整个 ascent**。
 * h=20pt、ascentUp=13 时只给到 25pt，公式底边却在 29.4pt，照样压下一行。
 *
 * 抽成顶层函数是为了能直接写单测锁住这条不变量：
 * 返回值必须 >= 公式底边相对行顶的位置。
 *
 * @param ascentUp 基线以上高度，**正数**（Android 的 `fontMetrics.ascent` 是负数，要取反）
 * @param mathHeightPt 公式位图按目标字号缩放后的高度
 * @param padding 行内留白比例
 */
internal fun requiredLineHeightForMath(
    ascentUp: Float,
    mathHeightPt: Float,
    padding: Float
): Float = ascentUp + mathHeightPt * (1f - PdfExporter.MATH_BOTTOM_RATIO + padding)

/**
 * A4 打印 PDF 引擎（PRD 第 8 节）：零依赖，系统 PdfDocument + StaticLayout。
 *
 * 版面常量：A4 纵向 595×842pt，页边距 42pt，可用宽 511pt；
 * 可用高度 = 842 - 42*2 - 页眉 26 - 页脚 20。
 * 页脚「第 n / m 页」需要总页数，因此先跑一遍不计页眉页脚的排版，再正式出图。
 */
class PdfExporter(
    private val context: Context,
    private val mathRenderer: com.mistakebook.math.MathRenderer
) {

    data class Options(
        val includeImage: Boolean = false,
        val showAnswer: Boolean = false,
        val blankRedoMode: Boolean = true,
        val blankHeightPt: Int = 100
    )

    data class Result(
        val file: File,
        val pageCount: Int,
        val skipped: List<String>
    )

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

    suspend fun export(
        questions: List<Question>,
        options: Options,
        target: File,
        onProgress: (Int, Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val built = questions.mapIndexedNotNull { index, question ->
            // 单题异常不影响整批：跳过后在结果里列出
            // index 是打印清单里的连续序号（1 起），数据库 id 会跳号，打印时用户靠它核对有没有漏题
            try {
                buildCard(question, options, index + 1)
            } catch (error: Throwable) {
                android.util.Log.e("PdfExporter", "题目 ${question.id} 排版失败", error)
                null
            }
        }
        val skipped = questions.filterNot { question -> built.any { it.questionId == question.id } }
            .map { it.stem.take(20) }

        // 第一遍量页数（写临时文件，随后删除）
        val probe = File(context.cacheDir, "pdf_probe_${System.currentTimeMillis()}.pdf")
        val totalPages = render(probe, built, options, totalPages = 1) { _, _ -> }
        probe.delete()

        target.parentFile?.mkdirs()
        render(target, built, options, totalPages, onProgress)

        Result(file = target, pageCount = totalPages, skipped = skipped)
    }

    // ===== 排版主循环 =====

    private fun render(
        target: File,
        cards: List<Card>,
        options: Options,
        totalPages: Int,
        onProgress: (Int, Int) -> Unit
    ): Int {
        val document = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = CONTENT_TOP

        // 两遍排版：重跑前清空上一遍留下的切片状态
        cards.forEach { card -> card.blocks.forEach { it.sliced = 0f } }

        fun startPage() {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(info)
            canvas = page!!.canvas
            drawChrome(canvas!!, pageNumber, totalPages)
            y = CONTENT_TOP
        }

        fun finishPage() {
            page?.let { document.finishPage(it) }
            page = null
        }

        startPage()
        cards.forEachIndexed { index, card ->
            onProgress(index + 1, cards.size)
            var cursor = 0
            while (cursor < card.blocks.size) {
                val block = card.blocks[cursor]
                val blockHeight = block.height - block.sliced
                val space = CONTENT_BOTTOM - y
                if (blockHeight <= space + 0.5f) {
                    block.draw(canvas!!, y, blockHeight)
                    y += blockHeight
                    block.sliced = 0f
                    cursor++
                } else {
                    val slice = if (block.sliceable) drawableHeight(block, space) else 0f
                    if (slice > 0.5f) {
                        block.draw(canvas!!, y, slice)
                        block.sliced += slice
                        y = CONTENT_BOTTOM
                    } else {
                        finishPage()
                        startPage()
                    }
                }
            }
            y += CARD_SPACING
        }
        finishPage()

        target.parentFile?.mkdirs()
        FileOutputStream(target).use { stream -> document.writeTo(stream) }
        document.close()
        return pageNumber
    }

    // ===== 块模型 =====

    internal abstract inner class Block(val height: Float, val sliceable: Boolean = false) {
        var sliced: Float = 0f

        abstract fun draw(canvas: Canvas, top: Float, drawHeight: Float)
    }

    internal inner class TextBlock(
        val questionId: Long,
        val layout: StaticLayout,
        height: Float
    ) : Block(height, sliceable = true) {
        override fun draw(canvas: Canvas, top: Float, drawHeight: Float) {
            val save = canvas.save()
            canvas.translate(MARGIN, top - sliced)
            canvas.clipRect(0f, sliced, USABLE_WIDTH, sliced + drawHeight)
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    internal inner class ImageBlock(
        val questionId: Long,
        private val bitmap: Bitmap?,
        private val drawWidth: Float,
        height: Float
    ) : Block(height) {
        private val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        override fun draw(canvas: Canvas, top: Float, drawHeight: Float) {
            val image = bitmap ?: return
            val left = (PAGE_WIDTH - drawWidth) / 2f
            canvas.drawBitmap(image, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
        }
    }

    /**
     * 重做区：标签 + 若干条书写横线。
     *
     * 布局按「标签独占一行、横线从标签下方开始」计算高度。
     * 之前标签画在 `top + 12`、第一根线在 `top + 28`，但块高只按用户设的
     * `blankHeightPt` 给，重做区调到最小值时标签会压到上一段正文上——
     * 用户看到的正是「重做区三个字盖在最后一行公式上」。
     * 所以这里把标签高度计入块高，并让横线只画在标签下方。
     */
    internal inner class BlankBlock(height: Float) : Block(height) {
        private val linePaint = Paint().apply {
            color = LINE_GRAY
            strokeWidth = 0.5f
        }
        private val labelPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 9f
            color = LABEL_GRAY
        }

        override fun draw(canvas: Canvas, top: Float, drawHeight: Float) {
            val bottom = top + drawHeight
            // 标签在顶部
            val baseline = top + LABEL_BASELINE
            if (baseline < bottom) {
                canvas.drawText("重做区", MARGIN, baseline, labelPaint)
            }
            // 标签下方才是横线，步长从标签底部起算
            val linesTop = top + LABEL_RESERVED
            var lineTop = linesTop + BLANK_LINE_STEP
            while (lineTop < bottom) {
                canvas.drawLine(MARGIN, lineTop, PAGE_WIDTH - MARGIN, lineTop, linePaint)
                lineTop += BLANK_LINE_STEP
            }
        }
    }

    internal inner class ColorBarBlock(height: Float, private val color: Int) : Block(height) {
        private val paint = Paint().apply { this.color = color }

        override fun draw(canvas: Canvas, top: Float, drawHeight: Float) {
            canvas.drawRect(MARGIN, top, PAGE_WIDTH - MARGIN, top + drawHeight, paint)
        }
    }

    internal inner class SpaceBlock(height: Float) : Block(height) {
        override fun draw(canvas: Canvas, top: Float, drawHeight: Float) = Unit
    }

    // ===== 含公式的富文本块 =====

    /** 行内的一个片段：文字或公式位图，x 为该行内的左偏移。 */
    internal sealed interface MathPiece {
        val x: Float

        data class Text(val text: String, override val x: Float) : MathPiece
        data class Image(
            val bitmap: Bitmap,
            override val x: Float,
            val width: Float,
            val height: Float
        ) : MathPiece
    }

    internal data class MathLine(val pieces: List<MathPiece>, val height: Float)

    /**
     * 文字与公式混排的块。公式已是位图，按基线对齐绘制，不会像纯源码那样打印出反斜杠。
     */
    internal inner class MathRichBlock(
        val questionId: Long,
        val lines: List<MathLine>,
        private val textPaint: TextPaint
    ) : Block(lines.sumOf { it.height.toDouble() }.toFloat(), sliceable = true) {

        private val imagePaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        /**
         * 文字度量。
         *
         * ## 注意 Android 的符号约定
         * `Paint.FontMetrics` 的文档原文是：
         * > Remember, Y values increase going down, so those values will be positive,
         * > and values that measure distances going up will be negative.
         *
         * 也就是说 **`ascent` 是负数**（基线以上为负），`descent` 才是正数。
         * 之前这里写 `baseline = lineTop + metrics.ascent`，等于把基线放到了
         * 行框顶边**之上** |ascent| 处，于是每一行都画在自己行框的上方、
         * 逐行向上累积——打印出来就是**整篇文字行行重叠**。
         *
         * [ascentUp] 取相反数得到正的「基线以上高度」，用它定位才是对的。
         */
        private val metrics = textPaint.fontMetrics
        private val ascentUp = -metrics.ascent

        override fun draw(canvas: Canvas, top: Float, drawHeight: Float) {
            val save = canvas.save()
            var y = top - sliced
            for (line in lines) {
                val lineTop = y
                val lineBottom = y + line.height
                // 只画落在可见区间内的行
                if (lineBottom > top - sliced && lineTop < top - sliced + drawHeight) {
                    // 基线 = 行顶 + 基线以上高度（ascentUp 是正数）
                    val baseline = lineTop + ascentUp
                    line.pieces.forEach { piece ->
                        when (piece) {
                            is MathPiece.Text ->
                                canvas.drawText(piece.text, MARGIN + piece.x, baseline, textPaint)

                            is MathPiece.Image -> {
                                // 公式底边略微探出基线：行内公式的常规观感
                                val drawTop = baseline - piece.height * MATH_BOTTOM_RATIO
                                canvas.drawBitmap(
                                    piece.bitmap,
                                    null,
                                    RectF(
                                        MARGIN + piece.x,
                                        drawTop,
                                        MARGIN + piece.x + piece.width,
                                        drawTop + piece.height
                                    ),
                                    imagePaint
                                )
                            }
                        }
                    }
                }
                y += line.height
                if (y >= top - sliced + drawHeight) break
            }
            canvas.restoreToCount(save)
        }
    }

    // ===== 卡片构建 =====

    internal inner class Card(val questionId: Long, val blocks: List<Block>)

    /**
     * 排一张题卡。序号 [index] 是打印清单里的第几题（数据库 id 会跳号，
     * 打印时用户需要连续编号才不会漏题）。
     *
     * 正文里的 LaTeX 会先渲染成位图再与文字混排——直接输出源码在 PDF 里就是一堆
     * 反斜杠和花括号，完全没法看。
     */
    private suspend fun buildCard(question: Question, options: Options, index: Int): Card {
        val blocks = mutableListOf<Block>()
        val subjectName = question.subjectId?.let { subjectNames[it] }
        // 整题公式一次性批量渲染：逐个渲染是每个公式 2 帧等待，
        // 一张十几题的 PDF 要等几十秒
        val preRendered = prerenderMath(question)

        blocks += ColorBarBlock(COLOR_BAR_HEIGHT, SubjectPalette.argbOf(subjectName))
        // 标题高度按实际行数算：知识点多的时候标题会折到两行，
        // 固定 16pt 会把第二行切掉
        val titleLayout = titleLayout(question, subjectName, index)
        blocks += TextBlock(question.id, titleLayout, titleLayout.height.toFloat())
        blocks += SpaceBlock(6f)

        blocks += richBlock(
            question.id, question.stem, STEM_SIZE, 1.25f, TEXT_BLACK, preRendered
        )

        // 附图优先：MinerU 切出的题目图才是题目的图，没有附图才回退打原图
        if (options.includeImage) {
            val figure = question.printImagePath
            if (figure.isNotBlank()) {
                val bitmap = runCatching { BitmapFactory.decodeFile(figure) }.getOrNull()
                if (bitmap != null) {
                    val width = minOf(USABLE_WIDTH * 0.45f, bitmap.width.toFloat())
                    val height = width * bitmap.height / bitmap.width
                    blocks += ImageBlock(question.id, bitmap, width, height)
                    val isFigure = question.figurePaths.isNotEmpty()
                    blocks += TextBlock(
                        question.id,
                        textLayout(
                            if (isFigure) "题目附图" else "原题照片",
                            CAPTION_SIZE, 1f, LABEL_GRAY
                        ),
                        CAPTION_LINE_HEIGHT
                    )
                }
            }
        }

        question.options.forEach { option ->
            blocks += richBlock(
                question.id,
                "${option.label}. ${option.text}",
                OPTION_SIZE, 1.3f, TEXT_BLACK, preRendered
            )
        }

        if (options.showAnswer) {
            if (question.answer.isNotBlank()) {
                blocks += SpaceBlock(6f)
                blocks += richBlock(
                    question.id, "答案：${question.answer}",
                    ANSWER_SIZE, 1.3f, TEXT_BLACK, preRendered
                )
            }
            if (question.analysis.isNotBlank()) {
                blocks += SpaceBlock(4f)
                blocks += richBlock(
                    question.id, "解析：${question.analysis}",
                    ANALYSIS_SIZE, 1.3f, ANALYSIS_GRAY, preRendered
                )
            }
        }

        if (options.blankRedoMode) {
            // 上边距 + 标签预留，保证「重做区」不会压到上一段正文
            blocks += SpaceBlock(14f)
            blocks += BlankBlock(options.blankHeightPt.toFloat() + LABEL_RESERVED)
        }

        blocks += ColorBarBlock(SEPARATOR_HEIGHT, LINE_GRAY)
        return Card(question.id, blocks)
    }

    /**
     * 把一段可能含 `$...$` 公式的文本排成块：公式渲染成位图，与文字按行基线混排。
     *
     * 不能直接用 StaticLayout 输出源码（用户看到的是一堆 LaTeX），
     * 也不能一行一个公式（推导过程会断得没法读）——所以这里做「同一行内混排」：
     * 按词切分文字、公式作为不可分割的原子，按可用宽度贪心换行。
     */
    private suspend fun richBlock(
        questionId: Long,
        text: String,
        sizePt: Float,
        lineSpacing: Float,
        color: Int,
        preRendered: Map<String, RenderedMath?>
    ): Block {
        if (text.isBlank()) return SpaceBlock(0f)
        val tokens = tokenizeWithMath(text)
        if (tokens.none { it is RichToken.MathToken }) {
            // 没有公式就走原来的纯文本路径，排版质量更好
            return TextBlock(
                questionId,
                textLayout(text, sizePt, lineSpacing, color),
                layoutHeight(text, sizePt, lineSpacing)
            )
        }
        return MathRichBlock(
            questionId = questionId,
            lines = buildMathLines(tokens, sizePt, lineSpacing, color, preRendered),
            textPaint = textPaintFor(sizePt, color)
        )
    }

    /**
     * 预先批量渲染整张题目里的所有公式。
     *
     * 不做预渲染的话，[richBlock] 会在换行计算的过程中逐个调 `render`，
     * 每个公式 2 次帧等待 + 2 次全画布 draw。整张题目有十几个公式时，
     * 「生成中」要卡十几秒。预渲染把帧等待从 O(公式数) 降到 O(1)。
     *
     * @return latex -> 位图。key 用「display:latex」区分块级与行内，
     *   两者排版参数不同，共用一个 key 会拿到错的图。
     */
    private suspend fun prerenderMath(question: Question): Map<String, RenderedMath?> {
        val sources = buildList {
            fun scan(text: String) {
                tokenizeWithMath(text).forEach { token ->
                    if (token is RichToken.MathToken) add(token)
                }
            }
            scan(question.stem)
            question.options.forEach { scan("${it.label}. ${it.text}") }
            scan(question.answer)
            scan(question.analysis)
        }
        if (sources.isEmpty()) return emptyMap()
        val requests = sources
            .distinct()
            .map { Triple("${if (it.display) "d" else "i"}:${it.latex}", it.latex, it.display) }
        val rendered = mathRenderer.renderAll(requests)
        // 去掉 display/inline 前缀：同一个 latex 在 PDF 里两种排版参数一致，
        // 按 latex 做 key 就够了，调用方查表也简单
        return rendered.entries.associate { entry ->
            val key = entry.key
            val latex = if (key.startsWith("d:") || key.startsWith("i:")) {
                key.substring(2)
            } else {
                key
            }
            Pair(latex, entry.value)
        }
    }

    private fun textPaintFor(sizePt: Float, color: Int): TextPaint = TextPaint().apply {
        isAntiAlias = true
        isSubpixelText = true
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = sizePt
        this.color = color
    }

    /**
     * 把 token 序列按可用宽度贪心换行，每个公式先渲染成位图。
     * 公式按「渲染字号 = 正文字号」等比缩放，保证公式里的字母数字与正文同号。
     */
    private suspend fun buildMathLines(
        tokens: List<RichToken>,
        sizePt: Float,
        lineSpacing: Float,
        color: Int,
        preRendered: Map<String, RenderedMath?>
    ): List<MathLine> {
        val paint = textPaintFor(sizePt, color)
        // Android 的 ascent 是**负数**（基线以上为负），取反得到正的「基线以上高度」。
        // 定位基线、算行高下限都要用它，写错符号会让整篇文字行行重叠。
        val ascentUp = -paint.fontMetrics.ascent
        // 行高下限：必须容得下带分式的公式，否则分式被压扁、分数线糊成一条。
        // 同时不能小于文字自身的 ascent+descent——否则纯文字行自己就压线。
        val baseLineHeight = maxOf(sizePt * lineSpacing, ascentUp + paint.fontMetrics.descent)
        val lines = mutableListOf<MathLine>()
        var pieces = mutableListOf<MathPiece>()
        var x = 0f
        var lineHasContent = false

        var currentHeight = baseLineHeight

        fun flush() {
            if (pieces.isNotEmpty() || lineHasContent) {
                lines += MathLine(pieces.toList(), currentHeight)
            }
            pieces = mutableListOf()
            x = 0f
            lineHasContent = false
            currentHeight = baseLineHeight
        }

        tokens.forEach { token ->
            when (token) {
                is RichToken.TextToken -> {
                    val text = token.text
                    val w = paint.measureText(text)
                    if (x + w > USABLE_WIDTH && lineHasContent) flush()
                    pieces += MathPiece.Text(text, x)
                    x += w
                    lineHasContent = true
                }

                is RichToken.MathToken -> {
                    // 优先用整题预渲染的结果；缺失时退回单条渲染兜底
                    val rendered = preRendered[token.latex]
                        ?: mathRenderer.render(token.latex, token.display)
                    if (rendered == null) {
                        // 渲染失败就把源码当普通文字排，至少能看出题目原样
                        val text = "\$${token.latex}\$"
                        val w = paint.measureText(text)
                        if (x + w > USABLE_WIDTH && lineHasContent) flush()
                        pieces += MathPiece.Text(text, x)
                        x += w
                        lineHasContent = true
                    } else {
                        val image = rendered.bitmap
                        // 与屏幕端**共用同一套缩放规则**（com.mistakebook.math.MathLayout）。
                        //
                        // 原来这里只判断「放不下就换行」，**没有等比缩小**，
                        // 于是比可打印宽度还宽的长公式会直接冲出纸面右边被切掉；
                        // 而同样的公式在 App 里却会被缩小——两边表现不一致。
                        val fitted = com.mistakebook.math.MathLayout.fit(
                            bitmapW = image.width,
                            bitmapH = image.height,
                            srcFontPx = rendered.fontPx,
                            // 字母与正文同大；总高度封顶，天生高的结构整体缩小。
                            // 与屏幕端（RichText）共用同一套规则，两边表现一致。
                            targetFontPx = com.mistakebook.math.MathLayout.letterTargetPx(sizePt),
                            maxWidthPx = USABLE_WIDTH,
                            maxHeightPx = if (token.display) {
                                com.mistakebook.math.MathLayout.displayMathMaxHeightPx(sizePt)
                            } else {
                                com.mistakebook.math.MathLayout.inlineMathMaxHeightPx(sizePt)
                            }
                        )
                        val w = fitted.width
                        val h = fitted.height
                        // 放得下就随行，放不下先换行；换行后仍放不下（公式本身超宽）
                        // 上面已经把它缩到 USABLE_WIDTH 以内，所以这里必然放得下。
                        if (x + w > USABLE_WIDTH && lineHasContent) flush()
                        pieces += MathPiece.Image(image, x, w, h)
                        x += w
                        lineHasContent = true
                        // 本行要容得下这个公式，而且**必须从基线往下量**。
                        // 原来用 `h * 1.25` 是从**行顶**量的，差了一整个 ascent，
                        // 公式照样压到下一行。见 requiredLineHeightForMath 的注释。
                        val needed = requiredLineHeightForMath(ascentUp, h, MATH_LINE_PADDING)
                        if (needed > currentHeight) currentHeight = needed
                    }
                }
            }
        }
        flush()
        return lines
    }

    private sealed interface RichToken {
        data class TextToken(val text: String) : RichToken
        data class MathToken(val latex: String, val display: Boolean) : RichToken
    }

    /** 按 `$...$` 切出公式，其余按「中文逐字 / 西文按词」切分，便于换行。 */
    private fun tokenizeWithMath(input: String): List<RichToken> {
        val out = mutableListOf<RichToken>()
        val buffer = StringBuilder()
        var i = 0
        fun flushText() {
            if (buffer.isEmpty()) return
            tokenizePlain(buffer.toString()).forEach { out += it }
            buffer.clear()
        }
        while (i < input.length) {
            val ch = input[i]
            if (ch == '$') {
                val display = input.startsWith("$$", i)
                val open = if (display) 2 else 1
                val close = if (display) "$$" else "$"
                val end = input.indexOf(close, i + open)
                if (end > i + open) {
                    flushText()
                    val body = input.substring(i + open, end).trim()
                    if (body.isNotEmpty()) {
                        out += RichToken.MathToken(body, display)
                    }
                    i = end + close.length
                    continue
                }
            }
            buffer.append(ch)
            i++
        }
        flushText()
        return out
    }

    private fun tokenizePlain(text: String): List<RichToken> {
        val out = mutableListOf<RichToken>()
        val word = StringBuilder()
        fun flushWord() {
            if (word.isNotEmpty()) {
                out += RichToken.TextToken(word.toString())
                word.clear()
            }
        }
        text.forEach { ch ->
            when {
                ch.isWhitespace() -> {
                    flushWord()
                    out += RichToken.TextToken(ch.toString())
                }
                // 中文与全角标点逐字断行；西文数字按词，避免把单词劈开
                ch.code >= 0x2E80 -> {
                    flushWord()
                    out += RichToken.TextToken(ch.toString())
                }

                else -> word.append(ch)
            }
        }
        flushWord()
        return out
    }

    private var subjectNames: Map<Long, String> = emptyMap()

    fun setSubjectNames(names: Map<Long, String>) {
        subjectNames = names
    }

    /**
     * 卡片标题行：`序号 · 学科 · 错因 · 难度 · 知识点`。
     * [index] 是打印清单里的连续序号——数据库 id 会跳号，用户靠它核对有没有漏题。
     */
    private fun titleLayout(
        question: Question,
        subjectName: String?,
        index: Int
    ): StaticLayout {
        val safe = question.difficulty.coerceIn(1, 5)
        val stars = "★".repeat(safe) + "☆".repeat(5 - safe)
        val points = question.knowledgePoints.takeIf { it.isNotEmpty() }?.joinToString("、").orEmpty()
        val title = buildString {
            append(index).append(". ")
            // 标题也可能是大模型生成的，可能含公式，一并剥掉
            append(plainText(question.displayTitle))
            append(" · ").append(subjectName ?: "未分类")
            append(" · ").append(question.errorReason.label)
            append(" · ").append(stars)
            if (points.isNotBlank()) append(" · ").append(plainText(points))
        }
        return textLayout(title, TITLE_SIZE, 1.2f, TEXT_BLACK)
    }

    /**
     * 标题行里**剥掉 LaTeX**，只留可读文字。
     *
     * 知识点字段经常含公式（`对数不等式 $\ln(1+t)$`、`$\int_0^1$`）。
     * 标题行用的是 StaticLayout，**不做公式渲染**——原来直接把 `$\ln(1+t)$`
     * 整个打出来，纸上是满行反斜杠。
     *
     * 为什么不把标题行也换成 MathRichBlock：标题行允许换行、且知识点里的公式
     * 本身信息量不大（`$\int_0^1$` 剥成「∫」不如不显示）。
     * 题目正文和解析里的公式才值得渲染，那里走 richBlock。
     */
    private fun plainText(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '$') {
                val display = raw.startsWith("$$", i)
                val open = if (display) 2 else 1
                val close = if (display) "$$" else "$"
                val end = raw.indexOf(close, i + open)
                if (end > i + open) {
                    i = end + close.length
                    continue
                }
            }
            append(ch)
            i++
        }
    }.replace(WHITESPACE_RUN, " ").trim()

    private fun textLayout(
        text: String,
        sizePt: Float,
        lineSpacing: Float,
        color: Int
    ): StaticLayout {
        val paint = TextPaint().apply {
            isAntiAlias = true
            isSubpixelText = true
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = sizePt
            this.color = color
        }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, USABLE_WIDTH.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacing)
            .setIncludePad(false)
            .build()
    }

    private fun layoutHeight(text: String, sizePt: Float, lineSpacing: Float): Float =
        textLayout(text, sizePt, lineSpacing, TEXT_BLACK).height.toFloat()

    /** 当前页还能放下多少内容：按行累计，超出则停。 */
    private fun drawableHeight(block: Block, space: Float): Float {
        if (!block.sliceable) return 0f
        return when (block) {
            is TextBlock -> {
                val layout = block.layout
                var usable = 0f
                for (line in 0 until layout.lineCount) {
                    val bottom = layout.getLineBottom(line).toFloat()
                    if (bottom - block.sliced <= space) {
                        usable = bottom - block.sliced
                    } else {
                        break
                    }
                }
                usable
            }
            // 公式块按整行切：宁可少放几行，也不要把一行劈成两半
            is MathRichBlock -> {
                var usable = 0f
                for (line in block.lines) {
                    if (line.height - block.sliced + usable <= space) {
                        usable += line.height
                    } else {
                        break
                    }
                }
                usable
            }

            else -> 0f
        }
    }

    private fun drawChrome(canvas: Canvas, pageNumber: Int, totalPages: Int) {
        val today = LocalDate.now().format(dateFormatter)
        val headerPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 10.5f
            color = LABEL_GRAY
        }
        canvas.drawText("错题本", MARGIN, CONTENT_TOP - 10f, headerPaint)
        canvas.drawText(
            today,
            PAGE_WIDTH - MARGIN - headerPaint.measureText(today),
            CONTENT_TOP - 10f,
            headerPaint
        )
        val footerPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 9.5f
            color = LABEL_GRAY
        }
        val footerText = "第 $pageNumber / $totalPages 页"
        canvas.drawText(
            footerText,
            (PAGE_WIDTH - footerPaint.measureText(footerText)) / 2f,
            PAGE_HEIGHT - MARGIN + 6f,
            footerPaint
        )
    }

    internal companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 42f
        const val USABLE_WIDTH = PAGE_WIDTH - MARGIN * 2
        const val HEADER_HEIGHT = 26f
        const val FOOTER_HEIGHT = 20f
        const val CONTENT_TOP = MARGIN + HEADER_HEIGHT
        const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT
        const val CARD_SPACING = 18f
        const val COLOR_BAR_HEIGHT = 3f
        const val SEPARATOR_HEIGHT = 0.5f
        const val BLANK_LINE_STEP = 28f

        /** 「重做区」标签占用的垂直空间（基线位置 + 标签下方留白）。 */
        const val LABEL_BASELINE = 9f
        const val LABEL_RESERVED = 18f
        const val STEM_SIZE = 14f
        const val OPTION_SIZE = 12.5f
        const val ANSWER_SIZE = 12f
        const val ANALYSIS_SIZE = 12f
        const val CAPTION_SIZE = 8.5f
        const val TITLE_SIZE = 11f
        const val CAPTION_LINE_HEIGHT = 12f

        /** 连续空白压成一个：剥掉公式后常留下多余空格。 */
        val WHITESPACE_RUN = Regex("\\s+")

        /**
         * 公式混排的两个比例。
         * BASELINE_RATIO：文字基线在行高里的位置（0.8 接近常规行距的视觉效果）。
         * MATH_BOTTOM_RATIO：公式底部落在基线下方多少——行内公式应略微下沉，
         * 底边压基线会显得整个公式往上飘。
         */
        const val BASELINE_RATIO = 0.8f
        const val MATH_BOTTOM_RATIO = 0.18f

        /** 含公式的行在公式高度之外额外留白（比例），避免分式与上下行贴死。 */
        const val MATH_LINE_PADDING = 0.25f

        val LINE_GRAY = 0xFFDDDDDD.toInt()
        val LABEL_GRAY = 0xFF8A8A8A.toInt()
        val ANALYSIS_GRAY = 0xFF555555.toInt()
        val TEXT_BLACK = 0xFF1B1F26.toInt()
    }
}
