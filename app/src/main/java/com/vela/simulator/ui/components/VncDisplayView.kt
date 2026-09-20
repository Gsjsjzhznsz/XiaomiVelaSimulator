package com.vela.simulator.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vela.simulator.device.DeviceTemplate

/**
 * VNC 画面视图：把 RFB 客户端的帧缓冲按设备屏幕形状裁剪显示
 * （圆表自动圆形裁剪，手环方形圆角裁剪），无帧时显示占位。
 */
@Composable
fun VncDisplayView(
    bitmap: Bitmap?,
    template: DeviceTemplate,
    modifier: Modifier = Modifier,
) {
    val round = template.screen.isRound
    val ar = template.screen.width.toFloat() / template.screen.height
    var viewSize = IntSize.Zero

    Box(
        modifier
            .onGloballyPositioned { viewSize = it.size }
            .clip(RoundedCornerShape(if (round) 50 else 16))
            .background(Color(0xFF050508)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(
                if (template.qemu.isCortexM)
                    "等待画面…\n（MPS2 MCU 镜像通常无显示设备，请使用控制台视图）"
                else
                    "等待画面…（若镜像无显示设备则为空）",
                color = Color(0xFF6E6E7A),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                // 等比适配绘制
                val w = size.width
                val h = size.height
                val drawW: Float
                val drawH: Float
                if (w / h > ar) { drawH = h; drawW = drawH * ar } else { drawW = w; drawH = drawW / ar }
                val left = (w - drawW) / 2
                val top = (h - drawH) / 2

                if (round) {
                    val path = Path().apply {
                        addOval(Rect(left, top, left + drawW, top + drawH))
                    }
                    clipPath(path) {
                        drawImage(
                            bitmap.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt()),
                        )
                    }
                } else {
                    drawImage(
                        bitmap.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt()),
                    )
                }
            }
        }
    }
}
