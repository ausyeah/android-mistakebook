package com.mistakebook.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.local.displayTitle
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.printImagePath
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.ui.common.DifficultyStars
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.mistakebook.ui.common.EmptyState
import com.mistakebook.ui.common.ErrorReasonChip
import com.mistakebook.ui.common.Format
import com.mistakebook.ui.common.QuestionThumb
import com.mistakebook.ui.common.SubjectDot
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.theme.Danger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    filterDue: Boolean = false,
    pickedNotebookId: Long? = null,
    onNotebookPicked: (Long?) -> Unit = {},
    onAddByPhoto: () -> Unit,
    /**
     * 相册导入的单张图进裁剪页。
     * 相册照片和拍照照片走**完全相同**的流程：框选、旋转 90°、涂鸦遮蔽，
     * 全部走完才提交识别。之前相册是导入后直接提交，裁剪页被整个跳过，
     * 所以用户没法框选题目、也没法涂掉红笔批注。
     *
     * 原来的 `onImported: (List<Long>) -> Unit` 随之移除——
     * 相册不再直连提交，没有任务 id 需要回传给首页了。
     */
    onCropImage: (String) -> Unit,
    onImportPdf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrint: () -> Unit,
    onOpenNotebooks: () -> Unit = {},
    onOpenQuestion: (Long) -> Unit
) {
    val viewModel: HomeViewModel = containerViewModel(container) { HomeViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(filterDue) {
        if (filterDue) viewModel.setDueOnly(true)
    }
    // 错题本页选完回来时应用筛选
    LaunchedEffect(pickedNotebookId) {
        viewModel.setNotebook(pickedNotebookId)
        onNotebookPicked(null)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var showKeyGate by remember { mutableStateOf(false) }
    var mineruMissing by remember { mutableStateOf(false) }
    var llmMissing by remember { mutableStateOf(false) }

    // 相册入口直接拉起系统选择器：原先中间还夹一个「从相册选择」页，
    // 整页只有一个按钮，等于多点一次屏幕才能到真正的选择器，已删除。
    //
    // 用**单选**而不是多选：现在每张图都要走完整的裁剪流程
    // （框选 → 旋转 → 涂鸦遮蔽 → 提交），多选会让用户在同一个页面里反复进出。
    // 需要一次处理很多张的场景走「导入 PDF」，那边有分页预览。
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val files = container.imageImporter.importUris(listOf(uri))
            importing = false
            if (files.isEmpty()) {
                snackbarHostState.showSnackbar(context.getString(R.string.gallery_copy_failed, ""))
            } else {
                // 交给裁剪页：框选 → 旋转 → 涂鸦遮蔽 → 提交，与拍照完全同一条路
                onCropImage(files.first().absolutePath)
            }
        }
    }

    fun pickFromGallery() {
        scope.launch {
            val snapshot = container.settingsStore.snapshotNow()
            if (!snapshot.mineruConfigured || !snapshot.llmConfigured) {
                mineruMissing = !snapshot.mineruConfigured
                llmMissing = !snapshot.llmConfigured
                showKeyGate = true
                return@launch
            }
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 搜索框放导航栏正中，做窄一点。
                    // 之前它独占一整行压在筛选条上方，把首屏最宝贵的位置全占了。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        OutlinedTextField(
                            value = state.keyword,
                            onValueChange = viewModel::setKeyword,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.home_search_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (state.keyword.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.setKeyword("") },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.home_search_clear),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                    }
                },
                actions = {
                    // 原先这里有个红色「待复习」文字角标。它不成图标、颜色又跳，
                    // 顶部一律不显示任何文字标记。
                    // 这里先后出现过两种：红色「待复习」角标、中性的「共 N 题」徽标，
                    // 用户两次都要求删掉——搜索栏右边紧挨着图标按钮，
                    // 塞一段文字在中间既突兀又抢注意力，题数在筛选菜单里已经能看到。
                    IconButton(onClick = onOpenPrint) {
                        Icon(Icons.Default.Print, contentDescription = stringResource(R.string.home_action_print))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_action_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_add_by_photo)) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            FilterRow(
                state = state,
                onStatusChange = viewModel::setStatus,
                onDueChange = viewModel::setDueOnly,
                onSubjectChange = viewModel::setSubject,
                onNotebookChange = viewModel::setNotebook,
                onOpenNotebooks = onOpenNotebooks
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.questions.isEmpty() && state.totalCount == 0 -> EmptyStateWithActions(
                onAddByPhoto = onAddByPhoto,
                onAddFromGallery = { pickFromGallery() },
                onImportPdf = onImportPdf
                )

                state.questions.isEmpty() -> EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    subtitle = "换个筛选条件试试"
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.questions, key = { it.id }) { question ->
                        val subjectName = state.subjects.firstOrNull { it.id == question.subjectId }?.name
                        SwipeableQuestionCard(
                            question = question,
                            subjectName = subjectName,
                            dueToday = state.dueCount > 0 && isDue(question),
                            onClick = { onOpenQuestion(question.id) },
                            onDelete = {
                                viewModel.delete(question)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "已删除「${question.displayTitle}」",
                                        actionLabel = "撤销",
                                        duration = androidx.compose.material3.SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoDelete(question)
                                    }
                                }
                            }
                        )
                    }
                    item {
                        // 触底加载下一页（每页 30）
                        LaunchedEffect(state.questions.size) {
                            viewModel.loadMore()
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.home_add_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                AddSheetItem(
                    icon = Icons.Default.PhotoCamera,
                    text = stringResource(R.string.home_add_by_photo),
                    onClick = {
                        showAddSheet = false
                        onAddByPhoto()
                    }
                )
                AddSheetItem(
                    icon = Icons.Default.PhotoLibrary,
                    text = stringResource(R.string.home_add_from_gallery),
                    enabled = !importing,
                    onClick = {
                        showAddSheet = false
                        pickFromGallery()
                    }
                )
                AddSheetItem(
                    icon = Icons.Default.PictureAsPdf,
                    text = stringResource(R.string.home_import_pdf),
                    onClick = {
                        showAddSheet = false
                        onImportPdf()
                    }
                )
            }
        }
    }

    if (importing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.gallery_copying))
                }
            }
        }
    }

    if (showKeyGate) {
        ApiKeyRequiredDialog(
            mineruMissing = mineruMissing,
            llmMissing = llmMissing,
            onOpenSettings = onOpenSettings,
            onDismiss = { showKeyGate = false }
        )
    }
}

