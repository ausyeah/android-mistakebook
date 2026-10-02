package com.mistakebook.print

import android.graphics.Bitmap
import android.util.Log
import com.mistakebook.math.MathRenderer
import com.mistakebook.math.RenderedMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DOCX 导出。**手写 zip + OOXML，零依赖**（项目规则禁止引 Apache POI）。
 *
 * ## docx 是什么
 *
 * 一个 zip 包，最少四样东西：
 * `[Content_Types].xml`（声明各部件类型）、`_rels/.rels`（包级关系）、
 * `word/document.xml`（正文）、以及有图时的 `word/_rels/document.xml.rels` 与 `word/media/` 下的图片。
 *
 * ## 公式：先试原生 OMML，再回退位图
 *
 * - OMML 是 Word / WPS 的**原生公式**，双击能编辑、能跟正文一起重排。
 * - 但 [LatexToOmml] 只覆盖常见子集；`\begin{aligned}`、矩阵这类返回 null。
 * - 回退是把公式渲染成 PNG 嵌进去，`alt` 里留 LaTeX 原文。
 *
 * **逐个公式**独立决定，不是一个失败就整份文档失败。
 * 反向用例：OMML 转换一旦吐出结构错误的 XML，Word 打开时会报「文档已损坏」——
 * 整份文档作废，比公式丑严重得多。
 */
class DocxExporter(private val mathRenderer: MathRenderer) {

    suspend fun export(doc: ExportDoc, target: File): ExportResult =
        withContext(Dispatchers.IO) {
            val media = mutableListOf<MediaEntry>()
            val skipped = mutableListOf<String>()

            val body = buildString {
                doc.cards.forEach { card ->
                    try {
                        append(renderCard(card, doc, media))
                    } catch (error: Throwable) {
                        // 单题坏掉不拖垮整批
                        Log.e("DocxExporter", "题目 ${card.questionId} 渲染失败", error)
                        skipped += card.header.take(40)
                    }
                }
            }

            target.parentFile?.mkdirs()
            writeZip(target, media, body)
            ExportResult(file = target, skipped = skipped)
        }

    // ------------------------------------------------------------ 正文

    private suspend fun renderCard(
        card: ExportCard,
        doc: ExportDoc,
        media: MutableList<MediaEntry>
    ): String = buildString {
        append(paragraph(escapeXml(card.header), bold = true, sizeHalfPt = 30))
        append(paragraph(escapeXml(renderTokens(card.stem, media)), sizeHalfPt = 24))

        // 附图优先，打原图兜底——与 PDF、HTML 两条路保持一致
        if (doc.options.includeImage && card.imagePath.isNotBlank()) {
            File(card.imagePath).takeIf { it.exists() }?.let { file ->
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes != null && bytes.size <= MAX_IMAGE_BYTES) {
                    val rid = addMedia(media, bytes, detectImageKind(bytes))
                    append(imageParagraph(rid, bytes, 2_400_000L))
                    append(
                        paragraph(
                            escapeXml(if (card.imageIsFigure) "题目附图" else "原题照片"),
                            sizeHalfPt = 18,
                            colorHex = "808080",
                            align = "center"
                        )
                    )
                }
            }
        }

        card.options.forEach { option ->
            val text = renderTokens(tokenizeWithMath(option.text), media)
            append(paragraph("${escapeXml(option.label)}. $text", sizeHalfPt = 22))
        }

        if (card.hasAnswer) {
            append(paragraph("答案：${escapeXml(renderTokens(card.answer, media))}", sizeHalfPt = 22))
        }
        if (card.hasAnalysis) {
            append(
                paragraph(
                    "解析：${escapeXml(renderTokens(card.analysis, media))}",
                    sizeHalfPt = 22,
                    colorHex = "444444"
                )
            )
        }

