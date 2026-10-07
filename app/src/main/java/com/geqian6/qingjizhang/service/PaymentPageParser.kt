package com.geqian6.qingjizhang.service

import com.geqian6.qingjizhang.data.TransactionRecord
import com.geqian6.qingjizhang.util.Category
import com.geqian6.qingjizhang.util.Money
import com.geqian6.qingjizhang.util.Source

/**
 * 支付「结果页 / 确认页」文本解析（无障碍通道专用）。
 *
 * 和 PaymentParser 的区别，很关键：
 * - PaymentParser 吃的是**通知**文本，短、干净，容错可以放宽
 * - 这个吃的是**整个屏幕**的文本，长、脏、什么都有，所以判据必须收紧到极致
 *
 * ===========================================================================
 * 本版核心：分清「谁给谁」，并把对方名字写进备注
 *
 * 四象限的判定依据（全部来自 2026-10-07 实机日志原文，不是推测）：
 *
 *  ┌ 我给对方 · 转账 —— 支出
 *  │   微信聊天页气泡：「￥0.01 ／ 你发起了一笔转账 ／ 微信转账 ／ XX头像」
 *  │   支付宝密码页  ：「向五十夜的秋(张涵)转账 ／ 支付金额0.01元 ／ 请输入支付密码」
 *  ├ 我给对方 · 红包 —— 支出
 *  │   付款页：「微信红包 ／ ￥0.01 ／ 微信支付 ／ 返回 ／ 请验证指纹」
 *  ├ 对方给我 · 转账 —— 收入
 *  │   我点「确认收款」后，聊天页气泡变成「￥0.01 ／ 已收款 ／ 微信转账」
 *  │   支付宝侧：「收款成功 / 已收款」
 *  └ 对方给我 · 红包 —— 收入
 *      领取后聊天页会出现系统提示「你领取了XX的红包」
 *
 * ---------------------------------------------------------------------------
 * ⚠️ 状态词**绝对不能**当方向词（实测踩过，写在这里防止有人改回去）：
 *   · 「已被接收」 —— 那条日志里的转账是**我发出**的，对方收下后气泡才写「已被接收」
 *   · 「已被领完」 —— 我发出的红包被别人抢光，同样是支出
 *   把这两个词当收入，就会把"我付出去的钱"记成"收入"。
 *   所以红包 / 转账气泡的方向**只认动作词**（发起/塞钱/验证/已收款/领取了）。
 *
 * ⚠️ 聊天页（一屏出现 ≥2 个「XX头像」节点）单独一套词表，只认**高置信方向词**。
 *   原因：聊天页里躺着大量历史消息，随便一句含金额的话都可能被误判 ——
 *   实测踩过「把诊断文本粘进微信聊天 → 连记 3 笔『聊天信息 · 收入』」。
 *
 * ---------------------------------------------------------------------------
 * 三道闸门（顺序 = 优先级）
 *   1. 方向必须明确（上表）；两边都不命中 / 都命中 → 不记
 *   2. 金额必须是「独立金额节点」或「带标签的金额」；长句子里夹的金额一律不算
 *   3. 商户名宁缺勿错；备注写明场景 + 对方 + 支付渠道
 *
 * 诚实说明：微信/支付宝随时可能改版，规则会失效。失效时的正确行为是
 * **什么都不记**（宁缺勿错），而不是硬塞一条错账。rawText 里留着当时屏幕原文。
 */
object PaymentPageParser {

    /**
     * 无障碍层给 contentDescription 加的前缀（见 PaymentAccessibilityService.walk）。
     * 进解析前必须剥掉 —— 否则头像图的无障碍说明会带着前缀被当成商户名。
     */
    private const val DESC_PREFIX = "〔描述〕"

    /** 微信头像节点的后缀：「苏格拉没有底头像」 */
    private const val AVATAR_SUFFIX = "头像"

