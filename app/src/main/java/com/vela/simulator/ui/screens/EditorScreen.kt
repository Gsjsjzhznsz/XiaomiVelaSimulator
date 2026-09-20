package com.vela.simulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.components.WatchPreview
import com.vela.simulator.ui.theme.VelaOrange

/**
 * 模板编辑器：新建自定义模板或以现有模板为蓝本修改。
 * 覆盖全部参数：外观 / 硬件 / 特性 / QEMU 仿真参数。
 */
@Composable
fun EditorScreen(vm: MainViewModel, id: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val base = remember(id) { id?.let { vm.templateById(it) } }

    var name by remember { mutableStateOf(base?.name?.removeSuffix("（自定义）")?.plus("（自定义）") ?: "我的 VELA 设备") }
    var shape by remember { mutableStateOf(base?.screen?.shape ?: DeviceTemplate.ScreenSpec.SHAPE_ROUND) }
    var inch by remember { mutableFloatStateOf((base?.screen?.sizeInch ?: 1.43).toFloat()) }
    var width by remember { mutableIntStateOf(base?.screen?.width ?: 466) }
    var height by remember { mutableIntStateOf(base?.screen?.height ?: 466) }
    var dpi by remember { mutableIntStateOf(base?.screen?.dpi ?: 326) }
    var smp by remember { mutableIntStateOf(base?.qemu?.smp ?: 1) }
    var memMb by remember { mutableIntStateOf(base?.qemu?.memoryMb ?: 256) }
    var machine by remember { mutableStateOf(base?.qemu?.machine ?: DeviceTemplate.QemuSpec.MACHINE_VIRT) }
    var cpu by remember { mutableStateOf(base?.qemu?.cpu ?: "cortex-a7") }
    var extraArgs by remember { mutableStateOf(base?.qemu?.extraArgs ?: "") }
    var features by remember { mutableStateOf(base?.features?.toSet() ?: setOf("nfc", "bluetooth")) }

    val preview = DeviceTemplate(
        id = "preview",
        name = name,
        screen = DeviceTemplate.ScreenSpec(
            shape = shape, sizeInch = inch.toDouble(), width = width, height = height, dpi = dpi,
        ),
        hardware = DeviceTemplate.HardwareSpec(),
        features = features.toList(),
        qemu = DeviceTemplate.QemuSpec(machine = machine, cpu = cpu, smp = smp, memoryMb = memMb, extraArgs = extraArgs),
    )

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("模板编辑器", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = {
                val t = preview.copy(
                    id = "custom-" + System.currentTimeMillis(),
                    qemu = preview.qemu.copy(
                        kernel = if (machine == DeviceTemplate.QemuSpec.MACHINE_VIRT)
                            DeviceTemplate.QemuSpec.KERNEL_OPENVELA_ARMV7A_NSH
                        else DeviceTemplate.QemuSpec.KERNEL_OPENVELA_MPS2_AN500_NSH,
                    ),
                    note = "用户自定义模板",
                )
                vm.templates.saveCustom(t)
                vm.refreshTemplates()
                onBack()
            }) { Text("保存") }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 预览
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                WatchPreview(preview, Modifier.height(160.dp))
            }

            OutlinedTextField(name, { name }, label = { Text("设备名称") }, modifier = Modifier.fillMaxWidth())

            Text("屏幕形状", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = shape == "round", onClick = { shape = "round" }, label = { Text("圆形") })
                FilterChip(selected = shape == "rect", onClick = { shape = "rect" }, label = { Text("方形") })
            }
            Text(
                "提示：方形屏中高宽比 ≥ 1.8 的竖长屏（如手环）会自动呈现胶囊圆角外观",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("屏幕尺寸 ${"%.2f".format(inch)}\"", style = MaterialTheme.typography.labelLarge)
            Slider(inch, { inch = it }, valueRange = 1.0f..2.5f, steps = 14)

            Text("分辨率 ${width}×${height}", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(192 to 490, 212 to 520, 336 to 480, 432 to 514, 466 to 466, 480 to 480).forEach { (w, h) ->
                    FilterChip(selected = width == w && height == h, onClick = { width = w; height = h }, label = { Text("$w×$h") })
                }
            }
            Text("像素密度 $dpi ppi", style = MaterialTheme.typography.labelLarge)
            Slider(dpi.toFloat(), { dpi = it.toInt() }, valueRange = 260f..400f)

            Text("QEMU 机器", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = machine == "virt", onClick = { machine = "virt"; cpu = "cortex-a7" }, label = { Text("virt (Cortex-A)") })
                FilterChip(selected = machine == "mps2-an500", onClick = { machine = "mps2-an500"; cpu = "cortex-m7" }, label = { Text("MPS2 M7") })
                FilterChip(selected = machine == "mps2-an521", onClick = { machine = "mps2-an521"; cpu = "cortex-m33" }, label = { Text("MPS2 M33") })
            }
            if (machine == "virt") {
                Text("虚拟内存 $memMb MB", style = MaterialTheme.typography.labelLarge)
                Slider(memMb.toFloat(), { memMb = it.toInt() }, valueRange = 128f..1024f)
                Text("SMP 核心数 $smp", style = MaterialTheme.typography.labelLarge)
                Slider(smp.toFloat(), { smp = it.toInt() }, valueRange = 1f..4f, steps = 2)
            }

            OutlinedTextField(
                extraArgs, { extraArgs = it },
                label = { Text("附加 QEMU 参数（可选，空格分隔）") },
                placeholder = { Text("-device virtio-gpu-device") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("设备特性", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("nfc", "esim", "gps", "bluetooth").forEach { f ->
                    FilterChip(
                        selected = f in features,
                        onClick = { features = if (f in features) features - f else features + f },
                        label = { Text(DeviceTemplate.FEATURE_LABELS[f] ?: f) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("heart_rate", "spo2", "speaker", "wifi").forEach { f ->
                    FilterChip(
                        selected = f in features,
                        onClick = { features = if (f in features) features - f else features + f },
                        label = { Text(DeviceTemplate.FEATURE_LABELS[f] ?: f) },
                    )
                }
            }

            Text(
                "保存后可在模板列表查看并启动；内核镜像使用所选机器对应的 openvela 官方镜像。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
