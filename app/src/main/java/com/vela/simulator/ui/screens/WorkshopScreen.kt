package com.vela.simulator.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Watch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.ui.LocalEnableBlur
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.components.PageScaffold
import com.vela.simulator.ui.theme.WatchScreenDark
import com.vela.simulator.ui.util.BlurredBar
import com.vela.simulator.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalTime

/*
 * v2.2.10: 工坊系列页面全面迁移到 miuix 设计语言（与「主题与外观」页同款）：
 *  - 全部颜色取自 MiuixTheme.colorScheme，随主题模式（浅色/深色/Monet 取色/
 *    关键色）实时变化 —— 旧实现硬编码 VelaOrange/VelaSurface 等常量，主题
 *    设置完全不影响本页（用户主诉之一）；
 *  - 顶栏走 BandQQ 同款 BlurredBar 毛玻璃 + SmallTopAppBar（含导入动作）；
 *  - 组件统一 miuix Card/Button/TextButton/LinearProgressIndicator，
 *    并保留 WatchfaceScreen 共用的 InfoCard/InfoRow/TemplateChips/DeviceFrame
 *    公开签名（表盘页同步获得主题响应能力）。
 */

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
            Text("模拟工坊", fontSize = 24.sp)
            Text(
                "快应用 (.rpk) 真机执行 + 表盘 (.bin) 解析预览",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            FeatureCard(
                icon = Icons.Filled.Extension,
                title = "快应用 · 虚拟机真实运行",
                desc = "导入 .rpk 包：一键装入虚拟机数据盘，由内置 vapp 运行时" +
                        "（QuickJS + LVGL）真实渲染执行；支持解析清单/图标/路由/i18n 与设备外形预览。",
                onClick = onOpenQuickApp,
            )
            Spacer(Modifier.height(12.dp))
            FeatureCard(
                icon = Icons.Filled.Watch,
                title = "表盘模拟",
                desc = "导入 .bin 表盘：扫描内嵌 PNG/JPEG 资源（预览图/背景/指针），" +
                        "按设备外形显示预览并叠加实时走时，支持 ZIP 容器表盘。",
                onClick = onOpenWatchface,
            )
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "说明：v2.2 起全部设备模板统一运行内置 vapp 固件，快应用在虚拟机内" +
                            "真实执行（不再是路由占位模拟）；数据盘包列表对所有模板通用，" +
                            "写入需先停止运行中的虚拟机。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            // 底部安全余量：悬浮底栏下方不被遮挡
            Spacer(Modifier.height(bottomInnerPadding + 16.dp))
        }
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(34.dp), tint = cs.primary)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, fontSize = 16.sp)
                Text(
                    desc, fontSize = 12.sp,
                    color = cs.onSurfaceSecondary, modifier = Modifier.padding(top = 4.dp),
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
    // 胶囊形（手环竖长屏）：四角 50% 圆角 → 胶囊轮廓（v0.2.4）
    val capsule = template.isCapsule
    Box(
        modifier
            .aspectRatioOf(template.screen.width, template.screen.height)
            .clip(RoundedCornerShape(if (round || capsule) 50 else 18))
            .background(WatchScreenDark),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun Modifier.aspectRatioOf(w: Int, h: Int): Modifier =
    this.then(Modifier.fillMaxWidth(0.62f).height((270 * h.toFloat() / w.toFloat()).coerceAtMost(280f).dp))

/** 主题响应选择胶囊（模板选择 / 页面路由 / i18n 语言共用） */
@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) cs.primary.copy(alpha = 0.16f) else cs.surfaceContainer)
            .border(1.dp, if (selected) cs.primary else cs.outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = if (compact) 11.sp else 13.sp,
            maxLines = 1,
            color = if (selected) cs.primary else cs.onSurfaceSecondary,
        )
    }
}

/** 模板选择 chips（横向滚动） */
@Composable
fun TemplateChips(
    templates: List<Pair<DeviceTemplate, *>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(templates, key = { it.first.id }) { (t, _) ->
            // 注意：SelectChip 末参是 Boolean(compact)，尾 lambda 无法自动绑定
            // onClick —— 必须显式具名传参（CI 编译实锤）
            SelectChip(label = t.name, selected = t.id == selectedId, onClick = { onSelect(t.id) })
        }
    }
}

/* ===================== 快应用 · 虚拟机真实运行 ===================== */