    /**
     * 一屏里出现这么多个「XX头像」节点，就判定为**聊天界面**。
     * 聊天页上的转账/红包气泡会长期挂着，必须用更严的词表 + 更长的去重窗口。
     */
    private const val CHAT_AVATAR_THRESHOLD = 2

    /** 场景标签，写进备注用 */
    const val SCENE_TRANSFER = "转账"
    const val SCENE_RED_PACKET = "红包"
    const val SCENE_PAYMENT = "支付"

    /**
     * **支出信号**（非聊天页用）。只收录「我正在付 / 我已经付完了」的文案。
     *
     * ⚠️ 别往里加这些词，它们都踩过坑：
     *   · 「消费」「扣款」  —— 微信/支付宝的**账单列表页**每行都有，会成片误记
     *   · 「到账」「转入」  —— 提现页的「2小时内到账」会命中，支出被记成收入
     */
    private val expenseSignals = listOf(
        // 结果页
        "支付成功", "付款成功", "已支付", "付款已成功", "支付已完成", "交易成功",
        "扣款成功", "转账成功", "已转账", "已转出",
        // 付款确认 / 验证页
        "请验证指纹", "请验证面容", "请输入支付密码", "确认付款", "立即支付", "塞钱进红包",
        // 微信转账发出后聊天页的气泡文案（这是转账唯一能抓到的完成标志）
        "你发起了一笔转账", "你发起了转账"
    )

    /**
     * **收入信号**（非聊天页用）。每一条都必须是「钱已经进来了」的明确写法。
     * ⚠️ 绝不能写裸的「收款」「转入」：付款成功页常带「收款方：XXX」，
     *    裸词会把一笔支出判成收入。
     */
    private val incomeSignals = listOf(
        // 到账
        "收款成功", "已收款", "已收钱", "已到账", "到账成功", "已入账", "收款到账",
        // 退款
        "退款成功", "已退款", "已退还", "已转入",
        // 微信侧
        "已存入零钱", "你领取了",
        // 对方转给我
        "向你转账", "给你转账", "转账给你"
    )

    /**
     * **聊天页专用·支出词**（只有这些才在聊天页放行支出）。
     * 都是"用户此刻确实在付钱"的动作词。
     */
    private val chatExpenseSignals = listOf(
        "你发起了一笔转账", "你发起了转账", "塞钱进红包",
        "请验证指纹", "请验证面容", "请输入支付密码",
        "支付成功", "付款成功", "已支付", "转账成功", "已转账", "已转出"
    )

    /**
     * **聊天页专用·收入词**。
     * ⚠️ 故意**不含**「已被接收」「已被领完」「待确认收款」——
     *    前两个是"我付出去那笔"的状态，第三个钱还没到账。
     */
    private val chatIncomeSignals = listOf(
        "已收款", "收款成功", "已收钱", "收款到账",
        "你领取了", "已存入零钱", "已到账", "已入账",
        "退款成功", "已退款", "已退还",
        "向你转账", "给你转账", "转账给你"
    )

    /** 出现这些词说明这笔没成，直接放弃 */
    private val abortKeywords = listOf(
        "支付失败", "付款失败", "交易失败", "已取消", "交易关闭",
        "待支付", "未支付", "正在处理", "处理中", "订单已失效", "余额不足",
        "红包已过期", "已退还给你"
    )

    /** 整个节点就是一个金额 */
    private val wholeAmountPatterns = listOf(
        Regex("^[￥¥]\\s?([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s?元?$"),
        Regex("^([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s?元$")
    )

    /** 带标签的金额：「支付金额0.01元」「转账金额：￥12」 */
    private val labelledAmount = Regex(
        "(?:支付|付款|转账|收款|扣款|实付|应付|交易|订单|红包)金额" +
            "\\s*[:：]?\\s*[￥¥]?\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元?"
    )

    /** 任意位置的金额。**只用来定位"金额在第几个节点"**，绝不用它取值 */
    private val anyAmount = Regex("[￥¥]\\s?[0-9]{1,7}(?:\\.[0-9]{1,2})?")

