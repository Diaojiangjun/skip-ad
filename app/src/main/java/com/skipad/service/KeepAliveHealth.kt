package com.skipad.service

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 保活能力自检与跳转。
 *
 * Android 没有「保活成功」的官方开关，实际能否长期存活取决于三件事：
 * 1. 应用是否被列入电池优化白名单（Doze 下不被冻结）
 * 2. 厂商 ROM 的自启动管理是否放行
 * 3. 用户是否把本应用从最近任务中划掉并禁止其后台运行
 *
 * 第 1 项可通过 API 查询；第 2、3 项**没有任何 API 可读**，
 * 只能引导用户手动确认 —— 这里如实反映这一点，不做虚假的「已开启」提示。
 */
object KeepAliveHealth {

    /**
     * 是否已豁免电池优化。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 通知权限是否已授予。
     *
     * 前台服务依赖常驻通知，通知被关闭会显著降低存活优先级。
     */
    fun hasNotificationPermission(context: Context): Boolean {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
            ?: return false
        return manager.areNotificationsEnabled()
    }

    /**
     * VPN 服务当前是否在运行。
     */
    fun isServiceActive(): Boolean = AdBlockVpnService.instance?.isActive() == true

    /**
     * 内存中是否有可用的无障碍服务进程（间接提升整体进程优先级）。
     */
    fun isAccessibilityActive(): Boolean = SkipAccessibilityService.instance != null

    /**
     * 申请电池优化豁免。
     *
     * 优先弹系统对话框（用户只需点一次「允许」）；
     * 若厂商 ROM 不支持该系统对话框，退回到电池优化列表页。
     */
    fun batteryOptimizationIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }.getOrElse {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * 打开系统电池优化列表（用于手动确认）。
     */
    fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * 尝试打开厂商的「自启动管理」页面。
     *
     * 各厂商的自启动页没有统一 API，只能按已知的组件名逐个尝试。
     * 全部失败时退回到应用详情页，由用户自行查找。
     *
     * @return 可用的 Intent
     */
    fun autoStartIntent(context: Context): Intent {
        val candidates = autoStartComponents()
        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
        }
        // 兜底：应用详情页
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 已知厂商的自启动管理组件。
     */
    private fun autoStartComponents(): List<ComponentName> = listOf(
        // 小米 / 红米
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        // 华为 / 荣耀
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        // OPPO / 一加 / realme
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
        ComponentName(
            "com.oplus.safecenter",
            "com.oplus.safecenter.startupapp.StartupAppListActivity",
        ),
        // vivo / iQOO
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
        // 魅族
        ComponentName(
            "com.meizu.safe",
            "com.meizu.safe.permission.SmartBGActivity",
        ),
        // 三星
        ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        // 联想 / 乐视
        ComponentName(
            "com.lenovo.security",
            "com.lenovo.security.purebackground.PureBackgroundActivity",
        ),
        ComponentName(
            "com.letv.android.letvsafe",
            "com.letv.android.letvsafe.AutobootManageActivity",
        ),
        // 华硕
        ComponentName(
            "com.asus.mobilemanager",
            "com.asus.mobilemanager.autostart.AutoStartActivity",
        ),
    )

    /**
     * 通知设置页。
     */
    fun notificationSettingsIntent(context: Context): Intent {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 应用详情页（用于关闭「强行停止」后的手动重新打开说明）。
     */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * 当前设备的厂商标识，用于给出针对性的引导文案。
     */
    fun manufacturerHint(appName: String): String {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val text = "$brand $manufacturer"
        return when {
            text.contains("xiaomi") || text.contains("redmi") ->
                "小米/红米：自启动管理 → 允许「$appName」自启动，并把省电策略设为「无限制」"
            text.contains("huawei") || text.contains("honor") ->
                "华为/荣耀：应用启动管理 → 关闭「$appName」的自动管理，手动允许自启动与后台活动"
            text.contains("oppo") || text.contains("oneplus") || text.contains("realme") ||
                text.contains("oplus") ->
                "OPPO/一加/realme：自启动管理 → 允许「$appName」自启动；耗电管理中设为「允许后台运行」"
            text.contains("vivo") || text.contains("iqoo") ->
                "vivo/iQOO：自启动 → 允许「$appName」；后台高耗电 → 允许"
            text.contains("meizu") ->
                "魅族：权限管理 → 后台管理 → 允许「$appName」后台运行"
            text.contains("samsung") ->
                "三星：电池 → 后台使用限制 → 把「$appName」加入「永不休眠的应用」"
            text.contains("asus") ->
                "华硕：自启动管理 → 允许「$appName」自启动"
            else ->
                "请在系统的「自启动」或「后台管理」中允许「$appName」，并关闭对其的省电限制"
        }
    }

    /**
     * 设备是否处于低内存状态（用于解释服务可能被回收）。
     */
    fun isLowRamDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        return activityManager.isLowRamDevice
    }

    /**
     * 最近一次进程退出的原因。
     *
     * Android 11+ 提供 `getHistoricalProcessExitReasons`，可读到系统给出的
     * 真实退出描述。国产 ROM 的一键清理通常表现为
     * `stop com.xxx due to o-stop(2)`，与「用户主动强行停止」不同，
     * 但对应用而言后果相同：进程被杀、`stopped=true`、广播与闹钟不再投递。
     */
    data class ExitInfo(
        val reasonCode: Int,
        val reasonText: String,
        val description: String,
        val timestamp: Long,
    )

    /**
     * 读取最近一次退出原因。数据不可用时返回 null。
     */
    fun lastExitInfo(context: Context): ExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        return try {
            val list = activityManager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                3,
            )
            val latest = list.firstOrNull() ?: return null
            ExitInfo(
                reasonCode = latest.reason,
                reasonText = reasonText(latest.reason),
                description = latest.description.orEmpty(),
                timestamp = latest.timestamp,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 退出原因码 → 中文描述。
     */
    private fun reasonText(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "正常退出"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "被信号终止"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被回收"
        android.app.ApplicationExitInfo.REASON_CRASH -> "应用崩溃"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "底层崩溃"
        android.app.ApplicationExitInfo.REASON_ANR -> "无响应被终止"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动停止"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "被用户停止"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程结束"
        android.app.ApplicationExitInfo.REASON_OTHER -> "被系统清理"
        else -> "未知原因（$reason）"
    }

    /**
     * 上次是否被系统清理（而非用户主动停止）。
     *
     * 用于在界面提示「上次后台被清理」，引导用户去锁定后台。
     */
    fun wasKilledBySystem(context: Context): Boolean {
        val info = lastExitInfo(context) ?: return false
        // 刚启动时的第一次查询会返回上次的退出记录
        return info.reasonCode == android.app.ApplicationExitInfo.REASON_OTHER ||
            info.reasonCode == android.app.ApplicationExitInfo.REASON_LOW_MEMORY ||
            info.reasonCode == android.app.ApplicationExitInfo.REASON_USER_STOPPED
    }

    /**
     * 打开「最近任务」界面，便于用户长按卡片锁定后台。
     *
     * 只能通过无障碍的全局动作打开 —— 系统没有公开的 recents Intent。
     */
    fun openRecents(context: Context): Boolean =
        SkipAccessibilityService.instance?.openRecents() == true

}
