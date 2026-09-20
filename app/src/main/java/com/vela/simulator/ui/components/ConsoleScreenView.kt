package com.vela.simulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.simulator.device.DeviceTemplate
import kotlinx.coroutines.delay

/**
 * 控制台画面（v0.2.5 兜底显示）：
 * 把串口 nsh 输出直接渲染到设备屏幕形状的"假屏幕"里——
 * 当镜像没有帧缓冲设备（MPS2 纯 nsh）或 VNC 连不上时，
 * 画面页依然有一块"活着的屏幕"（保证不管什么方法都能看到画面）。
 *
 * 自动滚动到底部，底部带闪烁光标，样式为终端绿字黑底。
 */
@Composable
fun ConsoleScreenView(
    lines: List<String>,
    template: DeviceTemplate,
    modifier: Modifier = Modifier,
) {
    val round = template.screen.isRound
    val shape = RoundedCornerShape(if (round) 50 else 16)
    val scroll = rememberScrollState()
    var cursorOn by remember { mutableStateOf(true) }

    // 输出增长时自动滚到底部
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }
    // 光标闪烁
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            cursorOn = !cursorOn
        }
    }

    val visible = lines.takeLast(24)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF050508)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 16.dp),
        ) {
            if (visible.isEmpty()) {
                Text(
                    "nsh",
                    color = Color(0xFF9BE89B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                )
            } else {
                visible.forEachIndexed { i, line ->
                    Text(
                        line.take(64),
                        color = Color(0xFF9BE89B),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text(
                if (cursorOn) "nsh> ▌" else "nsh>  ",
                color = Color(0xFFFF9E40),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
            )
        }
    }
}
