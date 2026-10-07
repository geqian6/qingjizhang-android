package com.geqian6.qingjizhang.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

/**
 * 自动记账的两条通道，以及它们的权限检测 / 系统设置跳转。
 *
 * 为什么是两条（这是踩坑换来的结论，不是凑数）：
 * · 无障碍读屏（主力）—— 能读到支付**结果页**上的金额和商户名。付款成功时
 *   往往只是同页换内容，不推通知也不换窗口，只有读屏拿得到。
 * · 通知监听（辅助）—— 能读到支付**通知**里的文本，覆盖读屏漏掉的场景。
 * 两个都要用户手动授权，随时可撤销；少开一个不会崩，但会漏账。
 */
object AutoRecord {

    private const val SERVICE_ACCESSIBILITY =
        "com.geqian6.qingjizhang.service.PaymentAccessibilityService"

    private const val SERVICE_NOTIFICATION =
        "com.geqian6.qingjizhang.service.PaymentNotificationListener"

    // ---------------- 无障碍（主力通道） ----------------

    /** 无障碍服务是否已开启 */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val target = accessibilityComponent(context)

        // 直接读系统设置里的字符串，最可靠
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (!enabled.isNullOrBlank()) {
            val shortName = target.flattenToShortString()
            val fullName = target.flattenToString()
            val hit = enabled.split(':').any { entry ->
                entry.equals(shortName, ignoreCase = true) ||
                    entry.equals(fullName, ignoreCase = true) ||
                    (entry.contains(context.packageName) && entry.contains(SERVICE_ACCESSIBILITY))
            }
            if (hit) return true
        }

        // 少数 ROM 不往字符串里写，用 AccessibilityManager 复核一次
        return runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == SERVICE_ACCESSIBILITY
                }
        }.getOrDefault(false)
    }

    /** 跳到系统的「无障碍」设置页 */
    fun openAccessibilitySettings(context: Context) {
        openSystemPage(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun accessibilityComponent(context: Context): ComponentName =
        ComponentName(context.packageName, SERVICE_ACCESSIBILITY)

    // ---------------- 通知使用权（辅助通道） ----------------

    /** 通知使用权是否已授予本 App */
    fun isNotificationAccessGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        if (enabled.isNullOrBlank()) return false
        val pkg = context.packageName
        return enabled.split(":").any { entry ->
            !TextUtils.isEmpty(entry) && entry.contains(pkg)
        }
    }

    /** 跳到系统的「通知使用权」设置页 */
    fun openNotificationAccessSettings(context: Context) {
        openSystemPage(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** 通知监听服务的完整类名，用于日志排查 */
    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context.packageName, SERVICE_NOTIFICATION)

    // ---------------- 汇总 ----------------

    /** 两条通道都通了才算「自动记账可用」 */
    fun isFullyGranted(context: Context): Boolean =
        isAccessibilityServiceEnabled(context) && isNotificationAccessGranted(context)

    private fun openSystemPage(context: Context, intent: Intent) {
        val primary = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(primary) }.onFailure {
            // 部分定制系统没有这个页面，退回应用详情页
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
