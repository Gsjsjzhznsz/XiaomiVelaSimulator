package com.vela.simulator.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.simulator.config.VelaConfig
import com.vela.simulator.ui.LocalEnableBlur
import com.vela.simulator.ui.theme.ThemeMode
import com.vela.simulator.ui.theme.VelaOrange
import com.vela.simulator.ui.util.BlurredBar
import com.vela.simulator.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 关键色候选（0 = 小米橙品牌默认），圆点选择（比 BandQQ 的下拉更直观）。
 */
private val KEY_COLOR_OPTIONS: List<Pair<Long, String>> = listOf(
    0L to "小米橙（默认）",
    0xFFF44336L to "红色", 0xFFE91E63L to "粉色", 0xFF9C27B0L to "紫色",
    0xFF673AB7L to "深紫", 0xFF3F51B5L to "靛蓝", 0xFF2196F3L to "蓝色",
    0xFF00BCD4L to "青色", 0xFF009688L to "蓝绿", 0xFF4CAF50L to "绿色",
    0xFFFFC107L to "琥珀", 0xFFFF9800L to "橙色", 0xFF795548L to "棕色",
    0xFF607D8BL to "蓝灰", 0xFFFF8FABL to "樱粉",
)

/**
 * 主题与外观全屏设置页（BandQQ / KernelSU ColorPaletteScreenMiuix 同构）：
 * 主题预览 → TabRow 三档模式 → Monet + 关键色圆点 → 模糊/悬浮底栏/液态玻璃（二级）
 * → 预测性返回 → 界面缩放/动画速度/动画延迟（内嵌 Slider 实时生效）。
 * 返回箭头/系统返回键退出（外层 PredictiveBackHandler 接管）。
 */
