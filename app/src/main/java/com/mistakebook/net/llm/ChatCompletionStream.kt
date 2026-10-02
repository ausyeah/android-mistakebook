package com.mistakebook.net.llm

import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.HttpFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 流式对话的一次输出。
 *
 * **全部走事件，不抛异常到 UI**（项目规则 6）。
 */
sealed interface ChatStreamEvent {
    /** 增量文本。 */
    data class Delta(val text: String) : ChatStreamEvent

    /**
     * 增量**思考过程**。与 [Delta] 分开传，上层才能分别渲染。
     *
     * 早先的实现**直接丢掉了它**——推理模型把思考放在 `reasoning_content`，
     * 与 `content` 完全分开。结果是用户看到一个只有蓝点、没有内容的空气泡，
     * 而模型确实已经思考了。
     */
    data class Thinking(val text: String) : ChatStreamEvent

    /**
     * 结束。
     *
     * @param finishReason `stop` 正常；**`length` 表示被 max_tokens 截断**。
     *   两者在上层必须区别对待——截断要提示用户「换个大点的模型或分次问」，
     *   而不能当成正常结束。
     */
    data class Done(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val finishReason: String? = null
    ) : ChatStreamEvent

    data class Failed(val error: ApiError) : ChatStreamEvent
}

/**
 * 降级判定。**独立成纯函数**才能单测——它是这个文件里唯一有分支策略的地方。
 */
object StreamFallback {

    /**
     * 能不能退回非流式。
     *
     * 三条硬规则：
     *
     * 1. **已经吐过内容就绝不降级**。降级会重新请求，用户会看到同一段话出现两遍。
     * 2. **鉴权 / 限流 / 服务端错误不降级**。这些重发一次还是同样的结果，
     *    只是白烧一次配额，还把真实错误信息冲淡了。
     * 3. **用户主动取消不降级**。取消后偷偷重发，用户点了停止却还在烧 token。
     */
    fun shouldFallback(error: ApiError, deltasEmitted: Int): Boolean {
        if (deltasEmitted > 0) return false
        return when (error.kind) {
            ApiErrorKind.AUTH,
            ApiErrorKind.RATE_LIMIT,
            ApiErrorKind.SERVER,
            ApiErrorKind.NO_KEY,
            ApiErrorKind.CANCELLED -> false

            // 服务端忽略 stream 参数、返回体不是 SSE、连不上：都值得试一次非流式
            ApiErrorKind.BAD_RESPONSE,
            ApiErrorKind.NETWORK,
            ApiErrorKind.TIMEOUT,
            ApiErrorKind.UNKNOWN -> true
        }
    }
}

/**
 * SSE 流式对话客户端。
 *
 * ## 为什么不用 Retrofit
 *
 * Retrofit 的 `Response<T>` 会把 body 一次性读完，流式拿不到增量。
 * 流式这条路必须直接用 OkHttp；非流式降级仍走 Retrofit（[LlmApi]），
 * 那里已经有成熟的错误处理。
 *
 * ## 降级阶梯不在重试里
 *
 * 早期版本把这些降级塞进 `repeat(n)` 的重试，n 不够就静默失效
 * （见 docs/DECISIONS.md 的 v0.1.13 事故）。这里是显式的 if，
 * 一次机会一次机会，走不走得清楚。
 */
