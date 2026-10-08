package com.geqian6.qingjizhang.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geqian6.qingjizhang.ui.theme.AppColor
import com.geqian6.qingjizhang.util.BillImporter
import com.geqian6.qingjizhang.util.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入历史账单。
 *
 * 存在的理由：自动记账只能从装上那一刻开始记，之前几个月的账全是空的。
 * 微信和支付宝都允许导出「用于个人对账」的账单明细（CSV），导进来就把历史补齐了。
 */
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BillImporter.Outcome?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        result = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                BillImporter.importFrom(context, uri)
            }
            result = outcome
            busy = false
        }
    }

    val outcome = result

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColor.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "导入历史账单",
                trailing = {
                    Text(
                        text = "关闭",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColor.primary,
                        modifier = Modifier.clickable { onBack() },
                    )
                },
            )
        }

        item {
            AppCard(padding = 18.dp) {
                Text(
                    text = "为什么需要这个",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColor.textPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "自动记账只能从装上这天开始记。前面几个月的账是空的，" +
                        "统计页自然也就看不到完整的花销。\n" +
                        "把微信、支付宝导出的账单明细导进来，历史就补齐了 —— " +
                        "而且这些账单里带着真实的商家名，归类会比自动抓的准得多。",
                    fontSize = 12.sp,
                    color = AppColor.textSecondary,
                )
            }
        }

        item {
            StepCard(
                badge = "微",
                badgeColor = AppColor.wechat,
                title = "第一步：从微信导出账单",
                steps = listOf(
                    "微信 → 我 → 服务 → 钱包 → 账单",
                    "点右上角「常见问题」→「下载账单」",
                    "用途选「用于个人对账」，再选时间范围",
                    "填你自己的邮箱，提交",
                    "去邮箱收邮件，把附件下载下来",
                ),
                tail = "微信发来的是压缩包，密码会显示在微信页面上。解压出来的 .csv 文件才是要导入的。",
            )
        }

        item {
            StepCard(
                badge = "支",
                badgeColor = AppColor.alipay,
                title = "第二步：从支付宝导出账单",
                steps = listOf(
                    "支付宝 → 我的 → 账单 → 右上角「…」",
                    "选「开具交易流水证明」→ 用途选「用于个人对账」",
                    "选时间范围，填邮箱，提交",
                    "去邮箱下载附件并解压，得到 .csv 文件",
                ),
                tail = "两家导出的文件都能导进来，先导哪家都行，重复的会自动跳过。",
            )
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (busy) AppColor.textTertiary else AppColor.primary)
                    .clickable(enabled = !busy) { picker.launch(arrayOf("*/*")) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (busy) "正在读账单…" else "选择账单文件（.csv）",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }

        item {
            AppCard(padding = 18.dp) {
                Text(
                    text = "导入会做什么、不会做什么",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColor.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                InfoRow("会按商家名自动归类成餐饮 / 交通 / 日用这些")
                InfoRow("已经在账本里的（比如自动记账抓过的）会自动跳过，不会记两遍")
                InfoRow("提现、还信用卡这类「钱只是换了个地方」的记录不导入，免得把支出算多")
                InfoRow("导入只看这份文件，文件读进来后不会被传出去，App 也没有联网权限")
            }
        }

        if (outcome != null) {
            item {
                AppCard(padding = 18.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (outcome.imported > 0) AppColor.income else AppColor.warning
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = if (outcome.imported > 0) "成功" else "没导入",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = outcome.message,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColor.textPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            if (outcome.source.isNotBlank()) {
                                append("来源：").append(Source.fullLabel(outcome.source)).append('\n')
                            }
                            append("新增 ").append(outcome.imported).append(" 笔")
                            if (outcome.skipped > 0) {
                                append(" · 跳过 ").append(outcome.skipped).append(" 笔")
                            }
                        },
                        fontSize = 12.sp,
                        color = AppColor.textSecondary,
                    )
                    if (outcome.imported > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "回「首页」或「明细」就能看到它们了。",
                            fontSize = 12.sp,
                            color = AppColor.primary,
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "提示：如果导入的账里有哪笔分类不对，在「明细」页点那笔，可以直接改分类或者删掉。",
                fontSize = 11.sp,
                color = AppColor.textTertiary,
            )
        }
    }
}

@Composable
private fun StepCard(
    badge: String,
    badgeColor: Color,
    title: String,
    steps: List<String>,
    tail: String,
) {
    AppCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(badgeColor)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColor.textPrimary,
            )
        }
        Spacer(Modifier.height(10.dp))
        steps.forEachIndexed { index, step ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = (index + 1).toString() + ".",
                    fontSize = 12.sp,
                    color = AppColor.textTertiary,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = step,
                    fontSize = 12.sp,
                    color = AppColor.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = tail,
            fontSize = 11.sp,
            color = AppColor.textTertiary,
        )
    }
}

@Composable
private fun InfoRow(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "·",
            fontSize = 13.sp,
            color = AppColor.textTertiary,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = AppColor.textSecondary,
        )
    }
}
