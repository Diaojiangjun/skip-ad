package com.skipad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.skipad.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skipad.data.prefs.AppSettings
import com.skipad.ui.MainViewModel
import com.skipad.ui.components.EmptyState
import com.skipad.ui.components.Hairline
import com.skipad.ui.components.ListRow
import com.skipad.ui.components.PageHeader
import com.skipad.ui.components.PageHorizontalPadding
import com.skipad.ui.components.SectionLabel
import com.skipad.ui.components.StatBlock
import com.skipad.ui.theme.formatCount

/**
 * 概览页。
 *
 * 只有一个状态、三个数字、一份列表。没有卡片、没有色块。
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    settings: AppSettings,
    onToggleProtection: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()

    // 状态刷新。
    // 启动过程中（已开启但服务还没就绪）用 1 秒间隔，让「启动中 → 保护中」
    // 在隧道建立后立刻反映出来；稳定运行时放到 5 秒，避免频繁查库。
    LaunchedEffect(settings.protectionEnabled, dashboard.serviceRunning) {
        while (true) {
            val starting = settings.protectionEnabled && !dashboard.serviceRunning
            kotlinx.coroutines.delay(if (starting) 1000 else 5000)
            viewModel.refreshStats()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            PageHeader(
                title = androidx.compose.ui.res.stringResource(R.string.app_name),
                trailing = {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onOpenSettings)
                            .padding(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_nav_settings),
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                },
            )
        }

        item { Spacer(Modifier.height(12.dp)) }

        // ---- 状态区 ----
        item {
            val active = settings.protectionEnabled && dashboard.serviceRunning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageHorizontalPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            active -> "保护中"
                            settings.protectionEnabled -> "启动中"
                            else -> "已关闭"
                        },
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            active -> "已加载 ${formatCount(dashboard.ruleCount.toLong())} 条规则"
                            settings.protectionEnabled -> "正在建立隧道，请稍候"
                            dashboard.ruleCount > 0 ->
                                "已加载 ${formatCount(dashboard.ruleCount.toLong())} 条规则"
                            else -> "尚未加载规则"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = settings.protectionEnabled,
                    onCheckedChange = onToggleProtection,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
        }

        // ---- 数据区 ----
        item {
            Spacer(Modifier.height(24.dp))
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                StatBlock(
                    value = formatCount(dashboard.todayBlocked),
                    label = "今日",
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    value = formatCount(dashboard.totalBlocked),
                    label = "累计",
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    value = formatCount(dashboard.uniqueDomains),
                    label = "域名",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(20.dp))
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 更新提示 ----
        dashboard.updateMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = PageHorizontalPadding,
                        end = PageHorizontalPadding,
                        top = 14.dp,
                    ),
                )
            }
        }

        // ---- 热门域名 ----
        item { SectionLabel("热门域名") }

        if (dashboard.topDomains.isEmpty()) {
            item { EmptyState("还没有拦截记录") }
        } else {
            items(dashboard.topDomains, key = { it.domain }) { entry ->
                ListRow(
                    title = entry.domain,
                    trailing = {
                        Text(
                            text = "×${entry.count}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            }
        }
    }
}
