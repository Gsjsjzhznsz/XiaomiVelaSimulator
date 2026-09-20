package com.vela.simulator.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.simulator.VelaTab
import com.vela.simulator.ui.util.BlurredBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面底栏（BandQQ / KernelSU 双形态同构）：
 * - 非悬浮：BlurredBar(textureBlur) 包 miuix NavigationBar，模糊开启时本体透明；
 * - 悬浮：FloatingBottomBar（液态玻璃 + 阻尼拖拽指示 pill + 交互高光）。
 *
 * @param blurBackdrop 顶栏/普通底栏共用的模糊采集层（null = 模糊关闭或设备不支持）
 * @param backdrop     悬浮底栏液态玻璃的采集层
 */
@Composable
fun BottomBar(
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enableFloatingBottomBar: Boolean,
    enableFloatingBottomBarGlass: Boolean,
) {
    if (!enableFloatingBottomBar) {
        BlurredBar(blurBackdrop) {
            NavigationBar(
                modifier = modifier,
                color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                content = {
                    VelaTab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            modifier = Modifier.weight(1f),
                            icon = tab.icon(),
                            label = tab.label,
                            selected = selected == index,
                            onClick = { onSelect(index) },
                        )
                    }
                }
            )
        }
    } else {
        // BandQQ 同款：悬浮栏底距 = 导航栏 inset + 8dp（无手势导航设备回退 28dp），
        // 容器 pointerInput 吞掉栏外空白区点击防穿透
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }
        FloatingBottomBar(
            modifier = modifier
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
            selectedIndex = selected,
            onSelected = onSelect,
            backdrop = backdrop,
            tabsCount = VelaTab.entries.size,
            isBlurEnabled = enableFloatingBottomBarGlass,
        ) { activateTab ->
            VelaTab.entries.forEachIndexed { index, tab ->
                FloatingBottomBarItem(
                    selected = selected == index,
                    onClick = { activateTab(index) },
                    // 关键：weight 子项在 IntrinsicSize.Min 的 intrinsic 测量中宽度为 0，
                    // 必须 minWidth 兜底，否则整个底栏塌缩成一个颗粒（KSU 同款写法）
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                ) {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = tab.label,
                    )
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

private fun VelaTab.icon(): ImageVector = when (this) {
    VelaTab.Home -> MiuixIcons.Home
    VelaTab.Workshop -> MiuixIcons.GridView
    VelaTab.Settings -> MiuixIcons.Settings
}
