package com.mistakebook.ui.print

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.print.PdfExporter
import com.mistakebook.print.PdfPublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrintUiState(
    val questions: List<Question> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val subjectId: Long? = null,
    val status: MasteryStatus? = null,
    val keyword: String = "",
    val selected: Set<Long> = emptySet(),
    val includeImage: Boolean = false,
    val showAnswer: Boolean = false,
    val blankRedo: Boolean = true,
    val blankHeight: Int = 100,
    val generating: Boolean = false,
    val result: PdfPublisher.Output? = null,
    val skipped: List<String> = emptyList(),
    val error: String? = null
) {
    val allSelected: Boolean
        get() = questions.isNotEmpty() && selected.size == questions.size

    val subjectNames: Map<Long, String>
        get() = subjects.associate { it.id to it.name }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class PrintViewModel(private val container: AppContainer) : ViewModel() {

    private val subjectId = MutableStateFlow<Long?>(null)
    private val status = MutableStateFlow<MasteryStatus?>(null)
    private val keyword = MutableStateFlow("")

    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val includeImage = MutableStateFlow(false)
    private val showAnswer = MutableStateFlow(false)
    private val blankRedo = MutableStateFlow(true)
    private val blankHeight = MutableStateFlow(100)
    private val generating = MutableStateFlow(false)
    private val result = MutableStateFlow<PdfPublisher.Output?>(null)
    private val skipped = MutableStateFlow<List<String>>(emptyList())
    private val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val snapshot: SettingsSnapshot = container.settingsStore.snapshotNow()
            includeImage.value = snapshot.printIncludeImage
            showAnswer.value = snapshot.printShowAnswer
            blankRedo.value = snapshot.printBlankRedo
            blankHeight.value = snapshot.printBlankHeightPt
        }
    }

    private val questions: StateFlow<List<Question>> =
        combine(subjectId, status, keyword.debounce(300)) { s, st, kw -> Triple(s, st, kw) }
            .flatMapLatest { (s, st, kw) ->
                container.questionRepository.observePage(
                    filter = com.mistakebook.data.repos.QuestionRepository.Filter(
                        subjectId = s,
                        status = st,
                        keyword = kw
                    ),
                    page = 0
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<PrintUiState> = combine(
        questions,
        combine(container.subjectRepository.observeAll(), selected) { subjects, sel -> subjects to sel },
        combine(includeImage, showAnswer) { image, answer -> image to answer },
        combine(blankRedo, blankHeight, generating) { redo, height, working ->
            Triple(redo, height, working)
        },
        combine(result, skipped, error) { output, skippedList, errorText ->
            Triple(output, skippedList, errorText)
        }
    ) { list, subjectsAndSelection, imageAndAnswer, redoAndMore, outcome ->
        PrintUiState(
            questions = list,
            subjects = subjectsAndSelection.first,
            selected = subjectsAndSelection.second,
            includeImage = imageAndAnswer.first,
            showAnswer = imageAndAnswer.second,
            blankRedo = redoAndMore.first,
            blankHeight = redoAndMore.second,
            generating = redoAndMore.third,
            result = outcome.first,
            skipped = outcome.second,
            error = outcome.third
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrintUiState())

    fun setSubject(id: Long?) {
        subjectId.value = id
    }

    fun setStatus(value: MasteryStatus?) {
        status.value = value
    }

    fun setKeyword(value: String) {
        keyword.value = value
    }

    fun toggleSelect(id: Long) {
        val current = selected.value
        selected.value = if (id in current) current - id else current + id
    }

    fun selectAll(ids: List<Long>) {
        selected.value = ids.toSet()
    }

    fun invertSelection(ids: List<Long>) {
        val current = selected.value
        selected.value = ids.filterNot { it in current }.toSet()
    }

    fun setIncludeImage(value: Boolean) {
        includeImage.value = value
    }

    fun setShowAnswer(value: Boolean) {
        showAnswer.value = value
    }

    fun setBlankRedo(value: Boolean) {
        blankRedo.value = value
    }

    fun setBlankHeight(value: Int) {
        blankHeight.value = value
    }

    fun generate() {
        val state = uiState.value
        if (state.selected.isEmpty() || state.generating) return
        generating.value = true
        error.value = null
        viewModelScope.launch {
            val questions = state.selected.mapNotNull { id ->
                container.questionRepository.findById(id)
            }
            val exporter = PdfExporter(container.appContext, container.mathRenderer)
            val options = PdfExporter.Options(
                includeImage = state.includeImage,
                showAnswer = state.showAnswer,
                blankRedoMode = state.blankRedo,
                blankHeightPt = state.blankHeight
            )
            val target = container.pdfPublisher.createTempFile()
            val exportResult = runCatching {
                exporter.export(questions, options, target) { _, _ -> }
            }
            generating.value = false
            exportResult.onSuccess { result ->
                val output = container.pdfPublisher.publish(result.file)
                this@PrintViewModel.result.value = output
                skipped.value = result.skipped
            }
            exportResult.onFailure { throwable ->
                error.value = throwable.message ?: "生成失败"
            }
        }
    }

    fun consumeResult() {
        result.value = null
    }

    fun consumeError() {
        error.value = null
    }
}
