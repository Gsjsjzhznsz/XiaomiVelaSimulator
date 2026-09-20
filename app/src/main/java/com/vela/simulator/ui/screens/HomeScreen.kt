package com.vela.simulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.components.PageScaffold
import com.vela.simulator.ui.components.WatchPreview
import com.vela.simulator.ui.theme.VelaGreen
import com.vela.simulator.ui.theme.VelaOrange
import com.vela.simulator.ui.theme.VelaRed
import com.vela.simulator.ui.theme.VelaSurface
import com.vela.simulator.ui.theme.VelaSurfaceHigh
import com.vela.simulator.ui.theme.VelaTextDim
import com.vela.simulator.util.FileLogger

/** 首页：QEMU 运行时状态 + 设备模板网格（PageScaffold 自带磨砂顶栏） */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    bottomInnerPadding: Dp = 0.dp,
    isActive: Boolean = true,
    onSelect: (String) -> Unit,
) {
    val templates by vm.templateList.collectAsState()
    val runtime by vm.runtimeState.collectAsState()

    // 上次异常退出提示（崩溃 tombstone）
    var lastCrash by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) { lastCrash = FileLogger.lastCrashSummary() }

    PageScaffold(title = "设备", bottomInnerPadding = bottomInnerPadding) { inner ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(inner.calculateTopPadding()))
        Text("Xiaomi VELA 模拟器", style = MaterialTheme.typography.headlineMedium)
        Text(
            "基于 openvela 官方源码与 QEMU 的可穿戴设备系统仿真",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        lastCrash?.let { (fname, cause) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1A1A)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠ 上次异常退出", color = VelaRed, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "忽略",
                            color = VelaTextDim,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    FileLogger.lastCrashFile()?.let { FileLogger.dismissCrash(it) }
                                    lastCrash = null
                                }
                                .padding(4.dp),
                        )
                    }
                    Text(
                        cause,
                        color = VelaTextDim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "详情见设置→诊断日志（$fname）",
                        color = VelaTextDim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // 运行时状态卡片
        Card(
            colors = CardDefaults.cardColors(containerColor = VelaSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().clickable(enabled = !runtime.busy) {
                if (!runtime.installed) vm.installRuntime("auto")
            },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Memory, null,
                    tint = if (runtime.installed) VelaGreen else VelaOrange,
                    modifier = Modifier.size(36.dp),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("QEMU 运行时", style = MaterialTheme.typography.titleMedium)
                    val status = when {
                        runtime.busy -> runtime.stage + (runtime.fileProgress.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                        runtime.installed -> "就绪 · " + runtime.version.take(70)
                        else -> "未安装 · 点击自动下载（Termux 源）"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    if (runtime.busy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = VelaOrange,
                            trackColor = VelaSurfaceHigh,
                        )
                    }
                    runtime.error?.let {
                        Text("错误: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("设备模板（${templates.size}）", style = MaterialTheme.typography.titleMedium)
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(templates, key = { it.first.id + it.second.fileName }) { (t, meta) ->
                TemplateCard(t, meta.source) { onSelect(t.id) }
            }
            item {
                NewTemplateCard { onSelect("__new__") }
            }
            // 底部安全余量：悬浮底栏下方不被遮挡
            item {
                Spacer(Modifier.height(bottomInnerPadding + 12.dp))
            }
        }
    }
    }
}

@Composable
fun TemplateCard(t: DeviceTemplate, source: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VelaSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(110.dp),
                contentAlignment = Alignment.Center,
            ) {
                WatchPreview(t, Modifier.size(96.dp), showTime = false)
            }
            Text(t.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                "${t.screen.width}×${t.screen.height} · ${if (t.screen.isRound) "圆形" else "方形"} · $source",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun NewTemplateCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = VelaTextDim),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(20.dp))
            .background(Color.Transparent).clickable(onClick = onClick),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(32.dp), tint = VelaOrange)
            Text("新建自定义模板", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}