@Composable
fun QuickAppScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onRunInVm: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MiuixTheme.colorScheme
    val state by vm.quickAppState.collectAsState()
    val templates by vm.templateList.collectAsState()
    val vmPackages by vm.vmPackages.collectAsState()
    val vmInstall by vm.vmInstallState.collectAsState()
    val diskBusy by vm.vmDiskBusy.collectAsState()
    val runtimeState by vm.runtimeState.collectAsState()
    val sessions by vm.sessionState.collectAsState()
    val runningCount = sessions.values.count {
        it.state.value == com.vela.simulator.engine.QemuSession.State.RUNNING ||
            it.state.value == com.vela.simulator.engine.QemuSession.State.BOOTING
    }
    var templateId by remember { mutableStateOf<String?>(null) }
    var launched by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(0) }

    // 进入页面/返回时刷新数据盘包列表
    LaunchedEffect(Unit) { vm.refreshVmPackages() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            launched = false
            vm.importQuickApp(uri)
        }
    }

    // BandQQ 同款顶栏毛玻璃（跟随「主题与外观 → 模糊」开关）
    val blurBackdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val blurActive = blurBackdrop != null

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(blurBackdrop) {
                SmallTopAppBar(
                    title = "快应用 · 虚拟机运行",
                    color = if (blurActive) Color.Transparent else cs.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, "返回", tint = cs.onBackground)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                pick.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            enabled = !state.busy,
                        ) {
                            Icon(MiuixIcons.Import, "导入", tint = cs.onBackground)
                        }
                    },
                )
            }
        },
        popupHost = { },
    ) { innerPadding ->
        Box(
            modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(innerPadding.calculateTopPadding() + 12.dp))

                when {
                    state.busy -> Box(
                        Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    state.pkg == null -> EmptyHint(
                        state.error,
                        "点击右上角导入一个 .rpk 快应用包（zip 容器，含 manifest.json）",
                    )

                    else -> {
                        val pkg = state.pkg!!
                        val tpl = templates.firstOrNull {
                            it.first.id == (templateId ?: templates.firstOrNull()?.first?.id)
                        }?.first
                        if (tpl != null) {
                            // v2.2.1: 显著的错误横幅（原先错误只在执行卡内小字，易被忽略）
                            vmInstall.error?.let { err ->
                                ErrorBanner(err) { vm.clearVmInstallError() }
                                Spacer(Modifier.height(10.dp))
                            }

                            SmallTitle(text = "目标设备")
                            Text(
                                "画面按所选模板的屏幕形状/分辨率自适应",
                                fontSize = 12.sp,
                                color = cs.onSurfaceSecondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            TemplateChips(templates, tpl.id) { templateId = it; launched = false }
                            Spacer(Modifier.height(14.dp))

                            // v2.2: 虚拟机执行卡 —— 安装到数据盘 + 一键启动
                            VmExecCard(
                                vm = vm,
                                tpl = tpl,
                                pkg = pkg,
                                vmInstall = vmInstall,
                                vmPackages = vmPackages,
                                diskBusy = diskBusy,
                                runtimeInstalled = runtimeState.installed,
                                runningCount = runningCount,
                                onLaunched = { onRunInVm(tpl.id) },
                            )
                            Spacer(Modifier.height(10.dp))

                            // 数据盘包列表（全模板通用）
                            VmPackageListCard(
                                packages = vmPackages,
                                busy = diskBusy,
                                canWrite = runningCount == 0 && runtimeState.installed,
                                onLaunch = { p ->
                                    // v2.2.5: 经 safeId 启动；旧格式包自动迁移重装（固件 LFN 路径长度缺陷）
                                    vm.launchVmPackage(tpl, p) { onRunInVm(tpl.id) }
                                },
                                onRemove = { vm.removeRpkFromVm(it) },
                            )
                            Spacer(Modifier.height(14.dp))

                            // 设备外形模拟预览（解析预览保留，真实画面以虚拟机为准）
                            SmallTitle(text = "外形预览")
                            DeviceFrame(tpl) {
                                if (!launched) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        pkg.iconFile?.let { path ->
                                            // v2.2.1: decodeFile 可能返回 null（重解析期间解包目录被重建）——
                                            // 原实现直接 .asImageBitmap() 会 NPE 崩溃，加空值回退
                                            val bmp = remember(path) { BitmapFactory.decodeFile(path) }
                                            if (bmp != null) {
                                                Image(
                                                    bmp.asImageBitmap(),
                                                    null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                                                    contentScale = ContentScale.Crop,
                                                )
                                                Spacer(Modifier.height(10.dp))
                                            }
                                        }
                                        Text(pkg.name, color = Color.White, fontSize = 14.sp)
                                        Spacer(Modifier.height(12.dp))
                                        Button(
                                            onClick = { launched = true; currentPage = 0 },
                                            colors = ButtonDefaults.buttonColorsPrimary(),
                                        ) { Text("外形预览", fontSize = 13.sp) }
                                    }
                                } else {
                                    QuickAppPageSim(pkg, currentPage) { i -> currentPage = i }
                                }
                            }

                            // 信息卡（v2.2.1: 可折叠，默认收起，缩短页面）
                            ExpandableCard(title = "包信息") {
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
                            ExpandableCard(title = "页面路由 (${pkg.pages.size})") {
                                pkg.pages.forEachIndexed { i, p ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                            launched = true; currentPage = i
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val entry = p.route == pkg.entryPage
                                        Text(
                                            if (entry) "入口" else "页面",
                                            fontSize = 10.sp,
                                            color = if (entry) cs.primary else cs.onSurfaceSecondary,
                                            modifier = Modifier
                                                .background(
                                                    (if (entry) cs.primary else cs.onSurfaceSecondary).copy(alpha = 0.12f),
                                                    RoundedCornerShape(6.dp),
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(p.route, fontSize = 14.sp, maxLines = 1)
                                        if (p.component.isNotBlank()) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("· ${p.component}", fontSize = 12.sp, color = cs.onSurfaceSecondary, maxLines = 1)
                                        }
                                    }
                                }
                            }

                            // i18n 卡
                            if (pkg.i18nLocales.isNotEmpty()) {
                                ExpandableCard(title = "i18n 字符串表 (${pkg.i18nLocales.size})") { I18nBody(pkg) }
                            }

                            // 文件清单
                            ExpandableCard(title = "文件清单 (前 ${pkg.files.size} 项)") {
                                pkg.files.take(40).forEach { f ->
                                    Text(
                                        "${f.path}   ${if (f.size > 0) "${f.size}B" else "-"}",
                                        fontSize = 12.sp,
                                        color = cs.onSurfaceSecondary,
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

                // 底部留白：导航栏高度 + 余量
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                            24.dp
                    )
                )
            }
        }
    }
}

/** 错误横幅（errorContainer 主题色，随深浅模式切换） */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MiuixIcons.Report, null, Modifier.size(18.dp), tint = cs.onErrorContainer)
        Text(
            message,
            fontSize = 13.sp,
            color = cs.onErrorContainer,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        Text(
            "知道了",
            fontSize = 13.sp,
            color = cs.onErrorContainer.copy(alpha = 0.75f),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** v2.2: 虚拟机执行卡（安装到数据盘 + 一键启动），miuix 主题化 */
@Composable
private fun VmExecCard(
    vm: MainViewModel,
    tpl: DeviceTemplate,
    pkg: com.vela.simulator.quickapp.RpkManager.QuickAppPackage,
    vmInstall: com.vela.simulator.ui.MainViewModel.VmInstallUiState,
    vmPackages: List<com.vela.simulator.quickapp.RpkInstaller.VmPackage>,
    diskBusy: Boolean,
    runtimeInstalled: Boolean,
    runningCount: Int,
    onLaunched: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MiuixIcons.Play, null, Modifier.size(20.dp), tint = cs.primary)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("在虚拟机中真实运行", fontSize = 15.sp)
                    Text(
                        "${tpl.name} · ${tpl.screen.width}×${tpl.screen.height} · vapp 运行时渲染",
                        fontSize = 12.sp, color = cs.onSurfaceSecondary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                !runtimeInstalled -> Text(
                    "首次使用请先在「设备」页安装 QEMU 运行环境",
                    fontSize = 13.sp, color = cs.error,
                )
                vmInstall.busy -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        vmInstall.stage, fontSize = 12.sp,
                        color = cs.onSurfaceSecondary, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.installRpkAndLaunch(tpl, pkg) { onLaunched() } },
                            enabled = !diskBusy,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (vmPackages.any { it.packageId == pkg.packageId }) "启动（已安装）" else "安装并启动",
                                fontSize = 14.sp,
                            )
                        }
                        Button(
                            onClick = { vm.installRpkToVm(pkg) },
                            enabled = !diskBusy,
                            colors = ButtonDefaults.buttonColors(),
                        ) { Text("仅装入数据盘", fontSize = 14.sp) }
                    }
                    Text(
                        if (runningCount > 0) "有 $runningCount 台虚拟机运行中：写入数据盘前会自动停止它们"
                        else "写入数据盘前会自动停止运行中的虚拟机",
                        fontSize = 12.sp, color = cs.onSurfaceSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    vmInstall.installedPkgId?.let {
                        Text("已装入数据盘: $it", fontSize = 12.sp, color = cs.primary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** v2.2: 虚拟机数据盘包列表（mtools 读取，含图标/版本；支持启动/卸载），miuix 主题化 */
@Composable
private fun VmPackageListCard(
    packages: List<com.vela.simulator.quickapp.RpkInstaller.VmPackage>,
    busy: Boolean,
    canWrite: Boolean,
    onLaunch: (com.vela.simulator.quickapp.RpkInstaller.VmPackage) -> Unit,
    onRemove: (String) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("虚拟机数据盘 · ${packages.size} 个包", fontSize = 14.sp, color = cs.primary)
            Spacer(Modifier.height(8.dp))
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (packages.isEmpty()) {
                Text(
                    "尚未读取到数据盘内容：请先在设备页部署内置镜像，或安装一个 .rpk 包",
                    fontSize = 13.sp, color = cs.onSurfaceSecondary,
                )
            }
            packages.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // v2.2.1: 图标文件是临时目录解包产物，refresh 后路径可能失效 ——
                    // decodeFile 判空回退到扩展图标（原实现 NPE 崩溃）
                    val icon = if (p.iconFile != null) {
                        remember(p.iconFile) { BitmapFactory.decodeFile(p.iconFile) }
                    } else null
                    if (icon != null) {
                        Image(
                            icon.asImageBitmap(), null,
                            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                                .background(cs.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MiuixIcons.Import, null, Modifier.size(18.dp), tint = cs.primary)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(p.name, fontSize = 14.sp, maxLines = 1)
                        Text(
                            "${p.packageId} · v${p.versionName.ifBlank { "-" }} · ${"%.0f".format(p.sizeBytes / 1024f)}KB",
                            fontSize = 11.sp, color = cs.onSurfaceSecondary, maxLines = 1,
                        )
                    }
                    Text(
                        "启动",
                        fontSize = 13.sp, color = cs.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onLaunch(p) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    IconButton(onClick = { onRemove(p.safeId) }, enabled = canWrite) {
                        Icon(
                            MiuixIcons.Delete, "卸载", Modifier.size(18.dp),
                            tint = if (canWrite) cs.error else cs.disabledOnSurface,
                        )
                    }
                }
            }
            if (!canWrite && packages.isNotEmpty()) {
                Text(
                    "虚拟机运行中无法写数据盘（卸载不可用，启动不受影响）",
                    fontSize = 12.sp, color = cs.onSurfaceSecondary,
                )
            }
        }
    }
}

/** 模拟页面：路由切换 + i18n 文案占位渲染（设备屏幕内模拟，保持深色底） */
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
    val cs = MiuixTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // 模拟状态栏
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ 返回", color = cs.primary, fontSize = 11.sp,
                modifier = Modifier.clickable { onSelectPage(0) },
            )
            Spacer(Modifier.weight(1f))
            Text(page?.route ?: "-", color = Color.White, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "%02d".format(LocalTime.now().hour) + ":" + "%02d".format(LocalTime.now().minute),
                color = Color.White, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        // 页面内容占位
        Text(page?.route ?: "无页面", color = cs.primary, fontSize = 16.sp)
        Text("component: ${page?.component ?: "-"}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth(0.8f),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "页面渲染为路由模拟\n（真实画面以虚拟机渲染为准）",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        // 页面切换 chips
        if (pkg.pages.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pkg.pages.size) { i ->
                    SelectChip(pkg.pages[i].route, i == pageIndex, { onSelectPage(i) }, compact = true)
                }
            }
        }
    }
}

