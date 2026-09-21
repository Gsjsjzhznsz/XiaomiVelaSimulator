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
    // v0.2.4：改为响应式读取模板列表 —— 自定义模板保存后立即打开不再竞态失败；
    // 仍找不到则温和返回（不再闪退式秒退）
    val templateList by vm.templateList.collectAsState()
    val t = remember(id, templateList) { vm.templateById(id) }
    if (t == null) {
        LaunchedEffect(id) { onBack() }
        return
    }

    val manifest by remember { mutableStateOf(vm.images.loadManifest()) }
    val entry: ImageManager.ImageEntry? = manifest.images.firstOrNull { it.id == t.suggestedImageId }
    val imageState by vm.imageState.collectAsState()
    // v0.2.5 修复“刚点下载就显示已就绪 + 进度条仍在”：
    // ① 下载状态按清单条目 id 作用域隔离，不再跨模板串扰；
    // ② kernel 文件先落盘但 entry 其余文件仍在下载时，不提前宣布“已就绪”
    val busyHere = imageState.busy && imageState.imageId == (entry?.id ?: "")
    val imageError = imageState.error?.takeIf { imageState.imageId == (entry?.id ?: "") }
    // v0.2.7：imageId 变化（下载完成/失败）都会刷新就绪判定（现在含 ELF 魔数 + 最小体积校验）
    val kernelReady = remember(imageState, busyHere) { !busyHere && vm.images.isKernelReady(t.qemu.kernel) }
    val kernelSizeMb = remember(imageState, busyHere) { vm.images.kernelSize(t.qemu.kernel) / 1024.0 / 1024.0 }

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
                                when {
                                    busyHere -> "正在下载镜像…"
                                    // v0.2.7：就绪态显示真实体积，取代此前无总量的累计 KB 计数
                                    kernelReady -> "镜像已就绪 · ${String.format(java.util.Locale.US, "%.1f", kernelSizeMb)}MB"
                                    vm.images.kernelSize(t.qemu.kernel) > 0L ->
                                        "镜像文件异常（${String.format(java.util.Locale.US, "%.1f", kernelSizeMb)}MB，非有效内核），请重新下载"
                                    else -> (entry?.desc ?: "未找到清单条目，可导入本地镜像")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (busyHere) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            color = VelaOrange, trackColor = VelaSurfaceHigh,
                        )
                        Text(imageState.fileProgress.ifBlank { imageState.stage }, style = MaterialTheme.typography.bodySmall)
                    }
                    imageError?.let {
                        Text("错误: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!kernelReady && entry != null) {
                            Button(onClick = { vm.downloadImage(entry) }, enabled = !busyHere) {
                                Text(if (busyHere) "下载中…" else "自动下载")
                            }
                        }
                        // v0.2.7：就绪后仍提供重新下载入口（覆盖文件损坏/需换源场景）
                        if (kernelReady && entry != null) {
                            OutlinedButton(onClick = { vm.downloadImage(entry) }, enabled = !busyHere) {
                                Text("重新下载")
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
                    SpecRow("屏幕", "${t.screen.sizeInch}\" ${t.shapeLabel}屏 · ${t.screen.width}×${t.screen.height} · ${t.screen.dpi}ppi")
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
