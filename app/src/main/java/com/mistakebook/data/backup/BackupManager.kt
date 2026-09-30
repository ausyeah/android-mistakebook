package com.mistakebook.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mistakebook.data.AppFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地备份：zip = mistake_book.db + files/ 全量。
 *
 * - Android Q+ 用 MediaStore.Downloads 写入 Download/错题本/，不需要存储权限；
 * - Q 以下写入 app 私有目录后由分享按钮导出（避免申请 WRITE_EXTERNAL_STORAGE）。
 * 恢复是覆盖式：替换 db 与 files 之后需要重启进程才会生效（设置页会提示重启）。
 */
class BackupManager(
    private val context: Context,
    private val files: AppFiles
) {

    suspend fun export(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = "backup_${timestamp()}.zip"
            val entries = collectEntries()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/错题本"
                    )
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法写入下载目录")
                context.contentResolver.openOutputStream(uri)!!.use { stream ->
                    writeZip(stream, entries)
                }
                "Download/错题本/$name"
            } else {
                val dir = files.exportDir.apply { mkdirs() }
                val target = File(dir, name)
                target.outputStream().use { stream -> writeZip(stream, entries) }
                target.absolutePath
            }
        }
    }

    /** 解压并覆盖数据库与文件目录，返回恢复后的题目数量。 */
    suspend fun restore(input: InputStream): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
            temp.mkdirs()
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val target = File(temp, entry.name)
                        if (target.canonicalPath.startsWith(temp.canonicalPath)) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val restoredDb = File(temp, "mistake_book.db")
            if (!restoredDb.exists()) error("备份中缺少数据库文件")
            restoredDb.copyTo(files.databaseFile, overwrite = true)
            val restoredFiles = File(temp, "files")
            if (restoredFiles.exists()) {
                restoredFiles.copyRecursively(context.filesDir, overwrite = true)
            }
            temp.deleteRecursively()
            countQuestions()
        }
    }

    private fun collectEntries(): List<Pair<File, String>> {
        val entries = mutableListOf<Pair<File, String>>()
        val filesRoot = context.filesDir
        listOf("crops", "questions", "imports", "mineru").forEach { dirName ->
            File(filesRoot, dirName).walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    entries += file to "files/${file.relativeTo(filesRoot).path}"
                }
        }
        return entries
    }

    private fun writeZip(stream: OutputStream, entries: List<Pair<File, String>>) {
        ZipOutputStream(stream).use { zip ->
            zip.putNextEntry(ZipEntry(files.databaseFile.name))
            files.databaseFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            entries.forEach { (file, entryName) ->
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())

    private fun countQuestions(): Int = runCatching {
        val db = SQLiteDatabase.openDatabase(
            files.databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        db.rawQuery("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }.getOrDefault(0)
}
