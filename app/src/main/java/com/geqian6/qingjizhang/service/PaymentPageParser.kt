package com.geqian6.qingjizhang.service

import com.geqian6.qingjizhang.data.TransactionRecord
import com.geqian6.qingjizhang.util.Category
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Source

/**
 * 支付「结果页」文本解析（无障碍通道专用）。
 *
 * 和 PaymentParser 的区别，很关键：
 * - PaymentParser 吃的是**通知**文本，短、干净，容错可以放宽
 * - 这个吃的是**整个屏幕**的文本，长、脏、什么都有，所以判据必须收紧
 *
 * 收紧的策略（顺序即优先级）：
 * 1. 必须出现明确的成功词，且不能出现失败/取消/待支付词
 * 2. 金额必须带货币符号（￥ / ¥）—— 页面上的裸数字（时间、订单号、序号）太多，
 *    只认带符号的金额，这一条就卡死了绝大多数误判
 * 3. 商户名先按「收款方 / 商户名称 / 付款给」这类标签取；取不到再退而求其次，
 *    取金额节点前后的文本，并且要求「不含数字、含中文、不撞噪声词」
 *
 * 诚实说明：这套规则是**未上机验证**的推测。微信/支付宝改版就会失效，
 * 所以解析不出时的正确行为是**什么都不记**（宁缺勿错），而不是硬塞一条。
 * 出问题时 rawText 里存着当时屏幕上的原文，可以回溯是哪条规则错了。
 */
object PaymentPageParser {

    /**
     * 无障碍层给 contentDescription 加的前缀（见 PaymentAccessibilityService.walk）。
     * 进解析前必须剥掉 —— 否则头像图的无障碍说明「苏格拉没有底头像」会带着前缀被当成商户名。
     * 实测踩过：账单里出现了商户名「苏格拉没有底头像」。
     */
    private const val DESC_PREFIX = "〔描述〕"

    private val successKeywords = listOf(
        "支付成功", "付款成功", "已支付", "交易成功", "转账成功",
        "已转账", "已转出", "已转入",
        "收款成功", "已收款", "退款成功", "已退款", "已存入", "已收钱"
    )

    /**
     * **支出**信号。判方向时**先查这张表**。
     * ⚠️ 微信转账给好友的完成页写的是「已转账」而不是「支付成功」，
     * 而这类页面往往同时出现「到账」字样 —— 不在这里抢先判成支出，就会被误判成收入（实测踩过）。
     */
    private val expenseKeywords = listOf(
        "支付成功", "付款成功", "已支付", "交易成功", "扣款", "消费",
        "转出", "已转账", "已转出", "转账给"
    )

    /**
     * **收入**信号。⚠️ 每一条都必须是「明确表示钱进来了」的写法。
     * 千万别写裸的「到账」：提现页的「2小时内到账」、转账页的「实时到账」都会命中，
     * 结果支出被记成收入 —— 实测踩过这个坑，所以只留「已到账」「到账成功」。
     */
    private val incomeKeywords = listOf(
        "已收款", "收款成功", "已收钱", "已存入",
        "退款成功", "已退款", "已转入", "已到账", "到账成功"
    )

    /** 出现这些词说明这笔没成，直接放弃 */
    private val abortKeywords = listOf(
        "支付失败", "付款失败", "交易失败", "已取消", "交易关闭",
        "待支付", "未支付", "正在处理", "处理中", "订单已失效", "余额不足"
    )

    /** 金额必须带货币符号 */
    private val strictAmount = Regex("[￥¥]\\s?([0-9]{1,7}(?:\\.[0-9]{1,2})?)")

    /** 带标签的商户名，最可靠 */
    private val labelledMerchant = listOf(
        Regex("(?:收款方|收款人|商户全称|商户名称|店铺名称|付款给|支付给|转账给|对方账号)[:：]?\\s*(.{2,24})"),
        Regex("向\\s*(.{2,24}?)\\s*(?:付款|支付|转账)")
    )

    /** 页面上的固定文案，不能当商户名 */
    private val noise = listOf(
        "支付成功", "付款成功", "已支付", "交易成功", "收款成功", "退款成功", "已退款",
        "收款方", "付款方", "付款方式", "支付方式", "零钱", "余额", "银行卡", "钱包",
        "账单详情", "查看详情", "查看账单", "完成", "返回", "首页", "更多", "优惠",
        "明细", "订单号", "交易单号", "时间", "金额", "状态", "备注", "手续费",
        "当前状态", "已存入", "到账", "转入", "转账", "详情", "关闭", "确定", "知道了",
        "使用零钱", "推荐", "币种", "商品", "说明", "全部", "本月", "今日", "再次支付",
        "电子凭证", "申请退款", "联系商家", "评价", "再来一笔"
    )

