package com.mistakebook.data.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.mistakebook.data.AppFiles
import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.data.pdf.PdfTextExtractor
import com.mistakebook.domain.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 附件处理结果。
 *
 * **刻意不是 [ChatAttachment] 实体**：实体带 `messageId` 外键，
 * 而用户是**先选附件、后发消息**——选的那一刻消息还不存在，插不进去。
 * 所以准备阶段只在内存里，发送时才由 ChatRepository 落库。
 */
data class PreparedAttachment(
    val localPath: String,
    val fileName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val sizeBytes: Long = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val textExcerpt: String = "",
    val extractedChars: Int = 0,
    val errorMessage: String? = null
) {
    val ok: Boolean get() = errorMessage == null
}

/**
 * 附件准备管线。**纯单机，不上传**。
 *
 * - 图片：按长边压到 [longEdgePx]、JPEG q80 存进私有目录，
 *   之后由 [ChatImageDataUrls] 编成 base64 data URL 随请求发出
 * - PDF：复用 [PdfTextExtractor] 抽文本
 * - TXT / MD：直接读
 * - DOCX：[DocxText] 从 zip 里抽正文
 * - 其它类型：保留文件但不参与构造请求（[AttachmentKind.OTHER]）
 *
 * ## 为什么要拷进私有目录
 *
 * 用户从相册或文件管理器选的原文件随时可能被删或被移动，
 * 而对话历史是长期存在的。原文件没了，历史里的附件就渲染不出来、
 * 下一轮对话也带不上去了。
 */