@Composable
fun ThemeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { VelaConfig(context) }

    val themeMode by configManager.observeThemeMode().collectAsState(initial = 0)
    val keyColor by configManager.observeKeyColor().collectAsState(initial = 0)
    val enableBlur by configManager.observeEnableBlur().collectAsState(initial = true)
    val floatingBar by configManager.observeFloatingBottomBar().collectAsState(initial = true)
    val navGlass by configManager.observeNavGlass().collectAsState(initial = true)
    val pageScale by configManager.observePageScale().collectAsState(initial = 1.0f)
    val predictiveBack by configManager.observePredictiveBack().collectAsState(initial = true)
    val motionSpeed by configManager.observeMotionSpeed().collectAsState(initial = 1.0f)
    val motionStagger by configManager.observeMotionStagger().collectAsState(initial = 100)

    val mode = ThemeMode.fromValue(themeMode)
    val isDark = mode.isDark || (mode.isSystem && isSystemInDarkTheme())
    val supportBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // 本页自身也走 BlurredBar 方案
    val blurBackdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val blurActive = blurBackdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(blurBackdrop) {
                SmallTopAppBar(
                    title = "主题与外观",
                    color = barColor,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
    ) { innerPadding ->
        var sliderValue by remember(pageScale) { mutableFloatStateOf(pageScale) }
        var speedValue by remember(motionSpeed) { mutableFloatStateOf(motionSpeed) }
        var staggerValue by remember(motionStagger) { mutableFloatStateOf(motionStagger.toFloat()) }

        Box(
            modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(innerPadding.calculateTopPadding()))
                Spacer(Modifier.height(12.dp))

                // ===== 主题实时预览 =====
                ThemePreviewCard(isDark = isDark, monet = mode.isMonet, keyColor = keyColor)
                Spacer(Modifier.height(24.dp))

                // ===== 主题模式 TabRow（跟随系统 / 浅色 / 深色）=====
                TabRow(
                    tabs = listOf("跟随系统", "浅色", "深色"),
                    selectedTabIndex = mode.value % 3,
                    onTabSelected = { index ->
                        scope.launch { configManager.setThemeMode(index + if (mode.isMonet) 3 else 0) }
                    },
                )

                // ===== Monet + 关键色 =====
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    SwitchPreference(
                        title = "启用 Monet 颜色",
                        summary = "跟随系统壁纸取色；关闭则使用品牌配色",
                        startAction = {
                            Icon(
                                MiuixIcons.Theme,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "启用 Monet 颜色",
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = mode.isMonet,
                        onCheckedChange = { on ->
                            scope.launch {
                                configManager.setThemeMode(mode.value % 3 + if (on) 3 else 0)
                            }
                        },
                    )
                    AnimatedVisibility(visible = !mode.isMonet) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("关键色", fontSize = 15.sp, color = colorScheme.onBackground)
                            ColorDotRow(
                                selected = keyColor,
                                onSelect = { c -> scope.launch { configManager.setKeyColor(c) } },
                            )
                        }
                    }
                }

                // ===== 模糊 / 悬浮底栏 / 液态玻璃（二级）=====
                SmallTitle(text = "界面与效果")
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    if (supportBlur) {
                        SwitchPreference(
                            title = "模糊",
                            summary = "启用顶栏和底栏的模糊效果",
                            startAction = {
                                Icon(
                                    MiuixIcons.CloudFill,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "模糊",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = enableBlur,
                            onCheckedChange = { on -> scope.launch { configManager.setEnableBlur(on) } },
                        )
                    }
                    SwitchPreference(
                        title = "悬浮底栏",
                        summary = "MIUIx 风格的悬浮胶囊底栏，支持阻尼拖拽",
                        startAction = {
                            Icon(
                                MiuixIcons.HorizontalSplit,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "悬浮底栏",
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = floatingBar,
                        onCheckedChange = { on ->
                            scope.launch { configManager.setEnableFloatingBottomBar(on) }
                        },
                    )
                    AnimatedVisibility(visible = floatingBar && supportBlur) {
                        SwitchPreference(
                            title = "液态玻璃",
                            summary = "悬浮底栏的实时折射 + 高光效果",
                            startAction = {
                                Icon(
                                    MiuixIcons.Scan,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "液态玻璃",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = navGlass,
                            onCheckedChange = { on -> scope.launch { configManager.setNavGlass(on) } },
                        )
                    }
                }

                // ===== 预测性返回 / 界面缩放 =====
                SmallTitle(text = "手势与显示")
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    SwitchPreference(
                        title = "预测性返回手势",
                        summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                            "返回手势期间页面跟手预览（重启应用后完全生效）；左右边缘拖拽始终可用"
                        else "返回手势期间页面跟手预览（当前系统版本使用边缘拖拽实现）",
                        startAction = {
                            Icon(
                                MiuixIcons.Sidebar,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "预测性返回手势",
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = predictiveBack,
                        onCheckedChange = { on ->
                            scope.launch { runCatching { configManager.setEnablePredictiveBack(on) } }
                        },
                    )
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("界面缩放", fontSize = 15.sp, color = colorScheme.onBackground)
                            Text(
                                "${(sliderValue * 100).roundToInt()}%",
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantActions,
                            )
                        }
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                scope.launch { configManager.setPageScale(sliderValue) }
                            },
                            valueRange = 0.8f..1.1f,
                            showKeyPoints = true,
                            keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                            magnetThreshold = 0.01f,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        )
                    }
                }

                // ===== 动画：速度 / 级联延迟 =====
                SmallTitle(text = "动画")
                Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("动画速度", fontSize = 15.sp, color = colorScheme.onBackground)
                            Text(
                                "${(speedValue * 10).roundToInt() / 10.0}x",
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantActions,
                            )
                        }
                        Slider(
                            value = speedValue,
                            onValueChange = { speedValue = it },
                            onValueChangeFinished = {
                                scope.launch {
                                    configManager.setMotionSpeed((speedValue * 100).roundToInt() / 100f)
                                }
                            },
                            valueRange = 0.5f..2f,
                            showKeyPoints = true,
                            keyPoints = listOf(0.5f, 1f, 1.5f, 2f),
                            magnetThreshold = 0.01f,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("级联延迟", fontSize = 15.sp, color = colorScheme.onBackground)
                            Text(
                                "${staggerValue.roundToInt()} ms",
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantActions,
                            )
                        }
                        Slider(
                            value = staggerValue,
                            onValueChange = { staggerValue = it },
                            onValueChangeFinished = {
                                scope.launch { configManager.setMotionStagger(staggerValue.roundToInt()) }
                            },
                            valueRange = 0f..300f,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 关键色圆点行：选中态描边高亮 */
@Composable
private fun ColorDotRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KEY_COLOR_OPTIONS.forEach { (argb, _) ->
            val color = if (argb == 0L) VelaOrange else Color(argb)
            val isSelected = if (argb == 0L) selected == 0 else selected == argb.toInt()
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) colorScheme.onBackground
                        else color.copy(alpha = 0.35f),
                        shape = CircleShape,
                    )
                    .clickable { onSelect(argb.toInt()) },
            )
        }
    }
}

/**
 * 主题实时预览卡（BandQQ ThemePreviewCard 简化版）：
 * 深浅色块 + 模式说明，随设置即时变化。
 */
@Composable
private fun ThemePreviewCard(isDark: Boolean, monet: Boolean, keyColor: Int) {
    val seed = when {
        keyColor != 0 -> Color(keyColor)
        monet -> colorScheme.primary
        else -> VelaOrange
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (isDark) "深色主题" else "浅色主题",
                fontSize = 16.sp,
                color = colorScheme.onBackground,
            )
            Text(
                when {
                    monet -> "动态取色已启用（跟随壁纸）"
                    else -> "品牌配色 · " + (KEY_COLOR_OPTIONS.find { it.first.toInt() == keyColor }?.second ?: "小米橙（默认）")
                },
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantActions,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(seed, seed.copy(alpha = 0.6f), seed.copy(alpha = 0.3f)).forEach { c ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(c),
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = MiuixIcons.GridView,
                    contentDescription = null,
                    tint = seed,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