        // 留白重做区：Word 里就是一个空段落，高度给够
        if (doc.options.blankRedoMode) {
            repeat(BLANK_LINES) { append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr></w:p>") }
        }
        append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:color=\"DDDDDD\"/></w:pBdr></w:pPr></w:p>")
    }

    /**
     * token 序列 -> 段落内容。
     *
     * 公式**不能**塞进 `w:t`——那会让 Word 把它当普通文字，
     * 显示成一串 `<m:f>` 之类的标签。所以公式单独生成 `m:oMath` 兄弟节点。
     */
    private suspend fun renderTokens(tokens: List<RichToken>, media: MutableList<MediaEntry>): String =
        buildString {
            tokens.forEach { token ->
                when (token) {
                    is RichToken.TextToken -> append(escapeXml(token.text))
                    is RichToken.MathToken -> {
                        append(ommlOrImage(token, media))
                        append(' ')
                    }
                }
            }
        }

    /** 原生 OMML，失败则内嵌位图。 */
    private suspend fun ommlOrImage(
        token: RichToken.MathToken,
        media: MutableList<MediaEntry>
    ): String {
        val omml = LatexToOmml.convert(token.latex, token.display)
        if (omml != null) {
            val body = "<m:oMath>$omml</m:oMath>"
            return if (token.display) "<m:oMathPara>$body</m:oMathPara>" else body
        }
        // 回退：渲染成位图。alt 留 LaTeX 原文，至少还能搜到。
        val rendered: RenderedMath? = runCatching {
            mathRenderer.render(token.latex, token.display)
        }.getOrNull()
        val bitmap = rendered?.bitmap
        if (bitmap != null) {
            val bytes = bitmapToPng(bitmap) ?: return latexFallback(token.latex)
            val rid = addMedia(media, bytes, "png")
            return imageParagraph(rid, bytes, if (token.display) 3_600_000L else 1_800_000L)
        }
        return latexFallback(token.latex)
    }

    /** 连位图都拿不到：显示 LaTeX 源码，比空白强。 */
    private fun latexFallback(latex: String): String =
        paragraph("［${escapeXml(latex)}］", sizeHalfPt = 20, colorHex = "AA0000")

    // ------------------------------------------------------------ 部件

    private fun addMedia(media: MutableList<MediaEntry>, bytes: ByteArray, kind: String): String {
        val index = media.size + 1
        val extension = if (kind == "jpeg") "jpeg" else "png"
        media += MediaEntry(path = "word/media/image$index.$extension", bytes = bytes, kind = kind)
        return REL_PREFIX + index
    }

    private fun writeZip(target: File, media: List<MediaEntry>, body: String) {
        FileOutputStream(target).use { fos ->
            ZipOutputStream(fos).use { zip ->
                fun put(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                put("[Content_Types].xml", contentTypes(media))
                put("_rels/.rels", rootRels())
                put("word/_rels/document.xml.rels", documentRels(media))
                media.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.path))
                    zip.write(entry.bytes)
                    zip.closeEntry()
                }
                // document.xml 放最后：它是最大的一块，先把包结构铺好
                put("word/document.xml", documentXml(docTitleFrom(body), body))
            }
        }
    }

    private fun docTitleFrom(body: String): String = "错题本导出"

    private fun documentXml(title: String, body: String): String = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
  <w:body>
    $body
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1134" w:right="1021" w:bottom="1134" w:left="1021" w:header="851" w:footer="992" w:gutter="0"/>
    </w:sectPr>
  </w:body>
