package com.mistakebook.pipeline

import com.mistakebook.data.AppFiles
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import com.mistakebook.net.HttpFactory
import com.mistakebook.net.mineru.FileUrlsData
import com.mistakebook.net.mineru.FileUrlsItem
import com.mistakebook.net.mineru.FileUrlsRequest
import com.mistakebook.net.mineru.ExtractResultItem
import com.mistakebook.net.mineru.MineruApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

// 识别产物：Markdown 主结果（图片路径已改写为绝对路径）+ 附带的图片文件。
data class MineruOutcome(
    val batchId: String,
    val markdown: String,
    val imagePaths: List<String>,
    val rawDir: File
)

/**
 * MinerU v4 本地文件上传链路（PRD 4.1）：
 * 申请上传地址 -> PUT 上传 -> 轮询 -> 下载 zip -> 解压 -> 图片与路径整理。
 *
 * 轮询策略：首延迟 2s，之后每 3s；连续 5 次 running 后按 3/5/8/10s 退避；总超时 300s。
 */
class MineruClient(
    private val api: MineruApi,
    private val files: AppFiles
) {

    suspend fun recognize(
        taskId: Long,
        file: File,
        fileName: String,
        mineruKey: String,
        modelVersion: String,
        language: String,
        forceOcr: Boolean,
        onStage: suspend (String) -> Unit
    ): ApiResult<MineruOutcome> = withContext(Dispatchers.IO) {
        if (mineruKey.isBlank()) {
            return@withContext fail(ApiErrorKind.NO_KEY)
        }
        val auth = "Bearer $mineruKey"

        onStage("上传识别请求…")
        val batch = when (val r = requestUploadUrl(auth, fileName, modelVersion, language, forceOcr)) {
            is ApiResult.Success -> r.data
            is ApiResult.Failure -> return@withContext r
        }
        val uploadUrl = batch.file_urls.firstOrNull()
            ?: return@withContext fail(ApiErrorKind.BAD_RESPONSE, "未获得上传地址")

        // 关键：OSS 签名不允许带 Content-Type 头，带了会 SignatureDoesNotMatch(403)
        onStage("上传文件 ${formatSize(file.length())}…")
        val uploaded = rethrowCancellation {
            api.uploadFile(
                uploadUrl = uploadUrl,
                body = file.asRequestBody()
            )
        }.getOrElse { return@withContext ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!uploaded.isSuccessful) {
            return@withContext fail(
                HttpFactory.httpError(uploaded.code(), uploaded.errorBody()?.string().orEmpty())
            )
        }

        pollResults(auth, batch.batch_id, fileName, taskId, onStage)
    }

    private suspend fun requestUploadUrl(
        auth: String,
        fileName: String,
        modelVersion: String,
        language: String,
        forceOcr: Boolean
    ): ApiResult<FileUrlsData> {
        val response = rethrowCancellation {
            api.fileUrlsBatch(
                authorization = auth,
                request = FileUrlsRequest(
                    files = listOf(FileUrlsItem(name = fileName, is_ocr = forceOcr)),
                    model_version = modelVersion,
                    language = language
                )
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        if (!body.isOk() || body.data == null) {
            return fail(ApiError(kind = ApiErrorKind.AUTH, serverMessage = body.msg))
        }
        return ApiResult.Success(body.data)
    }

    private suspend fun pollResults(
        auth: String,
        batchId: String,
        fileName: String,
        taskId: Long,
        onStage: suspend (String) -> Unit
    ): ApiResult<MineruOutcome> {
        val startedAt = System.currentTimeMillis()
        var runningCount = 0
        var first = true
        while (System.currentTimeMillis() - startedAt < TOTAL_TIMEOUT_MS) {
            val waitMs = when {
                first -> {
                    first = false
                    FIRST_DELAY_MS
                }
                runningCount >= 5 -> {
                    val step = (runningCount - 5).coerceAtMost(BACKOFF.size - 1)
                    BACKOFF[step]
                }
                else -> POLL_INTERVAL_MS
            }
            delay(waitMs)

            val response = rethrowCancellation { api.extractResults(auth, batchId) }
                .getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
            }
            if (!body.isOk()) {
                // -60012 task not found 结合 ping 语义即可判断 Key 有效性，这里一律按错误处理
                return fail(ApiError(kind = ApiErrorKind.AUTH, serverMessage = body.msg))
            }
            val item = matchItem(body.data?.extract_result.orEmpty(), fileName)
                // 数组为空 = 还没排上队，不算错。
                // 早先直接报「未找到本次文件的解析结果」失败，
                // 而刚提交的任务确实会短暂返回空数组。
                ?: if (body.data?.extract_result.isNullOrEmpty()) {
                    onStage("MinerU 排队中…")
                    null
                } else {
                    return fail(ApiErrorKind.BAD_RESPONSE, "未找到本次文件的解析结果")
                }

            // 每轮都报一次，带上服务端的状态原文。
            //
            // 以前只有 `extract_progress` 存在时才报，而那个字段解析不出来
            // （它是对象不是标量，见 MineruDto.progressPercent）→
            // 整轮循环一次都没报过 → 界面上一句固定文案 + 无限转圈，
            // 与「真卡死」无法区分，也拿不到任何排障信息。
            if (item != null) {
                val percent = item.progressPercent()
                val label = percent?.let { "MinerU 解析中 $it%" } ?: "MinerU 解析中（${item.state}）"
                onStage(label)
            }

            when (item?.state) {
                null -> runningCount++
                "done" -> {
                    val zipUrl = item.fullZipUrl
                        ?: return fail(ApiErrorKind.BAD_RESPONSE, "解析完成但未返回结果包地址")
                    return downloadAndExtract(zipUrl, taskId, batchId)
                }
                "failed" -> {
                    return fail(ApiErrorKind.SERVER, item.errMsg.orEmpty().ifBlank { "MinerU 解析失败" })
                }
                else -> runningCount++
            }
        }
        return fail(ApiErrorKind.TIMEOUT, "MinerU 解析超时")
    }

    private suspend fun downloadAndExtract(
        zipUrl: String,
        taskId: Long,
        batchId: String
    ): ApiResult<MineruOutcome> {
        val response = rethrowCancellation { api.downloadZip(zipUrl) }
            .getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val mineruDir = files.mineruDir(taskId).apply { mkdirs() }
        val zipFile = File(mineruDir, "result.zip")
        body.byteStream().use { input ->
            FileOutputStream(zipFile).use { output -> input.copyTo(output) }
        }
        val markdownFile = unzip(zipFile, mineruDir)
            ?: return fail(ApiErrorKind.BAD_RESPONSE, "结果包中未找到 full.md")
        var markdown = markdownFile.readText()
        val imageDir = files.questionImageDir(taskId)
        imageDir.mkdirs()
        val referenced = collectImageRefs(markdown)
        val absolutePaths = mutableListOf<String>()
        referenced.forEach { ref ->
            val source = resolveRef(mineruDir, ref) ?: return@forEach
            val target = File(imageDir, source.name)
            runCatching { source.copyTo(target, overwrite = true) }
            if (target.exists()) {
                absolutePaths.add(target.absolutePath)
                markdown = markdown.replace(ref, target.absolutePath)
            }
        }
        return ApiResult.Success(
            MineruOutcome(
                batchId = batchId,
                markdown = markdown,
                imagePaths = absolutePaths,
                rawDir = mineruDir
            )
        )
    }

    private fun unzip(zipFile: File, targetDir: File): File? {
        var markdown: File? = null
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = File(targetDir, entry.name)
                    // 防 zip slip
                    if (out.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        if (entry.name.substringAfterLast('/') == "full.md") markdown = out
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return markdown
    }

    private fun matchItem(items: List<ExtractResultItem>, fileName: String): ExtractResultItem? {
        items.firstOrNull { it.fileName == fileName }?.let { return it }
        items.firstOrNull { it.name == fileName }?.let { return it }
        items.firstOrNull {
            it.fileName.substringAfterLast('/') == fileName.substringAfterLast('/')
        }?.let { return it }
        return items.firstOrNull()
    }

    private fun collectImageRefs(markdown: String): List<String> =
        Regex("!\\[[^\\]]*\\]\\(([^)]+)\\)").findAll(markdown)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.startsWith("http") }
            .distinct()
            .toList()

    private fun resolveRef(root: File, ref: String): File? {
        val cleaned = ref.substringBefore('#').substringBefore('?')
        val direct = File(root, cleaned)
        if (direct.exists()) return direct
        return root.walkTopDown().firstOrNull {
            it.isFile && (it.name == cleaned.substringAfterLast('/') || it.path.endsWith(cleaned))
        }
    }

    private fun fail(kind: ApiErrorKind, message: String = ""): ApiResult.Failure =
        ApiResult.Failure(ApiError(kind = kind, serverMessage = message))

    private fun fail(error: ApiError): ApiResult.Failure = ApiResult.Failure(error)

    /** 设置页「测试连接」：ping 返回 -60012(task not found) 即视为 Key 有效。 */
    suspend fun testConnection(mineruKey: String): ApiResult<Boolean> {
        if (mineruKey.isBlank()) return fail(ApiErrorKind.NO_KEY)
        val response = rethrowCancellation { api.extractResults("Bearer $mineruKey", "ping") }
            .getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!response.isSuccessful) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        if (body.isOk()) return ApiResult.Success(true)
        return if (body.code == PING_TASK_NOT_FOUND || body.msg.contains("not found", ignoreCase = true)) {
            ApiResult.Success(true)
        } else {
            fail(ApiError(kind = ApiErrorKind.AUTH, serverMessage = body.msg))
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / 1024 / 1024} MB"

    companion object {
        const val PING_TASK_NOT_FOUND = -60012
        /**
 * 轮询总预算。
 *
 * **不是 300 秒。** 循环条件只在每轮开头检查一次，而单次 HTTP 调用受
 * `HttpFactory.CALL_TIMEOUT_SECONDS = 300` 约束，最坏退避 10s。
 * 于是最坏路径 ≈ 300（预算耗尽）+ 300（单次 call 卡满）+ 10 ≈ **10 分钟**才吐超时。
 * 用户看到的就是「一直在加载」，远超预期。
 */
const val TOTAL_TIMEOUT_MS = 360_000L
        const val FIRST_DELAY_MS = 2_000L
        const val POLL_INTERVAL_MS = 3_000L
        val BACKOFF = longArrayOf(3_000L, 5_000L, 8_000L, 10_000L)
    }

/**
 * 执行一个会弃异常的块，但**取消异常直接抛出**。
 *
 * ## 为什么不能直接用 [runCatching]
 *
 * `runCatching` 捕获 `Throwable`，而 [kotlinx.coroutines.CancellationException] 正是
 * `Throwable` 的子类。所以用户按「取消」时若正处于 HTTP 调用中，
 * 取消被转成普通 `Failure`，协程继续跑到结束。
 *
 * 界面上就是「识别失败，请重试」——用户重试即是真的重新上传一遍，
 * 又消耗一次 MinerU 额度。为一个取消按钮供应事故更多额度。
 */
private inline fun <T> rethrowCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
}

class MineruException(message: String) : Exception(message)
