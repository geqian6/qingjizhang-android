package com.geqian6.qingjizhang.util

/**
 * 分类定义。支出与收入各一套，用 key 存进数据库，label 只用于展示。
 * 改文案不影响历史数据。
 */
object Category {

    const val DINING = "dining"
    const val TRANSIT = "transit"
    const val SHOPPING = "shopping"
    const val ENTERTAINMENT = "entertainment"
    const val MEDICAL = "medical"
    const val HOUSING = "housing"
    const val DAILY = "daily"
    const val OTHER = "other"

    const val SALARY = "salary"
    const val PART_TIME = "part_time"
    const val REFUND = "refund"
    const val INCOME_OTHER = "income_other"

    val expenseKeys = listOf(
        DINING, TRANSIT, SHOPPING, ENTERTAINMENT,
        MEDICAL, HOUSING, DAILY, OTHER
    )

    val incomeKeys = listOf(
        SALARY, PART_TIME, REFUND, INCOME_OTHER
    )

    fun keys(isExpense: Boolean): List<String> = if (isExpense) expenseKeys else incomeKeys

    fun label(key: String): String = when (key) {
        DINING -> "餐饮"
        TRANSIT -> "交通"
        SHOPPING -> "购物"
        ENTERTAINMENT -> "娱乐"
        MEDICAL -> "医疗"
        HOUSING -> "住房"
        DAILY -> "日用"
        OTHER -> "其他"
        SALARY -> "工资"
        PART_TIME -> "兼职"
        REFUND -> "退款"
        INCOME_OTHER -> "其他收入"
        else -> "未分类"
    }

    /** 自动记账兜底：从商户名猜分类，猜不中就归入「其他」并由用户补全 */
    fun guess(merchant: String, isExpense: Boolean): String {
        if (!isExpense) return INCOME_OTHER
        val m = merchant
        return when {
            listOf("餐", "饭", "食", "茶", "咖啡", "面", "粉", "烧烤", "火锅", "肯德基", "麦当劳", "星巴克", "瑞幸", "饿了么", "美团")
                .any { m.contains(it) } -> DINING
            listOf("地铁", "公交", "打车", "滴滴", "高铁", "火车", "机票", "加油", "停车", "出行")
                .any { m.contains(it) } -> TRANSIT
            listOf("超市", "便利店", "购物", "商城", "淘宝", "京东", "拼多多", "唯品会", "罗森", "永辉", "盒马")
                .any { m.contains(it) } -> SHOPPING
            listOf("电影", "游戏", "KTV", "音乐", "视频", "会员", "娱乐")
                .any { m.contains(it) } -> ENTERTAINMENT
            listOf("医院", "药", "诊所", "挂号", "体检")
                .any { m.contains(it) } -> MEDICAL
            listOf("房租", "物业", "水费", "电费", "燃气", "宽带")
                .any { m.contains(it) } -> HOUSING
            listOf("日用", "洗护", "纸", "清洁")
                .any { m.contains(it) } -> DAILY
            else -> OTHER
        }
    }
}

/**
 * 来源定义。这是本 App 的核心字段——每笔钱从哪来。
 */
object Source {

    const val WECHAT = "wechat"
    const val ALIPAY = "alipay"
    const val BANK = "bank"
    const val CASH = "cash"
    const val MANUAL = "manual"

    val all = listOf(WECHAT, ALIPAY, BANK, CASH, MANUAL)

    /** 明细页筛选用的短标签 */
    fun label(key: String): String = when (key) {
        WECHAT -> "微信"
        ALIPAY -> "支付宝"
        BANK -> "银行卡"
        CASH -> "现金"
        MANUAL -> "手动"
        else -> "其他"
    }

    /** 流水副标题里的完整说法 */
    fun fullLabel(key: String): String = when (key) {
        WECHAT -> "微信支付"
        ALIPAY -> "支付宝"
        BANK -> "银行"
        CASH -> "现金"
        MANUAL -> "手动录入"
        else -> "未知来源"
    }

    /** 来源品牌色，与设计稿一致 */
    fun colorHex(key: String): String = when (key) {
        WECHAT -> "#07C160"
        ALIPAY -> "#1677FF"
        BANK -> "#8E8E93"
        CASH -> "#C79A00"
        MANUAL -> "#0066CC"
        else -> "#7A7A7A"
    }

    /** 是否属于「能自动抓取」的来源 */
    fun isAuto(key: String): Boolean = key == WECHAT || key == ALIPAY
}
