package com.geqian6.qingjizhang.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geqian6.qingjizhang.ui.theme.AppColor
import com.geqian6.qingjizhang.util.Category
import com.geqian6.qingjizhang.util.Dates
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Stats

@Composable
fun StatsScreen(
    viewModel: AppViewModel,
    onOpenBudget: () -> Unit,
) {
    val allRecords by viewModel.allRecords.collectAsState()
    val monthAnchor by viewModel.monthAnchor.collectAsState()
    var periodIndex by remember { mutableIntStateOf(2) } // 0 日 / 1 周 / 2 月

    val now = Dates.currentTs()

    val ranges = remember(monthAnchor, periodIndex) {
        when (periodIndex) {
            0 -> listOf(
                Dates.startOfDay(now) to Dates.addDays(Dates.startOfDay(now), 1),
                Dates.addDays(Dates.startOfDay(now), -1) to Dates.startOfDay(now),
            )
            1 -> listOf(
                Dates.startOfWeek(now) to Dates.endOfWeek(now),
                Dates.addDays(Dates.startOfWeek(now), -7) to Dates.startOfWeek(now),
            )
            else -> listOf(
                Dates.startOfMonth(monthAnchor) to Dates.addMonths(Dates.startOfMonth(monthAnchor), 1),
                Dates.addMonths(Dates.startOfMonth(monthAnchor), -1) to Dates.startOfMonth(monthAnchor),
            )
        }
    }

    val (curIncome, curExpense) = Stats.sumIn(allRecords, ranges[0].first, ranges[0].second)
    val (_, prevExpense) = Stats.sumIn(allRecords, ranges[1].first, ranges[1].second)

    val periodRecords = remember(allRecords, ranges) {
        allRecords.filter { it.occurredAt >= ranges[0].first && it.occurredAt < ranges[0].second }
    }

    val bars = remember(allRecords, monthAnchor) {
        Stats.monthlyBars(allRecords, 6, monthAnchor)
    }

    val slices = remember(periodRecords) { Stats.byCategory(periodRecords, isExpense = true) }

    val expenseChange = Stats.changeRatio(curExpense, prevExpense)
    val periodName = listOf("今天", "本周", "本月")[periodIndex]
    val prevName = listOf("昨天", "上周", "上月")[periodIndex]

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColor.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(title = "统计分析") }

        item {
            Segmented(
                options = listOf("日", "周", "月"),
                selectedIndex = periodIndex,
                onSelect = { periodIndex = it },
            )
        }

        item {
            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "收入 / 支出对比",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColor.textPrimary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(AppColor.income, "收入")
                        Spacer(Modifier.width(12.dp))
                        LegendDot(AppColor.expense, "支出")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AmountBlock("本期收入", curIncome, AppColor.income)
                    AmountBlock("本期支出", curExpense, AppColor.expense, alignEnd = true)
                }
                Spacer(Modifier.height(14.dp))
                BarChart(
                    bars = bars,
                    showIncome = true,
                    chartHeight = 118.dp,
                )
            }
        }

        item {
            val less = (prevExpense - curExpense).coerceAtLeast(0L)
            val more = (curExpense - prevExpense).coerceAtLeast(0L)
            val text = when {
                expenseChange == null ->
                    "$periodName支出 ¥${Money.format(curExpense)}，$prevName无记录，暂无法对比"
                expenseChange <= 0f ->
                    "$periodName支出比$prevName少 ¥${Money.format(less)}，下降 ${
                        String.format("%.1f", -expenseChange * 100)
                    }%"
                else ->
                    "$periodName支出比$prevName多 ¥${Money.format(more)}，上升 ${
                        String.format("%.1f", expenseChange * 100)
                    }%"
            }
            val positive = expenseChange != null && expenseChange <= 0f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (positive) AppColor.primarySoft else AppColor.warningBg)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (positive) AppColor.primary else AppColor.warning)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.textPrimary,
                )
            }
        }

        item {
            AppCard {
                Text(
                    text = "支出分类占比",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColor.textPrimary,
                )
                Spacer(Modifier.height(14.dp))
                if (slices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$periodName还没有支出记录",
                            fontSize = 13.sp,
                            color = AppColor.textSecondary,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DonutChart(slices = slices, palette = DonutPalette)
                        Spacer(Modifier.width(20.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            slices.take(5).forEachIndexed { index, slice ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(DonutPalette[index % DonutPalette.size])
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            text = slice.label,
                                            fontSize = 13.sp,
                                            color = AppColor.textPrimary,
                                        )
                                    }
                                    Text(
                                        text = "${(slice.fraction * 100).toInt()}%",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColor.textPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (slices.isNotEmpty()) {
            item {
                AppCard {
                    Text(
                        text = "分类金额",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColor.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    slices.forEach { slice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryBadge(slice.key, badgeSize = 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = Category.label(slice.key),
                                    fontSize = 14.sp,
                                    color = AppColor.textPrimary,
                                )
                            }
                            Text(
                                text = "-¥" + Money.format(slice.amountCents),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppColor.expense,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColor.card)
                    .clickable { onOpenBudget() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "去看本月预算",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.textPrimary,
                )
                Text("›", fontSize = 18.sp, color = AppColor.textTertiary)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, fontSize = 11.sp, color = AppColor.textSecondary)
    }
}

@Composable
private fun AmountBlock(
    label: String,
    amountCents: Long,
    color: Color,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(text = label, fontSize = 12.sp, color = AppColor.textSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "¥" + Money.format(amountCents),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