    /**
     * 带标签的商户名（对方名字），最可靠。
     * ⚠️ 取值用 `[^\s]{2,24}` 而不是 `.{2,24}`：后者会把标签后面的**下一个界面文案**
     * 一起吞进来（实测「收款方：罗森便利店 完成」→ 取成「罗森便利店 完成」，
     * 再被"完成"这个噪声词一票否决，整条规则白写）。
     */
    private val labelledMerchant = listOf(
        // 红包：对方发给我的
        Regex("你领取了\\s*([^\\s]{1,16}?)\\s*的红包"),
        // 对方转给我
        Regex("([^\\s]{2,16})\\s*向你转账"),
        // 通用标签
        Regex("(?:收款方|收款人|商户全称|商户名称|店铺名称|付款给|支付给|转账给|对方账号)[:：]?\\s*([^\\s]{2,24})"),
        // 我转给对方
        Regex("向\\s*([^\\s]{2,24}?)\\s*(?:付款|支付|转账)")
    )

    /**
     * 界面固定文案，不能当商户名。
     * ⚠️ 匹配方式是 contains，所以这里每一条都要"足够具体"，
     * 别写会误伤正常店名的短词。
     */
    private val noise = listOf(
        // 结果 / 状态文案
        "支付成功", "付款成功", "已支付", "付款已成功", "交易成功", "扣款成功",
        "转账成功", "已转账", "已转出", "收款成功", "已收款", "已收钱", "已存入",
        "已入账", "退款成功", "已退款", "已退还", "已转入", "已到账", "到账成功",
        "已被接收", "已被领完", "待确认收款", "已领取",
        // 表单标签
        "收款方", "收款人", "付款方", "付款方式", "支付方式",
        "支付金额", "付款金额", "转账金额", "收款金额", "订单金额", "单个金额", "总金额",
        "当前状态", "状态", "时间", "金额", "备注", "手续费", "币种", "商品", "说明",
        "支付渠道", "渠道说明", "剩余免费额度", "免费额度", "可切换银行卡", "切换提现方式",
        "提现金额", "提现", "充值", "转账说明", "清空", "收起键盘", "重新输入",
        // 通道 / 资金名词
        "零钱", "余额", "银行卡", "信用卡", "储蓄卡", "钱包", "花呗", "余额宝", "理财",
        "零钱通", "信用卡还款", "卡包", "出行", "医疗健康", "扫一扫", "收付款",
        // 界面动作
        "完成", "返回", "关闭", "确定", "知道了", "取消", "继续", "更多操作", "更多信息",
        "查看更多", "详情", "查看详情", "查看账单", "账单详情", "账单", "明细", "订单",
        // 微信特有
        "微信支付", "微信红包", "微信转账", "发红包", "红包封面", "添加表情", "塞钱进红包",
        "浮窗", "退出浮窗", "聊天信息", "聊天", "转账", "付款", "红包",
        "请验证指纹", "请验证面容", "请输入支付密码", "请输入金额", "添加备注",
        // 导航 / 列表
        "首页", "我的", "消息", "通讯录", "搜索", "最近", "听一听", "音乐", "播放",
        "最近播放", "最近使用的小程序", "扫码签到", "添加朋友", "新的朋友", "群聊",
        "加好友", "可能认识的人", "网关", "已选中", "未选中",
        // 加载态
        "正在加载", "加载中", "正在校验", "校验中", "处理中"
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

        // 第 1 道闸门：方向必须明确。
        //   聊天页用专用词表（只认高置信方向词），普通页用完整词表。
        val isChat = items.count { it.endsWith(AVATAR_SUFFIX) } >= CHAT_AVATAR_THRESHOLD
        val isExpense = if (isChat) {
            val e = chatExpenseSignals.any { joined.contains(it) }
            val i = chatIncomeSignals.any { joined.contains(it) }
            when {
                e && !i -> true      // 明确是我在付钱
                i && !e -> false     // 明确是对方给我
                else -> return null  // 都没命中 / 两边都命中（含糊）→ 宁可不记
            }
        } else {
            when {
                expenseSignals.any { joined.contains(it) } -> true
                incomeSignals.any { joined.contains(it) } -> false
                else -> return null
            }
        }

        // 第 2 道闸门：金额必须来自"独立金额节点"或"带标签的金额"
        val amountCents = extractAmountCents(items, joined) ?: return null
        if (amountCents <= 0L) return null

        // 第 3 道闸门：商户名（对方名字）与备注
        val merchant = extractMerchant(items, joined, source).ifBlank { Source.fullLabel(source) }
        val scene = detectScene(joined)
        val channel = extractChannel(items)
        val note = buildNote(isExpense, merchant, scene, channel)
        val guessed = Category.guess(merchant, isExpense)

        return TransactionRecord(
            amountCents = amountCents,
            isExpense = isExpense,
            category = guessed,
            source = source,
            merchant = merchant,
            note = note,
            rawText = joined.take(500),
            // 方向和金额都确定了，这笔就成立。
            // 分类猜不准只是分类不准，不该因此让用户以为"需要手动确认"。
            status = TransactionRecord.STATUS_CONFIRMED,
            occurredAt = now,
        )
    }

