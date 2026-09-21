package com.vela.simulator.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vela.simulator.device.DeviceTemplate

/**
 * VNC 画面视图：把 RFB 客户端的帧缓冲按设备屏幕形状裁剪显示
 * （圆表自动圆形裁剪，手环方形圆角裁剪），无帧时显示占位。
 *
 * 触摸输入：手势坐标按等比 letterbox 映射回帧缓冲坐标后，
 * 通过 [onTouch] 上行（调用方接到 RfbClient.sendTouch）。
 */
@Composable
fun VncDisplayView(
    bitmap: Bitmap?,
    template: DeviceTemplate,
    modifier: Modifier = Modifier,
    /** 帧缓冲坐标触摸回调 (fbX, fbY, pressed)；null = 只读显示 */
    onTouch: ((x: Int, y: Int, pressed: Boolean) -> Unit)? = null,
    /** 触摸点标记（回显反馈） */
    showTouchDot: Boolean = true,
) {
    val round = template.screen.isRound
    val ar = template.screen.width.toFloat() / template.screen.height
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var touchPoint by remember { mutableStateOf<Offset?>(null) }

    // letterbox 几何：视图尺寸 -> 帧缓冲实际绘制区域
    fun geometry(bmp: Bitmap): Rect {
        val w = viewSize.width.toFloat()
        val h = viewSize.height.toFloat()
        val bar = bmp.width.toFloat() / bmp.height
        val drawW: Float
        val drawH: Float
        if (w / h > bar) { drawH = h; drawW = drawH * bar } else { drawW = w; drawH = drawW / bar }
        val left = (w - drawW) / 2
        val top = (h - drawH) / 2
        return Rect(left, top, left + drawW, top + drawH)
    }

    /** 视图坐标 -> 帧缓冲坐标（允许越界，由发送端 clamp） */
    fun mapToFramebuffer(pos: Offset): Pair<Int, Int>? {
        val bmp = bitmap ?: return null
        if (viewSize.width == 0 || viewSize.height == 0) return null
        val g = geometry(bmp)
        val fx = ((pos.x - g.left) / g.width * bmp.width).toInt()
        val fy = ((pos.y - g.top) / g.height * bmp.height).toInt()
        return fx to fy
    }

    Box(
        modifier
            .onGloballyPositioned { viewSize = it.size }
            .clip(RoundedCornerShape(if (round) 50 else 16))
            .background(Color(0xFF050508))
            .then(
                if (onTouch != null && bitmap != null) {
                    Modifier.pointerInput(bitmap) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            mapToFramebuffer(down.position)?.let { (x, y) -> onTouch(x, y, true) }
                            touchPoint = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    mapToFramebuffer(change.position)?.let { (x, y) -> onTouch(x, y, true) }
                                    touchPoint = change.position
                                    change.consume()
                                } else {
                                    mapToFramebuffer(change.position)?.let { (x, y) -> onTouch(x, y, false) }
                                    touchPoint = null
                                    break
                                }
                            }
                        }
                    }
                } else Modifier
            ),
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
                val g = geometry(bitmap)
                val img = bitmap.asImageBitmap()
                if (round) {
                    // v0.3.1: 正圆裁剪 —— 取绘制区短边为直径，保证任意帧缓冲
                    // 宽高比下圆表都不变形（此前按 letterbox 矩形 addOval，
                    // 横向帧缓冲会被裁成椭圆，即真机反馈的“圆表变椭圆”）
                    val path = Path()
                    val d = minOf(g.width, g.height)
                    path.addCircle(
                        g.centerX, g.centerY, d / 2f,
                        androidx.compose.ui.graphics.PathDirection.CW,
                    )
                    clipPath(path) {
                        drawImage(
                            img,
                            dstOffset = androidx.compose.ui.unit.IntOffset(g.left.toInt(), g.top.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(g.width.toInt(), g.height.toInt()),
                        )
                    }
                } else {
                    drawImage(
                        img,
                        dstOffset = androidx.compose.ui.unit.IntOffset(g.left.toInt(), g.top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(g.width.toInt(), g.height.toInt()),
                    )
                }
            }
            // 触摸回显点
            if (showTouchDot) {
                val p = touchPoint
                if (p != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color(0xFFFF6900).copy(alpha = 0.55f),
                            radius = size.minDimension * 0.035f,
                            center = p,
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.8f),
                            radius = size.minDimension * 0.012f,
                            center = p,
                        )
                    }
                }
            }
        }
    }
}
