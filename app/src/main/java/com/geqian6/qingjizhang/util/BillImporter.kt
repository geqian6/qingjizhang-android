package com.geqian6.qingjizhang.util

import android.content.Context
import android.net.Uri
import com.geqian6.qingjizhang.data.AppDatabase
import com.geqian6.qingjizhang.data.TransactionRecord
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 账单导入：把微信 / 支付宝官方导出的「账单明细」CSV 变成流水。
 *
 * 三条设计原则：
 * 1. **按表头名字找列，不按第几列** —— 官方改版加一列，这里的代码不会崩。
 * 2. **宁缺勿错** —— 状态不是「成功」的、收/支是「/」的中性交易（提现、还款、充值零钱），
 *    一律不记。记错一笔比少记一笔麻烦得多。
 * 3. **导入要和已有的自动记账去重** —— 你导入的账单和手机自动抓的往往是同一笔。
 *
 * 支持的导出方式（两家官方的「用于个人对账」）：
 * - 微信：我 → 服务 → 钱包 → 账单 → 右上角 → 常见问题 → 下载账单 → 用于个人对账 → 发邮箱
 * - 支付宝：我的 → 账单 → 右上角 → 开具交易流水证明 → 用于个人对账 → 发邮箱
 * 邮件里拿到的是压缩包，解压出来的 .csv 就是这里要选的文件。
 */
object BillImporter {

    data class Outcome(
        val source: String,
        val imported: Int,
        val skipped: Int,
        val message: String,
    )

    private data class Layout(
        val source: String,
        val timeIndex: Int,
        val counterpartyIndex: Int,
        val productIndex: Int,
        val directionIndex: Int,
        val amountIndex: Int,
        val statusIndex: Int,
    )

    private class Row(
        val occurredAt: Long,
        val isExpense: Boolean,
        val amountCents: Long,
        val merchant: String,
        val product: String,
    )

    /** 状态白名单：只有这些算「这笔钱真的动了」 */
    private val okStates = listOf(
        "成功", "已转账", "已收钱", "已存入零钱", "朋友已收钱", "对方已收钱",
        "已到账", "已退还", "已收款", "收款成功"
    )

    private val badStates = listOf(
        "关闭", "失败", "等待", "未支付", "已撤销", "进行中", "已取消", "退款中"
    )

