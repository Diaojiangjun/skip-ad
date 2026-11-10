package com.skipad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skipad.ui.theme.MinimalColors

/** 水平内边距，全应用统一。 */
val PageHorizontalPadding = 20.dp

/**
 * 发丝分隔线。
 *
 * 极简布局里只用这一种结构元素区分内容，不用卡片。
 */
@Composable
fun Hairline(modifier: Modifier = Modifier, startIndent: Int = 0) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 区域小标题。
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = PageHorizontalPadding,
            end = PageHorizontalPadding,
            top = 28.dp,
            bottom = 10.dp,
        ),
    )
}

/**
 * 页面标题区。
 *
 * 用大号标题本身建立层级，不画分隔线、不加背景色。
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (leading != null) 8.dp else PageHorizontalPadding,
                end = PageHorizontalPadding,
                top = 12.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 通用列表行。
 */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PageHorizontalPadding, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(14.dp))
            trailing()
        }
    }
}

/**
 * 带开关的列表行。
 */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = PageHorizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
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

/**
 * 单选行。
 *
 * 用右侧的小圆点表示选中，替代笨重的 RadioButton。
 */
@Composable
fun ChoiceRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PageHorizontalPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .size(if (selected) 10.dp else 8.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                ),
        )
    }
}

/**
 * 纯文字操作。
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            emphasized -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
    )
}

/**
 * 状态圆点。
 *
 * @param active true 绿色、false 灰、null 用中性色（表示无法检测）
 */
@Composable
fun StatusDot(active: Boolean?, modifier: Modifier = Modifier) {
    val color = when (active) {
        true -> MaterialTheme.colorScheme.tertiary
        false -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        null -> MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    }
    Box(
        modifier = modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 轻量勾选控件。
 *
 * 用圆点代替 Checkbox，减少视觉重量。
 */
@Composable
fun CheckboxLike(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (checked) 12.dp else 9.dp)
                .clip(CircleShape)
                .background(
                    if (checked) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                )
        )
    }
}

/**
 * 大号统计数字。
 */
@Composable
fun StatBlock(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 空状态提示。
 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding, vertical = 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 强调色值，供需要直接取色的地方使用。
 */
object AccentPalette {
    val Active: Color @Composable get() = MaterialTheme.colorScheme.tertiary
    val Muted: Color @Composable get() = MinimalColors.TextSecondary
}
