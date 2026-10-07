package com.geqian6.qingjizhang.service

import com.geqian6.qingjizhang.data.TransactionRecord
import com.geqian6.qingjizhang.util.Category
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Source

/**
 * 支付**通知**文本解析（通知监听通道专用）。
 *
 * 重要：这些规则天然是脆弱的 —— 微信、支付宝随时可能改通知文案。
 * 所以：
 * 1. 解析不出来的直接丢弃，绝不硬塞一条错账
 * 2. 原始文本存进 rawText，出问题时能回溯到底哪条规则错了
 *
 * 方向判定跟 PaymentPageParser 保持**同一套口径**（这是 2026-10-07 实测校准过的）：
 *   · 支出表只放「正在付 / 付完了」的动作词
 *   · 收入表每一条都必须是「钱已经进来了」的明确写法，绝不写裸的「收款」「转入」
 *     （裸词会被付款通知里的「收款方：XXX」带偏，把支出判成收入）
 *   · 两边都不命中 → 不记（宁缺勿错）
 *
 * 备注：与无障碍通道一致，写明「谁给谁 + 对方是谁」。
 */
object PaymentParser {

    /** 金额：优先认带货币符号的，其次认「12.34元」 */
    private val amountPatterns = listOf(
        Regex("[￥¥]\\s?([0-9]+(?:\\.[0-9]{1,2})?)"),
        Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元"),
        Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*人民币"),
    )

    /** 支出信号：只放「钱出去了」的动作词 */
    private val expenseKeywords = listOf(
        "支付成功", "付款成功", "已支付", "已付款", "支出", "支付了", "付款给",
        "请验证指纹", "请输入支付密码", "已转账", "转账成功", "已转出",
        "你发起了一笔转账", "你发起了转账"
    )

    /**
     * **收入**信号。
     * ⚠️ 这里绝不能写裸的「收款」「转入」：付款成功通知里常带「收款方：XXX」，
     * 裸词会把一笔支出判成收入（实测踩过同类坑）。
     */
    private val incomeKeywords = listOf(
        "已收款", "收款成功", "收款到账", "已到账", "到账成功", "红包到账",
        "已退款", "退款成功", "已转入", "已入账", "已存入零钱",
        "已收到转账", "收到转账", "你领取了", "向你转账", "给你转账"
    )

    private val merchantPatterns = listOf(
        Regex("你领取了\\s*([^\\s]{1,16}?)\\s*的红包"),
        Regex("([^\\s]{2,16})\\s*向你转账"),
        Regex("向\\s*(.{1,24}?)\\s*(?:支付|付款|转账)"),
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

        // 方向：支出优先；两边都不命中则放弃（不再"默认支出"）
        val isExpense = when {
            expenseKeywords.any { text.contains(it) } -> true
            incomeKeywords.any { text.contains(it) } -> false
            else -> return null
        }

        val merchant = extractMerchant(text).ifBlank { Source.fullLabel(source) }
        val note = buildNote(isExpense, merchant, text)
        val guessed = Category.guess(merchant, isExpense)

        return TransactionRecord(
            amountCents = amountCents,
            isExpense = isExpense,
            category = guessed,
            source = source,
            merchant = merchant,
            note = note,
            rawText = text,
            // 方向和金额确定即成立；分类不准只影响分类
            status = TransactionRecord.STATUS_CONFIRMED,
            occurredAt = now,
        )
    }

    /** 备注：谁给谁 + 对方是谁 */
    private fun buildNote(isExpense: Boolean, merchant: String, text: String): String {
        val scene = when {
            text.contains("红包") -> "红包"
            text.contains("转账") -> "转账"
            else -> "支付"
        }
        val named = merchant.isNotBlank() &&
            merchant != Source.fullLabel(Source.WECHAT) &&
            merchant != Source.fullLabel(Source.ALIPAY) &&
            merchant != "微信红包"
        return when {
            scene == "红包" && isExpense -> "发红包"
            scene == "红包" -> if (named) "收到 " + merchant + " 的红包" else "收到红包"
            scene == "转账" && isExpense -> if (named) "转给 " + merchant else "转账支出"
            scene == "转账" -> if (named) "收到 " + merchant + " 的转账" else "收到一笔转账"
            isExpense -> if (named) "付给 " + merchant else ""
            else -> if (named) "收款 · " + merchant else ""
        }
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
