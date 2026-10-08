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

    // ------------------------------------------------------------------ 自动归类

    /**
     * 自动归类：拿「商户名 + 商品名」去词库里找最匹配的一类。
     *
     * 匹配方式是 `contains`，所以词条必须**够具体**：
     * 写「面」会命中「面膜」，写「药」没问题，写「票」会命中「发票」。拿不准就写长一点。
     */
    fun guess(merchant: String, product: String, isExpense: Boolean): String {
        val text = (merchant + " " + product).lowercase().trim()
        if (text.isBlank()) return if (isExpense) OTHER else INCOME_OTHER
        if (!isExpense) return guessIncome(text)

        // 这几类是「钱换了个地方放」或者「压根看不出买什么」，硬猜只会猜错
        if (neutralWords.any { text.contains(it) }) return OTHER

        for ((key, words) in expenseRules) {
            if (words.any { text.contains(it) }) return key
        }
        return OTHER
    }

    /** 旧签名：只有商户名、没有商品名时用 */
    fun guess(merchant: String, isExpense: Boolean): String = guess(merchant, "", isExpense)

    private fun guessIncome(text: String): String = when {
        text.contains("退款") || text.contains("退货") || text.contains("返还") -> REFUND
        text.contains("工资") || text.contains("薪资") || text.contains("薪酬") -> SALARY
        text.contains("兼职") || text.contains("稿费") || text.contains("劳务") ||
            text.contains("家教") || text.contains("实习") -> PART_TIME
        else -> INCOME_OTHER
    }

    /** 中性词：命中就直接归「其他」，不做行业猜测 */
    private val neutralWords = listOf(
        "微信支付", "支付宝", "零钱通", "零钱", "余额宝", "花呗", "借呗", "备用金",
        "信用卡还款", "还款", "白条", "提现", "理财", "基金", "股票", "转账", "红包",
        "亲属卡", "亲情卡", "余额", "账户", "充值中心", "转出", "转入"
    )

    /** 关键词按「先具体、后宽泛」排列，前面的先命中 */
    private val expenseRules: List<Pair<String, List<String>>> = listOf(
        DINING to listOf(
            "餐", "饭", "food", "烧烤", "烤肉", "火锅", "米线", "米粉", "拉面", "牛肉面",
            "面馆", "小吃", "早餐", "早点", "午餐", "晚餐", "夜宵", "食堂", "餐厅", "酒楼",
            "饭店", "菜馆", "川菜", "湘菜", "粤菜", "东北菜", "串串", "麻辣烫", "冒菜",
            "酸菜鱼", "黄焖鸡", "鸡公煲", "汉堡", "披萨", "寿司", "料理", "炸鸡", "奶茶",
            "咖啡", "茶饮", "甜品", "蛋糕", "烘焙", "面包", "冰淇淋", "冰激凌", "零食",
            "水果", "生鲜", "菜市场", "菜场", "买菜", "外卖", "美团", "饿了么",
            "麦当劳", "肯德基", "kfc", "必胜客", "汉堡王", "德克士", "华莱士", "塔斯汀",
            "星巴克", "瑞幸", "库迪", "喜茶", "奈雪", "蜜雪冰城", "蜜雪", "茶百道", "古茗",
            "沪上阿姨", "书亦", "益禾堂", "甜啦啦", "一点点", "coco", "老乡鸡", "乡村基",
            "真功夫", "吉野家", "萨莉亚", "西贝", "海底捞", "呷哺", "小龙坎", "外婆家",
            "南城香", "杨国福", "张亮", "和府捞面", "遇见小面", "大米先生", "便利店食品"
        ),
        TRANSIT to listOf(
            "地铁", "公交", "轨道", "巴士", "轻轨", "有轨电车", "轮渡", "船票", "打车",
            "出租", "网约车", "滴滴", "花小猪", "曹操", "t3出行", "高德打车", "首汽",
            "哈啰", "青桔", "共享单车", "单车", "骑行", "高铁", "火车", "铁路", "12306",
            "机票", "航空", "机场", "航班", "加油", "中石化", "中石油", "中国石化",
            "中国石油", "壳牌", "充电桩",
            "特来电", "星星充电", "停车", "高速", "etc", "过路费", "车费", "班车",
            "交通卡", "武汉通", "亿通行", "羊城通", "交通联合", "车票", "火车票", "汽车票"
        ),
        MEDICAL to listOf(
            "医院", "医药", "药房", "大药房", "药店", "诊所", "门诊", "挂号", "体检",
            "口腔", "牙科", "眼科", "中医", "针灸", "康复", "疫苗", "社区卫生", "防疫",
            "药品", "医疗", "药", "爱尔眼科", "老百姓大药房", "益丰", "一心堂", "叮当",
            "京东健康", "阿里健康", "平安好医生", "微医"
        ),
        HOUSING to listOf(
            "房租", "租金", "物业", "水费", "电费", "燃气", "天然气", "暖气", "取暖",
            "宽带", "网费", "国家电网", "自来水", "供水", "房贷", "公积金", "装修",
            "维修", "家政", "保洁", "搬家", "垃圾处理", "住房", "宿舍", "链家", "自如",
            "贝壳", "中介费"
        ),
        ENTERTAINMENT to listOf(
            "电影", "影城", "影院", "万达", "cgv", "大地影院", "ktv", "唱歌", "酒吧",
            "livehouse", "剧院", "演出", "演唱会", "景区", "旅游", "旅行社", "酒店",
            "民宿", "宾馆", "携程", "去哪儿", "飞猪", "同程", "马蜂窝", "游戏", "steam",
            "网吧", "剧本杀", "密室", "桌游", "棋牌", "台球", "彩票", "体彩", "福彩",
            "健身", "游泳", "体育馆", "球馆", "羽毛球", "瑜伽", "舞蹈", "按摩", "足浴",
            "洗浴", "美容", "美发", "理发", "剪发", "salon", "宠物", "会员",
            "爱奇艺", "腾讯视频", "优酷", "芒果tv", "哔哩哔哩", "b站", "网易云",
            "qq音乐", "spotify", "方特", "迪士尼", "环球影城", "欢乐谷", "乐园"
        ),
        SHOPPING to listOf(
            "超市", "便利店", "商城", "百货", "购物", "专卖店", "旗舰店", "淘宝", "天猫",
            "京东", "拼多多", "唯品会", "苏宁", "国美", "小红书", "得物", "抖音商城",
            "闲鱼", "转转", "山姆", "沃尔玛", "家乐福", "大润发", "永辉", "盒马", "罗森",
            "全家", "711", "美宜佳", "便利蜂", "名创优品", "无印良品", "muji", "宜家",
            "迪卡侬", "优衣库", "zara", "h&m", "耐克", "nike", "阿迪达斯", "adidas",
            "李宁", "安踏", "斐乐", "彪马", "服饰", "服装", "鞋", "箱包", "化妆品",
            "美妆", "屈臣氏", "丝芙兰", "护肤", "面膜", "洗发", "眼镜", "珠宝", "周大福",
            "老凤祥", "京东到家", "天猫超市", "小米", "华为", "苹果", "apple", "数码",
            "电子", "家电", "键盘", "鼠标", "耳机", "充电宝", "手机配件"
        ),
        DAILY to listOf(
            "日用", "洗护", "清洁", "纸巾", "纸品", "洗涤", "洗衣", "干洗", "快递",
            "寄件", "顺丰", "中通", "圆通", "申通", "韵达", "德邦", "邮政", "ems",
            "菜鸟", "驿站", "丰巢", "打印", "复印", "文印", "文具", "书店", "图书",
            "当当", "话费", "流量", "缴费", "中国移动", "中国联通", "中国电信"
        )
    )
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