private fun isDue(question: Question): Boolean {
    val next = question.nextReviewAt ?: return false
    return question.status != com.mistakebook.domain.MasteryStatus.MASTERED &&
        next <= java.time.LocalDate.now().toEpochDay()
}

@Composable
private fun EmptyStateWithActions(
    onAddByPhoto: () -> Unit,
    onImportPdf: () -> Unit,
    onAddFromGallery: () -> Unit
) {
    EmptyState(
        title = stringResource(R.string.home_empty_title),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Button(onClick = onAddByPhoto) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_add_by_photo))
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    androidx.compose.material3.TextButton(onClick = onAddFromGallery) {
                        Text(stringResource(R.string.home_add_from_gallery))
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.TextButton(onClick = onImportPdf) {
                        Text(stringResource(R.string.home_import_pdf))
                    }
                }
            }
        }
    )
}

@Composable
private fun AddSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableQuestionCard(
    question: Question,
    subjectName: String?,
    dueToday: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(Danger.copy(alpha = 0.12f))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = Danger,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    ) {
        QuestionCard(
            question = question,
            subjectName = subjectName,
            dueToday = dueToday,
            onClick = onClick
        )
    }
}

@Composable
private fun QuestionCard(
    question: Question,
    subjectName: String?,
    dueToday: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            QuestionThumb(
                imagePath = question.printImagePath,
                contentDescription = stringResource(R.string.edit_original_image),
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubjectDot(subjectName = subjectName)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = subjectName ?: "未分类",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dueToday) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Danger)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 标题优先（用户可自定义 / 大模型生成），没有标题才回退显示题干摘要
                Text(
                    text = question.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(42.dp)
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ErrorReasonChip(reason = question.errorReason)
                    Spacer(Modifier.width(8.dp))
                    DifficultyStars(difficulty = question.difficulty)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = Format.relativeDay(question.nextReviewAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/**
 * 三个筛选下拉：学科 / 掌握程度 / 错题本。
 *
 * 需求要求「做成三个可以展开的菜单」，所以这里不再混用 FilterChip——
 * 三个维度统一成同一种按钮外观，一眼看出是同一族控件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    state: HomeUiState,
    onStatusChange: (MasteryStatus?) -> Unit,
    onDueChange: (Boolean) -> Unit,
    onSubjectChange: (Long?) -> Unit,
    onNotebookChange: (Long?) -> Unit,
    onOpenNotebooks: () -> Unit
) {
    FilterMenuRow {
        SubjectFilterMenu(
            subjects = state.subjects,
            selectedId = state.subjectId,
            count = state.filteredCount.takeIf { state.subjectId != null },
            onSelect = onSubjectChange,
            modifier = Modifier.weight(1f)
        )
        MasteryFilterMenu(
            status = state.status,
            dueOnly = state.dueOnly,
            count = state.filteredCount.takeIf { state.status != null || state.dueOnly },
            onSelect = { status, due ->
                onStatusChange(status)
                if (due != state.dueOnly) onDueChange(due)
            },
            modifier = Modifier.weight(1f)
        )
        NotebookFilterMenu(
            notebooks = state.notebooks,
            selectedId = state.notebookId,
            count = state.filteredCount.takeIf { state.notebookId != null },
            onSelect = onNotebookChange,
            onManage = onOpenNotebooks,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatusChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelSmall) }
    )
}

@Composable
private fun SubjectDropdown(
    subjects: List<com.mistakebook.data.local.entities.Subject>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val label = subjects.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.home_subject_all)
    Box(modifier = modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_subject_all)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            subjects.forEach { subject ->
                DropdownMenuItem(
                    text = { Text(subject.name) },
                    onClick = {
                        onSelect(subject.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 错题本筛选下拉。
 *
 * 底部多一个「管理错题本…」入口：筛选只是选，新建/重命名/删除要单独一个页面，
 * 塞进下拉菜单会把这个已经很长的标签行撑爆。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookDropdown(
    notebooks: List<com.mistakebook.data.local.entities.Notebook>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val label = notebooks.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.notebook_filter)
    Box(modifier = modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            trailingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notebook_filter)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            notebooks.forEach { notebook ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(notebook.name)
                            if (notebook.isDefault) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.notebook_default_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(notebook.id)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notebook_manage)) },
                onClick = {
                    expanded = false
                    onManage()
                }
            )
        }
    }
}
