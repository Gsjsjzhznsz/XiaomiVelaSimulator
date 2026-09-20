package com.vela.simulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vela.simulator.config.AppearanceConfig
import com.vela.simulator.ui.LocalEnableBlur
import com.vela.simulator.ui.LocalEnableFloatingBottomBar
import com.vela.simulator.ui.LocalEnableFloatingBottomBarGlass
import com.vela.simulator.ui.LocalMotionSpeed
import com.vela.simulator.ui.LocalMotionStagger
import com.vela.simulator.ui.MainViewModel
import com.vela.simulator.ui.components.BottomBar
import com.vela.simulator.ui.screens.AboutScreen
import com.vela.simulator.ui.screens.EditorScreen
import com.vela.simulator.ui.screens.HomeScreen
import com.vela.simulator.ui.screens.QuickAppScreen
import com.vela.simulator.ui.screens.RunScreen
import com.vela.simulator.ui.screens.SettingsScreen
import com.vela.simulator.ui.screens.TemplateDetailScreen
import com.vela.simulator.ui.screens.ThemeScreen
import com.vela.simulator.ui.screens.WatchfaceScreen
import com.vela.simulator.ui.screens.WorkshopScreen
import com.vela.simulator.ui.theme.VelaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vela.simulator.util.FileLogger.i("app", "MainActivity.onCreate")
        enableEdgeToEdge()
        // 温启动时重新应用预测性返回开关（设置切换后无需冷启动进程）
        (application as? VelaApp)?.applyPredictiveBackFlag()
        val configManager = (application as VelaApp).config
        setContent {
            // activity 1.12+ 的 ComponentActivity 已实现 NavigationEventDispatcherOwner；
            // 显式注入 CompositionLocal，确保 miuix 弹窗系统的 NavigationBackHandler
            // 在任意 Popup/Dialog 子树内都能解析到（BandQQ v2.4.6 教训）。
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides this@MainActivity as NavigationEventDispatcherOwner,
            ) {
                // DataStore Flow 直接驱动全局主题：设置页切换立即生效，无需重启
                val cfg by configManager.observe().collectAsState(initial = AppearanceConfig())
                VelaTheme(themeMode = cfg.themeMode, keyColor = cfg.keyColor, pageScale = cfg.pageScale) {
                    CompositionLocalProvider(
                        LocalEnableBlur provides cfg.enableBlur,
                        LocalEnableFloatingBottomBar provides cfg.enableFloatingBottomBar,
                        LocalEnableFloatingBottomBarGlass provides cfg.navGlass,
                        LocalMotionSpeed provides cfg.motionSpeed,
                        LocalMotionStagger provides cfg.motionStagger,
                    ) {
                        // 注意：必须显式获取并传递 ViewModel。
                        // 不能写成 `VelaApp(vm = viewModel())` 默认参数形式 ——
                        // Compose 编译器 1.5.14 下省略实参调用会导致整个函数体被
                        // 静默跳过（组合树为空、界面黑屏、无任何崩溃日志）。
                        val vm: MainViewModel = viewModel()
                        VelaApp(vm)
                    }
                }
            }
        }
    }
}

/** 主界面三个页签（MIUIx 悬浮底栏驱动） */
enum class VelaTab(val label: String) {
    Home("设备"),
    Workshop("工坊"),
    Settings("设置"),
}

/**
 * 主界面（BandQQ / KernelSU 同构）：
 * - 外层 miuix Scaffold 只持有 bottomBar；每页 PageScaffold 自带顶栏（内容穿过顶栏，
 *   玻璃才有东西可模糊）；
 * - HorizontalPager：页面横向跟手滑动，底栏点击/拖拽联动；
 * - 推入页（模板详情/运行/编辑/快应用/表盘/主题设置）：全屏 AnimatedVisibility，
 *   预测性返回期间跟手位移/缩放/淡出（HyperOS 返回预览风格）；
 * - 双 backdrop：blurBackdrop 供顶栏与普通底栏 textureBlur；backdrop 供悬浮底栏液态玻璃。
 */
