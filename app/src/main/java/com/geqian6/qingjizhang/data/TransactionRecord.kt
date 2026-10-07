package com.geqian6.qingjizhang.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条流水记录。
 *
 * 设计要点：
 * - 金额一律以「分」为单位存 Long，绝不用浮点。浮点存钱做统计会差几分。
 * - source 区分来源（微信 / 支付宝 / 银行卡 / 现金 / 手动），这是本 App 的核心字段。
 * - rawText 保留捕获到的原始通知文本，解析规则出错时能回溯。
 * - status 区分「已确认」与「待确认」，自动记账抓到但没识别出分类的进待确认。
 */
@Entity(tableName = "transactions")
data class TransactionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 金额，单位：分。恒为正数，方向由 isExpense 决定。 */
    val amountCents: Long,

    /** true = 支出，false = 收入 */
    val isExpense: Boolean,

    /** 分类 key，见 Category.kt */
    val category: String,

    /** 来源 key，见 Source.kt */
    val source: String,

    /** 商户 / 对方名称 */
    val merchant: String = "",

    /** 用户备注 */
    val note: String = "",

    /** 捕获到的原始文本，便于排查解析问题 */
    val rawText: String = "",

    /** confirmed / pending */
    val status: String = STATUS_CONFIRMED,

    /** 发生时间，毫秒时间戳 */
    val occurredAt: Long,
) {
    companion object {
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_PENDING = "pending"
    }
}
