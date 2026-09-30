package com.mistakebook.print

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.mistakebook.data.AppFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF 产出与分享：Q+ 通过 MediaStore 落到 Download/错题本/，Q 以下放 app 私有目录，
 * 两种情况下都用一个本地文件负责分享（FileProvider 授权）。
 */
class PdfPublisher(
    private val context: Context,
    private val files: AppFiles
) {

    data class Output(
        val file: File,
        val displayPath: String,
        val shareUri: Uri
    )

    suspend fun publish(pdf: File): Output = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val name = "错题本_$stamp.pdf"
        var displayPath = pdf.absolutePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/错题本"
                    )
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        pdf.inputStream().use { input -> input.copyTo(output) }
                    }
                    displayPath = "Download/错题本/$name"
                }
            }
        }
        val shareTarget = File(files.shareCacheDir.apply { mkdirs() }, name)
        pdf.copyTo(shareTarget, overwrite = true)
        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shareTarget
        )
        Output(file = shareTarget, displayPath = displayPath, shareUri = shareUri)
    }

    /** 新建一个待写入的本地 PDF 文件。 */
    fun createTempFile(): File {
        files.exportDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        return File(files.exportDir, "错题本_$stamp.pdf")
    }
}
