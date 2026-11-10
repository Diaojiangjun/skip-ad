package com.skipad.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skipad.core.skip.BuiltInSkipRules
import com.skipad.service.SkipAccessibilityService
import com.skipad.ui.MainViewModel
import com.skipad.ui.components.Hairline
import com.skipad.ui.components.ListRow
import com.skipad.ui.components.PageHeader
import com.skipad.ui.components.PageHorizontalPadding
import com.skipad.ui.components.SectionLabel
import com.skipad.ui.components.StatusDot
import com.skipad.ui.components.TextAction
import com.skipad.ui.components.ToggleRow
import com.skipad.ui.theme.formatCount

/**
 * 应用跳过页。
 */
@Composable
fun SkipScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val sources by viewModel.skipSources.collectAsStateWithLifecycle()

    // 无障碍服务有三种状态：未开启 / 已开启但服务没连上 / 正常运行。
    // 只看设置项会把「已开启但服务被系统标记为无法运行」误判成正常，
    // 用户以为在跳过广告，实际一次都没生效。
    var accessibilityState by remember {
        mutableStateOf(com.skipad.service.AccessibilityState.DISABLED)
    }
    var lastCrash by remember { mutableStateOf<String?>(null) }
    var repairing by remember { mutableStateOf(false) }

    // 由状态派生，不再单独维护布尔值，避免两处不一致
    val accessibilityEnabled =
        accessibilityState == com.skipad.service.AccessibilityState.RUNNING
    val accessibilityNotConnected =
        accessibilityState == com.skipad.service.AccessibilityState.ENABLED_NOT_CONNECTED
    var skippedCount by remember { mutableStateOf(0L) }
    var subscriptionMessage by remember { mutableStateOf<String?>(null) }
    var showCustomUrl by remember { mutableStateOf(false) }
    var customUrl by remember(settings.skipSubscriptionUrl) {
        mutableStateOf(settings.skipSubscriptionUrl)
    }

    LaunchedEffect(Unit) { viewModel.refreshSkipSources() }

    LaunchedEffect(Unit) {
        var first = true
        while (true) {
            // 首次判断给 1.5 秒绑定宽限，避免刚启动时误报「未连接」
            accessibilityState = if (first) {
                com.skipad.service.AccessibilityHealth.state(context, graceMs = 1500)
            } else {
                com.skipad.service.AccessibilityHealth.state(context)
            }
            first = false
            skippedCount = SkipAccessibilityService.instance?.skippedCount() ?: 0L
            if (accessibilityState != com.skipad.service.AccessibilityState.RUNNING) {
                lastCrash = com.skipad.service.SkipCrashLog.read(context)
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            PageHeader(
                title = "应用跳过",
                subtitle = when (accessibilityState) {
                    com.skipad.service.AccessibilityState.RUNNING ->
                        "已跳过 ${formatCount(skippedCount)} 次开屏广告"
                    com.skipad.service.AccessibilityState.ENABLED_NOT_CONNECTED ->
                        "无障碍服务未连接，跳过功能当前不生效"
                    else -> "需要无障碍权限"
                },
                trailing = {
                    TextAction(
                        text = "更新规则",
                        onClick = {
                            viewModel.updateSkipSubscription { subscriptionMessage = it }
                        },
                        emphasized = true,
                    )
                },
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 权限 ----
        item {
            val notConnected = accessibilityNotConnected
            ListRow(
                title = when {
                    notConnected -> "无障碍服务未连接"
                    accessibilityEnabled -> "无障碍服务"
                    else -> "开启无障碍服务"
                },
                subtitle = when {
                    notConnected ->
                        "应用退出后进程被回收，系统会把服务标记为异常且不再自动重连。" +
                            "点「修复」会先自动尝试；不行就到系统无障碍列表里找到「净广告」" +
                            "重新开启（不必先关，弹窗里选「保持开启状态」即可）"
                    accessibilityEnabled -> "已授权，可自动点击跳过按钮"
                    else -> "授权后才能识别并点击跳过按钮"
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(accessibilityEnabled)
                        Spacer(Modifier.width(12.dp))
                        TextAction(
                            text = when {
                                notConnected && repairing -> "修复中"
                                notConnected -> "修复"
                                accessibilityEnabled -> "管理"
                                else -> "去开启"
                            },
                            onClick = {
                                if (notConnected) {
                                    repairing = true
                                    viewModel.recoverAccessibility { recovered ->
                                        repairing = false
                                        if (!recovered) {
                                            // 自动恢复不了，只能请用户手动重新授权
                                            openAccessibilitySettings(context)
                                        }
                                    }
                                } else {
                                    openAccessibilitySettings(context)
                                }
                            },
                            enabled = !repairing,
                            emphasized = !accessibilityEnabled,
                        )
                    }
                },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 未连接时给出根治建议 ----
        if (accessibilityNotConnected) {
            item {
                ListRow(
                    title = "锁定后台可避免再次发生",
                    subtitle = "进程被回收是服务掉线的直接原因。在最近任务里长按本应用卡片并锁定它",
                    trailing = {
                        TextAction(
                            text = "去锁定",
                            onClick = {
                                com.skipad.service.KeepAliveHealth.openRecents(context)
                            },
                            emphasized = true,
                        )
                    },
                )
                Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            }
        }

        // ---- 未连接时的诊断信息 ----
        if (accessibilityNotConnected) {
            item {
                Text(
                    text = describeCrash(lastCrash)
                        ?: "服务没有运行。先点上方的「修复」尝试自动恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = PageHorizontalPadding,
                        end = PageHorizontalPadding,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                )
                Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
            }
        }

        // ---- 开关 ----
        item {
            ToggleRow(
                title = "启用应用跳过",
                checked = settings.skipFeatureEnabled,
                onCheckedChange = { viewModel.setSkipFeatureEnabled(it) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        item {
            ToggleRow(
                title = "仅对已配置应用生效",
                subtitle = "关闭后通用规则也生效，覆盖面更广",
                checked = settings.skipOnlyConfiguredApps,
                onCheckedChange = { viewModel.setSkipOnlyConfiguredApps(it) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 规则来源 ----
        item { SectionLabel("规则来源") }

        subscriptionMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = PageHorizontalPadding,
                        end = PageHorizontalPadding,
                        bottom = 6.dp,
                    ),
                )
            }
        }

        items(sources, key = { it.source.id }) { item ->
            ToggleRow(
                title = item.source.displayName,
                subtitle = buildString {
                    if (item.source.author.isNotBlank()) {
                        append("by ").append(item.source.author).append(" · ")
                    }
                    append(item.source.description)
                    if (item.source.requiresDownload) {
                        append("\n")
                        append(
                            when {
                                item.ruleCount > 0 ->
                                    "已缓存 ${formatCount(item.ruleCount.toLong())} 条"
                                !item.enabled -> "未启用"
                                else -> "尚未下载，点击右上角更新"
                            }
                        )
                        if (item.skipped > 0) {
                            append(" · 另有 ${formatCount(item.skipped.toLong())} 条结构型规则未支持")
                        }
                    }
                },
                checked = item.enabled,
                onCheckedChange = { viewModel.toggleSkipSource(item.source.id, it) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        item {
            ListRow(
                title = "自定义订阅地址",
                subtitle = settings.skipSubscriptionUrl.ifBlank {
                    "点击填写你自己的 GKD / SKIP 订阅链接"
                },
                onClick = { showCustomUrl = true },
                trailing = {
                    TextAction(
                        text = if (settings.skipSubscriptionUrl.isBlank()) "填写" else "编辑",
                        onClick = { showCustomUrl = true },
                        emphasized = settings.skipSubscriptionUrl.isBlank(),
                    )
                },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 已适配应用 ----
        item {
            SectionLabel("已专门适配 ${viewModel.adaptedAppNames().size} 个应用")
        }

        item {
            Text(
                text = viewModel.adaptedAppNames().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = PageHorizontalPadding,
                    end = PageHorizontalPadding,
                    bottom = 8.dp,
                ),
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }
    }

    if (showCustomUrl) {
        CustomUrlDialog(
            value = customUrl,
            onChange = { customUrl = it },
            hasValue = settings.skipSubscriptionUrl.isNotBlank(),
            onSave = {
                viewModel.setSkipSubscriptionUrl(customUrl.trim())
                showCustomUrl = false
                subscriptionMessage = "已保存订阅地址，点击右上角「更新规则」生效"
            },
            onClear = {
                viewModel.setSkipSubscriptionUrl("")
                customUrl = ""
                showCustomUrl = false
                subscriptionMessage = "已清除自定义订阅"
            },
            onDismiss = { showCustomUrl = false },
        )
    }
}

/**
 * 自定义订阅地址编辑弹窗。
 */
@Composable
private fun CustomUrlDialog(
    value: String,
    onChange: (String) -> Unit,
    hasValue: Boolean,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义订阅地址") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    placeholder = { Text("https://…/gkd.json5") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "支持 GKD 订阅（.json5）与 SKIP 配置（.json）。" +
                        "留空则只使用上面已启用的规则源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onSave) { Text("保存并更新") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = if (hasValue) onClear else onDismiss) {
                Text(if (hasValue) "清除" else "取消")
            }
        },
    )
}

/**
 * 把崩溃记录压缩成一行可读文案。
 */
private fun describeCrash(raw: String?): String? {
    if (raw == null) return null
    val lines = raw.lineSequence().toList()
    val scene = lines.firstOrNull { it.startsWith("场景：") }?.removePrefix("场景：")
    val type = lines.firstOrNull { it.startsWith("类型：") }?.removePrefix("类型：")
    val time = lines.firstOrNull { it.startsWith("时间：") }?.removePrefix("时间：")
    if (scene == null && type == null) return null
    return buildString {
        append("上次异常：")
        if (time != null) append(time).append(" · ")
        append(scene ?: "未知场景")
        if (type != null) append(" · ").append(type.substringAfterLast('.'))
    }
}

/**
 * 判断无障碍服务是否已开启。
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServices.split(':').any { entry ->
        val service = entry.trim()
        service.startsWith(context.packageName) &&
            service.contains(SkipAccessibilityService::class.java.name)
    }
}

/**
 * 打开系统无障碍设置页。
 */
fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