    /**
     * 给诊断用：这一屏文本是靠哪个词判出方向的。
     *
     * 存在的唯一理由 —— 万一以后又出现「方向判反」，日志里能直接看到是哪个词干的，
     * 不用再靠猜。首次实测就是缺了这个信息，才只能反推。
     */
    fun matchedSignal(texts: List<String>): String {
        val items = texts.map {
            it.trim().replace('\u00A0', ' ').removePrefix(DESC_PREFIX)
        }.filter { it.isNotEmpty() }
        val joined = items.joinToString(" ")
        val isChat = items.count { it.endsWith(AVATAR_SUFFIX) } >= CHAT_AVATAR_THRESHOLD

        if (isChat) {
            chatExpenseSignals.firstOrNull { joined.contains(it) }
                ?.let { return "聊天页·支出词「" + it + "」" }
            chatIncomeSignals.firstOrNull { joined.contains(it) }
                ?.let { return "聊天页·收入词「" + it + "」" }
            return "聊天页·无明确方向词"
        }
        expenseSignals.firstOrNull { joined.contains(it) }
            ?.let { return "支出信号「" + it + "」" }
        incomeSignals.firstOrNull { joined.contains(it) }
            ?.let { return "收入信号「" + it + "」" }
        return "无方向信号"
    }

    /** 转账 / 红包 / 普通支付 —— 决定备注怎么写 */
    private fun detectScene(joined: String): String = when {
        joined.contains("红包") -> SCENE_RED_PACKET
        joined.contains("转账") -> SCENE_TRANSFER
        else -> SCENE_PAYMENT
    }

    /**
     * 备注：把「谁给谁 + 对方是谁 + 走的哪张卡」写清楚。
     * 例：「转给 五十夜的秋(张涵) · 工商银行储蓄卡(2556)」「收到 苏格拉没有底 的红包」
     */
    private fun buildNote(
        isExpense: Boolean,
        merchant: String,
        scene: String,
        channel: String,
    ): String {
        val named = merchant.isNotBlank() &&
            merchant != Source.fullLabel(Source.WECHAT) &&
            merchant != Source.fullLabel(Source.ALIPAY) &&
            merchant != "微信红包"
        val core = when {
            scene == SCENE_RED_PACKET && isExpense -> "发红包"
            scene == SCENE_RED_PACKET -> if (named) "收到 " + merchant + " 的红包" else "收到红包"
            scene == SCENE_TRANSFER && isExpense -> if (named) "转给 " + merchant else "转账支出"
            scene == SCENE_TRANSFER -> if (named) "收到 " + merchant + " 的转账" else "收到一笔转账"
            isExpense -> if (named) "付给 " + merchant else "自动记账"
            else -> if (named) "收款 · " + merchant else "自动收款"
        }
        return if (channel.isNotBlank()) core + " · " + channel else core
    }

