package com.mistakebook.print

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * **HTML → PDF**：先在 WebView 里排版，再交给系统的打印管线出 PDF。
 *
 * ## 为什么不再手写 Canvas 排版
 *
 * 原来 [PdfExporter] 用 `PdfDocument` + `Canvas` 自己断行、自己算公式尺寸、自己分页。
 * 出来的东西「公式都对，但高低大小各种不协调」——这类排版要同时照顾基线对齐、
 * 行高、公式的字号缩放、分页时的截断，任何一处算错就全局失调，而且**很难看出是哪一处错了**。
 *
 * 浏览器天生把这件事做对了，而用户实测 HTML 的效果最好。
 * 所以：**内容仍是同一份 [ExportDoc]，排版交给 WebView**。
 * 这也让三种格式真正共用一套内容判据（见 [ExportModel]）。
 *
 * ## 公式为什么在 PDF 里用 KaTeX 而不是 MathML
 *
 * 分享出去的 HTML 用**原生 MathML**——它是文字、可搜索、不依赖 JS、离线可看。
 * 但 PDF 走的是本进程的 WebView，**它的版本不保证支持 MathML**（MathML Core 要 Chrome 109+）。
 * 而 [com.mistakebook.math.MathRenderer] 用的 `assets/katex/math.html` 已经证明
 * KaTeX 在这类设备的 WebView 上能正常渲染——所以 PDF 这条路用 KaTeX 兜底，不赌 WebView 版本。
 *
 * ## 零新增依赖
 *
 * `WebView.createPrintDocumentAdapter` 是系统 API，就是「保存为 PDF」用的同一套管线。
 */
class HtmlPdfExporter(
    private val context: Context,
    private val htmlExporter: HtmlExporter
) {

    /**
     * @param doc 内容来源，与其它两种格式共用。
     * @param target 输出的 PDF 文件。
     */
    suspend fun export(doc: ExportDoc, target: File): ExportResult = withContext(Dispatchers.Main) {
        val built = htmlExporter.buildHtml(doc, HtmlExporter.FormulaMode.KATEX)
        target.parentFile?.mkdirs()

        val webView = WebView(context)
        try {
            if (!awaitPageReady(webView, built.html)) {
                return@withContext ExportResult(
                    file = target,
                    skipped = listOf("页面加载超时，未生成 PDF")
                )
            }
            // KaTeX 渲染是同步的，但字体度量要等一帧；过早取尺寸会拿到半成品。
            settleLayout(webView)
            if (!writePdf(webView, target)) {
                return@withContext ExportResult(
                    file = target,
                    skipped = built.skipped + "PDF 写出失败或超时"
                )
            }
            ExportResult(file = target, skipped = built.skipped)
        } finally {
            // 不 destroy 会泄漏整个 WebView——它比一个 Activity 还难回收
            runCatching { webView.destroy() }
        }
    }

    // ------------------------------------------------------------ 加载

    private suspend fun awaitPageReady(webView: WebView, html: String): Boolean =
        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                // baseURL 指向 assets，`katex.min.css` / `katex.min.js` 才能解析成相对路径
                webView.settings.javaScriptEnabled = true
                webView.loadDataWithBaseURL(ASSET_BASE, html, "text/html", "UTF-8", null)
            }
        } ?: false

    /**
     * 强制量一次布局。
     *
     * 打印适配器依赖 WebView 的测量结果，而 `loadDataWithBaseURL` 之后测量结果
     * 可能还是 0。不量一次的话 `onLayout` 会拿到 0×0 的页面。
     */
    private suspend fun settleLayout(webView: WebView) {
        delay(SETTLE_MS)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(PRINT_VIEWPORT_PX, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)
    }

    // ------------------------------------------------------------ 打印

    /**
     * 两个回调都传 `null`。
     *
     * [PrintDocumentAdapter.LayoutResultCallback] 和 `WriteResultCallback`
     * 的构造器是 **package-private**，`new` 不出来——外部想「知道打印什么时候完成」
     * 只能走回调，而这条路对第三方是堵死的。
     *
     * 传 `null` 时框架走同步路径：`onWrite` 返回时文件已写完。
     * 但这句话没有文档背书，本机也没有设备可验，所以后面
     * [awaitStablePdf] 再按文件大小稳定性兜一层——同步、异步都不会误判。
     */
    private suspend fun writePdf(webView: WebView, target: File): Boolean {
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            // 页边距交给 CSS 的 @page margin。两处都设会叠加成双倍边距
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        val adapter = webView.createPrintDocumentAdapter(target.nameWithoutExtension)
        val signal = CancellationSignal()
        val descriptor = runCatching {
            ParcelFileDescriptor.open(
                target,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE
            )
        }.getOrNull() ?: return false

        return try {
            adapter.onLayout(null, attributes, signal, null, Bundle())
            adapter.onWrite(arrayOf(PageRange.ALL_PAGES), descriptor, signal, null)
            awaitStablePdf(target)
        } catch (error: Throwable) {
            android.util.Log.e("HtmlPdfExporter", "WebView 打印失败", error)
            false
        } finally {
            runCatching { descriptor.close() }
        }
    }

    /**
     * 等 PDF 文件大小连续两次采样不再变化。
     *
     * 不这么做的话，「[onWrite] 是不是同步的」这个假设一旦不成立，
     * 就会在文件还没写完时就去分享——用户收到一个半截的 PDF。
     * 反过来（同步时）也只是多花 [STABLE_SAMPLES] × [POLL_MS]，可以接受。
     */
    private suspend fun awaitStablePdf(target: File): Boolean =
        withTimeoutOrNull(PRINT_TIMEOUT_MS) {
            var previous = -1L
            var stable = 0
            while (stable < STABLE_SAMPLES) {
                delay(POLL_MS)
                val size = target.length()
                if (size > 0 && size == previous) stable++ else stable = 0
                previous = size
            }
            true
        } ?: false

    private companion object {
        /** assets 里的 katex 目录。`katex.min.css` / `katex.min.js` 就在这下面。 */
        const val ASSET_BASE = "file:///android_asset/katex/"

        const val LOAD_TIMEOUT_MS = 20_000L

        /** 排版沉降时间。等 KaTeX 脚本跑完 + 字体度量完成。 */
        const val SETTLE_MS = 700L

        /** 打印超时。题量大时偏慢，但不该无限等。 */
        const val PRINT_TIMEOUT_MS = 120_000L

        /** 轮询间隔与「连续多少次不变算稳定」。 */
        const val POLL_MS = 200L
        const val STABLE_SAMPLES = 3

        /** 虚拟视口宽度（px）。A4 在 96dpi 下约 794px，取宽一点避免长公式被压缩。 */
        const val PRINT_VIEWPORT_PX = 1000
    }
}
