package com.mistakebook.net.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SSE 流式响应的一帧。
 *
 * 与非流式的 [ChatResponse] 分开：**流式给的是 `delta`（增量），
 * 非流式给的是 `message`（全量）**，字段名不同，共用一个类必然有一边读不到。
 */
@Serializable
data class ChatStreamChunk(
    val id: String = "",
    val choices: List<StreamChoice> = emptyList(),

    /** 只有显式要求 `stream_options.include_usage` 时才有。多数中转站不给。 */
    val usage: Usage? = null,

    /** 少数服务会带错误体在 200 响应里。 */
    val error: StreamError? = null
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta? = null,

    /**
     * `stop` = 正常结束；`length` = **被 max_tokens 截断**。
     *
     * 截断必须单独识别：它和「模型胡乱输出」在下游表现完全一样
     * （都是半截内容），但处理方式不同。
     */
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class StreamDelta(
    val role: String? = null,

    /** 首帧通常只有 role 没有 content；末帧可能 content 为 null。 */
    val content: String? = null
)

@Serializable
data class StreamError(
    val message: String = "",
    val type: String = ""
)
