package com.geqian6.qingjizhang.util

import android.content.Context
import android.net.Uri
import com.geqian6.qingjizhang.data.AppDatabase
import com.geqian6.qingjizhang.data.TransactionRecord
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * 账单导入：把微信 / 支付宝官方导出的「账单明细」变成流水。
 *
 * 三条设计原则：
 * 1. **按表头名字找列，不按第几列** —— 官方改版加一列，这里的代码不会崩。
 * 2. **宁缺勿错** —— 状态不是「成功」的、收/支是「/」或「不计收支」的中性交易
 *    （提现、还款、充值零钱），一律不记。记错一笔比少记一笔麻烦得多。
 * 3. **导入要和已有的自动记账去重** —— 你导入的账单和手机自动抓的往往是同一笔。
 *
 * 支持的输入（都是官方「用于个人对账」导出的）：
 * - **.csv** —— 早期导出格式，微信是 UTF-8 带 BOM，支付宝老版是 GBK，两种都认。
 * - **.xlsx** —— 新版微信直接给 Excel。纯靠系统自带的 zip + 文本解析，不引入任何第三方库。
 * - **.zip / 压缩包** —— 有些邮件里发的是压缩包，里面装着上面两种之一，也能直接选。
 *
 * 微信：我 → 服务 → 钱包 → 账单 → 右上角 → 常见问题 → 下载账单 → 用于个人对账 → 发邮箱
 * 支付宝：我的 → 账单 → 右上角 → 开具交易流水证明 → 用于个人对账 → 发邮箱
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
        /**
         * 时间列可能有好几个候选，按优先级排好。
         * 支付宝旧版叫「付款时间」，新版（交易流水明细）只有「交易时间」，
         * 有的行「付款时间」还是空的 —— 所以这里存一列不够，得存一串挨个试。
         */
        val timeIndexes: List<Int>,
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

    /** 一次导入的累计结果。csv 只有一份，xlsx/压缩包可能有多张表，所以用累计器。 */
    private class Acc {
        var source = ""
        var imported = 0
        var skipped = 0

        /** 有没有认出过账单表头。没有就说明用户选错文件了。 */
        var found = false
    }

    private class ZipContent(val names: List<String>, val files: Map<String, ByteArray>)

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

        val acc = Acc()
        val error = feed(context, bytes, acc, 0)
        return finish(acc, error)
    }

    /** 把一份文件（可能是 csv / xlsx / 压缩包）喂进去。depth 防止压缩包一层层套娃。 */
    private suspend fun feed(context: Context, bytes: ByteArray, acc: Acc, depth: Int): String? {
        if (depth > 3) return "这个文件里面套了太多层，读不动"

        if (isOle2(bytes)) {
            return "这是老版 Excel 格式（.xls）。用 WPS 或 Excel 打开它，另存为 .xlsx 或 .csv，再导一次"
        }
        if (isZip(bytes)) return feedZip(context, bytes, acc, depth)

        importRows(context, csvRows(bytes), acc)
        return null
    }

    /** 压缩包 / Excel 都是 zip 结构，这里统一处理 */
    private suspend fun feedZip(
        context: Context,
        bytes: ByteArray,
        acc: Acc,
        depth: Int,
    ): String? {
        val content = runCatching { readEntries(bytes) }.getOrNull()
            ?: return "这个压缩包有密码，读不开。请先用手机「文件管理」或电脑上的解压工具把它解开" +
                "（密码就写在微信给你的那条消息里），解开后直接选里面的表格文件"

        if (content.names.isEmpty()) return "这个压缩包里是空的"

        // 情形一：这是 .xlsx —— 解析里面的工作表
        val sheets = content.files.filterKeys { it.startsWith("xl/worksheets/") }
        if (sheets.isNotEmpty()) {
            val shared = content.files["xl/sharedStrings.xml"]
                ?.let { parseSharedStrings(decode(it)) }
                .orEmpty()

            for ((_, data) in sheets.toSortedMap()) {
                importRows(context, parseSheet(decode(data), shared), acc)
            }
            return null
        }

        // 情形二：这是普通压缩包 —— 里面的表格文件挨个试。
        // 支付宝的包里常带「使用说明」之类的文件排在前面，只试第一个会误判成"这不是账单"。
        val candidates = content.names.filter { entry ->
            val ext = entry.substringAfterLast('.', "").lowercase(Locale.ROOT)
            ext == "csv" || ext == "txt" || ext == "xlsx" || ext == "xls"
        }
        if (candidates.isEmpty()) {
            return "这个压缩包里没有表格文件（里面有：" + content.names.take(5).joinToString("、") + "）"
        }

        var lastError: String? = null
        for (name in candidates) {
            val data = content.files[name] ?: continue
            val error = feed(context, data, acc, depth + 1)
            if (error != null) lastError = error
        }
        if (acc.found) return null

        return lastError
            ?: "压缩包里的表格都不像账单明细（里面有：" + content.names.take(5).joinToString("、") + "）"
    }

    private fun finish(acc: Acc, error: String?): Outcome {
        if (error != null) return Outcome(acc.source, acc.imported, acc.skipped, error)

        if (!acc.found) {
            return Outcome(
                "", 0, 0,
                "这个文件里没找到微信或支付宝的账单明细表。请确认导出的是「账单」里的明细表" +
                    "（.csv 或 .xlsx 都行），而不是截图或 PDF"
            )
        }

        return Outcome(
            acc.source,
            acc.imported,
            acc.skipped,
            if (acc.imported == 0) "没有新记录可导入 —— 这些账单应该已经在你的账本里了"
            else "导入完成"
        )
    }

    // ------------------------------------------------------------------ 表格 → 流水

    private suspend fun importRows(context: Context, rows: List<List<String>>, acc: Acc): Boolean {
        // 表头行的特征：既有金额列，又有收支方向列。
        // 注意不能强求「交易对方」—— 支付宝导出时「展示交易对手信息」开关没打开就没有这一列。
        val headerIndex = rows.indexOfFirst { row ->
            if (row.size < 4) return@indexOfFirst false
            val joined = row.joinToString("")
            joined.contains("金额") &&
                (joined.contains("收/支") || joined.contains("收支") ||
                    joined.contains("交易对方") || joined.contains("交易时间"))
        }
        if (headerIndex < 0) return false

        val layout = buildLayout(rows[headerIndex]) ?: return false

        acc.found = true
        if (layout.source.isNotBlank()) acc.source = layout.source

        val dao = AppDatabase.get(context).transactionDao()
        val batch = ArrayList<TransactionRecord>()
        val seen = HashSet<String>()

        for (i in headerIndex + 1 until rows.size) {
            val cells = rows[i]

            // 空行（含 Excel 末尾的空白行）不算「跳过」，别把数字撑大
            if (cells.all { it.isBlank() }) continue

            val row = parseRow(layout, cells)
            if (row == null) {
                acc.skipped++
                continue
            }

            // 同一份文件里出现两次的，只留第一条
            val key = layout.source + "|" + row.amountCents + "|" + (row.occurredAt / 60_000L)
            if (!seen.add(key)) {
                acc.skipped++
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
                acc.skipped++
                continue
            }

            // 支付宝没开「展示交易对手信息」时对方列是空的，用商品说明顶上，免得列表里一片空白
            val merchant = row.merchant.ifBlank { row.product }
            val note = if (row.merchant.isBlank()) "" else row.product

            batch.add(
                TransactionRecord(
                    amountCents = row.amountCents,
                    isExpense = row.isExpense,
                    category = Category.guess(merchant, row.product, row.isExpense),
                    source = layout.source,
                    merchant = merchant,
                    note = note,
                    rawText = "导入自" + Source.fullLabel(layout.source) + "账单",
                    status = TransactionRecord.STATUS_CONFIRMED,
                    occurredAt = row.occurredAt,
                )
            )
        }

        if (batch.isNotEmpty()) dao.insertAll(batch)
        acc.imported += batch.size
        return true
    }

    private fun parseRow(layout: Layout, cells: List<String>): Row? {
        fun cell(index: Int): String =
            if (index in cells.indices) cells[index].trim() else ""

        val direction = cell(layout.directionIndex)

        // 「不计收支」里含「支出」两个字，必须排在前面拦掉，否则提现/还款会被记成支出
        if (direction.contains("不计")) return null

        val isExpense = when {
            direction.contains("支出") -> true
            direction.contains("收入") -> false
            else -> return null      // 「/」= 提现、还款这类中性交易，不记
        }

        if (!isSuccess(cell(layout.statusIndex))) return null

        val cents = parseCents(cell(layout.amountIndex)) ?: return null
        if (cents <= 0L) return null

        // 时间列挨个试：支付宝旧版用「付款时间」，新版用「交易时间」，个别行还可能为空
        var time: Long? = null
        for (index in layout.timeIndexes) {
            time = parseTime(cell(index))
            if (time != null) break
        }
        val at = time ?: return null

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
        if (!cleaned[0].isDigit()) return null     // 全是 "-" 这样的占位符直接放弃
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

        // Excel 里日期也可能被存成一串数字（1899-12-30 起的天数）。2026 年落在 45000 上下。
        val serial = raw.toDoubleOrNull()
        if (serial != null && serial in 20_000.0..80_000.0) return fromExcelSerial(serial)

        return null
    }

    /**
     * Excel 的日期序列号 → 本地时间戳。
     * 序列号本身是「不带时区」的，所以要按 UTC 读出来，再当成当地时间重新组装，
     * 否则会整体偏好几个小时（东八区偏 8 小时）。
     */
    private fun fromExcelSerial(serial: Double): Long? = runCatching {
        val utcMillis = ((serial - 25569.0) * 86_400_000.0).toLong()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = utcMillis

        val local = Calendar.getInstance()
        local.clear()
        local.set(
            utc.get(Calendar.YEAR),
            utc.get(Calendar.MONTH),
            utc.get(Calendar.DAY_OF_MONTH),
            utc.get(Calendar.HOUR_OF_DAY),
            utc.get(Calendar.MINUTE),
            utc.get(Calendar.SECOND)
        )
        local.timeInMillis
    }.getOrNull()

    private fun clean(raw: String): String {
        val value = raw
            .replace('\r', ' ')
            .replace('\n', ' ')      // 支付宝的「备注」列里会带换行，别让它把列表撑破
            .trim()
            .trim('"')
            .trim()
        return when (value) {
            "/", "-", "--", "\\", "" -> ""
            else -> value
        }
    }

    private fun buildLayout(header: List<String>): Layout? {
        val cells = header.map { it.trim().trim('"') }
        if (cells.isEmpty()) return null

        /** 先找「名字完全一样」的列，找不到再退一步找「包含关键字」的，避免误撞近义词 */
        fun find(vararg keys: String): Int {
            for (key in keys) {
                val exact = cells.indexOfFirst { it.equals(key, ignoreCase = true) }
                if (exact >= 0) return exact
            }
            return cells.indexOfFirst { cell -> keys.any { cell.contains(it) } }
        }

        // 只有这两列是硬性要求：金额 + 方向。其余都能缺
        val amountIndex = find("金额（元）", "金额(元)", "金额")
        val directionIndex = find("收/支", "收/支出", "收支类型", "收支")
        if (amountIndex < 0 || directionIndex < 0) return null

        val counterpartyIndex = find("交易对方", "交易对象")
        val productIndex = find("商品说明", "商品名称", "商品", "备注")

        // 支付宝的身份证：这些列名微信从来没有
        val isAlipay = cells.any { cell ->
            cell.contains("商家订单号") || cell.contains("交易订单号") ||
                cell.contains("收/付款方式") || cell.contains("对方账号") ||
                cell.contains("交易创建时间") || cell.contains("交易分类")
        }
        val source = if (isAlipay) Source.ALIPAY else Source.WECHAT

        val timeKeys = if (isAlipay) {
            arrayOf("付款时间", "交易时间", "交易创建时间", "创建时间", "时间")
        } else {
            arrayOf("交易时间", "付款时间", "时间")
        }
        val timeIndexes = timeKeys.map { find(it) }.filter { it >= 0 }.distinct()
        if (timeIndexes.isEmpty()) return null

        return Layout(
            source = source,
            timeIndexes = timeIndexes,
            counterpartyIndex = counterpartyIndex,
            productIndex = productIndex,
            directionIndex = directionIndex,
            amountIndex = amountIndex,
            statusIndex = find("当前状态", "交易状态", "状态"),
        )
    }

    // ------------------------------------------------------------------ CSV

    /**
     * CSV → 二维表。
     * 必须整段扫描、不能简单按 '\n' 切 —— 支付宝的「备注」列里会带换行，
     * 简单切法会把一条记录劈成两行，后面全部错位。
     */
    private fun csvRows(bytes: ByteArray): List<List<String>> {
        val text = decode(bytes)
        val rows = ArrayList<List<String>>()
        val sb = StringBuilder()
        var inQuotes = false

        for (ch in text) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(ch)
                }
                ch == '\r' -> {
                    // 引号内的回车是内容，引号外是 CRLF 的一半，丢掉
                    if (inQuotes) sb.append(ch)
                }
                ch == '\n' -> {
                    if (inQuotes) {
                        sb.append(ch)
                    } else {
                        rows.add(splitCsv(sb.toString()))
                        sb.setLength(0)
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) rows.add(splitCsv(sb.toString()))
        return rows
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

    // ------------------------------------------------------------------ XLSX

    /**
     * 读压缩包。xlsx 本身就是一个 zip，里面是 XML。
     * 只留下真正要用的条目（工作表 + 共享字符串 + 内层表格文件），避免整包解进内存。
     */
    private fun readEntries(bytes: ByteArray): ZipContent {
        val names = ArrayList<String>()
        val files = HashMap<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                names.add(name)

                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val keep = !entry.isDirectory && (
                    name == "xl/sharedStrings.xml" ||
                        name.startsWith("xl/worksheets/") ||
                        ext == "csv" || ext == "txt" || ext == "xlsx" || ext == "xls"
                    )
                if (keep) files[name] = zip.readBytes()

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ZipContent(names, files)
    }

    private val siRe = Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val tRe = Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val rowRe = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    private val cellRe = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val colRe = Regex("\\br=\"([A-Za-z]{1,3})\\d+\"")
    private val typeRe = Regex("\\bt=\"([A-Za-z]+)\"")
    private val vRe = Regex("<v\\b[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)

    /** xl/sharedStrings.xml → 字符串表（单元格里存的是下标） */
    private fun parseSharedStrings(xml: String): List<String> =
        siRe.findAll(xml).map { match ->
            tRe.findAll(match.groupValues[1])
                .joinToString("") { unescape(it.groupValues[1]) }
        }.toList()

    /**
     * xl/worksheets/sheetN.xml → 二维表。
     * 单元格是稀疏的（空单元格直接不写），所以必须按 r="C7" 里的列号回填，
     * 不能按出现顺序数。
     */
    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()

        for (rowMatch in rowRe.findAll(xml)) {
            val placed = ArrayList<Pair<Int, String>>()
            var maxIndex = -1
            var auto = 0

            for (cellMatch in cellRe.findAll(rowMatch.groupValues[1])) {
                val attrs = cellMatch.groupValues[1]
                val body = cellMatch.groupValues[2]

                val col = colRe.find(attrs)?.groupValues?.get(1)?.let { columnIndex(it) } ?: auto
                auto = col + 1

                val type = typeRe.find(attrs)?.groupValues?.get(1) ?: ""
                val value = when (type) {
                    "s" -> vRe.find(body)?.groupValues?.get(1)?.trim()
                        ?.toIntOrNull()
                        ?.let { if (it in shared.indices) shared[it] else "" } ?: ""

                    "inlineStr" -> tRe.findAll(body)
                        .joinToString("") { unescape(it.groupValues[1]) }

                    else -> vRe.find(body)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
                }

                placed.add(col to value)
                if (col > maxIndex) maxIndex = col
            }

            val cells = ArrayList<String>(maxIndex + 1)
            for (i in 0..maxIndex) cells.add("")
            for ((col, value) in placed) if (col in cells.indices) cells[col] = value

            out.add(cells)
        }
        return out
    }

    /** "A" → 0，"AA" → 26 */
    private fun columnIndex(letters: String): Int {
        var n = 0
        for (ch in letters.uppercase(Locale.ROOT)) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }

    private fun unescape(raw: String): String {
        if (!raw.contains('&')) return raw
        return raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    // ------------------------------------------------------------------ 其它

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    /** .xls 老格式（OLE2 复合文档）—— 里面是二进制，不折腾，直接引导用户另存 */
    private fun isOle2(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte()

    /**
     * 微信账单是 UTF-8 带 BOM；支付宝是 GBK（老版）或者 GB18030（新版）。
     * 先按 UTF-8 解，解出替换字符（\uFFFD）就说明猜错了，换 GB18030 再试
     * —— GB18030 是 GBK 的超集，中文基本全认，所以放在 GBK 前面。
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

        for (name in listOf("GB18030", "GBK")) {
            val text = runCatching { String(bytes, Charset.forName(name)) }.getOrNull()
            if (text != null && text.none { it == '\uFFFD' }) return text
        }
        return utf8
    }
}
