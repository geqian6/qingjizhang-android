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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geqian6.qingjizhang.ui.theme.AppColor
import com.geqian6.qingjizhang.util.BudgetStore
import com.geqian6.qingjizhang.util.Category
import com.geqian6.qingjizhang.util.Dates
import com.geqian6.qingjizhang.util.Money

@Composable
fun BudgetScreen(
    viewModel: AppViewModel,
    budgetStore: BudgetStore,
    onBack: () -> Unit,
) {
    val monthRecords by viewModel.monthRecords.collectAsState()

    var totalBudget by remember { mutableStateOf(budgetStore.totalBudgetCents()) }
    var categoryBudgets by remember {
        mutableStateOf(
            Category.expenseKeys.associateWith { budgetStore.categoryBudgetCents(it) }
        )
    }
    var alertAt80 by remember { mutableStateOf(budgetStore.alertAt80()) }
    var alertOnOver by remember { mutableStateOf(budgetStore.alertOnOver()) }

    var showTotalDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<String?>(null) }

    val spentByCategory = remember(monthRecords) {
        monthRecords
            .filter { it.isExpense }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amountCents } }
    }
    val totalSpent = spentByCategory.values.sum()

    val usedFraction = if (totalBudget > 0L) totalSpent.toFloat() / totalBudget.toFloat() else 0f
    val remaining = (totalBudget - totalSpent).coerceAtLeast(0L)
    val now = Dates.currentTs()
    val daysLeft = (Dates.daysInMonth(now) - Dates.dayOfMonth(now) + 1).coerceAtLeast(1)
    val dailyAvailable = remaining / daysLeft
    val overBudget = totalSpent > totalBudget

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
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹",
                    fontSize = 24.sp,
                    color = AppColor.textPrimary,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(end = 10.dp),
                )
                Text(
                    text = "预算",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColor.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "编辑预算",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.primary,
                    modifier = Modifier.clickable { showTotalDialog = true },
                )
            }
        }

        // 总预算
        item {
            AppCard {
                Text(
                    text = Dates.monthLabel(now) + "总预算",
                    fontSize = 13.sp,
                    color = AppColor.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "¥" + Money.format(totalSpent),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColor.textPrimary,
                    )
                    Text(
                        text = " / ¥" + Money.format(totalBudget),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColor.textSecondary,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                ProgressTrack(
                    fraction = usedFraction,
                    color = when {
                        overBudget -> AppColor.expense
                        usedFraction >= 0.8f -> AppColor.warning
                        else -> AppColor.primary
                    },
                    barHeight = 10.dp,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已用 ${(usedFraction * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = AppColor.textSecondary,
                    )
                    Text(
                        text = if (overBudget) "已超 ¥" + Money.format(totalSpent - totalBudget)
                        else "剩余 ¥" + Money.format(remaining) + " · 还有 $daysLeft 天",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (overBudget) AppColor.expense else AppColor.warningText,
                    )
                }
            }
        }

        // 预警条
        if (usedFraction >= 0.8f) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppColor.warningBg)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AppColor.warning)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (overBudget) "预算已经超了" else "预算即将用尽",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColor.warningTextDeep,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (overBudget)
                                "已经超出 ¥${Money.format(totalSpent - totalBudget)}，后面花钱留意点"
                            else
                                "本月还剩 $daysLeft 天，日均可用 ¥${Money.format(dailyAvailable)}",
                            fontSize = 12.sp,
                            color = AppColor.warningText,
                        )
                    }
                }
            }
        }

        // 分类预算
        item {
            AppCard(padding = 18.dp) {
                SectionHeaderRow(
                    title = "分类预算",
                    actionText = "调整",
                    onAction = { showTotalDialog = true },
                )
                Spacer(Modifier.height(14.dp))
                Category.expenseKeys.forEachIndexed { index, key ->
                    if (index > 0) Spacer(Modifier.height(16.dp))
                    val budget = categoryBudgets[key] ?: 0L
                    val spent = spentByCategory[key] ?: 0L
                    val fraction = if (budget > 0L) spent.toFloat() / budget.toFloat() else 0f
                    val over = spent > budget && budget > 0L
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = Category.label(key),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (over) AppColor.expense else AppColor.textPrimary,
                                modifier = Modifier.clickable { editingCategory = key },
                            )
                            Text(
                                text = if (over)
                                    "已超 ¥${Money.format(spent - budget)}（¥${Money.format(spent)} / ¥${Money.format(budget)}）"
                                else
                                    "¥${Money.format(spent)} / ¥${Money.format(budget)}",
                                fontSize = 12.sp,
                                fontWeight = if (over) FontWeight.Medium else FontWeight.Normal,
                                color = when {
                                    over -> AppColor.expense
                                    fraction >= 0.8f -> AppColor.warningText
                                    else -> AppColor.textSecondary
                                },
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        ProgressTrack(
                            fraction = fraction,
                            color = when {
                                over -> AppColor.expense
                                fraction >= 0.8f -> AppColor.warning
                                else -> AppColor.primary
                            },
                        )
                    }
                }
            }
        }

        // 超支提醒
        item {
            AppCard(padding = 18.dp) {
                Text(
                    text = "超支提醒",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColor.textPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "达到 80% 时提醒我",
                        fontSize = 14.sp,
                        color = AppColor.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = alertAt80,
                        onCheckedChange = {
                            alertAt80 = it
                            budgetStore.setAlertAt80(it)
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "超出预算时提醒我",
                        fontSize = 14.sp,
                        color = AppColor.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = alertOnOver,
                        onCheckedChange = {
                            alertOnOver = it
                            budgetStore.setAlertOnOver(it)
                        },
                    )
                }
            }
        }
    }

    if (showTotalDialog) {
        AmountEditDialog(
            title = "设置本月总预算",
            initialCents = totalBudget,
            onDismiss = { showTotalDialog = false },
            onConfirm = { cents ->
                budgetStore.setTotalBudgetCents(cents)
                totalBudget = cents
                showTotalDialog = false
            },
        )
    }

    val editing = editingCategory
    if (editing != null) {
        AmountEditDialog(
            title = "设置「${Category.label(editing)}」预算",
            initialCents = categoryBudgets[editing] ?: 0L,
            onDismiss = { editingCategory = null },
            onConfirm = { cents ->
                budgetStore.setCategoryBudgetCents(editing, cents)
                categoryBudgets = categoryBudgets.toMutableMap().also { it[editing] = cents }
                editingCategory = null
            },
        )
    }
}

@Composable
private fun AmountEditDialog(
    title: String,
    initialCents: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember {
        mutableStateOf(String.format("%.2f", initialCents / 100.0))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontSize = 17.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("金额（元）") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cents = Money.parseToCents(text)
                    if (cents != null) onConfirm(cents)
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
