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
        // 请求里只发了一个文件，所以 file_urls 也应当只有一个。
        // 真出现多个时**不能随便取第一个**——若服务端返回顺序与请求顺序不一致，
        // 我们会把文件传到「另一个文件」的名下，于是服务端那边永远等不到我们的文件，
        // 状态就一直停在 waiting-file / pending，而用量统计却在涨（别的文件被解析了）。
        val uploadUrl = batch.file_urls.singleOrNull()
            ?: if (batch.file_urls.isEmpty()) {
                return@withContext fail(ApiErrorKind.BAD_RESPONSE, "未获得上传地址")
            } else {
                return@withContext fail(
                    ApiErrorKind.BAD_RESPONSE,
                    "服务端返回了 ${batch.file_urls.size} 个上传地址（预期 1 个），无法确定哪个对应本次文件"
                )
            }

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
        // 最后一次观察到的状态，用于超时时的错误文案
        var lastSeenState = ""
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
            // 每轮都把诊断信息写进 stage——**用户看不到 logcat**，
            // 界面上的这行字是我们唯一的排障依据。
            // 包含：已等待秒数、服务端 state、返回项数、文件名匹配结果。
            val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
            val rawItems = body.data?.extract_result.orEmpty()

            val item = matchItem(rawItems, fileName)
            if (item != null) lastSeenState = item.state

            if (item != null) {
                val percent = item.progressPercent()
                val detail = buildString {
                    append("MinerU ").append(stageVerb(item.state)).append(' ')
                    percent?.let { append(it).append("% ") }
                    append("· ").append(elapsedSec).append("s")
                    append(" · state=").append(item.state.ifBlank { "(空)" })
                    append(" · 返回").append(rawItems.size).append("项")
                }
                onStage(detail)
            } else {
                onStage(
                    "MinerU 等待服务端登记 · ${elapsedSec}s · 返回${rawItems.size}项" +
                        if (rawItems.isEmpty()) "" else " · 文件名未匹配"
                )
            }

            // `waiting-file` = 服务端还没收到我们上传的文件。
            // 这是**我们这边**的问题（上传没落到位），继续轮询只会白等，
            // 必须立刻报错并说清该查什么。
            if (item?.state == "waiting-file" && elapsedSec > WAITING_FILE_GRACE_SEC) {
                return fail(
                    ApiErrorKind.BAD_RESPONSE,
                    "文件已提交但服务端 90 秒仍未收到。" +
                        "通常是上传未成功——请检查网络后重试；若反复出现请换个网络环境试"
                )
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
        // 超时文案要带**最后观察到的状态**。
        //
        // 只说「解析超时」的话，用户无法判断该重试还是该等：
        // 停在 `pending` 通常是服务端队列长（重试只会更糟），
        // 停在 `running` 更可能是文件太大或内容复杂。
        val waitedSec = (System.currentTimeMillis() - startedAt) / 1000
        val lastState = lastSeenState.ifBlank { "未知" }
        val advice = when (lastState) {
            "pending" -> "服务端队列较长，重试会排到更后面。建议稍后再试，或减少同时提交的任务数。"
            "waiting-file" -> "服务端始终没收到文件，请检查上传时的网络。"
            "running", "converting" -> "任务在服务端处理中但耗时过长，可能是图片过大或页数过多。"
            else -> "可以重试；若反复超时请检查网络或稍后再试。"
        }
        return fail(
            ApiErrorKind.TIMEOUT,
            "MinerU 解析超时（已等待 ${waitedSec / 60} 分 ${waitedSec % 60} 秒，最后状态 $lastState）。$advice"
        )
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

    /** 状态对应的中文动作词。纯粹为了界面可读。 */
    @androidx.annotation.VisibleForTesting
    fun stageVerb(state: String): String = when (state) {
        "done" -> "完成"
        "failed" -> "失败"
        "running" -> "解析中"
        "pending" -> "排队中"
        "waiting-file" -> "等待文件上传"
        "converting" -> "转换中"
        else -> "处理中"
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

/**
 * `waiting-file` 状态宽限秒数。
 *
 * 超过这个时间还停在这个状态，说明我们上传的文件服务端一直没收到——
 * 那是**我们这边**的问题（上传没落到位），不是队列问题，
 * 继续轮询只是白等，不如立刻报错说清该查什么。
 */
const val WAITING_FILE_GRACE_SEC = 90L
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

/**
 * 从批量结果里找出属于本次文件的那一项。
 *
 * ## 为什么不留 `items.firstOrNull()` 兜底
 *
 * 早先最后一行是 `return items.firstOrNull()`。一旦文件名对不上
 * （服务端改名、加后缀、编码差异），就会**静默拿到别人的那一项**——
 * 显示的是别的文件的状态，于是「一直 pending」这种症状
 * 完全无法归因：是它真的在排队，还是我根本在看错的那一项？
 *
 * 现在只接受三种明确匹配，且**列表里有多项时不再猜**：
 * 猜错的代价是用户看到错误的状态却无从判断。
 *
 * 提成顶层函数是为了**能单测**。放成 private 时测试只能自己抄一份
 * 实现——而本项目已经吃过三次「测试复制实现」的亏（BatchProtocolTest、
 * DocxPackageTest、ManualEntryTest）：抄一份的行为，测的是那份拷贝，
 * 真实的代码改了它不会红。
 */
@androidx.annotation.VisibleForTesting
fun matchItem(items: List<ExtractResultItem>, fileName: String): ExtractResultItem? {
    if (items.isEmpty()) return null
    // **查询名为空时不参与匹配。**
    // 服务端偶尔对某些文件不返回 `file_name`（空串），
    // 那时 `it.fileName == fileName` 会把那一项当成匹配成功，
    // 而它未必是本次的文件——等于又回到了「静默拿到别人的那一项」。
    if (fileName.isNotBlank()) {
        items.firstOrNull { it.fileName == fileName }?.let { return it }
        items.firstOrNull { it.name == fileName }?.let { return it }
        items.firstOrNull {
            it.fileName.substringAfterLast('/') == fileName.substringAfterLast('/')
        }?.let { return it }
    }
    // 只有一项时它必然是本次的（批量接口对单文件也返回单项）
    if (items.size == 1) return items[0]
    return null
}

class MineruException(message: String) : Exception(message)
