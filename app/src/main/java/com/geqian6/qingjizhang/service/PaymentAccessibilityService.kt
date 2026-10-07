package com.geqian6.qingjizhang.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.geqian6.qingjizhang.util.Diagnostics
import com.geqian6.qingjizhang.util.Money
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
 * 本服务用**两条腿**拿时机，任一条通就能工作：
 *   1. 事件驱动 —— 声明了三种事件类型，系统在窗口/内容变化时推给我们
 *   2. **主动轮询** —— 每 1.2 秒自己读一次当前活动窗口
 * 为什么必须补第 2 条：无障碍事件是「系统愿意推才推」，在内存紧张、页面自绘、
 * 同页换内容等情况下都可能**整批丢失**（成熟 App 的官方 FAQ 也承认这点）。
 * 只靠事件 = 把命交给系统。主动轮询不依赖任何事件，代价只是一次极廉价的包名比较。
 *
 * 另外：每次扫描的所见所闻都写进 Diagnostics，出问题时打开「我的」页就能定位卡在哪一环。
 *
 * 隐私：读取范围被 XML 限定在微信 / 支付宝两个包内，别的 App 一律不读；
 * 文本只在内存里过一遍解析，不入库、不上传（本 App 没有网络权限）。
 */
class PaymentAccessibilityService : AccessibilityService() {

    private var lastContentScanAt = 0L

    /** 按来源各记一份「上一屏内容签名」，内容没变就不重复解析 */
    private val lastSignatureBySource = HashMap<String, String>(2)

    /** 按来源记「上一次解析失败日志的时间」，防止失败日志刷屏 */
    private val lastFailLogAtBySource = HashMap<String, Long>(2)

    private var lastIdleHeartbeatAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    /** 主动轮询任务：服务活着就一直跑 */
    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            runCatching { scan("轮询") }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接，自动记账主力通道就绪")
        Diagnostics.record(this, "服务", "无障碍服务已连接，主动扫描已启动")
        polling = true
        handler.removeCallbacks(pollTask)
        handler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        val trigger = when (e.eventType) {
            // 换页：一定扫
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "窗口变化"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "窗口整体"

            // 同页内容变化：这类事件每秒能来几十次，必须节流，否则手机会烫
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentScanAt < CONTENT_SCAN_INTERVAL_MS) return
                lastContentScanAt = now
                "内容变化"
            }

            else -> return
        }

        // 关键：不能用事件上的 packageName 过滤。
        // TYPE_WINDOWS_CHANGED 事件的 packageName 常常是 null，
        // 而「付款成功后同页换内容」恰恰最依赖这个事件——
        // 一旦在这里 return，第三个事件类型就等于白声明，又会退回「只能靠别人捅醒」的老路。
        // 所以来源一律按「窗口自己的包名」判断（在 scan 里做）。
        runCatching { scan(trigger) }
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被系统中断")
        Diagnostics.record(this, "服务", "无障碍服务被系统中断")
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(pollTask)
        Diagnostics.record(this, "服务", "无障碍服务已销毁")
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    /**
     * 一次完整扫描：看当前屏幕 → 归属来源 → 解析 → 入库。
     * 事件和轮询共用这一条路径，避免两套逻辑跑偏。
     */
    private fun scan(trigger: String) {
        val activeRoot = rootInActiveWindow
        val activePkg = activeRoot?.packageName?.toString()
        val windowCount = runCatching { windows?.size ?: 0 }.getOrDefault(0)

        // 极廉价的粗筛：当前活动窗口不是微信/支付宝、又没有可见窗口，直接走人。
        // 这一步是整个轮询能长期开着的关键 —— 正常情况下它每 1.2 秒只花掉一次包名比较。
        if (sourceOf(activePkg) == null && windowCount == 0) {
            // 低频记一条「心跳」，用来判断轮询本身到底有没有在跑：
            // 没有心跳 = 服务没连上；有心跳但看不到微信 = 窗口读取受限。
            val now = System.currentTimeMillis()
            if (now - lastIdleHeartbeatAt >= IDLE_HEARTBEAT_MS) {
                lastIdleHeartbeatAt = now
                Diagnostics.record(
                    this,
                    "心跳",
                    "扫描在运行 · 当前前台=" + (activePkg ?: "读不到") + " · 可见窗口=" + windowCount
                )
            }
            return
        }

        val sources = collectBySource()
        if (sources.isEmpty()) return

        for ((source, items) in sources) {
            if (items.isEmpty()) continue

            // 同一屏内容没变就不重复解析（页面上的倒计时会让签名变化，靠 RecordSink 去重兜住）
            val signature = items.joinToString("|")
            if (lastSignatureBySource[source] == signature) continue
            lastSignatureBySource[source] = signature

            val now = System.currentTimeMillis()
            val record = PaymentPageParser.parse(items, source, now)

            if (record == null) {
                // 解析失败：把屏幕上读到的原文摘一段记下来，这是调规则唯一的依据
                val lastLog = lastFailLogAtBySource[source] ?: 0L
                if (now - lastLog >= FAIL_LOG_INTERVAL_MS) {
                    lastFailLogAtBySource[source] = now
                    Diagnostics.record(
                        this,
                        trigger + "·" + Source.label(source),
                        "读到 " + items.size + " 条文本但没认出金额 → " +
                            items.take(14).joinToString(" ／ ")
                    )
                }
                continue
            }

            Diagnostics.record(
                this,
                trigger + "·" + Source.label(source),
                "认出 ¥" + Money.format(record.amountCents) + " · " + record.merchant +
                    (if (record.isExpense) " · 支出" else " · 收入")
            )
            RecordSink.submit(applicationContext, record)
        }
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

        /** 内容变化事件的节流间隔 */
        const val CONTENT_SCAN_INTERVAL_MS = 350L

        /** 主动轮询间隔。1.2 秒足够覆盖「付款成功页停留几秒」这个场景 */
        const val POLL_INTERVAL_MS = 1200L

        /** 前台不在微信/支付宝时，多久记一条心跳 */
        const val IDLE_HEARTBEAT_MS = 30_000L

        /** 同一来源的「解析失败」日志最短间隔 */
        const val FAIL_LOG_INTERVAL_MS = 4_000L
    }
}