@Composable
fun VelaApp(vm: MainViewModel) {
    val enableBlur = LocalEnableBlur.current
    val floatingBar = LocalEnableFloatingBottomBar.current
    val glassBar = LocalEnableFloatingBottomBarGlass.current

    val pagerState = rememberPagerState(pageCount = { VelaTab.entries.size })
    val scope = rememberCoroutineScope()

    // 首次进入加载模板（IO 异步，UI 侧只触发）
    LaunchedEffect(Unit) { vm.refreshTemplates() }

    // 推入页状态（rememberSaveable：进程内配置变更不丢导航状态）
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var showQuickApp by rememberSaveable { mutableStateOf(false) }
    var showWatchface by rememberSaveable { mutableStateOf(false) }
    var showThemeScreen by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    val overlayOpen = detailId != null || runId != null || editorId != null ||
        showQuickApp || showWatchface || showThemeScreen || showAbout

    // 按推入层级关闭最顶层页（后推入的在上层）
    fun closeTop(): Boolean = when {
        showAbout -> { showAbout = false; true }
        runId != null -> { runId = null; true }
        editorId != null -> { editorId = null; true }
        detailId != null -> { detailId = null; true }
        showWatchface -> { showWatchface = false; true }
        showQuickApp -> { showQuickApp = false; true }
        showThemeScreen -> { showThemeScreen = false; true }
        else -> false
    }

    var backProgress by remember { mutableFloatStateOf(0f) }

    // ===== 系统预测性返回 =====
    // Android 14+ 且开启开关时系统下发跟手进度；其余设备退化为离散返回
    // （activity 1.12 的非预测路径同样会走完 collect，BandQQ 实证）。必须无条件调用。
    PredictiveBackHandler(enabled = overlayOpen) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            backProgress = 0f
            closeTop()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    // HyperOS 返回预览变换：右移 30% + 轻微缩放 + 淡出
    val predictiveTransform = Modifier.graphicsLayer {
        val p = backProgress
        if (p > 0f) {
            translationX = p * size.width * 0.3f
            val s = 1f - 0.08f * p
            scaleX = s
            scaleY = s
            alpha = 1f - 0.3f * p
        }
    }

    // 顶栏/普通底栏模糊源；悬浮底栏液态玻璃源（先垫 surface 底色防采样发黑）
    val blurBackdrop = com.vela.simulator.ui.util.rememberBlurBackdrop(enableBlur)
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    // 推入页动画时长跟随动画速度设置
    val motionSpeed = LocalMotionSpeed.current.coerceIn(0.5f, 2f)
    val pushIn = (260 / motionSpeed).roundToInt()
    val pushOut = (200 / motionSpeed).roundToInt()

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = !overlayOpen,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = backdrop,
                        selected = pagerState.currentPage,
                        onSelect = { index ->
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enableFloatingBottomBar = floatingBar,
                        enableFloatingBottomBarGlass = glassBar,
                    )
                }
            }
        },
    ) { innerPadding ->
        val bottomInnerPadding = innerPadding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (floatingBar && glassBar) Modifier.layerBackdrop(backdrop) else Modifier),
                beyondViewportPageCount = 1,
                pageContent = { page ->
                    when (VelaTab.entries[page]) {
                        VelaTab.Home -> HomeScreen(
                            vm,
                            bottomInnerPadding = bottomInnerPadding,
                            isActive = page == pagerState.currentPage,
                            onSelect = { id ->
                                // "__new__" 是新建自定义模板入口：直接进编辑器，
                                // 不进详情页（v0.2.4 修复自定义模板无法打开）
                                if (id == "__new__") editorId = id else detailId = id
                            },
                        )
                        VelaTab.Workshop -> WorkshopScreen(
                            vm,
                            bottomInnerPadding = bottomInnerPadding,
                            isActive = page == pagerState.currentPage,
                            onOpenQuickApp = { showQuickApp = true },
                            onOpenWatchface = { showWatchface = true },
                        )
                        VelaTab.Settings -> SettingsScreen(
                            vm,
                            bottomInnerPadding = bottomInnerPadding,
                            isActive = page == pagerState.currentPage,
                            onOpenThemeSettings = { showThemeScreen = true },
                            onOpenAbout = { showAbout = true },
                        )
                    }
                },
            )

            // ===== 全屏推入页（PredictiveBack 手势期间跟手变换）=====
            // OverlayHost 提供不透明背景 + 触摸吸收层，修复推入页触摸穿透
            AnimatedVisibility(
                visible = detailId != null,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val id = detailId ?: return@AnimatedVisibility
                OverlayHost(transform = predictiveTransform) {
                    TemplateDetailScreen(
                        vm, id,
                        onRun = { runId = id },
                        onEdit = { editorId = id },
                        onBack = { detailId = null },
                    )
                }
            }

            AnimatedVisibility(
                visible = runId != null,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val id = runId ?: return@AnimatedVisibility
                OverlayHost(transform = predictiveTransform) {
                    RunScreen(vm, id, onBack = { runId = null })
                }
            }

            AnimatedVisibility(
                visible = editorId != null,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val id = editorId
                OverlayHost(transform = predictiveTransform) {
                    EditorScreen(vm, id, onBack = { editorId = null })
                }
            }

            AnimatedVisibility(
                visible = showQuickApp,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                OverlayHost(transform = predictiveTransform) {
                    QuickAppScreen(vm, onBack = { showQuickApp = false })
                }
            }

            AnimatedVisibility(
                visible = showWatchface,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                OverlayHost(transform = predictiveTransform) {
                    WatchfaceScreen(vm, onBack = { showWatchface = false })
                }
            }

            AnimatedVisibility(
                visible = showThemeScreen,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                OverlayHost(transform = predictiveTransform) {
                    ThemeScreen(onBack = { showThemeScreen = false })
                }
            }

            AnimatedVisibility(
                visible = showAbout,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                OverlayHost(transform = predictiveTransform) {
                    AboutScreen(onBack = { showAbout = false })
                }
            }

            // ===== 边缘手势兜底（targetSdk 28 拿不到系统预测进度流）=====
            // 推入页打开时，左右边缘横向拖拽 → 跟手预览；系统手势导航的返回手势
            // 被排除区让位，三键导航/全面屏手势下均可用。
            EdgeBackGestures(
                enabled = overlayOpen,
                onProgress = { backProgress = it },
                onCommit = { backProgress = 0f; closeTop() },
                onCancel = { backProgress = 0f },
            )
        }
    }
}