/** i18n 字符串表内容（v2.2.1: 并入可折叠卡，原独立卡头移除） */
@Composable
private fun I18nBody(pkg: com.vela.simulator.quickapp.RpkManager.QuickAppPackage) {
    var locale by remember(pkg.filePath) { mutableStateOf(pkg.i18nLocales.first()) }
    val strings = remember(pkg.filePath, locale) {
        com.vela.simulator.quickapp.RpkManager.loadI18n(pkg, locale)
    }
    val cs = MiuixTheme.colorScheme
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pkg.i18nLocales) { l ->
                SelectChip(l, l == locale, { locale = l }, compact = true)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (strings.isEmpty()) {
            Text("该语言下未解析到字符串", fontSize = 13.sp, color = cs.onSurfaceSecondary)
        } else {
            strings.entries.take(30).forEach { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        entry.key, fontSize = 12.sp, color = cs.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(entry.value, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/* ===================== 通用卡片 ===================== */

/** v2.2.1: 可折叠信息卡（默认收起），点击标题展开/收起，缩短长页面 */
@Composable
fun ExpandableCard(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    val cs = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 14.sp, color = cs.primary, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = cs.onSurfaceSecondary)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column {
            Text(title, fontSize = 14.sp, color = cs.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    val cs = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = cs.onSurfaceSecondary, modifier = Modifier.width(92.dp))
        Text(value, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyHint(error: String?, hint: String) {
    val cs = MiuixTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(MiuixIcons.Import, null, Modifier.size(56.dp), tint = cs.outline)
        Text(
            error ?: hint,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = if (error != null) cs.error else cs.onSurfaceSecondary,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}
