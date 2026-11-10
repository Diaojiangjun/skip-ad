package com.skipad.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationManagerCompat
import com.skipad.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 快捷设置磁贴：单击即可开关广告拦截。
 */
class AdBlockTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val repository = SettingsRepository(applicationContext)
            val current = repository.settings.first()
            val enabled = !current.protectionEnabled

            if (enabled) {
                // 磁贴无法弹系统授权框，需要用户先在应用内授权
                if (VpnService.prepare(applicationContext) == null) {
                    repository.setProtectionEnabled(true)
                    val intent = Intent(applicationContext, AdBlockVpnService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                } else {
                    // 尚未授权，打开应用让用户完成授权
                    val launchIntent = applicationContext.packageManager
                        .getLaunchIntentForPackage(applicationContext.packageName)
                    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launchIntent?.let { applicationContext.startActivity(it) }
                }
            } else {
                repository.setProtectionEnabled(false)
                applicationContext.stopService(
                    Intent(applicationContext, AdBlockVpnService::class.java)
                )
            }
            refreshTile()
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        scope.launch {
            val enabled = SettingsRepository(applicationContext).settings.first().protectionEnabled
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            // 与桌面图标同源，避免中文/英文系统下出现两个不同名字
            tile.label = getString(com.skipad.R.string.tile_label)
            tile.updateTile()
        }
    }
}
