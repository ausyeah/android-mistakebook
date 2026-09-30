package com.mistakebook.math

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * LaTeX 公式渲染：WebView + KaTeX（资源打在本机 assets，无第三方 SDK）。
 * 渲染结果按「latex + 是否独立行」缓存，供页面展示与 PDF 导出共用。
 *
 * 踩过的坑（每个都会导致「公式位置一片空白」而不是回退到源码）：
 * 1. 必须 `setLayerType(LAYER_TYPE_SOFTWARE)`，否则内容在硬件 surface 里，
 *    `view.draw(canvas)` 抓到的是全透明位图。
 * 2. 必须给 WebView 真实布局尺寸，0×0 视口下页面内容根本不会光栅化。
 * 3. **JS 量到的 getBoundingClientRect() 是 CSS 像素，view.draw 画的是设备像素**。
 *    高分屏上两者差一个 devicePixelRatio（约 3 倍），必须让 JS 乘 dpr 再返回，
 *    否则抠出来的是公式左上角一小块甚至全空。
 * 4. WebView 重排/光栅化是异步的，JS 返回后要等几帧再抓，否则抓到上一帧。
 * 5. 抓到的位图可能只有背景色（不透明），单纯查 alpha 判不出空白，要按「主色占比」判断。
 */
/**
 * 一次渲染的产物：位图 + 该位图实际使用的字号（设备像素）。
 *
 * fontPx 是排版的关键：调用方必须按「目标字号 / fontPx」等比缩放，
 * 这样公式里的字母数字才和正文中文**同号**。若改成按包围盒高度缩放，
 * 公式之间会大小失衡（单字母被撑得巨大、长分式被压扁），观感很不协调。
 */
data class RenderedMath(
    val bitmap: Bitmap,
    val fontPx: Float
)

