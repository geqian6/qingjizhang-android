package com.geqian6.qingjizhang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geqian6.qingjizhang.data.TransactionRecord
import com.geqian6.qingjizhang.ui.theme.AppColor
import com.geqian6.qingjizhang.util.BarItem
import com.geqian6.qingjizhang.util.Category
import com.geqian6.qingjizhang.util.CategorySlice
import com.geqian6.qingjizhang.util.Dates
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Source

// ---------------------------------------------------------------------------
// 颜色映射
// ---------------------------------------------------------------------------

fun sourceColor(key: String): Color = when (key) {
    Source.WECHAT -> AppColor.wechat
    Source.ALIPAY -> AppColor.alipay
    Source.BANK -> AppColor.bank
    Source.CASH -> AppColor.cash
    else -> AppColor.manual
}

fun categoryColor(key: String): Color = when (key) {
    Category.DINING -> Color(0xFF0066CC)
    Category.TRANSIT -> Color(0xFF1E9E5A)
    Category.SHOPPING -> Color(0xFFE08000)
    Category.ENTERTAINMENT -> Color(0xFF6B4EE6)
    Category.MEDICAL -> Color(0xFFE5484D)
    Category.HOUSING -> Color(0xFF0E96A8)
    Category.DAILY -> Color(0xFFC79A00)
    Category.SALARY -> Color(0xFF0066CC)
    Category.PART_TIME -> Color(0xFF1E9E5A)
    Category.REFUND -> Color(0xFF4C93E0)
    else -> Color(0xFF8E8E93)
}

// ---------------------------------------------------------------------------
// 容器
// ---------------------------------------------------------------------------

/** 标准白卡片 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppColor.card)
            .padding(padding),
        content = content,
    )
}

/** 卡片内分隔线 */
@Composable
fun AppDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppColor.divider)
    )
}

/** 页面大标题行 */
@Composable
fun ScreenHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = AppColor.textPrimary,
        )
        trailing?.invoke()
    }
}

/** 小标题 + 右侧动作 */
@Composable
fun SectionHeaderRow(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    titleSize: Int = 15,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            fontSize = titleSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColor.textPrimary,
        )
        if (actionText != null) {
            Text(
                text = actionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AppColor.primary,
                modifier = if (onAction != null) Modifier.clickable { onAction() } else Modifier,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 基础控件
// ---------------------------------------------------------------------------

@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(51.dp)
            .height(31.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) AppColor.income else AppColor.textTertiary)
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(27.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/** 进度条。fraction 自动夹在 2%~100%，避免 0 宽不可见 */
@Composable
fun ProgressTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(RoundedCornerShape(999.dp))
            .background(AppColor.track)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
    }
}

/** 药丸形标签，用于筛选 / 选择 */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedColor: Color = AppColor.textPrimary,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) selectedColor else AppColor.card)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, Color(0xFFE0E0E0), shape)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else AppColor.textPrimary,
        )
    }
}

/** 分段切换控件 */
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColor.switchTrack)
            .padding(2.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) AppColor.card else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = AppColor.textPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 流水
// ---------------------------------------------------------------------------

