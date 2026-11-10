package com.skipad.ui.screens

import com.skipad.R
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skipad.data.prefs.AppSettings
import com.skipad.service.KeepAliveHealth
import com.skipad.ui.MainViewModel
import com.skipad.ui.components.Hairline
import com.skipad.ui.components.PageHorizontalPadding
import com.skipad.ui.components.StatusDot
import com.skipad.ui.components.TextAction

/**
 * 首次使用引导。
 *
 * 只在新安装时出现一次，把「必须由用户完成」的四件事按顺序摆出来：
 * 授权 VPN、授权无障碍、下载规则、锁定后台。
 * 每一步都能直接跳到对应位置，不用用户自己找。
 */
@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    settings: AppSettings,
    onEnableProtection: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val sources by viewModel.skipSources.collectAsStateWithLifecycle()

    var accessibilityEnabled by remember { mutableStateOf(false) }
    var rulesReady by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    // 只统计「跳过」规则源，DNS 拦截规则的 27 万条与本页无关
    val skipRuleCount = sources
        .filter { it.enabled && it.source.id != "builtin" }
        .sumOf { it.ruleCount }

    LaunchedEffect(Unit) {
        viewModel.refreshSkipSources()
        while (true) {
            // 与「应用」页同一个判据：设置里开着但服务没连上，不算完成
            accessibilityEnabled = com.skipad.service.AccessibilityHealth
                .state(context) == com.skipad.service.AccessibilityState.RUNNING
            rulesReady = skipRuleCount > 0
            kotlinx.coroutines.delay(1500)
        }
    }

    val protectionOn = settings.protectionEnabled && dashboard.serviceRunning

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Spacer(Modifier.height(48.dp))
            Column(Modifier.padding(horizontal = PageHorizontalPadding)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "四步完成设置",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "全部处理都在本机完成，不经过任何服务器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 1. 开启拦截 ----
        item {
            StepRow(
                index = 1,
                title = "开启广告拦截",
                description = "授权后本机建立一个本地 DNS 过滤隧道，拦截广告与追踪域名",
                done = protectionOn,
                actionLabel = if (protectionOn) "已开启" else "开启",
                onAction = { if (!protectionOn) onEnableProtection() },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 2. 无障碍 ----
        item {
            StepRow(
                index = 2,
                title = "允许自动跳过开屏",
                description = "需要无障碍权限，用于识别并点击开屏广告的「跳过」按钮",
                done = accessibilityEnabled,
                actionLabel = if (accessibilityEnabled) "已授权" else "去授权",
                hint = if (!accessibilityEnabled && isAccessibilityServiceEnabled(context)) {
                    "系统里已开启但服务未连接，请在设置里关闭后重新打开"
                } else {
                    null
                },
                onAction = { openAccessibilitySettings(context) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 3. 下载规则 ----
        item {
            StepRow(
                index = 3,
                title = "下载跳过规则",
                description = if (rulesReady) {
                    "已加载 $skipRuleCount 条跳过规则"
                } else {
                    "从 GKD / SKIP 社区拉取规则，覆盖常见应用的开屏与弹窗"
                },
                done = rulesReady,
                actionLabel = when {
                    updating -> "更新中"
                    rulesReady -> "已就绪"
                    else -> "下载"
                },
                onAction = {
                    if (!updating) {
                        updating = true
                        viewModel.updateSkipSubscription { message ->
                            updateMessage = message
                            updating = false
                        }
                    }
                },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        // ---- 4. 锁定后台 ----
        item {
            StepRow(
                index = 4,
                title = "锁定后台",
                description = "在最近任务里长按本应用卡片选择「锁定」，避免被一键清理杀掉",
                done = null,
                actionLabel = "打开最近任务",
                onAction = { KeepAliveHealth.openRecents(context) },
            )
            Hairline(Modifier.padding(horizontal = PageHorizontalPadding))
        }

        updateMessage?.let { message ->
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

        // ---- 完成 ----
        item {
            Spacer(Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageHorizontalPadding)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onFinish() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "开始使用",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextAction(text = "稍后设置，先进去看看", onClick = onFinish)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "以上步骤随时可以在「应用」与「设置」页重新调整",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PageHorizontalPadding),
            )
        }
    }
}

/**
 * 单个引导步骤。
 *
 * @param done true 已完成、false 未完成、null 无法检测（如锁定后台）
 */
@Composable
private fun StepRow(
    index: Int,
    title: String,
    description: String,
    done: Boolean?,
    actionLabel: String,
    onAction: () -> Unit,
    /** 额外提示，用于说明「看起来完成但实际没生效」的情况。 */
    hint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding, vertical = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 序号圆点：完成后替换为状态点
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (done == true) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = if (done == true) {
                    MaterialTheme.colorScheme.background
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (done != null) {
                    Spacer(Modifier.width(8.dp))
                    StatusDot(done)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        TextAction(
            text = actionLabel,
            onClick = onAction,
            enabled = done != true,
            emphasized = done != true,
        )
    }
}
