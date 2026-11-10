package com.skipad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.skipad.R
import com.skipad.data.prefs.AppSettings
import com.skipad.ui.components.Hairline
import com.skipad.ui.screens.DashboardScreen
import com.skipad.ui.screens.FilterScreen
import com.skipad.ui.screens.LogScreen
import com.skipad.ui.screens.SettingsScreen
import com.skipad.ui.screens.SkipScreen

/**
 * 应用顶层导航。
 *
 * 四个标签 + 一个独立的设置页（从概览右上角进入）。
 * 底部栏只有一条发丝线，没有背景色、没有胶囊高亮。
 */
@Composable
fun SkipAdApp(
    viewModel: MainViewModel,
    settings: AppSettings,
    onToggleProtection: (Boolean) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(Tab.DASHBOARD) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        // 设置页是独立分支，必须自己套一层 Scaffold 才能获得系统栏内边距，
        // 否则内容会绘制到状态栏下方并被遮挡。
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .clipToBounds()
            ) {
                SettingsScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    settings = settings,
                    onBack = { showSettings = false },
                )
            }
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomBar(selected = selectedTab, onSelect = { selectedTab = it })
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        // 裁剪到内容区：否则列表滚动时会绘制到状态栏下方，与状态栏文字重叠
        Box(modifier = contentModifier.clipToBounds()) {
            when (selectedTab) {
                Tab.DASHBOARD -> DashboardScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    settings = settings,
                    onToggleProtection = onToggleProtection,
                    onOpenSettings = { showSettings = true },
                )

                Tab.LOG -> LogScreen(modifier = Modifier.fillMaxSize(), viewModel = viewModel)

                Tab.SKIP -> SkipScreen(modifier = Modifier.fillMaxSize(), viewModel = viewModel)

                Tab.FILTERS -> FilterScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    settings = settings,
                )
            }
        }
    }
}

/**
 * 极简底部导航：图标 + 小字，选中仅改变文字颜色。
 */
@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(58.dp)
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val tint = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(tab.drawableRes),
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                    )
                }
            }
        }
    }
}

/**
 * 底部导航的标签页。
 */
enum class Tab(
    val label: String,
    val drawableRes: Int,
) {
    DASHBOARD("概览", R.drawable.ic_nav_home),
    LOG("日志", R.drawable.ic_nav_log),
    SKIP("应用", R.drawable.ic_nav_skip),
    FILTERS("规则", R.drawable.ic_nav_rules),
}

/**
 * 返回图标（供设置页使用）。
 */
@Composable
fun BackLeading(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onBack)
            .padding(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}
