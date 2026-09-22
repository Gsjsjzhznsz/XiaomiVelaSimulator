package com.vela.simulator.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VNC 显示几何回归测试（v0.3.4）：
 *
 * 锁定 letterbox 等比适配与触摸逆映射行为。背景：真机反馈「圆表画面是圆的、
 * 外框是椭圆的」——根因在 Compose 层百分比圆角作用于非正方形容器（UI 层修复），
 * 但几何层此前以内联逻辑存在无覆盖；本测试把 letterbox/逆映射提为纯函数后
 * 锁定行为，防止重构回归（尤其圆表 1:1 框内 16:10 帧缓冲的中心对齐）。
 */
class DisplayGeometryTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 0.01f) {
        assertTrue("expected=$expected actual=$actual", Math.abs(expected - actual) <= eps)
    }

    @Test
    fun `letterbox 竖视图放横画面 - 宽贴边上下留黑`() {
        // 运行页典型场景：竖向画面区 360x550，帧缓冲 1280x800 (bar=1.6)
        val r = DisplayGeometry.letterboxRect(360f, 550f, 1280, 800)
        assertEquals(360f, r.width, 0.01f)
        assertEquals(225f, r.height, 0.01f) // 360 / 1.6
        assertEquals(0f, r.left, 0.01f)
        assertEquals(162.5f, r.top, 0.01f)  // (550-225)/2
    }

    @Test
    fun `letterbox 圆表正方框 - 横画面水平贴边垂直居中`() {
        // 圆表模板 466x466 表身框，帧缓冲 1280x800
        val r = DisplayGeometry.letterboxRect(466f, 466f, 1280, 800)
        assertEquals(466f, r.width, 0.01f)
        assertClose(291.25f, r.height)      // 466 / 1.6
        assertEquals(0f, r.left, 0.01f)
        assertClose(87.375f, r.top)         // (466-291.25)/2
    }

    @Test
    fun `letterbox 横视图放竖画面 - 高贴边左右留黑`() {
        val r = DisplayGeometry.letterboxRect(500f, 300f, 466, 466)
        assertEquals(300f, r.width, 0.01f)
        assertEquals(300f, r.height, 0.01f)
        assertEquals(100f, r.left, 0.01f)
        assertEquals(0f, r.top, 0.01f)
    }

    @Test
    fun `letterbox 同比例 - 全铺`() {
        val r = DisplayGeometry.letterboxRect(300f, 300f, 466, 466)
        assertEquals(300f, r.width, 0.01f)
        assertEquals(300f, r.height, 0.01f)
        assertEquals(0f, r.left, 0.01f)
        assertEquals(0f, r.top, 0.01f)
    }

    @Test
    fun `letterbox 非法输入 - 零矩形防御`() {
        val r = DisplayGeometry.letterboxRect(0f, 300f, 466, 466)
        assertEquals(0f, r.width, 0f)
        assertEquals(0f, r.height, 0f)
    }

    @Test
    fun `逆映射 - 中心对中心`() {
        val r = DisplayGeometry.letterboxRect(466f, 466f, 1280, 800)
        val (fx, fy) = DisplayGeometry.mapToFramebuffer(233f, 233f, r, 1280, 800)
        assertEquals(640, fx)
        assertEquals(400, fy)
    }

    @Test
    fun `逆映射 - 绘制区四角精确到帧缓冲四角`() {
        val r = DisplayGeometry.letterboxRect(360f, 550f, 1280, 800)
        // 左上角（黑边下方第一像素）
        val (fx0, fy0) = DisplayGeometry.mapToFramebuffer(0f, 162.5f, r, 1280, 800)
        assertEquals(0, fx0)
        assertEquals(0, fy0)
        // 右下角
        val (fx1, fy1) = DisplayGeometry.mapToFramebuffer(360f, 550f, r, 1280, 800)
        assertEquals(1279, fx1)
        assertEquals(799, fy1)
    }

    @Test
    fun `逆映射 - 黑边区域 clamp 到帧缓冲边缘`() {
        // 圆表正方框内 1280x800 letterbox：上下黑边区（top<87.375 或 bottom>378.625）
        val r = DisplayGeometry.letterboxRect(466f, 466f, 1280, 800)
        val (fxTop, fyTop) = DisplayGeometry.mapToFramebuffer(233f, 0f, r, 1280, 800)
        assertEquals(640, fxTop)
        assertEquals(0, fyTop) // 上黑边收敛到 y=0，不会发出负坐标

        val (fxBot, fyBot) = DisplayGeometry.mapToFramebuffer(233f, 465f, r, 1280, 800)
        assertEquals(640, fxBot)
        assertEquals(799, fyBot) // 下黑边收敛到 y=799
    }

    @Test
    fun `逆映射 - 零矩形防御返回原点`() {
        val (fx, fy) = DisplayGeometry.mapToFramebuffer(10f, 10f, FbRect(0f, 0f, 0f, 0f), 100, 100)
        assertEquals(0, fx)
        assertEquals(0, fy)
    }
}
