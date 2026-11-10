package com.skipad.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 极简配色。
 *
 * 只用三种灰阶 + 一个强调色：
 * - 文字用近黑/近白，不依赖主题色
 * - 分隔线是唯一的结构元素
 * - 绿色仅用于「正在保护」这一个状态，其余一律中性
 */
object MinimalColors {
    val Background = Color(0xFFFBFBFB)
    val Surface = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF101010)
    val TextSecondary = Color(0xFF8A8A8E)
    val Hairline = Color(0xFFEDEDED)
    val Accent = Color(0xFF1B6C4C)

    val DarkBackground = Color(0xFF0B0B0C)
    val DarkSurface = Color(0xFF141416)
    val DarkTextPrimary = Color(0xFFF4F4F5)
    val DarkTextSecondary = Color(0xFF8A8A8E)
    val DarkHairline = Color(0xFF232326)
    val DarkAccent = Color(0xFF4ADE80)
}

private val LightColors = lightColorScheme(
    primary = MinimalColors.TextPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF2F2F2),
    onPrimaryContainer = MinimalColors.TextPrimary,
    secondary = MinimalColors.TextSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4F4F4),
    onSecondaryContainer = MinimalColors.TextPrimary,
    tertiary = MinimalColors.Accent,
    onTertiary = Color.White,
    error = Color(0xFFC0392B),
    background = MinimalColors.Background,
    onBackground = MinimalColors.TextPrimary,
    surface = MinimalColors.Background,
    onSurface = MinimalColors.TextPrimary,
    surfaceVariant = MinimalColors.Background,
    onSurfaceVariant = MinimalColors.TextSecondary,
    outline = MinimalColors.TextSecondary,
    outlineVariant = MinimalColors.Hairline,
)

private val DarkColors = darkColorScheme(
    primary = MinimalColors.DarkTextPrimary,
    onPrimary = Color(0xFF101010),
    primaryContainer = Color(0xFF1D1D20),
    onPrimaryContainer = MinimalColors.DarkTextPrimary,
    secondary = MinimalColors.DarkTextSecondary,
    onSecondary = Color(0xFF101010),
    secondaryContainer = Color(0xFF1D1D20),
    onSecondaryContainer = MinimalColors.DarkTextPrimary,
    tertiary = MinimalColors.DarkAccent,
    onTertiary = Color(0xFF04210F),
    error = Color(0xFFFF6B6B),
    background = MinimalColors.DarkBackground,
    onBackground = MinimalColors.DarkTextPrimary,
    surface = MinimalColors.DarkBackground,
    onSurface = MinimalColors.DarkTextPrimary,
    surfaceVariant = MinimalColors.DarkBackground,
    onSurfaceVariant = MinimalColors.DarkTextSecondary,
    outline = MinimalColors.DarkTextSecondary,
    outlineVariant = MinimalColors.DarkHairline,
)

/**
 * 排版：靠字号与字重建立层级，不用色块。
 */
private val MinimalTypography = Typography(
    // 大号数字 / 页面标题
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    // 列表主文案
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    // 列表次要文案
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    // 区域小标题
    labelMedium = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

/**
 * 应用主题。
 *
 * 刻意**关闭动态取色**：壁纸色会让同一界面的强调色随设备变化，
 * 极简设计需要稳定的灰阶与唯一的强调色。
 */
@Composable
fun SkipAdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MinimalTypography,
        content = content,
    )
}