    private val timeFormats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm"
    )

    // ------------------------------------------------------------------ 入口

    suspend fun importFrom(context: Context, uri: Uri): Outcome {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
        }.getOrNull()

        if (bytes == null || bytes.isEmpty()) {
            return Outcome("", 0, 0, "读不到这个文件的内容，换一个文件再试")
        }

        val text = decode(bytes)
        val lines = text.split('\n').map { it.trimEnd('\r') }
        val headerIndex = lines.indexOfFirst {
            it.contains("交易对方") && it.contains("金额")
        }
        if (headerIndex < 0) {
            return Outcome(
                "", 0, 0,
                "这个文件不像微信或支付宝导出的账单。请确认导出的是「账单明细」的 CSV，而不是截图或 PDF"
            )
        }

        val layout = buildLayout(splitCsv(lines[headerIndex]))
            ?: return Outcome("", 0, 0, "账单表头认不出来，可能官方改了导出格式")

        val dao = AppDatabase.get(context).transactionDao()
        val batch = ArrayList<TransactionRecord>()
        val seen = HashSet<String>()
        var skipped = 0

        for (i in headerIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue

            val parsed = parseRow(layout, splitCsv(line))
            if (parsed == null) {
                skipped++
                continue
            }
            val row: Row = parsed

            // 同一份文件里出现两次的，只留第一条
            val key = layout.source + "|" + row.amountCents + "|" + (row.occurredAt / 60_000L)
            if (!seen.add(key)) {
                skipped++
                continue
            }

            // 和账本里已有的（多半是自动记账抓的同一笔）比一比
            val near = dao.countNear(
                layout.source,
                row.amountCents,
                row.occurredAt - 120_000L,
                row.occurredAt + 120_000L
            )
            if (near > 0) {
                skipped++
                continue
            }

            batch.add(
                TransactionRecord(
                    amountCents = row.amountCents,
                    isExpense = row.isExpense,
                    category = Category.guess(row.merchant, row.product, row.isExpense),
                    source = layout.source,
                    merchant = row.merchant,
                    note = row.product,
                    rawText = "导入自" + Source.fullLabel(layout.source) + "账单",
                    status = TransactionRecord.STATUS_CONFIRMED,
                    occurredAt = row.occurredAt,
                )
            )
        }

        if (batch.isNotEmpty()) dao.insertAll(batch)

        return Outcome(
            layout.source,
            batch.size,
            skipped,
            if (batch.isEmpty()) "没有新记录可导入 —— 这些账单应该已经在你的账本里了"
            else "导入完成"
        )
    }

    // ------------------------------------------------------------------ 解析

    private fun parseRow(layout: Layout, cells: List<String>): Row? {
        fun cell(index: Int): String =
            if (index in cells.indices) cells[index].trim() else ""

        val direction = cell(layout.directionIndex)
        val isExpense = when {
            direction.contains("支出") -> true
            direction.contains("收入") -> false
            else -> return null      // 「/」= 提现、还款这类中性交易，不记
        }

        if (!isSuccess(cell(layout.statusIndex))) return null

        val cents = parseCents(cell(layout.amountIndex)) ?: return null
        if (cents <= 0L) return null

        val at = parseTime(cell(layout.timeIndex)) ?: return null

        return Row(
            occurredAt = at,
            isExpense = isExpense,
            amountCents = cents,
            merchant = clean(cell(layout.counterpartyIndex)),
            product = clean(cell(layout.productIndex)),
        )
    }

    private fun isSuccess(status: String): Boolean {
        if (status.isBlank()) return true          // 有些版本没有状态列，别误杀
        if (badStates.any { status.contains(it) }) return false
        return okStates.any { status.contains(it) }
    }

    private fun parseCents(raw: String): Long? {
        if (raw.isBlank()) return null
        val cleaned = raw
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .replace(" ", "")
            .trim()
        if (cleaned.isEmpty()) return null
        return runCatching {
            BigDecimal(cleaned)
                .multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        }.getOrNull()
    }

    private fun parseTime(raw: String): Long? {
        if (raw.isBlank()) return null
        for (format in timeFormats) {
            val millis = runCatching {
                SimpleDateFormat(format, Locale.CHINA).parse(raw)?.time
            }.getOrNull()
            if (millis != null) return millis
        }
        return null
    }

    private fun clean(raw: String): String {
        val value = raw.trim().trim('"').trim()
        return when (value) {
            "/", "-", "--", "\\", "" -> ""
            else -> value
        }
    }

    private fun buildLayout(header: List<String>): Layout? {
        val cells = header.map { it.trim().trim('"') }
        if (cells.isEmpty()) return null

        fun find(vararg keys: String): Int =
            cells.indexOfFirst { cell -> keys.any { cell.contains(it) } }

        val counterpartyIndex = find("交易对方")
        val amountIndex = find("金额")
        val directionIndex = find("收/支")
        if (counterpartyIndex < 0 || amountIndex < 0 || directionIndex < 0) return null

        val isAlipay = cells.any { it.contains("商家订单号") || it.contains("交易创建时间") }
        val source = if (isAlipay) Source.ALIPAY else Source.WECHAT

        val timeIndex = if (isAlipay) {
            val paid = find("付款时间")
            if (paid >= 0) paid else find("交易创建时间")
        } else {
            find("交易时间")
        }
        if (timeIndex < 0) return null

        return Layout(
            source = source,
            timeIndex = timeIndex,
            counterpartyIndex = counterpartyIndex,
            productIndex = find("商品名称", "商品"),
            directionIndex = directionIndex,
            amountIndex = amountIndex,
            statusIndex = find("当前状态", "交易状态"),
        )
    }

    /** 拆一行 CSV。带引号的字段里会有逗号（微信的「商品」列很常见），不能直接 split(',') */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /**
     * 微信账单是 UTF-8 带 BOM，支付宝老版是 GBK（新版本已改 UTF-8）。
     * 先按 UTF-8 解，解出替换字符就说明猜错了，换 GBK 再来。
     */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charset.forName("UTF-8"))
        }

        val utf8 = String(bytes, Charset.forName("UTF-8"))
        if (utf8.none { it == '\uFFFD' }) return utf8

        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
    }
}