    /**
     * @param texts  当前屏幕按阅读顺序抓下来的所有文本
     * @param source Source.WECHAT / Source.ALIPAY
     * @param now    当前时间戳
     * @return 解析成功返回一条流水；只要有一处拿不准就返回 null
     */
    fun parse(texts: List<String>, source: String, now: Long): TransactionRecord? {
        // 剥掉无障碍层加的描述前缀再进规则 —— 前缀是给诊断看的，不该污染商户名
        val items = texts.map {
            it.trim().replace('\u00A0', ' ').removePrefix(DESC_PREFIX)
        }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null

        val joined = items.joinToString(" ")
        if (joined.length < 6) return null
        if (abortKeywords.any { joined.contains(it) }) return null
        if (successKeywords.none { joined.contains(it) }) return null

        val amountCents = strictAmount.find(joined)
            ?.groupValues?.getOrNull(1)
            ?.let { Money.parseToCents(it) }
            ?: return null
        if (amountCents <= 0L) return null

        // 先查支出信号：绝大多数付款场景都是花钱，收入必须"明确"才算
        val isExpense = when {
            expenseKeywords.any { joined.contains(it) } -> true
            incomeKeywords.any { joined.contains(it) } -> false
            else -> true
        }

        val merchant = extractMerchant(items).ifBlank { Source.fullLabel(source) }
        val guessed = Category.guess(merchant, isExpense)
        val confident = guessed != Category.OTHER && guessed != Category.INCOME_OTHER

        return TransactionRecord(
            amountCents = amountCents,
            isExpense = isExpense,
            category = guessed,
            source = source,
            merchant = merchant,
            note = "",
            rawText = joined.take(500),
            status = if (confident) TransactionRecord.STATUS_CONFIRMED
            else TransactionRecord.STATUS_PENDING,
            occurredAt = now,
        )
    }

    private fun extractMerchant(items: List<String>): String {
        // 1) 带标签的商户名
        val joined = items.joinToString(" ")
        for (pattern in labelledMerchant) {
            val value = pattern.find(joined)?.groupValues?.getOrNull(1) ?: continue
            tidy(value, allowDigit = true)?.let { return it }
        }

        // 2) 金额节点前后各看 4 个节点
        val amountIndex = items.indexOfFirst { strictAmount.containsMatchIn(it) }
        if (amountIndex >= 0) {
            for (i in amountIndex - 1 downTo maxOf(0, amountIndex - 4)) {
                tidy(items[i], allowDigit = false)?.let { return it }
            }
            for (i in amountIndex + 1..minOf(items.lastIndex, amountIndex + 4)) {
                tidy(items[i], allowDigit = false)?.let { return it }
            }
        }

        // 3) 全局最长的合格中文短语
        return items.mapNotNull { tidy(it, allowDigit = false) }
            .maxByOrNull { it.length }
            .orEmpty()
    }

    /**
     * 清洗 + 判定一个候选商户名是否可信。返回 null 表示丢掉。
     * allowDigit=false 用于「猜」出来的候选（号码、日期、序号全在这条上被滤掉）；
     * allowDigit=true 只给带标签的候选（"7-11便利店" 这种才留得下）。
     */
    private fun tidy(raw: String, allowDigit: Boolean): String? {
        var s = raw.removePrefix(DESC_PREFIX)
            .replace('\n', ' ')
            .trim()
            .trimStart('·', '-', '—', ':', '：')
            .trim()
        // 头像图的无障碍说明长这样：「苏格拉没有底头像」。
        // 昵称本身是对的，尾巴这两个字纯粹是图片语义 —— 砍掉它，只留昵称。
        if (s.length > 2 && s.endsWith("头像")) s = s.dropLast(2).trim()

        if (s.length !in 2..24) return null
        if (strictAmount.containsMatchIn(s)) return null
        if (s.any { it.isDigit() } && (!allowDigit || Regex("[0-9]{2,}").containsMatchIn(s))) return null
        if (s.none { it.code in 0x4E00..0x9FFF || it.isLetter() }) return null
        if (noise.any { s.contains(it) }) return null
        return s
    }
}
