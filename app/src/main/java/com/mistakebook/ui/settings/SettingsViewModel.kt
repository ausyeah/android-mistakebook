package com.mistakebook.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.di.AppContainer
import com.mistakebook.net.ApiResult
import com.mistakebook.net.ApiError
import com.mistakebook.net.errorOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val snapshot: SettingsSnapshot = SettingsSnapshot(),
    val testingMineru: Boolean = false,
    val testingLlm: Boolean = false,

    /**
     * 各自的测试结果，**刻意不合并成一个字段**。
     *
     * 早先只有一对 `testResult` / `testOk`，MinerU 和大模型两个测试共用——
     * 于是点 MinerU 的测试按钮，结果却渲染在大模型那一组的下方
     * （两组在页面上相距好几屏，用户根本对不上是哪个按钮的结果）。
     * 而且两个测试还会互相覆盖：先测完大模型再测 MinerU，大模型的结果被冲掉。
     */
    val mineruTestResult: String? = null,
    val mineruTestOk: Boolean = false,
    val llmTestResult: String? = null,
    val llmTestOk: Boolean = false,
    val loadingModels: Boolean = false,
    val models: List<String> = emptyList(),
    val modelsError: String? = null,
    val pickedModel: String? = null,
    val backupMessage: String? = null
) {
    val activeProfile: LlmProfile? get() = snapshot.activeProfile
}

// 测试连接的瞬时状态
private data class TestState(
    val flags: TestFlags,
    val llmResult: String?,
    val llmOk: Boolean
) {
    val testingMineru: Boolean get() = flags.testingMineru
    val testingLlm: Boolean get() = flags.testingLlm
    val loadingModels: Boolean get() = flags.loadingModels
    val mineruResult: String? get() = flags.mineruResult
    val mineruOk: Boolean get() = flags.mineruOk
}

