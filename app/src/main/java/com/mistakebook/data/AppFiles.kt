package com.mistakebook.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * App 专属文件目录规划。
 *
 * 所有图片与中间产物都放在 app-specific files，卸载即清理；备份/恢复走 zip。
 */
class AppFiles(context: Context) {

    private val filesRoot: File = context.filesDir

    val cropDir: File = File(filesRoot, "crops")

    val importDir: File = File(filesRoot, "imports")

    val exportDir: File = File(filesRoot, "exports")

    val shareCacheDir: File = File(context.cacheDir, "share")

    val databaseFile: File = context.getDatabasePath("mistake_book.db")

    fun questionDir(taskId: Long): File = File(filesRoot, "questions/$taskId")

    fun questionImageDir(taskId: Long): File = File(questionDir(taskId), "images")

    fun mineruDir(taskId: Long): File = File(filesRoot, "mineru/$taskId")

    fun newCropFile(): File {
        cropDir.mkdirs()
        return File(cropDir, "${UUID.randomUUID()}.jpg")
    }

    fun newImportFile(extension: String): File {
        importDir.mkdirs()
        return File(importDir, "${UUID.randomUUID()}.$extension")
    }

    /** PDF 每页栅格化后的临时图片。 */
    fun newPdfPageFile(): File {
        val dir = File(importDir, "pdf_pages")
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }
}