class MathRenderer(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val cache = object : android.util.LruCache<String, RenderedMath>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: RenderedMath): Int = value.bitmap.byteCount
    }

    private var webView: WebView? = null
    private var pageLoaded: CompletableDeferred<Unit> = CompletableDeferred()

    /** 渲染结果为透明背景位图 + 实际字号；失败返回 null（调用方回退为源码文本）。 */
    suspend fun render(latex: String, displayMode: Boolean): RenderedMath? {
        val trimmed = com.mistakebook.pipeline.LatexSanitizer.clean(latex)
        if (trimmed.isEmpty()) return null
        val key = (if (displayMode) "d" else "i") + ":" + trimmed
        cache.get(key)?.let { return it }
        Log.i(TAG, "render 请求: '$trimmed' display=$displayMode")
        return withContext(Dispatchers.Main) {
            mutex.withLock {
                try {
                    val view = ensureWebView()
                    // 页面没加载完就发 JS，回调永远不回来；必须加超时，
                    // 否则 WebView 起不来会把 Mutex 永久占住，之后所有公式渲染一起卡死。
                    if (withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { pageLoaded.await() } == null) {
                        Log.w(TAG, "KaTeX 页面加载超时: $trimmed")
                        return@withLock null
                    }
                    // 留 32px 余量给 #out 的 padding，避免抠图时切到最右侧
                    val script =
                        "render(${jsonString(trimmed)}, $displayMode, ${MAX_BITMAP_EDGE - 32})"
                    // 光栅化有竞态：抓到空白就重来一次
                    repeat(2) { attempt ->
                        val raw = awaitJavascript(view, script)
                        if (raw == null) {
                            Log.w(TAG, "evaluateJavascript 无返回: $trimmed")
                            return@withLock null
                        }
                        val size = parseSize(raw)
                        if (size == null) {
                            Log.w(TAG, "尺寸解析失败, 原始返回: $raw")
                            return@withLock null
                        }
                        val width = size.width.coerceIn(1, MAX_BITMAP_EDGE)
                        val height = size.height.coerceIn(1, MAX_BITMAP_EDGE)
                        if (width < 2 || height < 2) return@withLock null

                        relayout(view)
                        view.scrollTo(0, 0)
                        val bitmap = capture(view, width, height)
                        if (bitmap != null) {
                            val result = RenderedMath(bitmap, size.fontPx)
                            cache.put(key, result)
                            return@withLock result
                        }
                        Log.w(TAG, "第 ${attempt + 1} 次抓图为空白，重试: $trimmed")
                    }
                    null
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Log.w(TAG, "render latex failed: $error")
                    null
                }
            }
        }
    }

    /** 每次渲染前重新走一遍 measure/layout，确保页面内容已在该尺寸下光栅化。 */
    private fun relayout(view: WebView) {
        view.measure(exactly(VIEWPORT_WIDTH), exactly(VIEWPORT_HEIGHT))
        view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
    }

    /**
     * 批量长卷视口。
     *
     * 视口必须够高，否则 WebView 会对内容做滚动/裁剪，`view.draw` 画不到
     * 视口外的内容，量出的 sheetRect 也会被截断。
     * 一张长卷最多 12 个公式，按每个 ~200 设备像素估，留 4096 足够。
     */
    private fun relayoutForBatch(view: WebView, neededHeight: Int) {
        val h = neededHeight.coerceIn(VIEWPORT_HEIGHT, BATCH_VIEWPORT_HEIGHT)
        view.measure(exactly(VIEWPORT_WIDTH), exactly(h))
        view.layout(0, 0, VIEWPORT_WIDTH, h)
    }

    private suspend fun waitFrames(count: Int) {
        repeat(count) { awaitFrame() }
    }

    /** 抓取页面左上角 width×height 的设备像素区域。 */
    private suspend fun capture(view: WebView, width: Int, height: Int): Bitmap? {
        waitFrames(2)
        // 从未挂载到窗口的 WebView 不会自己产出合成帧，AwContents 里是空的，
        // 直接 draw 出来就是全透明。先往一张临时画布上画一次，逼它把帧做出来。
        val scratchW = maxOf(view.width, 1)
        val scratchH = maxOf(view.height, 1)
        val scratch = Bitmap.createBitmap(scratchW, scratchH, Bitmap.Config.ARGB_8888)
        val scratchCanvas = Canvas(scratch)
        scratch.eraseColor(Color.MAGENTA)
        view.draw(scratchCanvas)
        scratch.recycle()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        // 判定必须看 alpha：「颜色数 > 1」是假指标，全透明区域照样能有几十种
        // 透明度抖动出来的颜色，一样等于什么都没画出来。
        val alpha = alphaStats(bitmap)
        Log.i(
            TAG,
            "capture ${width}x$height 不透明=${alpha.opaque}/${alpha.total} 最大alpha=${alpha.maxAlpha} 包围盒=${alpha.box}"
        )
        if (alpha.visible == 0) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /**
     * 公式渲染自检：把整条链路的真实值逐项打出来，供真机定位。
     * 「空白」这个症状对应至少五种完全不同的故障，光看现象无法区分，必须看数据。
     * 只做探测，不写缓存。
     */
    suspend fun diagnose(): String = withContext(Dispatchers.Main) {
        mutex.withLock {
            val sb = StringBuilder()
            try {
                val view = ensureWebView()
                sb.appendLine("view: ${view.width}x${view.height}  layerType=${view.layerType} (SOFTWARE=${View.LAYER_TYPE_SOFTWARE})")
                sb.appendLine("pageLoaded 已完成=${pageLoaded.isCompleted} 已取消=${pageLoaded.isCancelled}")

                val start = System.currentTimeMillis()
                val loaded = withTimeoutOrNull(20_000L) { pageLoaded.await() }
                sb.appendLine(
                    "等页面加载: ${if (loaded != null) "完成" else "超时"} 耗时=${System.currentTimeMillis() - start}ms"
                )

                val probe = awaitJavascript(
                    view,
                    "JSON.stringify({katex:typeof katex!=='undefined',dpr:window.devicePixelRatio," +
                        "iw:window.innerWidth,ih:window.innerHeight," +
                        "fonts:(document.fonts?document.fonts.status:'n/a')})"
                )
                sb.appendLine("环境探针: ${probe ?: "<evaluateJavascript 没返回>"}")

                val sample = "x^2+\\frac{1}{2}"
                val raw = awaitJavascript(
                    view,
                    "render(${jsonString(sample)}, false, ${MAX_BITMAP_EDGE - 32})"
                )
                sb.appendLine("render() 返回: ${raw ?: "<null>"}")
                val size = raw?.let { parseSize(it) }
                sb.appendLine("解析尺寸: ${size?.let { "${it.width}x${it.height} 字号=${it.fontPx}px" } ?: "<解析失败>"}")

                val htmlLen = awaitJavascript(
                    view,
                    "(document.getElementById('out').innerHTML||'').length"
                )
                sb.appendLine("KaTeX 产出的 HTML 长度: ${htmlLen ?: "<null>"}")

                relayout(view)
                waitFrames(2)
                val w = (size?.width ?: 300).coerceIn(1, MAX_BITMAP_EDGE)
                val h = (size?.height ?: 120).coerceIn(1, MAX_BITMAP_EDGE)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                val (colors, dominant) = colorStats(bmp)
                val alphaInfo = alphaStats(bmp)
                sb.appendLine("直接抓图 ${w}x$h: 颜色数=$colors 主色=${hexOf(dominant)}")
                sb.appendLine("  像素: 总数=${alphaInfo.total} 不透明(alpha=255)=${alphaInfo.opaque} 可见(alpha>0)=${alphaInfo.visible} 最大alpha=${alphaInfo.maxAlpha}")
                sb.appendLine("  非透明像素包围盒: ${alphaInfo.box}")
                // 导出 PNG，adb pull 出来肉眼看，别再靠猜
                runCatching {
                    val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                    val file = java.io.File(dir, "math_probe.png")
                    java.io.FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    sb.appendLine("  PNG 已导出: ${file.absolutePath}")
                }.onFailure { sb.appendLine("  PNG 导出失败: ${it.message}") }
                bmp.recycle()
            } catch (error: Throwable) {
                sb.appendLine("自检异常: $error")
            }
            sb.toString()
        }
    }

    private fun hexOf(color: Int): String = String.format("#%08X", color)

    private data class AlphaInfo(
        val total: Int,
        val opaque: Int,
        val visible: Int,
        val maxAlpha: Int,
        val box: String
    )

    /** 逐像素统计 alpha：颜色数多不代表有内容，全是透明度抖动一样是空白。 */
    private fun alphaStats(bitmap: Bitmap): AlphaInfo {
        val w = bitmap.width
        val h = bitmap.height
        var total = 0
        var opaque = 0
        var visible = 0
        var maxAlpha = 0
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                total++
                val a = bitmap.getPixel(x, y) ushr 24
                if (a > maxAlpha) maxAlpha = a
                if (a == 255) opaque++
                if (a > 0) {
                    visible++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        val box = if (maxX < 0) "空" else "($minX,$minY)-($maxX,$maxY)"
        return AlphaInfo(total, opaque, visible, maxAlpha, box)
    }

    /** 采样统计：不同颜色数、主色（出现最多的颜色）。 */
    private fun colorStats(bitmap: Bitmap): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 1 to bitmap.getPixel(0, 0)
        val stepX = (w / 64).coerceAtLeast(1)
        val stepY = (h / 64).coerceAtLeast(1)
        val counts = HashMap<Int, Int>(128)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                counts[c] = (counts[c] ?: 0) + 1
                x += stepX
            }
            y += stepY
        }
        return counts.size to counts.maxBy { it.value }.key
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebView(appContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        // 关键：不设 SOFTWARE 层，WebView 内容在硬件 surface 里，draw 到软件画布必然全空
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val loaded = CompletableDeferred<Unit>()
        pageLoaded = loaded
        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "KaTeX 页面加载完成: $url")
                view?.let { relayout(it) }
                loaded.complete(Unit)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                Log.e(TAG, "KaTeX 页面加载失败: ${error?.description}")
                loaded.complete(Unit)
            }
        }
        relayout(view)
        view.loadUrl(ASSET_URL)
        webView = view
        return view
    }

    private suspend fun awaitJavascript(view: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) {
                    continuation.resume(value?.takeIf { it != "null" })
                }
            }
        }

    /** 等下一帧，保证 WebView 完成一次光栅化。 */
    private suspend fun awaitFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        Choreographer.getInstance().postFrameCallback {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    /**
     * 解析 `render()` 返回的 `{"w":..,"h":..,"fs":..}`（单位已是设备像素）。
     * evaluateJavascript 会把字符串再 JSON 编码一层，形如 `"{\"w\":1}"`，
     * 这里先剥外层引号再按对象解析，两种形态都吃下。
     */
    private data class Size(val width: Int, val height: Int, val fontPx: Float)

    private fun parseSize(raw: String): Size? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = runCatching { Json.parseToJsonElement(text).jsonPrimitive.content }
                .getOrDefault(text)
        }
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val w = obj["w"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt() ?: return null
        val h = obj["h"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt() ?: return null
        val fs = obj["fs"]?.jsonPrimitive?.content?.toFloatOrNull() ?: DEFAULT_FONT_PX
        return Size(w, h, fs)
    }

    /**
     * 判断是否「没画出东西」。
     * 不能只看 alpha：WebView 可能画了一层不透明背景，那样整张 alpha 都是 255。
     * 取采样像素里出现最多的颜色当背景，只要存在明显不同的像素就算有内容。
     */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return true
        val stepX = (w / 48).coerceAtLeast(1)
        val stepY = (h / 48).coerceAtLeast(1)
        val samples = ArrayList<Int>(1024)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                samples.add(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        val counts = HashMap<Int, Int>(64)
        for (c in samples) counts[c] = (counts[c] ?: 0) + 1
        val bg = counts.maxBy { it.value }.key
        val bgR = Color.red(bg)
        val bgG = Color.green(bg)
        val bgB = Color.blue(bg)
        for (c in samples) {
            if (abs(Color.red(c) - bgR) > TOLERANCE ||
                abs(Color.green(c) - bgG) > TOLERANCE ||
                abs(Color.blue(c) - bgB) > TOLERANCE
            ) {
                return false
            }
        }
        return true
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun jsonString(text: String): String =
        Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(text)
        )

    /**
     * **批量渲染**：一次 WebView 往返出多个公式。
     *
     * ## 为什么必须批量
     * 单条路径每渲染一个公式的代价是：2 次 Choreographer 帧等待 +
     * 2 次全画布 `draw` + 1 张全尺寸临时位图。
     * 一道题的解析里有 8~12 个公式，就是 16~24 次帧等待（每帧 16ms）、
     * 以及 N 张位图的分配与 GC。这正是「打开详情页很卡」的全部来源。
     *
     * 批量路径把 N 个公式**一次性塞进同一条长卷纵向排列**，
     * 只等 2 帧、只 draw 一次，然后按各行偏移切图。
     * 帧等待从 O(N) 降到 O(1)，这是唯一量级上的改进。
     *
     * @param items `(key, latex, displayMode)`。key 是调用方的缓存键，
     *   与 latex 分开是必要的：行内 `r:` 前缀的公式在 JS 侧要包 `\begin{aligned}`，
     *   但缓存键必须用**原始** key，否则命中不了单条路径写入的缓存。
     * @return key -> 渲染结果；某个失败不影响其他。
     */
    suspend fun renderAll(
        items: List<Triple<String, String, Boolean>>
    ): Map<String, RenderedMath?> = withContext(Dispatchers.Main) {
        if (items.isEmpty()) return@withContext emptyMap()
        // 超量分批：一张长卷的位图开销随公式数线性增长，
        // 一次画 40 个公式会比画 4 批 10 个更容易触发 GC 抖动。
        if (items.size > BATCH_LIMIT) {
            val merged = linkedMapOf<String, RenderedMath?>()
            items.chunked(BATCH_LIMIT).forEach { batch ->
                merged.putAll(renderAll(batch))
            }
            return@withContext merged
        }
        val results = linkedMapOf<String, RenderedMath?>()
        // 命中缓存的直接给值，不再进 WebView
        val pending = mutableListOf<Triple<String, String, Boolean>>()
        items.forEach { (key, latex, display) ->
            val hit = cache.get(key)
            if (hit != null) {
                results[key] = hit
            } else {
                pending.add(Triple(key, latex, display))
            }
        }
        if (pending.isEmpty()) return@withContext results

        mutex.withLock {
            try {
                val view = ensureWebView()
                if (withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { pageLoaded.await() } == null) {
                    Log.w(TAG, "KaTeX 页面加载超时，批量渲染放弃")
                    pending.forEach { (key, _) -> results[key] = null }
                    return@withLock
                }
                val script = buildBatchScript(pending)
                val raw = awaitJavascript(view, script)
                if (raw == null) {
                    Log.w(TAG, "批量渲染 evaluateJavascript 无返回")
                    pending.forEach { (key, _) -> results[key] = null }
                    return@withLock
                }
                val layout = parseBatchLayout(raw) ?: run {
                    Log.w(TAG, "批量布局解析失败, 原始返回(截断): ${raw.take(200)}")
                    pending.forEach { (key, _) -> results[key] = null }
                    return@withLock
                }
                // 视口要先撑到长卷高度，view.draw 才画得到全部内容。
                // 顺序反了只会拿到第一屏，量出的 sheetRect 也是截断的。
                relayoutForBatch(view, layout.sheetHeight)
                val sheet = captureSheet(view, layout.sheetWidth, layout.sheetHeight)
                if (sheet == null) {
                    pending.forEach { (key, _) -> results[key] = null }
                    return@withLock
                }
                layout.entries.forEach { entry ->
                    val key = entry.key
                    val value = slice(sheet, entry.x, entry.y, entry.width, entry.height)
                        ?.let { RenderedMath(it, entry.fontPx) }
                    if (value != null) cache.put(key, value)
                    results[key] = value
                }
                sheet.recycle()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.w(TAG, "批量渲染失败: $error")
                pending.forEach { (key, _) -> results[key] = null }
            }
        }
        results
    }

    /** 生成「把 N 个公式竖排进一条长卷」的 JS。 */
    private fun buildBatchScript(items: List<Triple<String, String, Boolean>>): String {
        val payload = items.joinToString(",") { (key, latex, display) ->
            // 批量路径绕过了 render()，清洗必须在这里也做一次
            "{k:${jsonString(key)},t:${jsonString(
                com.mistakebook.pipeline.LatexSanitizer.clean(latex)
            )},d:$display}"
        }
        return "renderBatch([$payload],${MAX_BITMAP_EDGE - 32})"
    }

    /** 抓整张「公式长卷」，一次 draw 拿到所有公式。 */
    private suspend fun captureSheet(view: WebView, width: Int, height: Int): Bitmap? {
        waitFrames(BATCH_FRAME_WAIT)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        if (alphaStats(bitmap).visible == 0) {
            bitmap.recycle()
            Log.w(TAG, "批量长卷为空白 ${width}x$height")
            return null
        }
        return bitmap
    }

    private fun slice(
        sheet: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val safeX = x.coerceIn(0, (sheet.width - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (sheet.height - 1).coerceAtLeast(0))
        val safeW = width.coerceAtMost(sheet.width - safeX)
        val safeH = height.coerceAtMost(sheet.height - safeY)
        if (safeW <= 0 || safeH <= 0) return null
        return runCatching { Bitmap.createBitmap(sheet, safeX, safeY, safeW, safeH) }.getOrNull()
    }

    private data class BatchEntry(
        val key: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val fontPx: Float
    )

    private data class BatchLayout(
        val sheetWidth: Int,
        val sheetHeight: Int,
        val entries: List<BatchEntry>
    )

    private fun parseBatchLayout(raw: String): BatchLayout? {
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        val w = obj.optInt("w", 0)
        val h = obj.optInt("h", 0)
        if (w <= 0 || h <= 0) return null
        val array = obj.optJSONArray("items") ?: return null
        val entries = mutableListOf<BatchEntry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            entries += BatchEntry(
                key = item.optString("k"),
                x = item.optInt("x"),
                y = item.optInt("y"),
                width = item.optInt("w"),
                height = item.optInt("h"),
                fontPx = item.optDouble("f", 0.0).toFloat()
            )
        }
        return BatchLayout(w, h, entries)
    }

    companion object {
        private const val TAG = "MathRenderer"
        private const val ASSET_URL = "file:///android_asset/katex/math.html"
        private const val CACHE_BYTES = 12 * 1024 * 1024
        private const val MAX_BITMAP_EDGE = 2048
        private const val PAGE_LOAD_TIMEOUT_MS = 8_000L
        private const val TOLERANCE = 12
        private const val DEFAULT_FONT_PX = 112f
        // 单位是设备像素；换算成 CSS 视口约 730px @3x，足够放下常规公式
        // 视口要足够宽：aligned 公式块可能很长，宽度不够就会触发字号缩小甚至裁切。
        // 高度给足，一块多行公式才不会被切掉。
        private const val VIEWPORT_WIDTH = 3200
        private const val VIEWPORT_HEIGHT = 1600

        /**
         * 批量渲染后等待的帧数。
         * 2 帧足够 KaTeX 完成排版与光栅化；再多只是白等。
         */
        private const val BATCH_FRAME_WAIT = 2

        /**
         * 批量渲染单次最多处理多少个公式。
         * 超出就分批：一张长卷位图按 ARGB_8888 算是 width×height×4 字节，
         * 公式多了长卷会高到吃内存并触发 GC，反而比多次小渲染更慢。
         */
        private const val BATCH_LIMIT = 12

        /** 批量长卷视口上限。12 个公式 × ~200 设备像素 + 余量，4096 绰绰有余。 */
        private const val BATCH_VIEWPORT_HEIGHT = 4096
    }
}
