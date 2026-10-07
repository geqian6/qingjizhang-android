package com.geqian6.qingjizhang.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 金额工具。所有金额在内部一律用「分」表示，只在展示时转成元。
 */
object Money {

    /** 1234567 分 -> "12,345.67" */
    fun format(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val yuan = abs / 100
        val fen = abs % 100
        val yuanStr = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
        val body = "$yuanStr.${fen.toString().padStart(2, '0')}"
        return if (negative) "-$body" else body
    }

    /** 带符号，用于流水列表：支出 "-38.00"，收入 "+45.00" */
    fun formatSigned(cents: Long, isExpense: Boolean): String {
        val abs = if (cents < 0) -cents else cents
        return (if (isExpense) "-" else "+") + format(abs)
    }

    /** "38.00" / "38" -> 3800 分；解析失败返回 null */
    fun parseToCents(input: String): Long? {
        val trimmed = input.trim().replace(",", "")
        if (trimmed.isEmpty()) return null
        return try {
            val value = trimmed.toDouble()
            if (value < 0) null else Math.round(value * 100)
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/**
 * 时间工具。统一用毫秒时间戳，周起始为周一。
 */
object Dates {

    private val dayFmt = SimpleDateFormat("M月d日", Locale.CHINA)
    private val dayWeekFmt = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val monthFmt = SimpleDateFormat("yyyy年M月", Locale.CHINA)
    private val monthShortFmt = SimpleDateFormat("M月", Locale.CHINA)
    private val dayShortFmt = SimpleDateFormat("M/d", Locale.CHINA)

    fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun endOfDay(ts: Long): Long = startOfDay(ts) + 24L * 60 * 60 * 1000

    /** 周一为一周之始 */
    fun startOfWeek(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay(ts)
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    }.timeInMillis

    fun endOfWeek(ts: Long): Long = startOfWeek(ts) + 7L * 24 * 60 * 60 * 1000

    fun startOfMonth(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay(ts)
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    fun endOfMonth(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfMonth(ts)
        add(Calendar.MONTH, 1)
    }.timeInMillis

    fun addMonths(ts: Long, delta: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        add(Calendar.MONTH, delta)
    }.timeInMillis

    fun addDays(ts: Long, delta: Int): Long = ts + delta * 24L * 60 * 60 * 1000

    fun dayLabel(ts: Long): String = dayFmt.format(Date(ts))

    /** "10月7日 周二" —— SimpleDateFormat 中文给的是「星期二」，这里收成「周二」 */
    fun dayWeekLabel(ts: Long): String =
        dayWeekFmt.format(Date(ts)).replace("星期", "周")

    fun timeLabel(ts: Long): String = timeFmt.format(Date(ts))

    fun monthLabel(ts: Long): String = monthFmt.format(Date(ts))

    fun monthShortLabel(ts: Long): String = monthShortFmt.format(Date(ts))

    fun dayShortLabel(ts: Long): String = dayShortFmt.format(Date(ts))

    fun dayOfMonth(ts: Long): Int = Calendar.getInstance().apply {
        timeInMillis = ts
    }.get(Calendar.DAY_OF_MONTH)

    fun daysInMonth(ts: Long): Int = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
    }.get(Calendar.DAY_OF_MONTH)

    fun currentTs(): Long = System.currentTimeMillis()
}
