package com.geqian6.qingjizhang.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 全局色板。与设计稿一一对应，不要在这里之外硬编码颜色。
 *
 * 用色规则：
 * - 蓝色只用于交互（可点的东西）
 * - 红绿只管钱的方向（支出红 / 收入绿）
 * - 橙色只管预算快用完
 */
object AppColor {
    // 骨架
    val bg = Color(0xFFF5F5F7)
    val card = Color(0xFFFFFFFF)
    val track = Color(0xFFF0F0F2)
    val switchTrack = Color(0xFFE9E9EB)
    val divider = Color(0xFFF0F0F0)

    // 文字
    val textPrimary = Color(0xFF1D1D1F)
    val textSecondary = Color(0xFF7A7A7A)
    val textTertiary = Color(0xFFC7C7CC)

    // 语义
    val primary = Color(0xFF0066CC)
    val primaryLight = Color(0xFF4C93E0)
    val primarySoft = Color(0xFFEAF3FE)
    val expense = Color(0xFFE5484D)
    val income = Color(0xFF1E9E5A)
    val warning = Color(0xFFF5A623)
    val warningText = Color(0xFFC97A00)
    val warningBg = Color(0xFFFFF4E5)
    val warningTextDeep = Color(0xFF8A5A00)

    // 来源品牌色
    val wechat = Color(0xFF07C160)
    val alipay = Color(0xFF1677FF)
    val bank = Color(0xFF8E8E93)
    val cash = Color(0xFFC79A00)
    val manual = Color(0xFF0066CC)
}

private val LightColors = lightColorScheme(
    primary = AppColor.primary,
    onPrimary = Color.White,
    background = AppColor.bg,
    onBackground = AppColor.textPrimary,
    surface = AppColor.card,
    onSurface = AppColor.textPrimary,
    surfaceVariant = AppColor.track,
    onSurfaceVariant = AppColor.textSecondary,
)

@Composable
fun QingJiZhangTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
