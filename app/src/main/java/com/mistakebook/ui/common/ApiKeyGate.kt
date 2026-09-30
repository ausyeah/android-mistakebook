package com.mistakebook.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mistakebook.R

/**
 * 未配置 Key 时的统一引导（验收 3：不发起请求、不 crash，只引导去设置）。
 *
 * 必须如实区分缺的是 MinerU 还是大模型接入配置——之前这里写死显示「MinerU API Key」，
 * 结果实际缺的是大模型配置时，用户反复去检查一个已经填好的 Key。
 */
@Composable
fun ApiKeyRequiredDialog(
    mineruMissing: Boolean,
    llmMissing: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val missing = buildList {
        if (mineruMissing) add(stringResource(R.string.settings_mineru_key))
        if (llmMissing) add(stringResource(R.string.error_no_key_missing_llm))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_no_key)) },
        text = { Text(missing.joinToString("\n")) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenSettings()
            }) { Text(stringResource(R.string.error_no_key_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
