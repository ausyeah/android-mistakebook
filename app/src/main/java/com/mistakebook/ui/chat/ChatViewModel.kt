package com.mistakebook.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.chat.OutgoingMessage
import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.data.repos.ChatRepository
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.llm.ChatCompletionStream
import com.mistakebook.net.llm.ChatContentPart
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ChatStreamEvent
import com.mistakebook.net.llm.ImageUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 对话页状态。**单一 StateFlow**（项目规则 5）。
 *
 * 一次性事件（错误提示、已复制等）走 [ChatViewModel.events]，不塞进这里——
 * 塞进 state 的后果是转屏后事件重放，用户看到「已复制」弹两次。
 */
data class ChatUiState(
    val loading: Boolean = true,
    val sessionId: Long = 0L,
    val questionId: Long? = null,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),

    /** 输入框内容。首问会预填但不自动发送。 */
    val input: String = "",

    /** 还没发送的待发附件。 */
    val pendingAttachments: List<ChatAttachment> = emptyList(),

    val isStreaming: Boolean = false,

    /**
     * 被省略的早期对话轮数。大于 0 时列表顶部要插一条灰色分隔条，
     * 否则用户以为 AI 失忆。
     */
    val omittedCount: Int = 0,

    /** 本次回复被 max_tokens 截断。要区别于正常结束提示用户。 */
    val truncated: Boolean = false,

    /** 未配置 API Key——UI 弹引导而不是显示「失败」。 */
    val needsApiKey: Boolean = false,

    /** 最后一条助手消息失败，允许重试。 */
    val canRetry: Boolean = false
)

/** 一次性事件。 */
sealed interface ChatEvent {
    data class Error(val text: String) : ChatEvent
    data object Copied : ChatEvent
}

