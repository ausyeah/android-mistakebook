package com.mistakebook.print

/**
 * Word 段落的拼装。
 *
 * 单独抽出来是为了**能测**。DOCX 公式不渲染的根因是
 * [DocxExporter] 的 `renderTokens` 返回含标签的字符串，
 * 调用方无条件塞进 `<w:t>`——而当时的测试全部绕开这条管道，
 * 自己手搓 `documentBody`，于是 41 条 OMML 测试全绿、用户看到一串 XML 标签。
 *
 * 这里的 [Fragment] 是不透明类型模型：公式、文本、图片是**三种不同的类型**，
 * 「这段该不该进 `w:t`」由 [emit] 决定，编译器挡住拼字符串的错误路径。
 *
 * 纯 Kotlin，不碰 Android API——`Fragment.Image` 直接带算好的 EMU 尺寸，
 * 图片解码在 [DocxExporter] 那边做完。
 */
internal object DocxParagraphs {

    /** 段落级片段。 */
    sealed interface Fragment {
        /** 纯文本，**已转义**。 */
        data class Text(val xml: String) : Fragment

        /** 行内公式：`<m:oMath>` 片段，与 `w:r` 平级塞进同一个 `w:p`。 */
        data class Math(val xml: String) : Fragment

        /** 块级公式：独占一段并居中。 */
        data class DisplayMath(val xml: String) : Fragment

        /** 图片：独占一段。宽高为 EMU。 */
        data class Image(val relId: String, val cxEmu: Long, val cyEmu: Long, val docPrId: Int) : Fragment
    }

    /**
     * 把片段排成若干个 `w:p`。
     *
     * 断段规则每条都有理由：
     * - [Fragment.Image] 独占一段——`w:p` 不能嵌套 `w:p`
     * - [Fragment.DisplayMath] 独占一段并居中——`m:oMathPara` 的语义
     * - 其余共用一段——`m:oMath` 是 `w:p` 的合法子节点
     */
    fun emit(
        fragments: List<Fragment>,
        sizeHalfPt: Int = 24,
        colorHex: String? = null
    ): String = buildString {
        val runs = StringBuilder()

        fun closeParagraph() {
            if (runs.isEmpty()) return
            append("<w:p>").append(paragraphProps(null, sizeHalfPt, colorHex))
            append(runs).append("</w:p>")
            runs.setLength(0)
        }

        fragments.forEach { fragment ->
            when (fragment) {
                is Fragment.Text -> runs.append(textRun(fragment.xml, sizeHalfPt, colorHex))
                is Fragment.Math -> runs.append(fragment.xml)
                is Fragment.DisplayMath -> {
                    closeParagraph()
                    append("<w:p>").append(paragraphProps("center", sizeHalfPt, colorHex))
                    append(fragment.xml).append("</w:p>")
                }
                is Fragment.Image -> {
                    closeParagraph()
                    append(imageParagraph(fragment.relId, fragment.cxEmu, fragment.cyEmu, fragment.docPrId))
                }
            }
        }
        closeParagraph()
    }

    /**
     * **只接受纯文本**（已转义，不含标签）的单段。
     *
     * 早先 DOCX 的全部公式都经过这里，参数是含 `<m:oMath>` / `<w:p>` 的字符串——
     * Word 把 OMML 当可见文字显示，位图则变成「段落套段落」的 schema 违规。
     * 两条路一起坏。`require` 让这个错误在开发期就炸，而不是发给用户。
     */
    fun textParagraph(
        text: String,
        bold: Boolean = false,
        sizeHalfPt: Int = 24,
        colorHex: String? = null,
        align: String? = null
    ): String {
        require(text.none { it == '<' || it == '>' }) {
            "textParagraph 只接受纯文本——收到含标签的内容，公式请走 emit()"
        }
        return "<w:p>" + paragraphProps(align, sizeHalfPt, colorHex, bold) +
            "<w:r><w:rPr>" + runProps(bold, sizeHalfPt, colorHex) + "</w:rPr>" +
            "<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
    }

    /**
     * 内嵌图片段落。宽高单位 **EMU**（1 px @96dpi = 9525 EMU）。
     *
     * `wp:docPr` / `pic:cNvPr` 的 id 由调用方给——规范要求**文档内唯一**。
     * 原来硬编码成 `"0"`，XSD 只校验 `unsignedInt` 所以过得了 schema 检查，
     * 但 Word 一般容忍、WPS 可能只认第一张。
     */
    fun imageParagraph(relId: String, cxEmu: Long, cyEmu: Long, docPrId: Int): String = """
<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing>
  <wp:inline distT="0" distB="0" distL="0" distR="0">
    <wp:extent cx="$cxEmu" cy="$cyEmu"/>
    <wp:docPr id="$docPrId" name="Picture $docPrId"/>
    <a:graphic>
      <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
        <pic:pic>
          <pic:nvPicPr><pic:cNvPr id="${docPrId + 1}" name="Picture ${docPrId + 1}"/><pic:cNvPicPr/></pic:nvPicPr>
          <pic:blipFill><a:blip r:embed="$relId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
          <pic:spPr>
            <a:xfrm><a:off x="0" y="0"/><a:ext cx="$cxEmu" cy="$cyEmu"/></a:xfrm>
            <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
          </pic:spPr>
        </pic:pic>
      </a:graphicData>
    </a:graphic>
  </wp:inline>
</w:drawing></w:r></w:p>
    """.trimIndent()

    private fun paragraphProps(
        align: String?,
        sizeHalfPt: Int,
        colorHex: String?,
        bold: Boolean = false
    ): String =
        """<w:pPr>${if (align != null) """<w:jc w:val="$align"/>""" else ""}<w:rPr>${
            runProps(bold, sizeHalfPt, colorHex)
        }</w:rPr></w:pPr>"""

    private fun runProps(bold: Boolean, sizeHalfPt: Int, colorHex: String?): String = buildString {
        if (bold) append("<w:b/>")
        append("""<w:sz w:val="$sizeHalfPt"/><w:szCs w:val="$sizeHalfPt"/>""")
        if (colorHex != null) append("""<w:color w:val="$colorHex"/>""")
    }

    private fun textRun(text: String, sizeHalfPt: Int, colorHex: String?): String =
        "<w:r><w:rPr>" + runProps(false, sizeHalfPt, colorHex) + "</w:rPr>" +
            "<w:t xml:space=\"preserve\">$text</w:t></w:r>"
}