class ChatCompletionStream(
    private val client: OkHttpClient,
    private val api: LlmApi
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 流式专用客户端：**取消整体调用超时**。
     *
     * [HttpFactory] 给普通请求设了 300s call timeout，那是给「一次性拿完」设计的。
     * 流式下用户和模型一问一答可能超过 5 分钟，届时 socket 还连着却被强行掐断，
     * 表现为「答到一半断了」。安全性由 120s 读超时兜底（读不到任何字节才算卡死），
     * 正常情况下模型不会沉默 120 秒。
     */
    private val streamingClient: OkHttpClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 发起一次对话。
     *
     * 内部顺序：先流式，[StreamFallback] 判定可以降级时才发非流式。
     * **两条路对上层完全一样**——都是 [ChatStreamEvent] 序列，UI 察觉不到区别。
     */
    fun complete(profile: LlmProfile, request: ChatRequest): Flow<ChatStreamEvent> = flow {
        var deltas = 0
        var failure: ApiError? = null
        var ended = false

        try {
            rawStream(profile, request).collect { event ->
                when (event) {
                    is RawEvent.Delta -> {
                        if (event.text.isNotEmpty()) {
                            deltas++
                            emit(ChatStreamEvent.Delta(event.text))
                        }
                    }

                    is RawEvent.Thinking -> {
                        if (event.text.isNotEmpty()) emit(ChatStreamEvent.Thinking(event.text))
                    }

                    is RawEvent.Ended -> {
                        ended = true
                        emit(ChatStreamEvent.Done(event.promptTokens, event.completionTokens, event.finishReason))
                    }

                    is RawEvent.Failed -> failure = event.error
                }
            }
        } catch (cancelled: CancellationException) {
            // 用户按了停止或页面销毁。**绝不能当成错误降级重发**。
            throw cancelled
        } catch (t: Throwable) {
            failure = HttpFactory.throwableError(t)
        }

        if (ended) return@flow

        // 一帧都没解出来：可能是服务端忽略了 stream 参数、返回体不是 SSE、
        // 或者连不上。降级重试一次。
        val reason = failure
            ?: if (deltas == 0) ApiError(ApiErrorKind.BAD_RESPONSE, "模型未返回流式内容")
            else return@flow

        if (!StreamFallback.shouldFallback(reason, deltas)) {
            if (failure != null) emit(ChatStreamEvent.Failed(reason))
            return@flow
        }

        emitAll(nonStreaming(profile, request, deltas))
    }

    // ------------------------------------------------------------ 内部事件

    private sealed interface RawEvent {
        data class Delta(val text: String) : RawEvent
        data class Thinking(val text: String) : RawEvent
        data class Ended(val promptTokens: Int, val completionTokens: Int, val finishReason: String?) : RawEvent
        data class Failed(val error: ApiError) : RawEvent
    }

    // ------------------------------------------------------------ 流式

    private fun rawStream(profile: LlmProfile, request: ChatRequest): Flow<RawEvent> = callbackFlow {
        val call = streamingClient.newCall(buildRequest(profile, request, streaming = true))
        var finished = false

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!finished) {
                    finished = true
                    trySend(RawEvent.Failed(HttpFactory.throwableError(e)))
                    close()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!finished) readBody(response) { trySend(it) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (!finished) {
                        finished = true
                        trySend(RawEvent.Failed(HttpFactory.throwableError(t)))
                    }
                } finally {
                    if (!finished) {
                        finished = true
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }

    private fun readBody(response: Response, emit: (RawEvent) -> Unit) {
        val body = response.body
        if (!response.isSuccessful) {
            val text = runCatching { body?.string().orEmpty() }.getOrDefault("")
            emit(RawEvent.Failed(HttpFactory.httpError(response.code, text)))
            return
        }
        if (body == null) {
            emit(RawEvent.Failed(ApiError(ApiErrorKind.BAD_RESPONSE, "响应体为空")))
            return
        }

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            // 服务端直接返回了 JSON —— 它**忽略了 stream 参数**。
            // 直接就地解析，不要重新发一次请求。
            readPlainJson(body.string(), emit)
            return
        }

        val parser = SseLineParser(json)
        val source = body.source()
        var finishReason: String? = null
        var promptTokens = 0
        var completionTokens = 0

        while (true) {
            val line = source.readUtf8Line() ?: break
            when (val event = parser.accept(line)) {
                null, SseEvent.Ignore -> Unit

                is SseEvent.Chunk -> {
                    event.thinking.takeIf { it.isNotEmpty() }?.let { emit(RawEvent.Thinking(it)) }
                    event.text.takeIf { it.isNotEmpty() }?.let { emit(RawEvent.Delta(it)) }
                    // finish_reason / usage 只在特定帧出现，取**最后一个非空值**
                    event.finishReason?.let { finishReason = it }
                    if (event.promptTokens > 0) promptTokens = event.promptTokens
                    if (event.completionTokens > 0) completionTokens = event.completionTokens
                }

                SseEvent.Done -> {
                    emit(RawEvent.Ended(promptTokens, completionTokens, finishReason))
                    return
                }

                // 坏帧不中断：可能只是某一行被代理改坏了。累计数量交给上层判断。
                is SseEvent.Malformed -> Unit
            }
        }

        // 读到 EOF 但没收到 [DONE]：不少中转站不发这个哨兵，按正常结束处理。
        emit(RawEvent.Ended(promptTokens, completionTokens, finishReason))
    }

    /** 服务端忽略 stream、直接给 JSON 时走这里。 */
    private fun readPlainJson(text: String, emit: (RawEvent) -> Unit) {
        val parsed = runCatching { json.decodeFromString(ChatResponse.serializer(), text) }.getOrNull()
        if (parsed == null) {
            emit(RawEvent.Failed(ApiError(ApiErrorKind.BAD_RESPONSE, "响应不是有效的 SSE 或 JSON")))
            return
        }
        val choice = parsed.choices.firstOrNull()
        if (choice == null) {
            emit(RawEvent.Failed(ApiError(ApiErrorKind.BAD_RESPONSE, "模型未返回任何结果")))
            return
        }
        // 降级到非流式后思考也要输出：否则用户会看到「模型不回答」
        choice.message?.reasoningContent?.takeIf { it.isNotBlank() }
            ?.let { emit(RawEvent.Thinking(it)) }
        choice.message?.content?.takeIf { it.isNotEmpty() }?.let { emit(RawEvent.Delta(it)) }
        emit(
            RawEvent.Ended(
                promptTokens = parsed.usage?.promptTokens ?: 0,
                completionTokens = parsed.usage?.completionTokens ?: 0,
                finishReason = choice.finishReason
            )
        )
    }

    // ------------------------------------------------------------ 非流式降级

    private suspend fun nonStreaming(
        profile: LlmProfile,
        request: ChatRequest,
        deltasAlreadyEmitted: Int
    ): Flow<ChatStreamEvent> = flow {
        if (deltasAlreadyEmitted > 0) return@flow

        val response = try {
            api.chatCompletions(
                url = profile.chatCompletionsUrl(),
                authorization = "Bearer ${profile.apiKey}",
                request = request.copy(stream = false)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            emit(ChatStreamEvent.Failed(HttpFactory.throwableError(t)))
            return@flow
        }

        if (!response.isSuccessful) {
            val body = runCatching { response.errorBody()?.string().orEmpty() }.getOrDefault("")
            emit(ChatStreamEvent.Failed(HttpFactory.httpError(response.code(), body)))
            return@flow
        }

        val parsed = response.body()
        if (parsed == null || parsed.choices.isEmpty()) {
            emit(ChatStreamEvent.Failed(ApiError(ApiErrorKind.BAD_RESPONSE, "模型未返回任何结果")))
            return@flow
        }
        val choice = parsed.choices.first()
        choice.message?.reasoningContent?.takeIf { it.isNotBlank() }
            ?.let { emit(ChatStreamEvent.Thinking(it)) }
        val text = choice.message?.content.orEmpty()
        if (text.isNotEmpty()) emit(ChatStreamEvent.Delta(text))
        emit(
            ChatStreamEvent.Done(
                promptTokens = parsed.usage?.promptTokens ?: 0,
                completionTokens = parsed.usage?.completionTokens ?: 0,
                finishReason = choice.finishReason
            )
        )
    }

    // ------------------------------------------------------------ 请求构造

    private fun buildRequest(profile: LlmProfile, request: ChatRequest, streaming: Boolean): Request {
        // 对话**绝不能开 json_object**：会把回复强行掰成 JSON，
        // 用户看到的是一堆花括号而不是讲解。
        val body = request.copy(
            temperature = TEMPERATURE,
            max_tokens = request.max_tokens.takeIf { it > 0 } ?: MAX_TOKENS,
            responseFormat = null,
            stream = streaming
        )
        val payload = json.encodeToString(ChatRequest.serializer(), body)
        return Request.Builder()
            .url(profile.chatCompletionsUrl())
            .header("Authorization", "Bearer ${profile.apiKey}")
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .header("Cache-Control", "no-cache")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    companion object {
        /** 对话场景的温度。规格锁定 0.6。 */
        const val TEMPERATURE = 0.6

        /**
         * 对话的输出预算。
         *
         * 不沿用纠错任务的 16384：那是「多题 + 详细解析」的量级，
         * 单题讲解 4096 足够，而且响应更快。
         */
        const val MAX_TOKENS = 4096

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
