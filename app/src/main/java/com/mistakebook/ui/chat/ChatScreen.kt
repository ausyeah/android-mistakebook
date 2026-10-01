package com.mistakebook.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.mistakebook.R
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.launch

/** 列表末尾的哨兵项。**必须有**——否则消息正好排满时没有可滚余量，结论会被藏在屏幕外。 */
private const val SENTINEL_INDEX = -1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    questionId: Long?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val viewModel = containerViewModel(
        container = container,
        // 每题一个固定会话：key 带上 questionId，切换题目时才不会复用上一个的 ViewModel
        key = "chat-$questionId"
    ) { c ->
        ChatViewModel(
            repository = c.chatRepository,
            stream = c.chatCompletionStream,
            settingsStore = c.settingsStore,
            questionId = questionId
        )
    }

    // 用 collectAsState 而不是 collectAsStateWithLifecycle：
    // 后者在 lifecycle-runtime-compose 里，本项目没引这个依赖（零新增依赖）。
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val copiedText = stringResource(R.string.chat_copied)
    val title = stringResource(
        if (questionId != null) R.string.chat_title_question else R.string.chat_title_free
    )

    // 内容版本号：流式时用最后一条消息的正文长度，否则只有消息条数变化时才会触发跟随
    val contentKey = remember(state.messages) {
        val last = state.messages.lastOrNull()
        (state.messages.size to (last?.content?.length ?: 0))
    }
    val coordinator = rememberAutoScrollEffect(listState, contentKey)

    var showKeyGate by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf(0L) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // 首次进入题目会话时预填首问，**不自动发送**
    LaunchedEffect(state.sessionId) {
        if (state.sessionId != 0L && state.messages.isEmpty() && questionId != null) {
            viewModel.onInputChange(ChatViewModel.PREFILL_QUESTION)
        }
    }

    LaunchedEffect(state.needsApiKey) { if (state.needsApiKey) showKeyGate = true }
    LaunchedEffect(state.truncated) { if (state.truncated) showKeyGate = false }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.Copied -> snackbarHost.showSnackbar(copiedText)
                is ChatEvent.Error -> snackbarHost.showSnackbar(event.text)
            }
        }
    }

    if (state.truncated) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTruncatedNotice,
            title = { Text(stringResource(R.string.chat_truncated_title)) },
            text = { Text(stringResource(R.string.chat_truncated_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTruncatedNotice) {
                    Text(stringResource(R.string.chat_dismiss))
                }
            }
        )
    }

    if (showKeyGate) {
        ApiKeyRequiredDialog(
            mineruMissing = false,
            llmMissing = true,
            onOpenSettings = {
                showKeyGate = false
                viewModel.dismissApiKeyGate()
                onOpenSettings()
            },
            onDismiss = {
                showKeyGate = false
                viewModel.dismissApiKeyGate()
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.chat_delete_confirm_title)) },
            text = { Text(stringResource(R.string.chat_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteMessage(pendingDeleteId)
                }) { Text(stringResource(R.string.chat_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.chat_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_rename_hint)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    viewModel.renameSession(renameText)
                }) { Text(stringResource(R.string.chat_rename_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title.ifBlank { title },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_cancel)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        renameText = state.title
                        showRenameDialog = true
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.chat_rename))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.chat_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    MessageList(
                        state = state,
                        listState = listState,
                        mathRenderer = container.mathRenderer,
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            viewModel.notifyCopied()
                        },
                        onRetry = viewModel::retry,
                        onDelete = { id ->
                            pendingDeleteId = id
                            showDeleteDialog = true
                        }
                    )
                }

                if (coordinator.showJumpButton) {
                    JumpToBottomButton(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        onClick = { scope.launch { coordinator.jumpToBottom(listState) } }
                    )
                }
            }

            ChatInputBar(
                text = state.input,
                streaming = state.isStreaming,
                attachments = state.pendingAttachments,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onRemoveAttachment = viewModel::removePendingAttachment
            )
        }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    listState: LazyListState,
    mathRenderer: com.mistakebook.math.MathRenderer,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit
) {
    // 「已省略 N 条早期对话」插在最上面：被丢的永远是最旧的轮次
    val items: List<ChatListItem> = buildList {
        if (state.omittedCount > 0) add(ChatListItem.OmittedNotice(state.omittedCount))
        state.messages.forEach { add(ChatListItem.Message(it)) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items.size) { index ->
            when (val item = items[index]) {
                is ChatListItem.OmittedNotice -> OmittedNoticeRow(item.count)
                is ChatListItem.Message -> MessageBubble(
                    message = item.message,
                    mathRenderer = mathRenderer,
                    onCopy = onCopy,
                    onRetry = onRetry,
                    onDelete = onDelete
                )
            }
        }
        // 哨兵项：保证永远有可滚余量，否则消息排满时结论会正好卡在屏幕外
        item(key = SENTINEL_INDEX) { Spacer(Modifier.height(1.dp)) }
    }
}

private sealed interface ChatListItem {
    data class OmittedNotice(val count: Int) : ChatListItem
    data class Message(val message: ChatMessage) : ChatListItem
}

@Composable
private fun OmittedNoticeRow(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_omitted_notice, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    mathRenderer: com.mistakebook.math.MathRenderer,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit
) {
    val isUser = message.role == ChatRole.USER
    val isInjected = message.injected

    // 题目上下文那条是给模型看的，用一条低调的分隔样式展示，
    // 免得用户以为 AI 莫名其妙先说了一段。
    if (isInjected) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.chat_context_header),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            RichText(
                text = message.content,
                mathRenderer = mathRenderer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                RichText(
                    text = message.content,
                    mathRenderer = mathRenderer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (message.status == MessageStatus.STREAMING) {
                    Spacer(Modifier.height(6.dp))
                    // 光标：流式时末尾的小圆点
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        StatusRow(
            message = message,
            isUser = isUser,
            onCopy = onCopy,
            onRetry = onRetry,
            onDelete = onDelete
        )
    }
}

@Composable
private fun StatusRow(
    message: ChatMessage,
    isUser: Boolean,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        when {
            message.status == MessageStatus.FAILED -> {
                Text(
                    text = stringResource(R.string.chat_failed_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.chat_retry), style = MaterialTheme.typography.labelSmall)
                }
            }

            message.status == MessageStatus.CANCELED -> {
                Text(
                    text = stringResource(R.string.chat_stopped_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            message.status == MessageStatus.DONE && !isUser -> {
                IconButton(
                    onClick = { onCopy(message.content) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        IconButton(onClick = { onDelete(message.id) }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.chat_delete_message),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun JumpToBottomButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
        modifier = modifier
    ) {
        IconButton(onClick = onClick) {
            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = stringResource(R.string.chat_jump_to_bottom)
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    streaming: Boolean,
    attachments: List<com.mistakebook.data.local.entities.ChatAttachment>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Long) -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachments.forEach { attachment ->
                        PendingAttachmentChip(attachment = attachment, onRemove = { onRemoveAttachment(attachment.id) })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (streaming) onStop() else onSend() },
                    enabled = streaming || text.isNotBlank() || attachments.isNotEmpty(),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (streaming) Icons.Default.Stop else Icons.Default.Send,
                        contentDescription = stringResource(if (streaming) R.string.chat_stop else R.string.chat_send)
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingAttachmentChip(
    attachment: com.mistakebook.data.local.entities.ChatAttachment,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onRemove)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            when (attachment.status) {
                com.mistakebook.domain.AttachmentStatus.PREPARING -> {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.chat_attachment_preparing),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                else -> {
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp)
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.chat_attachment_remove),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
