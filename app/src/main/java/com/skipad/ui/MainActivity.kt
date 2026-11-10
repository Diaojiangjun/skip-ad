package com.skipad.ui

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.skipad.service.AdBlockVpnService
import com.skipad.service.KeepAliveHealth
import com.skipad.service.KeepAliveScheduler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.draw.clipToBounds
import com.skipad.ui.screens.OnboardingScreen
import com.skipad.ui.theme.SkipAdTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** 等待系统 VPN 授权的回调。 */
    private var pendingVpnStart by mutableStateOf(false)

    /** 防止恢复流程并发执行。 */
    private var restoring = false

    private companion object {
        /** 是否已询问过电池优化豁免。 */
        const val KEY_BATTERY_PROMPTED = "battery_prompted"
    }

    /**
     * 通知权限请求。
     *
     * Android 13+ 默认不授予 POST_NOTIFICATIONS，前台服务的常驻通知会变成不可见。
     * 没有可见通知的前台服务在多数 ROM 上更容易被清理，因此开启保护时主动申请。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不阻塞功能，仅在自检面板中提示 */ }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startProtection()
        } else {
            pendingVpnStart = false
            viewModel.setProtectionEnabled(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 服务的恢复统一放在 onResume 中处理（onCreate 之后必然触发 onResume），
        // 避免两处同时触发 startProtection 造成并发建立隧道。
        setContent {
            SkipAdTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val settings by viewModel.settings.collectAsStateWithLifecycle()
                    // 设置尚未从磁盘读出时不要做任何分支判断：
                    // settings 的首帧是默认实例（onboardingDone=false），据此渲染会
                    // 在每次冷启动/重进时闪一下引导页，然后又被真实值覆盖掉。
                    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()

                    LaunchedEffect(pendingVpnStart) {
                        if (pendingVpnStart) {
                            requestVpnPermission()
                        }
                    }

                    if (!settingsLoaded) {
                        // 停留一帧左右，背景色与主题一致，视觉上等同于直接进入主界面
                        Box(Modifier.fillMaxSize())
                    } else if (!settings.onboardingDone) {
                        // 首次安装：先走一遍引导，把必须由用户完成的授权串起来
                        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .clipToBounds()
                            ) {
                                OnboardingScreen(
                                    viewModel = viewModel,
                                    settings = settings,
                                    onEnableProtection = {
                                        pendingVpnStart = true
                                        viewModel.setProtectionEnabled(true)
                                    },
                                    onFinish = { viewModel.setOnboardingDone() },
                                )
                            }
                        }
                    } else {
                        SkipAdApp(
                            viewModel = viewModel,
                            settings = settings,
                            onToggleProtection = { enabled ->
                                if (enabled) {
                                    pendingVpnStart = true
                                    viewModel.setProtectionEnabled(true)
                                } else {
                                    stopProtection()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * 请求系统 VPN 授权。
     */
    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // 已经授权过，直接启动
            startProtection()
        }
    }

    /**
     * 启动拦截服务。
     */
    private fun startProtection() {
        pendingVpnStart = false
        val intent = Intent(this, AdBlockVpnService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Android 12+ 在后台可能拒绝启动前台服务（ForegroundServiceStartNotAllowedException）
            android.util.Log.w("SkipAdMain", "启动前台服务被拒绝", e)
        }
        ensureNotificationPermission()
        // 开启保护后启动周期性自检，服务被杀时能自行恢复
        KeepAliveScheduler.scheduleWatchdog(this)
        maybeRequestBatteryExemption()
        viewModel.refreshStats()
    }

    /**
     * 申请通知权限（Android 13+）。
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * 首次开启保护时申请电池优化豁免。
     *
     * 这是保活最有效的一步：未豁免时系统会在 Doze 下冻结应用，
     * 前台服务也会被降级清理。只询问一次，避免反复打扰。
     */
    private fun maybeRequestBatteryExemption() {
        if (KeepAliveHealth.isIgnoringBatteryOptimizations(this)) return

        val prefs = getSharedPreferences("skipad_keepalive", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_PROMPTED, false)) return
        prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()

        runCatching {
            startActivity(KeepAliveHealth.batteryOptimizationIntent(this))
        }
    }

    /**
     * 停止拦截服务。
     */
    private fun stopProtection() {
        pendingVpnStart = false
        // 用户主动关闭：取消自检，否则会被自愈机制重新拉起
        KeepAliveScheduler.cancelWatchdog(this)
        stopService(Intent(this, AdBlockVpnService::class.java))
        viewModel.setProtectionEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStats()
        restoreProtectionIfNeeded()
    }

    /**
     * 若设置中已开启保护但服务未运行，则尝试恢复。
     *
     * 只在 VPN 已授权的前提下直接启动；未授权时保持关闭状态，
     * 由用户主动点击开关触发授权流程。
     */
    private fun restoreProtectionIfNeeded() {
        if (AdBlockVpnService.instance?.isActive() == true) return
        if (pendingVpnStart) return
        if (restoring) return
        restoring = true

        lifecycleScope.launch {
            try {
                val enabled = viewModel.isProtectionEnabled()
                if (!enabled) return@launch
                if (AdBlockVpnService.instance?.isActive() == true) return@launch

                // 已授权（prepare 返回 null）才静默恢复，避免反复弹窗打扰
                if (VpnService.prepare(this@MainActivity) == null) {
                    startProtection()
                }
            } finally {
                restoring = false
            }
        }
    }
}
