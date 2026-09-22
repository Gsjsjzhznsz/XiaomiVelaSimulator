package com.vela.simulator.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.vnc.DisplayGeometry

/**
 * VNC 画面视图：把 RFB 客户端的帧缓冲按设备屏幕形状裁剪显示
 * （圆表自动圆形裁剪，手环方形圆角裁剪），无帧时显示占位。
 *
 * 外壳（表身框）几何 = 模板屏幕宽高比的内层框，帧缓冲在框内 letterbox 居中：
 *  - 圆表（1:1）→ 正方形框 + CircleShape = 正圆表身
 *  - 胶囊（高宽比 >= 1.8）→ 窄高框 + 50% 圆角 = 胶囊表身
 *  - 方屏 → 圆角矩形表身
 * 外框形状与主页 WatchPreview 预览语义一致。
 *
 * v0.3.4 修复「圆表画面是圆的、外框却是椭圆的」：
 *  此前外壳为 fillMaxSize 整页容器 + clip(RoundedCornerShape(50))。Compose 的
 *  RoundedCornerShape(50) 是【百分比】圆角——对非正方形的整页容器（运行页
 *  画面区约 360x550）四角各削 50% 后矩形退化为椭圆/胶囊，而框内 v0.3.1 的
 *  正圆画面居中，形成「画面正圆 + 外框椭圆」的观感。修复后外壳先按模板
 *  屏幕比例收口再取形，圆表外壳恒为正圆。
 *
 * 触摸输入：手势坐标按等比 letterbox 逆映射回帧缓冲坐标（DisplayGeometry，
 * 越界收敛到边缘）后，通过 [onTouch] 上行（调用方接到 RfbClient.sendTouch）。
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
    val capsule = template.isCapsule
    val screenAr = template.screen.aspect
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var touchPoint by remember { mutableStateOf<Offset?>(null) }

    /** 表身框内坐标 -> 帧缓冲坐标 */
    fun mapToFramebuffer(pos: Offset): Pair<Int, Int>? {
        val bmp = bitmap ?: return null
        if (viewSize.width == 0 || viewSize.height == 0) return null
        val r = DisplayGeometry.letterboxRect(
            viewSize.width.toFloat(), viewSize.height.toFloat(), bmp.width, bmp.height,
        )
        return DisplayGeometry.mapToFramebuffer(pos.x, pos.y, r, bmp.width, bmp.height)
    }

    Box(modifier, contentAlignment = Alignment.Center) {
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
            // 表身框：按模板屏幕比例收口（在父容器内取最大适配尺寸并居中）
            Box(
                Modifier
                    .aspectRatio(screenAr)
                    .onGloballyPositioned { viewSize = it.size }
                    .clip(
                        when {
                            round -> CircleShape
                            // 框本身已是模板比例，50% 圆角对胶囊形语义正确
                            capsule -> RoundedCornerShape(50)
                            else -> RoundedCornerShape(16)
                        }
                    )
                    .background(Color(0xFF050508))
                    .then(
                        if (onTouch != null) {
                            Modifier.pointerInput(bitmap, screenAr) {
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
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // 等比适配绘制（帧缓冲在表身框内 letterbox 居中）
                    val g = DisplayGeometry.letterboxRect(
                        size.width, size.height, bitmap.width, bitmap.height,
                    )
                    val img = bitmap.asImageBitmap()
                    if (round) {
                        // v0.3.1: 正圆内容裁剪 —— 取绘制区短边为直径，保证任意帧
                        // 宽高比下圆表内容都不变形；圆用等边椭圆 addOval 表达，
                        // 避免低版本 Compose 缺 addCircle/PathDirection
                        val d = minOf(g.width, g.height)
                        val cx = g.left + g.width / 2f
                        val cy = g.top + g.height / 2f
                        val path = Path().apply {
                            addOval(Rect(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f))
                        }
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
}
