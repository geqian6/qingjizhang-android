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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geqian6.qingjizhang.data.TransactionRecord
import com.geqian6.qingjizhang.ui.theme.AppColor
import com.geqian6.qingjizhang.util.BudgetStore
import com.geqian6.qingjizhang.util.Dates
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Stats

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    budgetStore: BudgetStore,
    onOpenBudget: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val monthRecords by viewModel.monthRecords.collectAsState()
    val allRecords by viewModel.allRecords.collectAsState()
    val pendingRecords by viewModel.pendingRecords.collectAsState()
    val monthAnchor by viewModel.monthAnchor.collectAsState()

    val income = Stats.totalIncome(monthRecords)
    val expense = Stats.totalExpense(monthRecords)
    val balance = income - expense

    val bars = Stats.dailyBars(allRecords, 7, Dates.currentTs())
    val weekExpense = bars.sumOf { it.expenseCents }

    val totalBudget = budgetStore.totalBudgetCents()
    val usedFraction = if (totalBudget > 0L) expense.toFloat() / totalBudget.toFloat() else 0f
    val remaining = (totalBudget - expense).coerceAtLeast(0L)
    val budgetWarning = usedFraction >= 0.8f

    val recent = monthRecords.take(2)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColor.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = Dates.monthLabel(monthAnchor) + "账单",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColor.textPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (pendingRecords.isEmpty()) AppColor.income else AppColor.warning)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (pendingRecords.isEmpty()) "已全部确认"
                        else "${pendingRecords.size} 笔待确认",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColor.textSecondary,
                    )
                }
            }
        }

        item {
            AppCard {
                Text(
                    text = "本月结余",
                    fontSize = 13.sp,
                    color = AppColor.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "¥" + Money.format(balance),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColor.textPrimary,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("收入", fontSize = 12.sp, color = AppColor.textSecondary)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "+" + Money.format(income),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColor.income,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("支出", fontSize = 12.sp, color = AppColor.textSecondary)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "-" + Money.format(expense),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColor.expense,
                        )
                    }
                }
            }
        }

        item {
            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "近 7 日支出",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColor.textPrimary,
                    )
                    Text(
                        text = "¥" + Money.format(weekExpense),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColor.textPrimary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                BarChart(bars = bars)
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColor.card)
                    .clickable { onOpenBudget() }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "本月预算",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(150.dp)) {
                    ProgressTrack(
                        fraction = usedFraction,
                        color = if (budgetWarning) AppColor.warning else AppColor.primary,
                        barHeight = 6.dp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "剩余 ¥" + Money.format(remaining),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (budgetWarning) AppColor.warningText else AppColor.textSecondary,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "›",
                    fontSize = 18.sp,
                    color = AppColor.textTertiary,
                )
            }
        }

        item {
            SectionHeaderRow(
                title = "最近流水",
                actionText = "查看全部",
                onAction = onOpenDetail,
                titleSize = 17,
            )
        }

        item {
            if (recent.isEmpty()) {
                EmptyCard("还没有记账，点下面的「记一笔」开始")
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(AppColor.card)
                ) {
                    recent.forEachIndexed { index, record ->
                        if (index > 0) AppDivider()
                        TransactionRow(record = record, onClick = onOpenDetail)
                    }
                    AppDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDetail() }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "查看全部流水",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColor.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppColor.card)
            .padding(vertical = 36.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            color = AppColor.textSecondary,
        )
    }
}

/** 一天的分组卡片：组头显示当日合计，下面跟当天的流水 */
@Composable
fun DayGroupCard(
    dayStart: Long,
    records: List<TransactionRecord>,
    onRecordClick: (TransactionRecord) -> Unit = {},
) {
    val dayExpense = records.filter { it.isExpense }.sumOf { it.amountCents }
    val dayIncome = records.filter { !it.isExpense }.sumOf { it.amountCents }
    val summary = buildString {
        if (dayExpense > 0L) append("支出 ¥").append(Money.format(dayExpense))
        if (dayIncome > 0L) {
            if (isNotEmpty()) append("  ·  ")
            append("收入 ¥").append(Money.format(dayIncome))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppColor.card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = Dates.dayWeekLabel(dayStart),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColor.textPrimary,
            )
            Text(
                text = summary,
                fontSize = 12.sp,
                color = AppColor.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        records.forEachIndexed { index, record ->
            if (index > 0) AppDivider()
            TransactionRow(record = record, onClick = { onRecordClick(record) })
        }
    }
}
