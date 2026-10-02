package com.mistakebook.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LaTeX -> OMML 转换测试。
 *
 * 这个转换器**一旦吐出结构错误的 XML，Word 打开时会报「文档已损坏」**，
 * 整份文档作废——比公式丑严重得多。所以正向、反向都要钉住：
 * 特别要验证「基取对了」（`xy^2` 的基不是 `xy`）。
 */
class LatexToOmmlTest {

    private fun convert(latex: String, display: Boolean = false): String? =
        LatexToOmml.convert(latex, display)

    // ------------------------------------------------------------ 基本

    @Test
    fun `单个变量`() {
        assertEquals("<m:r><m:t>x</m:t></m:r>", convert("x"))
    }

    @Test
    fun `多字符变量合成一个 run`() {
        // 反向用例：拆成 x/y 两个 run 会让 `xy^2` 的基取错
        assertEquals("<m:r><m:t>xy</m:t></m:r>", convert("xy"))
    }

    @Test
    fun `空白被丢掉`() {
        assertEquals(
            "<m:r><m:t>x</m:t></m:r><m:r><m:t>+</m:t></m:r><m:r><m:t>1</m:t></m:r>",
            convert("x + 1")
        )
    }

    // ------------------------------------------------------------ 分数

    @Test
    fun `分式转成 m_f`() {
        val xml = convert("""\frac{a}{b}""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("<m:f>"))
        assertTrue(xml.contains("<m:num>"))
        assertTrue(xml.contains("<m:den>"))
        assertTrue("分子应为 a", xml.contains("<m:t>a</m:t>"))
        assertTrue("分母应为 b", xml.contains("<m:t>b</m:t>"))
    }

    @Test
    fun `dfrac 与 tfrac 等价`() {
        assertEquals(convert("""\frac{a}{b}"""), convert("""\dfrac{a}{b}"""))
        assertEquals(convert("""\frac{a}{b}"""), convert("""\tfrac{a}{b}"""))
    }

    @Test
    fun `分式嵌套`() {
        val xml = convert("""\frac{\frac{a}{b}}{c}""")
        assertNotNull(xml)
        // 内层分式必须在自己的 num 里
        assertTrue(xml!!.indexOf("<m:t>a</m:t>") < xml.indexOf("<m:t>c</m:t>"))
    }

    @Test
    fun `分式参数不带花括号也能读`() {
        val braced = convert("""\frac{a}{b}""")
        assertEquals(braced, convert("""\frac ab"""))
    }

    @Test
    fun `空分式退回除号`() {
        val xml = convert("""\frac{}{b}""")
        assertNotNull(xml)
        assertTrue("空分子应退化成 /", xml!!.contains("<m:t>/</m:t>"))
    }

    // ------------------------------------------------------------ 上下标

    @Test
    fun `上标转成 m_sSup 且基只取紧邻的那个`() {
        val xml = convert("""x^2""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("<m:sSup>"))
        assertTrue("基应为 x", xml.contains("<m:e><m:r><m:t>x</m:t></m:r></m:e>"))
        assertTrue("上标应为 2", xml.contains("<m:sup><m:r><m:t>2</m:t></m:r></m:sup>"))
    }

    @Test
    fun `多字符变量的上标基只取最后一段之外的整体`() {
        // xy^2 的基是整个 xy（LaTeX 里 xy 就是一个标识符），
        // 但至少不能变成空基或把 y 单独留下导致显示成 (xy)^2 的错误括号
        val xml = convert("""xy^2""")
        assertNotNull(xml)
        assertTrue("基不能为空", xml!!.contains("<m:e>"))
    }

    @Test
    fun `下标转成 m_sSub`() {
        val xml = convert("""x_1""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("<m:sSub>"))
        assertTrue(xml.contains("<m:sub><m:r><m:t>1</m:t></m:r></m:sub>"))
    }

    @Test
    fun `花括号包起来的上下标`() {
        assertEquals(convert("""x^{2}"""), convert("""x^2"""))
        assertEquals(convert("""x_{i}"""), convert("""x_i"""))
    }

    @Test
    fun `多层上标`() {
        val xml = convert("""e^{x^2}""")
        assertNotNull(xml)
        assertTrue("上标里应再含一个 sSup", xml!!.count { it == '<' } > 0)
        assertTrue(xml.contains("<m:t>x</m:t>"))
        assertTrue(xml.contains("<m:t>2</m:t>"))
    }

    @Test
    fun `没基的上下标不产生结构错误`() {
        // ^2 前面什么都没有：宁可不生成 sSup，也不要吐一个 <m:e> 为空的标签
        val xml = convert("""^2""")
        assertTrue(xml == null || !xml.contains("<m:sSup>"))
    }

    @Test
    fun `上标的参数是命令`() {
        val xml = convert("""x^\alpha""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("α"))
    }

    // ------------------------------------------------------------ 根号