class ChatAttachmentPreparer(
    private val context: Context,
    private val appFiles: AppFiles
) {

    suspend fun prepare(sessionId: Long, uri: Uri, longEdgePx: Int): PreparedAttachment =
        withContext(Dispatchers.IO) {
            val fileName = displayNameOf(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            val kind = classify(fileName, context.contentResolver.getType(uri))
            val extension = extensionFor(kind, fileName)

            runCatching {
                val target = appFiles.chatFile(sessionId, fileName.substringBeforeLast('.'), extension)
                val size = copyTo(uri, target)
                require(size in 1..MAX_FILE_BYTES) { "文件太大：$size" }
                when (kind) {
                    AttachmentKind.IMAGE -> processImage(target, size, longEdgePx)
                    AttachmentKind.PDF -> processPdf(target, fileName, size)
                    AttachmentKind.TEXT -> processPlainText(target, fileName, size)
                    AttachmentKind.DOCX -> processDocx(target, fileName, size)
                    AttachmentKind.OTHER -> PreparedAttachment(
                        localPath = target.absolutePath,
                        fileName = fileName,
                        mimeType = "",
                        kind = kind,
                        sizeBytes = size
                    )
                }
            }.getOrElse { throwable ->
                PreparedAttachment(
                    localPath = "",
                    fileName = fileName,
                    mimeType = "",
                    kind = kind,
                    errorMessage = throwable.message ?: "处理失败"
                )
            }
        }

    // ------------------------------------------------------------ 处理

    private fun copyTo(uri: Uri, target: File): Long {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件")
        input.use { stream ->
            target.outputStream().use { output -> stream.copyTo(output) }
        }
        return target.length()
    }

    private fun processImage(file: File, size: Long, longEdgePx: Int): PreparedAttachment {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: return PreparedAttachment(
                localPath = file.absolutePath,
                fileName = file.name,
                mimeType = "image/jpeg",
                kind = AttachmentKind.IMAGE,
                sizeBytes = size,
                errorMessage = "无法解码这张图片"
            )
        val scaled = scaleDown(bitmap, longEdgePx)
        // createScaledBitmap 在需要缩放时返回新 Bitmap，原图可以立刻释放；
        // 不缩放时返回的是同一个对象，不能 recycle——那会把 scaled 一起废掉。
        if (scaled !== bitmap) bitmap.recycle()
        val width = scaled.width
        val height = scaled.height
        compress(scaled, file)
        scaled.recycle()
        return PreparedAttachment(
            localPath = file.absolutePath,
            fileName = file.name,
            mimeType = "image/jpeg",
            kind = AttachmentKind.IMAGE,
            sizeBytes = file.length(),
            widthPx = width,
            heightPx = height
        )
    }

    private fun scaleDown(bitmap: Bitmap, longEdgePx: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= longEdgePx || longEdgePx <= 0) return bitmap
        val ratio = longEdgePx.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun compress(bitmap: Bitmap, target: File) {
        target.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }

    private fun processPdf(file: File, fileName: String, size: Long): PreparedAttachment {
        val pages = PdfTextExtractor.extractAll(file.readBytes())
        val text = pages.filter { it.isNotBlank() }.joinToString("\n")
        return excerpt(file, fileName, "application/pdf", AttachmentKind.PDF, size, text)
    }

    private fun processPlainText(file: File, fileName: String, size: Long): PreparedAttachment {
        val text = file.readText(Charsets.UTF_8)
        return excerpt(file, fileName, "text/plain", AttachmentKind.TEXT, size, text)
    }

    private fun processDocx(file: File, fileName: String, size: Long): PreparedAttachment {
        val text = DocxText.extract(file.readBytes())
        return excerpt(file, fileName, DOCX_MIME, AttachmentKind.DOCX, size, text)
    }

    /**
     * 统一处理「抽出来的文本」：截断入库。
     *
     * 截断到 [MAX_EXCERPT_CHARS] 而不是塞全文，两个原因：
     * 长文档全文入库会让表膨胀；而上下文预算总共才 8000 字符，
     * 一条附件吃掉大半就等于把历史对话全挤掉了。
     */
    private fun excerpt(
        file: File,
        fileName: String,
        mimeType: String,
        kind: AttachmentKind,
        size: Long,
        text: String
    ): PreparedAttachment {
        val trimmed = text.trim()
        return PreparedAttachment(
            localPath = file.absolutePath,
            fileName = fileName,
            mimeType = mimeType,
            kind = kind,
            sizeBytes = size,
            textExcerpt = trimmed.take(MAX_EXCERPT_CHARS),
            extractedChars = trimmed.length,
            errorMessage = if (trimmed.isEmpty()) "没有从 $fileName 里读到任何文字" else null
        )
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    companion object {

        // ------------------------------------------------------------ 分派

        /** 按文件名与 MIME 判断类型。**纯逻辑，可单测。** */
        fun classify(fileName: String, mimeType: String?): AttachmentKind {
            val lower = fileName.lowercase()
            val mime = mimeType?.lowercase().orEmpty()
            return when {
                mime.startsWith("image/") -> AttachmentKind.IMAGE
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".bmp") ->
                    AttachmentKind.IMAGE

                mime == "application/pdf" || lower.endsWith(".pdf") -> AttachmentKind.PDF
                lower.endsWith(".docx") || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    AttachmentKind.DOCX

                // .doc 是老二进制格式，零依赖解不开——归 OTHER 而不是 DOCX，
                // 因为归错类型会让 UI 显示「就绪」但模型什么也收不到
                mime.startsWith("text/") || lower.endsWith(".txt") || lower.endsWith(".md") ||
                    lower.endsWith(".markdown") || lower.endsWith(".csv") -> AttachmentKind.TEXT

                else -> AttachmentKind.OTHER
            }
        }

        /** 落盘扩展名。图片统一存 jpg——已经是 jpg 的不必再压一遍。 */
        fun extensionFor(kind: AttachmentKind, fileName: String): String = when (kind) {
            AttachmentKind.IMAGE -> "jpg"
            AttachmentKind.PDF -> "pdf"
            AttachmentKind.DOCX -> "docx"
            AttachmentKind.TEXT -> if (fileName.lowercase().endsWith(".md")) "md" else "txt"
            AttachmentKind.OTHER -> fileName.substringAfterLast('.', "bin").ifBlank { "bin" }
        }
        /** 单文件上限。超过直接拒绝，不做「读一半」。 */
        const val MAX_FILE_BYTES = 20L * 1024 * 1024

        const val JPEG_QUALITY = 80

        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        /**
         * 抽出的文本最多入库多少字符。
         *
         * 取 4000：够模型看清文档在讲什么，又不会让一条附件把
         * 8000 字符的上下文预算吃掉一半以上。
         */
        const val MAX_EXCERPT_CHARS = 4000
    }
}
