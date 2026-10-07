package com.geqian6.qingjizhang.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(record: TransactionRecord): Long

    @Update
    suspend fun update(record: TransactionRecord)

    @Delete
    suspend fun delete(record: TransactionRecord)

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<TransactionRecord>>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    suspend fun getBetween(from: Long, to: Long): List<TransactionRecord>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionRecord>>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE isExpense = 1 AND occurredAt >= :from AND occurredAt < :to")
    suspend fun sumExpense(from: Long, to: Long): Long

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE isExpense = 0 AND occurredAt >= :from AND occurredAt < :to")
    suspend fun sumIncome(from: Long, to: Long): Long

    @Query("SELECT status, COUNT(*) AS cnt FROM transactions WHERE status = 'pending' GROUP BY status")
    fun observePendingCount(): Flow<PendingCount?>

    @Query("SELECT * FROM transactions WHERE status = 'pending' ORDER BY occurredAt DESC")
    fun observePending(): Flow<List<TransactionRecord>>

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'pending'")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun getAll(): List<TransactionRecord>

    /**
     * 去重：同来源、同金额、在 since 之后是否已有记录。
     * 支付通知经常重复推送，靠这个避免同一笔记两遍。
     */
    @Query("SELECT COUNT(*) FROM transactions WHERE source = :source AND amountCents = :amount AND occurredAt > :since")
    suspend fun countRecentDuplicate(source: String, amount: Long, since: Long): Int
}

data class PendingCount(
    val status: String,
    val cnt: Int,
)
