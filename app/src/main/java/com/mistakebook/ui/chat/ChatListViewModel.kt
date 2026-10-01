package com.mistakebook.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.entities.ChatSession
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.repos.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 会话列表的一行。会话与题目的关联要一次查出来，不能在 Composable 里逐条查库。 */
data class ChatListItemUi(
    val session: ChatSession,
    val questionTitle: String?
)

data class ChatListUiState(
    val loading: Boolean = true,
    val items: List<ChatListItemUi> = emptyList()
)

class ChatListViewModel(
    private val repository: ChatRepository,
    private val questionDao: com.mistakebook.data.local.QuestionDao
) : ViewModel() {

    private val _state = MutableStateFlow(ChatListUiState())
    val state: StateFlow<ChatListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSessions().combine(questionDao.observeTitles()) { sessions, rows ->
                val titles = rows.associate { it.id to it.title }
                ChatListUiState(
                    loading = false,
                    items = sessions.map { session ->
                        ChatListItemUi(
                            session = session,
                            questionTitle = session.questionId?.let { titles[it] }
                        )
                    }
                )
            }.collect { _state.value = it }
        }
    }

    /** 首页右上角进入：直接开一个自由会话。 */
    fun startFreeSession(onReady: (Long) -> Unit) {
        viewModelScope.launch { onReady(repository.newFreeSession().id) }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { repository.renameSession(id, title) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.softDeleteSession(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.softDeleteAllSessions() }
    }
}
