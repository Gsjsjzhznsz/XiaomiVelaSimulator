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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.vela.simulator.engine.ImageManager
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.components.WatchPreview
import com.vela.simulator.ui.theme.VelaGreen
import com.vela.simulator.ui.theme.VelaOrange
import com.vela.simulator.ui.theme.VelaSurface
import com.vela.simulator.ui.theme.VelaSurfaceHigh

/** 模板详情：外观预览 + 全参数规格 + 镜像管理 + 启动入口 */
@Composable
fun TemplateDetailScreen(
    vm: MainViewModel,
    id: String,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = remember(id) { vm.templateById(id) }
    if (t == null) {
        LaunchedEffect(id) { onBack() }
        return
    }

    val manifest by remember { mutableStateOf(vm.images.loadManifest()) }
    val entry: ImageManager.ImageEntry? = manifest.images.firstOrNull { it.id == t.suggestedImageId }
    val imageState by vm.imageState.collectAsState()
    val kernelReady = remember(imageState) { vm.images.isKernelReady(t.qemu.kernel) }

    // 自定义导入
    var importTarget by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val name = importTarget
        if (uri != null && name != null) {
            vm.importImage(uri, name) { }
        }
    }

    Column(modifier.fillMaxSize()) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(t.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "编辑模板") }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            // 外观预览
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                WatchPreview(t, Modifier.size(200.dp))
            }
            Text(
                "${t.brand} · ${t.releaseYear} · ${t.os}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                )
            Spacer(Modifier.height(12.dp))

            // 镜像卡片
            Card(
                colors = CardDefaults.cardColors(containerColor = VelaSurface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (kernelReady) Icons.Filled.CheckCircle else Icons.Filled.Download,
                            null, tint = if (kernelReady) VelaGreen else VelaOrange,
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(entry?.name ?: t.qemu.kernel, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (kernelReady) "镜像已就绪 · ${t.qemu.kernel}"
                                else (entry?.desc ?: "未找到清单条目，可导入本地镜像"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (imageState.busy) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            color = VelaOrange, trackColor = VelaSurfaceHigh,
                        )
                        Text(imageState.fileProgress, style = MaterialTheme.typography.bodySmall)
                    }
                    imageState.error?.let {
                        Text("错误: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!kernelReady && entry != null) {
                            Button(onClick = { vm.downloadImage(entry) }, enabled = !imageState.busy) {
                                Text("自动下载")
                            }
                        }
                        OutlinedButton(onClick = {
                            importTarget = t.qemu.kernel
                            filePicker.launch("*/*")
                        }) { Text("导入本地镜像") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 规格卡
            Card(
                colors = CardDefaults.cardColors(containerColor = VelaSurface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpecRow("屏幕", "${t.screen.sizeInch}\" ${if (t.screen.isRound) "AMOLED 圆形" else "AMOLED 方形"} · ${t.screen.width}×${t.screen.height} · ${t.screen.dpi}ppi")
                    SpecRow("处理器", t.hardware.cpuArch)
                    SpecRow("内存/存储",
                        (t.hardware.ramMb?.let { "${it}MB" } ?: "未公开") + " / " +
                        (t.hardware.storageMb?.let { "${it}MB" } ?: "未公开"))
                    t.hardware.batteryMah?.let { SpecRow("电池", "${it}mAh") }
                    if (t.features.isNotEmpty()) {
                        SpecRow("特性", t.featureLabels.joinToString(" · "))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("仿真参数（QEMU）", style = MaterialTheme.typography.labelLarge, color = VelaOrange)
                    SpecRow("机器", "${t.qemu.machine} · ${t.qemu.cpu} ×${t.qemu.smp}")
                    if (!t.qemu.isCortexM) SpecRow("内存", "${t.qemu.memoryMb}MB")
                    SpecRow("内核", t.qemu.kernel)
                    if (t.qemu.extraArgs.isNotBlank()) SpecRow("附加参数", t.qemu.extraArgs)
                    if (t.note.isNotBlank()) {
                        Text(t.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 启动栏
        Box(
            Modifier.fillMaxWidth().background(VelaSurface).padding(16.dp),
        ) {
            Button(
                onClick = {
                    vm.startSession(t)
                    onRun()
                },
                enabled = kernelReady,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Text("  启动模拟", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9A9AA6), modifier = Modifier.size(width = 88.dp, height = 22.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
