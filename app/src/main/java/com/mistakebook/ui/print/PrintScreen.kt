package com.mistakebook.ui.print

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mistakebook.data.local.displayTitle
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.printImagePath
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.EmptyState
import com.mistakebook.ui.common.Format
import com.mistakebook.ui.common.QuestionThumb
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.settings.BlankHeightRow
import com.mistakebook.print.ExportFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 打印页（PRD 7.7）：筛选 -> 勾选 -> 打印选项 -> 生成 PDF -> 分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: PrintViewModel = containerViewModel(container) { PrintViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.print_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Button(
                    onClick = viewModel::generate,
                    enabled = state.selected.isNotEmpty() && !state.generating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (state.generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(
                            R.string.print_selected_format,
                            state.selected.size
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            PrintFilters(
                state = state,
                onSubjectChange = viewModel::setSubject,
                onStatusChange = viewModel::setStatus,
                onKeywordChange = viewModel::setKeyword,
                onSelectAll = { viewModel.selectAll(state.questions.map { it.id }) },
                onInvert = { viewModel.invertSelection(state.questions.map { it.id }) }
            )

            PrintOptionsPanel(
                includeImage = state.includeImage,
                showAnswer = state.showAnswer,
                blankRedo = state.blankRedo,
                blankHeight = state.blankHeight,
                onIncludeImage = viewModel::setIncludeImage,
                onShowAnswer = viewModel::setShowAnswer,
                onBlankRedo = viewModel::setBlankRedo,
                onBlankHeight = viewModel::setBlankHeight,
                format = state.format,
                onFormat = viewModel::setFormat
            )

            if (state.questions.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.print_empty),
                    subtitle = ""
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.questions, key = { _, item -> item.id }) { index, question ->
                        val subjectName = state.subjectNames[question.subjectId]
                        PrintRow(
                            question = question,
                            subjectName = subjectName,
                            index = index + 1,
                            checked = question.id in state.selected,
                            onToggle = { viewModel.toggleSelect(question.id) }
                        )
                    }
                }
            }
        }
    }

    if (state.result != null) {
        val output = state.result!!
        AlertDialog(
            onDismissRequest = viewModel::consumeResult,
            title = { Text(stringResource(R.string.print_done_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.print_path_format, output.displayPath),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.skipped.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.print_skipped_format,
                                state.skipped.joinToString("、")
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        // MIME 必须按格式取：写死 application/pdf 的话，分享 docx 时收件部应用会拒绝。
                            type = output.format.mimeType
                        putExtra(Intent.EXTRA_STREAM, output.shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                }) { Text(stringResource(R.string.print_share)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(output.shareUri, output.format.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }) { Text(stringResource(R.string.print_open)) }
                    TextButton(onClick = viewModel::consumeResult) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        )
    }

    if (state.error != null) {
        AlertDialog(
            onDismissRequest = viewModel::consumeError,
            title = { Text(stringResource(R.string.print_failed, "")) },
            text = { Text(state.error.orEmpty()) },
            confirmButton = {
                TextButton(onClick = viewModel::consumeError) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

@Composable
private fun PrintRow(
    question: Question,
    subjectName: String?,
    /** 打印清单里的连续序号，和 PDF 卡片上的编号一致，方便对照漏题。 */
    index: Int,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            QuestionThumb(
                imagePath = question.printImagePath,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.print_item_index, index),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = subjectName ?: stringResource(R.string.print_unclassified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = question.displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrintFilters(
    state: PrintUiState,
    onSubjectChange: (Long?) -> Unit,
    onStatusChange: (MasteryStatus?) -> Unit,
    onKeywordChange: (String) -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubjectDropdown(
                subjects = state.subjects,
                selectedId = state.subjectId,
                onSelect = onSubjectChange,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.print_select_all)) }
            TextButton(onClick = onInvert) { Text(stringResource(R.string.print_select_invert)) }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf<Pair<MasteryStatus?, Int>>(
                null to R.string.home_filter_all,
                MasteryStatus.ACTIVE to R.string.home_filter_active,
                MasteryStatus.MASTERED to R.string.home_filter_mastered
            ).forEach { (value, labelRes) ->
                FilterChip(
                    selected = state.status == value,
                    onClick = { onStatusChange(value) },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }
    }
}

@Composable
private fun PrintOptionsPanel(
    includeImage: Boolean,
    showAnswer: Boolean,
    blankRedo: Boolean,
        blankHeight: Int,
        format: ExportFormat,
    onIncludeImage: (Boolean) -> Unit,
    onShowAnswer: (Boolean) -> Unit,
    onBlankRedo: (Boolean) -> Unit,
        onBlankHeight: (Int) -> Unit,
        onFormat: (ExportFormat) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.print_options),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            OptionSwitch(
                label = stringResource(R.string.print_include_image),
                checked = includeImage,
                onChange = onIncludeImage
            )
            OptionSwitch(
                label = stringResource(R.string.print_show_answer),
                checked = showAnswer,
                onChange = onShowAnswer
            )
            OptionSwitch(
                label = stringResource(R.string.print_blank_redo),
                checked = blankRedo,
                onChange = onBlankRedo
            )
            if (blankRedo) {
                // 与设置页共用同一控件：直接显示并使用设置里存的值，不再是写死的三档
                BlankHeightRow(height = blankHeight, onChange = onBlankHeight)
            }
            Spacer(Modifier.height(10.dp))
            FormatRow(format = format, onChange = onFormat)
        }
    }
}

  /**
   * 导出格式选择。
   *
   * 用三个卡片而不是下拉框：三种格式的**用途差很大**（打印 / 分享 / 交给 Word 编辑），
   * 下拉框里一行字说不清，选错了用户开始导出才发现。
   */
  @Composable
  private fun FormatRow(format: ExportFormat, onChange: (ExportFormat) -> Unit) {
      Column {
          Text(
              text = stringResource(R.string.export_format_title),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(Modifier.height(6.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              ExportFormat.entries.forEach { candidate ->
                  val selected = candidate == format
                  Surface(
                      shape = RoundedCornerShape(8.dp),
                      color = if (selected) {
                          MaterialTheme.colorScheme.primaryContainer
                      } else {
                          MaterialTheme.colorScheme.surfaceVariant
                      },
                      border = if (selected) {
                          androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                      } else {
                          null
                      },
                      modifier = Modifier
                          .weight(1f)
                          .clickable { onChange(candidate) }
                  ) {
                      Box(
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                          contentAlignment = Alignment.Center
                      ) {
                          Text(
                              text = stringResource(candidate.labelRes),
                              style = MaterialTheme.typography.labelSmall,
                              color = if (selected) {
                                  MaterialTheme.colorScheme.onPrimaryContainer
                              } else {
                                  MaterialTheme.colorScheme.onSurfaceVariant
                              },
                              maxLines = 2,
                              textAlign = TextAlign.Center
                          )
                      }
                  }
              }
          }
      }
  }

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