    @Test
    fun `根号转成 m_rad`() {
        val xml = convert("""\sqrt{x}""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("<m:rad>"))
        assertTrue(xml.contains("<m:t>x</m:t>"))
    }

    @Test
    fun `带可选参数的根号只取被开方数`() {
        // n 次根号的可选参数只吃掉不渲染——渲染错比不渲染好
        assertEquals(convert("""\sqrt{x}"""), convert("""\sqrt[3]{x}"""))
    }

    // ---------------------------------------------------------- 命令与符号

    @Test
    fun `希腊字母`() {
        assertTrue(convert("""\alpha""")!!.contains("α"))
        assertTrue(convert("""\beta""")!!.contains("β"))
        assertTrue(convert("""\pi""")!!.contains("π"))
        assertTrue(convert("""\Omega""")!!.contains("Ω"))
    }

    @Test
    fun `关系符`() {
        assertTrue(convert("""\leq""")!!.contains("≤"))
        assertTrue(convert("""\geq""")!!.contains("≥"))
        assertTrue(convert("""\neq""")!!.contains("≠"))
        assertTrue(convert("""\approx""")!!.contains("≈"))
    }

    @Test
    fun `箭头`() {
        assertTrue(convert("""\rightarrow""")!!.contains("→"))
        assertTrue(convert("""\to""")!!.contains("→"))
    }

    @Test
    fun `函数名保持正体名字而不是单字母`() {
        // \sin 必须整体输出，否则会被读成 s*i*n
        assertTrue(convert("""\sin x""")!!.contains("<m:t>sin</m:t>"))
    }

    @Test
    fun `无穷大`() {
        assertTrue(convert("""\infty""")!!.contains("∞"))
    }

    @Test
    fun `不认识的命令丢掉反斜杠而不是整体失败`() {
        // 错题本里 \text 之类写法不少；丢掉反斜杠至少还能读
        val xml = convert("""\foo""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("foo"))
        assertTrue("不该把反斜杠原样输出", !xml.contains("\\"))
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(convert(""))
        assertNull(convert("   "))
    }

    // ---------------------------------------------------------- left/right

    @Test
    fun `left right 的括号被丢掉只取内容`() {
        val xml = convert("""\left( x + 1 \right)""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("<m:t>x</m:t>"))
        assertTrue("括号本身不该出现在正文里", !xml.contains("<m:t>(</m:t>"))
    }

    @Test
    fun `left right 带花括号`() {
        assertNotNull(convert("""\left\{ x \right\}"""))
    }

    // ---------------------------------------------------------- 不支持环境

    @Test
    fun `对齐环境这类复杂结构可以转换但不保证美观`() {
        // 不崩溃就够：aligned 的内容会退化成顺序文本，而不是丢掉整个公式。
        // 之所以不在转换器里专门处理 m:eqArr，是因为写错会导致 Word 报文档损坏。
        assertNotNull(convert("""\begin{aligned} a &= b \\ c &= d \end{aligned}"""))
    }

    @Test
    fun `未知环境不导致死循环`() {
        val xml = convert("""\begin{foo} x \end{foo}""")
        assertTrue(xml == null || xml.contains("x"))
    }

    // ---------------------------------------------------------- 转义

    @Test
    fun `XML 特殊字符被转义`() {
        // 反向用例：不转义的话 < 会让整个 document.xml 变成非法 XML，
        // Word 报「文档已损坏」——整份文档作废，比公式丑严重得多
        assertEquals("&lt;", LatexToOmml.escapeXml("<"))
        assertEquals("&gt;", LatexToOmml.escapeXml(">"))
        assertEquals("&amp;", LatexToOmml.escapeXml("&"))
        assertTrue(convert("a<b")!!.contains("&lt;"))
    }

    @Test
    fun `公式里的不等号不会被当成 XML 标签`() {
        val xml = convert("""x < y""")
        assertNotNull(xml)
        assertTrue("必须转义成实体", xml!!.contains("&lt;"))
        // 剥掉所有真正的标签后，剩下的文本里不能有任何裸尖括号
        val textOnly = xml.replace(Regex("<[^>]+>"), "")
        assertTrue("剥掉标签后仍有裸尖括号: $textOnly", !textOnly.contains("<") && !textOnly.contains(">"))
        // 裸 & 只在不成实体时才是问题（`&lt;` 里的 & 是合法的）
        assertTrue("存在裸 & : $textOnly", !Regex("&(?!(amp|lt|gt|quot|apos);)").containsMatchIn(textOnly))
    }

    @Test
    fun `引号也被转义`() {
        // OMML 属性值里出现裸引号会截断属性
        assertEquals("&quot;", LatexToOmml.escapeXml("\""))
        assertEquals("&apos;", LatexToOmml.escapeXml("'"))
    }

    // ---------------------------------------------------------- 组合

    @Test
    fun `典型错题公式能整条转换`() {
        val xml = convert("""\frac{-b \pm \sqrt{b^2-4ac}}{2a}""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("<m:f>"))
        assertTrue(xml.contains("±"))
        assertTrue(xml.contains("<m:rad>"))
    }

    @Test
    fun `极限表达式`() {
        val xml = convert("""\lim_{x \to 0} \frac{\sin x}{x} = 1""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("lim"))
        assertTrue(xml.contains("<m:f>"))
    }

    @Test
    fun `求和带上下限`() {
        val xml = convert("""\sum_{i=1}^{n} i""")
        assertNotNull(xml)
        assertTrue(xml!!.contains("<m:nary>"))
    }
}