    /**
     * 取值顺序：先扫**独立金额节点**（整节点就是一个金额），再退到带标签的金额。
     * 两者都拿不到就返回 null —— 这正是把"长句子里含金额"挡在门外的关键。
     */
    private fun extractAmountCents(items: List<String>, joined: String): Long? {
        for (item in items) {
            for (pattern in wholeAmountPatterns) {
                val raw = pattern.find(item)?.groupValues?.getOrNull(1) ?: continue
                val cents = Money.parseToCents(raw)
                if (cents != null && cents > 0L) return cents
            }
        }
        val raw = labelledAmount.find(joined)?.groupValues?.getOrNull(1) ?: return null
        val cents = Money.parseToCents(raw)
        return if (cents != null && cents > 0L) cents else null
    }

    /**
     * 支付渠道。实测节点形如
     * `已选中,工商银行储蓄卡,尾号(2556),支付渠道说明内容: 银行卡渠道`。
     * 只认带「已选中」的那个 —— 「未选中」的是备选项，不能当实际扣款渠道。
     */
    private fun extractChannel(items: List<String>): String {
        for (item in items) {
            if (!item.contains("已选中")) continue
            val name = selectedChannel.find(item)?.groupValues?.getOrNull(1)?.trim() ?: continue
            // 不能用 noise 过滤：渠道名本身就含「储蓄卡」「银行卡」这类词，会被误杀
            if (name.length !in 2..16) continue
            if (name.any { it.isDigit() }) continue
            val tail = tailNumber.find(item)?.groupValues?.getOrNull(1)
            return if (tail != null) name + "(" + tail + ")" else name
        }
        return ""
    }

    private fun extractMerchant(items: List<String>, joined: String, source: String): String {
        // 1) 带标签的对方名（最可靠）
        for (pattern in labelledMerchant) {
            val value = pattern.find(joined)?.groupValues?.getOrNull(1) ?: continue
            tidy(value, allowDigit = true)?.let { return it }
        }

        // 2) 微信的头像节点：剥掉「头像」两字就是对方昵称。
        //    实测：红包确认页和转账气泡里都能看到这个节点，比"猜邻居"准得多。
        for (item in items) {
            if (!item.endsWith(AVATAR_SUFFIX)) continue
            tidy(item, allowDigit = false)?.let { return it }
        }

        // 3) 金额节点前后各最多 3 个节点里，第一个干净的
        val amountIndex = items.indexOfFirst { anyAmount.containsMatchIn(it) }
        if (amountIndex >= 0) {
            for (i in amountIndex - 1 downTo maxOf(0, amountIndex - 3)) {
                tidy(items[i], allowDigit = false)?.let { return it }
            }
            for (i in amountIndex + 1..minOf(items.lastIndex, amountIndex + 3)) {
                tidy(items[i], allowDigit = false)?.let { return it }
            }
        }

        // 4) 微信红包页面确实没有名字，给个说得过去的名字，别让用户看到空白
        if (source == Source.WECHAT && joined.contains("红包")) return "微信红包"

        return ""
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
        if (s.length > 2 && s.endsWith(AVATAR_SUFFIX)) s = s.dropLast(AVATAR_SUFFIX.length).trim()

        if (s.length !in 2..24) return null
        if (anyAmount.containsMatchIn(s)) return null
        if (s.any { it.isDigit() } && (!allowDigit || Regex("[0-9]{2,}").containsMatchIn(s))) return null
        if (s.none { it.code in 0x4E00..0x9FFF || it.isLetter() }) return null
        if (noise.any { s.contains(it) }) return null
        return s
    }

    /** 「已选中,工商银行储蓄卡,尾号(2556),…」里取渠道名 */
    private val selectedChannel = Regex("已选中[，,]([^，,]{2,16})")

    /** 卡号后四位 */
    private val tailNumber = Regex("尾号[（(]?(\\d{4})")
}
