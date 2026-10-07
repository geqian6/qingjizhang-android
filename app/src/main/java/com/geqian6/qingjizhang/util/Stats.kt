package com.geqian6.qingjizhang.util

import com.geqian6.qingjizhang.data.TransactionRecord

/** 分类占比的一片 */
data class CategorySlice(
    val key: String,
    val label: String,
    val amountCents: Long,
    val fraction: Float,
)

/** 图表上的一根柱子 */
data class BarItem(
    val label: String,
    val incomeCents: Long,
    val expenseCents: Long,
    val highlight: Boolean,
)

/**
 * 纯计算，不依赖 Android。所有统计都从流水列表算出来，
 * 好处是数据源换了（本机库 / 导入账单）统计逻辑不用动。
 */
object Stats {

    fun totalIncome(records: List<TransactionRecord>): Long =
        records.filter { !it.isExpense }.sumOf { it.amountCents }

    fun totalExpense(records: List<TransactionRecord>): Long =
        records.filter { it.isExpense }.sumOf { it.amountCents }

    fun balance(records: List<TransactionRecord>): Long =
        totalIncome(records) - totalExpense(records)

    fun byCategory(records: List<TransactionRecord>, isExpense: Boolean): List<CategorySlice> {
        val filtered = records.filter { it.isExpense == isExpense }
        val total = filtered.sumOf { it.amountCents }
        if (total <= 0L) return emptyList()
        return filtered
            .groupBy { it.category }
            .map { (key, list) ->
                val sum = list.sumOf { it.amountCents }
                CategorySlice(
                    key = key,
                    label = Category.label(key),
                    amountCents = sum,
                    fraction = sum.toFloat() / total.toFloat(),
                )
            }
            .sortedByDescending { it.amountCents }
    }

    /** 截至 endTs 的近 days 天，逐日收支 */
    fun dailyBars(records: List<TransactionRecord>, days: Int, endTs: Long): List<BarItem> {
        val endDayStart = Dates.startOfDay(endTs)
        return (days - 1 downTo 0).map { offset ->
            val dayStart = Dates.addDays(endDayStart, -offset)
            val dayEnd = Dates.addDays(dayStart, 1)
            val inDay = records.filter { it.occurredAt >= dayStart && it.occurredAt < dayEnd }
            BarItem(
                label = if (offset == 0) "今天" else Dates.dayShortLabel(dayStart),
                incomeCents = inDay.filter { !it.isExpense }.sumOf { it.amountCents },
                expenseCents = inDay.filter { it.isExpense }.sumOf { it.amountCents },
                highlight = offset == 0,
            )
        }
    }

    /** 截至 endTs 的近 months 个月，逐月收支 */
    fun monthlyBars(records: List<TransactionRecord>, months: Int, endTs: Long): List<BarItem> {
        val currentMonthStart = Dates.startOfMonth(endTs)
        return (months - 1 downTo 0).map { offset ->
            val monthStart = Dates.addMonths(currentMonthStart, -offset)
            val monthEnd = Dates.addMonths(monthStart, 1)
            val inMonth = records.filter { it.occurredAt >= monthStart && it.occurredAt < monthEnd }
            BarItem(
                label = Dates.monthShortLabel(monthStart),
                incomeCents = inMonth.filter { !it.isExpense }.sumOf { it.amountCents },
                expenseCents = inMonth.filter { it.isExpense }.sumOf { it.amountCents },
                highlight = offset == 0,
            )
        }
    }

    /** 按自然日分组，供明细页使用。返回 (当天 0 点时间戳, 当天流水) */
    fun groupByDay(records: List<TransactionRecord>): List<Pair<Long, List<TransactionRecord>>> =
        records
            .groupBy { Dates.startOfDay(it.occurredAt) }
            .toList()
            .sortedByDescending { it.first }

    /** 环比变化率。上一周期为 0 时无法计算，返回 null */
    fun changeRatio(current: Long, previous: Long): Float? {
        if (previous <= 0L) return null
        return (current - previous).toFloat() / previous.toFloat()
    }

    /** 在某段时间内的收入 / 支出 */
    fun sumIn(records: List<TransactionRecord>, from: Long, to: Long): Pair<Long, Long> {
        val inRange = records.filter { it.occurredAt >= from && it.occurredAt < to }
        return totalIncome(inRange) to totalExpense(inRange)
    }
}
