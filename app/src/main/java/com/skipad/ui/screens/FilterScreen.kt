package com.skipad.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skipad.data.filter.FilterSource
import com.skipad.data.prefs.AppSettings
import com.skipad.ui.MainViewModel
import com.skipad.ui.components.Hairline
import com.skipad.ui.components.ListRow
import com.skipad.ui.components.PageHeader
import com.skipad.ui.components.PageHorizontalPadding
import com.skipad.ui.components.ToggleRow
import com.skipad.ui.components.TextAction
import com.skipad.ui.theme.formatCount

/**
 * 规则源页。
 */
@Composable
fun FilterScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    settings: AppSettings,
) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()

    // ---- 域名查询 ----
    var showQueryDialog by remember { mutableStateOf(false) }
    var queryInput by remember { mutableStateOf("") }
    var queryResult by remember { mutableStateOf<MainViewModel.DomainQuery?>(null) }
    var queryError by remember { mutableStateOf<String?>(null) }
    var querying by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            PageHeader(
                title = "规则源",
                subtitle = "共 ${formatCount(dashboard.ruleCount.toLong())} 条规则",
                trailing = {
                    TextAction(
                        text = if (dashboard.isUpdating) "更新中" else "更新",
                        onClick = { viewModel.updateFilters() },
                        enabled = !dashboard.isUpdating,
                        emphasized = true,
                    )
                },
            )
        }

        // ---- 域名查询 ----
        item {
            ListRow(
                title = "查询域名",
                subtitle = when {
                    querying -> "查询中…"
                    queryError != null -> queryError
                    queryResult != null -> queryResultSummary(queryResult!!)
                    else -> "输入域名，立刻告诉你会不会被拦、命中哪些规则源"
                },
                onClick = { showQueryDialog = true },
                trailing = {
                    TextAction(
                        text = "查询",
                        onClick = { showQueryDialog = true },
                        emphasized = true,
                    )
                },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        dashboard.updateMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = PageHorizontalPadding,
                        end = PageHorizontalPadding,
                        top = 6.dp,
                    ),
                )
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        items(FilterSource.entries.toList(), key = { it.id }) { source ->
            ToggleRow(
                title = source.displayName,
                subtitle = source.description,
                checked = settings.enabledFilterIds.contains(source.id),
                onCheckedChange = { viewModel.toggleFilter(source, it) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }
    }

    // ---- 域名查询弹窗 ----
    if (showQueryDialog) {
        AlertDialog(
            onDismissRequest = { showQueryDialog = false },
            title = { Text("查询域名") },
            text = {
                Column {
                    OutlinedTextField(
                        value = queryInput,
                        onValueChange = { queryInput = it },
                        label = { Text("域名") },
                        placeholder = { Text("ads.example.com") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "会告诉你这个域名是否被拦截，以及命中了哪些规则源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = queryInput.trim()
                    if (input.isEmpty()) {
                        queryError = "请输入域名"
                        showQueryDialog = false
                        return@TextButton
                    }
                    queryError = null
                    queryResult = null
                    querying = true
                    showQueryDialog = false
                    viewModel.queryDomain(input) { result ->
                        querying = false
                        if (result == null) {
                            queryError = "域名格式不正确"
                        } else {
                            queryResult = result
                        }
                    }
                }) { Text("查询") }
            },
            dismissButton = {
                TextButton(onClick = { showQueryDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 把查询结果压成一行文案。
 */
private fun queryResultSummary(result: MainViewModel.DomainQuery): String = when {
    result.allowed -> "${result.domain} 在白名单中，不会被拦截"
    result.blocked && result.sources.isNotEmpty() ->
        "${result.domain} 会被拦截（命中：${result.sources.joinToString("、")}）"
    result.blocked -> "${result.domain} 会被拦截"
    else -> "${result.domain} 不会被拦截"
}
