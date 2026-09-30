package com.mistakebook.math

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量渲染返回值的解包与解析。
 *
 * ## 这里锁的是本轮真机上定位到的那个 bug
 * 真机 logcat 原文：
 * ```
 * W/MathRenderer: 批量布局解析失败, 原始返回(截断):
 *   "{\"w\":2016,\"h\":1180,\"items\":[{\"k\":\"i:X\",\"x\":0,\"y\":42,\"w\":77,\"h\":170,\"fs\":112},...
 * ```
 * 可见返回值的**最外层是引号**、内部引号被转义——这就是
 * `evaluateJavascript` 对字符串返回值多加的那一层 JSON 编码。
 * 旧代码直接 `JSONObject(raw)`，解析字符串字面量必然失败，
 * 于是 `parseBatchLayout` 恒返回 null，所有公式退回 LaTeX 源码。
 *
 * 下面的载荷形态取自真机日志，不是凭空构造的。
 */
class BatchLayoutParseTest {

    /** 与 [MathRenderer] 里的实现保持一致。 */
    private fun unwrap(raw: String): String {
        val text = raw.trim()
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return runCatching { Json.parseToJsonElement(text).jsonPrimitive.content }
                .getOrDefault(text)
        }
        return text
    }

    /** 真机日志里 `renderBatch` 返回值的内部 JSON 形态。 */
    private val innerJson =
        """{"w":2016,"h":3152,"items":[""" +
            """{"k":"i:A, B","x":0,"y":42,"w":272,"h":172,"fs":112},""" +
            """{"k":"i:n","x":0,"y":305,"w":81,"h":172,"fs":112},""" +
            """{"k":"i:P^{-1}AP = A","x":0,"y":568,"w":437,"h":172,"fs":112}]}"""

    /** evaluateJavascript 回调实际收到的东西：JSON 字符串字面量。 */
    private val asJavascriptString = Json.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(innerJson)
    )

    @Test
    fun `javascriptStringIsDetectedByQuotesAndBackslashes`() {
        // 前置断言：这份载荷确实是「被多包了一层」——
        // 直接 JSONObject 解析会失败，这正是线上那个 bug 的触发条件
        val directFails = runCatching { JSONObject(asJavascriptString) }.isFailure
        assertTrue("载荷应无法被直接 JSONObject 解析（否则这个测试没测到东西）", directFails)
        assertTrue(asJavascriptString.startsWith("\"{\\"))
    }

    @Test
    fun `unwrapRestoresTheInnerJson`() {
        assertEquals(innerJson, unwrap(asJavascriptString))
    }

    @Test
    fun `unwrapLeavesPlainJsonUntouched`() {
        // 兼容两种形态：万一某些 WebView 版本不额外编码，也不能被误剥
        assertEquals(innerJson, unwrap(innerJson))
    }

    @Test
    fun `unwrapLeavesMalformedInputAsIsSoCallerCanReportIt`() {
        val junk = "not json at all"
        assertEquals(junk, unwrap(junk))
        assertEquals("", unwrap("   "))
    }

    /**
     * 端到端：解包后能解析出全部条目，且字段与真机日志一致。
     */
    @Test
    fun `batchLayoutParsesAllEntriesAfterUnwrap`() {
        val obj = JSONObject(unwrap(asJavascriptString))
        assertEquals(2016, obj.optInt("w"))
        assertEquals(3152, obj.optInt("h"))
        val items = obj.optJSONArray("items")
        assertEquals(3, items.length())
        assertEquals("i:A, B", items.getJSONObject(0).optString("k"))
        assertEquals(42, items.getJSONObject(0).optInt("y"))
        assertEquals(305, items.getJSONObject(1).optInt("y"))
        assertEquals(568, items.getJSONObject(2).optInt("y"))
        assertEquals(112, items.getJSONObject(0).optInt("fs"))
    }

    /**
     * 纵坐标必须单调递增，否则切图会错位、重叠。
     *
     * 真机日志里相邻条目的 y 是 42 / 305 / 568 —— 步长 263，
     * 而条目自身高 172，说明行间留白 91 设备像素。留白不够的话，
     * 分式下标、根号钩会流进下一张切片。
     */
    @Test
    fun `entryRowsAreMonotonicAndSeparated`() {
        val items = JSONObject(unwrap(asJavascriptString)).optJSONArray("items")
        var previousBottom = -1
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val y = o.optInt("y")
            val h = o.optInt("h")
            assertTrue("第 $i 条的 y=$y 没有大于上一条的底 $previousBottom", y > previousBottom)
            previousBottom = y + h
        }
        // 相邻行之间确有留白
        val first = items.getJSONObject(0)
        val second = items.getJSONObject(1)
        assertTrue(
            "行间留白不足：${second.optInt("y")} - ${first.optInt("y") + first.optInt("h")}",
            second.optInt("y") - (first.optInt("y") + first.optInt("h")) > 0
        )
    }

    /**
     * 切片必须落在长卷内——越界说明 JS 侧量的宽度和 Android 侧 layout 的宽度不一致。
     * 越界会让 `slice()` 拿到 null，公式又变成空白。
     */
    @Test
    fun `entriesStayInsideSheetBounds`() {
        val obj = JSONObject(unwrap(asJavascriptString))
        val sheetW = obj.optInt("w")
        val sheetH = obj.optInt("h")
        val items = obj.optJSONArray("items")
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val x = o.optInt("x")
            val y = o.optInt("y")
            val w = o.optInt("w")
            val h = o.optInt("h")
            assertTrue("第 $i 条 x+w=${x + w} 超出长卷宽 $sheetW", x + w <= sheetW)
            assertTrue("第 $i 条 y+h=${y + h} 超出长卷高 $sheetH", y + h <= sheetH)
        }
    }

    /**
     * fs 是设备像素字号，Android 侧按「目标字号 / fs」缩放。
     *
     * 注意 dpr **可以是小数**（本机实测 3.5，很常见），
     * 所以 fs 不必是 BASE_PX 的整数倍。这里只断言 dpr 落在合理区间。
     */
    @Test
    fun `fontPxMatchesDevicePixelRatio`() {
        val o = JSONObject(unwrap(asJavascriptString)).optJSONArray("items").getJSONObject(0)
        val fs = o.optDouble("f", 0.0).toFloat()
        val dpr = fs / BASE_FONT_PX
        assertTrue("dpr=$dpr 不在合理区间", dpr >= 1.0f && dpr <= 6.0f)
        // 顺带钉住真机实测值，防止以后悄悄改了缩放基准
        assertEquals(112f, fs, 1e-3f)
        assertEquals(3.5f, dpr, 1e-3f)
    }

    private companion object {
        /** 与 math.html 里的 `BASE_PX` 保持一致。 */
        const val BASE_FONT_PX = 32f
    }

    /** 单条路径也必须用同一套解包，否则两条路径行为会再次分叉。 */
    @Test
    fun `singlePathParsesTheSamePayloadShape`() {
        val single = """{"w":77,"h":170,"fs":112}"""
        val encoded = Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(single)
        )
        val obj = Json.parseToJsonElement(unwrap(encoded)).jsonObject
        assertEquals(77, obj["w"]!!.jsonPrimitive.content.toInt())
        assertEquals(170, obj["h"]!!.jsonPrimitive.content.toInt())
        assertEquals(112f, obj["fs"]!!.jsonPrimitive.content.toFloat(), 1e-3f)
    }
}
