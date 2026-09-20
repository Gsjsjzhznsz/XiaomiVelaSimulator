package com.vela.simulator.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.components.PageScaffold
import com.vela.simulator.ui.components.WatchPreview
import com.vela.simulator.ui.theme.VelaBg
import com.vela.simulator.ui.theme.VelaGreen
import com.vela.simulator.ui.theme.VelaOrange
import com.vela.simulator.ui.theme.VelaOutline
import com.vela.simulator.ui.theme.VelaRed
import com.vela.simulator.ui.theme.VelaSurface
import com.vela.simulator.ui.theme.VelaSurfaceHigh
import com.vela.simulator.ui.theme.VelaTextDim
import com.vela.simulator.ui.theme.WatchScreenDark
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/* ===================== 工坊首页 ===================== */

/** 工坊：快应用模拟 (rpk) + 表盘模拟 (bin)（PageScaffold 自带磨砂顶栏） */
@Composable
fun WorkshopScreen(
    vm: MainViewModel,
    bottomInnerPadding: Dp = 0.dp,
    isActive: Boolean = true,
    onOpenQuickApp: () -> Unit,
    onOpenWatchface: () -> Unit,
) {
    PageScaffold(title = "工坊", bottomInnerPadding = bottomInnerPadding) { inner ->
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(inner.calculateTopPadding()))
        Text("模拟工坊", style = MaterialTheme.typography.headlineMedium)
        Text(
            "快应用 (.rpk) 与表盘 (.bin) 的包解析与设备外形模拟",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        FeatureCard(
            icon = { Icon(Icons.Filled.Extension, null, tint = VelaOrange, modifier = Modifier.size(34.dp)) },
            title = "快应用模拟",
            desc = "导入 .rpk 包：解析 manifest 清单、图标、页面路由、i18n 字符串表，" +
                    "并在设备外形内模拟启动画面与页面跳转。",
            onClick = onOpenQuickApp,
        )
        Spacer(Modifier.height(12.dp))
        FeatureCard(
            icon = { Icon(Icons.Filled.Watch, null, tint = VelaGreen, modifier = Modifier.size(34.dp)) },
            title = "表盘模拟",
            desc = "导入 .bin 表盘：扫描内嵌 PNG/JPEG 资源（预览图/背景/指针），" +
                    "按设备外形显示预览并叠加实时走时，支持 ZIP 容器表盘。",
            onClick = onOpenWatchface,
        )
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = VelaSurface), shape = RoundedCornerShape(16.dp)) {
            Text(
                "说明：快应用的 JS 业务逻辑与表盘的私有布局指令需要官方引擎才能完整执行，" +
                        "本工坊提供的是包级解析 + 外形模拟预览；解析结果可用于开发期的包体检查与资源审阅。",
                style = MaterialTheme.typography.bodySmall,
                color = VelaTextDim,
                modifier = Modifier.padding(14.dp),
            )
        }
        // 底部安全余量：悬浮底栏下方不被遮挡
        Spacer(Modifier.height(bottomInnerPadding + 16.dp))
    }
    }
}

@Composable
private fun FeatureCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VelaSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    desc, style = MaterialTheme.typography.bodySmall,
                    color = VelaTextDim, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/* ===================== 公共小组件 ===================== */

