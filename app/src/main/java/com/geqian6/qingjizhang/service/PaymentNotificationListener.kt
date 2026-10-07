package com.geqian6.qingjizhang.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.geqian6.qingjizhang.util.RecordSink
import com.geqian6.qingjizhang.util.Source

/**
 * 自动记账的**辅助通道**：监听系统通知，从中识别支付流水。
 *
 * 为什么是辅助而不是主力：安卓上没有「查询其他 App 支付记录」的公开接口，
 * 通知使用权是唯一正规途径之一，但**它不够**——
 * 付款成功时界面常常只是在同一个页面里换了内容，既不推通知也不换窗口，
 * 这条通道就什么都收不到。所以真正扛事的是 PaymentAccessibilityService。
 * 两条都开着，覆盖面才够。
 *
 * 已知限制：
 * - 微信对通知内容的第三方读取做过限制，通知里可能不带完整信息 —— 行业性问题，
 *   不是本 App 能解决的，所以手动记账始终是主干功能。
 * - 部分机型在省电策略下会限制后台服务，需要用户手动加白名单。
 */
class PaymentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusBarNotification = sbn ?: return

        val source = sourceOf(statusBarNotification.packageName) ?: return

        val notification: Notification = statusBarNotification.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val combined = listOf(title, subText, text, bigText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val record = PaymentParser.parse(combined, source, System.currentTimeMillis()) ?: return
        // 通道名传进去：判重靠它区分「两条通道看到同一笔」和「用户真的付了第二笔」
        RecordSink.submit(applicationContext, record, RecordSink.CHANNEL_NOTIFY)
    }

    private fun sourceOf(packageName: String?): String? = when (packageName) {
        "com.tencent.mm" -> Source.WECHAT
        "com.eg.android.AlipayGphone" -> Source.ALIPAY
        "com.eg.android.AlipayGphone.samsung" -> Source.ALIPAY
        else -> null
    }
}
