package com.mistakebook.net.mineru

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** MinerU v4 统一响应信封。 */
@Serializable
data class MineruEnvelope<T>(
    val code: Int = 0,
    val msg: String = "",
    val data: T? = null
) {
    fun isOk(): Boolean = code == 0
}

@Serializable
data class FileUrlsRequest(
    val files: List<FileUrlsItem>,
    val model_version: String,
    val language: String,
    val enable_formula: Boolean = true,
    val enable_table: Boolean = true
)

@Serializable
data class FileUrlsItem(
    val name: String,
    val is_ocr: Boolean
)

@Serializable
data class FileUrlsData(
    val batch_id: String,
    val file_urls: List<String> = emptyList()
)

@Serializable
data class ExtractResultsData(
    val extract_result: List<ExtractResultItem> = emptyList()
)

@Serializable
data class ExtractResultItem(
    // 实测字段名是 file_name（PRD 写的 name 与官方响应不一致，这里按官方响应来）
    @SerialName("file_name") val fileName: String = "",
    val name: String = "",
    val state: String = "",
    @SerialName("full_zip_url") val fullZipUrl: String? = null,
    @SerialName("err_msg") val errMsg: String? = null,
    @SerialName("extract_progress") val extractProgress: JsonElement? = null
) {
    /** extract_progress 可能是 42、42.0 或 "42%"，统一取整数。 */
    fun progressPercent(): Int? {
        val raw = extractProgress?.toString()?.trim('"')?.removeSuffix("%") ?: return null
        return raw.trim().toDoubleOrNull()?.toInt()
    }
}