/** 设备外形屏幕容器（按模板形状裁剪，中间叠加 content） */
@Composable
fun DeviceFrame(
    template: DeviceTemplate,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val round = template.screen.isRound
    Box(
        modifier
            .aspectRatioOf(template.screen.width, template.screen.height)
            .clip(RoundedCornerShape(if (round) 50 else 18))
            .background(WatchScreenDark),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun Modifier.aspectRatioOf(w: Int, h: Int): Modifier =
    this.then(Modifier.fillMaxWidth(0.62f).height((270 * h.toFloat() / w.toFloat()).coerceAtMost(280f).dp))

/** 模板选择 chips（横向滚动） */
@Composable
fun TemplateChips(
    templates: List<Pair<DeviceTemplate, *>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(templates, key = { it.first.id }) { (t, _) ->
            AssistChip(
                onClick = { onSelect(t.id) },
                label = { Text(t.name, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    containerColor = if (t.id == selectedId) VelaOrange.copy(alpha = 0.16f) else VelaSurface,
                    labelColor = if (t.id == selectedId) VelaOrange else VelaTextDim,
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, if (t.id == selectedId) VelaOrange else VelaOutline,
                ),
            )
        }
    }
}

/* ===================== 快应用模拟 ===================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAppScreen(vm: MainViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.quickAppState.collectAsState()
    val templates by vm.templateList.collectAsState()
    var templateId by remember { mutableStateOf<String?>(null) }
    var launched by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(0) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            launched = false
            vm.importQuickApp(uri)
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("快应用模拟 (.rpk)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { pick.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !state.busy) {
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
                CircularProgressIndicator(Modifier.padding(24.dp), color = VelaOrange)
            } else if (state.pkg == null) {
                EmptyHint(state.error, "选择一个 .rpk 快应用包（zip 容器，含 manifest.json）")
            } else {
                val pkg = state.pkg!!
                val tpl = templates.firstOrNull { it.first.id == (templateId ?: templates.firstOrNull()?.first?.id) }?.first
                if (tpl != null) {
                    Spacer(Modifier.height(8.dp))
                    TemplateChips(templates, tpl.id) { templateId = it; launched = false }
                    Spacer(Modifier.height(12.dp))

                    // 设备外形模拟屏幕
                    DeviceFrame(tpl) {
                        if (!launched) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                pkg.iconFile?.let {
                                    Image(
                                        BitmapFactory.decodeFile(it).asImageBitmap(),
                                        null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }
                                Text(pkg.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { launched = true; currentPage = 0 },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = VelaOrange),
                                ) { Text("启动模拟") }
                            }
                        } else {
                            QuickAppPageSim(pkg, currentPage) { i -> currentPage = i }
                        }
                    }
                }

                // 信息卡
                InfoCard(title = "包信息") {
                    InfoRow("名称", pkg.name)
                    InfoRow("包名", pkg.packageId)
                    InfoRow("版本", "${pkg.versionName} (${pkg.versionCode})")
                    InfoRow("最低平台", pkg.minPlatformVersion)
                    InfoRow("入口页面", pkg.entryPage)
                    InfoRow("文件数", "${pkg.totalFiles} 项 · ${"%.1f".format(pkg.fileSize / 1024f / 1024f)} MB")
                    if (pkg.features.isNotEmpty()) InfoRow("features", pkg.features.joinToString(", "))
                    if (pkg.permissions.isNotEmpty()) InfoRow("permissions", pkg.permissions.joinToString(", "))
                }

                // 页面路由卡
                InfoCard(title = "页面路由 (${pkg.pages.size})") {
                    pkg.pages.forEachIndexed { i, p ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                launched = true; currentPage = i
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                (if (p.route == pkg.entryPage) "入口" else "页面"),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (p.route == pkg.entryPage) VelaOrange else VelaTextDim,
                                modifier = Modifier
                                    .background(
                                        (if (p.route == pkg.entryPage) VelaOrange else VelaTextDim).copy(alpha = 0.12f),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(p.route, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            if (p.component.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text("· ${p.component}", style = MaterialTheme.typography.bodySmall, color = VelaTextDim)
                            }
                        }
                    }
                }

                // i18n 卡
                if (pkg.i18nLocales.isNotEmpty()) I18nCard(pkg)

                // 文件清单
                InfoCard(title = "文件清单 (前 ${pkg.files.size} 项)") {
                    pkg.files.take(40).forEach { f ->
                        Text(
                            "${f.path}   ${if (f.size > 0) "${f.size}B" else "-"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VelaTextDim,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** 模拟页面：路由切换 + i18n 文案占位渲染 */
@Composable
private fun QuickAppPageSim(
    pkg: com.vela.simulator.quickapp.RpkManager.QuickAppPackage,
    pageIndex: Int,
    onSelectPage: (Int) -> Unit,
) {
    val page = pkg.pages.getOrNull(pageIndex) ?: pkg.pages.firstOrNull()
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(pageIndex) {
        progress = 0f
        while (progress < 1f) { delay(40); progress += 0.05f }
    }
    Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // 模拟状态栏
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", color = VelaOrange, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { onSelectPage(0) })
            Spacer(Modifier.weight(1f))
            Text(page?.route ?: "-", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("%02d".format(LocalTime.now().hour) + ":" + "%02d".format(LocalTime.now().minute),
                color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(10.dp))
        // 页面内容占位
        Text(page?.route ?: "无页面", color = VelaOrange, style = MaterialTheme.typography.titleMedium)
        Text("component: ${page?.component ?: "-"}", color = VelaTextDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { progress }, color = VelaOrange, trackColor = VelaSurfaceHigh,
            modifier = Modifier.fillMaxWidth(0.8f))
        Spacer(Modifier.height(10.dp))
        Text("页面渲染为路由模拟\n（完整 UX 渲染需快应用 JS 引擎）", color = VelaTextDim,
            style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Spacer(Modifier.weight(1f))
        // 页面切换 chips
        if (pkg.pages.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pkg.pages.size) { i ->
                    AssistChip(
                        onClick = { onSelectPage(i) },
                        label = { Text(pkg.pages[i].route, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = if (i == pageIndex) VelaOrange.copy(alpha = 0.18f) else VelaSurfaceHigh,
                            labelColor = if (i == pageIndex) VelaOrange else VelaTextDim,
                        ),
                    )
                }
            }
        }
    }
}

/** i18n 字符串表查看 */
@Composable
private fun I18nCard(pkg: com.vela.simulator.quickapp.RpkManager.QuickAppPackage) {
    var locale by remember(pkg.filePath) { mutableStateOf(pkg.i18nLocales.first()) }
    val strings = remember(pkg.filePath, locale) {
        com.vela.simulator.quickapp.RpkManager.loadI18n(pkg, locale)
    }
    InfoCard(title = "i18n 字符串表") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pkg.i18nLocales) { l ->
                AssistChip(
                    onClick = { locale = l },
                    label = { Text(l) },
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = if (l == locale) VelaOrange.copy(alpha = 0.18f) else VelaSurfaceHigh,
                        labelColor = if (l == locale) VelaOrange else VelaTextDim,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (strings.isEmpty()) {
            Text("该语言下未解析到字符串", style = MaterialTheme.typography.bodySmall, color = VelaTextDim)
        } else {
            strings.entries.take(30).forEach { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(entry.key, style = MaterialTheme.typography.bodySmall, color = VelaOrange,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(10.dp))
                    Text(entry.value, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1)
                }
            }
        }
    }
}

/* ===================== 通用卡片 ===================== */

@Composable
fun InfoCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VelaSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = VelaOrange)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = VelaTextDim,
            modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyHint(error: String?, hint: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.InstallMobile, null, tint = VelaOutline, modifier = Modifier.size(56.dp))
        Text(
            error ?: hint,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) VelaRed else VelaTextDim,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}
