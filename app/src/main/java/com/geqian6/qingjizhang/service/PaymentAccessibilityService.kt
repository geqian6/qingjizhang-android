package com.geqian6.qingjizhang.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.geqian6.qingjizhang.util.RecordSink
import com.geqian6.qingjizhang.util.Source

/**
 * 自动记账的**主力通道**：无障碍读屏。
 *
 * 为什么必须有这条腿（这是踩过的坑，不是设计偏好）：
 * 只靠通知监听会漏。微信/支付宝付款成功时，界面往往只是**在同一个页面里换了内容**，
 * 不产生新通知，也不产生「窗口变化」——于是只声明了通知监听的 App 什么都收不到。
 * 实测对照：同一台手机上，另一个 App 一付款就弹窗，而这个只有通知监听的 App 毫无反应；
 * 把那个 App 的无障碍打开后，它才被「顺带唤醒」。这说明支付结果页的文本是拿得到的，
 * 只是通道没搭对。
 *
 * 所以本服务的三个关键点，缺一个就会重演那个坑：
 * 1. 事件类型必须**同时**声明 typeWindowStateChanged + typeWindowContentChanged + typeWindowsChanged
 *    （见 res/xml/accessibility_service_config.xml）
 * 2. flags 必须带 flagRetrieveInteractiveWindows，否则 getWindows() 直接返回空
 * 3. 必须带 flagIncludeNotImportantViews，否则很多动态渲染出来的文本节点读不到
 *
 * 隐私：只读取范围被 XML 限定在微信 / 支付宝两个包内，别的一律不碰；
 * 文本只在内存里过一遍解析，不入库、不上传（本 App 没有网络权限）。
 */
class PaymentAccessibilityService : AccessibilityService() {

    private var lastContentScanAt = 0L

    /** 按来源各记一份「上一屏内容签名」，内容没变就不重复解析 */
    private val lastSignatureBySource = HashMap<String, String>(2)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接，自动记账主力通道就绪")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        when (e.eventType) {
            // 换页：一定扫
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit

            // 同页内容变化：这类事件每秒能来几十次，必须节流，否则手机会烫
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentScanAt < CONTENT_SCAN_INTERVAL_MS) return
                lastContentScanAt = now
            }

            else -> return
        }

        // 关键：不能用事件上的 packageName 过滤。
        // TYPE_WINDOWS_CHANGED 事件的 packageName 常常是 null，
        // 而「付款成功后同页换内容」恰恰最依赖这个事件——
        // 一旦在这里 return，第三个事件类型就等于白声明，又会退回「只能靠别人捅醒」的老路。
        // 所以来源一律按「窗口自己的包名」判断。
        val sources = collectBySource()
        if (sources.isEmpty()) return

        for ((source, items) in sources) {
            if (items.isEmpty()) continue

            // 同一屏内容没变就不重复解析（页面上的倒计时会让签名变化，靠 RecordSink 去重兜住）
            val signature = items.joinToString("|")
            if (lastSignatureBySource[source] == signature) continue
            lastSignatureBySource[source] = signature

            val record = PaymentPageParser.parse(items, source, System.currentTimeMillis()) ?: continue
            RecordSink.submit(applicationContext, record)
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被系统中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "无障碍服务已销毁")
    }

    /**
     * 按「窗口」抓取文本，并带上这个窗口属于哪个来源（微信 / 支付宝）。
     *
     * 返回的是分组结果而不是一坨文本：这样来源判定和文本抓取用的是同一份依据，
     * 不会出现「事件说这是微信、窗口其实是支付宝」的错配。
     */
    private fun collectBySource(): List<Pair<String, List<String>>> {
        val result = ArrayList<Pair<String, List<String>>>(2)

        runCatching {
            val windowList: List<AccessibilityWindowInfo> = windows ?: emptyList()
            for (window in windowList) {
                val root = window.root ?: continue
                val src = sourceOf(root.packageName?.toString()) ?: continue
                val texts = ArrayList<String>(64)
                walk(root, texts, 0)
                if (texts.isNotEmpty()) result.add(src to texts)
            }
        }

        // 某些机型 / 场景下 windows 返回空，退回当前活动窗口
        if (result.isEmpty()) {
            val root = rootInActiveWindow
            val src = sourceOf(root?.packageName?.toString())
            if (root != null && src != null) {
                val texts = ArrayList<String>(64)
                walk(root, texts, 0)
                if (texts.isNotEmpty()) result.add(src to texts)
            }
        }

        return result
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 40 || out.size > 400) return
        node.text?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(it) }
        node.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(it) }
        val count = node.childCount
        for (i in 0 until count) {
            walk(node.getChild(i), out, depth + 1)
        }
    }

    private fun sourceOf(packageName: String?): String? = when (packageName) {
        "com.tencent.mm" -> Source.WECHAT
        "com.eg.android.AlipayGphone" -> Source.ALIPAY
        "com.eg.android.AlipayGphone.samsung" -> Source.ALIPAY
        else -> null
    }

    private companion object {
        const val TAG = "QJZ-Accessibility"
        const val CONTENT_SCAN_INTERVAL_MS = 350L
    }
}
