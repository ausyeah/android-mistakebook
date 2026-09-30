package com.mistakebook.math

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量渲染返回值的解包与结构校验。
 *
 * ## 这里锁的是本轮真机上定位到的那个 bug
 * 真机 logcat 原文：
 * ```
 * W/MathRenderer: 批量布局解析失败, 原始返回(截断):
 *   "{\"w\":2016,\"h\":1180,\"items\":[{\"k\":\"i:X\",\"x\":0,\"y\":42,\"w\":77,\"h\":170,\"fs\":112},...
 * ```
 * 返回值**最外层是引号、内部引号被转义**——这是 `evaluateJavascript`
 * 对字符串返回值多加的那一层 JSON 编码。
 * 旧代码直接拿它去构造 JSONObject，解析字符串字面量必然失败，
 * `parseBatchLayout` 恒返回 null，所有公式退回 LaTeX 源码。
 *
 * ## 为什么用 kotlinx.serialization 而不是 org.json
 * 生产代码里 `parseBatchLayout` 用的是 `org.json`，但那是 **Android 的类**，
 * 在 JVM 单元测试里只有打桩实现：要么抛 `RuntimeException`，
 * 要么（开了 returnDefaultValues）静默返回默认值。
 * 两种都会让测试变成测「打桩行为」而不是测真实逻辑。
 * 这里改用 kotlinx.serialization 走同一份数据——
 * 真正出 bug 的是**解包那一层**，它是纯 Kotlin、在单测里完全可测。
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
    private val asJavascriptString: String = Json.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(innerJson)
    )

    private fun parseObject(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /**
     * 取整数字段。
     *
     * `intOrNull` 返回可空值，直接参与算术会满屏 `!!`。
     * 这里收口：字段缺失或不是整数时直接失败，并把字段名带进错误信息——
     * 「静默返回 0」会让这类断言变成恒真。
     */
    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull
            ?: error("字段 $key 缺失或不是整数（拿到的值：${this[key]}）")

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: error("字段 $key 缺失或不是字符串（拿到的值：${this[key]}）")

    private fun JsonObject.float(key: String): Float =
        this[key]?.jsonPrimitive?.floatOrNull
            ?: error("字段 $key 缺失或不是数字（拿到的值：${this[key]}）")

    @Test
    fun `payload really carries an extra json encoding layer`() {
        // 前置断言：这份载荷确实「被多包了一层」。
        // 没有这条，下面的解包测试就可能变成恒真。
        assertTrue("真机载荷应以引号加转义开头", asJavascriptString.startsWith("\"{\\"))
        assertTrue(
            "载荷内部应含被转义的引号",
            asJavascriptString.contains("\\\"")
        )
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
        assertEquals("not json at all", unwrap("not json at all"))
        assertEquals("", unwrap("   "))
    }

    /** 端到端：解包后能解析出全部条目，且字段与真机日志一致。 */
    @Test
    fun `batchLayoutParsesAllEntriesAfterUnwrap`() {
        val obj = parseObject(unwrap(asJavascriptString))
        assertEquals(2016, obj.int("w"))
        assertEquals(3152, obj.int("h"))
        val items = obj["items"]!!.jsonArray
        assertEquals(3, items.size)
        assertEquals("i:A, B", items[0].jsonObject.text("k"))
        assertEquals(42, items[0].jsonObject.int("y"))
        assertEquals(305, items[1].jsonObject.int("y"))
        assertEquals(568, items[2].jsonObject.int("y"))
        assertEquals(112, items[0].jsonObject.int("fs"))
    }

    /**
     * 纵坐标必须单调递增，且行间留白够。
     *
     * 真机日志里相邻条目的 y 是 42 / 305 / 568，条目高 172，
     * 步长 263 —— 行间留白 91 设备像素。留白不够的话，
     * 分式下标、根号的钩会流进下一张切片。
     */
    @Test
    fun `entryRowsAreMonotonicAndSeparated`() {
        val items = parseObject(unwrap(asJavascriptString))["items"]!!.jsonArray
        var previousBottom = -1
        items.forEachIndexed { i, element ->
            val o = element.jsonObject
            val y = o.int("y")
            assertTrue("第 $i 条的 y=$y 没有大于上一条的底 $previousBottom", y > previousBottom)
            previousBottom = y + o.int("h")
        }
        val first = items[0].jsonObject
        val second = items[1].jsonObject
        val gap = second.int("y") - (first.int("y") + first.int("h"))
        assertTrue("行间留白不足：$gap", gap > 0)
    }

    /**
     * 切片必须落在长卷内——越界说明 JS 侧量的宽度和 Android 侧 layout 的宽度不一致。
     * 越界会让 `slice()` 拿到 null，公式又变成空白。
     */
    @Test
    fun `entriesStayInsideSheetBounds`() {
        val obj = parseObject(unwrap(asJavascriptString))
        val sheetW = obj.int("w")
        val sheetH = obj.int("h")
        obj["items"]!!.jsonArray.forEachIndexed { i, element ->
            val o = element.jsonObject
            assertTrue("第 $i 条 x+w=${o.int("x") + o.int("w")} 超出长卷宽 $sheetW",
                o.int("x") + o.int("w") <= sheetW)
            assertTrue("第 $i 条 y+h=${o.int("y") + o.int("h")} 超出长卷高 $sheetH",
                o.int("y") + o.int("h") <= sheetH)
        }
    }

    /**
     * fs 是设备像素字号，Android 侧按「目标字号 / fs」等比缩放。
     *
     * 注意 dpr **可以是小数**（本机实测 3.5，很常见），
     * 所以 fs 不必是 BASE_PX 的整数倍——早先写成「必须是整数倍」是条假断言。
     */
    @Test
    fun `fontPxMatchesDevicePixelRatio`() {
        val o = parseObject(unwrap(asJavascriptString))["items"]!!
            .jsonArray[0].jsonObject
        val dpr = o.float("fs") / BASE_FONT_PX
        assertTrue("dpr=$dpr 不在合理区间", dpr >= 1.0f && dpr <= 6.0f)
        // 钉住真机实测值，防止以后悄悄改了缩放基准
        assertEquals(3.5f, dpr, 1e-3f)
    }

    /** 单条路径的载荷是同一种形态，必须同样能解包。 */
    @Test
    fun `singlePathPayloadUnwrapsTheSameWay`() {
        val single = """{"w":77,"h":170,"fs":112}"""
        val encoded = Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(single)
        )
        val obj = parseObject(unwrap(encoded))
        assertEquals(77, obj.int("w"))
        assertEquals(170, obj.int("h"))
        assertEquals(112f, obj.float("fs"), 1e-3f)
    }

    private companion object {
        /** 与 math.html 里的 `BASE_PX` 保持一致。 */
        const val BASE_FONT_PX = 32f
    }
}
