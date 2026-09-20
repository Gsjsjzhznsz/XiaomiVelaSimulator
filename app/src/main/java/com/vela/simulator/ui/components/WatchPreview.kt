package com.vela.simulator.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vela.simulator.device.DeviceTemplate
import com.vela.simulator.ui.theme.WatchBezel
import com.vela.simulator.ui.theme.WatchScreenDark
import kotlin.math.min

/**
 * 设备外观预览：按模板的屏幕形状/比例/边框绘制表身与屏幕，
 * 附带时间数字表盘（digital 风格）占位渲染。
 */
@Composable
fun WatchPreview(
    template: DeviceTemplate,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFFF6900),
    showTime: Boolean = true,
) {
    Canvas(modifier.fillMaxSize()) {
        val s = template.screen
        val round = s.isRound
        // 胶囊形（v0.2.4）：手环等竖长屏（高宽比 >= 1.8）表身全圆角胶囊，屏幕大圆角
        val capsule = !round && s.height.toFloat() / s.width >= 1.8f
        val bezel = s.bezelPx / s.width.toFloat()

        // 计算适配区域的表体矩形（保持屏幕宽高比）
        val maxW = size.width
        val maxH = size.height
        val bodyW: Float
        val bodyH: Float
        val ar = s.width.toFloat() / s.height
        if (maxW / maxH > ar) { bodyH = maxH; bodyW = bodyH * ar } else { bodyW = maxW; bodyH = bodyW / ar }
        val left = (size.width - bodyW) / 2
        val top = (size.height - bodyH) / 2

        val bodyRect = Rect(left, top, left + bodyW, top + bodyH)
        val scrRect = Rect(
            left + bodyW * bezel, top + bodyH * bezel,
            left + bodyW * (1 - bezel), top + bodyH * (1 - bezel),
        )

        fun drawBody(r: Rect) {
            when {
                round -> drawCircle(color = WatchBezel, radius = r.width / 2, center = r.center)
                capsule -> drawRoundRect(
                    color = WatchBezel,
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(r.width / 2),
                )
                else -> drawRoundRect(
                    color = WatchBezel,
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(r.width * 0.18f),
                )
            }
        }

        fun drawScreen(r: Rect) {
            when {
                round -> drawCircle(color = WatchScreenDark, radius = r.width / 2, center = r.center)
                capsule -> drawRoundRect(
                    color = WatchScreenDark,
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(r.width * 0.32f),
                )
                else -> drawRoundRect(
                    color = WatchScreenDark,
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(r.width * 0.12f),
                )
            }
        }

        drawBody(bodyRect)
        drawScreen(scrRect)

        if (showTime) {
            // 数字表盘占位
            val cx = scrRect.center.x
            val cy = scrRect.center.y
            if (round) {
                // 表圈刻度
                val rr = scrRect.width / 2
                val tick = Path()
                for (i in 0 until 12) {
                    val a = Math.toRadians((i * 30).toDouble())
                    val x1 = cx + (rr * 0.86f * kotlin.math.cos(a).toFloat())
                    val y1 = cy + (rr * 0.86f * kotlin.math.sin(a).toFloat())
                    val x2 = cx + (rr * 0.94f * kotlin.math.cos(a).toFloat())
                    val y2 = cy + (rr * 0.94f * kotlin.math.sin(a).toFloat())
                    tick.moveTo(x1, y1); tick.lineTo(x2, y2)
                }
                drawPath(tick, accent, style = Stroke(1.5f))
                // 时间刻度条
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 210f,
                    useCenter = false,
                    topLeft = Offset(cx - rr * 0.72f, cy - rr * 0.72f),
                    size = Size(rr * 1.44f, rr * 1.44f),
                    style = Stroke(3f, pathEffect = PathEffect.cornerPathEffect(4f)),
                )
                // 中心"时间"
                val barW = scrRect.width * 0.5f
                val barH = scrRect.height * 0.14f
                drawRoundRect(
                    color = Color(0xFF3A3A46),
                    topLeft = Offset(cx - barW / 2, cy - barH * 1.8f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barH / 2),
                )
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(cx - barW / 3, cy + barH * 0.8f),
                    size = Size(barW / 1.5f, barH * 0.7f),
                    cornerRadius = CornerRadius(barH / 2),
                )
            } else {
                // 方屏（手环）占位：上下状态条 + 时间块
                val pad = scrRect.width * 0.09f
                drawRoundRect(
                    color = accent.copy(alpha = 0.9f),
                    topLeft = Offset(scrRect.left + pad, scrRect.top + pad),
                    size = Size(scrRect.width - pad * 2, scrRect.height * 0.045f),
                    cornerRadius = CornerRadius(4f),
                )
                val bigW = scrRect.width - pad * 2
                val bigH = scrRect.height * 0.16f
                drawRoundRect(
                    color = Color(0xFF3A3A46),
                    topLeft = Offset(scrRect.left + pad, scrRect.center.y - bigH),
                    size = Size(bigW, bigH),
                    cornerRadius = CornerRadius(bigH / 3),
                )
                drawRoundRect(
                    color = Color(0xFF2C2C38),
                    topLeft = Offset(scrRect.left + pad, scrRect.center.y + pad),
                    size = Size(bigW, bigH * 0.8f),
                    cornerRadius = CornerRadius(bigH / 3),
                )
                drawRoundRect(
                    color = Color(0xFF2C2C38),
                    topLeft = Offset(scrRect.left + pad, scrRect.center.y + pad * 2 + bigH * 0.8f),
                    size = Size(bigW * 0.6f, bigH * 0.8f),
                    cornerRadius = CornerRadius(bigH / 3),
                )
            }
        }
    }
}