/** 分类徽章：彩色圆 + 分类首字。省掉一整套图标资源，语义反而更清楚 */
@Composable
fun CategoryBadge(key: String, badgeSize: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(badgeSize)
            .clip(CircleShape)
            .background(categoryColor(key)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = Category.label(key).take(1),
            color = Color.White,
            fontSize = (badgeSize.value * 0.4f).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 一条流水 */
@Composable
fun TransactionRow(
    record: TransactionRecord,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(record.category)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.merchant.ifBlank { Category.label(record.category) },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppColor.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Source.fullLabel(record.source),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = sourceColor(record.source),
                )
                Text(
                    text = " · ${Dates.timeLabel(record.occurredAt)}",
                    fontSize = 12.sp,
                    color = AppColor.textSecondary,
                )
                if (record.status == TransactionRecord.STATUS_PENDING) {
                    Text(
                        text = " · 待确认",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColor.warning,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = Money.formatSigned(record.amountCents, record.isExpense),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (record.isExpense) AppColor.expense else AppColor.income,
        )
    }
}

// ---------------------------------------------------------------------------
// 图表
// ---------------------------------------------------------------------------

@Composable
private fun Bar(fraction: Float, color: Color, maxHeight: Dp, barWidth: Dp = 10.dp) {
    Box(
        Modifier
            .width(barWidth)
            .height(maxHeight * fraction.coerceIn(0.03f, 1f))
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
}

/**
 * 柱状图。showIncome = true 时每根柱子是「收入 + 支出」双柱。
 */
@Composable
fun BarChart(
    bars: List<BarItem>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 124.dp,
    showIncome: Boolean = false,
) {
    val maxValue = bars.maxOfOrNull {
        maxOf(if (showIncome) it.incomeCents else 0L, it.expenseCents)
    } ?: 0L

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight + 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { bar ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Row(
                    modifier = Modifier.height(chartHeight),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (showIncome) {
                        Bar(
                            fraction = if (maxValue > 0L) bar.incomeCents.toFloat() / maxValue.toFloat() else 0f,
                            color = AppColor.income,
                            maxHeight = chartHeight,
                        )
                    }
                    Bar(
                        fraction = if (maxValue > 0L) bar.expenseCents.toFloat() / maxValue.toFloat() else 0f,
                        color = if (bar.highlight) AppColor.primary else AppColor.primaryLight,
                        maxHeight = chartHeight,
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = bar.label,
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = if (bar.highlight) AppColor.primary else AppColor.textSecondary,
                )
            }
        }
    }
}

/**
 * 环形图。传进来的 slice 顺序决定绘制顺序，颜色从 palette 依次取。
 */
@Composable
fun DonutChart(
    slices: List<CategorySlice>,
    palette: List<Color>,
    modifier: Modifier = Modifier,
    chartSize: Dp = 108.dp,
    strokeWidth: Dp = 16.dp,
) {
    Canvas(modifier = modifier.size(chartSize)) {
        if (slices.isEmpty()) return@Canvas
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            val sweep = slice.fraction * 360f
            drawArc(
                color = palette[index % palette.size],
                startAngle = startAngle,
                sweepAngle = (sweep - 3f).coerceAtLeast(0.5f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            startAngle += sweep
        }
    }
}

/** 环形图的默认配色，前三个与设计稿一致 */
val DonutPalette: List<Color> = listOf(
    AppColor.primary,
    AppColor.primaryLight,
    AppColor.income,
    AppColor.warning,
    Color(0xFF6B4EE6),
    Color(0xFF0E96A8),
)

// ---------------------------------------------------------------------------
// 底部导航
// ---------------------------------------------------------------------------

enum class AppTab(val label: String) {
    HOME("首页"),
    DETAIL("明细"),
    STATS("统计"),
    MINE("我的"),
}

@Composable
private fun TabIcon(tab: AppTab, tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        when (tab) {
            AppTab.HOME -> {
                drawLine(tint, Offset(w * 0.12f, h * 0.46f), Offset(w * 0.5f, h * 0.16f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.88f, h * 0.46f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.2f, h * 0.42f), Offset(w * 0.2f, h * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.8f, h * 0.42f), Offset(w * 0.8f, h * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.2f, h * 0.86f), Offset(w * 0.8f, h * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            AppTab.DETAIL -> {
                for (i in 0..2) {
                    val y = h * (0.26f + i * 0.26f)
                    drawCircle(tint, radius = sw * 1.1f, center = Offset(w * 0.2f, y))
                    drawLine(tint, Offset(w * 0.38f, y), Offset(w * 0.86f, y), strokeWidth = sw, cap = StrokeCap.Round)
                }
            }
            AppTab.STATS -> {
                drawRoundRect(tint, topLeft = Offset(w * 0.14f, h * 0.5f), size = Size(w * 0.18f, h * 0.36f), cornerRadius = CornerRadius(sw))
                drawRoundRect(tint, topLeft = Offset(w * 0.41f, h * 0.3f), size = Size(w * 0.18f, h * 0.56f), cornerRadius = CornerRadius(sw))
                drawRoundRect(tint, topLeft = Offset(w * 0.68f, h * 0.14f), size = Size(w * 0.18f, h * 0.72f), cornerRadius = CornerRadius(sw))
            }
            AppTab.MINE -> {
                drawCircle(tint, radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.32f), style = Stroke(width = sw))
                drawArc(
                    color = tint,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(w * 0.2f, h * 0.52f),
                    size = Size(w * 0.6f, h * 0.6f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
fun BottomTabBar(
    current: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 21.dp, end = 21.dp, top = 10.dp, bottom = 21.dp)
            .height(62.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(AppColor.card)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(36.dp))
            .padding(4.dp)
    ) {
        AppTab.entries.forEach { tab ->
            val selected = tab == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (selected) AppColor.primary else Color.Transparent)
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TabIcon(tab, if (selected) Color.White else AppColor.textSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tab.label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) Color.White else AppColor.textSecondary,
                )
            }
        }
    }
}
