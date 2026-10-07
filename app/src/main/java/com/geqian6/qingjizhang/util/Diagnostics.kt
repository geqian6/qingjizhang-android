package com.geqian6.qingjizhang.util

import android.content.Context
import android.text.format.DateFormat

/**
 * 自动记账诊断记录（内存 + 落盘各一份）。
 *
 * 为什么要专门做这个：
 * 自动记账「没反应」其实是三种完全不同的病，但外表一模一样——
 *   ① 无障碍服务压根没被连上 / 轮询没跑起来
 *   ② 服务活着，但当前前台窗口读不到微信/支付宝（窗口读取被限制）
 *   ③ 窗口读到了，屏幕上也有文本，但解析规则没认出金额
 * 光看「有没有记上一笔」是分不出这三者的，只能靠把过程记下来。
 *
 * 隐私：只记「扫到了什么」，不记输入框内容 —— 无障碍读屏本来也只读文本节点。
 * 这些记录只存在本机，App 没有网络权限，也永远不会上传。
 */
object Diagnostics {

    private const val PREFS_NAME = "qjz_diagnostics"
    private const val KEY_LINES = "lines"

    /** 最多留这么多条，超了丢最老的。
     *  40 条太少：微信付款页是固定页面，一次调试就能刷掉十几条，回头再看已经翻走了。
     *  120 条 × 每条最长 1200 字，最坏情况约 140KB，落在 SharedPreferences 里完全无所谓。 */
    private const val MAX_LINES = 120

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var loaded = false

    /** 记一条。tag 用来分类（服务 / 心跳 / 轮询·微信 …），detail 是内容 */
    fun record(context: Context, tag: String, detail: String) {
        val app = context.applicationContext
        val one = DateFormat.format("MM-dd HH:mm:ss", System.currentTimeMillis()).toString() +
            "｜" + tag + "｜" + detail.replace('\n', ' ').take(1200)
        synchronized(lock) {
            ensureLoaded(app)
            lines.addLast(one)
            while (lines.size > MAX_LINES) lines.removeFirst()
            persist(app)
        }
    }

    /** 按时间顺序返回（最新的在最后） */
    fun snapshot(context: Context): List<String> {
        val app = context.applicationContext
        synchronized(lock) {
            ensureLoaded(app)
            return lines.toList()
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            lines.clear()
            loaded = true
            persist(app)
        }
    }

    private fun ensureLoaded(app: Context) {
        if (loaded) return
        loaded = true
        val raw = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LINES, null) ?: return
        raw.split('\n').forEach { if (it.isNotBlank()) lines.addLast(it) }
    }

    private fun persist(app: Context) {
        runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LINES, lines.joinToString("\n"))
                .apply()
        }
    }
}
