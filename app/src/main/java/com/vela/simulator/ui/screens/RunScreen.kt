package com.vela.simulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vela.simulator.engine.QemuSession
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.components.ConsoleView
import com.vela.simulator.ui.components.VncDisplayView
import com.vela.simulator.ui.theme.VelaGreen
import com.vela.simulator.ui.theme.VelaOrange
import com.vela.simulator.ui.theme.VelaRed
import com.vela.simulator.ui.theme.VelaSurface
import com.vela.simulator.ui.theme.VelaSurfaceHigh

/** 运行页：串口 nsh 控制台 + VNC 帧缓冲画面 双视图 */
@Composable
fun RunScreen(vm: MainViewModel, id: String, onBack: () -> Unit) {
    val t = remember(id) { vm.templateById(id) }
    if (t == null) { LaunchedEffect(id) { onBack() }; return }

    val session = vm.sessionState.collectAsState().value
    val state = session?.state?.collectAsState()?.value ?: QemuSession.State.IDLE
    val logs = session?.logLines?.collectAsState()?.value ?: emptyList()
    val serialConnected = session?.console?.isConnected == true
    var tab by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }

    // VNC 位图刷新：帧循环在 IO 线程，UI 侧定时读取
    var vncBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(session, state) {
        if (state == QemuSession.State.RUNNING) {
            while (true) {
                vncBmp = session?.vnc?.framebuffer
                kotlinx.coroutines.delay(100)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val stateText = when (state) {
                    QemuSession.State.BOOTING -> "启动中…"
                    QemuSession.State.RUNNING -> "运行中 · 串口:${session?.ports?.collectAsState()?.value?.serial ?: 0}"
                    QemuSession.State.EXITED -> "QEMU 已退出（代码 ${session?.exitCode?.collectAsState()?.value ?: 0}）"
                    QemuSession.State.FAILED -> "启动失败"
                    else -> "未运行"
                }
                Text(stateText, style = MaterialTheme.typography.bodySmall, color = when (state) {
                    QemuSession.State.RUNNING -> VelaGreen
                    QemuSession.State.FAILED, QemuSession.State.EXITED -> VelaRed
                    else -> Color(0xFF9A9AA6)
                })
            }
            IconButton(onClick = { vm.startSession(t) }, enabled = state != QemuSession.State.RUNNING && state != QemuSession.State.BOOTING) {
                Icon(Icons.Filled.Refresh, "重启")
            }
            IconButton(onClick = { vm.stopSession() }, enabled = state == QemuSession.State.RUNNING || state == QemuSession.State.BOOTING) {
                Icon(Icons.Filled.Stop, "停止", tint = if (state == QemuSession.State.RUNNING) VelaRed else Color(0xFF66666F))
            }
        }

        // 视图切换
        TabRow(selectedTabIndex = tab, containerColor = VelaSurface) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("控制台") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("画面") })
        }

        when (tab) {
            0 -> {
                ConsoleView(logs, serialConnected, Modifier.weight(1f).fillMaxWidth().padding(8.dp))
                // 命令输入行
                Row(
                    Modifier.fillMaxWidth().background(VelaSurface).padding(horizontal = 12.dp, vertical = 6.dp).imePadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("nsh> ", style = MaterialTheme.typography.bodyMedium, color = VelaOrange)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).height(56.dp),
                        placeholder = { Text("help", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardActions = KeyboardActions(onSend = {
                            session?.sendLine(input)
                            input = ""
                        }),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = VelaSurfaceHigh,
                            unfocusedContainerColor = VelaSurfaceHigh,
                            focusedBorderColor = VelaOrange,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        session?.sendLine(input)
                        input = ""
                    }) { Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = VelaOrange) }
                }
            }
            1 -> {
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    VncDisplayView(vncBmp, t, Modifier.fillMaxSize())
                }
                Text(
                    "画面来自 QEMU VNC (Raw 编码)。无显示设备的镜像（如 MPS2 MCU 镜像）为空属正常，请使用控制台。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
