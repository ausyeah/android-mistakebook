package com.mistakebook.print

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * PDF 行内公式排版的行高不变量。
 *
 * ## 这类 bug 为什么必须写测试
 * 「打印出来行行重叠」这个症状对应好几个完全不同的成因，光看现象无法区分。
 * 这里锁住的是最容易被改坏、又最不容易被肉眼在代码里看出来的那条：
 * **行高必须从基线往下量，而不是从行顶量。**
 *
 * 之前两次都写错了同一个地方：
 * 1. `baseline = lineTop + metrics.ascent` —— Android 的 ascent 是**负数**
 *    （基线以上为负），等于把基线放到行框顶边之上，整篇文字逐行向上累积
 * 2. 行高按 `h * 1.25` 给 —— 从行顶量，差了一整个 ascent，公式照样压下一行
 */
class PdfLineHeightTest {

    /** 14pt 无衬线字体的基线以上高度，取 Android 上典型值。 */
    private val ascentUp = 13f
    private val padding = PdfExporter.MATH_LINE_PADDING
    private val belowBaseline = 1f - PdfExporter.MATH_BOTTOM_RATIO

    /**
     * 核心不变量：公式底边相对行顶的位置，不能超过给它的行高。
     *
     * 公式画在 `[baseline - h*0.18, baseline + h*0.82]`，
     * 基线在 `lineTop + ascentUp`，所以底边 = `ascentUp + 0.82*h`。
     */
    @Test
    fun `lineHeightContainsFormulaBelowBaseline`() {
        for (mathHeight in listOf(4f, 8f, 12f, 20f, 30f, 45f, 70f, 100f)) {
            val given = requiredLineHeightForMath(ascentUp, mathHeight, padding)
            val inkBottom = ascentUp + mathHeight * belowBaseline
            assertTrue(
                "公式高 ${mathHeight}pt：给了 ${given}pt 行高，装不下底边 ${inkBottom}pt",
                given >= inkBottom
            )
        }
    }

    /**
     * 旧算法（从行顶量的 `h * 1.25`）在多数尺寸下**装不下**。
     *
     * 这条测试的作用是把「为什么不能简单用 h * 1.25」钉死：
     * 少掉的正好是基线以上那一段。
     */
    @Test
    fun `legacyHeightIsInsufficientForMostSizes`() {
        var insufficient = 0
        for (mathHeight in listOf(8f, 12f, 20f, 30f, 45f)) {
            val legacy = mathHeight * 1.25f
            val inkBottom = ascentUp + mathHeight * belowBaseline
            if (legacy < inkBottom) insufficient++
        }
        // 绝大多数尺寸下旧算法都不够；留 1 的余量是为了公式极矮时两者可能恰好相等
        assertTrue("旧算法本该在多数尺寸下装不下，实际只有 $insufficient/5 例", insufficient >= 4)
    }

    /**
     * 公式很高时也不能从行**顶**溢出到上一行。
     *
     * 公式顶边 = `ascentUp - 0.18*h`；只要行高 >= 公式高，顶边就 >= 0。
     */
    @Test
    fun `lineHeightAlsoContainsFormulaAboveLineTop`() {
        for (mathHeight in listOf(4f, 12f, 20f, 30f, 45f, 70f, 100f)) {
            val given = requiredLineHeightForMath(ascentUp, mathHeight, padding)
            assertTrue(
                "公式高 ${mathHeight}pt：行高 ${given}pt 小于公式自身高度",
                given >= mathHeight
            )
        }
    }

    /** 行高必须随公式高度单调递增，否则大公式会拿到和小公式一样的空间。 */
    @Test
    fun `lineHeightGrowsMonotonically`() {
        var previous = 0f
        for (mathHeight in listOf(4f, 8f, 12f, 20f, 30f, 45f, 70f)) {
            val h = requiredLineHeightForMath(ascentUp, mathHeight, padding)
            assertTrue("公式 ${mathHeight}pt 的行高 ${h}pt 未大于上一档", h > previous)
            previous = h
        }
    }

    /**
     * `ascentUp` 传负数（漏了取反）会让行高塌到基线以上——正是原来那个 bug。
     * 这条测试逼调用方明确「传进来的必须是正数」。
     */
    @Test
    fun `ascentUpMustBePositiveAndSufficesAtZero`() {
        val normal = requiredLineHeightForMath(ascentUp, 20f, padding)
        val noAscent = requiredLineHeightForMath(0f, 20f, padding)
        assertTrue("漏掉 ascent 会让行高少掉 $normal-$noAscent", normal - noAscent > 10f)
        // 基线以上高度为 0 的极端情况仍要装得下公式垂下去的部分
        assertTrue(noAscent >= 20f * belowBaseline)
    }

    /** 行高公式不该依赖 padding 为负（否则调用方传错会把行压扁）。 */
    @Test
    fun `paddingIsAdditive`() {
        val a = requiredLineHeightForMath(ascentUp, 20f, 0.25f)
        val b = requiredLineHeightForMath(ascentUp, 20f, 0.50f)
        assertTrue(abs((b - a) - 20f * 0.25f) < 1e-3f)
    }
}