/**
 * 对话页 ViewModel。
 *
 * ## 为什么流式内容直接写库、不在 state 里另存一份
 *
 * 另存一份就得把「库里的列表」和「内存里的流式文本」合并，
 * 而合并两份来源正是本项目栽过多次的坑（顺序依赖、重组不刷新）。
 * 改成**节流写库**后，UI 的消息列表永远只有 Room 一个来源。
 * 代价是 ≤[WRITE_THROTTLE_MS] 的显示延迟——肉眼察觉不到。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val stream: ChatCompletionStream,
    private val settingsStore: SettingsStore,
    private val questionId: Long?
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val partSerializer = ListSerializer(ChatContentPart.serializer())

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            val session = repository.sessionForQuestion(questionId)
            // 进程上次被杀时那条消息永远停在 STREAMING，不收拾的话界面会一直转圈，
            // 而且用户没有任何办法让它停下来。
            repository.recoverStaleStreaming(session.id)
            if (questionId != null) {
                repository.ensureQuestionContext(session.id, questionId)
            }
            _state.value = _state.value.copy(
                sessionId = session.id,
                questionId = session.questionId,
                title = session.title
            )
            repository.observeMessages(session.id).collect { messages ->
                _state.value = _state.value.copy(
                    loading = false,
                    messages = messages,
                    canRetry = messages.lastOrNull()?.status == MessageStatus.FAILED
                )
            }
        }
    }

    fun onInputChange(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    // ------------------------------------------------------------ 发送

    fun send() {
        val current = _state.value
        if (current.isStreaming || current.loading) return
        val text = current.input.trim()
        if (text.isEmpty() && current.pendingAttachments.isEmpty()) return

        viewModelScope.launch {
            val profile = settingsStore.settings.first().activeProfile
            if (profile == null || !profile.isConfigured()) {
                _state.value = _state.value.copy(needsApiKey = true)
                return@launch
            }
            val attachmentIds = _state.value.pendingAttachments.map { it.id }
            _state.value = _state.value.copy(
                input = "",
                pendingAttachments = emptyList(),
                truncated = false
            )
            runOnce(profile, text, attachmentIds, isRetry = false)
        }
    }

    /**
     * 重试：删掉那条失败的助手消息，用**原来的问题**重发。
     *
     * 复用原问题而不是清空输入框——用户点了重试就是要看同一个答案。
     */
    fun retry() {
        val current = _state.value
        if (current.isStreaming) return
        val lastUser = current.messages.lastOrNull { it.role == ChatRole.USER && !it.injected }
        val failed = current.messages.lastOrNull { it.role == ChatRole.ASSISTANT }
        if (lastUser == null || failed == null) return

        viewModelScope.launch {
            val profile = settingsStore.settings.first().activeProfile
            if (profile == null || !profile.isConfigured()) {
                _state.value = _state.value.copy(needsApiKey = true)
                return@launch
            }
            repository.deleteMessage(failed.id)
            runOnce(profile, lastUser.content, lastUser.attachmentIds(), isRetry = true)
        }
    }

    fun stop() {
        streamJob?.cancel()
    }

    private suspend fun runOnce(
        profile: LlmProfile,
        text: String,
        attachmentIds: List<Long>,
        isRetry: Boolean
    ) {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L) return

        if (!isRetry) {
            repository.insertUserMessage(sessionId, text, attachmentIds, questionId)
        }
        val context = repository.buildContext(sessionId, questionId)
        val assistantId = repository.insertStreamingAssistantMessage(sessionId, profile.model)
        _state.value = _state.value.copy(
            isStreaming = true,
            omittedCount = context.omittedCount,
            canRetry = false,
            truncated = false
        )

        val job = viewModelScope.launch { collectStream(profile, context.messages, assistantId) }
        streamJob = job
        job.join()
        streamJob = null
    }

    /**
     * 收流并节流落库。
     *
     * 每收到一段就写一次库的话，一次回复能产生上千次写 + 上千次 Flow 发射，
     * 列表反复重组。节流到 [WRITE_THROTTLE_MS] 一次，肉眼看不出差别。
     */
    private suspend fun collectStream(
        profile: LlmProfile,
        outgoing: List<OutgoingMessage>,
        assistantId: Long
    ) {
        val request = ChatRequest(
            model = profile.model,
            messages = outgoing.map { it.toLlm() },
            temperature = ChatCompletionStream.TEMPERATURE,
            max_tokens = ChatCompletionStream.MAX_TOKENS,
            responseFormat = null,
            stream = true
        )

        val buffer = StringBuilder()
        var lastWriteAt = 0L
        var status = MessageStatus.DONE
        var errorText: String? = null
        var promptTokens = 0
        var completionTokens = 0
        var wasTruncated = false

        // 局部挂起函数：把「攒着的增量」写进库里。
        suspend fun flush() {
            if (buffer.isEmpty()) return
            val snapshot = buffer.toString()
            buffer.setLength(0)
            lastWriteAt = System.currentTimeMillis()
            repository.appendAssistantContent(assistantId, snapshot)
        }

        try {
            stream.complete(profile, request).collect { event ->
                when (event) {
                    is ChatStreamEvent.Delta -> {
                        buffer.append(event.text)
                        if (System.currentTimeMillis() - lastWriteAt >= WRITE_THROTTLE_MS) flush()
                    }

                    is ChatStreamEvent.Done -> {
                        promptTokens = event.promptTokens
                        completionTokens = event.completionTokens
                        // length = 被 max_tokens 截断。它和「模型胡乱输出」在下游一样，
                        // 但处理方式不同：要提示用户换个更大的模型或分次问。
                        wasTruncated = event.finishReason == FINISH_REASON_LENGTH
                    }

                    is ChatStreamEvent.Failed -> {
                        status = if (event.error.kind == ApiErrorKind.CANCELLED) {
                            MessageStatus.CANCELED
                        } else {
                            MessageStatus.FAILED
                        }
                        errorText = event.error.serverMessage.takeIf { it.isNotBlank() }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // 用户按了停止：不是失败，保留已收到的内容。
            status = MessageStatus.CANCELED
            throw cancelled
        } finally {
            // 收尾必须放 finally：取消时 CancellationException 已经抛出去了，
            // 放在 try 之后的那段代码根本不会执行——
            // 表现是「按了停止，缓冲区里最后半句永久丢失，状态卡在生成中」。
            // NonCancellable 保证这一段能跑完。
            withContext(NonCancellable) {
                flush()
                repository.finishAssistantMessage(
                    id = assistantId,
                    status = status,
                    errorMessage = errorText,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens
                )
            }
            // StateFlow 写入不是挂起函数，取消态下依然能执行
            _state.value = _state.value.copy(
                isStreaming = false,
                truncated = wasTruncated,
                canRetry = status == MessageStatus.FAILED
            )
            errorText?.let { _events.trySend(ChatEvent.Error(it)) }
        }
    }

    // ------------------------------------------------------------ 附件

    /** 步骤 5 的附件管线接进来。这里只管把结果挂到 state 上。 */
    fun onAttachmentsPrepared(attachments: List<ChatAttachment>) {
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments + attachments
        )
    }

    fun removePendingAttachment(id: Long) {
        val target = _state.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments.filterNot { it.id == id }
        )
        viewModelScope.launch { repository.deleteAttachment(target) }
    }

    // ------------------------------------------------------------ 其他

    fun dismissApiKeyGate() {
        _state.value = _state.value.copy(needsApiKey = false)
    }

    fun dismissTruncatedNotice() {
        _state.value = _state.value.copy(truncated = false)
    }

    fun notifyCopied() {
        viewModelScope.launch { _events.send(ChatEvent.Copied) }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch { repository.deleteMessage(id) }
    }

    fun renameSession(title: String) {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L || title.isBlank()) return
        viewModelScope.launch {
            repository.renameSession(sessionId, title)
            _state.value = _state.value.copy(title = title.trim())
        }
    }

    // ------------------------------------------------------------ 转换

    private fun OutgoingMessage.toLlm() = com.mistakebook.net.llm.ChatMessage(
        role = when (role) {
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
            ChatRole.SYSTEM -> "system"
        },
        content = if (images.isEmpty()) {
            JsonPrimitive(text)
        } else {
            val parts = buildList {
                add(ChatContentPart(type = ChatContentPart.TYPE_TEXT, text = text.ifBlank { "（见下图）" }))
                images.forEach {
                    add(ChatContentPart(type = ChatContentPart.TYPE_IMAGE, imageUrl = ImageUrl(it)))
                }
            }
            json.parseToJsonElement(json.encodeToString(partSerializer, parts))
        }
    )

    private fun ChatMessage.attachmentIds(): List<Long> {
        if (attachmentIdsJson.isBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(attachmentIdsJson) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                runCatching { element.jsonPrimitive.content.toLongOrNull() }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /**
         * 落库节流间隔。
         *
         * 500ms 的理由：一次流式回复通常持续十几秒，节流后是 20~30 次写库；
         * 再密就纯属浪费，因为用户分辨不出「每 50ms 更新」和「每 500ms 更新」。
         */
        const val WRITE_THROTTLE_MS = 500L

        const val FINISH_REASON_LENGTH = "length"

        /**
         * 从题目页进入时的首问预填。**不自动发送**——
         * 自动发会白烧 token，而且用户十有八九想改问法。
         */
        const val PREFILL_QUESTION = "请讲解这道题，给出详细解题步骤、答案和易错点。"
    }
}