/** 两个「正在测试」标志 + MinerU 的结果 + 拉模型列表的标志。 */
private data class TestFlags(
    val testingMineru: Boolean = false,
    val testingLlm: Boolean = false,
    val loadingModels: Boolean = false,
    val mineruResult: String? = null,
    val mineruOk: Boolean = false
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val store: SettingsStore = container.settingsStore

    private val testingMineru = MutableStateFlow(false)
    private val testingLlm = MutableStateFlow(false)
    private val loadingModels = MutableStateFlow(false)
    private val mineruTestResult = MutableStateFlow<String?>(null)
    private val mineruTestOk = MutableStateFlow(false)
    private val llmTestResult = MutableStateFlow<String?>(null)
    private val llmTestOk = MutableStateFlow(false)
    private val models = MutableStateFlow<List<String>>(emptyList())
    private val modelsError = MutableStateFlow<String?>(null)
    private val pickedModel = MutableStateFlow<String?>(null)
    private val backupMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        store.settings,
        // combine 的类型化重载最多到 5 个参数，而这里有 7 个。
        // 套一层：两个测试各自合成一个，再合成 TestState。
        combine(
            combine(
                testingMineru, testingLlm, loadingModels,
                mineruTestResult, mineruTestOk
            ) { a, b, c, d, e -> TestFlags(a, b, c, d, e) },
            combine(llmTestResult, llmTestOk) { result, ok -> result to ok }
        ) { flags, llm -> TestState(flags, llm.first, llm.second) },
        combine(models, modelsError, pickedModel) { list, error, picked ->
            Triple(list, error, picked)
        },
        backupMessage
    ) { snapshot, test, modelsPair, backup ->
        SettingsUiState(
            snapshot = snapshot,
            testingMineru = test.testingMineru,
            testingLlm = test.testingLlm,
            loadingModels = test.loadingModels,
            mineruTestResult = test.mineruResult,
            mineruTestOk = test.mineruOk,
            llmTestResult = test.llmResult,
            llmTestOk = test.llmOk,
            models = modelsPair.first,
            modelsError = modelsPair.second,
            pickedModel = modelsPair.third,
            backupMessage = backup
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setMineruKey(value: String) = viewModelScope.launch { store.setMineruKey(value) }

    fun saveActiveProfile(profile: LlmProfile) = viewModelScope.launch {
        val current = uiState.value.activeProfile ?: return@launch
        store.upsertProfile(profile.copy(id = current.id))
    }

    fun addProfile(profile: LlmProfile) = viewModelScope.launch {
        store.upsertProfile(profile)
    }

    fun deleteProfile(id: String) = viewModelScope.launch { store.deleteProfile(id) }

    fun selectProfile(id: String) = viewModelScope.launch { store.setActiveProfileId(id) }

    // setMineruModelVersion / setOcrLanguage / setForceOcr 已随设置项移除。
    // 模型版本固定 vlm、语言固定 ch、强制 OCR 固定开启，写在 SettingsSnapshot 里。

    fun setAttachImage(value: Boolean) = viewModelScope.launch { store.setAttachOriginalImage(value) }

    fun setEnhancePhotos(value: Boolean) = viewModelScope.launch { store.setEnhancePhotos(value) }

    fun setPrintIncludeImage(value: Boolean) = viewModelScope.launch {
        store.setPrintIncludeImage(value)
    }

    fun setPrintShowAnswer(value: Boolean) = viewModelScope.launch {
        store.setPrintShowAnswer(value)
    }

    fun setPrintBlankRedo(value: Boolean) = viewModelScope.launch { store.setPrintBlankRedo(value) }

    fun setPrintBlankHeight(value: Int) = viewModelScope.launch {
        store.setPrintBlankHeightPt(value)
    }

    fun setReminderEnabled(value: Boolean) = viewModelScope.launch {
        store.setReviewReminderEnabled(value)
    }

    fun setReminderTime(hour: Int, minute: Int) = viewModelScope.launch {
        store.setReminderTime(hour, minute)
    }

    fun testMineru() {
        viewModelScope.launch {
            testingMineru.value = true
            val key = store.snapshotNow().mineruKey
            val result = container.mineruClient.testConnection(key)
            testingMineru.value = false
            mineruTestOk.value = result is com.mistakebook.net.ApiResult.Success
            mineruTestResult.value = if (result is com.mistakebook.net.ApiResult.Success) {
                SUCCESS
            } else {
                result.errorOrNull()?.serverMessage ?: FAILED
            }
        }
    }

    /**
     * 测试连接。
     *
     * ## 关键：用调用方传来的**当前输入**，不是已保存的配置
     * 原来读 `store.snapshotNow().activeProfile`，于是「填了 URL 和 API Key
     * 但还没按保存」时，测试用的是旧值（通常是空），必然失败。
     * 用户看到的就是「明明填了却说连不上」。
     *
     * @param draft 当前输入框里的内容
     * @return 毫秒数，UI 用来显示响应时间
     */
    fun testLlm(draft: LlmProfile) {
        viewModelScope.launch {
            testingLlm.value = true
            llmTestResult.value = null
            // 至少要有域名和 Key 才能测；模型名可以为空（测的就是「能不能拿到模型」）
            if (draft.baseUrl.isBlank() || draft.apiKey.isBlank()) {
                testingLlm.value = false
                llmTestOk.value = false
                llmTestResult.value = "请先填写接口地址和 API Key"
                return@launch
            }
            val probe = draft.normalized()
            val started = System.nanoTime()
            // 第一步：Key 与域名是否通（拉模型列表）
            val listResult = container.llmClient.listModels(probe)
            val listMs = elapsedMs(started)
            val modelCount = (listResult as? ApiResult.Success)?.data?.size ?: 0
            if (listResult is ApiResult.Failure) {
                testingLlm.value = false
                llmTestOk.value = false
                llmTestResult.value = describeError(listResult.error, "连接失败") + "（${listMs}ms）"
                return@launch
            }
            // 第二步：模型本身能否真正出字。模型名为空时跳过——
            // 用户可能就是想先确认「接口通不通」，还没想好用哪个模型
            val probeStarted = System.nanoTime()
            val probeOutcome: ApiResult<String> = if (probe.model.isBlank()) {
                ApiResult.Success("")
            } else {
                container.llmClient.probeModel(probe)
            }
            val probeMs = elapsedMs(probeStarted)
            testingLlm.value = false

            llmTestResult.value = when {
                probe.model.isBlank() -> "接口可达 · 响应 ${listMs}ms · 共 $modelCount 个可用模型" +
                    "（填上模型名可再测首字响应）"

                probeOutcome is ApiResult.Success ->
                    "连接正常 · 首字响应 ${probeMs}ms · 模型 ${probe.model} · 共 $modelCount 个可用模型"

                else -> "接口可达（${listMs}ms，已拉到 $modelCount 个模型），但模型 ${probe.model} " +
                    describeError((probeOutcome as ApiResult.Failure).error, "无响应")
            }
            llmTestOk.value = probeOutcome is ApiResult.Success && probe.model.isNotBlank()
        }
    }

    /**
     * 拉取可用模型列表。
     *
     * 同样用**当前输入**而不是已保存值——这正是「填了 URL 和 API 就该能拉列表」
     * 成立的前提。模型名可以为空：拉列表的目的就是拿到模型名。
     */
    fun fetchModels(draft: LlmProfile, onReady: (List<String>) -> Unit = {}) {
        viewModelScope.launch {
            loadingModels.value = true
            modelsError.value = null
            if (draft.baseUrl.isBlank() || draft.apiKey.isBlank()) {
                loadingModels.value = false
                modelsError.value = "请先填写接口地址和 API Key"
                return@launch
            }
            val started = System.nanoTime()
            when (val result = container.llmClient.listModels(draft.normalized())) {
                is ApiResult.Success -> {
                    val list = result.data
                    models.value = list
                    loadingModels.value = false
                    if (list.isEmpty()) {
                        modelsError.value = "接口可达（${elapsedMs(started)}ms）但没返回任何模型"
                    } else {
                        // 直接把选择弹窗打开：用户点这个按钮就是想选模型，
                        // 让他再点一次「可用模型」纯属多余
                        onReady(list)
                    }
                }

                is ApiResult.Failure -> {
                    models.value = emptyList()
                    loadingModels.value = false
                    modelsError.value = describeError(result.error, "获取模型列表失败")
                }
            }
        }
    }

    fun consumeModels() {
        models.value = emptyList()
        modelsError.value = null
    }

    /** 从模型列表里选一个，回填到当前配置的模型输入框。 */
    fun pickModel(model: String) {
        pickedModel.value = model
    }

    fun consumePickedModel() {
        pickedModel.value = null
    }

    private fun describeError(error: ApiError, prefix: String): String {
        val detail = error.serverMessage.takeIf { it.isNotBlank() }
        return if (detail != null) "$prefix：$detail" else prefix
    }

    /**
     * 毫秒耗时。
     * 用 [System.nanoTime] 而不是 `currentTimeMillis`：后者受系统时钟调整影响，
     * 用户改个系统时间就可能算出负的响应时间。
     */
    private fun elapsedMs(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000

    fun clearMineruTestResult() {
        mineruTestResult.value = null
    }

    fun clearLlmTestResult() {
        llmTestResult.value = null
    }

    fun clearTrash() {
        viewModelScope.launch {
            val trash = container.questionRepository.listDeletedBefore(0)
            trash.forEach { question ->
                container.questionRepository.hardDelete(listOf(question.id))
                container.files.deleteRecursively(container.files.questionDir(question.id))
            }
            backupMessage.value = if (trash.isEmpty()) "回收站已经是空的" else "已清空回收站"
        }
    }

    fun consumeBackupMessage() {
        backupMessage.value = null
    }

    companion object {
        const val SUCCESS = "连接成功"
        const val FAILED = "连接失败，请检查 Key 与网络"
        const val NO_PROFILE = "尚未配置大模型接入"
    }
}
