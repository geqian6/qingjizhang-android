package com.geqian6.qingjizhang.util

import android.content.Context

/**
 * 预算存储。
 *
 * 预算不放进流水表——它是配置不是数据，用 SharedPreferences 存更轻，
 * 也不用为它升数据库版本。金额同样以「分」为单位。
 */
class BudgetStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("qingjizhang_budgets", Context.MODE_PRIVATE)

    fun totalBudgetCents(): Long = sp.getLong(KEY_TOTAL, DEFAULT_TOTAL)

    fun setTotalBudgetCents(cents: Long) {
        sp.edit().putLong(KEY_TOTAL, cents.coerceAtLeast(0L)).apply()
    }

    fun categoryBudgetCents(categoryKey: String): Long =
        sp.getLong(KEY_CAT_PREFIX + categoryKey, defaultForCategory(categoryKey))

    fun setCategoryBudgetCents(categoryKey: String, cents: Long) {
        sp.edit().putLong(KEY_CAT_PREFIX + categoryKey, cents.coerceAtLeast(0L)).apply()
    }

    fun resetAll() {
        sp.edit().clear().apply()
    }

    /** 达到 80% 时提醒 */
    fun alertAt80(): Boolean = sp.getBoolean(KEY_ALERT_80, true)

    fun setAlertAt80(value: Boolean) {
        sp.edit().putBoolean(KEY_ALERT_80, value).apply()
    }

    /** 超出预算时提醒 */
    fun alertOnOver(): Boolean = sp.getBoolean(KEY_ALERT_OVER, true)

    fun setAlertOnOver(value: Boolean) {
        sp.edit().putBoolean(KEY_ALERT_OVER, value).apply()
    }

    companion object {
        private const val KEY_TOTAL = "total_budget_cents"
        private const val KEY_CAT_PREFIX = "cat_budget_"
        private const val KEY_ALERT_80 = "alert_at_80"
        private const val KEY_ALERT_OVER = "alert_on_over"

        /** 默认总预算 5000 元 */
        private const val DEFAULT_TOTAL = 500_000L

        /** 各分类默认预算，单位分 */
        fun defaultForCategory(key: String): Long = when (key) {
            Category.DINING -> 150_000L
            Category.SHOPPING -> 100_000L
            Category.TRANSIT -> 30_000L
            Category.ENTERTAINMENT -> 40_000L
            Category.MEDICAL -> 20_000L
            Category.HOUSING -> 120_000L
            Category.DAILY -> 30_000L
            else -> 50_000L
        }
    }
}
