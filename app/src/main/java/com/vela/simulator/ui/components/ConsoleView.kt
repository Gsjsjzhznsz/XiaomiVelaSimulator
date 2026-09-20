package com.vela.simulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vela.simulator.ui.theme.MonoStyle
import com.vela.simulator.ui.theme.VelaGreen
import com.vela.simulator.ui.theme.VelaTextDim

/**
 * 串口控制台视图：日志自动滚动区 + 连接状态。
 * 日志行前缀着色：[vela] 橙 / [qemu] 蓝灰 / [serial] 青绿 / 普通输出 绿白。
 */
@Composable
fun ConsoleView(
    lines: List<String>,
    serialConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B0B0F))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("nsh 控制台", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .background(
                            if (serialConnected) VelaGreen else VelaTextDim,
                            RoundedCornerShape(4.dp)
                        )
                )
                Text(
                    if (serialConnected) "串口已连接" else "串口未连接",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        ConsoleLog(lines, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
fun ConsoleLog(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        items(lines.size) { i ->
            val line = lines[i]
            val color = when {
                line.startsWith("[vela]") -> Color(0xFFFF9E57)
                line.startsWith("[qemu]") -> Color(0xFF8FA6B8)
                line.startsWith("[serial]") || line.startsWith("[vnc]") -> Color(0xFF7FD8C0)
                else -> Color(0xFFC8E6C9)
            }
            Text(
                line,
                style = MonoStyle,
                color = color,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}