</w:document>
    """.trimIndent()

    private fun contentTypes(media: List<MediaEntry>): String {
        val pngNeeded = media.any { it.kind == "png" }
        val jpegNeeded = media.any { it.kind == "jpeg" }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            // 图片的 Default 必须**按实际用到的**才声明。多声明一个并不会出错，
            // 但会让 Word 认为包里有那个类型，装载时多一步校验。
            if (pngNeeded) append("""<Default Extension="png" ContentType="image/png"/>""")
            if (jpegNeeded) append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
            append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
            append("</Types>")
        }
    }

    private fun rootRels(): String = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
    """.trimIndent()

    private fun documentRels(media: List<MediaEntry>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        media.forEach { entry ->
            val id = REL_PREFIX + (entry.path.substringAfterLast("image").substringBefore('.'))
            append("""<Relationship Id="$id" """)
            append("""Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" """)
            append("""Target="${entry.path.removePrefix("word/")}"/>""")
        }
        append("</Relationships>")
    }

    // ------------------------------------------------------------ 段落与图片

    /**
     * @param sizeHalfPt 字号，**半磅**。Word 的 `w:sz` 用半磅，24 = 12pt。
     */
    private fun paragraph(
        text: String,
        bold: Boolean = false,
        sizeHalfPt: Int = 24,
        colorHex: String? = null,
        align: String? = null
    ): String {
        val props = buildString {
            if (align != null) append("""<w:jc w:val="$align"/>""")
        }
        val runProps = buildString {
            if (bold) append("<w:b/>")
            append("""<w:sz w:val="$sizeHalfPt"/><w:szCs w:val="$sizeHalfPt"/>""")
            if (colorHex != null) append("""<w:color w:val="$colorHex"/>""")
        }
        return """<w:p><w:pPr>$props<w:rPr>$runProps</w:rPr></w:pPr>""" +
            """<w:r><w:rPr>$runProps</w:rPr><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""
    }

    /**
     * 内嵌图片段落。
     *
     * 宽高用 **EMU**（1 px @96dpi = 9525 EMU）。这里只给宽度，
     * 高度按原图比例算——给错比例会让照片被拉变形。
     */
    private fun imageParagraph(relId: String, bytes: ByteArray, maxWidthEmu: Long): String {
        val (width, height) = runCatching {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val w = options.outWidth.takeIf { it > 0 } ?: 1
            val h = options.outHeight.takeIf { it > 0 } ?: 1
            w.toLong() to h.toLong()
        }.getOrDefault(1L to 1L)

        val scale = minOf(1.0, maxWidthEmu.toDouble() / (width * EMU_PER_PX))
        val cx = (width * EMU_PER_PX * scale).toLong()
        val cy = (height * EMU_PER_PX * scale).toLong()

        return """
<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing>
  <wp:inline distT="0" distB="0" distL="0" distR="0">
    <wp:extent cx="$cx" cy="$cy"/>
    <wp:docPr id="0" name="Picture"/>
    <a:graphic>
      <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
        <pic:pic>
          <pic:nvPicPr><pic:cNvPr id="0" name="Picture"/><pic:cNvPicPr/></pic:nvPicPr>
          <pic:blipFill><a:blip r:embed="$relId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
          <pic:spPr>
            <a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>
            <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
          </pic:spPr>
        </pic:pic>
      </a:graphicData>
    </a:graphic>
  </wp:inline>
</w:drawing></w:r></w:p>
        """.trimIndent()
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray? = runCatching {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }.getOrNull()

    /** 按**魔数**判断图片类型，不看扩展名——扩展名可能是错的，Word 会拒绝加载。 */
    private fun detectImageKind(bytes: ByteArray): String {
        val isJpeg = bytes.size > 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        return if (isJpeg) "jpeg" else "png"
    }

    private fun escapeXml(raw: String): String = LatexToOmml.escapeXml(raw)

    private data class MediaEntry(val path: String, val bytes: ByteArray, val kind: String)

    private companion object {
        /** 关系 id 前缀。图片的 rId 统一从这个号段发。 */
        const val REL_PREFIX = "rId"

        /** 1 像素 @96dpi 对应的 EMU 数。 */
        const val EMU_PER_PX = 9525L

        /** 单张内嵌图上限 4MB。超过就跳过附图，不让 docx 变成几十 MB。 */
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

        /** 留白重做区的空段落个数。 */
        const val BLANK_LINES = 6
    }
}
