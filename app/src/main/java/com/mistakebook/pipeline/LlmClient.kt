package com.mistakebook.pipeline

import android.graphics.Bitmap
import com.mistakebook.data.ImageNormalizer
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import com.mistakebook.net.HttpFactory
import com.mistakebook.net.llm.ChatContentPart
import com.mistakebook.net.llm.ChatMessage
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ImageUrl
import com.mistakebook.net.llm.LlmApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 大模型纠错调用（OpenAI 兼容）。
 *
 * - 降级阶梯（每级一次机会，互不挤占）：带 response_format → 去掉该字段 →
 *   去掉该字段且把「只输出 JSON」写进 prompt；
 * - 可选把原图以 base64 data URL 一并送入（多模态模型），长边压到 [longEdgePx]；
 * - **区分「被 max_tokens 截断」和「返回垃圾」**：前者 JSON 必然残缺，
 *   原先两者都落到 BAD_RESPONSE，导致上层只能笼统报「整理失败」；
 * - 瞬时错误（网络/限流/5xx）额外重试 1 次（2s 退避），鉴权与截断不重试。
 */
class LlmClient(private val api: LlmApi) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * @return 成功时 [LlmReply.text] 是模型原文；被截断时返回
     *   [ApiErrorKind.BAD_RESPONSE] 且 serverMessage 指明「输出超长被截断」，
     *   由调用方决定是分批还是让用户减少题目。
     */
    suspend fun refineJson(
        profile: LlmProfile,
        systemPrompt: String,
        userPrompt: String,
        imageFile: File?,
        longEdgePx: Int,
        attachImage: Boolean,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        if (!profile.isConfigured()) return@withContext fail(ApiErrorKind.NO_KEY)

        val imagePart = if (attachImage && imageFile != null && imageFile.exists()) {
            encodeImage(imageFile, longEdgePx)
        } else {
            null
        }

        // 降级阶梯：每一级都有自己的一次机会。
        // 之前的写法把这些降级塞进 repeat(2) 的重试里，阶梯第一步就把次数用光，
        // 后面两级根本没机会执行，最终只会返回笼统的「大模型调用失败」。
        val ladder = listOf(
            LadderStep(jsonMode = true, reminder = false),
            LadderStep(jsonMode = false, reminder = false),
            LadderStep(jsonMode = false, reminder = true)
        )
        var last: ApiResult.Failure? = null

        for (step in ladder) {
            val system = if (step.reminder) systemPrompt + JSON_ONLY_REMINDER else systemPrompt
            when (val result = sendOnce(profile, system, userPrompt, imagePart, step.jsonMode, maxTokens)) {
                is ApiResult.Success -> return@withContext result
                is ApiResult.Failure -> {
                    // 截断不降级：重试多少次还是超长，直接把「超长」这个事实报上去，
                    // 让上层分批或提示用户，比降级后再失败一次诚实得多。
                    if (result.error.serverMessage == TRUNCATED_MESSAGE) {
                        return@withContext result
                    }
                    last = result
                    // 鉴权/限流重试无意义，直接进下一级
                    if (result.error.kind == ApiErrorKind.AUTH) break
                }
            }
        }

        // 阶梯走完还没成，对瞬时错误做一次退避重试
        val failure = last ?: fail(ApiErrorKind.UNKNOWN, "大模型调用失败")
        if (failure.error.kind == ApiErrorKind.CANCELLED) return@withContext failure
        delay(RETRY_BACKOFF_MS)
        when (val retry = sendOnce(profile, systemPrompt + JSON_ONLY_REMINDER, userPrompt, imagePart, false, maxTokens)) {
            is ApiResult.Success -> retry
            is ApiResult.Failure -> retry
        }
    }

    private data class LadderStep(val jsonMode: Boolean, val reminder: Boolean)

    private suspend fun sendOnce(
        profile: LlmProfile,
        systemPrompt: String,
        userPrompt: String,
        imagePart: ChatContentPart?,
        useJsonMode: Boolean,
        maxTokens: Int
    ): ApiResult<String> {
        val messages = buildList {
            add(ChatMessage(role = "system", content = JsonPrimitive(systemPrompt)))
            val userContent = if (imagePart == null) {
                JsonPrimitive(userPrompt)
            } else {
                json.encodeToString(
                    ListSerializer(ChatContentPart.serializer()),
                    listOf(
                        ChatContentPart(type = ChatContentPart.TYPE_TEXT, text = userPrompt),
                        imagePart
                    )
                ).let { Json.parseToJsonElement(it) }
            }
            add(ChatMessage(role = "user", content = userContent))
        }
        val request = ChatRequest(
            model = profile.model,
            messages = messages,
            max_tokens = maxTokens,
            responseFormat = if (useJsonMode) com.mistakebook.net.llm.ResponseFormat("json_object") else null
        )
        val response = runCatching {
            api.chatCompletions(
                url = profile.chatCompletionsUrl(),
                authorization = "Bearer ${profile.apiKey}",
                request = request
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!response.isSuccessful) {
            val body = response.errorBody()?.string().orEmpty()
            val error = HttpFactory.httpError(response.code(), body)
            return ApiResult.Failure(
                if (response.code() == 400) {
                    error.copy(kind = ApiErrorKind.BAD_RESPONSE, serverMessage = error.serverMessage)
                } else {
                    error
                }
            )
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        val choice = body.choices.firstOrNull()
            ?: return fail(ApiErrorKind.BAD_RESPONSE, "大模型未返回任何结果")
        val text = choice.message?.content.orEmpty()
        if (text.isBlank()) return fail(ApiErrorKind.BAD_RESPONSE, "大模型未返回内容")
        // finish_reason 解析了却从来没用过：被 max_tokens 截断的 JSON 必然残缺，
        // 表现出来和「模型胡乱输出」一模一样，只能靠它区分。
        if (choice.finishReason == "length") {
            return ApiResult.Failure(
                ApiError(
                    kind = ApiErrorKind.BAD_RESPONSE,
                    serverMessage = TRUNCATED_MESSAGE,
                    detail = "finish_reason=length, max_tokens=$maxTokens"
                )
            )
        }
        return ApiResult.Success(text)
    }

    /** 长边压到 [longEdgePx]，JPEG q80，转 base64 data URL。 */
    private fun encodeImage(file: File, longEdgePx: Int): ChatContentPart? {
        val bitmap = ImageNormalizer.decodeBounded(file, longEdgePx) ?: return null
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val base64 = android.util.Base64.encodeToString(
                stream.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            ChatContentPart(
                type = ChatContentPart.TYPE_IMAGE,
                imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64")
            )
        } finally {
            bitmap.recycle()
        }
    }


    /** 设置页「测试连接」：GET {base}/models，HTTP 200 即有效。 */
    suspend fun listModels(profile: LlmProfile): ApiResult<List<String>> {
        if (!profile.isConfigured()) return fail(ApiErrorKind.NO_KEY)
        val response = runCatching {
            api.listModels(
                url = profile.modelsUrl(),
                authorization = "Bearer ${profile.apiKey}"
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!response.isSuccessful) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        val ids = body.data.map { it.id }.filter { it.isNotBlank() }.distinct().sorted()
        if (ids.isEmpty()) return fail(ApiErrorKind.BAD_RESPONSE, "未返回任何模型")
        return ApiResult.Success(ids)
    }

    /**
     * 真正验证「模型可用」：发一次极小的 chat/completions（max_tokens=1），
     * 能拿到返回内容才算连通，避免只测了域名但模型名写错。
     */
    suspend fun probeModel(profile: LlmProfile): ApiResult<String> {
        if (!profile.isConfigured()) return fail(ApiErrorKind.NO_KEY)
        val request = ChatRequest(
            model = profile.model,
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("ping"))),
            max_tokens = 1,
            responseFormat = null,
            stream = false
        )
        val response = runCatching {
            api.chatCompletions(
                url = profile.chatCompletionsUrl(),
                authorization = "Bearer ${profile.apiKey}",
                request = request
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!response.isSuccessful) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        val content = body.choices.firstOrNull()?.message?.content.orEmpty()
        if (body.choices.isEmpty()) return fail(ApiErrorKind.BAD_RESPONSE, "模型未返回内容")
        return ApiResult.Success(content.ifBlank { "ok" })
    }

    /** 旧版测试连接（保留给 MinerU 侧使用）。 */
    suspend fun testConnection(profile: LlmProfile): ApiResult<Boolean> {
        if (!profile.isConfigured()) return fail(ApiErrorKind.NO_KEY)
        val response = runCatching {
            api.listModels(
                url = profile.modelsUrl(),
                authorization = "Bearer ${profile.apiKey}"
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        return if (response.isSuccessful) {
            ApiResult.Success(true)
        } else {
            fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
    }

    private fun fail(kind: ApiErrorKind, message: String = ""): ApiResult.Failure =
        ApiResult.Failure(ApiError(kind = kind, serverMessage = message))

    private fun fail(error: ApiError): ApiResult.Failure = ApiResult.Failure(error)

    companion object {
        const val RETRY_BACKOFF_MS = 2_000L
        const val JSON_ONLY_REMINDER = "\n\n再次强调：只输出 JSON 本身，不要任何其他文字。"

        /**
         * 多题 + 详细解析的输出预算。
         * 4096 会把多题结果拦腰截断，JSON 残缺必然解析失败。
         */
        const val DEFAULT_MAX_TOKENS = 16384

        private const val TRUNCATED_MARKER = "__truncated__"

        /** 面向用户的中文提示：输出被 max_tokens 截断。 */
        const val TRUNCATED_MESSAGE =
            "题目太多，AI 输出超长被截断。请一次少导入几道题，或在编辑页分次整理。"
    }
}
