package com.geqian6.qingjizhang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import com.geqian6.qingjizhang.util.Dates
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Source
import com.geqian6.qingjizhang.util.Stats

@Composable
fun DetailScreen(
    viewModel: AppViewModel,
    onRecordClick: (Long) -> Unit = {},
) {
    val monthRecords by viewModel.monthRecords.collectAsState()
    val monthAnchor by viewModel.monthAnchor.collectAsState()
    var sourceFilter by remember { mutableStateOf<String?>(null) }

    val filtered = remember(monthRecords, sourceFilter) {
        val key = sourceFilter
        if (key == null) monthRecords else monthRecords.filter { it.source == key }
    }
    val grouped = remember(filtered) { Stats.groupByDay(filtered) }
    val filterKey = sourceFilter

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColor.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(title = "账单明细")
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundNavButton("‹") { viewModel.shiftMonth(-1) }
                Text(
                    text = Dates.monthLabel(monthAnchor),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColor.textPrimary,
                )
                RoundNavButton("›") { viewModel.shiftMonth(1) }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillChip(
                    label = "全部",
                    selected = filterKey == null,
                    onClick = { sourceFilter = null },
                )
                listOf(Source.WECHAT, Source.ALIPAY, Source.BANK, Source.CASH, Source.MANUAL)
                    .forEach { key ->
                        PillChip(
                            label = Source.label(key),
                            selected = filterKey == key,
                            onClick = { sourceFilter = if (filterKey == key) null else key },
                            selectedColor = sourceColor(key),
                        )
                    }
            }
        }

        if (grouped.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(AppColor.card)
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (filterKey == null) "这个月还没有记录"
                        else "这个月没有「${Source.label(filterKey)}」的记录",
                        fontSize = 13.sp,
                        color = AppColor.textSecondary,
                    )
                }
            }
        } else {
            grouped.forEach { (dayStart, dayRecords) ->
                item {
                    DayGroupCard(
                        dayStart = dayStart,
                        records = dayRecords,
                        onRecordClick = { onRecordClick(it.id) },
                    )
                }
            }

            item {
                val totalExpense = filtered.filter { it.isExpense }.sumOf { it.amountCents }
                val totalIncome = filtered.filter { !it.isExpense }.sumOf { it.amountCents }
                AppCard(padding = 16.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "共 ${filtered.size} 笔",
                            fontSize = 13.sp,
                            color = AppColor.textSecondary,
                        )
                        Text(
                            text = "收入 ¥${Money.format(totalIncome)}  支出 ¥${Money.format(totalExpense)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColor.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundNavButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColor.card)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            color = AppColor.textSecondary,
        )
    }
}
