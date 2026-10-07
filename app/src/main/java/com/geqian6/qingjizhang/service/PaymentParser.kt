package com.geqian6.qingjizhang.service

import com.geqian6.qingjizhang.data.TransactionRecord
import com.geqian6.qingjizhang.util.Category
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Source

/**
 * 支付通知文本解析。
 *
 * 重要：这些规则天然是脆弱的——微信、支付宝随时可能改通知文案。
 * 所以：
 * 1. 解析不出来的直接丢弃，绝不硬塞一条错账
 * 2. 解析出金额但认不出分类的，标成「待确认」，进数据库让用户补
 * 3. 原始文本存进 rawText，出问题时能回溯到底哪条规则错了
 *
 * 后续应该把规则挪进数据库做成可配置，这样改规则不用重新发版。
 */
object PaymentParser {

    /** 金额：优先认带货币符号的，其次认「12.34元」 */
    private val amountPatterns = listOf(
        Regex("[￥¥]\\s?([0-9]+(?:\\.[0-9]{1,2})?)"),
        Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元"),
        Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*人民币"),
    )

    private val expenseKeywords = listOf(
        "支付成功", "付款成功", "已支付", "已付款", "消费", "支出", "扣款", "转出", "支付了", "付款给"
    )

    private val incomeKeywords = listOf(
        "收款", "已收款", "到账", "转入", "退款", "红包到账", "收入", "返还"
    )

    private val merchantPatterns = listOf(
        Regex("向\\s*(.{1,24}?)\\s*支付"),
        Regex("付款给\\s*(.{1,24})"),
        Regex("收款方[:：]?\\s*(.{1,24})"),
        Regex("商户[:：]?\\s*(.{1,24})"),
        Regex("[—\\-·]\\s*(.{2,24})$"),
    )

    /**
     * @param rawText 通知里拼起来的所有文本
     * @param source  来源常量（Source.WECHAT / Source.ALIPAY）
     * @param now     当前时间戳
     * @return 解析成功返回一条流水；判断不出金额或不像支付通知则返回 null
     */
    fun parse(rawText: String, source: String, now: Long): TransactionRecord? {
        val text = rawText.replace('\n', ' ').replace('\r', ' ').trim()
        if (text.length < 4) return null

        val hasPayKeyword =
            expenseKeywords.any { text.contains(it) } || incomeKeywords.any { text.contains(it) }
        if (!hasPayKeyword) return null

        val amountCents = extractAmountCents(text) ?: return null
        if (amountCents <= 0L) return null

        val isExpense = when {
            expenseKeywords.any { text.contains(it) } -> true
            incomeKeywords.any { text.contains(it) } -> false
            else -> true
        }

        val merchant = extractMerchant(text).ifBlank { Source.fullLabel(source) }
        val guessed = Category.guess(merchant, isExpense)

        // 认不出具体分类时标为待确认，交给用户补全，而不是替用户猜死
        val confident = guessed != Category.OTHER && guessed != Category.INCOME_OTHER

        return TransactionRecord(
            amountCents = amountCents,
            isExpense = isExpense,
            category = guessed,
            source = source,
            merchant = merchant,
            note = "",
            rawText = text,
            status = if (confident) TransactionRecord.STATUS_CONFIRMED
            else TransactionRecord.STATUS_PENDING,
            occurredAt = now,
        )
    }

    private fun extractAmountCents(text: String): Long? {
        for (pattern in amountPatterns) {
            val match = pattern.find(text) ?: continue
            val raw = match.groupValues.getOrNull(1) ?: continue
            val cents = Money.parseToCents(raw)
            if (cents != null && cents > 0L) return cents
        }
        return null
    }

    private fun extractMerchant(text: String): String {
        for (pattern in merchantPatterns) {
            val match = pattern.find(text) ?: continue
            val candidate = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val cleaned = candidate
                .removeSuffix("。")
                .removeSuffix("，")
                .removeSuffix("成功")
                .trim()
            if (cleaned.length in 2..24) return cleaned
        }
        return ""
    }
}
