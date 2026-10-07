package com.geqian6.qingjizhang.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.geqian6.qingjizhang.MainActivity
import com.geqian6.qingjizhang.R
import com.geqian6.qingjizhang.data.AppDatabase
import com.geqian6.qingjizhang.data.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自动记账的唯一入库口。
 *
 * 为什么必须收敛到一个口：现在有**两条通道**（无障碍读屏 + 通知监听），
 * 同一笔付款两条都会看到。如果各自往库里写，用户就会看到一笔钱记两遍。
 * 所以两条通道都只负责「产出 TransactionRecord」，入库、去重、提醒统一在这里做。
 */
object RecordSink {

    /** 去重窗口：同来源 + 同金额，这个时间内的第二条丢弃 */
    private const val DEDUPE_WINDOW_MS = 90_000L

    private const val CHANNEL_ID = "auto_record"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 收到一条自动识别出来的流水 */
    fun submit(context: Context, record: TransactionRecord) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val dao = AppDatabase.get(app).transactionDao()
                val duplicated = dao.countRecentDuplicate(
                    record.source,
                    record.amountCents,
                    record.occurredAt - DEDUPE_WINDOW_MS
                )
                if (duplicated == 0) {
                    dao.insert(record)
                    notifyRecorded(app, record)
                }
            }
        }
    }

    /** 记完一笔弹个通知，用户不用打开 App 就知道记上了 */
    private fun notifyRecorded(context: Context, record: TransactionRecord) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "自动记账",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "自动记下一笔时提醒，方便当场核对" }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_ledger)
            .setContentTitle(if (record.isExpense) "已自动记一笔支出" else "已自动记一笔收入")
            .setContentText(
                Source.label(record.source) + " · " + record.merchant +
                    "　¥" + Money.format(record.amountCents)
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Android 13+ 没给通知权限会抛 SecurityException，这里静默跳过，
        // 因为记账本身已经成功了，不该因为发不出通知而失败
        runCatching {
            NotificationManagerCompat.from(context)
                .notify((record.occurredAt % Int.MAX_VALUE).toInt(), notification)
        }
    }
}
