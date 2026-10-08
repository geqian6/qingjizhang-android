package com.geqian6.qingjizhang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geqian6.qingjizhang.ui.theme.AppColor
import com.geqian6.qingjizhang.util.AutoRecord
import com.geqian6.qingjizhang.util.BudgetStore
import com.geqian6.qingjizhang.util.Diagnostics
import com.geqian6.qingjizhang.util.Source
import kotlinx.coroutines.delay

@Composable
fun MineScreen(
    viewModel: AppViewModel,
    budgetStore: BudgetStore,
    onOpenBudget: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val context = LocalContext.current
    val monthRecords by viewModel.monthRecords.collectAsState()
    val pendingRecords by viewModel.pendingRecords.collectAsState()

    var a11yEnabled by remember { mutableStateOf(AutoRecord.isAccessibilityServiceEnabled(context)) }
    var notifEnabled by remember { mutableStateOf(AutoRecord.isNotificationAccessGranted(context)) }
    var showBankDialog by remember { mutableStateOf(false) }
    var diagLines by remember { mutableStateOf(Diagnostics.snapshot(context)) }

    // 用户去系统设置里授权完要跳回来。这里没有可靠的生命周期回调能抓到那一刻，
    // 而这个页面只在「我的」Tab 显示期间存在，所以就轮询一下 —— 代价可以忽略。
    LaunchedEffect(Unit) {
        while (true) {
            a11yEnabled = AutoRecord.isAccessibilityServiceEnabled(context)
            notifEnabled = AutoRecord.isNotificationAccessGranted(context)
            diagLines = Diagnostics.snapshot(context)
            delay(1200L)
        }
    }

    val granted = a11yEnabled && notifEnabled

    val wechatCount = monthRecords.count { it.source == Source.WECHAT }
    val alipayCount = monthRecords.count { it.source == Source.ALIPAY }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColor.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(title = "我的") }

        // 自动记账：两条通道要分别授权，缺一条就漏账
        item {
            AppCard(padding = 18.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (granted) "自动记账已就绪" else "自动记账还没就绪",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColor.textPrimary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (granted) "两条通道都通了，付款后会自动记一笔"
                            else "下面两项都要打开，少一项就会漏账",
                            fontSize = 12.sp,
                            color = AppColor.textSecondary,
                        )
                    }
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (granted) AppColor.income else AppColor.warning)
                    )
                }

                Spacer(Modifier.height(16.dp))

                PermissionRow(
                    title = "无障碍服务",
                    badge = "主力",
                    hint = if (a11yEnabled) "已开启 · 能读到支付结果页上的金额和商户名"
                    else "未开启 · 这条才是能真正读到金额的通道",
                    ok = a11yEnabled,
                    actionText = if (a11yEnabled) "去检查" else "去开启",
                    onAction = { AutoRecord.openAccessibilitySettings(context) },
                )

                Spacer(Modifier.height(10.dp))

                PermissionRow(
                    title = "通知使用权",
                    badge = "辅助",
                    hint = if (notifEnabled) "已开启 · 能读到支付通知里的文本"
                    else "未开启 · 收不到支付通知",
                    ok = notifEnabled,
                    actionText = if (notifEnabled) "去检查" else "去开启",
                    onAction = { AutoRecord.openNotificationAccessSettings(context) },
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "找不到「无障碍」入口？在系统设置里搜「无障碍」→ 已安装的服务 → " +
                        "找到轻记账 → 打开开关（不是只点一下名字）。",
                    fontSize = 11.sp,
                    color = AppColor.textTertiary,
                )
            }
        }

        // 自动记账诊断：把每次扫描看到了什么摊开写在这里。
        // 自动记账「没反应」有三种完全不同的病因（服务没连上 / 读不到窗口 / 解析没认出金额），
        // 外表都是一样的，只能靠这块记录区分。
        item {
            AppCard(padding = 18.dp) {
                Text(
                    text = "自动记账诊断",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColor.textPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "下面记的是最近扫到的内容。没记上账时，点「复制全部」发给我就能定位卡在哪。",
                    fontSize = 12.sp,
                    color = AppColor.textSecondary,
                )

                Spacer(Modifier.height(12.dp))

                if (diagLines.isEmpty()) {
                    Text(
                        text = "还没有任何记录 —— 说明扫描没跑起来。\n先把无障碍开关关掉再打开一次，然后去付一笔小额试试。",
                        fontSize = 12.sp,
                        color = AppColor.textTertiary,
                    )
                } else {
                    diagLines.reversed().take(12).forEach { line ->
                        Text(
                            text = line,
                            fontSize = 10.sp,
                            color = AppColor.textSecondary,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AppColor.primarySoft)
                            .clickable {
                                runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                    cm.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "轻记账诊断",
                                            diagLines.joinToString("\n")
                                        )
                                    )
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "复制全部",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColor.primary,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AppColor.bg)
                            .clickable {
                                Diagnostics.clear(context)
                                diagLines = emptyList()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "清空",
                            fontSize = 12.sp,
                            color = AppColor.textSecondary,
                        )
                    }
                }
            }
        }

        // 微信
        item {
            SourceRow(
                badgeText = "微",
                badgeColor = AppColor.wechat,
                name = "微信支付",
                status = if (granted) "已连接 · 本月自动记 $wechatCount 笔"
                else "未授权 · 本月自动记 $wechatCount 笔",
                statusColor = if (granted) AppColor.income else AppColor.textSecondary,
                trailing = {
                    Text(
                        text = if (granted) "正常" else "待授权",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (granted) AppColor.income else AppColor.warningText,
                    )
                },
            )
        }

        // 支付宝
        item {
            SourceRow(
                badgeText = "支",
                badgeColor = AppColor.alipay,
                name = "支付宝",
                status = if (granted) "已连接 · 本月自动记 $alipayCount 笔"
                else "未授权 · 本月自动记 $alipayCount 笔",
                statusColor = if (granted) AppColor.income else AppColor.textSecondary,
                trailing = {
                    Text(
                        text = if (granted) "正常" else "待授权",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (granted) AppColor.income else AppColor.warningText,
                    )
                },
            )
        }

        // 银行卡
        item {
            SourceRow(
                badgeText = "银",
                badgeColor = AppColor.bank,
                name = "银行卡",
                status = "无法自动同步 · 需要手动导入或补录",
                statusColor = AppColor.textSecondary,
                trailing = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AppColor.primary)
                            .clickable { showBankDialog = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "了解",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                },
            )
        }

        // 待确认
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(AppColor.card)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (pendingRecords.isEmpty()) "没有待确认的流水"
                        else "${pendingRecords.size} 笔待确认",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColor.textPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (pendingRecords.isEmpty())
                            "自动抓到的流水都已归类"
                        else
                            "自动抓到了，但没识别出分类，点开补全",
                        fontSize = 12.sp,
                        color = AppColor.textSecondary,
                    )
                }
                if (pendingRecords.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AppColor.primarySoft)
                            .clickable { viewModel.confirmAllPending() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = "全部确认",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColor.primary,
                        )
                    }
                }
            }
        }

        // 预算
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(AppColor.card)
                    .clickable { onOpenBudget() }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "预算与超支提醒",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColor.textPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "总预算 ¥" + com.geqian6.qingjizhang.util.Money.format(
                            budgetStore.totalBudgetCents()
                        ),
                        fontSize = 12.sp,
                        color = AppColor.textSecondary,
                    )
                }
                Text("›", fontSize = 18.sp, color = AppColor.textTertiary)
            }
        }

        // 导入历史账单：自动记账只能从装上那天开始，之前的账靠这个补
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(AppColor.card)
                    .clickable { onOpenImport() }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "导入历史账单",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColor.textPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "把微信、支付宝以前月份的账单导进来，补上历史",
                        fontSize = 12.sp,
                        color = AppColor.textSecondary,
                    )
                }
                Text("›", fontSize = 18.sp, color = AppColor.textTertiary)
            }
        }

        // 隐私说明
        item {
            AppCard(padding = 18.dp) {
                Text(
                    text = "数据与隐私",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColor.textPrimary,
                )
                Spacer(Modifier.height(10.dp))
                InfoLine("所有流水只存在这台手机的本地数据库里，不上传任何服务器")
                InfoLine("App 没有申请网络权限，物理上无法联网")
                InfoLine("无障碍服务只读取微信和支付宝，别的应用一律不读")
                InfoLine("不读取密码、聊天内容和输入框内容")
                InfoLine("两项权限随时可以在系统设置里撤销，撤销后自动记账立即停止")
                InfoLine("已保存的历史流水不会因为撤销权限而丢失")
            }
        }

        // 版本
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "轻记账 v0.1.0 · 本地版",
                    fontSize = 12.sp,
                    color = AppColor.textTertiary,
                )
            }
        }
    }

    if (showBankDialog) {
        AlertDialog(
            onDismissRequest = { showBankDialog = false },
            title = { Text("银行卡为什么不能自动记", fontSize = 17.sp) },
            text = {
                Text(
                    text = "各家银行都没有对个人开放的账单接口，只有对企业的「银企直连」。\n\n" +
                        "所以任何号称能自动同步银行卡的 App，要么是让你手动输，要么是在拿你的网银账号密码——后者很危险。\n\n" +
                        "这里的做法是：把银行流水当普通流水手动记一笔，来源选「银行卡」。虽然麻烦，但账是真的。",
                    fontSize = 14.sp,
                    color = AppColor.textPrimary,
                )
            },
            confirmButton = {
                TextButton(onClick = { showBankDialog = false }) {
                    Text("知道了")
                }
            },
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    badge: String,
    hint: String,
    ok: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColor.bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (ok) AppColor.income else AppColor.warning),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (ok) "✓" else "!",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.textPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(AppColor.primarySoft)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(text = badge, fontSize = 10.sp, color = AppColor.primary)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(text = hint, fontSize = 11.sp, color = AppColor.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (ok) AppColor.primarySoft else AppColor.primary)
                .clickable { onAction() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = actionText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (ok) AppColor.primary else androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}

@Composable
private fun SourceRow(
    badgeText: String,
    badgeColor: androidx.compose.ui.graphics.Color,
    name: String,
    status: String,
    statusColor: androidx.compose.ui.graphics.Color,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppColor.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(badgeColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = badgeText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppColor.textPrimary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = status,
                fontSize = 12.sp,
                color = statusColor,
            )
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun InfoLine(text: String) {
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

/** 供外部（如 MainActivity）判断自动记账两条通道是否都已就绪 */
fun isAutoRecordGranted(context: Context): Boolean =
    AutoRecord.isFullyGranted(context)
