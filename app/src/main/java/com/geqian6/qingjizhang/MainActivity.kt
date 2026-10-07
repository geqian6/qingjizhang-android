package com.geqian6.qingjizhang

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geqian6.qingjizhang.ui.AddScreen
import com.geqian6.qingjizhang.ui.AppTab
import com.geqian6.qingjizhang.ui.AppViewModel
import com.geqian6.qingjizhang.ui.BottomTabBar
import com.geqian6.qingjizhang.ui.BudgetScreen
import com.geqian6.qingjizhang.ui.DetailScreen
import com.geqian6.qingjizhang.ui.HomeScreen
import com.geqian6.qingjizhang.ui.MineScreen
import com.geqian6.qingjizhang.ui.StatsScreen
import com.geqian6.qingjizhang.ui.theme.AppColor
import com.geqian6.qingjizhang.ui.theme.QingJiZhangTheme
import com.geqian6.qingjizhang.util.BudgetStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QingJiZhangTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val viewModel: AppViewModel = viewModel()
    val budgetStore = remember { BudgetStore(context) }

    var tab by remember { mutableStateOf(AppTab.HOME) }
    var showAdd by remember { mutableStateOf(false) }
    var showBudget by remember { mutableStateOf(false) }

    // Android 13+ 的通知权限。只用于「自动记了一笔」的提醒，拒绝也不影响记账本身。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColor.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    showBudget -> BudgetScreen(
                        viewModel = viewModel,
                        budgetStore = budgetStore,
                        onBack = { showBudget = false },
                    )

                    else -> when (tab) {
                        AppTab.HOME -> HomeScreen(
                            viewModel = viewModel,
                            budgetStore = budgetStore,
                            onOpenBudget = { showBudget = true },
                            onOpenDetail = { tab = AppTab.DETAIL },
                        )

                        AppTab.DETAIL -> DetailScreen(viewModel = viewModel)

                        AppTab.STATS -> StatsScreen(
                            viewModel = viewModel,
                            onOpenBudget = { showBudget = true },
                        )

                        AppTab.MINE -> MineScreen(
                            viewModel = viewModel,
                            budgetStore = budgetStore,
                            onOpenBudget = { showBudget = true },
                        )
                    }
                }
            }

            if (!showAdd && !showBudget) {
                BottomTabBar(
                    current = tab,
                    onSelect = { tab = it },
                )
            }
        }

        // 「记一笔」悬浮按钮，只在首页和明细页出现
        if (!showAdd && !showBudget && (tab == AppTab.HOME || tab == AppTab.DETAIL)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 112.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(AppColor.primary)
                    .clickable { showAdd = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                )
            }
        }

        // 记一笔：全屏覆盖
        if (showAdd) {
            AddScreen(
                viewModel = viewModel,
                onClose = { showAdd = false },
            )
        }
    }
}
