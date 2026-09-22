package com.vela.simulator.vnc

/**
 * VNC 画面显示几何（纯函数，无 Android/Compose 依赖，供 VncDisplayView 与 JVM 单测共用）。
 *
 * v0.3.4 引入：把 letterbox 等比适配与触摸逆映射从 VncDisplayView 内联逻辑
 * 提取为可测试的纯函数。此前真机反馈「圆表画面是圆的、外框是椭圆的」，
 * 根因在 Compose 层（百分比圆角作用于非正方形容器），几何本身正确；
 * 提取后用单测锁定行为，防止后续重构回归。
 */

/** 帧缓冲在视图内的等比绘制矩形（letterbox fit 居中） */
data class FbRect(val left: Float, val top: Float, val width: Float, val height: Float)

object DisplayGeometry {

    /**
     * 在 viewW x viewH 的视图内等比放置 fbW x fbH 的画面（fit，短边贴边居中）。
     * 任一维度非正时返回零矩形（画面尚未就绪的防御分支）。
     */
    fun letterboxRect(viewW: Float, viewH: Float, fbW: Int, fbH: Int): FbRect {
        if (viewW <= 0f || viewH <= 0f || fbW <= 0 || fbH <= 0) return FbRect(0f, 0f, 0f, 0f)
        val bar = fbW.toFloat() / fbH
        val drawW: Float
        val drawH: Float
        if (viewW / viewH > bar) { drawH = viewH; drawW = drawH * bar } else { drawW = viewW; drawH = drawW / bar }
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        return FbRect(left, top, drawW, drawH)
    }

    /**
     * 视图内坐标 -> 帧缓冲坐标（letterbox 逆映射）。
     * 结果 clamp 到帧缓冲范围内（黑边区域触摸收敛到边缘，QEMU 端不会收到越界值）。
     */
    fun mapToFramebuffer(x: Float, y: Float, r: FbRect, fbW: Int, fbH: Int): Pair<Int, Int> {
        if (r.width <= 0f || r.height <= 0f) return 0 to 0
        val fx = ((x - r.left) / r.width * fbW).toInt().coerceIn(0, fbW - 1)
        val fy = ((y - r.top) / r.height * fbH).toInt().coerceIn(0, fbH - 1)
        return fx to fy
    }
}
