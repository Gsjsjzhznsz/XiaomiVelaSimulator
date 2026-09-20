package com.vela.simulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vela.simulator.engine.QemuRuntime
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.theme.VelaSurface

/** 设置：QEMU 运行时管理、镜像下载源、关于 */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val runtime by vm.runtimeState.collectAsState()
    var mirror by remember { mutableStateOf("official") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))

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
                        selected = mirror == "official",
                        onClick = { mirror = "official" },
                        label = { Text("官方源") },
                    )
                    FilterChip(
                        selected = mirror == "tuna",
                        onClick = { mirror = "tuna" },
                        label = { Text("清华 TUNA 镜像") },
                    )
                }
                Text(
                    if (mirror == "official") QemuRuntime.REPO_OFFICIAL else QemuRuntime.REPO_TUNA,
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(12.dp))
                Text("系统镜像下载源", style = MaterialTheme.typography.titleMedium)
                Text(
                    "默认清单指向本仓库 Release 中托管、基于 openvela 官方源码构建的 QEMU 镜像；" +
                        "国内网络可配合 ghproxy 等加速前缀使用，或直接导入本地 .elf 镜像。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 关于
        Card(colors = CardDefaults.cardColors(containerColor = VelaSurface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Xiaomi VELA Simulator v0.1.0\n" +
                        "基于 openvela（小米 VELA 开源版，Apache-2.0）与 QEMU。\n" +
                        "本应用为社区学习工具，与 Xiaomi 无隶属或背书关系；" +
                        "设备模板参数为公开资料整理的可编辑预设。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
