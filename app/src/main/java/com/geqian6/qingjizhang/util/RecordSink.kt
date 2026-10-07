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
 * 所以两条通道都只负责「产出 TransactionRecord」，判重、入库、提醒统一在这里做。
 *
 * ---------------------------------------------------------------------------
 * ⚠️ 判重的核心教训（2026-10-07 用户实测换来的，改这里之前必读）
 *
 * 判重**必须分通道**，不能只看「同来源 + 同金额 + 时间窗」。
 *
 * 踩过的坑：最初按「同来源 + 同金额 + 90 秒（后来拉到 180 秒）」一刀切，
 * 结果把**真实存在的连续小额支付**整片吃掉 ——
 * 用户实测「给同一个朋友连转 3 次 0.01 元，只有第一笔被记上」。
 * 三笔的来源相同、金额相同、间隔几十秒，必然全落在同一个窗口里。
 * 这是判据本身的缺陷，不是参数没调好：**金额相同不代表是同一笔**。
 *
 * 现在的判据：
 *   · 两条通道看同一笔 → 来源同、金额同、**通道不同**、间隔很短 → 判为同一笔
 *   · 同一通道内的连续页面（付款确认页 → 结果页）→ 间隔通常 1~3 秒 → 用很短的窗口挡
 *   · 同一通道内的真实多笔 → 间隔 10 秒以上 → **正常记**，不挡
 */
object RecordSink {

    /** 通道：无障碍读屏（主力） */
    const val CHANNEL_A11Y = "a11y"

    /** 通道：通知监听（辅助） */
    const val CHANNEL_NOTIFY = "notify"

    /**
     * **跨通道**去重窗口。
     * 同一笔支付会被两条通道各看到一次（读屏一次、通知一次）。
     * 来源相同 + 金额相同 + **通道不同** + 落在这个窗口内 → 视为同一笔，丢弃后来者。
     * 2 分钟足够宽松：两条通道看到同一笔的时间差通常在几秒内。
     */
    const val CROSS_CHANNEL_WINDOW_MS = 120_000L

    /**
     * **同通道**去重窗口 —— 故意压得很短。
     *
     * 只用来挡「同一笔的连续页面」：付款确认页出一次、支付结果页再出一次，
     * 金额和来源都一样，间隔通常 1~3 秒。10 秒足够挡住。
     *
     * 为什么不能再长：真实场景里「给同一个人连转 3 次 0.01」是完全正常的操作，
     * 用户实测每次间隔通常在 10 秒以上。窗口一旦拉到几十秒，这些真实流水就被吃掉了。
     *
     * 至于「同一屏反复停留」（轮询每 1.2 秒扫一次），不靠这里挡 ——
     * 由 Service 的**页面签名变化**判断负责，那样才不会误伤。
     */
    const val SAME_CHANNEL_WINDOW_MS = 10_000L

    /**
     * 聊天页专用窗口。
     * 微信的转账/红包气泡会长期挂在聊天记录里，别人每发一条新消息就会重扫一遍，
     * 同一个气泡会被反复看到 —— 所以聊天页上的记录要用超长窗口。
     */
    const val CHAT_DEDUPE_WINDOW_MS = 30L * 60L * 1000L

    private const val CHANNEL_ID = "auto_record"

    /**
     * 内存里最近提交过的记录。
     * 两个 Service 跑在同一个进程里，共享这一份就够，不必每次查库。
     */
    private class Seen(
        val source: String,
        val cents: Long,
        val channel: String,
        val chatPage: Boolean,
        val at: Long,
    )

    private val recent = ArrayDeque<Seen>()
    private val lock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 收到一条自动识别出来的流水。
     *
     * @param channel  [CHANNEL_A11Y] 或 [CHANNEL_NOTIFY]。判重靠它区分「两条通道看到同一笔」
     * @param chatPage 这一屏是不是聊天界面（只有无障碍通道能判断）
     * @return true = 已提交入库；false = 被判为重复丢弃（调用方可以把它写进诊断，便于排查）
     */
    fun submit(
        context: Context,
        record: TransactionRecord,
        channel: String = CHANNEL_NOTIFY,
        chatPage: Boolean = false,
    ): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()

        synchronized(lock) {
            // 超过最长窗口的条目不可能再命中，丢掉防止内存表无限增长
            while (recent.isNotEmpty() && now - recent.first().at > CHAT_DEDUPE_WINDOW_MS) {
                recent.removeFirst()
            }
            val duplicated = recent.any { s ->
                if (s.source != record.source || s.cents != record.amountCents) {
                    return@any false
                }
                val window = when {
                    chatPage || s.chatPage -> CHAT_DEDUPE_WINDOW_MS
                    s.channel == channel -> SAME_CHANNEL_WINDOW_MS
                    else -> CROSS_CHANNEL_WINDOW_MS
                }
                now - s.at < window
            }
            if (duplicated) return false
            recent.addLast(Seen(record.source, record.amountCents, channel, chatPage, now))
        }

        scope.launch {
            runCatching {
                val dao = AppDatabase.get(app).transactionDao()
                // 兜底：进程刚重启时上面的内存表是空的，用库里的记录挡一下「同一瞬间」的重复。
                // 窗口取得和同通道一样短 —— 长窗口正是吃掉连续小额支付的元凶，绝不能用。
                val tooClose = dao.countRecentDuplicate(
                    record.source,
                    record.amountCents,
                    now - SAME_CHANNEL_WINDOW_MS
                )
                if (tooClose > 0) return@runCatching
                dao.insert(record)
                notifyRecorded(app, record)
            }
        }
        return true
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
