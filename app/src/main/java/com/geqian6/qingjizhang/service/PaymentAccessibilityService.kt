package com.geqian6.qingjizhang.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
 *
 * 本服务用**两条腿**拿时机，任一条通就能工作：
 *   1. 事件驱动 —— 声明了三种事件类型，系统在窗口/内容变化时推给我们
 *   2. **主动轮询** —— 每 1.2 秒自己读一次当前活动窗口
 * 为什么必须补第 2 条：无障碍事件是「系统愿意推才推」，在内存紧张、页面自绘、
 * 同页换内容等情况下都可能**整批丢失**（成熟 App 的官方 FAQ 也承认这点）。
 *
 * 诊断（每次扫描的所见所闻都落一条，用来区分「窗口没拿到」/「拿到窗口但页面不吐文本」/
 * 「文本拿到了但规则没认出」三种病）：
 *   · 「窗口·xx」  —— 这一屏有几个窗口、各属于谁、根节点下多少子节点
 *   · 「xx·微信｜解析未成｜窗口=n 节点=m 文本=k｜原文 → …」
 *   · 「xx·微信｜空屏｜窗口=n 节点=m 文本=0」—— 窗口在，但一个文本节点都没有
 *
 * ⚠️ 去重规则（踩过坑，别改回去）：
 * 诊断**不能按「文本签名」去重**。微信付款页永远只吐固定的那几条文本（实测只有「微信支付」），
 * 签名永远不变 —— 一旦按签名去重，第二次之后微信的一切就再也不记录了，
 * 用户看到的现象是「明明付款了，诊断里什么也没有」。
 * 所以这里改成：**内容变了立刻记；内容没变也按时间兜底记一条**。
 * 真正防重复入库交给 RecordSink（同来源 + 同金额 90 秒去重）和下面的 changed 判断。
 *
 * 隐私：读取范围被 XML 限定在微信 / 支付宝两个包内，别的 App 一律不读；
 * 文本只在内存里过一遍解析，不入库、不上传（本 App 没有网络权限）。
 */
class PaymentAccessibilityService : AccessibilityService() {

    private var lastContentScanAt = 0L

    /** 按来源记「上一屏内容签名」，用来判断内容有没有变（注意：只用于入库节流，不用于诊断节流） */
    private val lastSignatureBySource = HashMap<String, String>(2)

    /** 按来源记「上一次写诊断的时间」，内容没变时靠它兜底，防止微信这种固定页面被静音 */
    private val lastScanLogAtBySource = HashMap<String, Long>(2)

    private var lastIdleHeartbeatAt = 0L
    private var lastWindowBriefAt = 0L
    private var lastWindowBrief = ""

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

        // 把系统实际发给我们的能力也记下来 —— XML 里写了不等于生效，
        // 尤其 flagRetrieveInteractiveWindows 不生效时 getWindows() 会直接返回空列表。
        val info = serviceInfo
        val flags = info?.flags ?: 0
        val flagNames = buildString {
            if (flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) {
                append("可读窗口 ✓")
            } else {
                append("可读窗口 ✗")
            }
            append(" / ")
            if (flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS != 0) {
                append("含次要视图 ✓")
            } else {
                append("含次要视图 ✗")
            }
        }
        Diagnostics.record(
            this,
            "服务",
            "无障碍服务已连接｜" + flagNames +
                "｜事件类型=" + (info?.eventTypes ?: -1) +
                "｜主动扫描已启动"
        )

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
        val windowList = runCatching { windows ?: emptyList() }.getOrDefault(emptyList())
        val activeRoot = rootInActiveWindow
        val activePkg = activeRoot?.packageName?.toString()
        val activeSrc = sourceOf(activePkg)

