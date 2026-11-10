package com.skipad.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skipad.ui.MainViewModel
import com.skipad.ui.components.EmptyState
import com.skipad.ui.components.Hairline
import com.skipad.ui.components.ListRow
import com.skipad.ui.components.PageHeader
import com.skipad.ui.components.SectionLabel
import com.skipad.ui.components.PageHorizontalPadding
import com.skipad.ui.components.TextAction
import com.skipad.ui.theme.formatRelativeTime

/**
 * 日志页。
 *
 * 纯列表，无卡片、无图标。
 */
@Composable
fun LogScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            PageHeader(
                title = "日志",
                trailing = {
                    if (logs.entries.isNotEmpty()) {
                        TextAction(text = "清空", onClick = { showClearDialog = true })
                    }
                },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 按应用归因 ----
        if (logs.appStats.isNotEmpty()) {
            item { SectionLabel("按应用") }
            item {
                Text(
                    text = "按拦截发生时的前台应用归因，属近似统计",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = PageHorizontalPadding,
                        end = PageHorizontalPadding,
                        bottom = 6.dp,
                    ),
                )
            }
            items(logs.appStats, key = { it.appPackage }) { stat ->
                ListRow(
                    title = appDisplayName(stat.appPackage),
                    subtitle = "${stat.domainCount} 个域名",
                    trailing = {
                        Text(
                            text = "${stat.count}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            }
            item {
                Spacer(Modifier.height(8.dp))
                Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            }
        }

        if (logs.entries.isEmpty()) {
            item { EmptyState("还没有拦截记录") }
        } else {
            items(logs.entries, key = { it.domain }) { entry ->
                ListRow(
                    title = entry.domain,
                    subtitle = formatRelativeTime(entry.lastSeen),
                    trailing = {
                        Text(
                            text = "${entry.count}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空日志") },
            text = { Text("将删除全部历史拦截记录，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLogs()
                    showClearDialog = false
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

}

/**
 * 把包名显示成可读的应用名。
 *
 * 取不到应用名时回落到包名本身 —— 不猜测、不编造。
 */
@androidx.compose.runtime.Composable
private fun appDisplayName(packageName: String): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}
