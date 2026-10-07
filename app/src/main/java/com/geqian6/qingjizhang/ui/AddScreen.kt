package com.geqian6.qingjizhang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.geqian6.qingjizhang.data.TransactionRecord
import com.geqian6.qingjizhang.ui.theme.AppColor
import com.geqian6.qingjizhang.util.Category
import com.geqian6.qingjizhang.util.Dates
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Source

@Composable
fun AddScreen(
    viewModel: AppViewModel,
    onClose: () -> Unit,
) {
    var isExpense by remember { mutableStateOf(true) }
    var amountText by remember { mutableStateOf("0") }
    var categoryKey by remember { mutableStateOf(Category.DINING) }
    var sourceKey by remember { mutableStateOf(Source.WECHAT) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(isExpense) {
        categoryKey = if (isExpense) Category.DINING else Category.SALARY
    }

    val amountCents = Money.parseToCents(amountText) ?: 0L
    val canSave = amountCents > 0L

    val onKey: (String) -> Unit = { key ->
        when {
            key == "⌫" -> amountText = if (amountText.length <= 1) "0" else amountText.dropLast(1)
            key == "." -> if (!amountText.contains(".")) amountText += "."
            amountText == "0" -> amountText = key
            else -> {
                val dot = amountText.indexOf('.')
                val overLimit = dot >= 0 && amountText.length - dot > 2
                if (!overLimit) amountText += key
            }
        }
    }

    val save: () -> Unit = {
        if (canSave) {
            viewModel.add(
                TransactionRecord(
                    amountCents = amountCents,
                    isExpense = isExpense,
                    category = categoryKey,
                    source = sourceKey,
                    merchant = note.ifBlank { Category.label(categoryKey) },
                    note = note,
                    rawText = "",
                    status = TransactionRecord.STATUS_CONFIRMED,
                    occurredAt = Dates.currentTs(),
                )
            )
            onClose()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColor.bg)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "取消",
                        fontSize = 15.sp,
                        color = AppColor.textSecondary,
                        modifier = Modifier.clickable { onClose() },
                    )
                    Text(
                        text = "记一笔",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColor.textPrimary,
                    )
                    Text(
                        text = "保存",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canSave) AppColor.primary else AppColor.textTertiary,
                        modifier = Modifier.clickable(enabled = canSave) { save() },
                    )
                }
            }

            // 金额 + 收支切换
            item {
                AppCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(AppColor.switchTrack)
                                .padding(3.dp)
                        ) {
                            TypeButton("支出", isExpense, AppColor.expense) { isExpense = true }
                            TypeButton("收入", !isExpense, AppColor.income) { isExpense = false }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "¥ " + amountText,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColor.textPrimary,
                        )
                    }
                }
            }

            // 分类
            item {
                AppCard(padding = 18.dp) {
                    Text(
                        text = "选择分类",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColor.textPrimary,
                    )
                    Spacer(Modifier.height(14.dp))
                    CategoryGrid(
                        keys = Category.keys(isExpense),
                        selected = categoryKey,
                        onSelect = { categoryKey = it },
                    )
                }
            }

            // 来源
            item {
                AppCard(padding = 18.dp) {
                    Text(
                        text = "这笔钱从哪来",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColor.textPrimary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Source.all.forEach { key ->
                            PillChip(
                                label = Source.label(key),
                                selected = sourceKey == key,
                                onClick = { sourceKey = key },
                                selectedColor = sourceColor(key),
                            )
                        }
                    }
                }
            }

            // 备注
            item {
                AppCard(padding = 18.dp) {
                    Text(
                        text = "备注",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColor.textPrimary,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = { Text("添加备注…", fontSize = 14.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // 数字键盘
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf(".", "0", "⌫"),
            ).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (key == "⌫") AppColor.switchTrack else AppColor.card)
                                .clickable { onKey(key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = key,
                                fontSize = 18.sp,
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

@Composable
private fun TypeButton(
    label: String,
    selected: Boolean,
    selectedColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) selectedColor else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) androidx.compose.ui.graphics.Color.White else AppColor.textPrimary,
        )
    }
}

@Composable
private fun CategoryGrid(
    keys: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        keys.chunked(4).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowKeys.forEach { key ->
                    val isSelected = key == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(key) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (isSelected) categoryColor(key)
                                    else categoryColor(key).copy(alpha = 0.12f)
                                )
                                .padding(
                                    horizontal = 14.dp,
                                    vertical = 8.dp,
                                ),
                        ) {
                            Text(
                                text = Category.label(key).take(1),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isSelected) androidx.compose.ui.graphics.Color.White
                                else categoryColor(key),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = Category.label(key),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = if (isSelected) AppColor.textPrimary else AppColor.textSecondary,
                        )
                    }
                }
                repeat(4 - rowKeys.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
