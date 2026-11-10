package com.skipad.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.skipad.service.KeepAliveHealth

/**
 * 后台保活自检。
 *
 * 每项只显示一个圆点、一个名称、一句状态。没有卡片，没有图标。
 */
@Composable
fun KeepAliveSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var serviceActive by remember { mutableStateOf(KeepAliveHealth.isServiceActive()) }
    var batteryExempt by remember {
        mutableStateOf(KeepAliveHealth.isIgnoringBatteryOptimizations(context))
    }
    var notificationsEnabled by remember {
        mutableStateOf(KeepAliveHealth.hasNotificationPermission(context))
    }
    // 只在进入页面时读一次：启动后首次查询返回的是「上一次」的退出记录
    val lastExit = remember { KeepAliveHealth.lastExitInfo(context) }

    LaunchedEffect(Unit) {
        while (true) {
            serviceActive = KeepAliveHealth.isServiceActive()
            batteryExempt = KeepAliveHealth.isIgnoringBatteryOptimizations(context)
            notificationsEnabled = KeepAliveHealth.hasNotificationPermission(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    Column(modifier.fillMaxWidth()) {
        HealthRow(
            label = "前台服务",
            status = if (serviceActive) "运行中" else "已停止",
            ok = serviceActive,
        )
        HealthRow(
            label = "电池优化",
            status = if (batteryExempt) "已豁免" else "未豁免",
            ok = batteryExempt,
            action = if (batteryExempt) null else "去设置",
            onAction = {
                launchSafely(context, KeepAliveHealth.batteryOptimizationIntent(context))
            },
        )
        HealthRow(
            label = "通知权限",
            status = if (notificationsEnabled) "已开启" else "已关闭",
            ok = notificationsEnabled,
            action = if (notificationsEnabled) null else "去开启",
            onAction = {
                launchSafely(context, KeepAliveHealth.notificationSettingsIntent(context))
            },
        )
        HealthRow(
            label = "自启动管理",
            status = "系统无法检测，请手动确认",
            ok = null,
            action = "去设置",
            onAction = { launchSafely(context, KeepAliveHealth.autoStartIntent(context)) },
        )
        HealthRow(
            label = "锁定后台",
            status = "在最近任务中长按本应用卡片并选择「锁定」",
            ok = null,
            action = "打开最近任务",
            onAction = { KeepAliveHealth.openRecents(context) },
        )
        lastExit?.let { info ->
            HealthRow(
                label = "上次退出",
                status = buildString {
                    append(info.reasonText)
                    if (info.description.isNotBlank()) {
                        append(" · ").append(info.description.take(48))
                    }
                },
                ok = null,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = KeepAliveHealth.manufacturerHint(
                androidx.compose.ui.res.stringResource(com.skipad.R.string.app_name)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = PageHorizontalPadding),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "多数国产系统（如 ColorOS / MIUI）的「一键清理」等同于强制停止：进程被杀死、" +
                "无障碍服务被关闭，且系统不再投递广播与闹钟 —— 此时任何应用都无法自行重启，" +
                "只能手动再次打开。把它在最近任务中「锁定」，清理时就会跳过本应用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = PageHorizontalPadding),
        )
    }
}

/**
 * 单项状态行。
 *
 * @param ok true 达标、false 未达标、null 无法检测
 */
@Composable
private fun HealthRow(
    label: String,
    status: String,
    ok: Boolean?,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(ok)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.width(10.dp))
            TextAction(text = action, onClick = onAction)
        }
    }
}

/**
 * 安全启动 Activity：部分厂商 ROM 会移除目标页面。
 */
private fun launchSafely(context: Context, intent: Intent) {
    val safe = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(safe) }
        .onFailure {
            runCatching {
                context.startActivity(KeepAliveHealth.appDetailsIntent(context))
            }
        }
}