        // 极廉价的粗筛：当前活动窗口不是微信/支付宝、又没有可见窗口，直接走人。
        // 这一步是整个轮询能长期开着的关键 —— 正常情况下它每 1.2 秒只花掉一次包名比较。
        if (activeSrc == null && windowList.isEmpty()) {
            // 低频记一条「心跳」，用来判断轮询本身到底有没有在跑：
            // 没有心跳 = 服务没连上；有心跳但看不到微信 = 窗口读取受限。
            val now = System.currentTimeMillis()
            if (now - lastIdleHeartbeatAt >= IDLE_HEARTBEAT_MS) {
                lastIdleHeartbeatAt = now
                Diagnostics.record(
                    this,
                    "心跳",
                    "扫描在运行 · 当前前台=" + (activePkg ?: "读不到") +
                        " · 可见窗口=" + windowList.size
                )
            }
            return
        }

        // 只要和微信/支付宝沾边，就把「这一屏长什么样」记一笔 —— 这是定位问题的第一手材料
        recordWindowBrief(trigger, windowList, activeRoot)

        val scans = gather(windowList, activeRoot)
        if (scans.isEmpty()) return

        for (s in scans) {
            val now = System.currentTimeMillis()
            val label = trigger + "·" + Source.label(s.source)

            val signature = s.texts.joinToString("|").take(600)
            val changed = lastSignatureBySource[s.source] != signature
            val lastLog = lastScanLogAtBySource[s.source] ?: 0L
            val due = now - lastLog >= SCAN_LOG_INTERVAL_MS

            // 内容变了 → 立刻记；内容没变 → 也按时间兜底记一条。
            // （微信付款页签名永远不变，只按签名去重就会把它彻底静音 —— 这个坑踩过。）
            if (!changed && !due) continue
            lastSignatureBySource[s.source] = signature
            lastScanLogAtBySource[s.source] = now

            if (s.texts.isEmpty()) {
                Diagnostics.record(
                    this,
                    "空屏·" + label,
                    "窗口=" + s.windowCount + " 节点=" + s.nodeCount +
                        " 文本=0｜窗口拿到了，但整棵树一个文本节点都没有"
                )
                continue
            }

            val record = PaymentPageParser.parse(s.texts, s.source, now)

            if (record == null) {
                Diagnostics.record(
                    this,
                    label,
                    "解析未成｜窗口=" + s.windowCount + " 节点=" + s.nodeCount +
                        " 文本=" + s.texts.size + "｜原文 → " +
                        s.texts.take(40).joinToString(" ／ ") { it.take(26) }
                )
                continue
            }

            Diagnostics.record(
                this,
                label,
                "认出 ¥" + Money.format(record.amountCents) + " · " + record.merchant +
                    (if (record.isExpense) " · 支出" else " · 收入") +
                    (if (changed) "" else " · 内容未变（跳过入库）")
            )

            // 只有内容变化时才提交入库：同一屏反复扫不该反复写。
            // RecordSink 里还有「同来源 + 同金额 90 秒」的第二道闸。
            if (changed) RecordSink.submit(applicationContext, record)
        }
    }

    /** 把「这一屏有几个窗口、各属于谁、根节点下多少子节点」记一笔（限频，防刷屏） */
    private fun recordWindowBrief(
        trigger: String,
        windowList: List<AccessibilityWindowInfo>,
        activeRoot: AccessibilityNodeInfo?,
    ) {
        val sb = StringBuilder()
        sb.append("窗口数=").append(windowList.size)
        windowList.forEach { w ->
            val r = runCatching { w.root }.getOrNull()
            sb.append(" ｜").append(windowTypeName(w.type)).append('=')
                .append(shortPkg(r?.packageName?.toString()))
                .append("(子=").append(r?.childCount ?: -1).append(')')
        }
        sb.append(" ｜活动窗口=").append(shortPkg(activeRoot?.packageName?.toString()))
            .append("(子=").append(activeRoot?.childCount ?: -1).append(')')

        val brief = sb.toString()
        val now = System.currentTimeMillis()
        // 结构变了立刻记（进微信 / 进付款页那一刻一定留一条）；结构没变按时间兜底
        if (brief == lastWindowBrief && now - lastWindowBriefAt < WINDOW_BRIEF_INTERVAL_MS) return
        lastWindowBrief = brief
        lastWindowBriefAt = now
        Diagnostics.record(this, "窗口·" + trigger, brief)
    }

    /**
     * 按「窗口」抓取文本，并带上这个窗口属于哪个来源（微信 / 支付宝）。
     *
     * 返回的是分组结果而不是一坨文本：这样来源判定和文本抓取用的是同一份依据，
     * 不会出现「事件说这是微信、窗口其实是支付宝」的错配。
     */
    private fun gather(
        windowList: List<AccessibilityWindowInfo>,
        activeRoot: AccessibilityNodeInfo?,
    ): List<SrcScan> {
        val textsBySource = HashMap<String, MutableList<String>>(2)
        val nodesBySource = HashMap<String, Int>(2)
        val windowsBySource = HashMap<String, Int>(2)

        fun visit(root: AccessibilityNodeInfo?, src: String) {
            val texts = textsBySource.getOrPut(src) { ArrayList(64) }
            nodesBySource[src] = (nodesBySource[src] ?: 0) + walk(root, texts, 0)
            windowsBySource[src] = (windowsBySource[src] ?: 0) + 1
        }

        for (w in windowList) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val src = sourceOf(root.packageName?.toString()) ?: continue
            visit(root, src)
        }

        // 某些机型 / 场景下 windows 返回空，退回当前活动窗口
        if (textsBySource.isEmpty() && activeRoot != null) {
            val src = sourceOf(activeRoot.packageName?.toString())
            if (src != null) visit(activeRoot, src)
        }

        return textsBySource.map { (src, texts) ->
            SrcScan(
                source = src,
                texts = texts,
                nodeCount = nodesBySource[src] ?: 0,
                windowCount = windowsBySource[src] ?: 0,
            )
        }
    }

    /**
     * 深度优先收集文本，返回**访问到的节点总数**。
     *
     * 为什么要返回节点数：只报「读到几条文本」分不清两种情况 ——
     * 「整棵树就没几个节点」（选错窗口）和「树很大但都是自绘、不吐文本」（页面屏蔽读屏）。
     * 节点多而文本少 = 后者，方向就得换。
     */
    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int): Int {
        if (node == null || depth > 60) return 0
        var count = 1
        if (out.size < 500) {
            node.text?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(it) }
            node.contentDescription?.toString()?.trim()
                ?.let { if (it.isNotEmpty()) out.add("〔描述〕" + it) }
        }
        val children = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until children) {
            count += walk(node.getChild(i), out, depth + 1)
        }
        return count
    }

    private fun sourceOf(packageName: String?): String? = when (packageName) {
        "com.tencent.mm" -> Source.WECHAT
        "com.eg.android.AlipayGphone" -> Source.ALIPAY
        "com.eg.android.AlipayGphone.samsung" -> Source.ALIPAY
        else -> null
    }

    /** 包名缩短成看得懂的名字，方便直接在诊断面板里读 */
    private fun shortPkg(pkg: String?): String = when (pkg) {
        null -> "读不到"
        "com.tencent.mm" -> "微信"
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.android.systemui" -> "状态栏"
        else -> pkg.substringAfterLast('.')
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "应用窗"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "系统窗"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "无障碍浮层"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "分屏"
        else -> "窗$type"
    }

    private companion object {
        const val TAG = "QJZ-Accessibility"

        /** 内容变化事件的节流间隔 */
        const val CONTENT_SCAN_INTERVAL_MS = 350L

        /** 主动轮询间隔。1.2 秒足够覆盖「付款成功页停留几秒」这个场景 */
        const val POLL_INTERVAL_MS = 1200L

        /** 前台不在微信/支付宝时，多久记一条心跳 */
        const val IDLE_HEARTBEAT_MS = 30_000L

        /** 同一来源「内容没变」时的诊断兜底间隔 —— 微信固定页面靠它才不会被静音 */
        const val SCAN_LOG_INTERVAL_MS = 5_000L

        /** 窗口概览最短记录间隔（在微信/支付宝里才记） */
        const val WINDOW_BRIEF_INTERVAL_MS = 15_000L
    }
}

/** 单个来源这一屏的扫描结果 */
private class SrcScan(
    val source: String,
    val texts: List<String>,
    val nodeCount: Int,
    val windowCount: Int,
)
