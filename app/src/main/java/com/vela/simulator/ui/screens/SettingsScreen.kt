package com.vela.simulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.vela.simulator.engine.QemuRuntime
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.components.PageScaffold
import com.vela.simulator.ui.theme.VelaSurface
import com.vela.simulator.util.FileLogger
import java.io.File

/** 设置：主题外观、QEMU 运行时管理、镜像下载源、诊断日志、关于 */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    bottomInnerPadding: Dp = 0.dp,
    isActive: Boolean = true,
    onOpenThemeSettings: () -> Unit,
    onOpenAbout: () -> Unit = {},
) {
    val runtime by vm.runtimeState.collectAsState()
    var mirror by remember { mutableStateOf("auto") }

    PageScaffold(title = "设置", bottomInnerPadding = bottomInnerPadding) { inner ->
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(inner.calculateTopPadding()))
        Spacer(Modifier.height(12.dp))

        // 主题与外观入口（MIUIx）
        Card(
            colors = CardDefaults.cardColors(containerColor = VelaSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenThemeSettings),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🎨", modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("主题与外观", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "主题模式 · 关键色 · 悬浮底栏 · 预测返回 · 动画",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("›", style = MaterialTheme.typography.headlineSmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        // QEMU 运行时
        Card(colors = CardDefaults.cardColors(containerColor = VelaSurface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("QEMU 运行时", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (runtime.installed) "已安装 · " + runtime.version.take(80)
                    else "未安装",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (runtime.busy) {
                    Text(runtime.stage, style = MaterialTheme.typography.bodySmall)
                    Text(runtime.fileProgress, style = MaterialTheme.typography.bodySmall)
                }
                runtime.error?.let {
                    Text("错误: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.installRuntime(mirror) },
                        enabled = !runtime.busy,
                    ) { Text(if (runtime.installed) "重新安装" else "立即安装") }
                    OutlinedButton(onClick = { vm.refreshRuntimeStatus() }) { Text("刷新状态") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 下载源
        Card(colors = CardDefaults.cardColors(containerColor = VelaSurface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("QEMU 运行时下载源（Termux apt 仓库）", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mirror == "auto",
                        onClick = { mirror = "auto" },
                        label = { Text("自动（推荐）") },
                    )
                    FilterChip(
                        selected = mirror == "official",
                        onClick = { mirror = "official" },
                        label = { Text("官方源") },
                    )
                    FilterChip(
                        selected = mirror == "tuna",
                        onClick = { mirror = "tuna" },
                        label = { Text("TUNA") },
                    )
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mirror == "bfsu",
                        onClick = { mirror = "bfsu" },
                        label = { Text("BFSU") },
                    )
                    FilterChip(
                        selected = mirror == "ustc",
                        onClick = { mirror = "ustc" },
                        label = { Text("USTC") },
                    )
                    FilterChip(
                        selected = mirror == "nju",
                        onClick = { mirror = "nju" },
                        label = { Text("NJU") },
                    )
                    FilterChip(
                        selected = mirror == "sjtu",
                        onClick = { mirror = "sjtu" },
                        label = { Text("SJTU") },
                    )
                }
                Text(
                    if (mirror == "auto")
                        "6 个软件源并发竞速，谁最快用谁；下载阶段 4 路并发 + 分段并行，" +
                            "单包失败自动换源重试（境外自动命中官方源，境内自动命中教育网镜像）"
                    else QemuRuntime.repoBase(mirror),
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(12.dp))
                Text("系统镜像下载源", style = MaterialTheme.typography.titleMedium)
                Text(
                    "默认清单指向本仓库 Release 中托管、基于 openvela 官方源码构建的 QEMU 镜像；" +
                        "下载时自动尝试直连并逐个切换 gh-proxy 等加速线路，无需配置；" +
                        "也可直接导入本地 .elf 镜像。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 诊断日志
        Card(colors = CardDefaults.cardColors(containerColor = VelaSurface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("诊断日志", style = MaterialTheme.typography.titleMedium)
                val ctx = LocalContext.current
                var logPath by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    logPath = FileLogger.logFile?.absolutePath ?: "日志目录不可用"
                }
                Text(
                    "应用运行 / QEMU 会话 / 崩溃记录都会写入本地文件，遇到问题可通过“分享日志”导出发给开发者。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    logPath,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val f = FileLogger.logFile
                        if (f == null || !f.exists()) {
                            Toast.makeText(ctx, "日志文件尚未生成", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        runCatching {
                            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(send, "分享诊断日志"))
                        }.onFailure {
                            Toast.makeText(ctx, "分享失败: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("分享日志") }
                    OutlinedButton(onClick = {
                        FileLogger.clear()
                        Toast.makeText(ctx, "日志已清空", Toast.LENGTH_SHORT).show()
                    }) { Text("清空日志") }
                }
                val crash = FileLogger.lastCrashSummary()
                if (crash != null) {
                    Text(
                        "最近崩溃: ${crash.first} · ${crash.second}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 关于入口（v0.2.4：与 BandQQ 同构的独立关于页）
        Card(
            colors = CardDefaults.cardColors(containerColor = VelaSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAbout),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ℹ️", modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "版本 · 作者与联系方式 · 仓库 · 项目简介",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("›", style = MaterialTheme.typography.headlineSmall)
            }
        }

        // 底部安全余量：悬浮底栏下方不被遮挡
        Spacer(Modifier.height(bottomInnerPadding + 16.dp))
    }
    }
}
