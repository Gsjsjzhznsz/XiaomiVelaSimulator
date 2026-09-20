package com.vela.simulator.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.theme.VelaGreen
import com.vela.simulator.ui.theme.VelaOrange
import com.vela.simulator.ui.theme.VelaOutline
import com.vela.simulator.ui.theme.VelaRed
import com.vela.simulator.ui.theme.VelaTextDim
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

/* ===================== 表盘模拟 ===================== */

/**
 * 表盘模拟 (.bin)：导入 → 资源扫描 → 设备外形预览 + 实时走时叠加。
 * 提取出的整幅图（面积最大者）作为表盘预览底图。
 */
@Composable
fun WatchfaceScreen(vm: MainViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.watchfaceState.collectAsState()
    val templates by vm.templateList.collectAsState()
    var templateId by remember { mutableStateOf<String?>(null) }
    var liveTime by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(LocalTime.now()) }

    // 走时节拍
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = LocalTime.now() }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importWatchface(uri)
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("表盘模拟 (.bin)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { pick.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }, enabled = !state.busy) {
                Icon(Icons.Filled.InstallMobile, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入")
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.padding(24.dp), color = VelaGreen)
            } else if (state.pkg == null) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Watch, null, tint = VelaOutline, modifier = Modifier.size(56.dp))
                    Text(
                        state.error ?: "选择一个 .bin 表盘文件（也支持 ZIP 容器表盘）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.error != null) VelaRed else VelaTextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            } else {
                val pkg = state.pkg!!
                val tpl = templates.firstOrNull { it.first.id == (templateId ?: templates.firstOrNull()?.first?.id) }?.first
                if (tpl != null) {
                    Spacer(Modifier.height(4.dp))
                    TemplateChips(templates, tpl.id) { templateId = it }
                    Spacer(Modifier.height(10.dp))

                    // 表盘预览（设备外形 + 实时走时）
                    DeviceFrame(tpl) {
                        val previewBmp = pkg.previewFile?.let { BitmapFactory.decodeFile(it) }
                        if (previewBmp != null) {
                            Image(
                                previewBmp.asImageBitmap(), null,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            // 无预览图: 显示占位表盘
                            com.vela.simulator.ui.components.WatchPreview(tpl, showTime = false)
                            Text(
                                "未提取到整幅预览图\n（表盘可能使用私有图片编码）",
                                color = VelaTextDim,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                        // 实时走时叠加
                        if (liveTime) {
                            LiveClockOverlay(tpl, now)
                        }
                    }

                    // 走时开关
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("实时走时叠加", style = MaterialTheme.typography.bodySmall, color = VelaTextDim)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = liveTime,
                            onCheckedChange = { liveTime = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = VelaGreen),
                        )
                    }
                }

                // 信息卡
                InfoCard(title = "表盘信息") {
                    InfoRow("文件名", pkg.fileName)
                    InfoRow("大小", "%.1f KB".format(pkg.fileSize / 1024f))
                    InfoRow("格式", if (pkg.zipEntries != null) "ZIP 容器 (${pkg.zipEntries.size} 项)" else "二进制 .bin")
                    InfoRow("提取图片", "${pkg.images.size} 张")
                    if (pkg.previewWidth > 0) InfoRow("预览图", "${pkg.previewWidth} × ${pkg.previewHeight}")
                }

                // 头部摘要
                InfoCard(title = "头部摘要 (前 48 字节)") {
                    Text(
                        pkg.headerHex.chunked(24).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = VelaTextDim,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                // 资源画廊
                if (pkg.images.isNotEmpty()) {
                    InfoCard(title = "提取资源 (${pkg.images.size})") {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxWidth().height(((pkg.images.size / 4 + 1) * 86).coerceAtMost(344).dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                        ) {
                            items(pkg.images) { img ->
                                val bmp = BitmapFactory.decodeFile(img.file.absolutePath)
                                if (bmp != null) {
                                    Image(
                                        bmp.asImageBitmap(), null,
                                        Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        Modifier.fillMaxWidth().height(80.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .backgroundDark(),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("?", color = VelaTextDim) }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        pkg.images.take(8).forEach { img ->
                            Text(
                                "${img.file.name} · ${img.width}×${img.height} · ${img.format} @0x${java.lang.Long.toHexString(img.offset)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = VelaTextDim,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // 字符串表
                if (pkg.strings.isNotEmpty()) {
                    InfoCard(title = "内嵌字符串 (前 ${pkg.strings.size} 条)") {
                        pkg.strings.take(24).forEach { s ->
                            Text(
                                s, style = MaterialTheme.typography.bodySmall,
                                color = Color.White, maxLines = 1,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun Modifier.backgroundDark(): Modifier =
    this.then(Modifier.background(Color(0xFF21212B)))

/** 实时走时叠加：digital 数字时间 / analog 指针，按模板风格 */
@Composable
private fun LiveClockOverlay(template: com.vela.simulator.device.DeviceTemplate, now: LocalTime) {
    val analog = template.ui.watchfaceStyle == "analog"
    val accent = template.ui.accentColor.let { c ->
        runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrDefault(VelaOrange)
    }
    if (analog) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = size.minDimension / 2 * 0.82f
            fun hand(angleDeg: Double, len: Float, width: Float, color: Color) {
                val a = Math.toRadians(angleDeg)
                drawLine(
                    color = color,
                    start = Offset(cx, cy),
                    end = Offset(cx + (len * cos(a)).toFloat(), cy + (len * sin(a)).toFloat()),
                    strokeWidth = width,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            val hAng = (now.hour % 12) * 30.0 + now.minute * 0.5
            val mAng = now.minute * 6.0 + now.second * 0.1
            val sAng = now.second * 6.0
            hand(hAng - 90, r * 0.5f, size.minDimension * 0.012f, Color.White)
            hand(mAng - 90, r * 0.74f, size.minDimension * 0.009f, Color.White)
            hand(sAng - 90, r * 0.8f, size.minDimension * 0.005f, accent)
            drawCircle(color = accent, radius = size.minDimension * 0.012f, center = Offset(cx, cy))
            // 刻度
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30 - 90).toDouble())
                val c = Color.White.copy(alpha = 0.55f)
                drawLine(
                    color = c,
                    start = Offset(cx + (r * 0.88f * cos(a)).toFloat(), cy + (r * 0.88f * sin(a)).toFloat()),
                    end = Offset(cx + (r * 0.96f * cos(a)).toFloat(), cy + (r * 0.96f * sin(a)).toFloat()),
                    strokeWidth = size.minDimension * 0.006f,
                )
            }
        }
    } else {
        // digital：大号数字时间
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(
                "%02d:%02d".format(now.hour, now.minute),
                color = Color.White,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 34.sp,
                ),
            )
            Text(
                "%02d".format(now.second),
                color = VelaOrange,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
