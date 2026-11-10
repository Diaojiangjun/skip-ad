package com.skipad.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skipad.data.prefs.AppSettings
import com.skipad.data.prefs.BlockMode
import com.skipad.data.prefs.UpstreamDns
import com.skipad.ui.MainViewModel
import com.skipad.ui.components.CheckboxLike
import com.skipad.ui.components.ChoiceRow
import com.skipad.ui.components.Hairline
import com.skipad.ui.components.KeepAliveSection
import com.skipad.ui.components.ListRow
import com.skipad.ui.components.PageHeader
import com.skipad.ui.components.PageHorizontalPadding
import com.skipad.ui.components.SectionLabel
import com.skipad.ui.components.StatusDot
import com.skipad.ui.components.TextAction
import com.skipad.ui.components.ToggleRow

/**
 * 设置页。
 *
 * 全部用「区域标题 + 行 + 发丝线」组织，不用任何卡片或色块。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    var showAddAllow by remember { mutableStateOf(false) }
    var showAddCustomDomain by remember { mutableStateOf(false) }
    var customDomainInput by remember { mutableStateOf("") }
    var customDomainMessage by remember { mutableStateOf<String?>(null) }
    val customDomainEffect by viewModel.customDomainEffect.collectAsStateWithLifecycle()
    var showAppList by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var newDomain by remember { mutableStateOf("") }
    var subscriptionUrl by remember(settings.skipSubscriptionUrl) {
        mutableStateOf(settings.skipSubscriptionUrl)
    }

    // 定期回读自定义域名的生效状态（保护开启/引擎热更新后状态会变）
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshCustomDomainEffect()
            kotlinx.coroutines.delay(5000)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            PageHeader(
                title = "设置",
                leading = { com.skipad.ui.BackLeading(onBack) },
            )
        }

        // ---- 拦截行为 ----
        item { SectionLabel("拦截行为") }
        items(BlockMode.entries.toList(), key = { it.name }) { mode ->
            ChoiceRow(
                title = mode.label,
                subtitle = mode.description,
                selected = settings.blockMode == mode,
                onClick = { viewModel.setBlockMode(mode) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 上游 DNS ----
        item { SectionLabel("上游 DNS") }
        items(UpstreamDns.presets, key = { it.first }) { (address, label) ->
            ChoiceRow(
                title = label,
                subtitle = address,
                selected = settings.upstreamDns == address,
                onClick = { viewModel.setUpstreamDns(address) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 白名单 ----
        item { SectionLabel("白名单") }
        if (settings.allowList.isEmpty()) {
            item {
                ListRow(title = "暂无条目", subtitle = "白名单中的域名不会被拦截")
            }
        } else {
            items(settings.allowList.sorted(), key = { it }) { domain ->
                ListRow(
                    title = domain,
                    trailing = {
                        TextAction(
                            text = "移除",
                            onClick = { viewModel.removeFromAllowList(domain) },
                        )
                    },
                )
                Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            }
        }
        item {
            ListRow(
                title = "添加域名",
                onClick = { showAddAllow = true },
                trailing = {
                    Text("+", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 自定义拦截域名 ----
        item { SectionLabel("自定义拦截") }
        if (settings.customBlockDomains.isEmpty()) {
            item {
                ListRow(
                    title = "暂无条目",
                    subtitle = "手动添加要拦截的域名，与规则源同样生效（覆盖子域）",
                )
            }
        } else {
            items(settings.customBlockDomains.sorted(), key = { it }) { domain ->
                ListRow(
                    title = domain,
                    subtitle = when (customDomainEffect[domain]) {
                        true -> "已拦截该域名及其子域 · 已确认生效"
                        false -> "已拦截该域名及其子域 · 当前未生效"
                        null -> "已拦截该域名及其子域"
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(customDomainEffect[domain] == true)
                            Spacer(Modifier.width(12.dp))
                            TextAction(
                                text = "移除",
                                onClick = { viewModel.removeCustomBlockDomain(domain) },
                            )
                        }
                    },
                )
                Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            }
        }
        item {
            ListRow(
                title = "添加拦截域名",
                subtitle = customDomainMessage,
                onClick = { showAddCustomDomain = true },
                trailing = {
                    Text("+", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 按应用排除 ----
        item { SectionLabel("按应用排除") }
        item {
            ListRow(
                title = "已排除应用",
                subtitle = if (settings.excludedPackages.isEmpty()) {
                    "未排除，全部应用都会被拦截"
                } else {
                    "${settings.excludedPackages.size} 个应用不走代理"
                },
                onClick = { showAppList = true },
                trailing = { TextAction(text = "选择", onClick = { showAppList = true }) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 应用跳过订阅 ----
        item { SectionLabel("应用跳过订阅") }
        item {
            ListRow(
                title = "订阅地址",
                subtitle = settings.skipSubscriptionUrl.ifBlank { "未配置，仅使用内置规则" },
                onClick = { showSubscription = true },
                trailing = { TextAction(text = "编辑", onClick = { showSubscription = true }) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 后台保活 ----
        item { SectionLabel("后台保活") }
        item { KeepAliveSection() }

        // ---- 其他 ----
        item { SectionLabel("其他") }
        item {
            ToggleRow(
                title = "开机自动启动",
                subtitle = "设备重启后自动恢复拦截",
                checked = settings.bootStartEnabled,
                onCheckedChange = { viewModel.setBootStart(it) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }
        item {
            ToggleRow(
                title = "静默通知",
                subtitle = "系统要求前台服务必须有通知，无法隐藏；静默后不显示时间、不响铃",
                checked = settings.quietNotification,
                onCheckedChange = { viewModel.setQuietNotification(it) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        item { SectionLabel("帮助") }
        item {
            ListRow(
                title = "使用引导",
                subtitle = "四步设置：开启拦截 / 无障碍 / 规则 / 锁定后台",
                onClick = { viewModel.restartOnboarding() },
                trailing = { TextAction(text = "查看", onClick = { viewModel.restartOnboarding() }) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        item {
            Text(
                text = "SkipAd · 纯 Kotlin · 无需 Root",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = PageHorizontalPadding,
                    top = 32.dp,
                ),
            )
        }
    }

    // ---- 添加白名单 ----
    if (showAddAllow) {
        AlertDialog(
            onDismissRequest = { showAddAllow = false },
            title = { Text("添加白名单") },
            text = {
                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    label = { Text("域名") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newDomain.isNotBlank()) viewModel.addToAllowList(newDomain)
                    newDomain = ""
                    showAddAllow = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddAllow = false }) { Text("取消") }
            },
        )
    }

    // ---- 添加自定义拦截域名 ----
    if (showAddCustomDomain) {
        AlertDialog(
            onDismissRequest = { showAddCustomDomain = false },
            title = { Text("添加拦截域名") },
            text = {
                Column {
                    OutlinedTextField(
                        value = customDomainInput,
                        onValueChange = { customDomainInput = it },
                        label = { Text("域名") },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "添加后立即生效，会拦截该域名及其所有子域。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = customDomainInput
                    customDomainInput = ""
                    showAddCustomDomain = false
                    viewModel.addCustomBlockDomain(input) { customDomainMessage = it }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomDomain = false }) { Text("取消") }
            },
        )
    }

    // ---- 订阅地址 ----
    if (showSubscription) {
        AlertDialog(
            onDismissRequest = { showSubscription = false },
            title = { Text("规则订阅地址") },
            text = {
                Column {
                    OutlinedTextField(
                        value = subscriptionUrl,
                        onValueChange = { subscriptionUrl = it },
                        placeholder = { Text("https://…/gkd.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "支持 GKD / SKIP 格式，留空则仅用内置规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setSkipSubscriptionUrl(subscriptionUrl.trim())
                    showSubscription = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSubscription = false }) { Text("取消") }
            },
        )
    }

    // ---- 按应用排除 ----
    if (showAppList) {
        AppExclusionDialog(
            excluded = settings.excludedPackages,
            onDismiss = { showAppList = false },
            onConfirm = { viewModel.setExcludedPackages(it) },
        )
    }
}

/**
 * 可启动应用列表，用于按应用排除。
 */
@Composable
private fun AppExclusionDialog(
    excluded: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(excluded) }
    var query by remember { mutableStateOf("") }

    val apps = remember {
        try {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { app -> app.packageName to pm.getApplicationLabel(app).toString() }
                .sortedBy { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }

    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter {
            it.second.contains(query, ignoreCase = true) ||
                it.first.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按应用排除") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(filtered, key = { it.first }) { (packageName, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            CheckboxLike(
                                checked = selected.contains(packageName),
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + packageName
                                    else selected - packageName
                                },
                            )
                        }
                        Hairline()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
