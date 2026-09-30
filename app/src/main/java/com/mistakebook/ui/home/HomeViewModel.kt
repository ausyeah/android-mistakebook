package com.mistakebook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.data.repos.QuestionRepository
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.MasteryStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeUiState(
    val questions: List<Question> = emptyList(),
    val filteredCount: Int = 0,
    val totalCount: Int = 0,
    val dueCount: Int = 0,
    val status: MasteryStatus? = null,
    val subjectId: Long? = null,
    val notebookId: Long? = null,
    val keyword: String = "",
    val dueOnly: Boolean = false,
    val subjects: List<Subject> = emptyList(),
    val notebooks: List<com.mistakebook.data.local.entities.Notebook> = emptyList(),
    val loading: Boolean = true
) {
    val today: Long get() = LocalDate.now().toEpochDay()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val repository: QuestionRepository = container.questionRepository
    private val statusFilter = MutableStateFlow<MasteryStatus?>(null)
    private val subjectFilter = MutableStateFlow<Long?>(null)
    private val notebookFilter = MutableStateFlow<Long?>(null)
    private val keywordInput = MutableStateFlow("")
    private val page = MutableStateFlow(0)
    private val dueOnly = MutableStateFlow(false)

    private data class Query(
        val status: MasteryStatus?,
        val subjectId: Long?,
        val notebookId: Long?,
        val keyword: String,
        val page: Int,
        val dueOnly: Boolean
    )

    // combine 的类型化重载最多只到 5 个 flow。第 6 个用一次嵌套 combine 补上，
    // 强用 vararg 版本会退化成 Array<Any?>，类型全丢。
    private val filter = combine(
        statusFilter,
        subjectFilter,
        notebookFilter,
        keywordInput.debounce(300),
        dueOnly
    ) { status, subject, notebook, keyword, due ->
        Query(status, subject, notebook, keyword, 0, due)
    }.combine(page) { query, pageIndex -> query.copy(page = pageIndex) }

    private fun questionsOf(query: Query) = if (query.dueOnly) {
        repository.observeDue(LocalDate.now())
    } else {
        repository.observePage(
            filter = QuestionRepository.Filter(
                status = query.status,
                subjectId = query.subjectId,
                notebookId = query.notebookId,
                keyword = query.keyword
            ),
            page = query.page
        )
    }

    private val questions: StateFlow<List<Question>> = filter
        .flatMapLatest { query -> questionsOf(query) }
        .map { list ->
            if (dueOnly.value) {
                // 待复习是另一个数据源，学科/错题本/关键词只能在内存里补筛
                list.filter { question ->
                    (statusFilter.value == null || question.status == statusFilter.value) &&
                        (subjectFilter.value == null || question.subjectId == subjectFilter.value) &&
                        (notebookFilter.value == null || question.notebookId == notebookFilter.value) &&
                        (keywordInput.value.isBlank() || question.stem.contains(keywordInput.value.trim()))
                }
            } else {
                list
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filteredCount: StateFlow<Int> = filter
        .flatMapLatest { query ->
            if (query.dueOnly) {
                questionsOf(query).map { it.size }
            } else {
                repository.observeCount(
                    QuestionRepository.Filter(
                        status = query.status,
                        subjectId = query.subjectId,
                        notebookId = query.notebookId,
                        keyword = query.keyword
                    )
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val uiState: StateFlow<HomeUiState> = combine(
        questions,
        filteredCount,
        repository.observeTotalCount(),
        repository.observeDueCount(LocalDate.now())
    ) { list, filtered, total, due ->
        HomeUiState(
            questions = list,
            filteredCount = filtered,
            totalCount = total,
            dueCount = due,
            loading = false
        )
    }
        .combine(statusFilter) { state, status -> state.copy(status = status) }
        .combine(subjectFilter) { state, subjectId -> state.copy(subjectId = subjectId) }
        .combine(notebookFilter) { state, id -> state.copy(notebookId = id) }
        .combine(keywordInput) { state, keyword -> state.copy(keyword = keyword) }
        .combine(dueOnly) { state, due -> state.copy(dueOnly = due) }
        .combine(container.subjectRepository.observeAll()) { state, subjects ->
            state.copy(subjects = subjects)
        }
        .combine(container.notebookRepository.observeAll()) { state, notebooks ->
            state.copy(notebooks = notebooks)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setStatus(status: MasteryStatus?) {
        statusFilter.value = status
        page.value = 0
    }

    fun setSubject(subjectId: Long?) {
        subjectFilter.value = subjectId
        page.value = 0
    }

    fun setNotebook(notebookId: Long?) {
        notebookFilter.value = notebookId
        page.value = 0
    }

    fun setKeyword(keyword: String) {
        keywordInput.value = keyword
        page.value = 0
    }

    fun setDueOnly(value: Boolean) {
        dueOnly.value = value
        page.value = 0
    }

    fun loadMore() {
        if (filteredCount.value > questions.value.size) {
            page.value = page.value + 1
        }
    }

    fun delete(question: Question) {
        viewModelScope.launch {
            repository.softDelete(question.id)
        }
    }

    fun undoDelete(question: Question) {
        viewModelScope.launch {
            repository.restore(question.id)
        }
    }

    fun clearTrash() {
        viewModelScope.launch {
            repository.listDeletedBefore(0).forEach { question ->
                repository.hardDelete(listOf(question.id))
                container.files.deleteRecursively(container.files.questionDir(question.id))
            }
        }
    }
}