/**
 * 推入页宿主（v0.2.4 触摸穿透修复）：
 * - 不透明背景：推入页不再透出底下的 Pager 页面；
 * - 触摸吸收层：空白区域的指针事件在本层被消费，不再落到底下的
 *   HorizontalPager/模板卡片上。事件分发 Main pass 自深向浅，
 *   页内按钮/滚动/AndroidView 先于本层拿到事件，交互不受影响。
 */
@Composable
private fun OverlayHost(transform: Modifier, content: @Composable () -> Unit) {
    val bg = MiuixTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxSize()
            .then(transform)
            .background(bg)
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        event.changes.forEach { it.consume() }
                        if (event.changes.all { !it.pressed }) break
                    }
                }
            },
    ) { content() }
}

/**
 * HyperOS 风格边缘返回手势（BandQQ 之外的新增兜底）：
 * 左右两侧各 28dp 竖条监听横向拖拽并从系统手势区排除（Android 10+），
 * progress 直接驱动 predictiveTransform 跟手；松手超阈值提交关闭，否则回弹。
 * 推入页未打开时不组合，零开销。
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EdgeBackGestures(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!enabled) return
    val density = LocalDensity.current
    val fullPx = with(density) { 240.dp.toPx() }
    val gesture = Modifier
        .fillMaxHeight()
        .width(28.dp)
        .systemGestureExclusion()
        .pointerInput(Unit) {
            var startX = 0f
            var progress = 0f
            detectHorizontalDragGestures(
                onDragStart = { offset -> startX = offset.x; progress = 0f },
                onDragEnd = { if (progress > 0.55f) onCommit() else onCancel() },
                onDragCancel = { onCancel() },
            ) { change, _ ->
                val dx = kotlin.math.abs(change.position.x - startX)
                progress = (dx / fullPx).coerceIn(0f, 1f)
                onProgress(progress)
            }
        }
    Row(Modifier.fillMaxSize()) {
        Box(gesture)
        Box(Modifier.weight(1f))
        Box(gesture)
    }
}
