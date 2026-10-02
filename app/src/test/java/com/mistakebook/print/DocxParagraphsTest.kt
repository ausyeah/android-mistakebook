package com.mistakebook.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mistakebook.print.DocxParagraphs.Fragment

/**
 * **DOCX 装配管道**的回归测试。
 *
 * ## 为什么需要这一整个文件
 *
 * 上一版有 41 条 OMML 测试，全绿；用户打开导出的 docx，看到的是一串
 * `<m:oMath>` 标签文字。
 *
 * 根因不在 OMML（逐条对照 ECMA-376，结构 8 项全合法），
 * 而在 `renderTokens` 返回**含标签的字符串**、被调用方无条件塞进 `<w:t>`。
 * 位图回退也走同一个漏斗，所以「改用图片」同样救不了。
 *
 * 而 `DocxPackageTest` 查不出这个：它自己手搓 `documentBody`，
 * **从不调用 `renderCard` / `renderTokens` / 段落拼装**。
 * 测试绕开了出事的那段代码，等于没测。
 *
 * 所以这里测的就是那一段。
 */
class DocxParagraphsTest {

    private fun text(value: String) = Fragment.Text(value)

    private val omml = "<m:oMath><m:r><m:t>x</m:t></m:r></m:oMath>"

    // ------------------------------------------------------- 原始 bug

    /**
     * 核心回归：公式绝不能出现在 `<w:t>` 里。
     *
     * 放进 `<w:t>` 的话 Word 会把 XML 当可见文字原样显示——
     * 这正是用户看到的「公式不能正常渲染」。
     */
    @Test
    fun `行内公式不落进文本 run`() {
        val xml = DocxParagraphs.emit(listOf(text("设 "), Fragment.Math(omml), text(" 为函数")))

        assertFalse("w:t 里不能出现子元素", insideTextRuns(xml).contains("<m:oMath"))
        assertFalse("w:t 里不能出现子元素", insideTextRuns(xml).contains("</m:oMath>"))
        assertTrue("公式要作为 w:p 的兄弟节点存在", xml.contains(omml))
        assertTrue("文字要保留", xml.contains("<w:t xml:space=\"preserve\">设 </w:t>"))
    }

    /**
     * 位图回退曾返回**一整个 `<w:p>`**，被塞进 `<w:t>` 变成段落套段落，
     * 是 schema 违规——Word 报「文档已损坏」并触发修复。
     */
    @Test
    fun `图片段落不落进文本 run 也不嵌套段落`() {
        val xml = DocxParagraphs.emit(
            listOf(
                text("见下图 "),
                Fragment.Image(relId = "rId3", cxEmu = 1905000L, cyEmu = 952500L, docPrId = 4)
            )
        )

        assertFalse("图片不能出现在 w:t 里", insideTextRuns(xml).contains("<w:drawing"))
        assertEquals(
            "w:p 必须开闭配对",
            countOccurrences(xml, "<w:p>"),
            countOccurrences(xml, "</w:p>")
        )

        // 段落数：文字一段 + 图片一段
        assertEquals(2, countOccurrences(xml, "<w:p>"))
        assertTrue("图片关系 id 要写进去", xml.contains("""<a:blip r:embed="rId3"/>"""))
    }

    /** 文字和图片之间必须断段，但不能多出空段。 */
    @Test
    fun `图片前后各自断段且不留空段`() {
        val xml = DocxParagraphs.emit(
            listOf(
                text("前"),
                Fragment.Image("rId1", 100L, 200L, 2),
                text("后")
            )
        )
        assertEquals(3, countOccurrences(xml, "<w:p>"))
        assertTrue(xml.indexOf("前") < xml.indexOf("<w:drawing"))
        assertTrue(xml.indexOf("<w:drawing") < xml.indexOf("后"))
    }

    // ------------------------------------------------------- 块级公式

    @Test
    fun `块级公式独占一段并居中`() {
        val xml = DocxParagraphs.emit(
            listOf(text("解："), Fragment.DisplayMath(omml), text("完毕"))
        )
        // 文字一段 + 公式一段 + 文字一段
        assertEquals(3, countOccurrences(xml, "<w:p>"))
        val mathSegment = xml.substringAfter("</w:p>")
        assertTrue("公式段要居中", mathSegment.substringBefore(omml).contains("""<w:jc w:val="center"/>"""))
        assertFalse("公式仍在 w:t 外", insideTextRuns(xml).contains("<m:oMath"))
    }

    @Test
    fun `空片段列表不产出空段落`() {
        assertEquals("", DocxParagraphs.emit(emptyList()))
    }

    // ------------------------------------------------------- textParagraph

    @Test
    fun `textParagraph 只收纯文本。收到标签就报错`() {
        var threw = false
        try {
            DocxParagraphs.textParagraph(omml)
        } catch (error: IllegalArgumentException) {
            threw = true
            assertTrue(
                "报错信息要说清该走哪条路",
                error.message?.contains("emit") == true
            )
        }
        assertTrue("含标签的输入必须被拒绝", threw)
    }

    @Test
    fun `textParagraph 输出标准的单段`() {
        val xml = DocxParagraphs.textParagraph("错题本", bold = true, sizeHalfPt = 30)
        assertEquals(1, countOccurrences(xml, "<w:p>"))
        assertTrue(xml.contains("<w:b/>"))
        assertTrue(xml.contains("""<w:sz w:val="30"/>"""))
        assertTrue(xml.contains("<w:t xml:space=\"preserve\">错题本</w:t>"))
    }

    // ------------------------------------------------------- 图片 id

    /**
     * `wp:docPr` / `pic:cNvPr` 的 id 规范上要求文档内唯一。
     * 原来硬编码 `"0"`，schema 校验过得去，但 WPS 可能只认第一张。
     */
    @Test
    fun `图片 id 不重复`() {
        val xml = DocxParagraphs.emit(
            listOf(
                Fragment.Image("rId1", 100L, 200L, 2),
                Fragment.Image("rId2", 100L, 200L, 4),
                Fragment.Image("rId3", 100L, 200L, 6)
            )
        )
        val ids = Regex("""(?:docPr|cNvPr) id="(\d+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals("docPr 与 cNvPr 各一个，6 个 id", 6, ids.size)
        assertEquals("id 不能重复", ids.size, ids.toSet().size)
    }

    @Test
    fun `图片宽高用 EMU 且不拉伸`() {
        val xml = DocxParagraphs.imageParagraph("rId1", 1905000L, 952500L, 2)
        assertTrue(xml.contains("""<wp:extent cx="1905000" cy="952500"/>"""))
        assertTrue("span 与 extent 必须一致，否则会拉伸",
            xml.contains("""<a:ext cx="1905000" cy="952500"/>"""))
    }

    // ------------------------------------------------------- 工具

    /**
     * 抽出所有 `<w:t ...>…</w:t>` 的内容。
     *
     * 断言「w:t 里没有子元素」比断言「w:t 里没有 m:oMath」更强：
     * 将来新增任何类型的片段，这条都自动覆盖。
     */
    private fun insideTextRuns(xml: String): String =
        Regex("""<w:t[^>]*>(.*?)</w:t>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .joinToString("") { it.groupValues[1] }

    private fun countOccurrences(text: String, needle: String): Int =
        text.windowed(needle.length).count { it == needle }
}